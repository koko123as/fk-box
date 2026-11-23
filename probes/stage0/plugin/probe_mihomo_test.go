package plugin

import (
	"context"
	"fmt"
	"io"
	"net"
	"strconv"
	"testing"

	M "github.com/sagernet/sing/common/metadata"

	mihomoDialer "github.com/metacubex/mihomo/component/dialer"
	mihomoC "github.com/metacubex/mihomo/constant"
	mihomoInbound "github.com/metacubex/mihomo/listener/inbound"
)

// echoTunnel is the test-side mihomo C.Tunnel behind the mieru server
// inbound: every proxied TCP stream is echoed back.
type echoTunnel struct{}

func (echoTunnel) HandleTCPConn(conn net.Conn, metadata *mihomoC.Metadata) {
	defer conn.Close()
	_, _ = io.Copy(conn, conn)
}

func (echoTunnel) HandleUDPPacket(packet mihomoC.UDPPacket, metadata *mihomoC.Metadata) {
	packet.Drop()
}

func (echoTunnel) NatTable() mihomoC.NatTable { return nil }

type fixedListenConfig struct{ listener net.Listener }

func (c fixedListenConfig) Listen(ctx context.Context, network, address string) (net.Listener, error) {
	return c.listener, nil
}

func (c fixedListenConfig) ListenPacket(ctx context.Context, network, address string) (net.PacketConn, error) {
	return nil, fmt.Errorf("udp not used in this probe")
}

// Item 3: a mihomo mieru node built with adapter.ParseProxy runs inside a
// sing-box "type: mihomo" outbound; its sockets come from sing-box's
// protected dialer via WithDialerForAPI, with no global mihomo hook.
func TestProbeMihomoMieru(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := listener.Addr().(*net.TCPAddr).Port
	server, err := mihomoInbound.NewMieru(&mihomoInbound.MieruOption{
		BaseOption: mihomoInbound.BaseOption{
			NameStr:            "fk-probe-mieru",
			Listen:             "127.0.0.1",
			Port:               strconv.Itoa(port),
			ListenConfigForAPI: fixedListenConfig{listener},
		},
		Transport:           "TCP",
		Users:               map[string]string{"fkprobe": "stage0-password"},
		UserHintIsMandatory: true,
	})
	if err != nil {
		t.Fatal(err)
	}
	if err = server.Listen(echoTunnel{}); err != nil {
		t.Fatal(err)
	}
	defer server.Close()

	ctx, platform, err := NewProbeContext(true)
	if err != nil {
		t.Fatal(err)
	}
	config := fmt.Sprintf(`{
  "log": {"level": "warn"},
  "outbounds": [
    {"type": "direct", "tag": "direct"},
    {"type": "mihomo", "tag": "mieru", "proxy": {
      "type": "mieru", "server": "127.0.0.1", "port": %d, "transport": "TCP",
      "username": "fkprobe", "password": "stage0-password"
    }}
  ],
  "route": {"final": "direct", "auto_detect_interface": true}
}`, port)
	instance, err := NewBox(ctx, config)
	if err != nil {
		t.Fatal(err)
	}
	defer instance.Close()
	if err = instance.Start(); err != nil {
		t.Fatal(err)
	}
	outbound, loaded := instance.Outbound().Outbound("mieru")
	if !loaded {
		t.Fatal("mihomo outbound missing")
	}
	mieru := outbound.(*MihomoOutbound)
	before := platform.Protected()
	conn, err := outbound.DialContext(ctx, "tcp", M.ParseSocksaddr("198.51.100.7:80"))
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	if err = EchoRoundTrip(conn, []byte("fk-box stage0 via mihomo mieru")); err != nil {
		t.Fatal(err)
	}
	if mieru.Dials() == 0 {
		t.Fatal("mieru did not dial through the sing-box dialer")
	}
	if platform.Protected() <= before {
		t.Fatal("mieru socket was not protected by sing-box")
	}
	if mihomoDialer.DefaultSocketHook != nil {
		t.Fatal("unexpected global mihomo DefaultSocketHook")
	}
	t.Logf("PROBE item3 mihomo %s via sing-box dialer: echo ok, mihomo dials %d, protected sockets %d, DefaultSocketHook nil",
		mieru.ProxyType(), mieru.Dials(), platform.Protected()-before)
}
