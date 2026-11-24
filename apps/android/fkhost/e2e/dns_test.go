package e2e

import (
	"context"
	"fmt"
	"net"
	"net/netip"
	"os"
	"slices"
	"testing"
	"time"

	"github.com/koko123as/fk-box/apps/android/fkhost/brainffi"
	"github.com/koko123as/fk-box/apps/android/fkhost/host"
	"github.com/koko123as/fk-box/apps/android/fkhost/internal/fixture"
	"github.com/koko123as/fk-box/apps/android/fkhost/plugin"
)

// The CI job routes this range into the TUN device too.
var fakeRange = netip.MustParsePrefix("198.18.128.0/20")

// lookupThroughTun asks the brain's DNS the way an app does: a UDP query to
// port 53 of an address routed into the TUN (fkbrain hijacks it to fkdns).
func lookupThroughTun(t *testing.T, name string) (netip.Addr, time.Duration) {
	t.Helper()
	resolver := &net.Resolver{PreferGo: true, Dial: func(ctx context.Context, network, address string) (net.Conn, error) {
		var dialer net.Dialer
		return dialer.DialContext(ctx, "udp", "198.18.99.53:53")
	}}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	start := time.Now()
	addresses, err := resolver.LookupNetIP(ctx, "ip4", name)
	elapsed := time.Since(start)
	if err != nil || len(addresses) == 0 {
		t.Fatalf("lookup %s through the TUN: %v %v", name, addresses, err)
	}
	return addresses[0].Unmap(), elapsed
}

func percentile(values []time.Duration, p int) time.Duration {
	sorted := slices.Clone(values)
	slices.Sort(sorted)
	return sorted[min(len(sorted)-1, len(sorted)*p/100)]
}

// The brain's DNS end to end on the TUN: apps' queries get fake-ip answers
// from the Rust brain, connections to them are decided and dialed by name
// (native Shadowsocks, mihomo TCP and UDP to a domain, Xray after a
// reload), node server names resolve through the brain (DNS fixture via
// the direct outbound's protected dialer), names an IP rule needs are
// prefetched after a DNS_MISS, and nothing uses the system resolver: every
// name here exists only in the DNS fixture.
func TestDNSEndToEnd(t *testing.T) {
	tunName := os.Getenv("FKHOST_E2E_TUN")
	if tunName == "" {
		t.Skip("FKHOST_E2E_TUN is not set (the CI job creates the TUN device)")
	}
	echo := fixture.StartEcho(t)
	dnsServer := fixture.StartDNS(t)
	mieruPort := fixture.StartMieru(t)
	ssPort := fixture.StartShadowsocks(t, "127.0.0.1", echo.Port)
	xhttpPort := fixture.FreePort(t, "tcp")
	fixture.StartXrayServer(t, xhttpPort, map[string]any{
		"network": "xhttp", "xhttpSettings": map[string]any{"path": "/fk-e2e-dns", "mode": "packet-up"},
	}, echo.Port)

	bridge := &tunBridge{name: tunName}
	defer bridge.Close()
	h, err := host.New(host.Options{Bridge: bridge})
	if err != nil {
		t.Fatal(err)
	}
	if err = brainffi.Attach(h); err != nil {
		t.Fatal("attach: ", err)
	}
	upstream := fmt.Sprintf("udp://127.0.0.1:%d", dnsServer.Port)
	dnsOptions := fmt.Sprintf(`{"tun_address": ["172.19.0.1/30"], "tun_auto_route": false, "tun_stack": "gvisor", "tun_mtu": 1500, "log_level": "debug",
  "dns": {"fake_ip_range": %q, "fake_ip_range6": null, "direct_upstreams": [%q], "proxy_upstreams": [%q],
          "policy": [{"domains": ["+.fixture.test"], "upstreams": [%q], "outbound": "fk-direct"}]}}`,
		fakeRange.String(), upstream, upstream, upstream)
	subscription := fmt.Sprintf(`proxies:
  - {name: ss-native, type: ss, server: 127.0.0.1, port: %d, cipher: aes-128-gcm, password: %q}
  - {name: mieru-plugin, type: mieru, server: mieru-server.fixture.test, port: %d, transport: TCP, udp: true, username: fkbox, password: %q}
rules:
  - DOMAIN-SUFFIX,ss.fixture.test,ss-native
  - DOMAIN-SUFFIX,mieru.fixture.test,mieru-plugin
  - IP-CIDR,127.0.0.0/8,ss-native
  - MATCH,mieru-plugin
`, ssPort, fixture.Password, mieruPort, fixture.Password)
	if err = brainffi.StartProfile(subscription, dnsOptions); err != nil {
		t.Fatal("start profile: ", err)
	}
	defer func() {
		if err := brainffi.Stop(); err != nil {
			t.Log("stop: ", err)
		}
	}()
	before, protectedBefore, lookupsBefore := plugin.Stats(), h.Platform().Protected(), plugin.MihomoLookups.Load()

	// 1. App queries through the TUN: fake-ip answers from the brain.
	var lookups []time.Duration
	ssAddress, elapsed := lookupThroughTun(t, "a.ss.fixture.test.")
	lookups = append(lookups, elapsed)
	if !fakeRange.Contains(ssAddress) {
		t.Fatalf("a.ss.fixture.test -> %s, not a fake-ip address of %s", ssAddress, fakeRange)
	}
	again, _ := lookupThroughTun(t, "a.ss.fixture.test.")
	if again != ssAddress {
		t.Fatalf("fake-ip not stable: %s then %s", ssAddress, again)
	}
	for i := 0; i < 20; i++ {
		_, elapsed := lookupThroughTun(t, fmt.Sprintf("q%d.ss.fixture.test.", i))
		lookups = append(lookups, elapsed)
	}

	// 2. Connections to fake addresses are decided and dialed by name.
	tcpEcho(t, netip.AddrPortFrom(ssAddress, 7777).String())
	udpEcho(t, netip.AddrPortFrom(ssAddress, 7777).String())
	mieruAddress, _ := lookupThroughTun(t, "b.mieru.fixture.test.")
	fqdnPackets := plugin.MihomoFQDNPackets()
	tcpEcho(t, netip.AddrPortFrom(mieruAddress, 8888).String())
	udpEcho(t, netip.AddrPortFrom(mieruAddress, 8888).String())
	middle := plugin.Stats()
	if middle.Routed["ss-native"] < before.Routed["ss-native"]+2 || middle.Routed["mieru-plugin"] < before.Routed["mieru-plugin"]+2 {
		t.Fatalf("routing by name: before %v after %v", before.Routed, middle.Routed)
	}
	if middle.FakeIP < before.FakeIP+4 || middle.FakeIPStale != before.FakeIPStale || middle.DNSHijacked <= before.DNSHijacked {
		t.Fatalf("fake-ip counters: before %+v after %+v", before, middle)
	}
	if plugin.MihomoFQDNPackets() <= fqdnPackets {
		t.Fatal("mihomo UDP to a domain did not resolve through the brain")
	}
	if plugin.MihomoLookups.Load() <= lookupsBefore || dnsServer.Queries() == 0 {
		t.Fatalf("node server name: mihomo lookups %d, fixture queries %d", plugin.MihomoLookups.Load()-lookupsBefore, dnsServer.Queries())
	}

	// 3. First packet: lookup + connect + first echo, fresh names each time.
	var firstPackets []time.Duration
	for i := 0; i < 10; i++ {
		start := time.Now()
		address, _ := lookupThroughTun(t, fmt.Sprintf("f%d.ss.fixture.test.", i))
		tcpEcho(t, netip.AddrPortFrom(address, 7777).String())
		firstPackets = append(firstPackets, time.Since(start))
	}

	// 4. DNS_MISS: these names match no domain rule; IP-CIDR needs their
	// address, the cache misses (MATCH carries the connection), the brain
	// prefetches, and the next connection matches IP-CIDR (ss-native).
	const names = 10
	var addresses []netip.Addr
	for i := 0; i < names; i++ {
		address, _ := lookupThroughTun(t, fmt.Sprintf("n%d.plain.fixture.test.", i))
		addresses = append(addresses, address)
	}
	cold := plugin.Stats()
	for _, address := range addresses {
		tcpEcho(t, netip.AddrPortFrom(address, 7777).String())
	}
	coldDone := plugin.Stats()
	time.Sleep(1500 * time.Millisecond)
	for _, address := range addresses {
		tcpEcho(t, netip.AddrPortFrom(address, 7777).String())
	}
	warmDone := plugin.Stats()
	coldMiss, warmMiss := coldDone.DNSMiss-cold.DNSMiss, warmDone.DNSMiss-coldDone.DNSMiss
	if coldMiss != names || warmMiss != 0 {
		t.Fatalf("DNS_MISS: cold %d, warm %d of %d", coldMiss, warmMiss, names)
	}
	if warmDone.Routed["ss-native"] < coldDone.Routed["ss-native"]+names {
		t.Fatalf("prefetched names did not match IP-CIDR: %v -> %v", coldDone.Routed, warmDone.Routed)
	}
	if h.Platform().Protected() <= protectedBefore {
		t.Fatal("no protected socket")
	}
	t.Logf("E2E DNS fake-ip through TUN: lookups %d, p50 %v, p90 %v, max %v; fake-ip connections %d, stale %d, dns hijacked %d",
		len(lookups), percentile(lookups, 50), percentile(lookups, 90), slices.Max(lookups),
		middle.FakeIP-before.FakeIP, middle.FakeIPStale-before.FakeIPStale, middle.DNSHijacked-before.DNSHijacked)
	t.Logf("E2E DNS first packet (lookup + connect + first echo via ss-native): p50 %v, p90 %v, max %v",
		percentile(firstPackets, 50), percentile(firstPackets, 90), slices.Max(firstPackets))
	t.Logf("E2E DNS_MISS ratio: cold %d/%d (%.0f%%), after prefetch %d/%d (%.0f%%); all decisions so far %d/%d",
		coldMiss, names, 100*float64(coldMiss)/names, warmMiss, names, 100*float64(warmMiss)/names,
		warmDone.DNSMiss-before.DNSMiss, warmDone.Decided-before.Decided)
	t.Logf("E2E DNS resolver: mihomo node lookups %d, mihomo UDP to a domain %d, fixture queries %d, protected %d",
		plugin.MihomoLookups.Load()-lookupsBefore, plugin.MihomoFQDNPackets()-fqdnPackets, dnsServer.Queries(), h.Platform().Protected()-protectedBefore)

	// 5. Reload to an Xray node addressed by name (resolved by the brain);
	// a domain connection through it, TCP and UDP.
	queries := dnsServer.Queries()
	link := fmt.Sprintf("vless://%s@xh-server.fixture.test:%d?encryption=none&security=none&type=xhttp&path=%%2Ffk-e2e-dns&mode=packet-up#xh", fixture.UUID, xhttpPort)
	if err = brainffi.ReloadProfile(link, dnsOptions); err != nil {
		t.Fatal("reload profile: ", err)
	}
	xrayAddress, _ := lookupThroughTun(t, "c.xh.fixture.test.")
	if !fakeRange.Contains(xrayAddress) {
		t.Fatalf("c.xh.fixture.test -> %s", xrayAddress)
	}
	beforeXray := plugin.Stats()
	tcpEcho(t, netip.AddrPortFrom(xrayAddress, 443).String())
	udpEcho(t, netip.AddrPortFrom(xrayAddress, 443).String())
	afterXray := plugin.Stats()
	if afterXray.Routed["xh"] < beforeXray.Routed["xh"]+2 || dnsServer.Queries() <= queries {
		t.Fatalf("xray by name: routed %v, fixture queries %d -> %d", afterXray.Routed, queries, dnsServer.Queries())
	}
	t.Logf("E2E DNS reload to xray: fake-ip survives (%s), xh tcp+udp by name ok, server name resolved through the brain (fixture queries +%d)",
		xrayAddress, dnsServer.Queries()-queries)
}
