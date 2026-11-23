# Single kernel + Rust brain: configuration import and generation (stage 2)

Stage 2 of the single-kernel design ([brain-ffi.md](brain-ffi.md) is the FFI
contract and the router, [brain-dns-rules.md](brain-dns-rules.md) the DNS
dependent rules). The Rust brain reads a subscription into one model and
writes the minimal sing-box configuration the kernel runs. Code:
`crates/client-core/src/config/brain/` (`config::brain`). Nothing is wired
into the Android app yet; `main`'s runtime behaviour is unchanged.

Versions checked against: sing-box 1.15.0-alpha.13 (`option/`,
`include/registry.go`), mihomo 1.19.34 (`adapter/outbound/*.go`,
`adapter/outboundgroup/parser.go`, `common/convert/`), Xray-core 26.11.9
(`infra/conf/`), sing-quic at the commit sing-box pins (`hysteria.ParsePorts`).

## Model

| Type | Content |
| --- | --- |
| `Node` | `tag`, `protocol` (as the source named it), `server`, `port` (0 for ranges), `transport`, `security` (none / TLS / REALITY), `detour` (chained proxy, always a node), `spec` |
| `Spec` | `Native` (sing-box outbound object), `NativeEndpoint` (sing-box endpoint, WireGuard/MASQUE), `Mihomo` (mihomo proxy map as written, `dialer-proxy` moved to `detour`), `Xray` (Xray outbound object) |
| `Group` | `name`, `kind` (select / url-test / fallback / load-balance), resolved `members`, `params` (url, interval s, tolerance ms, timeout ms, lazy, max-failed-times, strategy, expected-status, selector default, idle timeout, interrupt, disable-udp, hidden, icon) |
| `Rules` | the stage 1 `RouterIr` (rules, sub-rules, rule sets, final) and sing-box `sniff` host directives |
| `ImportResult` | format, nodes, groups, rules, `notices` |
| `Notice` | `IgnoredDns` ("已忽略订阅 DNS 配置"), `IgnoredSettings`, `Downgraded`, `SkippedLink`, `EmptyGroup`, `HostDirective` |

`config::brain::import(content, loaders)` detects the format: sing-box JSON
(starts with `{`), Clash/mihomo YAML (a mapping with `proxies`,
`proxy-groups`, `proxy-providers` or `rules`), a share-link list, or Base64
of a share-link list. Rule providers / rule sets are read through the
caller's loaders (the engine does no I/O). `validate` then checks: names
unique and not reserved (`fkbrain`, `fk-direct`, `fk-block`, `fk-tun`,
`fkdns`), detours naming nodes without loops, group members and rule targets
existing (nodes, groups, `DIRECT`, `REJECT`, `REJECT-DROP`, `PASS`,
`COMPATIBLE`), no group loops.

## Protocol → implementation

A node is native when sing-box has the protocol and **every** key of the
mihomo map has a sing-box field (keys mihomo would leave at their zero value
do not count; mihomo reads `_` in a key as `-`, so does the import). One key
without an equivalent moves the whole node to the `mihomo` plugin, with a
`Downgraded` notice naming the key or value. Nothing is dropped.

| Source protocol | Native sing-box | Plugin | Conditions for native / notes |
| --- | --- | --- | --- |
| ss | `shadowsocks` | mihomo | methods of option/shadowsocks.go (`dummy` → `none`); plugin `obfs` → `obfs-local` with `obfs=…;obfs-host=…` (mihomo's default host `bing.com`); `udp-over-tcp` → `udp_over_tcp` with mihomo's default version 1; `smux` → `multiplex`. `v2ray-plugin`, `shadow-tls`, `restls`, `kcptun`, `gost-plugin` → mihomo |
| ssr | — (sing-box 1.6 removed it; `include/registry.go` stub) | mihomo | always |
| vmess | `vmess` | mihomo | `cipher` ∈ auto/none/zero/aes-128-cfb/aes-128-gcm/chacha20-poly1305; packet encoding as mihomo decides (packetaddr / xudp / none); transports tcp, ws, httpupgrade (`v2ray-http-upgrade`), grpc (service name only), h2 (only with TLS → sing-box `http`). `http` (TCP header), `mekya`, `mkcp`, `xhttp`, `fingerprint`, `certificate`/`private-key`, `ech-opts`, `shadow-tls-opts`, `restls-opts`, `jls-opts`, `tlsmirror-opts`, `name-cert-verify`, `ip-version` → mihomo |
| vless | `vless` | mihomo, xray | as vmess; `flow` only `xtls-rprx-vision`; `encryption` only empty/`none` (mihomo default XUDP is written explicitly); REALITY `support-x25519mlkem768` → mihomo. From share links: `type=xhttp`/`splithttp`, VLESS encryption, REALITY with `pqv` → xray |
| trojan | `trojan` | mihomo, xray | ws/grpc transports; `ss-opts` enabled → mihomo. Share link `type=xhttp` → xray |
| hysteria2 | `hysteria2` | mihomo | `ports` → `server_ports` (`a:b`; a single port `p:p`, as sing-quic's `ParsePorts` needs); `hop-interval` (s, range → `hop_interval_max`, minimum 5 as mihomo); `up`/`down` whole Mbps/Gbps; `obfs` salamander; `bbr-profile`. `fingerprint` (pinSHA256), `realm-opts`, `obfs` gecko, `cwnd`, `udp-mtu`, QUIC windows → mihomo |
| hysteria | `hysteria` | mihomo | `protocol` udp only; `auth-str`, `auth`, `obfs`, `up`/`down`, `disable-mtu-discovery` → `disable_path_mtu_discovery`. faketcp/wechat-video, `recv-window*`, `fast-open` → mihomo |
| tuic | `tuic` | mihomo | v5 (`uuid`/`password`) only; `congestion-controller`, `udp-relay-mode`, `reduce-rtt` → `zero_rtt_handshake`, `heartbeat-interval` (ms), `udp-over-stream`, `disable-sni`. v4 `token`, `ip`, `request-timeout`, … → mihomo |
| wireguard | endpoint `wireguard` (the outbound is a 1.13 removal stub) | mihomo | one peer: `ip`/`ipv6` (+/32, /128), `private-key`, `public-key`, `pre-shared-key`, `allowed-ips` (default 0.0.0.0/0, ::/0), `reserved` (byte list), `mtu`, `workers`, `persistent-keepalive`. `peers`, `amnezia-wg-option`, `remote-dns-resolve`/`dns`, `ip-stack`, `reserved` as a string → mihomo |
| anytls | `anytls` | mihomo | idle session options, `client-metadata`; sing-box always offers UDP (mihomo `udp` is not a protocol field) |
| socks5 | `socks` (version 5) | mihomo | TLS → mihomo |
| http | `http` | mihomo | TLS, `headers` |
| ssh | `ssh` | mihomo | `private-key` as PEM content; a file path → mihomo |
| direct, reject | `direct`, `block` | — | dial fields only |
| snell, mieru, sudoku, shadowquic, gost-relay, masque, trusttunnel | — | mihomo | always (stage 3 checks each for mihomo tunnel/global state) |
| tailscale, openvpn, zerotier, easytier | — | — | **import error** (decided 2026-11-24) |
| dns, rematch, relay | — | — | **import error** (mihomo DNS tunnel / rule re-run / removed) |

Dial fields every native mapping takes: `tfo` → `tcp_fast_open`, `mptcp` →
`tcp_multi_path`, `interface-name` → `bind_interface`, `routing-mark` →
`routing_mark`, `dialer-proxy` → `detour`; `ip-version` other than `dual` →
mihomo plugin (address family belongs to the brain's resolver).

Share links are read the way mihomo's `ConvertsV2Ray` reads them (into a
mihomo map, then the table above), except: an unreadable line is a
`SkippedLink` notice (no readable line at all is an error); `wireguard://` /
`wg://` (v2rayN form) is added; `mieru://` (mieru's binary profile) is an
error naming `mierus://`; an empty name becomes `server:port`; no random
`User-Agent` header is invented; `allowInsecure`/`insecure` is honoured for
VLESS/VMess/Trojan; `https://` proxy links keep certificate verification on;
an unknown Shadowsocks plugin is an error; REALITY `spx` without the Xray path
is reported (`IgnoredSettings`), it only drives Xray's spider. KCP/QUIC
link transports are errors.

sing-box JSON outbounds are kept as written (types of the 1.15 registry:
direct, block, socks, http, shadowsocks, snell, vmess, trojan, naive, tor,
ssh, shadowtls, vless, anytls, hysteria, hysteria2, tuic; endpoints
wireguard, masque). `selector`/`urltest` become groups. A `domain_resolver`
pointing at a subscription DNS server is pointed at `fkdns`. Errors: the
`wireguard` outbound (removed in 1.13), `shadowsocksr`, `dns` outbound,
unknown types, and the tailscale/openvpn/openconnect endpoints (decided
2026-11-24).

## Groups

mihomo (1.19.34, no providers): members are `proxies`, then with
`include-all`/`include-all-proxies` every proxy name sorted (mihomo sorts
`AllProxies`), filtered by `filter` (split on backquote; a name enters once);
then `exclude-filter` and `exclude-type` (mihomo adapter type names,
case-insensitive) apply to every member. `filter` without `include-all` has no
effect, as in mihomo. Patterns are mihomo's regexp2 (.NET); one Rust `regex`
cannot compile (lookaround, backreferences) is an import error. An empty
group gets an `EmptyGroup` notice (mihomo would use `COMPATIBLE`).
`relay` groups, `use` and `include-all-providers` are errors;
`interface-name`/`routing-mark`/`dialer-proxy` on a group are ignored with a
notice (mihomo ignores them and logs an error); `empty-fallback` and unknown
group keys are reported (`IgnoredSettings`).

sing-box `selector` → select (`default` must be a member), `urltest` →
url-test (`interval`/`idle_timeout` Go durations → seconds, `tolerance` ms).
Unknown fields are errors.

## Chained proxies

mihomo `dialer-proxy` and sing-box `detour` become `Node.detour`; the
generator writes it as the sing-box `detour` of the native outbound, of the
endpoint, or of the `mihomo`/`xray` plugin outbound (whose dialer is the
sing-box dialer, so the chain stays inside sing-box). A detour naming a
group is an error until the brain runs groups (stage 5); `DIRECT`, an unknown
name and loops (`a -> b -> a`) are errors.

## Generated configuration

```json
{
  "dns": {"servers": [{"type": "fkdns", "tag": "fkdns"}], "final": "fkdns"},
  "inbounds": [{"type": "tun", "tag": "fk-tun", "address": ["172.19.0.1/30", "fdfe:dcba:9876::1/126"], "auto_route": true}],
  "endpoints": [ …WireGuard nodes… ],
  "outbounds": [
    {"type": "fkbrain", "tag": "fkbrain", "outbounds": ["node-1", "…", "fk-direct", "fk-block"]},
    …nodes in import order…,
    {"type": "direct", "tag": "fk-direct"},
    {"type": "block", "tag": "fk-block"}
  ],
  "route": {"final": "fkbrain", "auto_detect_interface": true, "default_domain_resolver": "fkdns"}
}
```

- No `route.rules`, `route.rule_set`, `dns.rules`, `selector` or `urltest`.
- TUN defaults are the Android service's today (`core_profile.go`,
  `mobile-ffi` VPN routing): the two addresses and `auto_route`; sing-box's
  default MTU and stack. `GenerateOptions` can set `mtu`, `strict_route`,
  `stack` and a `log.level`.
- `auto_detect_interface` switches on the platform's
  `AutoDetectInterfaceControl` (VpnService.protect) for every dialer,
  plugin dialers included.
- `default_domain_resolver: fkdns`: with only `fkdns` in `dns.servers`, a
  dialer without a resolver would fall back to the default transport
  (`common/dialer/dialer.go`).
- Without route rules sing-box neither sniffs nor hijacks DNS; fkbrain does
  both itself (stage 3, see [brain-ffi.md](brain-ffi.md#host-prerequisites)).

## Option contract of the plugin types

All four are registered through the public registries
(`outbound.Register`, `dns.RegisterTransport`, `box.Context`), as the stage 0
probe did. sing-box decodes their options strictly (unknown keys fail).

| Type | Kind | Options |
| --- | --- | --- |
| `fkbrain` | outbound | `outbounds`: `[]string`, the table `decide` indexes into (`fkb_decision.outbound`); nodes in import order, then `fk-direct`, `fk-block`. Non-empty, no duplicates. It implements `adapter.ConnectionHandler` and is `route.final`. |
| `fkdns` | DNS transport | none in this contract (stage 4 adds what the brain's resolver needs). |
| `mihomo` | outbound | sing-box `DialerOptions` (`detour`, bind/mark/TFO fields) + `proxy`: the mihomo proxy map (`name`, `type`, …; never `dialer-proxy`). |
| `xray` | outbound | sing-box `DialerOptions` + `outbound`: an Xray outbound object (`protocol`, `settings` in the simplified form `address`/`port`/`id`/`encryption`/`flow`, `streamSettings` with `network`, `security`, `tlsSettings`/`realitySettings`, `xhttpSettings` (link `extra` kept whole), `wsSettings`, `httpupgradeSettings`, `grpcSettings`). Never `allowInsecure` (Xray 26 refuses it). |

## Errors and downgrades (summary)

Import errors (the whole import fails): refused node/endpoint types (above),
`proxy-providers`, group `use`/`include-all-providers`, `relay`, uncompilable
group filters, detour to a group / unknown / `DIRECT` / loops, group loops,
unknown group members or rule targets, duplicate or reserved names, unknown
proxy/outbound/endpoint types, a node without server, the rule engine's own
errors (brain-ffi.md), an XHTTP/REALITY share link asking for
`allowInsecure`/`insecure` (Xray 26 removed it; use `pcs`/`vcn`), a link list
without one readable link.

Downgrades to the mihomo plugin (`Downgraded` notice naming the key): see the
table. Notices without loss of a node: `IgnoredDns` (`dns`, `hosts`,
sing-box `dns`, `domain_resolver` rewritten), `IgnoredSettings` (client
settings such as ports, `mode`, `tun`, `inbounds`, `log`; REALITY `spx`;
group keys mihomo ignores), `SkippedLink`, `EmptyGroup`, `HostDirective`
(sing-box `sniff`).

## Verification

`.github/workflows/brain.yml` runs the `config::brain` tests (per-protocol
input → output snapshots, error paths, ignored DNS, chained proxies, loop
detection, the generated configuration) and writes every generated
configuration to a directory; `probes/stage2` (own `go.mod`, the stage 0
module graph) registers `fkbrain`/`fkdns`/`mihomo`/`xray` as option-only
placeholders and runs sing-box 1.15.0-alpha.13's parser and `box.New` on each
(tags `with_quic,with_wireguard,with_utls,with_gvisor`). Not covered: naive
(needs cronet), running the plugins (stage 3), mihomo/Xray accepting their
maps (stage 3 builds them with `adapter.ParseProxy` / Xray `infra/conf`).
