package plugin

import (
	"context"
	"net/netip"
	"sync"
	"sync/atomic"

	"github.com/miekg/dns"

	up "github.com/koko123as/fk-box/apps/android/fkhost/upstream"
)

// mihomo resolves proxy server names itself in places that do not go
// through the injected dialer (resolveUDPAddr for UDP relays of ss/ssr/
// socks5/hysteria, mieru's DNSResolver, dialer.NewDialer for QUIC based
// protocols, ECH lookups). Those use the process-wide
// resolver.ProxyServerHostResolver / DefaultResolver / DirectHostResolver,
// and with them nil mihomo falls back to resolver.SystemResolver: the
// system DNS. The plugin points all three at sing-box's DNS router (the
// fkdns server of the running configuration), once per process, public
// exported variables only. A lookup with no running configuration fails;
// it never reaches the system resolver. Since stage 4 fkdns is the brain's
// resolver, so these lookups follow its DNS policy (node server names go
// through the direct upstreams).

type singBoxResolver struct{}

var _ up.MihomoResolver = singBoxResolver{}

var mihomoDNS struct {
	sync.Mutex
	current up.DNSRouter
	refs    int
}

// MihomoLookups counts names mihomo resolved through sing-box.
var MihomoLookups atomic.Int64

func init() {
	up.SetMihomoDefaultResolver(singBoxResolver{})
	up.SetMihomoProxyServerHostResolver(singBoxResolver{})
	up.SetMihomoDirectHostResolver(singBoxResolver{})
}

func installMihomoResolver(router up.DNSRouter) {
	if router == nil {
		return
	}
	mihomoDNS.Lock()
	defer mihomoDNS.Unlock()
	if mihomoDNS.current != router {
		mihomoDNS.current = router
		mihomoDNS.refs = 0
	}
	mihomoDNS.refs++
}

func releaseMihomoResolver(router up.DNSRouter) {
	mihomoDNS.Lock()
	defer mihomoDNS.Unlock()
	if mihomoDNS.current != router {
		return
	}
	mihomoDNS.refs--
	if mihomoDNS.refs <= 0 {
		mihomoDNS.current = nil
		mihomoDNS.refs = 0
	}
}

func currentDNSRouter() (up.DNSRouter, error) {
	mihomoDNS.Lock()
	defer mihomoDNS.Unlock()
	if mihomoDNS.current == nil {
		return nil, up.NewError("mihomo: no running configuration to resolve with")
	}
	return mihomoDNS.current, nil
}

func (singBoxResolver) lookup(ctx context.Context, host string, keep func(netip.Addr) bool) ([]netip.Addr, error) {
	router, err := currentDNSRouter()
	if err != nil {
		return nil, err
	}
	MihomoLookups.Add(1)
	addresses, err := router.Lookup(ctx, host, up.DNSQueryOptions{})
	if err != nil {
		return nil, err
	}
	var kept []netip.Addr
	for _, address := range addresses {
		address = address.Unmap()
		if keep == nil || keep(address) {
			kept = append(kept, address)
		}
	}
	if len(kept) == 0 {
		return nil, up.NewError("mihomo: no address for ", host)
	}
	return kept, nil
}

func (r singBoxResolver) LookupIP(ctx context.Context, host string) ([]netip.Addr, error) {
	return r.lookup(ctx, host, nil)
}

func (r singBoxResolver) LookupIPv4(ctx context.Context, host string) ([]netip.Addr, error) {
	return r.lookup(ctx, host, netip.Addr.Is4)
}

func (r singBoxResolver) LookupIPv6(ctx context.Context, host string) ([]netip.Addr, error) {
	return r.lookup(ctx, host, netip.Addr.Is6)
}

// ResolveECH reads the ECH configuration from the name's HTTPS record,
// asked through sing-box's DNS router (fkdns: the brain forwards the query
// along the route the rules give the name, never the system resolver).
func (r singBoxResolver) ResolveECH(ctx context.Context, host string) ([]byte, error) {
	query := new(dns.Msg)
	query.SetQuestion(dns.Fqdn(host), dns.TypeHTTPS)
	response, err := r.ExchangeContext(ctx, query)
	if err != nil {
		return nil, err
	}
	for _, answer := range response.Answer {
		record, isHTTPS := answer.(*dns.HTTPS)
		if !isHTTPS {
			continue
		}
		for _, value := range record.Value {
			if ech, isECH := value.(*dns.SVCBECHConfig); isECH && len(ech.ECH) > 0 {
				return ech.ECH, nil
			}
		}
	}
	return nil, up.NewError("mihomo: no ECH configuration for ", host)
}

func (singBoxResolver) ExchangeContext(ctx context.Context, message *dns.Msg) (*dns.Msg, error) {
	router, err := currentDNSRouter()
	if err != nil {
		return nil, err
	}
	return router.Exchange(ctx, message, up.DNSQueryOptions{})
}

// Invalid reports a usable resolver (mihomo's name for it).
func (singBoxResolver) Invalid() bool { return true }

func (singBoxResolver) ClearCache() {}

func (singBoxResolver) ResetConnection() {}
