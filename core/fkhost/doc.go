// Package fkhost holds fk-box's own host services: the program-side services
// the kernels (core_kernel_*.go in libbox) get through the interfaces in
// core_host.go. CI copies it into the sing-box tree as experimental/fkhost
// (tools/patch_entry.py, sing-box's go.mod, no Go module of its own).
//
// It never imports libbox. On Android the embedding host (libbox's
// CommandServer, where sing-box still runs) connects the few things only it
// can give: the app's VpnService through AndroidVPN, and the service log the
// app's log page reads (Logger.SetForward). The desktop core process
// (cmd/fk-core) uses the same services directly. See core/README.md.
package fkhost
