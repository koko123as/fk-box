# The brain's DNS (stage 4)

Rust owns DNS policy: hosts, the answer cache, fake-ip, and which upstream
and outbound a query takes. Go only carries bytes: the fkdns transport hands
every query to the brain (`dns_message`), and the brain's only way to the
network is the host's `exchange(outbound_tag, upstream, message)`, which
dials through that sing-box outbound and so through the protected dialer
(`VpnService.protect`). Nothing calls the system resolver; with no brain a
query fails. Code: `crates/brain-ffi/src/dns/`, `apps/android/fkhost/plugin/
{fkdns,dnsbrain,brain,mihomo}.go`.

## Paths

| Query | From | Answer |
| --- | --- | --- |
| A/AAAA, app (port-53 hijack, `FKB_DNS_CLIENT`) | fkbrain → sing-box DNS router → fkdns | hosts; else fake-ip (TTL 1) unless the name is in `fake_ip_filter`; else real |
| A/AAAA, sing-box (domain destination of `fk-direct`, a node's server name, mihomo/Xray resolvers) | fkdns without the flag | real: hosts → cache → upstream |
| HTTPS/SVCB of a fake-ip name | app | empty NOERROR (their address hints would bypass fake-ip) |
| anything else | either | forwarded unchanged along the name's route, ID restored |
| names a rule needed (`DNS_MISS`) | decide's prefetch queue | the prefetch thread resolves A then AAAA and fills decide's cache |

Real resolution, in order: `hosts`; the answer cache (per name and type,
upstream TTL clamped to 1 s..1 h, NXDOMAIN/NODATA 30 s, 8192 entries); the
route:

1. a `policy` entry whose domains match: its upstreams, through its
   `outbound` (or through the outbound the rules pick);
2. a node's own server name: the direct upstreams via `fk-direct` (a node
   cannot carry the lookup of its own address);
3. the routing rules, decided on the name alone (no cache, IP rules
   unknown): `fk-direct` → direct upstreams via `fk-direct`; a node →
   proxy upstreams through that node; REJECT / `fk-block` → NXDOMAIN without
   asking anyone.

Upstreams are tried in order, the query's time split between them (at
least 800 ms each while time remains); SERVFAIL/REFUSED or an error moves
on, NOERROR/NXDOMAIN is the answer. Every answer also fills decide's cache
(`DnsCache`, both families merged).

## Fake-ip

- One slot per name gives both addresses: slot n is `fake_ip_range + n` and
  `fake_ip_range6 + n`; slots 0, 1 and the last IPv4 address are never used.
  When the pool is full the oldest allocation is recycled (a cursor walks
  the slots). 198.18.0.0/15 holds 131,069 names.
- fkbrain calls `fakeip_reverse` for every connection to an IP before it
  sniffs and decides: an allocated address becomes the name
  (`Destination` = name, `RouteOriginalDestination` = the app's address, so
  sing-box's connection manager maps UDP replies back); a stale one (inside
  the range, unknown) is refused; anything else is unchanged. decide also
  maps a fake destination it receives (`dst` cleared, domain set).
- The outbound then dials the name: proxies resolve it remotely; `fk-direct`
  resolves it through fkdns for real; mihomo, whose packet connections take
  IP addresses only, resolves it through the brain
  (`ListenPacketWithDestination`) and sing-box translates between the two
  addresses; Xray sends a domain UDP destination as one `core.Dial` stream.
- `fake_ip_store`: the table is saved (temp file + rename) every 30 s when
  it changed and on stop, and loaded at start when the ranges match, so
  addresses apps cached survive a restart of the service.

## Settings (`dns` object of the profile options)

The subscription's own `dns:` / `nameserver-policy` stay ignored
(brain-config.md). Every key is optional; unknown keys are refused.

| Key | Default | Notes |
| --- | --- | --- |
| `direct_upstreams` | `udp://223.5.5.5:53`, `udp://119.29.29.29:53` | names the rules send direct; sent from the device via `fk-direct` |
| `proxy_upstreams` | `tcp://1.1.1.1:53`, `tcp://8.8.8.8:53` | through the node the rules picked; TCP because a node may not relay UDP |
| `fake_ip` | `true` | `false`: apps get real answers |
| `fake_ip_range` | `198.18.0.0/15` | prefix 8..29 |
| `fake_ip_range6` | `fc00::/18` | `null`: AAAA of fake names is empty |
| `fake_ip_filter` | `localhost`, `+.lan`, `+.local`, `+.localdomain`, `+.home.arpa`, `+.in-addr.arpa`, `+.ip6.arpa`, `time.*.com`, `+.ntp.org`, `+.msftconnecttest.com`, `+.msftncsi.com`, `stun.*` | replaces the default list |
| `hosts` | `localhost` → 127.0.0.1, ::1 | name → address or list; replaces the default |
| `policy` | none | `[{"domains": [...], "upstreams": [...], "outbound": "tag"?}]` |
| `fake_ip_store` | none (memory only) | a file path in the app's files directory |
| `timeout_ms` | 5000 | 100..60000, when the caller gives none |

Patterns: `+.example.com` (the name and subdomains), `*.example.com`
(subdomains), mihomo wildcards (`*`, `?`), or an exact name. Upstreams:
`udp://`, `tcp://`, `tls://` (853), `https://` (443, path `/dns-query`)
with an IP address (a name would need DNS to reach DNS) and, for tls/https,
`?sni=` for the certificate name (the IP otherwise). DoT/DoH are Go's
`crypto/tls` / `net/http` over the outbound's dialer (system roots); DoH
connections are pooled per outbound until the configuration changes.

Why these defaults: fake-ip means a connection always reaches decide with
its name (no `DNS_MISS` for domain rules, no on-device resolution of
proxied names, no DNS leak for them); the direct resolvers are the ones a
mainland-China network answers best for direct names; proxied names, when
they need a real answer (fake-ip off, filtered names, IP rules), are asked
through the proxy.

## Not covered

- PTR answers for fake addresses; DNS64; ECS.
- A name whose IP rules matter is first decided without its address (rule
  skipped, `DNS_MISS`), then prefetched; the first connection may take
  another route than later ones (brain-dns-rules.md).
- The routing rules decide a query's route on the name alone: a domestic
  name that only a GEOIP rule would send direct is asked through the proxy.
