# core/

fk-box's own Go code, overlaid into sing-box's `experimental/libbox` package at build
time (`.github/actions/setup-core` copies `core_*.go` and `ui_*.go`). Upstream kernel sources and `go.mod` are not
edited here.

fk-box's own packages sit next to it in the same sing-box tree and module
(`tools/patch_entry.py`, no Go module of their own):

| Source | In the sing-box tree | What it is |
| --- | --- | --- |
| `core/fkhost/` | `experimental/fkhost` | fk-box's host services (log, HTTP, platform per OS, working directory, the protocol server); never imports libbox |
| `core/fkbox/` | `experimental/fkbox` | the gomobile entry package, bound into `libbox.aar` next to libbox (Java `io.nekohasekai.fkbox.Fkbox`); forwards to libbox so the Kotlin calls to `Libbox` keep their names |
| `core/fkcore/` | `cmd/fk-core` | the desktop core process |

## Clients and the `Core` interface

Every client talks to the core through the UI protocol in `protocol/methods.json`:
methods (answered by Go through `UIInvoke`, or by the client's host), events (delivered to
a `UIEventSink`), a protocol version and the platform capabilities a client declares.
`core_interface.go` names the client-facing surface as the Go interface `Core`: `Hello`,
`Start`, `Reload`, `Stop`, `SelectOutbound`, `SubscribeConnections`, `SubscribeLogs`,
`SubscribeDashboard` and `SetEventSink`.

- **Android** runs the core in-process through gomobile. `InProcessCore` wraps the
  `CommandServer` and the Go-side streams the Kotlin host already calls; Android keeps
  using that path unchanged and never runs the core out of process.
- **Desktop clients** run the core as the child process `fk-core` (`core/fkcore`,
  built from the same packages; CI builds it on Linux and checks the
  handshake). The client starts it, sends `hello`, then speaks the protocol
  as JSON lines over stdio or a Unix socket (`-socket PATH`); the framing is
  `fkhost.ProtocolServer` (`{"id","method","args"}` in, `{"id","result"|"error"}`
  and `{"event","payload"}` out; anything before `hello` is refused). It
  answers the Go-owned methods, `startService`/`reloadService`
  `{content, core?}` (a desktop client passes the profile; Android's host
  reads it from its own store), `stopService`, `setLogsVisible`, `clearLogs`,
  and raises the core's events; host-owned methods are refused by name.
  No desktop client exists yet.

Every client starts with `hello`; see `core_hello.go` and `tools/protocol.py`.

## Kernels and host services

The three kernels (sing-box, Xray, mihomo) implement one internal interface,
`coreKernel` (`core_kernel.go`), one file per kernel:
`core_kernel_singbox.go`, `core_kernel_xray.go`, `core_kernel_mihomo.go`
(tests `TestUIKernel<Name>*`). A kernel gets everything it needs from the
program hosting it through fk-box's own host interfaces (`core_host.go`), never
from libbox globals or libbox types:

| Interface | What it provides |
| --- | --- |
| `Platform` | create the TUN, protect a socket (dial control), the owner of a connection, default-network changes |
| `CommandService` | the control channel the UI reads the core through (moving open streams to the running core, group refreshes) |
| `Lifecycle` | starting and stopping sing-box inside the service, the working directory |
| `HTTP` | HTTP clients that trust the host's certificate store |
| `Log` | the service log the app shows, and the process log |

Each is implemented by fk-box's own package `fkhost` or, where Android still
needs libbox, by a thin adapter (see the status below). `core_touch_singbox.go`
lists every sing-box, sing-tun and libbox symbol the code here uses, so an
upgrade that changes one fails to compile there by name; the upgrade checklist
is in [libbox.md](../libbox.md#升级内核).

The core switch (`startOrReloadCore`, `closeCore` in `core_service.go`) runs
against any kernel host: libbox's `CommandServer` on Android, or the
standalone host (`core_standalone.go`, not built for Android) that the desktop
core uses.

## Moving the host out of libbox: status

The plan had five steps. What each one changed, and what is left and why:

1. **fk-box's own gomobile entry package — done.** `core/fkbox` is copied to
   `experimental/fkbox` and bound next to libbox (`tools/patch_entry.py` adds
   it to the Android bind in `cmd/internal/build_libbox`). It forwards to
   libbox (version, protocol version, hello, Go-owned methods, event sink)
   and carries new host entry points (`defaultNetworkChanged`); every
   existing Kotlin call keeps `Libbox`.
2. **Host services moved behind their interfaces.**
   - `Log` — done: `fkhost.Log` (ring, subscribers). On Android each line is
     also forwarded to the StartedService log, because the app's log page
     reads that stream (it also carries sing-box's own lines).
   - `HTTP` — done: `fkhost.NewHTTPClient`, same TLS settings and Android
     certificate store as libbox's client; used by the kernels' host, geo,
     subscriptions, WebDAV/update checks and the egress probe.
   - `Platform` — done: `fkhost.Platform` builds the TUN request, dups the
     descriptor, protects sockets and maps connection owners; on Android it
     calls the app's VpnService through `fkhost.AndroidVPN`, adapted from the
     PlatformInterface object the app already passes to libbox
     (`core_host_android.go`). The app reports default-network changes to
     `Fkbox.defaultNetworkChanged()`.
   - `Lifecycle` — partly: the working directory is fkhost's
     (`SetWorkingDirectory`; Android falls back to libbox's `Setup` path).
     On Android sing-box still starts and stops inside the `CommandServer`
     (step 3).
   - `CommandService` — partly: fk-box's own protocol server
     (`fkhost.ProtocolServer`) serves `fk-core`. Android keeps libbox's
     `CommandServer`/StartedService gRPC: see step 3.
3. **sing-box as an ordinary kernel — done for the standalone host, not on
   Android.** In `core_standalone.go` fk-box creates and starts `box.Box`
   itself (logs into `fkhost.Log`). Android still runs sing-box inside
   libbox's `daemon.StartedService`, and the patches that exist because of
   that stay: `patch_multicore.py`, the libbox hunks of `httpdebug.patch` and
   `http_rewrite.patch`, and `libbox-no-legacy.patch` (libbox.aar is still
   built). Why it stops there:
   - The Android UI reads sing-box (and, through `coreStartedService`, Xray
     and mihomo) over the StartedService gRPC service with libbox's
     `CommandClient`: status, groups, clash mode, connections, logs,
     outbounds, URL tests, and the Tailscale/Taildrop, USB/IP, OpenConnect,
     OpenVPN, network-quality, STUN and notification services. Replacing it
     means re-implementing that service (about 3,000 lines upstream) in
     fk-box's protocol and moving every Kotlin stream onto it, which cannot
     keep Android's behavior "unchanged" without device testing, and AGENTS.md
     requires keeping all of those features.
   - Remote sessions (`newStandaloneRemoteCommandClient`) talk to other
     sing-box instances over the same gRPC service, so the client side of it
     stays in any case.
   - Starting sing-box outside libbox on Android also needs sing-box's
     platform wrapper (`adapter.PlatformInterface`: interface monitor, Wi-Fi
     state, local DNS transport, neighbors, notifications, auto-redirect),
     which is libbox code today.
   The next step is to serve the UI's read streams (status, groups, mode,
   connections, logs) from fk-box's protocol on Android first, keeping the
   gRPC service for remote sessions and the endpoint services.
4. **Platform files by build tag — done.** `fkhost/platform_android.go`
   (VpnService fd, protect, owners), `platform_linux.go` (`/dev/net/tun` as
   `fkbox0`, routing table 2023 with `not fwmark 0x2023` and a main-table
   `suppress_prefixlength 0` rule, `SO_MARK` on the kernels' sockets; needs
   CAP_NET_ADMIN; no connection owners), `platform_darwin.go`
   (NetworkExtension packet tunnel through `SetPacketTunnel`, `IP_BOUND_IF`),
   `platform_windows.go` and `platform_other.go`. Kernels stay platform-free.
   On Windows `OpenTun` reports that wintun has no descriptor: Xray and
   mihomo need a session-based TUN adapter first (sing-box opens wintun
   itself and runs there).
5. **Desktop core process — done** (see "Clients and the `Core` interface").
   Untested on a real desktop: CI only builds it on Linux and checks the
   handshake and method routing.
