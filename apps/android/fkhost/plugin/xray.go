package plugin

import (
	"bytes"
	"context"
	stdjson "encoding/json"
	"net"
	"strconv"
	"strings"
	"sync"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/common/dialer"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/bufio"
	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"

	xnet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/infra/conf/serial"

	// Every Xray protocol and transport, as fk-box links today.
	_ "github.com/xtls/xray-core/main/distro/all"
)

// XrayOptions: sing-box dial fields (detour, bind, mark) plus one Xray
// outbound object (protocol/settings/streamSettings).
type XrayOptions struct {
	option.DialerOptions
	Outbound map[string]any `json:"outbound"`
}

// Protocols and transports the plugin runs. Everything else is refused
// with a reason (stage 3 review of Xray-core 26.11.9):
//   - wireguard: resolves its peer with Xray's process-wide DNS client
//     (proxy/wireguard/client.go LookupIP) and may open a kernel TUN;
//     sing-box's native WireGuard endpoint covers it.
//   - hysteria (transport and protocol), masque: listen with
//     internet.ListenSystemPacket, which the alternative system dialer
//     does not cover, so their sockets would skip sing-box's protect.
//   - freedom, blackhole, dns, loopback: not proxy outbounds (sing-box
//     direct/block, the brain's DNS).
var (
	xrayProtocols  = map[string]bool{"vless": true, "vmess": true, "trojan": true, "shadowsocks": true}
	xrayTransports = map[string]bool{
		"": true, "tcp": true, "raw": true, "ws": true, "websocket": true, "httpupgrade": true,
		"grpc": true, "gun": true, "xhttp": true, "splithttp": true, "kcp": true, "mkcp": true,
	}
)

// Xray is the `xray` outbound: an Xray instance holding only this outbound
// (no inbounds, no routing, no DNS), dispatched with core.Dial /
// core.DialUDP. Its sockets come from internet.DialSystem, which the plugin
// redirects process-wide to sing-box dialers (xray_dialer.go).
type Xray struct {
	outbound.Adapter
	logger   log.ContextLogger
	config   []byte
	servers  []string
	upstream *xrayUpstream
	access   sync.RWMutex
	instance *core.Instance
}

func NewXray(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, options XrayOptions) (adapter.Outbound, error) {
	if len(options.Outbound) == 0 {
		return nil, E.New("xray: missing outbound")
	}
	if err := checkXrayOutbound(options.Outbound); err != nil {
		return nil, E.Cause(err, "xray")
	}
	servers := xrayServers(options.Outbound)
	if len(servers) == 0 {
		return nil, E.New("xray: outbound without a server address")
	}
	upstream, err := dialer.NewWithOptions(dialer.Options{
		Context:        ctx,
		Options:        options.DialerOptions,
		RemoteIsDomain: true,
	})
	if err != nil {
		return nil, E.Cause(err, "xray: create sing-box dialer")
	}
	config, err := stdjson.Marshal(map[string]any{
		"log":       map[string]any{"loglevel": "warning"},
		"outbounds": []any{withTag(options.Outbound, "proxy")},
	})
	if err != nil {
		return nil, err
	}
	// Build Xray's configuration now so errors surface when the
	// configuration is checked; the instance itself is made at start.
	if _, err = serial.LoadJSONConfig(bytes.NewReader(config)); err != nil {
		return nil, E.Cause(err, "xray: load outbound")
	}
	installXrayDialer()
	return &Xray{
		Adapter:  outbound.NewAdapterWithDialerOptions(TypeXray, tag, []string{N.NetworkTCP, N.NetworkUDP}, options.DialerOptions),
		logger:   logger,
		config:   config,
		servers:  servers,
		upstream: &xrayUpstream{dialer: upstream, key: dialerKey(options.DialerOptions), tag: tag},
	}, nil
}

func (x *Xray) Start(stage adapter.StartStage, scope *adapter.Scope) error {
	if stage != adapter.StartStateInitialize {
		return nil
	}
	built, err := serial.LoadJSONConfig(bytes.NewReader(x.config))
	if err != nil {
		return E.Cause(err, "xray: load outbound")
	}
	instance, err := core.New(built)
	if err != nil {
		return E.Cause(err, "xray: new instance")
	}
	if err = instance.Start(); err != nil {
		_ = instance.Close()
		return E.Cause(err, "xray: start instance")
	}
	registerXrayServers(x.servers, x.upstream)
	x.access.Lock()
	x.instance = instance
	x.access.Unlock()
	scope.Add(func() error {
		x.access.Lock()
		x.instance = nil
		x.access.Unlock()
		unregisterXrayServers(x.servers, x.upstream)
		return instance.Close()
	})
	return nil
}

func (x *Xray) current() (*core.Instance, error) {
	x.access.RLock()
	defer x.access.RUnlock()
	if x.instance == nil {
		return nil, E.New("xray: outbound ", x.Tag(), " is not started")
	}
	return x.instance, nil
}

// Servers are the host:port pairs this outbound's sockets go to.
func (x *Xray) Servers() []string { return append([]string(nil), x.servers...) }

func xrayDestination(network xnet.Network, destination M.Socksaddr) xnet.Destination {
	var address xnet.Address
	if destination.IsFqdn() {
		address = xnet.DomainAddress(destination.Fqdn)
	} else {
		address = xnet.IPAddress(destination.Addr.Unmap().AsSlice())
	}
	return xnet.Destination{Network: network, Address: address, Port: xnet.Port(destination.Port)}
}

func (x *Xray) withUpstream(ctx context.Context) context.Context {
	return context.WithValue(ctx, xrayUpstreamKey{}, x.upstream)
}

func (x *Xray) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	instance, err := x.current()
	if err != nil {
		return nil, err
	}
	switch N.NetworkName(network) {
	case N.NetworkTCP:
		return core.Dial(x.withUpstream(ctx), instance, xrayDestination(xnet.Network_TCP, destination))
	case N.NetworkUDP:
		return core.Dial(x.withUpstream(ctx), instance, xrayDestination(xnet.Network_UDP, destination))
	default:
		return nil, E.New("xray: unsupported network ", network)
	}
}

// ListenPacket: core.DialUDP takes any IP destination per packet; its
// WriteTo only understands IP addresses (common/net DestinationFromAddr),
// so a domain destination (fake-ip, stage 4) is one core.Dial UDP stream.
func (x *Xray) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	instance, err := x.current()
	if err != nil {
		return nil, err
	}
	if destination.IsFqdn() {
		conn, err := core.Dial(x.withUpstream(ctx), instance, xrayDestination(xnet.Network_UDP, destination))
		if err != nil {
			return nil, err
		}
		return bufio.NewUnbindPacketConnWithAddr(conn, destination), nil
	}
	return core.DialUDP(x.withUpstream(ctx), instance)
}

func withTag(object map[string]any, tag string) map[string]any {
	out := make(map[string]any, len(object)+1)
	for key, value := range object {
		out[key] = value
	}
	out["tag"] = tag
	return out
}

func asObject(value any) map[string]any {
	object, _ := value.(map[string]any)
	return object
}

// checkXrayOutbound refuses what would leave sing-box's dialer or use one
// of Xray's process-wide singletons (the last instance's DNS client and
// outbound manager in transport/internet: dialer.go InitSystemDialer).
func checkXrayOutbound(object map[string]any) error {
	protocol, _ := object["protocol"].(string)
	if !xrayProtocols[strings.ToLower(protocol)] {
		return E.New("protocol ", strconv.Quote(protocol), " is not supported in the xray plugin")
	}
	settings := asObject(object["settings"])
	if settings == nil {
		return E.New("outbound without settings")
	}
	if strategy, exists := settings["domainStrategy"]; exists && !isAsIs(strategy) {
		return E.New("settings.domainStrategy uses Xray's DNS; the brain resolves")
	}
	stream := asObject(object["streamSettings"])
	if stream == nil {
		return nil
	}
	network, _ := stream["network"].(string)
	if !xrayTransports[strings.ToLower(network)] {
		return E.New("transport ", strconv.Quote(network), " is not supported in the xray plugin")
	}
	if tls := asObject(stream["tlsSettings"]); tls != nil {
		if _, exists := tls["allowInsecure"]; exists {
			return E.New("allowInsecure was removed in Xray 26")
		}
	}
	if sockopt := asObject(stream["sockopt"]); sockopt != nil {
		for _, key := range []string{"dialerProxy", "addressPortStrategy", "happyEyeballs"} {
			if _, exists := sockopt[key]; exists {
				return E.New("streamSettings.sockopt.", key, " is not supported (chains are sing-box detours, names resolve in the brain)")
			}
		}
		if strategy, exists := sockopt["domainStrategy"]; exists && !isAsIs(strategy) {
			return E.New("streamSettings.sockopt.domainStrategy uses Xray's DNS; the brain resolves")
		}
	}
	if finalMask, exists := stream["finalmask"]; exists {
		encoded, _ := stdjson.Marshal(finalMask)
		if bytes.Contains(bytes.ToLower(encoded), []byte(`"realm"`)) {
			return E.New("finalmask realm (STUN) resolves with the system resolver")
		}
	}
	return nil
}

func isAsIs(value any) bool {
	text, _ := value.(string)
	return text == "" || strings.EqualFold(text, "AsIs")
}

func hostPort(address any, port any) string {
	host, _ := address.(string)
	if host == "" {
		return ""
	}
	var portText string
	switch typed := port.(type) {
	case float64:
		portText = strconv.Itoa(int(typed))
	case int:
		portText = strconv.Itoa(typed)
	case string:
		portText = typed
	}
	if portText == "" || portText == "0" {
		return ""
	}
	return net.JoinHostPort(strings.Trim(host, "[]"), portText)
}

// xrayServers lists where the outbound connects: the simplified
// settings.address/port, vnext/servers entries, and an XHTTP download
// side (xhttpSettings.extra.downloadSettings).
func xrayServers(object map[string]any) []string {
	var servers []string
	add := func(entry string) {
		if entry == "" {
			return
		}
		for _, existing := range servers {
			if existing == entry {
				return
			}
		}
		servers = append(servers, entry)
	}
	settings := asObject(object["settings"])
	add(hostPort(settings["address"], settings["port"]))
	for _, key := range []string{"vnext", "servers"} {
		list, _ := settings[key].([]any)
		for _, item := range list {
			entry := asObject(item)
			add(hostPort(entry["address"], entry["port"]))
		}
	}
	stream := asObject(object["streamSettings"])
	for _, key := range []string{"xhttpSettings", "splithttpSettings"} {
		download := asObject(asObject(asObject(stream[key])["extra"])["downloadSettings"])
		if download != nil {
			add(hostPort(download["address"], download["port"]))
		}
	}
	return servers
}
