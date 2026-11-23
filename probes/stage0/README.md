# Stage 0 feasibility probe (single sing-box kernel + Rust brain)

Probe code only. It has its own `go.mod`, is not part of the app, libbox or
any release build, and changes no behaviour on `main`. No upstream file of
sing-box, mihomo or Xray is patched; only exported APIs are used.

| Item | What is checked | Where |
| --- | --- | --- |
| 1 | Custom outbound types (`mihomo`, `xray`, `fkchain`, `fkbrain`) and a custom DNS transport (`fkdns`) registered through `outbound.Register` / `dns.RegisterTransport`, both into own registries passed to `box.Context` and into the registries of an `include.Context`; whether libbox's own context can take them | `plugin/register.go`, `plugin/probe_singbox_test.go`, `libboxinject/` (run inside an unpatched sing-box checkout) |
| 2 | A plugin outbound looks up another outbound by tag through `adapter.OutboundManager` and dials it; `fkbrain` receives whole connections from the router and forwards them by decision | `plugin/chain.go`, `plugin/brain.go` |
| 3 | mihomo mieru node from `adapter.ParseProxy` with `WithDialerForAPI` → sing-box protected dialer, no global hook | `plugin/mihomo.go`, `plugin/probe_mihomo_test.go` |
| 4 | Xray VLESS+XHTTP outbound in an outbounds-only instance, `core.Dial`; protection through `internet.RegisterDialerController` and through `internet.UseAlternativeSystemDialer` → sing-box dialer | `plugin/xray.go`, `plugin/probe_xray_test.go` |
| 5 | Go → Rust `decide` through a C function pointer registered at startup (fixed C structs, no JSON); latency p50/p99 incl. cgo | `ffi/`, `rustcb/` |
| 6 | Dependency conflicts of the three modules in one `go.mod`, against the current multi-core graph | `tools/deps_report.py` |

Servers in the tests (echo, mieru inbound, Xray XHTTP inbound) are in-process
test fixtures on loopback. Nothing opens a local SOCKS/HTTP inbound or runs a
subprocess. CI: `.github/workflows/stage0-probe.yml` (ubuntu-24.04-arm).
