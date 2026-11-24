// Package plugin holds the four sing-box plugin types of the single-kernel
// design (docs/architecture/brain-config.md, brain-host.md): fkbrain (the
// route.final outbound that asks the Rust brain), fkdns (the only DNS
// server), mihomo and xray (protocol outbounds from those libraries). They
// are registered through sing-box's public registries; nothing upstream is
// patched, and every socket they open comes from a sing-box dialer.
package plugin

import (
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/dns"
)

// Type names in the sing-box configuration ("type": "...").
const (
	TypeBrain  = "fkbrain"
	TypeFKDNS  = "fkdns"
	TypeMihomo = "mihomo"
	TypeXray   = "xray"
)

// RegisterOutbounds adds fkbrain, mihomo and xray to an outbound registry.
func RegisterOutbounds(registry *outbound.Registry) {
	outbound.Register[BrainOptions](registry, TypeBrain, NewBrain)
	outbound.Register[MihomoOptions](registry, TypeMihomo, NewMihomo)
	outbound.Register[XrayOptions](registry, TypeXray, NewXray)
}

// RegisterDNSTransports adds fkdns to a DNS transport registry.
func RegisterDNSTransports(registry *dns.TransportRegistry) {
	dns.RegisterTransport[FKDNSOptions](registry, TypeFKDNS, NewFKDNS)
}
