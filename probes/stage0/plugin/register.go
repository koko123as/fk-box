package plugin

import (
	"context"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/dns"
	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/option"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/json"
	"github.com/sagernet/sing/service"
)

// Plugin type names as they appear in sing-box JSON ("type": "...").
const (
	TypeMihomo = "mihomo"
	TypeXray   = "xray"
	TypeChain  = "fkchain"
	TypeBrain  = "fkbrain"
	TypeFKDNS  = "fkdns"
)

// RegisterOutbounds adds the plugin outbound types to a sing-box outbound
// registry through the exported outbound.Register (adapter/outbound/registry.go).
func RegisterOutbounds(registry *outbound.Registry) {
	outbound.Register[MihomoOutboundOptions](registry, TypeMihomo, NewMihomoOutbound)
	outbound.Register[XrayOutboundOptions](registry, TypeXray, NewXrayOutbound)
	outbound.Register[ChainOutboundOptions](registry, TypeChain, NewChainOutbound)
	outbound.Register[BrainOutboundOptions](registry, TypeBrain, NewBrainOutbound)
}

// RegisterDNSTransports adds the probe DNS transport through the exported
// dns.RegisterTransport (dns/transport_registry.go).
func RegisterDNSTransports(registry *dns.TransportRegistry) {
	dns.RegisterTransport[FKDNSOptions](registry, TypeFKDNS, NewFKDNSTransport)
}

// NewContextOwnRegistries is route (b) in the report: the host builds its own
// registries (upstream include.*Registry plus the plugins) and hands them to
// box.Context. No libbox code is involved.
func NewContextOwnRegistries(parent context.Context) context.Context {
	outboundRegistry := include.OutboundRegistry()
	RegisterOutbounds(outboundRegistry)
	dnsRegistry := include.DNSTransportRegistry()
	RegisterDNSTransports(dnsRegistry)
	return box.Context(parent, include.InboundRegistry(), outboundRegistry, include.EndpointRegistry(), dnsRegistry, include.ServiceRegistry(), include.CertificateProviderRegistry())
}

// InjectIntoContext is route (a): the registries already sit in a context
// built by include.Context (as libbox's baseContext does); they are pointers,
// so registering into the ones found in the context changes what that context
// parses and builds. It fails if the registries are not the upstream types.
func InjectIntoContext(ctx context.Context) error {
	outboundRegistry, ok := service.FromContext[adapter.OutboundRegistry](ctx).(*outbound.Registry)
	if !ok {
		return E.New("outbound registry in context is not *outbound.Registry")
	}
	dnsRegistry, ok := service.FromContext[adapter.DNSTransportRegistry](ctx).(*dns.TransportRegistry)
	if !ok {
		return E.New("DNS transport registry in context is not *dns.TransportRegistry")
	}
	RegisterOutbounds(outboundRegistry)
	RegisterDNSTransports(dnsRegistry)
	return nil
}

// ParseOptions decodes a sing-box JSON config against the registries in ctx,
// the same call libbox's parseConfig makes.
func ParseOptions(ctx context.Context, content string) (option.Options, error) {
	return json.UnmarshalExtendedContext[option.Options](ctx, []byte(content))
}

// NewBox parses and creates (but does not start) a box in ctx.
func NewBox(ctx context.Context, content string) (*box.Box, error) {
	options, err := ParseOptions(ctx, content)
	if err != nil {
		return nil, E.Cause(err, "decode config")
	}
	return box.New(box.Options{Context: ctx, Options: options})
}

func includeContext(ctx context.Context) context.Context {
	return include.Context(ctx)
}
