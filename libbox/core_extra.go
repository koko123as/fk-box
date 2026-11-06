package libbox

import (
	"context"
	"encoding/json"
	"fmt"
	"net"

	"github.com/sagernet/sing-box/daemon"

	mihomoYAML "github.com/metacubex/mihomo/common/yaml"
	mihomoInner "github.com/metacubex/mihomo/listener/inner"
	mihomoTunnel "github.com/metacubex/mihomo/tunnel"
	xrayNet "github.com/xtls/xray-core/common/net"
	xrayCore "github.com/xtls/xray-core/core"

	"google.golang.org/grpc"
	"google.golang.org/protobuf/types/known/emptypb"
)

// dialThroughCore opens a TCP connection routed by the running Xray or mihomo
// core exactly like app traffic (rules, selected node), for the dashboard's
// egress IP check.
func dialThroughCore(ctx context.Context, running *runningCore, network, address string) (net.Conn, error) {
	switch running.core {
	case CoreXray:
		if running.xray == nil {
			return nil, fmt.Errorf("xray is not running")
		}
		destination, err := xrayNet.ParseDestination("tcp:" + address)
		if err != nil {
			return nil, err
		}
		return xrayCore.Dial(ctx, running.xray, destination)
	case CoreMihomo:
		conn, err := mihomoInner.HandleTcp(mihomoTunnel.Tunnel, address, "")
		if err != nil {
			return nil, err
		}
		stop := context.AfterFunc(ctx, func() { conn.Close() })
		return &afterFuncConn{Conn: conn, stop: stop}, nil
	default:
		return nil, fmt.Errorf("unknown core: %s", running.core)
	}
}

type afterFuncConn struct {
	net.Conn
	stop func() bool
}

func (c *afterFuncConn) Close() error {
	c.stop()
	return c.Conn.Close()
}

// idleWhileOtherCore serves a sing-box-only status stream (Tailscale,
// OpenConnect, OpenVPN, Taildrop): while Xray or mihomo runs there is nothing
// to report, so the stream stays open and empty instead of failing with
// "invalid argument" (the sing-box service itself is idle then).
func idleWhileOtherCore[T any](server grpc.ServerStreamingServer[T], singBox func(grpc.ServerStreamingServer[T]) error) error {
	return switchStream(server, singBox, func(ctx context.Context, _ *runningCore, _ func(*T) error) error {
		<-ctx.Done()
		return ctx.Err()
	})
}

func (s *coreStartedService) SubscribeTailscaleStatus(empty *emptypb.Empty, server grpc.ServerStreamingServer[daemon.TailscaleStatusUpdate]) error {
	return idleWhileOtherCore(server, func(stream grpc.ServerStreamingServer[daemon.TailscaleStatusUpdate]) error {
		return s.StartedService.SubscribeTailscaleStatus(empty, stream)
	})
}

func (s *coreStartedService) SubscribeOpenConnectStatus(empty *emptypb.Empty, server grpc.ServerStreamingServer[daemon.OpenConnectStatusUpdate]) error {
	return idleWhileOtherCore(server, func(stream grpc.ServerStreamingServer[daemon.OpenConnectStatusUpdate]) error {
		return s.StartedService.SubscribeOpenConnectStatus(empty, stream)
	})
}

func (s *coreStartedService) SubscribeOpenVPNStatus(empty *emptypb.Empty, server grpc.ServerStreamingServer[daemon.OpenVPNStatusUpdate]) error {
	return idleWhileOtherCore(server, func(stream grpc.ServerStreamingServer[daemon.OpenVPNStatusUpdate]) error {
		return s.StartedService.SubscribeOpenVPNStatus(empty, stream)
	})
}

func (s *coreStartedService) SubscribeTaildropInbox(request *daemon.SubscribeTaildropInboxRequest, server grpc.ServerStreamingServer[daemon.TaildropInbox]) error {
	return idleWhileOtherCore(server, func(stream grpc.ServerStreamingServer[daemon.TaildropInbox]) error {
		return s.StartedService.SubscribeTaildropInbox(request, stream)
	})
}

// CoreProfileStats counts a profile's groups, outbounds and rules for the
// dashboard card, as JSON {"groups":n,"outbounds":n,"rules":n}. mihomo:
// proxy-groups / proxies (+ providers) / rules; Xray: routing.balancers /
// outbounds / routing.rules.
func CoreProfileStats(core string, content string) (string, error) {
	var groups, outbounds, rules int
	switch core {
	case CoreMihomo:
		var root struct {
			Proxies   []any          `yaml:"proxies"`
			Providers map[string]any `yaml:"proxy-providers"`
			Groups    []any          `yaml:"proxy-groups"`
			Rules     []any          `yaml:"rules"`
		}
		if err := mihomoYAML.Unmarshal([]byte(normalizeCoreContent(content)), &root); err != nil {
			return "", err
		}
		groups, outbounds, rules = len(root.Groups), len(root.Proxies)+len(root.Providers), len(root.Rules)
	case CoreXray:
		var root struct {
			Outbounds []json.RawMessage `json:"outbounds"`
			Routing   struct {
				Balancers []json.RawMessage `json:"balancers"`
				Rules     []json.RawMessage `json:"rules"`
			} `json:"routing"`
		}
		if err := json.Unmarshal([]byte(normalizeCoreContent(content)), &root); err != nil {
			return "", err
		}
		groups, outbounds, rules = len(root.Routing.Balancers), len(root.Outbounds), len(root.Routing.Rules)
	default:
		return "", fmt.Errorf("unsupported core: %s", core)
	}
	encoded, err := json.Marshal(map[string]int{"groups": groups, "outbounds": outbounds, "rules": rules})
	return string(encoded), err
}
