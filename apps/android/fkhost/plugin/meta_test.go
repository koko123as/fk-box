package plugin

import (
	"net/netip"
	"testing"
	"unsafe"

	"github.com/sagernet/sing-box/adapter"
	M "github.com/sagernet/sing/common/metadata"
)

// The offsets of crates/brain-ffi/include/fkbox_brain.h (its _Static_asserts).
func TestMetaLayout(t *testing.T) {
	var m Meta
	var d Decision
	checks := []struct {
		name string
		got  uintptr
		want uintptr
	}{
		{"sizeof(fkb_conn_meta)", unsafe.Sizeof(m), 576},
		{"network", unsafe.Offsetof(m.Network), 8},
		{"src_port", unsafe.Offsetof(m.SrcPort), 12},
		{"dst_port", unsafe.Offsetof(m.DstPort), 14},
		{"src_ip", unsafe.Offsetof(m.SrcIP), 16},
		{"dst_ip", unsafe.Offsetof(m.DstIP), 32},
		{"uid", unsafe.Offsetof(m.UID), 48},
		{"inbound_index", unsafe.Offsetof(m.InboundIndex), 52},
		{"domain_len", unsafe.Offsetof(m.DomainLen), 56},
		{"domain_source", unsafe.Offsetof(m.DomainSource), 58},
		{"package_len", unsafe.Offsetof(m.PackageLen), 60},
		{"domain", unsafe.Offsetof(m.Domain), 64},
		{"package_name", unsafe.Offsetof(m.PackageName), 320},
		{"process_name", unsafe.Offsetof(m.ProcessName), 448},
		{"sizeof(fkb_decision)", unsafe.Sizeof(d), 16},
		{"outbound", unsafe.Offsetof(d.Outbound), 4},
		{"rule_index", unsafe.Offsetof(d.RuleIndex), 8},
		{"generation", unsafe.Offsetof(d.Generation), 12},
	}
	for _, check := range checks {
		if check.got != check.want {
			t.Errorf("%s: %d, want %d", check.name, check.got, check.want)
		}
	}
}

func TestMetaFill(t *testing.T) {
	var m Meta
	metadata := adapter.InboundContext{
		Network:     "udp",
		Inbound:     "fk-tun",
		Source:      M.ParseSocksaddr("172.19.0.1:40000"),
		Destination: M.SocksaddrFromNetIP(netip.MustParseAddrPort("[::ffff:198.18.99.1]:443")),
		Protocol:    "quic",
		Domain:      "example.com",
		ProcessInfo: &adapter.ConnectionOwner{UserId: 10123, PackageNames: []string{"com.example.app"}},
	}
	m.fill(&metadata, 0)
	if m.ABIVersion != ABIVersion || m.Size != 576 || m.Network != NetUDP || m.Protocol != ProtoQUIC {
		t.Fatalf("header: %+v", m)
	}
	if m.DstIPVersion != 4 || m.DstIP[0] != 198 || m.DstIP[3] != 1 || m.DstPort != 443 {
		t.Fatalf("destination (IPv4-mapped is IPv4): v%d %v", m.DstIPVersion, m.DstIP[:4])
	}
	if m.SrcIPVersion != 4 || m.SrcPort != 40000 {
		t.Fatalf("source: v%d %d", m.SrcIPVersion, m.SrcPort)
	}
	if string(m.Domain[:m.DomainLen]) != "example.com" || m.DomainSource != DomainSniffed {
		t.Fatalf("domain: %q %d", m.Domain[:m.DomainLen], m.DomainSource)
	}
	if m.UID != 10123 || string(m.PackageName[:m.PackageLen]) != "com.example.app" || m.InboundIndex != 0 {
		t.Fatalf("owner: %d %q %d", m.UID, m.PackageName[:m.PackageLen], m.InboundIndex)
	}
	fqdn := adapter.InboundContext{Network: "tcp", Destination: M.ParseSocksaddr("www.example.org:80")}
	m.fill(&fqdn, InboundUnknown)
	if m.DstIPVersion != 0 || m.DomainSource != DomainFQDN || string(m.Domain[:m.DomainLen]) != "www.example.org" || m.UID != UIDUnknown {
		t.Fatalf("fqdn: %+v", m)
	}
}

func TestGenerationTables(t *testing.T) {
	for generation := uint32(1); generation <= 4; generation++ {
		registerGeneration(generation, []string{"a", "b"})
	}
	if _, ok := outboundTag(1, 0); ok {
		t.Fatal("generation 1 should be gone after three newer ones")
	}
	if tag, ok := outboundTag(2, 1); !ok || tag != "b" {
		t.Fatalf("generation 2 index 1: %q %v", tag, ok)
	}
	if _, ok := outboundTag(4, 2); ok {
		t.Fatal("index out of range accepted")
	}
}

func TestXrayOutboundChecks(t *testing.T) {
	good := map[string]any{
		"protocol": "vless",
		"settings": map[string]any{"address": "a.example", "port": float64(443), "id": "x", "encryption": "none"},
		"streamSettings": map[string]any{"network": "xhttp", "xhttpSettings": map[string]any{
			"path": "/", "extra": map[string]any{"downloadSettings": map[string]any{"address": "b.example", "port": float64(8443)}},
		}},
	}
	if err := checkXrayOutbound(good); err != nil {
		t.Fatal(err)
	}
	if servers := xrayServers(good); len(servers) != 2 || servers[0] != "a.example:443" || servers[1] != "b.example:8443" {
		t.Fatalf("servers: %v", servers)
	}
	refused := []map[string]any{
		{"protocol": "wireguard", "settings": map[string]any{}},
		{"protocol": "vless", "settings": map[string]any{}, "streamSettings": map[string]any{"network": "hysteria"}},
		{"protocol": "vless", "settings": map[string]any{}, "streamSettings": map[string]any{"sockopt": map[string]any{"dialerProxy": "x"}}},
		{"protocol": "vless", "settings": map[string]any{}, "streamSettings": map[string]any{"sockopt": map[string]any{"domainStrategy": "UseIP"}}},
		{"protocol": "vless", "settings": map[string]any{"domainStrategy": "UseIPv4"}},
	}
	for i, object := range refused {
		if err := checkXrayOutbound(object); err == nil {
			t.Errorf("case %d accepted", i)
		}
	}
}
