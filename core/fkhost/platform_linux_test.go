//go:build linux && !android

package fkhost

import (
	"net"
	"net/netip"
	"testing"

	"golang.org/x/sys/unix"
)

func TestLinuxRulesAndProtectBeforeTun(t *testing.T) {
	rules := linuxRules(unix.AF_INET)
	if len(rules) != 2 {
		t.Fatalf("%d rules", len(rules))
	}
	suppress, toTun := rules[0], rules[1]
	if suppress.Table != unix.RT_TABLE_MAIN || suppress.SuppressPrefixlen != 0 || suppress.Priority != linuxRulePriority-1 {
		t.Fatalf("suppress rule: %+v", suppress)
	}
	if toTun.Table != linuxRouteTable || !toTun.Invert || !toTun.MarkSet || toTun.Mark != linuxMark {
		t.Fatalf("tun rule: %+v", toTun)
	}
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Skip(err)
	}
	defer listener.Close()
	raw, err := listener.(*net.TCPListener).SyscallConn()
	if err != nil {
		t.Fatal(err)
	}
	// No TUN yet: nothing is marked (and no CAP_NET_ADMIN is needed).
	if err := NewPlatform().Protect(raw); err != nil {
		t.Fatal(err)
	}
	if got := linuxIPNet(netip.MustParsePrefix("172.19.0.1/30")).String(); got != "172.19.0.1/30" {
		t.Fatal(got)
	}
}
