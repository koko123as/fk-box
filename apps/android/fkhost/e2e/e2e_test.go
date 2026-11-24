// End to end on Linux arm64: a real TUN device (created by the CI job, as
// VpnService would hand over its descriptor), the Go host with the Rust
// brain linked in (brainffi), and the brain driving it: it imports a Clash
// subscription, generates the sing-box configuration, compiles the rules
// and calls the host's start; then a reload with a share link (Xray).
// Traffic enters the TUN from the kernel, sing-box hands every connection
// to fkbrain, fkbrain calls the Rust decide, and the chosen outbound
// (native Shadowsocks, the mihomo plugin, the xray plugin) carries it to
// the loopback fixtures.
package e2e

import (
	"fmt"
	"net"
	"os"
	"sync"
	"testing"
	"time"

	"github.com/koko123as/fk-box/apps/android/fkhost/brainffi"
	"github.com/koko123as/fk-box/apps/android/fkhost/host"
	"github.com/koko123as/fk-box/apps/android/fkhost/internal/fixture"
	"github.com/koko123as/fk-box/apps/android/fkhost/plugin"
)

func TestMain(m *testing.M) { fixture.Main(m) }

type tunBridge struct {
	name      string
	access    sync.Mutex
	protected int
}

func (b *tunBridge) Protect(fd int) error {
	b.access.Lock()
	b.protected++
	b.access.Unlock()
	return nil
}

func (b *tunBridge) OpenTun(request *host.TunRequest) (int, error) {
	return fixture.OpenTunDevice(b.name)
}

const options = `{"tun_address": ["172.19.0.1/30"], "tun_auto_route": false, "tun_stack": "gvisor", "tun_mtu": 1500, "log_level": "debug"}`

func tcpEcho(t *testing.T, address string) {
	t.Helper()
	conn, err := net.DialTimeout("tcp", address, 10*time.Second)
	if err != nil {
		t.Fatalf("tcp %s: %v", address, err)
	}
	defer conn.Close()
	if err = fixture.StreamRoundTrip(conn, []byte("fk-box e2e tcp "+address)); err != nil {
		t.Fatalf("tcp %s: %v", address, err)
	}
}

func udpEcho(t *testing.T, address string) {
	t.Helper()
	conn, err := net.ListenPacket("udp", "0.0.0.0:0")
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	destination, err := net.ResolveUDPAddr("udp", address)
	if err != nil {
		t.Fatal(err)
	}
	if err = fixture.PacketRoundTrip(conn, destination, []byte("fk-box e2e udp "+address)); err != nil {
		t.Fatalf("udp %s: %v", address, err)
	}
}

func TestEndToEnd(t *testing.T) {
	tunName := os.Getenv("FKHOST_E2E_TUN")
	if tunName == "" {
		t.Skip("FKHOST_E2E_TUN is not set (the CI job creates the TUN device)")
	}
	echo := fixture.StartEcho(t)
	mieruPort := fixture.StartMieru(t)
	ssPort := fixture.StartShadowsocks(t, "127.0.0.1", echo.Port)
	xhttpPort := fixture.FreePort(t, "tcp")
	fixture.StartXrayServer(t, xhttpPort, map[string]any{
		"network": "xhttp", "xhttpSettings": map[string]any{"path": "/fk-e2e", "mode": "packet-up"},
	}, echo.Port)

	bridge := &tunBridge{name: tunName}
	h, err := host.New(host.Options{Bridge: bridge})
	if err != nil {
		t.Fatal(err)
	}
	if err = brainffi.Attach(h); err != nil {
		t.Fatal("attach: ", err)
	}

	subscription := fmt.Sprintf(`proxies:
  - {name: ss-native, type: ss, server: 127.0.0.1, port: %d, cipher: aes-128-gcm, password: %q}
  - {name: mieru-plugin, type: mieru, server: 127.0.0.1, port: %d, transport: TCP, username: fkbox, password: %q}
rules:
  - IP-CIDR,198.18.99.1/32,ss-native
  - IP-CIDR,198.18.99.2/32,mieru-plugin
  - IP-CIDR,198.18.99.3/32,REJECT
  - MATCH,REJECT
`, ssPort, fixture.Password, mieruPort, fixture.Password)
	if err = brainffi.StartProfile(subscription, options); err != nil {
		t.Fatal("start profile: ", err)
	}
	defer func() {
		if err := brainffi.Stop(); err != nil {
			t.Log("stop: ", err)
		}
	}()
	instance := h.Box()
	if instance == nil {
		t.Fatal("host not running after start")
	}
	for _, tag := range []string{"fkbrain", "ss-native", "mieru-plugin", "fk-direct", "fk-block"} {
		if _, loaded := instance.Outbound().Outbound(tag); !loaded {
			t.Fatalf("generated configuration lacks outbound %s", tag)
		}
	}
	before, protectedBefore := plugin.Stats(), h.Platform().Protected()

	tcpEcho(t, "198.18.99.1:7777")
	udpEcho(t, "198.18.99.1:7777")
	tcpEcho(t, "198.18.99.2:8888")
	udpEcho(t, "198.18.99.2:8888")

	// Rejected by the Rust rules: the connection must not carry data.
	if conn, err := net.DialTimeout("tcp", "198.18.99.3:80", 5*time.Second); err == nil {
		_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
		_, _ = conn.Write([]byte("GET / HTTP/1.1\r\nHost: rejected.example\r\n\r\n"))
		if n, err := conn.Read(make([]byte, 16)); err == nil && n > 0 {
			t.Fatal("rejected connection carried data")
		}
		conn.Close()
	}

	middle := plugin.Stats()
	if middle.Routed["ss-native"] < before.Routed["ss-native"]+2 || middle.Routed["mieru-plugin"] < before.Routed["mieru-plugin"]+2 {
		t.Fatalf("routing by the Rust decide: before %v after %v", before.Routed, middle.Routed)
	}
	if middle.Rejected <= before.Rejected || middle.DecideFailed != before.DecideFailed {
		t.Fatalf("reject / failed decisions: before %+v after %+v", before, middle)
	}
	if h.Platform().Protected() <= protectedBefore {
		t.Fatal("no protected socket")
	}
	t.Logf("E2E clash profile: ss-native %d, mieru-plugin %d, rejected %d, decided %d, mean decide %.0f ns (cgo + Rust), protected %d",
		middle.Routed["ss-native"]-before.Routed["ss-native"], middle.Routed["mieru-plugin"]-before.Routed["mieru-plugin"],
		middle.Rejected-before.Rejected, middle.Decided-before.Decided,
		float64(middle.DecideNanos-before.DecideNanos)/float64(max(middle.Decided-before.Decided, 1)),
		h.Platform().Protected()-protectedBefore)

	// Reload: a share link only Xray carries (VLESS + XHTTP); a link list
	// sends everything to its first node.
	link := fmt.Sprintf("vless://%s@127.0.0.1:%d?encryption=none&security=none&type=xhttp&path=%%2Ffk-e2e&mode=packet-up#xh", fixture.UUID, xhttpPort)
	if err = brainffi.ReloadProfile(link, options); err != nil {
		t.Fatal("reload profile: ", err)
	}
	if instance = h.Box(); instance == nil {
		t.Fatal("host not running after reload")
	}
	if outbound, loaded := instance.Outbound().Outbound("xh"); !loaded || outbound.Type() != plugin.TypeXray {
		t.Fatal("the link did not become an xray plugin outbound")
	}
	xrayBefore := plugin.XrayDialerStats()
	tcpEcho(t, "198.18.99.4:443")
	udpEcho(t, "198.18.99.4:443")
	after := plugin.Stats()
	xrayAfter := plugin.XrayDialerStats()
	if after.Routed["xh"] < middle.Routed["xh"]+2 || xrayAfter.Refused != xrayBefore.Refused {
		t.Fatalf("xray after reload: routed %v, dialer %+v", after.Routed, xrayAfter)
	}
	stats := h.Stats()
	if stats.UplinkBytes == 0 || stats.DownlinkBytes == 0 || stats.Goroutines == 0 {
		t.Fatalf("host stats: %+v", stats)
	}
	t.Logf("E2E reload to xray: xh %d, xray dialer from ctx %d / destination %d, host stats %+v",
		after.Routed["xh"]-middle.Routed["xh"], xrayAfter.FromContext-xrayBefore.FromContext,
		xrayAfter.FromDestination-xrayBefore.FromDestination, stats)
}
