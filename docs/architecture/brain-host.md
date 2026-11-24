# Single kernel + Rust brain: the Go host and the plugins (stage 3)

Stage 3 of the single-kernel design ([brain-ffi.md](brain-ffi.md) is the C
ABI and the router, [brain-config.md](brain-config.md) the generated
configuration). Code: `apps/android/fkhost/` (Go module
`github.com/koko123as/fk-box/apps/android/fkhost`, the same module graph
as the stage 0/2 probes, versions of `version.properties`; nothing is
replaced or patched). Not wired into the Android app yet (stage 6); the
existing libbox paths, including capture/MITM/HTTP rewrite, are untouched
and not part of the new path (to be decided in stage 6).

| Package | Content |
| --- | --- |
| `plugin` | `fkbrain`, `fkdns`, `mihomo`, `xray` and their registration; the Go mirror of `fkb_conn_meta`/`fkb_decision`; counters |
| `host` | the host: `box.Context` on `include.*Registry()` + the plugins, `daemon.NewStartedService`, the Android `adapter.PlatformInterface` (`Platform` over a `Bridge`), start/reload/stop/probe/exchange/stats |
| `brainffi` | cgo: links `libfkbox_brain_ffi.a`, exports the `fkb_host_api` functions, `fkb_brain_attach`, the Rust `decide` as fkbrain's decider, the control calls |
| `internal/fixture` | test fixtures only (loopback servers, TUN opener) |
| `e2e` | the end-to-end test (TUN → sing-box → fkbrain → Rust → outbound) |

## Host (option A)

- `host.NewContext(platform)`: `service.ContextWithDefaultRegistry`, then
  `box.Context` with the upstream inbound/endpoint/service/certificate
  registries and outbound/DNS registries that also hold the four plugin
  types (`outbound.Register`, `dns.RegisterTransport`), and the platform
  registered as `adapter.PlatformInterface`. No libbox code.
- `daemon.NewStartedService(ServiceOptions{Context: ctx, ...})`:
  `StartOrReloadService` creates every instance in that context
  (`service.ExtendContext`), so the plugins resolve. `Start(config,
  generation)` runs `CheckConfig` first (parse + `box.New` + close): a
  configuration that fails there never stops the running one. A failure
  after the check (opening the TUN, starting an outbound) leaves nothing
  running and is reported to the brain.
- `Platform` (`host/platform.go`) implements `adapter.PlatformInterface`
  over a `Bridge` the Android shell provides (stage 6 JNI):
  `Protect(fd)` = `VpnService.protect` (`AutoDetectInterfaceControl`, with
  `route.auto_detect_interface` every sing-box dialer, plugin dialers
  included), `OpenTun(request)` = `VpnService.Builder.establish` (the host
  duplicates the descriptor and runs `tun.New` on it, name from
  `TUNGETIFF`), and optionally `OwnerFinder` (uid + packages, used when the
  configuration has `route.find_process`) and `NetworkSource` (interfaces
  and default network; without it sing-box watches the system itself).
  Shell, SSH, Tailscale, bridges, neighbours and auto-redirect are not
  offered.
- The `fkb_host_api` table (`brainffi/export.go`, `ctx` = a
  `runtime/cgo.Handle`): `start`/`reload` → `Host.Start`, `stop`,
  `probe(tag, url)` (one HTTP(S) GET through the outbound, status and time
  to headers), `exchange(tag, upstream, message)` (`udp://`/`tcp://` with an
  IP upstream through the outbound; `tls://`/`https://` come with stage 4),
  `stats` (bytes and live connections through fkbrain, rates since the last
  call, heap+stack in use, goroutines). `FKB_E_TIMEOUT` when `timeout_ms`
  passed, `FKB_E_NOT_FOUND` for an unknown tag.
- `brainffi.Attach(host)` checks the struct sizes against the brain
  (`fkb_struct_size`, ABI version), calls `fkb_brain_attach`, and installs
  the brain's `decide` (called through a C trampoline with the Go
  `plugin.Meta`/`plugin.Decision` directly: same layout, no Go pointers).
- Control path (new brain exports, [header](../../crates/brain-ffi/include/fkbox_brain.h)):
  `fkb_brain_start_profile(profile, options, err)` /
  `fkb_brain_reload_profile` / `fkb_brain_stop`. The brain imports the
  subscription (`config::brain::import`), generates the configuration,
  compiles the rules against fkbrain's table and calls the host's
  `start`/`reload`. Until groups run in the brain (stage 5) a rule naming a
  group goes to the group's selector `default`, else its first member
  (followed through nested groups); a link list sends everything to its
  first node. `route.find_process` is set when the rules read the process,
  package or uid.

## fkbrain

`route.final`, so sing-box hands it every connection whole
(`route/route.go`: the chain's last outbound implements
`adapter.ConnectionHandler` / `PacketConnectionHandler`). There are no
route rules, so it does what the router would otherwise do:

1. Sniff: TCP with `sniff.PeekStream` (TLS, HTTP, DNS, BitTorrent, SSH,
   RDP; skipped for server-first ports, `sniff.Skip`), UDP by reading the
   first packet (up to 4 for a fragmented QUIC hello) within sing-box's
   300 ms payload timeout and `sniff.PeekPacket` (DNS, QUIC, STUN, uTP,
   UDP tracker, DTLS, NTP). Read data is put back (`bufio.NewCachedConn` /
   `NewCachedPacketConn`).
2. DNS to port 53 (sniffed `dns`) goes to the brain's DNS without a
   decision, like the Android service's hijack-dns and mihomo's
   `dns-hijack: any:53`: `protocol/dns.HandleStreamDNSRequest` (TCP) or
   `NewDNSPacketConnection` (UDP) on sing-box's DNS router, whose only
   server is fkdns. A `HIJACK_DNS` decision does the same.
3. Fill `fkb_conn_meta` from `adapter.InboundContext` (network, addresses,
   FQDN or sniffed domain, protocol, uid/package/process from
   `ProcessInfo`, inbound index in the running configuration) and call
   `decide` once.
4. `ROUTE`: the index is looked up in the outbound table of the decision's
   generation (the host keeps the last three; fkbrain registers its table
   under the generation the host is creating), then the outbound by tag;
   the connection goes to `adapter.ConnectionManager.NewConnection` /
   `NewPacketConnection`, counted for stats. `REJECT` / `REJECT_DROP`:
   closed with sing-box's rejected errors (reset / drop). Anything that
   fails (no brain, error code, unknown generation, index or tag) rejects:
   fail closed, never direct.

## fkdns (placeholder until stage 4)

The only DNS server. Every query goes to one upstream (`upstream` option,
default `udp://1.1.1.1:53`, IP addresses only so no resolver is needed),
over UDP or TCP, through `dialer.NewDefault` (protected by the platform);
never the system resolver. Stage 4 replaces the body with the brain's
`resolve`.

## mihomo plugin

- `adapter.ParseProxy(map, WithDialerForAPI(d))`: `BasicOption.NewDialer`
  prefers `DialerForAPI` over mihomo's own dialer, `dialer-proxy` and the
  global `DefaultSocketHook`. `d` adapts the outbound's sing-box dialer
  (`dialer.NewWithOptions`, `RemoteIsDomain`): the protected dialer, or the
  detour outbound (mihomo `dialer-proxy` = sing-box `detour`). TCP:
  `Proxy.DialContext`; UDP: `ListenPacketContext` (also behind
  `DialContext("udp")`).
- sing-box 1.15 closes outbounds only through the start scope, so the map
  is checked in the constructor (parsed, then closed) and the proxy built
  in `Start` (initialize stage) with its `Close` in the scope.
- Server names: mihomo resolves them itself in several places that do not
  go through the injected dialer (`resolveUDPAddr` for ss/ssr/socks5/
  hysteria UDP, mieru's `DNSResolver`, `dialer.NewDialer` in the QUIC
  protocols, ECH). Those read the process-wide
  `resolver.ProxyServerHostResolver` / `DefaultResolver` /
  `DirectHostResolver`; nil, mihomo falls back to `SystemResolver` (system
  DNS). The plugin sets all three, at package init, to a resolver that asks
  the running configuration's DNS router (fkdns); without a running
  configuration a lookup fails. ECH lookups fail until stage 4.
- Refused (constructor error): `dns` (relays into mihomo's resolver),
  `rematch` (mihomo's rule engine), `relay` (removed), `tailscale`,
  `openvpn`, `zerotier`, `easytier` (own userspace networks dialing and
  resolving outside the injected dialer; refused at import too, decided
  2026-11-24), `direct`/`reject` (use sing-box's).

Review of the protocols the import sends to mihomo (1.19.34
`adapter/outbound`, `transport/*`; none uses `tunnel`):

| Protocol | Global state | Verdict |
| --- | --- | --- |
| ssr | `ntp.Now` (offset 0 unless mihomo's NTP service runs; it does not) for timestamps; UDP server name via `resolveUDPAddr` → the resolver above | runs |
| mieru | its client and goroutines are per proxy; server name via `mieruDNSResolver` → `ProxyServerHostResolver` → the resolver above; packets through the injected dialer | runs (TCP+UDP tested) |
| sudoku | HTTP mask tunnel and multiplex dialer built on `s.dialer` (injected) | runs |
| shadowquic | `shadowquic.DialQuic` uses `dialer.NewDialer(WithNetDialer(...))` whose socket comes from the injected packet dialer; the name resolves through `ProxyServerHostResolver` → the resolver above | runs |
| restls, jls | TLS libraries with `ntp.Now` only | run |
| trusttunnel | pool client and health check per proxy, `context.TODO`, dials with the injected dialer | runs |
| masque, snell, gost-relay | injected dialer; masque resolves targets with `DefaultResolver` → the resolver above | run |

## xray plugin

- One Xray instance per outbound holding only that outbound (no inbounds,
  routing or DNS), from the outbound JSON through `infra/conf/serial`
  (checked in the constructor, instance created and started in `Start`,
  closed through the scope). TCP: `core.Dial`; UDP: `core.DialUDP` (any IP
  destination per packet); a domain UDP destination (fake-ip, stage 4) is
  one `core.Dial` UDP stream, since the dispatcher's `WriteTo` only takes IP
  addresses.
- Protect: Xray's system dialer is process-wide
  (`internet.UseAlternativeSystemDialer`, last writer wins, and afterwards
  `RegisterDialerController` is refused). The plugin installs one dialer,
  once per process, that picks the sing-box dialer of the outbound a socket
  belongs to: from the dial context (`core.Dial` passes the plugin's
  context down), else by destination among the running xray outbounds'
  servers (Xray's own contexts — XHTTP's connection pool, mux/XUDP workers
  — lose the value), else the dial fails; it never falls back to Xray's own
  dialer. UDP sockets are returned as `net.PacketConnWrapper`, the type the
  XHTTP/QUIC and finalmask paths require (they panic on others).
- Refused: protocols other than vless/vmess/trojan/shadowsocks (wireguard
  resolves through Xray's process-wide DNS client and may open a kernel
  TUN; hysteria and masque listen with `ListenSystemPacket`, which the
  alternative dialer does not cover; freedom/blackhole/dns/loopback are not
  proxies); transports other than raw/tcp, ws, httpupgrade, grpc,
  xhttp/splithttp, mKCP; `domainStrategy` other than AsIs (Xray's DNS),
  `sockopt.dialerProxy` / `addressPortStrategy` / `happyEyeballs` (the last
  instance's process-wide outbound manager and DNS client), finalmask
  `realm` (STUN lookups through the system resolver), `allowInsecure`.
- XHTTP's connection pool (`transport/internet/splithttp/dialer.go`,
  `globalDialerMap`) is keyed by destination and the instance's stream
  settings pointer and never pruned: after a reload the new instance gets a
  new pool (old connections are not reused), and each reload leaves the
  old instance's entry behind until the process ends (small leak; its
  connections close with their idle timeouts).
- mKCP: its UDP socket is a `DialSystem` UDP socket, so it comes from the
  sing-box dialer (tested).

## Tests (`.github/workflows/fkhost.yml`, ubuntu-24.04-arm)

Build of every package with all errors, `go vet` (informational), then
`go test` with tags `with_quic,with_wireguard,with_utls,with_gvisor`:

- `plugin`: the C layout of `Meta`/`Decision`, metadata filling,
  generation tables, the xray refusals and server extraction.
- `host`: through the host context and the Android platform over a
  counting bridge, against loopback fixtures: native Shadowsocks TCP+UDP;
  mihomo mieru TCP+UDP with the server addressed by name (resolved through
  fkdns and the DNS fixture); mihomo Shadowsocks UDP (mihomo's own server
  lookup through the plugin's resolver, the sing-box packet dialer);
  mihomo with a sing-box detour (the node address is unreachable except
  through the chained Shadowsocks server); Xray VLESS+XHTTP TCP+UDP in two
  fresh instances in a row; Xray VLESS+mKCP TCP+UDP; fkbrain with a Go
  decider (HTTP host sniffed, routed by index, rejected, DNS hijacked to
  fkdns). Each asserts that sockets went through `Protect` (and, for Xray,
  that no dial was refused or fell outside the sing-box dialers).
- `e2e`: a TUN device the job creates (`ip tuntap add ... user`,
  198.18.99.0/24 routed into it); the brain imports a Clash subscription
  and starts the host through `fkb_host_api`; kernel traffic enters the
  TUN (gVisor stack), fkbrain asks the Rust decide, and native
  Shadowsocks / the mihomo plugin carry TCP and UDP, a rejected address
  carries nothing; then a reload with a VLESS+XHTTP share link moves
  everything to the xray plugin.

The Xray servers of the tests run in a child copy of the test binary: the
plugin's process-wide Xray dialer would also capture an in-process
server's freedom sockets.

Not covered: a device (real `VpnService`, `protect`, Android network
callbacks), the JNI bridge (stage 6), DNS through the brain (stage 4),
groups (stage 5), mihomo protocols other than mieru/ss at run time, Xray
REALITY/TLS/h3 at run time.
