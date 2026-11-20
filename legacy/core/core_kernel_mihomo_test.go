package libbox

import (
	"errors"
	"net/netip"
	"testing"

	mihomoProcess "github.com/metacubex/mihomo/component/process"
	mihomoConfig "github.com/metacubex/mihomo/config"
	mihomoConstant "github.com/metacubex/mihomo/constant"
)

const mihomoKernelTestProfile = `mixed-port: 7890
proxies:
  - {name: a, type: ss, server: 203.0.113.1, port: 8388, cipher: aes-128-gcm, password: x}
proxy-groups:
  - {name: PROXY, type: select, proxies: [a, DIRECT]}
rules:
  - MATCH,PROXY
`

// Prepare parses and checks the YAML once and hands the raw config on.
func TestUIKernelMihomoPrepare(t *testing.T) {
	prepared, err := (mihomoKernel{}).Prepare(mihomoKernelTestProfile)
	if err != nil {
		t.Fatal(err)
	}
	if raw, ok := prepared.(*mihomoConfig.RawConfig); !ok || raw == nil {
		t.Fatalf("Prepare returned %T, want the raw config", prepared)
	}
	if _, err := (mihomoKernel{}).Prepare("proxy-groups:\n  - {name: P, type: select, proxies: [missing]}\n"); err == nil {
		t.Fatal("unknown group member was accepted")
	}
}

// PROCESS-NAME asks the host for the connection's app.
func TestUIKernelMihomoPackageNameFromHost(t *testing.T) {
	host := &fakeHost{owner: &hostConnectionOwner{PackageNames: []string{"com.example.app", "com.example.shared"}}}
	useFakeKernelHost(t, host)
	metadata := &mihomoConstant.Metadata{
		NetWork: mihomoConstant.TCP,
		SrcIP:   netip.MustParseAddr("172.19.0.1"),
		SrcPort: 40000,
		DstIP:   netip.MustParseAddr("203.0.113.9"),
		DstPort: 443,
	}
	name, err := mihomoPackageName(metadata)
	if err != nil || name != "com.example.app" {
		t.Fatalf("package = %q, %v", name, err)
	}
	if len(host.asked) != 2 || host.asked[0] != netip.MustParseAddrPort("172.19.0.1:40000") || host.asked[1] != netip.MustParseAddrPort("203.0.113.9:443") {
		t.Fatalf("asked the host about %v", host.asked)
	}

	host.owner = nil
	if _, err := mihomoPackageName(metadata); !errors.Is(err, mihomoProcess.ErrNotFound) {
		t.Fatalf("no owner: %v, want ErrNotFound", err)
	}
	host.noOwners = true
	if _, err := mihomoPackageName(metadata); !errors.Is(err, mihomoProcess.ErrPlatformNotSupport) {
		t.Fatalf("no owner lookup: %v, want ErrPlatformNotSupport", err)
	}
}
