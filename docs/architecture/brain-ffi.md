# Single kernel + Rust brain: FFI contract and router (stage 1)

Target design (stage 0 probe: branch `arch/stage0-probe`, draft PR #63): one
sing-box kernel does TUN, forwarding, protocol outbounds and protected
dialing; a Rust "brain" owns rules, DNS, groups and health checks. sing-box's
route sends every connection to the **fkbrain** plugin outbound (it
implements `adapter.ConnectionHandler`), which calls the brain's `decide`
once, synchronously, when the connection is set up, then hands it to the
outbound the brain picked. mihomo and Xray protocols are sing-box plugin
outbound types. The host builds its own `box.Context` and
`daemon.NewStartedService` (option A, chosen by the user: no libbox
`CommandServer`, no patch to sing-box/mihomo/Xray).

Stage 1 (this document) adds only the contract and the Rust side:

- `crates/brain-ffi` (`fkbox-brain-ffi`): the C ABI
  ([`include/fkbox_brain.h`](../../crates/brain-ffi/include/fkbox_brain.h)),
  its `#[repr(C)]` mirror, the brain's state (rule snapshot, DNS cache, host
  table) and the wrappers over the host's callbacks. Static library
  `libfkbox_brain_ffi.a` for cgo, rlib for tests and the bench.
- `crates/client-core/src/rules/engine/`: the rule engine (unified
  representation, Clash/mihomo and sing-box importers, compiled matchers,
  `decide`).

Nothing is wired into the Android app or the Go code yet; `main`'s runtime
behaviour is unchanged. The host side (Go: `box.Context`, the fkbrain
outbound, the cgo bridge) is later work.

## Why a crate of its own

`fkbox-mobile-ffi` is the UI/session layer of the platform shell (JSON call
surface for the Kotlin host, command-session plan). The brain ABI is the
kernel's hot path: a C ABI linked into the Go side, fixed structs, its own
threading and memory rules. A separate crate keeps either from pulling the
other's dependencies or ABI into its library, and lets the Go host link the
brain without the UI. The rule engine itself is platform-neutral business
logic and lives in `client-core` (`rules::engine`), next to the existing
per-core routing model (`rules::routing`), which it does not replace yet.

## Calls

| Direction | Function | When | May block |
| --- | --- | --- | --- |
| host → brain | `fkb_brain_attach(host, brain)` | once, at startup, before anything else | no |
| host → brain | `brain.decide(meta, out)` | every new connection (fkbrain `NewConnectionEx` / `NewPacketConnectionEx`) | **never** |
| host → brain | `brain.resolve(query, out)` | the DNS transport plugin (stage 4; returns `FKB_E_UNIMPLEMENTED` in ABI 1) | yes, up to `timeout_ms` |
| brain → host | `start` / `reload(config_json, generation, err)` | configuration changes | yes |
| brain → host | `stop(err)` | shutdown | yes |
| brain → host | `probe(outbound_tag, url, timeout_ms, out, err)` | health checks / delay tests | yes, up to `timeout_ms` |
| brain → host | `exchange(outbound_tag, upstream, message, timeout_ms, response, err)` | the brain's DNS upstream queries through an outbound | yes, up to `timeout_ms` |
| brain → host | `stats(out)` | dashboards | briefly |

"Rust registers its function pointers" (as in the stage 0 probe) is
`fkb_brain_attach`: the brain writes its `fkb_brain_api` table into memory the
host provides and keeps a copy of the host's `fkb_host_api`.

## Structures (ABI 1, 64-bit little-endian)

Every struct is fixed; the header checks sizes and offsets with
`_Static_assert`, the Rust mirror (`src/abi.rs`) with `const` asserts, and
both sides compare at run time (`abi_version` and `size` fields;
`fkb_struct_size(FKB_STRUCT_*)`, which the Go side should check against its
own `unsafe.Sizeof` at init, as the stage 0 probe did).

`fkb_conn_meta` (576 bytes, host memory):

| Offset | Field | Meaning |
| --- | --- | --- |
| 0 | `abi_version` u32, `size` u32 | `FKB_ABI_VERSION`, `sizeof` |
| 8 | `network` u8 | `FKB_NET_TCP` / `UDP` / `UNKNOWN` |
| 9 / 10 | `dst_ip_version`, `src_ip_version` u8 | 0 (none), 4, 6 |
| 11 | `protocol` u8 | sniffed protocol (`FKB_PROTO_*`: http, tls, quic, dns, bittorrent, stun, dtls, ssh, rdp, ntp) |
| 12 / 14 | `src_port`, `dst_port` u16 | |
| 16 / 32 | `src_ip[16]`, `dst_ip[16]` | IPv4 in the first 4 bytes; IPv4-mapped IPv6 is read as IPv4 |
| 48 | `uid` i32 | Android uid, -1 unknown |
| 52 | `inbound_index` u32 | index into the running configuration's inbound table, `UINT32_MAX` unknown |
| 56 | `domain_len` u16, `domain_source` u8, `flags` u8 | domain from an FQDN destination or the sniffer (host / SNI); flags: package/process name truncated |
| 60 | `package_len`, `process_len` u8, `reserved` u16 | |
| 64 | `domain[256]` | UTF-8, not NUL-terminated (the brain lowercases, strips the trailing dot and applies IDNA) |
| 320 / 448 | `package_name[128]`, `process_name[128]` | Android package, desktop process name |

Strings are inline arrays, not pointers: a Go struct holding Go pointers may
not be passed to C, and inline arrays make the record one flat copy (the
stage 0 probe measured the 304-byte predecessor; this one adds the package,
process and protocol fields).

`fkb_decision` (16 bytes, host memory): `action` u16 (`ROUTE`, `REJECT`,
`REJECT_DROP`, `HIJACK_DNS`), `flags` u16 (`FKB_DECISION_DNS_MISS`),
`outbound` u32 (index into the outbound table of `generation`), `rule_index`
u32 (top-level rule, `FKB_RULE_FINAL`), `generation` u32.

Others: `fkb_str` (borrowed bytes), `fkb_buf` (caller-owned output buffer),
`fkb_resolve_query` (272) / `fkb_resolve_result` (288, up to 16 addresses),
`fkb_probe_result` (16), `fkb_stats` (56), `fkb_brain_api` (24),
`fkb_host_api` (64; `ctx` is the host's opaque handle, a
`runtime/cgo.Handle` value, never dereferenced by the brain).

## Memory ownership

- **Whoever allocates frees; nothing crosses the boundary to be freed by the
  other side.** There is no free function in the ABI.
- Inputs (`fkb_conn_meta`, `fkb_resolve_query`, every `fkb_str`) are
  **borrowed for the duration of the call**. The callee copies what it keeps.
  The host must not change the record while the call runs.
- Outputs go into memory the **caller** owns: `fkb_decision`,
  `fkb_resolve_result`, `fkb_probe_result`, `fkb_stats` are caller structs;
  variable-size outputs use `fkb_buf` (caller allocates `cap` bytes, callee
  writes at most `cap` and sets `len`). When a reply does not fit, the callee
  sets `len` to the size it needs and returns `FKB_E_BUFFER`; the brain's
  `exchange` wrapper retries once with that size (up to 65535). Error
  buffers (`err`, 1024 bytes from the brain) are truncated instead.
- `fkb_brain_attach` copies the host table; the host may free its copy
  afterwards. The brain table is written into host memory.
- `ctx` stays valid from attach until the process ends (or until a later
  attach replaces the table).

## Threading

- `decide` is called concurrently from many goroutines (each on its own OS
  thread during the cgo call). It reads the current rule snapshot through an
  `ArcSwap` load (no lock), evaluates an immutable compiled `Router`, and
  reads the DNS cache through sharded read locks held for one hash probe. It
  does no I/O, no allocation on the common path (an IDNA conversion or an
  uppercase domain allocates once), and never waits on another thread.
- Reload compiles the new `Router` off the decide path, then swaps the
  snapshot atomically. A decide running during the swap finishes with the
  snapshot it loaded; its decision carries that snapshot's `generation`.
- Brain → host calls come from the brain's own threads, never from inside
  `decide`. The host's functions must be safe to call from any thread (cgo
  callbacks on non-Go threads are fine). `start`/`reload`/`stop` are
  serialized by the brain.
- The DNS cache's prefetch hint (`DnsView::prefetch`) uses `try_lock`: a
  contended queue drops the hint rather than wait.

## Generations and reload

1. The brain compiles the new configuration's `Router` against the outbound
   and inbound order of the sing-box JSON it is about to hand over.
2. It takes a fresh generation number (every attempt, so a failed attempt
   never shares a number with the next one) and calls `start`/`reload` with
   it. The host applies the JSON and registers the outbound/inbound tables
   under that generation.
3. Only when the host returns `FKB_OK` does the brain install the snapshot.
   On failure the previous snapshot stays and decisions keep naming the old
   generation.
4. The host keeps the previous generation's table until decisions stop naming
   it (keeping the last two is enough: a decide that loaded the old snapshot
   returns within microseconds). A decision naming an unknown generation is
   treated like an error.

## Errors and timeouts

| Code | Meaning | Host's action for `decide` |
| --- | --- | --- |
| `FKB_OK` 0 | success | use the decision |
| `FKB_E_INVALID` -1 | NULL, wrong `abi_version`/`size`, malformed field (bad UTF-8, unknown enum value, length beyond the array) | fallback |
| `FKB_E_NOT_READY` -2 | no snapshot (before start, after stop) | fallback |
| `FKB_E_PANIC` -3 | internal failure, caught at the boundary (`catch_unwind`; the release profile unwinds) | fallback |
| `FKB_E_UNIMPLEMENTED` -4 | not in this build (resolve in ABI 1; a NULL host entry) | — |
| `FKB_E_TIMEOUT` -5 | the host gave up after `timeout_ms` | — |
| `FKB_E_BUFFER` -6 | output buffer too small, `len` = size needed | — |
| `FKB_E_HOST` -7 | host failure, message in `err` | — |
| `FKB_E_NOT_FOUND` -8 | unknown outbound tag | — |

The fallback for a failed `decide` is the host's configured policy (reject,
or a fixed outbound), decided when the Go side is written; the brain never
returns a partial decision.

`decide` has no timeout: it is bounded computation (see the bench). Calls
that may block take `timeout_ms`; the host enforces it (its own context) and
returns `FKB_E_TIMEOUT`. There is no cancellation in ABI 1; a later version
can add a request id.

## Host prerequisites

- Sniff before `decide` (sing-box `sniff` rules run in the host; the
  sing-box importer returns them as host directives).
- Fill the meta from `adapter.InboundContext`: network, source, destination
  (FQDN → `domain` with `FKB_DOMAIN_FQDN`, otherwise the address and the
  sniffed domain), `ProcessInfo` (uid, package), inbound index, sniffed
  protocol.
- The outbound and inbound tables are the order of the JSON handed to
  `start`/`reload` for that generation.

## Rule engine

The engine compiles each program (main rules, each sub-rule list) into:

- a domain index: exact names and suffixes in hash maps keyed per label
  boundary (a flattened label trie: one probe per label of the queried name),
  keywords in one Aho-Corasick automaton, regexes in one `RegexSet`, wildcards
  as globs;
- CIDR prefix tries (IPv4, IPv6) for source, destination and
  destination-through-the-DNS-cache;
- the remaining ("generic") rules, evaluated in order.

Every index entry carries its rule's position; a lookup gives the earliest
matching indexed rule, and only generic rules before it are evaluated, so
first-match semantics hold. Rules whose condition is a union of domain and IP
entries (`DOMAIN*`, `IP-CIDR*`, `GEOSITE`, `GEOIP` from `geoip.dat`,
domain/ipcidr rule sets, `OR` of those) are indexed; `MATCH` sets the
candidate. Conditions evaluate to true, false or unknown (see
[brain-dns-rules.md](brain-dns-rules.md)).

Supported: `DOMAIN`, `DOMAIN-SUFFIX`, `DOMAIN-KEYWORD`, `DOMAIN-REGEX`,
`DOMAIN-WILDCARD`, `GEOSITE` (with `@attr`), `GEOIP` / `SRC-GEOIP`
(`geoip.dat` or `geoip.metadb`; `LAN` built in), `IP-CIDR` / `IP-CIDR6`
(`no-resolve`, `src`), `SRC-IP-CIDR`, `DST-PORT` / `SRC-PORT` (lists and
ranges), `NETWORK`, `PROCESS-NAME` (also matches the Android package, as
mihomo on Android), `UID`, `IN-NAME`, `RULE-SET` (`no-resolve`, `src`),
`AND` / `OR` / `NOT`, `SUB-RULE`, `MATCH`; targets `REJECT`, `REJECT-DROP`,
`PASS` and outbound names. sing-box: `domain`, `domain_suffix`,
`domain_keyword`, `domain_regex`, `ip_cidr`, `ip_is_private`,
`source_ip_cidr`, `source_ip_is_private`, `port`, `port_range`,
`source_port`, `source_port_range`, `network`, `ip_version`, `protocol`,
`process_name`, `package_name`, `package_name_regex`, `user_id`, `inbound`,
`rule_set` (+ `rule_set_ip_cidr_match_source`), `invert`, logical rules;
actions `route`, `reject` (`default`/`drop`), `hijack-dns`; `sniff` as a host
directive; `route.final` (else the first outbound).

Rule-set formats: mihomo `yaml`, `text`, `mrs` (domain and ipcidr); sing-box
`source` (JSON) and `binary` (SRS v1–v5, decoded back into source rules).

Not supported (import error naming the rule or field): mihomo `IP-ASN`,
`SRC-IP-ASN`, `IP-SUFFIX`, `SRC-IP-SUFFIX`, `IN-PORT`, `IN-TYPE`,
`IN-USER`, `DSCP`, `PROCESS-PATH`, `PROCESS-PATH-REGEX`,
`PROCESS-PATH-WILDCARD`, `PROCESS-NAME-REGEX`, `PROCESS-NAME-WILDCARD`,
`REMATCH-NAME`, `NETWORK-TYPE`, `SCRIPT`, nested `RULE-SET` inside a
provider, MRS classical (mihomo has none); sing-box `auth_user`, `client`,
`clash_mode`, `wifi_ssid`, `wifi_bssid`, `network_type`,
`network_is_expensive`, `network_is_constrained`, interface addresses,
`process_path`, `process_path_regex`, `user`, `source_mac_address`,
`source_hostname`, `preferred_by`, `rule_set_ip_cidr_accept_empty`,
`adguard_domain`, `query_type`, legacy `geoip`/`geosite`/`source_geoip`,
actions `resolve`, `route-options`, `bypass`, route options on a route
action, `reject` `no_drop`, unknown fields. SRS items AdGuard, network type,
interface addresses and `query_type` are refused when the file is read.

Known divergences: sing-box's rule-set merging (`matchWithOuterGroups`) is
modelled as "the rule's `rule_set`s join its destination-address group",
exact for sets whose rules hold destination-address items only (geosite /
geoip style); sets mixing other groups can differ. mihomo `RULE-SET,…,src`
swaps IP and port conditions of the set (the domain stays).

## Verification

`.github/workflows/brain.yml` (pull requests touching `crates/`, `Cargo.*` or
the workflow; `ubuntu-24.04-arm`): `rustfmt --check` on the new files,
`cargo test` for `fkbox-client-core` and `fkbox-brain-ffi`, the header
compiled as C11 with its static asserts, the static library linked into a C
smoke test (`crates/brain-ffi/tests/c/abi_smoke.c`), the real geo files of
the pinned meta-rules-dat commit (sha256-checked, downloaded into the runner
only) for a format check, and the decide bench
(`cargo bench -p fkbox-brain-ffi --bench decide`) with its numbers in the
job summary.
