// Package plugin holds the four sing-box plugin types of the single-kernel
// design (docs/architecture/brain-config.md, brain-host.md): fkbrain (the
// route.final outbound that asks the Rust brain), fkdns (the only DNS
// server), mihomo and xray (protocol outbounds from those libraries). They
// are registered through sing-box's public registries; nothing upstream is
// patched, and every socket they open comes from a sing-box dialer.
package plugin

import (
	up "github.com/koko123as/fk-box/apps/android/fkhost/upstream"
)

// Type names in the sing-box configuration ("type": "...").
const (
	TypeBrain  = "fkbrain"
	TypeFKDNS  = "fkdns"
	TypeMihomo = "mihomo"
	TypeXray   = "xray"
)

// RegisterOutbounds adds fkbrain, mihomo and xray to an outbound registry.
func RegisterOutbounds(registry *up.Registry) {
	up.Register[BrainOptions](registry, TypeBrain, NewBrain)
	up.Register[MihomoOptions](registry, TypeMihomo, NewMihomo)
	up.Register[XrayOptions](registry, TypeXray, NewXray)
}

// RegisterDNSTransports adds fkdns to a DNS transport registry.
func RegisterDNSTransports(registry *up.TransportRegistry) {
	up.RegisterTransport[FKDNSOptions](registry, TypeFKDNS, NewFKDNS)
}
