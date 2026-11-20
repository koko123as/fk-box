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
