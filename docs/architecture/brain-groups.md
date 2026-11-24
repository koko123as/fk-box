# Proxy groups and health checks in the brain (stage 5)

`select`, `url-test`, `fallback` and `load-balance` run in the Rust brain
(`crates/brain-ffi/src/groups/`). sing-box never sees a group: the generated
configuration still has no `selector`/`urltest` outbound, and nothing about a
group change reaches the host except the delay tests it runs.

## How decide follows a group

- The rules are compiled against fkbrain's outbound table **followed by the
  group names**. A rule naming a group yields a virtual index
  `outbounds.len() + group`.
- Each decision snapshot (`brain::Snapshot`) holds an immutable
  `GroupTable`: per group either `To(member)` (select, url-test, fallback)
  or `Balance { strategy, members, alive }` (load-balance). After the rules,
  decide (and the DNS route, `Brain::dns_route`) follow a virtual index
  through the table to a real outbound index, `REJECT` or `REJECT-DROP`.
  Lock-free, no IO, no allocation: one more load and a few branches per hop
  (bench: "group resolution").
- A change (manual selection, a test result) builds a new table and stores a
  new snapshot that shares the router, the outbound table and the generation
  with the old one, in one atomic swap under the lifecycle lock (so a reload
  never receives the previous configuration's table). A decision sees the
  old choices or the new ones, never a mix; the generation does not move and
  the host is not called: **switching never restarts or reloads the service**.
- Nested groups work (a member may be a group, any depth); loops, unknown
  members and a group named like an outbound are refused when the profile is
  planned.
- Built-in members: `DIRECT`, `COMPATIBLE` (an empty group) and `PASS` go to
  `fk-direct`; `REJECT` / `REJECT-DROP` reject.
- `dialer-proxy` / `detour` naming a group stays refused (stage 2 rule): a
  sing-box `detour` is fixed when the instance starts, so following a group
  would mean a reload per change.

## Choosing

| type | choice | manual selection |
| --- | --- | --- |
| select | the selected member: kept across reloads by name, else the persisted one, else sing-box `default`, else the first | yes (`fkb_brain_group_select`) |
| url-test | the fastest alive member; the current one stays while its delay is within `tolerance` ms of the fastest; untested: stays; current dead and nothing measured: the first usable | **no** (AGENTS.md) |
| fallback | the first member that is not known dead (untested counts as alive, as in mihomo); all dead: the first | **no** |
| load-balance | per connection, among alive members: `consistent-hashing` (default; jump hash of the destination's last two labels, else its address, retried 5 times past dead members, then the first alive), `round-robin` (a dead member's turn goes to the next alive one), `sticky-sessions` (hash of source address + destination; mihomo's LRU cache is not reproduced, the choice is stable while the alive set is) | **no** |

A member that is a group is as healthy as what it routes to now (a
load-balance member: its best member).

Defaults when the source sets none: `url` `https://www.gstatic.com/generate_204`,
`interval` 300 s (0 disables periodic tests), `timeout` 5000 ms, `tolerance`
50 ms (sing-box's default; mihomo has none), `lazy` true,
`expected-status` any (`*`, `204`, `200/204`, `200-299` are read).

## Health checks

- Every test is the host's `probe(outbound_tag, url, timeout_ms)`: an HTTP(S)
  request through the outbound, i.e. through sing-box's protected dialer.
  The brain never opens a socket.
- A sample is stored per (outbound tag, url) and shared by every group with
  that url. Success = a response whose status matches `expected-status`;
  the delay is the time to the response headers.
- Scheduler: a brain thread (`fkb-groups`, started at attach) wakes every
  second and runs the due tests: an automatic group not tested since its
  configuration was installed (every reload re-tests), or whose interval has
  elapsed — for a `lazy` group only if decide went through it since its last
  test (an atomic flag the decision sets). Select groups are not tested on
  their own.
- A test round probes each (outbound, url) once, members of nested groups
  through what they route to now, with at most `PROBE_CONCURRENCY` (8) probes
  in flight over all groups.
- Not reproduced: mihomo's `max-failed-times` (the brain learns nothing about
  connection failures; health comes from probes only).

## Persistence

`group_store` in the start/reload options (a file path, like
`dns.fake_ip_store`) keeps manual selections: `{"version":1,"selected":{"group":"member"}}`,
rewritten (temporary file + rename) on every selection. Absent: memory only
(a reload still keeps selections by name). The path should be per profile.

## Control API (replaces the Clash API)

Exported by the brain ([header](../../crates/brain-ffi/include/fkbox_brain.h));
control path only, never per connection. The Go host wraps them as
`brainffi.Groups`, `SelectGroup`, `TestGroup`; stage 6 calls them from the
JNI bridge.

- `int32_t fkb_brain_groups(fkb_buf *out)` — the document below into a
  caller buffer; `FKB_E_BUFFER` with `len` = size needed.
- `int32_t fkb_brain_group_select(fkb_str group, fkb_str member, fkb_buf *err)` —
  `FKB_E_NOT_FOUND` (unknown group or member), `FKB_E_INVALID` (automatic
  group), `FKB_E_HOST` (the store could not be written; the selection is in
  effect anyway).
- `int32_t fkb_brain_group_test(fkb_str group, fkb_buf *err)` — test one
  group now (empty name: all), blocking until the round is done (about the
  group's timeout at most); `FKB_E_NOT_READY` without a running profile.

```json
{
  "version": 12,
  "groups": [
    {
      "name": "Auto", "type": "url-test", "selectable": false, "now": "node-b",
      "strategy": null, "url": "https://www.gstatic.com/generate_204",
      "interval_secs": 300, "tested_secs_ago": 4, "hidden": false, "icon": null,
      "members": [
        {"name": "node-a", "kind": "node", "alive": true, "delay_ms": 312},
        {"name": "node-b", "kind": "node", "alive": true, "delay_ms": 88},
        {"name": "DIRECT", "kind": "builtin", "alive": null, "delay_ms": null}
      ]
    }
  ]
}
```

`version` changes whenever a choice changes (poll and compare). `now` is null
for load-balance. `alive` is null for an untested member; `kind` is `node`,
`group` or `builtin`. `tested_secs_ago` is null until the first test.

## Tests

`crates/brain-ffi/src/groups/tests.rs` (fake probe): url-test switching and
tolerance, fallback on failure and recovery, lazy tests, load-balance spread
/ stability / round-robin / sticky, the in-flight limit, persistence and
refusal of manual selection in automatic groups, nested groups and built-in
members, and whole-snapshot swaps under 4 concurrent deciding threads with
the DNS route following. `apps/android/fkhost/e2e/groups_test.go`: a url-test
group over two real Shadowsocks nodes, measured through the host's probe,
switches when the delays swap; TUN traffic follows; the sing-box instance is
the same object throughout.
