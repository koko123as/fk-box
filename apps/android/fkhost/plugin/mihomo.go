package plugin

import (
	"context"
	"math"
	"net"
	"net/netip"
	"strings"
	"sync"
	"sync/atomic"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/common/dialer"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/bufio"
	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/service"

	mihomoAdapter "github.com/metacubex/mihomo/adapter"
	mihomoC "github.com/metacubex/mihomo/constant"
)

// MihomoOptions: sing-box dial fields (detour, bind, mark, TFO) plus one
// mihomo proxy map, as a Clash `proxies:` entry decodes it.
type MihomoOptions struct {
	option.DialerOptions
	Proxy map[string]any `json:"proxy"`
}

// mihomo proxy types the plugin refuses, with the reason (stage 3 review of
// adapter/outbound in mihomo 1.19.34; the Rust import already refuses the
// first group, the plugin repeats it so a hand-written configuration cannot
// slip them in).
var mihomoRefused = map[string]string{
	"direct":    "use sing-box direct",
	"reject":    "use sing-box block",
	"dns":       "mihomo's DNS outbound relays into mihomo's own resolver (resolver.RelayDnsConn)",
	"rematch":   "re-runs mihomo's rule engine (tunnel)",
	"relay":     "removed in mihomo; chains are sing-box detours",
	"tailscale": "runs its own userspace network and dials targets with dialer.NewDialer outside the injected dialer (decided 2026-11-24: refused)",
	"openvpn":   "dials targets through its own stack and resolver (decided 2026-11-24: refused)",
	"zerotier":  "own userspace network (decided 2026-11-24: refused)",
	"easytier":  "own userspace network and resolver.ProxyServerHostResolver lookups outside the dialer (decided 2026-11-24: refused)",
}

// Mihomo is the `mihomo` outbound: one mihomo proxy built with
// adapter.ParseProxy and WithDialerForAPI, so every socket it opens comes
// from the sing-box dialer of this outbound (BasicOption.NewDialer prefers
// DialerForAPI over mihomo's own dialer, dialer-proxy and the global
// DefaultSocketHook). The dialer is sing-box's protected dialer, or the
// detour outbound when `detour` is set (mihomo `dialer-proxy`).
//
// The map is checked when the configuration is created and the proxy is
// built at start (sing-box 1.15 closes outbounds through the start scope
// only, so nothing that needs closing is made in the constructor).
type Mihomo struct {
	outbound.Adapter
	ctx       context.Context
	logger    log.ContextLogger
	mapping   map[string]any
	dialer    *mihomoDialer
	dnsRouter adapter.DNSRouter
	access    sync.RWMutex
	proxy     mihomoC.Proxy
}

// mihomoDialer adapts a sing-box N.Dialer to mihomo's C.Dialer and counts.
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
	if !rAddrPort.IsValid() {
		return nil, E.New("mihomo: packet dial without a remote address")
	}
	return d.upstream.ListenPacket(ctx, M.SocksaddrFromNetIP(rAddrPort))
}

func NewMihomo(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, options MihomoOptions) (adapter.Outbound, error) {
	if len(options.Proxy) == 0 {
		return nil, E.New("mihomo: missing proxy")
	}
	mapping := normalizeJSONNumbers(options.Proxy).(map[string]any)
	kind, _ := mapping["type"].(string)
	if kind == "" {
		return nil, E.New("mihomo: proxy map without type")
	}
	if reason, refused := mihomoRefused[strings.ToLower(kind)]; refused {
		return nil, E.New("mihomo: type ", kind, " is not supported in the plugin: ", reason)
	}
	if _, exists := mapping["dialer-proxy"]; exists {
		return nil, E.New("mihomo: dialer-proxy must be the outbound's detour")
	}
	if name, _ := mapping["name"].(string); name == "" {
		mapping["name"] = tag
	}
	upstream, err := dialer.NewWithOptions(dialer.Options{
		Context:        ctx,
		Options:        options.DialerOptions,
		RemoteIsDomain: true,
	})
	if err != nil {
		return nil, E.Cause(err, "mihomo: create sing-box dialer")
	}
	adapterDialer := &mihomoDialer{upstream: upstream}
	// Check the map now (configuration errors surface at check/creation),
	// then let go of what mihomo built; Start builds the proxy for real.
	checked, err := mihomoAdapter.ParseProxy(copyMapping(mapping), mihomoAdapter.WithDialerForAPI(adapterDialer))
	if err != nil {
		return nil, E.Cause(err, "mihomo: parse proxy")
	}
	_ = checked.Close()
	return &Mihomo{
		Adapter:   outbound.NewAdapterWithDialerOptions(TypeMihomo, tag, []string{N.NetworkTCP, N.NetworkUDP}, options.DialerOptions),
		ctx:       ctx,
		logger:    logger,
		mapping:   mapping,
		dialer:    adapterDialer,
		dnsRouter: service.FromContext[adapter.DNSRouter](ctx),
	}, nil
}

func (m *Mihomo) Start(stage adapter.StartStage, scope *adapter.Scope) error {
	if stage != adapter.StartStateInitialize {
		return nil
	}
	installMihomoResolver(m.dnsRouter)
	proxy, err := mihomoAdapter.ParseProxy(copyMapping(m.mapping), mihomoAdapter.WithDialerForAPI(m.dialer))
	if err != nil {
		return E.Cause(err, "mihomo: build proxy")
	}
	m.access.Lock()
	m.proxy = proxy
	m.access.Unlock()
	router := m.dnsRouter
	scope.Add(func() error {
		m.access.Lock()
		m.proxy = nil
		m.access.Unlock()
		releaseMihomoResolver(router)
		return proxy.Close()
	})
	return nil
}

func (m *Mihomo) current() (mihomoC.Proxy, error) {
	m.access.RLock()
	defer m.access.RUnlock()
	if m.proxy == nil {
		return nil, E.New("mihomo: outbound ", m.Tag(), " is not started")
	}
	return m.proxy, nil
}

// Dials / PacketDials count the sockets mihomo asked the sing-box dialer for.
func (m *Mihomo) Dials() int64       { return m.dialer.dials.Load() }
func (m *Mihomo) PacketDials() int64 { return m.dialer.packets.Load() }

// ProxyType is mihomo's adapter type name.
func (m *Mihomo) ProxyType() string {
	proxy, err := m.current()
	if err != nil {
		return ""
	}
	return proxy.Type().String()
}

func mihomoMetadata(network mihomoC.NetWork, destination M.Socksaddr) *mihomoC.Metadata {
	metadata := &mihomoC.Metadata{NetWork: network, DstPort: destination.Port}
	if destination.IsFqdn() {
		metadata.Host = destination.Fqdn
	} else {
		metadata.DstIP = destination.Addr.Unmap()
	}
	return metadata
}

func (m *Mihomo) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	proxy, err := m.current()
	if err != nil {
		return nil, err
	}
	switch N.NetworkName(network) {
	case N.NetworkTCP:
		return proxy.DialContext(ctx, mihomoMetadata(mihomoC.TCP, destination))
	case N.NetworkUDP:
		packetConn, address, err := m.ListenPacketWithDestination(ctx, destination)
		if err != nil {
			return nil, err
		}
		target := destination
		if address.IsValid() {
			target = M.SocksaddrFrom(address, destination.Port)
		}
		return bufio.NewBindPacketConn(packetConn, target.UDPAddr()), nil
	default:
		return nil, E.New("mihomo: unsupported network ", network)
	}
}

func (m *Mihomo) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	proxy, err := m.current()
	if err != nil {
		return nil, err
	}
	return m.listenPacket(ctx, proxy, destination)
}

// ListenPacketWithDestination (sing-box dialer.PacketDialerWithDestination):
// mihomo's packet connections address datagrams by IP only (WriteTo takes
// a net.UDPAddr), so a domain destination (a fake-ip connection that
// fkbrain mapped back to its name) is resolved first, through sing-box's
// DNS router (fkdns, the brain: real addresses, the route the rules give
// the name), and the relay opened to that address. The returned address
// lets sing-box's connection manager translate between it and the
// address the app used (bufio.NewNATPacketConn).
func (m *Mihomo) ListenPacketWithDestination(ctx context.Context, destination M.Socksaddr) (net.PacketConn, netip.Addr, error) {
	proxy, err := m.current()
	if err != nil {
		return nil, netip.Addr{}, err
	}
	if !destination.IsFqdn() {
		packetConn, err := m.listenPacket(ctx, proxy, destination)
		return packetConn, netip.Addr{}, err
	}
	if m.dnsRouter == nil {
		return nil, netip.Addr{}, E.New("mihomo: no DNS router to resolve ", destination.Fqdn)
	}
	addresses, err := m.dnsRouter.Lookup(ctx, destination.Fqdn, adapter.DNSQueryOptions{})
	if err != nil {
		return nil, netip.Addr{}, E.Cause(err, "mihomo: resolve ", destination.Fqdn)
	}
	if len(addresses) == 0 {
		return nil, netip.Addr{}, E.New("mihomo: no address for ", destination.Fqdn)
	}
	address := addresses[0].Unmap()
	for _, candidate := range addresses {
		if candidate.Unmap().Is4() {
			address = candidate.Unmap()
			break
		}
	}
	mihomoFQDNPackets.Add(1)
	packetConn, err := m.listenPacket(ctx, proxy, M.SocksaddrFrom(address, destination.Port))
	if err != nil {
		return nil, netip.Addr{}, err
	}
	return packetConn, address, nil
}

var _ dialer.PacketDialerWithDestination = (*Mihomo)(nil)

// MihomoFQDNPackets counts UDP relays opened for a domain destination.
func MihomoFQDNPackets() int64 { return mihomoFQDNPackets.Load() }

var mihomoFQDNPackets atomic.Int64

func (m *Mihomo) listenPacket(ctx context.Context, proxy mihomoC.Proxy, destination M.Socksaddr) (net.PacketConn, error) {
	if !proxy.SupportUDP() {
		return nil, E.New("mihomo: ", m.Tag(), " (", proxy.Type().String(), ") does not relay UDP")
	}
	return proxy.ListenPacketContext(ctx, mihomoMetadata(mihomoC.UDP, destination))
}

func copyMapping(mapping map[string]any) map[string]any {
	return normalizeJSONNumbers(mapping).(map[string]any)
}

// sing-box decodes free-form JSON numbers as float64; mihomo's structure
// decoder wants ints for ports and similar fields. Keys are trimmed.
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
