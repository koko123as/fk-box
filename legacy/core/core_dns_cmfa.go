//go:build android && cmfa

package libbox

import (
	"net/netip"
	"strings"
	"sync"

	mihomoDNS "github.com/metacubex/mihomo/dns"
)

// UpdateCoreSystemDNS gives mihomo the underlying network's DNS servers,
// comma separated. Built with the cmfa tag (as ClashMetaForAndroid is),
// mihomo's "system" resolver reads them from here instead of the OS.
func UpdateCoreSystemDNS(servers string) {
	var list []string
	for _, server := range strings.Split(servers, ",") {
		if addr, err := netip.ParseAddr(strings.TrimSpace(server)); err == nil {
			list = append(list, netip.AddrPortFrom(addr, 53).String())
		}
	}
	// The network callback fires on every link property or capability change
	// (signal strength, bandwidth estimates). Only a new set of servers
	// replaces the resolver and drops the cache.
	key := strings.Join(list, ",")
	systemDNSAccess.Lock()
	changed := !systemDNSSet || key != systemDNSLast
	systemDNSLast, systemDNSSet = key, true
	systemDNSAccess.Unlock()
	if !changed {
		return
	}
	mihomoDNS.UpdateSystemDNS(list)
	mihomoDNS.FlushCacheWithDefaultResolver()
}

var (
	systemDNSAccess sync.Mutex
	systemDNSLast   string
	systemDNSSet    bool
)

// flushCoreDNS drops mihomo's cached answers and DNS connections, which
// belong to the previous underlying network.
func flushCoreDNS() {
	mihomoDNS.FlushCacheWithDefaultResolver()
}
