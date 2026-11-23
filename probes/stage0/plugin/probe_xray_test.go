package plugin

import (
	"fmt"
	"net"
	"sync/atomic"
	"syscall"
	"testing"

	M "github.com/sagernet/sing/common/metadata"

	"github.com/xtls/xray-core/transport/internet"
)

// The two Xray hooks are process-wide and cannot be undone, so CI runs each
// of these tests in its own `go test -run` process.

const probeUUID = "6f1b8f0e-5c0e-4a5e-9a39-2f9f3c7d1a01"

func freeTCPPort(t *testing.T) int {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	return listener.Addr().(*net.TCPAddr).Port
}

// startXHTTPServer is the test fixture: an Xray VLESS+XHTTP inbound on
// loopback with a freedom outbound, in the same process.
func startXHTTPServer(t *testing.T, port int) func() {
	instance, err := NewXrayInstance(map[string]any{
		"log": map[string]any{"loglevel": "warning"},
		"inbounds": []any{map[string]any{
			"tag": "in", "listen": "127.0.0.1", "port": port, "protocol": "vless",
			"settings": map[string]any{
				"clients":    []any{map[string]any{"id": probeUUID}},
				"decryption": "none",
			},
			"streamSettings": map[string]any{
				"network":       "xhttp",
				"xhttpSettings": map[string]any{"path": "/fk-stage0", "mode": "packet-up"},
			},
		}},
		"outbounds": []any{map[string]any{"protocol": "freedom", "tag": "direct"}},
	})
	if err != nil {
		t.Fatal(err)
	}
	return func() { instance.Close() }
}

func xrayClientConfig(port int) string {
	return fmt.Sprintf(`{
  "log": {"level": "warn"},
  "outbounds": [
    {"type": "direct", "tag": "direct"},
    {"type": "xray", "tag": "xhttp", "outbound": {
      "protocol": "vless",
      "settings": {"address": "127.0.0.1", "port": %d, "id": %q, "encryption": "none"},
      "streamSettings": {"network": "xhttp", "xhttpSettings": {"path": "/fk-stage0", "mode": "packet-up"}}
    }}
  ],
  "route": {"final": "direct", "auto_detect_interface": true}
}`, port, probeUUID)
}

// Item 4, route A: Xray's system dialer replaced by a sing-box dialer
// (internet.UseAlternativeSystemDialer). XHTTP sockets are then created and
// protected by sing-box.
func TestXrayXHTTPAlternativeDialer(t *testing.T) {
	UseSingBoxDialerForXray()
	echo, err := StartEchoServer()
	if err != nil {
		t.Fatal(err)
	}
	defer echo.Close()
	port := freeTCPPort(t)
	stop := startXHTTPServer(t, port)
	defer stop()

	ctx, platform, err := NewProbeContext(true)
	if err != nil {
		t.Fatal(err)
	}
	instance, err := NewBox(ctx, xrayClientConfig(port))
	if err != nil {
		t.Fatal(err)
	}
	defer instance.Close()
	if err = instance.Start(); err != nil {
		t.Fatal(err)
	}
	outbound, _ := instance.Outbound().Outbound("xhttp")
	before := platform.Protected()
	conn, err := outbound.DialContext(ctx, "tcp", M.ParseSocksaddr(echo.Addr().String()))
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	if err = EchoRoundTrip(conn, []byte("fk-box stage0 via xray xhttp")); err != nil {
		t.Fatal(err)
	}
	contextHits, globalHits, targets := XrayStats()
	server := fmt.Sprintf("127.0.0.1:%d", port)
	if !containsTarget(targets, server) {
		t.Fatalf("no Xray dial to the XHTTP server went through sing-box: %v", targets)
	}
	if platform.Protected() <= before {
		t.Fatal("XHTTP sockets were not protected by sing-box")
	}
	err = internet.RegisterDialerController(func(network, address string, c syscall.RawConn) error { return nil })
	t.Logf("PROBE item4 xray xhttp via UseAlternativeSystemDialer: echo ok; dials with plugin ctx=%d, via global fallback=%d; targets=%v; protected sockets %d; RegisterDialerController afterwards -> %v",
		contextHits, globalHits, targets, platform.Protected()-before, err)
}

// Item 4, route B: Xray keeps its own system dialer; protect via
// internet.RegisterDialerController (what fk-box does today).
func TestXrayRegisterDialerController(t *testing.T) {
	var controlled atomic.Int64
	if err := internet.RegisterDialerController(func(network, address string, c syscall.RawConn) error {
		controlled.Add(1)
		return nil
	}); err != nil {
		t.Fatal(err)
	}
	echo, err := StartEchoServer()
	if err != nil {
		t.Fatal(err)
	}
	defer echo.Close()
	port := freeTCPPort(t)
	stop := startXHTTPServer(t, port)
	defer stop()
	ctx, _, err := NewProbeContext(true)
	if err != nil {
		t.Fatal(err)
	}
	instance, err := NewBox(ctx, xrayClientConfig(port))
	if err != nil {
		t.Fatal(err)
	}
	defer instance.Close()
	if err = instance.Start(); err != nil {
		t.Fatal(err)
	}
	outbound, _ := instance.Outbound().Outbound("xhttp")
	conn, err := outbound.DialContext(ctx, "tcp", M.ParseSocksaddr(echo.Addr().String()))
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	if err = EchoRoundTrip(conn, []byte("fk-box stage0 xray controller")); err != nil {
		t.Fatal(err)
	}
	if controlled.Load() == 0 {
		t.Fatal("RegisterDialerController saw no sockets")
	}
	_, globalHits, _ := XrayStats()
	t.Logf("PROBE item4 xray xhttp via RegisterDialerController: echo ok; controller saw %d sockets (client + in-process server freedom, process-wide); sing-box dialer used %d times", controlled.Load(), globalHits)
}
