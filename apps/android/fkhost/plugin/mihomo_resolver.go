package plugin

import (
	"context"
	"net/netip"
	"sync"
	"sync/atomic"

	"github.com/sagernet/sing-box/adapter"
	E "github.com/sagernet/sing/common/exceptions"

	"github.com/metacubex/mihomo/component/resolver"
	"github.com/miekg/dns"
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
// it never reaches the system resolver.

type singBoxResolver struct{}

var _ resolver.Resolver = singBoxResolver{}

var mihomoDNS struct {
	sync.Mutex
	current adapter.DNSRouter
	refs    int
}

// MihomoLookups counts names mihomo resolved through sing-box.
var MihomoLookups atomic.Int64

func init() {
	resolver.DefaultResolver = singBoxResolver{}
	resolver.ProxyServerHostResolver = singBoxResolver{}
	resolver.DirectHostResolver = singBoxResolver{}
}

func installMihomoResolver(router adapter.DNSRouter) {
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

func releaseMihomoResolver(router adapter.DNSRouter) {
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

func currentDNSRouter() (adapter.DNSRouter, error) {
	mihomoDNS.Lock()
	defer mihomoDNS.Unlock()
	if mihomoDNS.current == nil {
		return nil, E.New("mihomo: no running configuration to resolve with")
	}
	return mihomoDNS.current, nil
}

func (singBoxResolver) lookup(ctx context.Context, host string, keep func(netip.Addr) bool) ([]netip.Addr, error) {
	router, err := currentDNSRouter()
	if err != nil {
		return nil, err
	}
	MihomoLookups.Add(1)
	addresses, err := router.Lookup(ctx, host, adapter.DNSQueryOptions{})
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
		return nil, E.New("mihomo: no address for ", host)
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

// ResolveECH: ECH configurations come from HTTPS records, which the brain's
// resolver will provide (stage 4); until then an ech-opts lookup fails
// instead of asking the system.
func (singBoxResolver) ResolveECH(ctx context.Context, host string) ([]byte, error) {
	return nil, E.New("mihomo: ECH lookup for ", host, " is not available before the brain's resolver (stage 4)")
}

func (singBoxResolver) ExchangeContext(ctx context.Context, message *dns.Msg) (*dns.Msg, error) {
	router, err := currentDNSRouter()
	if err != nil {
		return nil, err
	}
	return router.Exchange(ctx, message, adapter.DNSQueryOptions{})
}

// Invalid reports a usable resolver (mihomo's name for it).
func (singBoxResolver) Invalid() bool { return true }

func (singBoxResolver) ClearCache() {}

func (singBoxResolver) ResetConnection() {}
