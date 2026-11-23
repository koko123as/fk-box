package plugin

import (
	"bytes"
	"context"
	stdjson "encoding/json"
	"net"
	"sync"
	"sync/atomic"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/common/dialer"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"

	xnet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/infra/conf/serial"
	"github.com/xtls/xray-core/transport/internet"

	// Every Xray protocol/transport, as fk-box links today.
	_ "github.com/xtls/xray-core/main/distro/all"
)

// XrayOutboundOptions: "type": "xray" carries one Xray outbound object
// (protocol/settings/streamSettings, as in an Xray config) plus sing-box
// dialer fields for the sockets Xray opens.
type XrayOutboundOptions struct {
	option.DialerOptions
	Outbound map[string]any `json:"outbound"`
}

// XrayOutbound runs an Xray instance that has only this one outbound (no
// inbounds, no routing) and dispatches through it with core.Dial /
// core.DialUDP (core/functions.go, "xray:api:stable" / "beta").
type XrayOutbound struct {
	outbound.Adapter
	instance *core.Instance
	upstream N.Dialer
}

type xrayDialerKey struct{}

func NewXrayOutbound(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, options XrayOutboundOptions) (adapter.Outbound, error) {
	if len(options.Outbound) == 0 {
		return nil, E.New("xray: missing outbound")
	}
	upstream, err := dialer.NewWithOptions(dialer.Options{
		Context:        ctx,
		Options:        options.DialerOptions,
		RemoteIsDomain: true,
	})
	if err != nil {
		return nil, E.Cause(err, "xray: create sing-box dialer")
	}
	instance, err := NewXrayInstance(map[string]any{
		"log":       map[string]any{"loglevel": "warning"},
		"outbounds": []any{withTag(options.Outbound, "proxy")},
	})
	if err != nil {
		return nil, err
	}
	xrayDialerUpstream.CompareAndSwap(nil, &upstreamHolder{upstream})
	return &XrayOutbound{
		Adapter:  outbound.NewAdapterWithDialerOptions(TypeXray, tag, []string{N.NetworkTCP, N.NetworkUDP}, options.DialerOptions),
		instance: instance,
		upstream: upstream,
	}, nil
}

func withTag(object map[string]any, tag string) map[string]any {
	out := make(map[string]any, len(object)+1)
	for key, value := range object {
		out[key] = value
	}
	out["tag"] = tag
	return out
}

// NewXrayInstance builds and starts an Xray instance from a JSON-shaped map.
func NewXrayInstance(config map[string]any) (*core.Instance, error) {
	content, err := stdjson.Marshal(config)
	if err != nil {
		return nil, err
	}
	built, err := serial.LoadJSONConfig(bytes.NewReader(content))
	if err != nil {
		return nil, E.Cause(err, "xray: load config")
	}
	instance, err := core.New(built)
	if err != nil {
		return nil, E.Cause(err, "xray: new instance")
	}
	if err = instance.Start(); err != nil {
		instance.Close()
		return nil, E.Cause(err, "xray: start instance")
	}
	return instance, nil
}

func xrayDestination(network xnet.Network, destination M.Socksaddr) xnet.Destination {
	var address xnet.Address
	if destination.IsFqdn() {
		address = xnet.DomainAddress(destination.Fqdn)
	} else {
		address = xnet.IPAddress(destination.Addr.AsSlice())
	}
	return xnet.Destination{Network: network, Address: address, Port: xnet.Port(destination.Port)}
}

func (x *XrayOutbound) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	if N.NetworkName(network) != N.NetworkTCP {
		return nil, E.New("xray: DialContext only handles tcp, got ", network)
	}
	ctx = context.WithValue(ctx, xrayDialerKey{}, x.upstream)
	return core.Dial(ctx, x.instance, xrayDestination(xnet.Network_TCP, destination))
}

func (x *XrayOutbound) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	ctx = context.WithValue(ctx, xrayDialerKey{}, x.upstream)
	return core.DialUDP(ctx, x.instance)
}

func (x *XrayOutbound) Close() error {
	return x.instance.Close()
}

// ---- protected socket hooks (both are process-wide in Xray) ----

type upstreamHolder struct{ dialer N.Dialer }

var xrayDialerUpstream atomic.Pointer[upstreamHolder]

// XrayDialStats records what the alternative system dialer saw.
type XrayDialStats struct {
	access      sync.Mutex
	ContextHits int64 // dial ctx still carried the plugin's own sing-box dialer
	GlobalHits  int64 // fell back to the process-wide dialer
	Targets     []string
}

var xrayStats XrayDialStats

func XrayStats() (contextHits, globalHits int64, targets []string) {
	xrayStats.access.Lock()
	defer xrayStats.access.Unlock()
	return xrayStats.ContextHits, xrayStats.GlobalHits, append([]string(nil), xrayStats.Targets...)
}

// singBoxSystemDialer replaces Xray's system dialer (transport/internet
// UseAlternativeSystemDialer, "xray:api:stable"). Every socket any Xray
// instance in the process opens then comes from a sing-box dialer: the
// plugin's own when the dial context still carries it, else the global one.
type singBoxSystemDialer struct{}

func (singBoxSystemDialer) Dial(ctx context.Context, source xnet.Address, destination xnet.Destination, sockopt *internet.SocketConfig) (net.Conn, error) {
	upstream, fromContext := ctx.Value(xrayDialerKey{}).(N.Dialer)
	xrayStats.access.Lock()
	if fromContext {
		xrayStats.ContextHits++
	} else {
		xrayStats.GlobalHits++
	}
	xrayStats.Targets = append(xrayStats.Targets, destination.NetAddr())
	xrayStats.access.Unlock()
	if !fromContext {
		holder := xrayDialerUpstream.Load()
		if holder == nil {
			return nil, E.New("xray: no sing-box dialer registered")
		}
		upstream = holder.dialer
	}
	return upstream.DialContext(ctx, destination.Network.SystemString(), M.ParseSocksaddr(destination.NetAddr()))
}

func (singBoxSystemDialer) DestIpAddress() net.IP { return nil }

// UseSingBoxDialerForXray installs singBoxSystemDialer. Process-wide and
// last-writer-wins; after it, internet.RegisterDialerController returns an
// error and its controllers are ignored (transport/internet/system_dialer.go).
func UseSingBoxDialerForXray() {
	internet.UseAlternativeSystemDialer(singBoxSystemDialer{})
}
