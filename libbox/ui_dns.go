package libbox

// FK-box: the dashboard's DNS query count. sing-box counts in its DNS router
// (tools/patch_dns_count.py, libbox/dns/ui_query_count.go): every message it
// answers, including hijack-dns queries that never become connections.
// Xray (port-53 datagrams through the TUN tracker) and mihomo (its dns-hijack
// service) add to uiOtherDNSQueries from core_xray_track.go / core_service.go.
import (
	"sync/atomic"

	"github.com/sagernet/sing-box/dns"
)

var (
	uiOtherDNSQueries atomic.Int64
	uiDNSQueryBase    atomic.Int64
)

func uiDNSQueryTotal() int64 { return dns.UIQueryCount() + uiOtherDNSQueries.Load() }

// ReadUIDNSQueryCount is the number of DNS queries the running core answered
// since it last started or reloaded. Local service only.
func ReadUIDNSQueryCount() int64 {
	if count := uiDNSQueryTotal() - uiDNSQueryBase.Load(); count > 0 {
		return count
	}
	return 0
}

func resetUIDNSQueryCount() { uiDNSQueryBase.Store(uiDNSQueryTotal()) }
