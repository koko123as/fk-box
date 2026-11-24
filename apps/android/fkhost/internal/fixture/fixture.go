// Package fixture holds test fixtures of the host tests only: loopback echo
// and DNS servers, proxy servers for each plugin (a sing-box Shadowsocks
// server, a mihomo mieru server, Xray VLESS servers in a child test
// process) and a TUN opener. Nothing here is product code; the product
// never listens on loopback. Internal: only fkhost's tests import it.
package fixture

import (
	"bufio"
	"bytes"
	"context"
	stdjson "encoding/json"
	"fmt"
	"io"
	"net"
	"net/netip"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/json"
	"github.com/sagernet/sing/service"
	"golang.org/x/sys/unix"

	mihomoC "github.com/metacubex/mihomo/constant"
	mihomoInbound "github.com/metacubex/mihomo/listener/inbound"
	mDNS "github.com/miekg/dns"
	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/infra/conf/serial"
)

const (
	UUID           = "6f1b8f0e-5c0e-4a5e-9a39-2f9f3c7d1a01"
	Password       = "fk-stage3-password"
	xrayFixtureEnv = "FKHOST_XRAY_FIXTURE"
)

// Main is the TestMain of a test binary that starts Xray servers: in the
// child process it runs the server instead of the tests.
func Main(m *testing.M) {
	if config := os.Getenv(xrayFixtureEnv); config != "" {
		os.Exit(runXrayFixture(config))
	}
	os.Exit(m.Run())
}

// ---- echo: TCP and UDP on the same port ----

// Echo echoes TCP streams and UDP datagrams on Port (127.0.0.1).
type Echo struct {
	Port     int
	listener net.Listener
	packet   net.PacketConn
}

func StartEcho(t *testing.T) *Echo {
	t.Helper()
	for attempt := 0; attempt < 20; attempt++ {
		listener, err := net.Listen("tcp", "127.0.0.1:0")
		if err != nil {
			t.Fatal(err)
		}
		port := listener.Addr().(*net.TCPAddr).Port
		packet, err := net.ListenPacket("udp", "127.0.0.1:"+strconv.Itoa(port))
		if err != nil {
			listener.Close()
			continue
		}
		server := &Echo{Port: port, listener: listener, packet: packet}
		go func() {
			for {
				conn, err := listener.Accept()
				if err != nil {
					return
				}
				go func() {
					defer conn.Close()
					_, _ = io.Copy(conn, conn)
				}()
			}
		}()
		go func() {
			buffer := make([]byte, 65535)
			for {
				n, addr, err := packet.ReadFrom(buffer)
				if err != nil {
					return
				}
				_, _ = packet.WriteTo(buffer[:n], addr)
			}
		}()
		t.Cleanup(func() {
			listener.Close()
			packet.Close()
		})
		return server
	}
	t.Fatal("no free TCP+UDP port")
	return nil
}

func FreePort(t *testing.T, network string) int {
	t.Helper()
	if network == "udp" {
		packet, err := net.ListenPacket("udp", "127.0.0.1:0")
		if err != nil {
			t.Fatal(err)
		}
		defer packet.Close()
		return packet.LocalAddr().(*net.UDPAddr).Port
	}
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	return listener.Addr().(*net.TCPAddr).Port
}

func StreamRoundTrip(conn net.Conn, payload []byte) error {
	_ = conn.SetDeadline(time.Now().Add(15 * time.Second))
	if _, err := conn.Write(payload); err != nil {
		return fmt.Errorf("write: %w", err)
	}
	got := make([]byte, len(payload))
	if _, err := io.ReadFull(conn, got); err != nil {
		return fmt.Errorf("read: %w", err)
	}
	if !bytes.Equal(got, payload) {
		return fmt.Errorf("echo mismatch: %q", got)
	}
	return nil
}

// packetRoundTrip sends payload to destination and expects it back
// (retrying: the first packets of a relay may be lost while it sets up).
func PacketRoundTrip(conn net.PacketConn, destination net.Addr, payload []byte) error {
	buffer := make([]byte, 65535)
	var lastErr error
	for attempt := 0; attempt < 5; attempt++ {
		if _, err := conn.WriteTo(payload, destination); err != nil {
			return fmt.Errorf("write: %w", err)
		}
		_ = conn.SetReadDeadline(time.Now().Add(3 * time.Second))
		n, _, err := conn.ReadFrom(buffer)
		if err != nil {
			lastErr = err
			continue
		}
		if !bytes.Equal(buffer[:n], payload) {
			return fmt.Errorf("echo mismatch: %q", buffer[:n])
		}
		return nil
	}
	return fmt.Errorf("no UDP echo: %w", lastErr)
}

// ---- DNS: names under fixture.test resolve to 127.0.0.1 ----

// DNS answers A 127.0.0.1 for names under fixture.test (NXDOMAIN else) on
// UDP 127.0.0.1:Port.
type DNS struct {
	Port    int
	queries atomic.Int64
}

// Queries is the number of queries received.
func (d *DNS) Queries() int64 { return d.queries.Load() }

func StartDNS(t *testing.T) *DNS {
	t.Helper()
	packet, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	server := &DNS{Port: packet.LocalAddr().(*net.UDPAddr).Port}
	handler := mDNS.HandlerFunc(func(writer mDNS.ResponseWriter, request *mDNS.Msg) {
		server.queries.Add(1)
		response := new(mDNS.Msg)
		response.SetReply(request)
		for _, question := range request.Question {
			if !strings.HasSuffix(question.Name, ".fixture.test.") {
				response.Rcode = mDNS.RcodeNameError
				continue
			}
			if question.Qtype == mDNS.TypeA {
				response.Answer = append(response.Answer, &mDNS.A{
					Hdr: mDNS.RR_Header{Name: question.Name, Rrtype: mDNS.TypeA, Class: mDNS.ClassINET, Ttl: 60},
					A:   net.IPv4(127, 0, 0, 1).To4(),
				})
			}
		}
		_ = writer.WriteMsg(response)
	})
	dnsServer := &mDNS.Server{PacketConn: packet, Handler: handler}
	go func() { _ = dnsServer.ActivateAndServe() }()
	t.Cleanup(func() { _ = dnsServer.Shutdown() })
	return server
}

// ---- sing-box Shadowsocks server: every connection goes to the echo port ----

func StartShadowsocks(t *testing.T, overrideAddress string, overridePort int) int {
	t.Helper()
	port := FreePort(t, "tcp")
	config := fmt.Sprintf(`{
  "log": {"level": "warn"},
  "inbounds": [{"type": "shadowsocks", "tag": "ss-in", "listen": "127.0.0.1", "listen_port": %d, "method": "aes-128-gcm", "password": %q}],
  "outbounds": [{"type": "direct", "tag": "direct"}],
  "route": {"rules": [{"network": ["tcp", "udp"], "action": "route-options", "override_address": %q, "override_port": %d}], "final": "direct"}
}`, port, Password, overrideAddress, overridePort)
	ctx := include.Context(service.ContextWithDefaultRegistry(context.Background()))
	options, err := json.UnmarshalExtendedContext[option.Options](ctx, []byte(config))
	if err != nil {
		t.Fatal("ss server config: ", err)
	}
	instance, err := box.New(box.Options{Context: ctx, Options: options})
	if err != nil {
		t.Fatal("ss server: ", err)
	}
	if err = instance.Start(); err != nil {
		t.Fatal("ss server start: ", err)
	}
	t.Cleanup(func() { instance.Close() })
	return port
}

// ---- mihomo mieru server: its tunnel echoes TCP and UDP itself ----

type echoTunnel struct{}

func (echoTunnel) HandleTCPConn(conn net.Conn, metadata *mihomoC.Metadata) {
	defer conn.Close()
	_, _ = io.Copy(conn, conn)
}

func (echoTunnel) HandleUDPPacket(packet mihomoC.UDPPacket, metadata *mihomoC.Metadata) {
	payload := append([]byte(nil), packet.Data()...)
	source := net.UDPAddrFromAddrPort(netip.AddrPortFrom(metadata.DstIP, metadata.DstPort))
	_, _ = packet.WriteBack(payload, source)
	packet.Drop()
}

func (echoTunnel) NatTable() mihomoC.NatTable { return nil }

type fixedListenConfig struct{ listener net.Listener }

func (c fixedListenConfig) Listen(ctx context.Context, network, address string) (net.Listener, error) {
	return c.listener, nil
}

func (c fixedListenConfig) ListenPacket(ctx context.Context, network, address string) (net.PacketConn, error) {
	return nil, fmt.Errorf("the mieru fixture uses TCP")
}

func StartMieru(t *testing.T) int {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := listener.Addr().(*net.TCPAddr).Port
	server, err := mihomoInbound.NewMieru(&mihomoInbound.MieruOption{
		BaseOption: mihomoInbound.BaseOption{
			NameStr:            "fk-stage3-mieru",
			Listen:             "127.0.0.1",
			Port:               strconv.Itoa(port),
			ListenConfigForAPI: fixedListenConfig{listener},
		},
		Transport:           "TCP",
		Users:               map[string]string{"fkbox": Password},
		UserHintIsMandatory: true,
	})
	if err != nil {
		t.Fatal(err)
	}
	if err = server.Listen(echoTunnel{}); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { server.Close() })
	return port
}

// ---- Xray servers in a child test process ----
//
// The plugin replaces Xray's system dialer process-wide, and an Xray server
// in the same process would dial its freedom outbound through it (and be
// refused: its targets are no plugin's servers). The server therefore runs
// in a child copy of this test binary.

func runXrayFixture(config string) int {
	built, err := serial.LoadJSONConfig(strings.NewReader(config))
	if err != nil {
		fmt.Fprintln(os.Stderr, "xray fixture config:", err)
		return 2
	}
	instance, err := core.New(built)
	if err == nil {
		err = instance.Start()
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "xray fixture start:", err)
		return 2
	}
	fmt.Println("READY")
	_, _ = io.Copy(io.Discard, os.Stdin)
	instance.Close()
	return 0
}

// StartXrayServer runs a VLESS inbound with the given stream settings whose
// freedom outbound redirects every connection to the echo port.
func StartXrayServer(t *testing.T, port int, streamSettings map[string]any, echoPort int) {
	t.Helper()
	StartXrayInbound(t, port, "vless", map[string]any{"clients": []any{map[string]any{"id": UUID}}, "decryption": "none"}, streamSettings, echoPort)
}

// StartXrayInbound runs one Xray inbound (any protocol, its settings and
// stream settings) whose freedom outbound redirects every connection to the
// echo port (stage 7 protocol tests).
func StartXrayInbound(t *testing.T, port int, protocol string, settings map[string]any, streamSettings map[string]any, echoPort int) {
	t.Helper()
	config, err := stdjson.Marshal(map[string]any{
		"log": map[string]any{"loglevel": "warning"},
		"inbounds": []any{map[string]any{
			"tag": "in", "listen": "127.0.0.1", "port": port, "protocol": protocol,
			"settings":       settings,
			"streamSettings": streamSettings,
		}},
		"outbounds": []any{map[string]any{"protocol": "freedom", "tag": "direct", "settings": map[string]any{
			"redirect":   fmt.Sprintf("127.0.0.1:%d", echoPort),
			"finalRules": []any{map[string]any{"action": "allow", "network": "tcp,udp"}},
		}}},
	})
	if err != nil {
		t.Fatal(err)
	}
	command := exec.Command(os.Args[0], "-test.run=^$")
	command.Env = append(os.Environ(), xrayFixtureEnv+"="+string(config))
	command.Stderr = os.Stderr
	stdin, err := command.StdinPipe()
	if err != nil {
		t.Fatal(err)
	}
	stdout, err := command.StdoutPipe()
	if err != nil {
		t.Fatal(err)
	}
	if err = command.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		stdin.Close()
		done := make(chan struct{})
		go func() { _ = command.Wait(); close(done) }()
		select {
		case <-done:
		case <-time.After(5 * time.Second):
			_ = command.Process.Kill()
		}
	})
	ready := make(chan error, 1)
	go func() {
		scanner := bufio.NewScanner(stdout)
		for scanner.Scan() {
			if scanner.Text() == "READY" {
				ready <- nil
				_, _ = io.Copy(io.Discard, stdout)
				return
			}
		}
		ready <- fmt.Errorf("xray fixture exited before READY")
	}()
	select {
	case err = <-ready:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(20 * time.Second):
		t.Fatal("xray fixture did not start")
	}
}

// OpenTunDevice attaches to a persistent TUN device the CI job created for
// this user (ip tuntap add ... user), the way VpnService hands over its fd.
func OpenTunDevice(name string) (int, error) {
	fd, err := unix.Open("/dev/net/tun", unix.O_RDWR|unix.O_CLOEXEC, 0)
	if err != nil {
		return -1, err
	}
	ifr, err := unix.NewIfreq(name)
	if err != nil {
		unix.Close(fd)
		return -1, err
	}
	ifr.SetUint16(unix.IFF_TUN | unix.IFF_NO_PI)
	if err = unix.IoctlIfreq(fd, unix.TUNSETIFF, ifr); err != nil {
		unix.Close(fd)
		return -1, fmt.Errorf("TUNSETIFF %s: %w", name, err)
	}
	return fd, nil
}
