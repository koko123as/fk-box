package ffi

import (
	"testing"

	"github.com/koko123as/fk-box/probes/stage0/plugin"
)

// Items 2 + 5 together: sing-box routes a connection to fkbrain, fkbrain asks
// the Rust decider over the C ABI, and forwards to the outbound it picked.
func TestFFIBrainEndToEnd(t *testing.T) {
	if err := Init(); err != nil {
		t.Fatal(err)
	}
	plugin.SetDecider(Decider())
	echo, err := plugin.StartEchoServer()
	if err != nil {
		t.Fatal(err)
	}
	defer echo.Close()
	ctx, _, err := plugin.NewProbeContext(true)
	if err != nil {
		t.Fatal(err)
	}
	instance, err := plugin.NewBox(ctx, `{
  "log": {"level": "warn"},
  "outbounds": [
    {"type": "direct", "tag": "direct"},
    {"type": "block", "tag": "proxy-placeholder"},
    {"type": "fkbrain", "tag": "brain", "outbounds": ["direct", "proxy-placeholder"]}
  ],
  "route": {"final": "brain", "auto_detect_interface": true}
}`)
	if err != nil {
		t.Fatal(err)
	}
	defer instance.Close()
	if err = instance.Start(); err != nil {
		t.Fatal(err)
	}
	// 127.0.0.1 is private -> Rust returns outbound 0 (direct).
	if err = plugin.RouteThrough(ctx, instance.Router(), echo.Addr().String(), []byte("decided in rust")); err != nil {
		t.Fatal(err)
	}
	// Port 22 -> Rust rejects.
	if err = plugin.RouteThrough(ctx, instance.Router(), "127.0.0.1:22", []byte("x")); err == nil {
		t.Fatal("rust reject was not applied")
	}
	brain, _ := instance.Outbound().Outbound("brain")
	b := brain.(*plugin.BrainOutbound)
	t.Logf("PROBE item5 fkbrain + rust decide: decided=%d rejected=%d", b.Decided(), b.Rejected())
	if b.Decided() != 2 || b.Rejected() != 1 {
		t.Fatalf("decided=%d rejected=%d", b.Decided(), b.Rejected())
	}
}
