# core/

fk-box's own Go code, overlaid into sing-box's `experimental/libbox` package at build
time (`.github/actions/setup-core` copies `core_*.go` and `ui_*.go`). Upstream kernel sources and `go.mod` are not
edited here.

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
- **Desktop clients (planned)** will run the core as a child process that serves the same
  protocol over stdio or a local socket: the client sends `hello` first, then the same
  method and event JSON. That transport is not implemented yet.

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

Today every one of them is implemented by delegating to libbox (`libboxHost`),
so behavior is unchanged. `core_touch_singbox.go` lists every sing-box, sing-tun
and libbox symbol the code here uses, so an upgrade that changes one fails to
compile there by name; the upgrade checklist is in [libbox.md](../libbox.md#升级内核).

## Plan: moving the host out of libbox (design only, not implemented)

libbox is still the shell of the whole Go layer: TUN (VpnService), protect,
network monitoring, the CommandServer, the lifecycle, HTTP and logs all come
from it, for all three kernels. The plan, in this order, each step on its own
branch with the behavior unchanged:

1. **fk-box's own gomobile entry package.** A new package of fk-box's (copied
   into the sing-box tree at build time like `core_*.go`, using sing-box's
   `go.mod`; no new Go module) becomes what gomobile binds. At first it only
   forwards to libbox, so the Kotlin calls keep their names.
2. **Move the host services out one at a time**, each behind the interface it
   already has: `Log` (a log ring and subscribers of fk-box's), `HTTP` (the
   certificate store and TLS settings), `Platform` (TUN creation, protect,
   connection owner, default-network watch, called straight from the app's
   platform implementation), `Lifecycle`, and last `CommandService` (fk-box's
   own gRPC or protocol server for the methods in `protocol/methods.json`
   instead of `daemon.StartedService`). After each move libbox no longer
   provides that service to Xray and mihomo.
3. **sing-box becomes an ordinary kernel.** Once nothing else needs libbox's
   service, `core_kernel_singbox.go` starts sing-box's `box.Box` itself on the
   host's TUN like the other kernels, and the patches that exist only because
   fk-box lives inside libbox go away: `patch_multicore.py` (wrapping the
   StartedService registration), the libbox parts of `httpdebug.patch` and
   `http_rewrite.patch` (hooks in `command_server.go`, `config.go`), and
   `libbox-no-legacy.patch` if libbox is no longer built. Patches to sing-box
   itself (DNS counter, route hook, sing-tun) stay as long as their features do.
4. **Platform files by build tag.** The `Platform` implementation splits into
   `_android.go` (VpnService TUN fd, `protect`, `getConnectionOwnerUid`),
   `_windows.go` (wintun), `_linux.go` (`/dev/net/tun`, routing with fwmark so
   the kernel's own sockets bypass the TUN) and `_darwin.go`
   (NetworkExtension's packet flow); kernels stay platform-free.
5. **Desktop clients run the core as a child process**, `fk-core`, built from
   the same packages: the client starts it, sends `hello`, then speaks the
   protocol over stdio or a local socket (see "Clients and the `Core`
   interface" above).

None of this is started yet: the code today stops at the interfaces above.
