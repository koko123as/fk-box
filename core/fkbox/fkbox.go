// Package fkbox is fk-box's own gomobile entry package. CI copies it into the
// sing-box tree as experimental/fkbox (like core_*.go into
// experimental/libbox, under sing-box's go.mod, no Go module of its own) and
// binds it next to libbox into libbox.aar: Java io.nekohasekai.fkbox.Fkbox.
//
// It forwards to libbox, where fk-box's core still lives, so the Kotlin calls
// to Libbox keep their names; new host entry points are added here instead of
// to libbox (core/README.md, "Moving the host out of libbox").
package fkbox

import (
	"github.com/sagernet/sing-box/experimental/fkhost"
	"github.com/sagernet/sing-box/experimental/libbox"
)

// Version is the sing-box version the core was built from.
func Version() string { return libbox.Version() }

// ProtocolVersion is the UI protocol version this core speaks (protocol/methods.json).
func ProtocolVersion() int32 { return libbox.ProtocolVersion }

// ProtocolMinClient is the oldest client protocol version this core accepts.
func ProtocolMinClient() int32 { return libbox.ProtocolMinClient }

// Hello runs the protocol's "hello" handshake (JSON in, JSON out).
func Hello(request string) (string, error) { return libbox.UIInvoke("hello", request) }

// Invoke runs a method Go answers (owner "go" in protocol/methods.json) with
// its JSON arguments and answers the JSON result.
func Invoke(method string, args string) (string, error) { return libbox.UIInvoke(method, args) }

// HandlesMethod reports whether Go answers method.
func HandlesMethod(method string) bool { return libbox.UIHandlesMethod(method) }

// EventSink receives the events Go raises for the UI: kind is the event name
// in protocol/methods.json, payload its JSON value.
type EventSink interface {
	Emit(kind string, payload string)
}

// SetEventSink installs (nil removes) the receiver of Go-raised UI events.
// It replaces a sink installed through libbox's SetUIEventSink.
func SetEventSink(sink EventSink) {
	if sink == nil {
		libbox.SetUIEventSink(nil)
		return
	}
	libbox.SetUIEventSink(sink)
}

// DefaultNetworkChanged is called by the app's platform implementation when
// the underlying default network is replaced (Wi-Fi to cellular and back);
// the running Xray or mihomo closes connections bound to the old network.
func DefaultNetworkChanged() { fkhost.DefaultNetworkChanged() }
