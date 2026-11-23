package stage2

import (
	"context"
	"net"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/dns"
	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/json"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/service"

	mDNS "github.com/miekg/dns"
)

// Type names of docs/architecture/brain-config.md.
const (
	TypeBrain  = "fkbrain"
	TypeFKDNS  = "fkdns"
	TypeMihomo = "mihomo"
	TypeXray   = "xray"
)

// BrainOptions: the outbound table decide() indexes into.
type BrainOptions struct {
	Outbounds []string `json:"outbounds"`
}

// FKDNSOptions: no options in the stage 2 contract.
type FKDNSOptions struct{}

// MihomoOptions: sing-box dial fields (detour) plus one mihomo proxy map.
type MihomoOptions struct {
	option.DialerOptions
	Proxy map[string]any `json:"proxy"`
}

// XrayOptions: sing-box dial fields (detour) plus one Xray outbound object.
type XrayOptions struct {
	option.DialerOptions
	Outbound map[string]any `json:"outbound"`
}

// placeholder is an outbound that only exists to be parsed and created.
type placeholder struct {
	outbound.Adapter
}

func (p *placeholder) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	return nil, E.New("stage 2 placeholder outbound ", p.Type(), " does not dial")
}

func (p *placeholder) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	return nil, E.New("stage 2 placeholder outbound ", p.Type(), " does not dial")
}

func newBrain(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, options BrainOptions) (adapter.Outbound, error) {
	if len(options.Outbounds) == 0 {
		return nil, E.New("fkbrain: empty outbound table")
	}
	seen := make(map[string]bool, len(options.Outbounds))
	for _, name := range options.Outbounds {
		if name == "" || seen[name] {
			return nil, E.New("fkbrain: empty or duplicate tag in the outbound table: ", name)
		}
		seen[name] = true
	}
	return &placeholder{Adapter: outbound.NewAdapter(TypeBrain, tag, []string{N.NetworkTCP, N.NetworkUDP}, options.Outbounds)}, nil
}

func newMihomo(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, options MihomoOptions) (adapter.Outbound, error) {
	name, _ := options.Proxy["name"].(string)
	kind, _ := options.Proxy["type"].(string)
	if name == "" || kind == "" {
		return nil, E.New("mihomo: proxy map without name or type")
	}
	if _, exists := options.Proxy["dialer-proxy"]; exists {
		return nil, E.New("mihomo: dialer-proxy must be the outbound's detour")
	}
	return &placeholder{Adapter: outbound.NewAdapterWithDialerOptions(TypeMihomo, tag, []string{N.NetworkTCP, N.NetworkUDP}, options.DialerOptions)}, nil
}

func newXray(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, options XrayOptions) (adapter.Outbound, error) {
	protocol, _ := options.Outbound["protocol"].(string)
	if protocol == "" {
		return nil, E.New("xray: outbound without protocol")
	}
	if _, ok := options.Outbound["settings"].(map[string]any); !ok {
		return nil, E.New("xray: outbound without settings")
	}
	if stream, ok := options.Outbound["streamSettings"].(map[string]any); ok {
		if tls, ok := stream["tlsSettings"].(map[string]any); ok {
			if _, exists := tls["allowInsecure"]; exists {
				return nil, E.New("xray: allowInsecure was removed in Xray 26")
			}
		}
	}
	return &placeholder{Adapter: outbound.NewAdapterWithDialerOptions(TypeXray, tag, []string{N.NetworkTCP, N.NetworkUDP}, options.DialerOptions)}, nil
}

// fkdnsTransport answers nothing; the stage 2 check only creates it.
type fkdnsTransport struct {
	dns.TransportAdapter
}

var _ adapter.DNSTransport = (*fkdnsTransport)(nil)

func newFKDNS(ctx context.Context, logger log.ContextLogger, tag string, options FKDNSOptions) (adapter.DNSTransport, error) {
	return &fkdnsTransport{TransportAdapter: dns.NewTransportAdapter(TypeFKDNS, tag, nil)}, nil
}

func (t *fkdnsTransport) Start(stage adapter.StartStage, scope *adapter.Scope) error { return nil }

func (t *fkdnsTransport) Reset() {}

func (t *fkdnsTransport) Exchange(ctx context.Context, message *mDNS.Msg) (*mDNS.Msg, error) {
	return nil, E.New("stage 2 placeholder fkdns does not resolve")
}

func (t *fkdnsTransport) ExchangeAsync(ctx context.Context, message *mDNS.Msg, callback func(response *mDNS.Msg, err error)) {
	callback(t.Exchange(ctx, message))
}

// NewContext builds a box context on the upstream registries plus the four
// placeholder types (public outbound.Register / dns.RegisterTransport /
// box.Context, as stage 0), with the probe platform registered.
func NewContext() (context.Context, *Platform) {
	ctx := service.ContextWithDefaultRegistry(context.Background())
	outboundRegistry := include.OutboundRegistry()
	outbound.Register[BrainOptions](outboundRegistry, TypeBrain, newBrain)
	outbound.Register[MihomoOptions](outboundRegistry, TypeMihomo, newMihomo)
	outbound.Register[XrayOptions](outboundRegistry, TypeXray, newXray)
	dnsRegistry := include.DNSTransportRegistry()
	dns.RegisterTransport[FKDNSOptions](dnsRegistry, TypeFKDNS, newFKDNS)
	ctx = box.Context(ctx, include.InboundRegistry(), outboundRegistry, include.EndpointRegistry(), dnsRegistry, include.ServiceRegistry(), include.CertificateProviderRegistry())
	platform := new(Platform)
	service.MustRegister[adapter.PlatformInterface](ctx, platform)
	return ctx, platform
}

// ParseOptions decodes a configuration against the registries in ctx.
func ParseOptions(ctx context.Context, content []byte) (option.Options, error) {
	return json.UnmarshalExtendedContext[option.Options](ctx, content)
}
