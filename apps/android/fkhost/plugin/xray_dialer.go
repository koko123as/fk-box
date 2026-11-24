package plugin

import (
	"context"
	stdjson "encoding/json"
	"net"
	"sync"
	"sync/atomic"

	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/bufio"
	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"

	xnet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/transport/internet"
)

// Xray opens every outbound socket through internet.DialSystem, whose
// system dialer is process-wide (transport/internet/system_dialer.go,
// UseAlternativeSystemDialer, "xray:api:stable"; last writer wins, and
// afterwards RegisterDialerController is refused). The plugin installs one
// dialer, once, that picks the sing-box dialer of the outbound the socket
// belongs to:
//
//  1. the dial context still carries it (core.Dial / core.DialUDP pass
//     the plugin's context down to the transport), else
//  2. the destination is a server of exactly one dialer identity among the
//     running xray outbounds (contexts Xray creates itself, such as
//     XHTTP's process-wide connection pool and mux workers, lose the
//     value), else
//  3. the dial fails. Nothing falls back to Xray's own system dialer, so an
//     Xray socket is always a sing-box (protected) one.
//
// UDP replies use net.PacketConnWrapper, the type DefaultSystemDialer
// returns and the XHTTP/QUIC and finalmask paths require (they panic on
// any other type).

type xrayUpstreamKey struct{}

type xrayUpstream struct {
	dialer N.Dialer
	key    string
	tag    string
}

// XrayDialStats counts how the installed dialer chose.
type XrayDialStats struct {
	FromContext     int64
	FromDestination int64
	Refused         int64
}

var (
	xrayDialerOnce sync.Once
	xrayFromCtx    atomic.Int64
	xrayFromDest   atomic.Int64
	xrayRefused    atomic.Int64

	xrayRoutesAccess sync.Mutex
	// server host:port -> dialer identity -> registrations
	xrayRoutes = make(map[string]map[string]*xrayRoute)
)

type xrayRoute struct {
	upstream *xrayUpstream
	refs     int
}

func XrayDialerStats() XrayDialStats {
	return XrayDialStats{FromContext: xrayFromCtx.Load(), FromDestination: xrayFromDest.Load(), Refused: xrayRefused.Load()}
}

func installXrayDialer() {
	xrayDialerOnce.Do(func() {
		internet.UseAlternativeSystemDialer(singBoxSystemDialer{})
	})
}

func dialerKey(options option.DialerOptions) string {
	encoded, err := stdjson.Marshal(options)
	if err != nil {
		return options.Detour
	}
	return string(encoded)
}

func registerXrayServers(servers []string, upstream *xrayUpstream) {
	xrayRoutesAccess.Lock()
	defer xrayRoutesAccess.Unlock()
	for _, server := range servers {
		byKey := xrayRoutes[server]
		if byKey == nil {
			byKey = make(map[string]*xrayRoute)
			xrayRoutes[server] = byKey
		}
		route := byKey[upstream.key]
		if route == nil {
			route = &xrayRoute{}
			byKey[upstream.key] = route
		}
		// The newest registration of an identity serves dials without context.
		route.upstream = upstream
		route.refs++
	}
}

func unregisterXrayServers(servers []string, upstream *xrayUpstream) {
	xrayRoutesAccess.Lock()
	defer xrayRoutesAccess.Unlock()
	for _, server := range servers {
		byKey := xrayRoutes[server]
		route := byKey[upstream.key]
		if route == nil {
			continue
		}
		route.refs--
		if route.refs <= 0 {
			delete(byKey, upstream.key)
		}
		if len(byKey) == 0 {
			delete(xrayRoutes, server)
		}
	}
}

func upstreamForDestination(server string) (*xrayUpstream, error) {
	xrayRoutesAccess.Lock()
	defer xrayRoutesAccess.Unlock()
	byKey := xrayRoutes[server]
	switch len(byKey) {
	case 0:
		return nil, E.New("xray: no running xray outbound connects to ", server)
	case 1:
		for _, route := range byKey {
			return route.upstream, nil
		}
	}
	return nil, E.New("xray: ", len(byKey), " xray outbounds with different dial settings connect to ", server, " and the dial carries no outbound")
}

type singBoxSystemDialer struct{}

func (singBoxSystemDialer) Dial(ctx context.Context, source xnet.Address, destination xnet.Destination, sockopt *internet.SocketConfig) (net.Conn, error) {
	upstream, fromContext := ctx.Value(xrayUpstreamKey{}).(*xrayUpstream)
	if fromContext {
		xrayFromCtx.Add(1)
	} else {
		var err error
		upstream, err = upstreamForDestination(destination.NetAddr())
		if err != nil {
			xrayRefused.Add(1)
			return nil, err
		}
		xrayFromDest.Add(1)
	}
	target := M.ParseSocksaddr(destination.NetAddr())
	if destination.Network == xnet.Network_UDP {
		conn, err := upstream.dialer.DialContext(ctx, N.NetworkUDP, target)
		if err != nil {
			return nil, err
		}
		return &xnet.PacketConnWrapper{PacketConn: bufio.NewUnbindPacketConn(conn), Dest: conn.RemoteAddr()}, nil
	}
	return upstream.dialer.DialContext(ctx, N.NetworkTCP, target)
}

func (singBoxSystemDialer) DestIpAddress() net.IP { return nil }
