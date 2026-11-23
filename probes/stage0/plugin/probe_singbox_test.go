package plugin

import (
	"context"
	"net"
	"net/netip"
	"strings"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/include"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/service"
)

const registryConfig = `{
  "log": {"level": "warn"},
  "dns": {
    "servers": [{"type": "fkdns", "tag": "fk", "address": "10.9.8.7"}],
    "final": "fk"
  },
  "outbounds": [
    {"type": "direct", "tag": "direct-a"},
    {"type": "fkchain", "tag": "chain", "via": "direct-a"}
  ],
  "route": {"final": "chain", "auto_detect_interface": true}
}`

// Item 1: custom outbound + custom DNS transport, both registration routes.
func TestProbeRegistry(t *testing.T) {
	for _, own := range []bool{true, false} {
		name := map[bool]string{true: "own_registries_box_Context", false: "inject_into_include_Context"}[own]
		t.Run(name, func(t *testing.T) {
			ctx, _, err := NewProbeContext(own)
			if err != nil {
				t.Fatal(err)
			}
			instance, err := NewBox(ctx, registryConfig)
			if err != nil {
				t.Fatal(err)
			}
			defer instance.Close()
			if err = instance.Start(); err != nil {
				t.Fatal(err)
			}
			chain, loaded := instance.Outbound().Outbound("chain")
			if !loaded || chain.Type() != TypeChain {
				t.Fatalf("fkchain outbound not built: %v", loaded)
			}
			router := service.FromContext[adapter.DNSRouter](ctx)
			if router == nil {
				t.Fatal("no DNS router in context")
			}
			lookupCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
			defer cancel()
			addresses, err := router.Lookup(lookupCtx, "probe.fk-box.test", adapter.DNSQueryOptions{})
			if err != nil {
				t.Fatal(err)
			}
			if len(addresses) == 0 || addresses[0] != netip.MustParseAddr("10.9.8.7") {
				t.Fatalf("fkdns answer not used: %v", addresses)
			}
			t.Logf("PROBE item1 %s: fkchain outbound and fkdns transport built; lookup -> %v", name, addresses)
		})
	}
}

// Item 1 (negative): a fresh include.Context (what libbox's baseContext and
// CheckConfig build on every call) does not know the plugin types.
func TestProbeRegistryFreshContextRejects(t *testing.T) {
	ctx := include.Context(service.ContextWithDefaultRegistry(context.Background()))
	_, err := ParseOptions(ctx, registryConfig)
	if err == nil {
		t.Fatal("plugin types parsed in a fresh include.Context")
	}
	t.Logf("PROBE item1 fresh include.Context rejects plugin types: %v", err)
}

// Item 2: a plugin outbound looks up another outbound by tag through
// adapter.OutboundManager and dials through it; the socket is protected.
func TestProbeChainDialsByTag(t *testing.T) {
	echo, err := StartEchoServer()
	if err != nil {
		t.Fatal(err)
	}
	defer echo.Close()
	ctx, platform, err := NewProbeContext(true)
	if err != nil {
		t.Fatal(err)
	}
	instance, err := NewBox(ctx, registryConfig)
	if err != nil {
		t.Fatal(err)
	}
	defer instance.Close()
	if err = instance.Start(); err != nil {
		t.Fatal(err)
	}
	outbound, _ := instance.Outbound().Outbound("chain")
	before := platform.Protected()
	conn, err := outbound.DialContext(ctx, "tcp", M.ParseSocksaddr(echo.Addr().String()))
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	if err = EchoRoundTrip(conn, []byte("fk-box stage0 chain")); err != nil {
		t.Fatal(err)
	}
	chain := outbound.(*ChainOutbound)
	if chain.Dials() != 1 {
		t.Fatalf("chain dials = %d", chain.Dials())
	}
	if platform.Protected() <= before {
		t.Fatal("direct socket did not pass AutoDetectInterfaceControl (protect)")
	}
	t.Logf("PROBE item2 fkchain -> direct-a by tag: echo ok, protected sockets %d", platform.Protected()-before)
}

const brainConfig = `{
  "log": {"level": "warn"},
  "outbounds": [
    {"type": "direct", "tag": "direct"},
    {"type": "block", "tag": "block"},
    {"type": "fkbrain", "tag": "brain", "outbounds": ["direct", "block"]}
  ],
  "route": {"final": "brain", "auto_detect_interface": true}
}`

// Item 2 (routing form): the router hands whole connections to fkbrain, which
// asks the decider and forwards to the chosen outbound by tag.
func TestProbeBrainRoutesConnections(t *testing.T) {
	echo, err := StartEchoServer()
	if err != nil {
		t.Fatal(err)
	}
	defer echo.Close()
	echoPort := uint16(echo.Addr().(*net.TCPAddr).Port)
	SetDecider(func(metadata *adapter.InboundContext) Decision {
		if metadata.Destination.Port == echoPort {
			return Decision{Index: 0}
		}
		return Decision{Reject: true}
	})
	ctx, _, err := NewProbeContext(true)
	if err != nil {
		t.Fatal(err)
	}
	instance, err := NewBox(ctx, brainConfig)
	if err != nil {
		t.Fatal(err)
	}
	defer instance.Close()
	if err = instance.Start(); err != nil {
		t.Fatal(err)
	}
	if err = RouteThrough(ctx, instance.Router(), echo.Addr().String(), []byte("routed by fkbrain")); err != nil {
		t.Fatal(err)
	}
	if err = RouteThrough(ctx, instance.Router(), "127.0.0.1:9", []byte("x")); err == nil {
		t.Fatal("rejected destination still connected")
	}
	brain, _ := instance.Outbound().Outbound("brain")
	b := brain.(*BrainOutbound)
	if b.Decided() != 2 || b.Rejected() != 1 {
		t.Fatalf("decided=%d rejected=%d", b.Decided(), b.Rejected())
	}
	t.Logf("PROBE item2 fkbrain routed 1 connection to direct, rejected 1")
}

func containsTarget(targets []string, target string) bool {
	for _, item := range targets {
		if strings.EqualFold(item, target) {
			return true
		}
	}
	return false
}
