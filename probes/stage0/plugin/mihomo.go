package plugin

import (
	"context"
	"math"
	"net"
	"net/netip"
	"strings"
	"sync/atomic"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/common/dialer"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"

	mihomoAdapter "github.com/metacubex/mihomo/adapter"
	mihomoC "github.com/metacubex/mihomo/constant"
)

// MihomoOutboundOptions: "type": "mihomo" carries one mihomo proxy mapping
// (the same map a Clash YAML "proxies:" entry decodes to) plus the usual
// sing-box dialer fields, which decide how the proxy's own sockets are made.
type MihomoOutboundOptions struct {
	option.DialerOptions
	Proxy map[string]any `json:"proxy"`
}

type MihomoOutbound struct {
	outbound.Adapter
	proxy  mihomoC.Proxy
	dialer *mihomoDialer
}

// mihomoDialer adapts a sing-box N.Dialer to mihomo's C.Dialer. It is passed
// per proxy through adapter.WithDialerForAPI (adapter/parser.go), which
// BasicOption.NewDialer prefers over mihomo's own dialer and over the global
// dialer.DefaultSocketHook (adapter/outbound/base.go).
type mihomoDialer struct {
	upstream N.Dialer
	dials    atomic.Int64
	packets  atomic.Int64
}

var _ mihomoC.Dialer = (*mihomoDialer)(nil)

func (d *mihomoDialer) DialContext(ctx context.Context, network, address string) (net.Conn, error) {
	d.dials.Add(1)
	return d.upstream.DialContext(ctx, N.NetworkName(network), M.ParseSocksaddr(address))
}

func (d *mihomoDialer) ListenPacket(ctx context.Context, network, address string, rAddrPort netip.AddrPort) (net.PacketConn, error) {
	d.packets.Add(1)
	return d.upstream.ListenPacket(ctx, M.SocksaddrFromNetIP(rAddrPort))
}

func NewMihomoOutbound(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, options MihomoOutboundOptions) (adapter.Outbound, error) {
	if len(options.Proxy) == 0 {
		return nil, E.New("mihomo: missing proxy")
	}
	upstream, err := dialer.NewWithOptions(dialer.Options{
		Context:        ctx,
		Options:        options.DialerOptions,
		RemoteIsDomain: true,
	})
	if err != nil {
		return nil, E.Cause(err, "mihomo: create sing-box dialer")
	}
	mapping := normalizeJSONNumbers(options.Proxy).(map[string]any)
	if _, hasName := mapping["name"]; !hasName {
		mapping["name"] = tag
	}
	adapterDialer := &mihomoDialer{upstream: upstream}
	proxy, err := mihomoAdapter.ParseProxy(mapping, mihomoAdapter.WithDialerForAPI(adapterDialer))
	if err != nil {
		return nil, E.Cause(err, "mihomo: parse proxy")
	}
	return &MihomoOutbound{
		Adapter: outbound.NewAdapterWithDialerOptions(TypeMihomo, tag, []string{N.NetworkTCP, N.NetworkUDP}, options.DialerOptions),
		proxy:   proxy,
		dialer:  adapterDialer,
	}, nil
}

// Dials reports how many TCP dials mihomo made through the sing-box dialer.
func (m *MihomoOutbound) Dials() int64 { return m.dialer.dials.Load() }

func (m *MihomoOutbound) ProxyType() string { return m.proxy.Type().String() }

func mihomoMetadata(network mihomoC.NetWork, destination M.Socksaddr) *mihomoC.Metadata {
	metadata := &mihomoC.Metadata{NetWork: network, DstPort: destination.Port}
	if destination.IsFqdn() {
		metadata.Host = destination.Fqdn
	} else {
		metadata.DstIP = destination.Addr
	}
	return metadata
}

func (m *MihomoOutbound) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	if N.NetworkName(network) != N.NetworkTCP {
		return nil, E.New("mihomo: DialContext only handles tcp, got ", network)
	}
	return m.proxy.DialContext(ctx, mihomoMetadata(mihomoC.TCP, destination))
}

func (m *MihomoOutbound) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	return m.proxy.ListenPacketContext(ctx, mihomoMetadata(mihomoC.UDP, destination))
}

func (m *MihomoOutbound) Close() error {
	return m.proxy.Close()
}

// sing-box decodes free-form JSON numbers as float64; mihomo's structure
// decoder wants ints for ports and similar fields.
func normalizeJSONNumbers(value any) any {
	switch typed := value.(type) {
	case map[string]any:
		out := make(map[string]any, len(typed))
		for key, item := range typed {
			out[strings.TrimSpace(key)] = normalizeJSONNumbers(item)
		}
		return out
	case []any:
		out := make([]any, len(typed))
		for i, item := range typed {
			out[i] = normalizeJSONNumbers(item)
		}
		return out
	case float64:
		if typed == math.Trunc(typed) && math.Abs(typed) < 1<<53 {
			return int(typed)
		}
		return typed
	default:
		return value
	}
}
