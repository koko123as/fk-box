package host

import (
	"context"
	"fmt"
	"net"
	"net/netip"
	"sync"
	"testing"
	"time"

	"github.com/koko123as/fk-box/apps/android/fkhost/internal/fixture"
	"github.com/koko123as/fk-box/apps/android/fkhost/plugin"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/buf"
	"github.com/sagernet/sing/common/json"
	M "github.com/sagernet/sing/common/metadata"

	mDNS "github.com/miekg/dns"
)

// newTestBox creates and starts a sing-box instance in the host's context
// (registries + plugins + the Android platform over a counting bridge).
func newTestBox(t *testing.T, config string) (*box.Box, *testBridge, context.Context) {
	t.Helper()
	bridge := &testBridge{}
	ctx := NewContext(NewPlatform(bridge))
	options, err := json.UnmarshalExtendedContext[option.Options](ctx, []byte(config))
	if err != nil {
		t.Fatal("parse: ", err)
	}
	instance, err := box.New(box.Options{Context: ctx, Options: options})
	if err != nil {
		t.Fatal("box.New: ", err)
	}
	if err = instance.Start(); err != nil {
		instance.Close()
		t.Fatal("start: ", err)
	}
	t.Cleanup(func() { instance.Close() })
	if pendingDNSPort != 0 {
		installFixtureDNS(t, instance, pendingDNSPort)
	}
	return instance, bridge, ctx
}

// pendingDNSPort: the DNS fixture the next test box's fkdns answers from.
var pendingDNSPort int

// fixtureDNSBrain plays the brain's DNS in the host tests (the Rust brain
// is linked only in brainffi): every message goes to the DNS fixture
// through the configuration's fk-direct outbound, with the same exchange
// the host offers the brain (protected dialer).
type fixtureDNSBrain struct {
	port     int
	outbound adapter.Outbound
}

func (f fixtureDNSBrain) Exchange(ctx context.Context, message []byte, client bool) ([]byte, error) {
	return plugin.ExchangeVia(ctx, f.outbound, fmt.Sprintf("udp://127.0.0.1:%d", f.port), message, nil)
}

func (fixtureDNSBrain) Reverse(netip.Addr) (string, bool) { return "", false }

func installFixtureDNS(t *testing.T, instance *box.Box, port int) {
	t.Helper()
	pendingDNSPort = 0
	plugin.SetDNSBrain(fixtureDNSBrain{port: port, outbound: outboundOf(t, instance, "fk-direct")})
	t.Cleanup(func() { plugin.SetDNSBrain(nil) })
}

// clientConfig: fkdns as the only DNS server (answered from the DNS
// fixture, see fixtureDNSBrain), the given outbounds, protect on every
// dialer.
func clientConfig(dnsPort int, outbounds string) string {
	pendingDNSPort = dnsPort
	return fmt.Sprintf(`{
  "log": {"level": "debug"},
  "dns": {"servers": [{"type": "fkdns", "tag": "fkdns"}], "final": "fkdns", "disable_cache": true},
  "outbounds": [%s, {"type": "direct", "tag": "fk-direct"}, {"type": "block", "tag": "fk-block"}],
  "route": {"final": "fk-direct", "auto_detect_interface": true, "default_domain_resolver": "fkdns"}
}`, outbounds)
}

func outboundOf(t *testing.T, instance *box.Box, tag string) adapter.Outbound {
	t.Helper()
	outbound, loaded := instance.Outbound().Outbound(tag)
	if !loaded {
		t.Fatalf("outbound %s missing", tag)
	}
	return outbound
}

// checkTCPUDP runs a TCP and a UDP echo through the outbound to a
// destination the server redirects to the echo fixture.
func checkTCPUDP(t *testing.T, ctx context.Context, outbound adapter.Outbound, destination string, udp bool) {
	t.Helper()
	conn, err := outbound.DialContext(ctx, "tcp", M.ParseSocksaddr(destination))
	if err != nil {
		t.Fatal("tcp dial: ", err)
	}
	if err = fixture.StreamRoundTrip(conn, []byte("fk-box stage3 tcp via "+outbound.Tag())); err != nil {
		conn.Close()
		t.Fatal("tcp: ", err)
	}
	conn.Close()
	if !udp {
		return
	}
	target := M.ParseSocksaddr(destination)
	packetConn, err := outbound.ListenPacket(ctx, target)
	if err != nil {
		t.Fatal("udp listen: ", err)
	}
	defer packetConn.Close()
	if err = fixture.PacketRoundTrip(packetConn, target.UDPAddr(), []byte("fk-box stage3 udp via "+outbound.Tag())); err != nil {
		t.Fatal("udp: ", err)
	}
}

// Native sing-box outbound (Shadowsocks) through the same host context.
func TestNativeShadowsocks(t *testing.T) {
	echo := fixture.StartEcho(t)
	dns := fixture.StartDNS(t)
	ssPort := fixture.StartShadowsocks(t, "127.0.0.1", echo.Port)
	instance, bridge, ctx := newTestBox(t, clientConfig(dns.Port, fmt.Sprintf(
		`{"type": "shadowsocks", "tag": "ss", "server": "127.0.0.1", "server_port": %d, "method": "aes-128-gcm", "password": %q}`,
		ssPort, fixture.Password)))
	before := bridge.Count()
	checkTCPUDP(t, ctx, outboundOf(t, instance, "ss"), "198.18.99.1:7", true)
	if bridge.Count() <= before {
		t.Fatal("no protected socket")
	}
	t.Logf("STAGE3 native shadowsocks: tcp+udp echo ok, protected sockets %d", bridge.Count()-before)
}

// mihomo mieru, server addressed by name: the name resolves through fkdns
// (sing-box DNS, the DNS fixture), never the system resolver.
func TestMihomoMieru(t *testing.T) {
	dns := fixture.StartDNS(t)
	mieruPort := fixture.StartMieru(t)
	instance, bridge, ctx := newTestBox(t, clientConfig(dns.Port, fmt.Sprintf(
		`{"type": "mihomo", "tag": "mieru", "proxy": {"name": "mieru", "type": "mieru", "server": "mieru.fixture.test", "port": %d, "transport": "TCP", "udp": true, "username": "fkbox", "password": %q}}`,
		mieruPort, fixture.Password)))
	mieru := outboundOf(t, instance, "mieru").(*plugin.Mihomo)
	before, lookups := bridge.Count(), plugin.MihomoLookups.Load()
	checkTCPUDP(t, ctx, mieru, "198.18.99.2:9", true)
	if mieru.Dials() == 0 {
		t.Fatal("mieru did not dial through the sing-box dialer")
	}
	if bridge.Count() <= before {
		t.Fatal("no protected socket")
	}
	if dns.Queries() == 0 {
		t.Fatal("the server name was not resolved through fkdns")
	}
	t.Logf("STAGE3 mihomo %s: tcp+udp echo ok, mihomo dials %d, protected %d, fkdns queries %d, mihomo resolver lookups %d",
		mieru.ProxyType(), mieru.Dials(), bridge.Count()-before, dns.Queries(), plugin.MihomoLookups.Load()-lookups)
}

// mihomo Shadowsocks UDP: mihomo resolves the server for its UDP relay
// itself (resolveUDPAddr with resolver.ProxyServerHostResolver), which the
// plugin points at sing-box's DNS; the packet socket is the sing-box one.
func TestMihomoShadowsocksUDP(t *testing.T) {
	echo := fixture.StartEcho(t)
	dns := fixture.StartDNS(t)
	ssPort := fixture.StartShadowsocks(t, "127.0.0.1", echo.Port)
	instance, bridge, ctx := newTestBox(t, clientConfig(dns.Port, fmt.Sprintf(
		`{"type": "mihomo", "tag": "mss", "proxy": {"name": "mss", "type": "ss", "server": "ss.fixture.test", "port": %d, "cipher": "aes-128-gcm", "password": %q, "udp": true}}`,
		ssPort, fixture.Password)))
	mss := outboundOf(t, instance, "mss").(*plugin.Mihomo)
	before, lookups := bridge.Count(), plugin.MihomoLookups.Load()
	checkTCPUDP(t, ctx, mss, "198.18.99.3:53", true)
	if mss.PacketDials() == 0 {
		t.Fatal("mihomo UDP did not use the sing-box packet dialer")
	}
	if plugin.MihomoLookups.Load() == lookups {
		t.Fatal("mihomo did not resolve the server through sing-box")
	}
	if bridge.Count() <= before {
		t.Fatal("no protected socket")
	}
	t.Logf("STAGE3 mihomo ss: tcp+udp echo ok, dials %d, packet dials %d, resolver lookups %d, protected %d",
		mss.Dials(), mss.PacketDials(), plugin.MihomoLookups.Load()-lookups, bridge.Count()-before)
}

// dialer-proxy as a sing-box detour: the mieru node's address is TEST-NET
// (unreachable); only the Shadowsocks server it is chained through
// redirects to the real mieru server, so the echo proves the chain.
func TestMihomoDetour(t *testing.T) {
	dns := fixture.StartDNS(t)
	mieruPort := fixture.StartMieru(t)
	ssPort := fixture.StartShadowsocks(t, "127.0.0.1", mieruPort)
	instance, bridge, ctx := newTestBox(t, clientConfig(dns.Port, fmt.Sprintf(
		`{"type": "shadowsocks", "tag": "hop", "server": "127.0.0.1", "server_port": %d, "method": "aes-128-gcm", "password": %q},
     {"type": "mihomo", "tag": "chained", "detour": "hop", "proxy": {"name": "chained", "type": "mieru", "server": "192.0.2.1", "port": 1, "transport": "TCP", "udp": true, "username": "fkbox", "password": %q}}`,
		ssPort, fixture.Password, fixture.Password)))
	chained := outboundOf(t, instance, "chained").(*plugin.Mihomo)
	before := bridge.Count()
	checkTCPUDP(t, ctx, chained, "198.18.99.4:80", false)
	if chained.Dials() == 0 || bridge.Count() <= before {
		t.Fatalf("chain not used: dials %d, protected %d", chained.Dials(), bridge.Count()-before)
	}
	t.Logf("STAGE3 mihomo detour (mieru via native ss): echo ok, mieru dials %d, protected %d", chained.Dials(), bridge.Count()-before)
}

func xrayOutboundJSON(tag string, port int, stream string) string {
	return fmt.Sprintf(`{"type": "xray", "tag": %q, "outbound": {
      "protocol": "vless",
      "settings": {"address": "127.0.0.1", "port": %d, "id": %q, "encryption": "none"},
      "streamSettings": %s}}`, tag, port, fixture.UUID, stream)
}

// Xray VLESS + XHTTP: TCP and UDP (core.Dial / core.DialUDP), twice in a
// row in fresh instances (XHTTP's process-wide connection pool is keyed by
// each instance's stream settings, so the second instance never reuses the
// first one's connections).
func TestXrayXHTTP(t *testing.T) {
	echo := fixture.StartEcho(t)
	dns := fixture.StartDNS(t)
	port := fixture.FreePort(t, "tcp")
	stream := map[string]any{"network": "xhttp", "xhttpSettings": map[string]any{"path": "/fk-stage3", "mode": "packet-up"}}
	fixture.StartXrayServer(t, port, stream, echo.Port)
	for round := 1; round <= 2; round++ {
		func() {
			bridge := &testBridge{}
			ctx := NewContext(NewPlatform(bridge))
			options, err := json.UnmarshalExtendedContext[option.Options](ctx, []byte(clientConfig(dns.Port,
				xrayOutboundJSON("xh", port, `{"network": "xhttp", "xhttpSettings": {"path": "/fk-stage3", "mode": "packet-up"}}`))))
			if err != nil {
				t.Fatal(err)
			}
			instance, err := box.New(box.Options{Context: ctx, Options: options})
			if err != nil {
				t.Fatal(err)
			}
			defer instance.Close()
			if err = instance.Start(); err != nil {
				t.Fatal(err)
			}
			installFixtureDNS(t, instance, dns.Port)
			statsBefore := plugin.XrayDialerStats()
			checkTCPUDP(t, ctx, outboundOf(t, instance, "xh"), "198.18.99.5:443", true)
			stats := plugin.XrayDialerStats()
			if stats.Refused != statsBefore.Refused {
				t.Fatalf("round %d: Xray dials refused: %+v", round, stats)
			}
			if stats.FromContext+stats.FromDestination == statsBefore.FromContext+statsBefore.FromDestination || bridge.Count() == 0 {
				t.Fatalf("round %d: Xray sockets did not come from sing-box: %+v, protected %d", round, stats, bridge.Count())
			}
			t.Logf("STAGE3 xray xhttp round %d: tcp+udp echo ok, dialer from ctx %d, from destination %d, protected %d",
				round, stats.FromContext-statsBefore.FromContext, stats.FromDestination-statsBefore.FromDestination, bridge.Count())
		}()
	}
}

// Xray VLESS + mKCP: the transport's UDP socket comes from the sing-box
// dialer as net.PacketConnWrapper.
func TestXrayMKCP(t *testing.T) {
	echo := fixture.StartEcho(t)
	dns := fixture.StartDNS(t)
	port := fixture.FreePort(t, "udp")
	fixture.StartXrayServer(t, port, map[string]any{"network": "kcp"}, echo.Port)
	instance, bridge, ctx := newTestBox(t, clientConfig(dns.Port, xrayOutboundJSON("kcp", port, `{"network": "kcp"}`)))
	before := bridge.Count()
	statsBefore := plugin.XrayDialerStats()
	checkTCPUDP(t, ctx, outboundOf(t, instance, "kcp"), "198.18.99.6:22", true)
	stats := plugin.XrayDialerStats()
	if stats.Refused != statsBefore.Refused || bridge.Count() <= before {
		t.Fatalf("mKCP sockets: %+v, protected %d", stats, bridge.Count()-before)
	}
	t.Logf("STAGE3 xray mkcp: tcp+udp echo ok, protected %d", bridge.Count()-before)
}

// ---- fkbrain with a Go decider, connections injected into the router ----

type recordingDecider struct {
	access   sync.Mutex
	metas    []plugin.Meta
	byLast   map[byte]plugin.Decision
	fallback plugin.Decision
}

func (d *recordingDecider) Decide(meta *plugin.Meta, out *plugin.Decision) int32 {
	d.access.Lock()
	defer d.access.Unlock()
	d.metas = append(d.metas, *meta)
	if decision, ok := d.byLast[meta.DstIP[3]]; ok && meta.DstIPVersion == 4 {
		*out = decision
	} else {
		*out = d.fallback
	}
	return plugin.CodeOK
}

func (d *recordingDecider) last() plugin.Meta {
	d.access.Lock()
	defer d.access.Unlock()
	return d.metas[len(d.metas)-1]
}

// pipePacketConn is the TUN side of an injected UDP connection.
type pipePacketConn struct {
	remote   M.Socksaddr
	in       chan []byte
	out      chan []byte
	closed   chan struct{}
	once     sync.Once
	access   sync.Mutex
	deadline time.Time
}

func newPipePacketConn(remote string) *pipePacketConn {
	return &pipePacketConn{remote: M.ParseSocksaddr(remote), in: make(chan []byte, 8), out: make(chan []byte, 8), closed: make(chan struct{})}
}

func (c *pipePacketConn) ReadPacket(buffer *buf.Buffer) (M.Socksaddr, error) {
	c.access.Lock()
	deadline := c.deadline
	c.access.Unlock()
	var timeout <-chan time.Time
	if !deadline.IsZero() {
		timer := time.NewTimer(time.Until(deadline))
		defer timer.Stop()
		timeout = timer.C
	}
	select {
	case packet := <-c.in:
		_, err := buffer.Write(packet)
		return c.remote, err
	case <-timeout:
		return M.Socksaddr{}, timeoutError{}
	case <-c.closed:
		return M.Socksaddr{}, net.ErrClosed
	}
}

func (c *pipePacketConn) WritePacket(buffer *buf.Buffer, destination M.Socksaddr) error {
	defer buffer.Release()
	select {
	case c.out <- append([]byte(nil), buffer.Bytes()...):
		return nil
	case <-c.closed:
		return net.ErrClosed
	}
}

func (c *pipePacketConn) Close() error {
	c.once.Do(func() { close(c.closed) })
	return nil
}

func (c *pipePacketConn) LocalAddr() net.Addr {
	return &net.UDPAddr{IP: net.IPv4(172, 19, 0, 1), Port: 40001}
}

func (c *pipePacketConn) SetDeadline(t time.Time) error { return c.SetReadDeadline(t) }

func (c *pipePacketConn) SetReadDeadline(t time.Time) error {
	c.access.Lock()
	c.deadline = t
	c.access.Unlock()
	return nil
}

func (c *pipePacketConn) SetWriteDeadline(t time.Time) error { return nil }

type timeoutError struct{}

func (timeoutError) Error() string   { return "i/o timeout" }
func (timeoutError) Timeout() bool   { return true }
func (timeoutError) Temporary() bool { return true }

func injectTCP(t *testing.T, router adapter.Router, ctx context.Context, destination string) (net.Conn, chan error) {
	t.Helper()
	client, server := net.Pipe()
	closed := make(chan error, 1)
	go router.RouteConnectionEx(ctx, server, adapter.InboundContext{
		Inbound:     "fk-test",
		InboundType: "fk-test",
		Network:     "tcp",
		Source:      M.ParseSocksaddr("172.19.0.1:40000"),
		Destination: M.ParseSocksaddr(destination),
	}, func(err error) { closed <- err })
	return client, closed
}

// fkbrain end to end inside sing-box: it sniffs (HTTP host, DNS), asks the
// decider, routes to the outbound of the decision's index (here a native
// Shadowsocks node), rejects, and hijacks DNS to fkdns.
func TestBrainRouting(t *testing.T) {
	echo := fixture.StartEcho(t)
	dns := fixture.StartDNS(t)
	ssPort := fixture.StartShadowsocks(t, "127.0.0.1", echo.Port)
	plugin.SetPendingGeneration(7)
	decider := &recordingDecider{
		byLast: map[byte]plugin.Decision{
			1: {Action: plugin.ActionRoute, Outbound: 0, Generation: 7},
			2: {Action: plugin.ActionReject, Generation: 7},
		},
		fallback: plugin.Decision{Action: plugin.ActionRoute, Outbound: 2, Generation: 7},
	}
	plugin.SetDecider(decider)
	defer plugin.SetDecider(nil)
	config := fmt.Sprintf(`{
  "log": {"level": "debug"},
  "dns": {"servers": [{"type": "fkdns", "tag": "fkdns"}], "final": "fkdns", "disable_cache": true},
  "outbounds": [
    {"type": "fkbrain", "tag": "fkbrain", "outbounds": ["ss", "fk-direct", "fk-block"]},
    {"type": "shadowsocks", "tag": "ss", "server": "127.0.0.1", "server_port": %d, "method": "aes-128-gcm", "password": %q},
    {"type": "direct", "tag": "fk-direct"},
    {"type": "block", "tag": "fk-block"}
  ],
  "route": {"final": "fkbrain", "auto_detect_interface": true, "default_domain_resolver": "fkdns"}
}`, ssPort, fixture.Password)
	pendingDNSPort = dns.Port
	instance, bridge, ctx := newTestBox(t, config)
	before := plugin.Stats()

	// Routed by index 0 (ss); the HTTP host is sniffed first.
	client, _ := injectTCP(t, instance.Router(), ctx, "198.18.99.1:80")
	request := []byte("GET / HTTP/1.1\r\nHost: sniffed.example\r\n\r\n")
	if err := fixture.StreamRoundTrip(client, request); err != nil {
		t.Fatal("routed: ", err)
	}
	client.Close()
	meta := decider.last()
	if meta.Protocol != plugin.ProtoHTTP || string(meta.Domain[:meta.DomainLen]) != "sniffed.example" || meta.DomainSource != plugin.DomainSniffed {
		t.Fatalf("sniffed meta: protocol %d domain %q source %d", meta.Protocol, meta.Domain[:meta.DomainLen], meta.DomainSource)
	}
	if meta.Network != plugin.NetTCP || meta.DstPort != 80 || meta.SrcPort != 40000 || meta.InboundIndex != plugin.InboundUnknown {
		t.Fatalf("meta: %+v", meta)
	}

	// Rejected.
	client, closed := injectTCP(t, instance.Router(), ctx, "198.18.99.2:80")
	_ = client.SetDeadline(time.Now().Add(5 * time.Second))
	_, _ = client.Write([]byte("GET / HTTP/1.1\r\nHost: rejected.example\r\n\r\n"))
	select {
	case <-closed:
	case <-time.After(10 * time.Second):
		t.Fatal("rejected connection not closed")
	}
	client.Close()

	// DNS to port 53 is hijacked to fkdns (sniffed, no decision needed).
	packetConn := newPipePacketConn("198.18.99.53:53")
	done := make(chan error, 1)
	go instance.Router().RoutePacketConnectionEx(ctx, packetConn, adapter.InboundContext{
		Inbound: "fk-test", InboundType: "fk-test", Network: "udp",
		Source: M.ParseSocksaddr("172.19.0.1:40001"), Destination: M.ParseSocksaddr("198.18.99.53:53"),
	}, func(err error) { done <- err })
	query := new(mDNS.Msg)
	query.SetQuestion("hijacked.fixture.test.", mDNS.TypeA)
	packed, _ := query.Pack()
	packetConn.in <- packed
	select {
	case reply := <-packetConn.out:
		var response mDNS.Msg
		if err := response.Unpack(reply); err != nil || len(response.Answer) == 0 {
			t.Fatalf("hijacked DNS reply: %v %v", err, response.String())
		}
	case <-time.After(10 * time.Second):
		t.Fatal("no DNS reply through the hijack")
	}
	packetConn.Close()

	after := plugin.Stats()
	if after.Routed["ss"] <= before.Routed["ss"] || after.Rejected <= before.Rejected || after.DNSHijacked <= before.DNSHijacked {
		t.Fatalf("counters: before %+v after %+v", before, after)
	}
	if bridge.Count() == 0 {
		t.Fatal("no protected socket")
	}
	t.Logf("STAGE3 fkbrain: sniff http %q, route by index -> ss, reject, dns hijack -> fkdns; decided %d (mean %.0f ns incl. Go decider), protected %d",
		"sniffed.example", after.Decided-before.Decided, float64(after.DecideNanos-before.DecideNanos)/float64(max(after.Decided-before.Decided, 1)), bridge.Count())
}
