package libbox

import (
	"context"
	"testing"
	"time"

	mihomoResolver "github.com/metacubex/mihomo/component/resolver"
	mihomoConfig "github.com/metacubex/mihomo/config"
	mDNS "github.com/miekg/dns"
)

// A first mihomo start waits inside ApplyConfig for every remote provider it
// has not cached yet. The readers of the running core (dashboard streams,
// probes, the network callback) must not wait for it: they only take
// coreAccess, which a start no longer holds.
func TestCoreInternalsStartDoesNotBlockReaders(t *testing.T) {
	coreLifecycle.Lock() // a start or stop in progress
	defer coreLifecycle.Unlock()
	done := make(chan struct{})
	go func() {
		defer close(done)
		if otherCore() != nil {
			t.Error("no core was started")
		}
		CoreDefaultNetworkChanged()
	}()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("readers of the running core waited for a core start")
	}
}

type fakeMihomoDNS struct{ served int }

func (f *fakeMihomoDNS) ServeMsg(ctx context.Context, msg *mDNS.Msg) (*mDNS.Msg, error) {
	f.served++
	return msg, nil
}

// The counter wraps the resolver each ApplyConfig installs exactly once, and
// leaves a disabled DNS (nil) alone.
func TestCoreInternalsMihomoDNSCounter(t *testing.T) {
	previous := mihomoResolver.DefaultService
	t.Cleanup(func() { mihomoResolver.DefaultService = previous })

	mihomoResolver.DefaultService = nil
	countMihomoDNS()
	if mihomoResolver.DefaultService != nil {
		t.Fatal("a disabled DNS got a counter")
	}

	first := &fakeMihomoDNS{}
	mihomoResolver.DefaultService = first
	countMihomoDNS()
	countMihomoDNS()
	wrapped, ok := mihomoResolver.DefaultService.(countingMihomoDNS)
	if !ok || wrapped.Service != first {
		t.Fatalf("resolver not wrapped once: %#v", mihomoResolver.DefaultService)
	}
	before := uiOtherDNSQueries.Load()
	if _, err := mihomoResolver.ServeMsg(context.Background(), new(mDNS.Msg)); err != nil {
		t.Fatal(err)
	}
	if uiOtherDNSQueries.Load() != before+1 || first.served != 1 {
		t.Fatalf("query not counted once: %d -> %d, served %d", before, uiOtherDNSQueries.Load(), first.served)
	}

	// The next start's ApplyConfig installs a new resolver.
	second := &fakeMihomoDNS{}
	mihomoResolver.DefaultService = second
	countMihomoDNS()
	if wrapped, ok := mihomoResolver.DefaultService.(countingMihomoDNS); !ok || wrapped.Service != second {
		t.Fatalf("new resolver not wrapped: %#v", mihomoResolver.DefaultService)
	}
}

func TestCoreInternalsMihomoRemoteProviders(t *testing.T) {
	raw := &mihomoConfig.RawConfig{
		ProxyProvider: map[string]map[string]any{
			"sub":   {"type": "http", "url": "https://example.com/sub"},
			"local": {"type": "file", "path": "./local.yaml"},
		},
		RuleProvider: map[string]map[string]any{
			"reject": {"type": "http", "behavior": "domain", "url": "https://example.com/reject.yaml"},
			"inline": {"type": "inline", "behavior": "domain"},
		},
	}
	if got := mihomoRemoteProviders(raw); got != 2 {
		t.Fatalf("remote providers = %d, want 2", got)
	}
}
