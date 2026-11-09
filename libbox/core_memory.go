package libbox

// Process-wide memory hygiene for the cores that share the app's process.
//
// Transparent huge pages: Go returns free heap to the kernel with
// MADV_DONTNEED in small chunks. When the kernel runs THP (or anonymous
// multi-size THP) in "always" mode, khugepaged and fault-time large folios
// fill those sparse regions back in, so RSS stays far above what the Go
// runtime holds and debug.FreeOSMemory is undone (golang/go#64332,
// https://go.dev/doc/gc-guide#Linux_transparent_huge_pages). Go 1.21.4+ no
// longer works around this, and GODEBUG=disablethp cannot be set for a
// gomobile library (//go:debug only accepts registered settings, and the
// runtime has already read GODEBUG when the library loads). So the process
// opts out with prctl(PR_SET_THP_DISABLE), and only when the kernel actually
// has THP set to "always": with "madvise" or "never" nothing is collapsed
// behind Go's back (Go no longer madvises its heap for huge pages), and the
// call is skipped. The flag is per process and covers ART/HWUI too, which
// only loses huge-page TLB savings.

import (
	"io"
	"os"
	"path/filepath"
	"runtime/debug"
	"strings"
	"sync/atomic"

	mihomoGeodata "github.com/metacubex/mihomo/component/geodata"
	mihomoMMDB "github.com/metacubex/mihomo/component/mmdb"
	mihomoProvider "github.com/metacubex/mihomo/constant/provider"
	mihomoConstant "github.com/metacubex/mihomo/constant"
	mihomoTunnel "github.com/metacubex/mihomo/tunnel"
	mihomoStatistic "github.com/metacubex/mihomo/tunnel/statistic"
	"golang.org/x/sys/unix"
)

const thpSysfs = "/sys/kernel/mm/transparent_hugepage"

// coreTHPDisabled records whether init turned THP off for this process.
var coreTHPDisabled atomic.Bool

// coreTHPRefill records that THP runs in "always" mode and could not be
// turned off, so the kernel may refill memory Go released. Only then can the
// Go mappings' resident size exceed Go's own accounting, and only then does
// ReadUIRuntimeMemory pay for the /proc/self/smaps walk.
var coreTHPRefill atomic.Bool

func init() {
	if thpAlwaysOn(thpSysfs) {
		if err := unix.Prctl(unix.PR_SET_THP_DISABLE, 1, 0, 0, 0); err == nil {
			coreTHPDisabled.Store(true)
		} else {
			coreTHPRefill.Store(true)
		}
	}
}

// thpMode returns the selected value of a sysfs THP switch such as
// "always [madvise] never".
func thpMode(path string) string {
	content, err := os.ReadFile(path)
	if err != nil {
		return ""
	}
	text := string(content)
	start := strings.IndexByte(text, '[')
	end := strings.IndexByte(text, ']')
	if start < 0 || end <= start {
		return strings.TrimSpace(text)
	}
	return text[start+1 : end]
}

// thpAlwaysOn reports whether anonymous memory can get huge pages without
// madvise: the PMD-size switch, or any multi-size (mTHP) order, is "always"
// (an order set to "inherit" follows the top-level switch).
func thpAlwaysOn(root string) bool {
	top := thpMode(filepath.Join(root, "enabled"))
	if top == "always" {
		return true
	}
	orders, _ := filepath.Glob(filepath.Join(root, "hugepages-*kB", "enabled"))
	for _, order := range orders {
		if mode := thpMode(order); mode == "always" || (mode == "inherit" && top == "always") {
			return true
		}
	}
	return false
}

// releaseCoreMemory returns idle heap to the OS after a core stopped or
// started (sing-box's own daemon does the same around its instance).
func releaseCoreMemory() { debug.FreeOSMemory() }

// mihomoReleaseState drops what mihomo keeps in process-wide globals after
// its listeners are closed: the rules and rule providers, the proxies and
// proxy providers (their update/health-check loops are stopped), tracked
// connections, the GeoIP/GeoSite matcher caches and the MMDB/ASN readers.
// The next ApplyConfig rebuilds all of them from the profile; the DNS
// resolver and fake-IP mapper are left alone, since mihomo hands the old
// fake-IP mappings to the next start.
func mihomoReleaseState() {
	mihomoStatistic.DefaultManager.Range(func(tracker mihomoStatistic.Tracker) bool {
		_ = tracker.Close()
		return true
	})
	for _, provider := range mihomoTunnel.Providers() {
		closeMihomoProvider(provider)
	}
	for _, provider := range mihomoTunnel.RuleProviders() {
		closeMihomoProvider(provider)
	}
	mihomoTunnel.UpdateRules(nil, nil, nil)
	mihomoTunnel.UpdateProxies(map[string]mihomoConstant.Proxy{}, map[string]mihomoProvider.ProxyProvider{})
	mihomoGeodata.ClearGeoIPCache()
	mihomoGeodata.ClearGeoSiteCache()
	mihomoMMDB.ReloadIP()
	mihomoMMDB.ReloadASN()
}

func closeMihomoProvider(provider mihomoProvider.Provider) {
	if closer, ok := provider.(io.Closer); ok {
		_ = closer.Close()
	}
}
