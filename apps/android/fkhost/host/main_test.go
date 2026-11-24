package host

import (
	"sync"
	"testing"

	"github.com/koko123as/fk-box/apps/android/fkhost/internal/fixture"
)

func TestMain(m *testing.M) { fixture.Main(m) }

// testBridge counts Protect calls (there is no VpnService on Linux; the
// count proves a socket came from a sing-box dialer with
// auto_detect_interface) and opens the TUN device the CI job prepared.
type testBridge struct {
	access    sync.Mutex
	protected int
	tunName   string
}

func (b *testBridge) Protect(fd int) error {
	b.access.Lock()
	b.protected++
	b.access.Unlock()
	return nil
}

func (b *testBridge) Count() int {
	b.access.Lock()
	defer b.access.Unlock()
	return b.protected
}

func (b *testBridge) OpenTun(request *TunRequest) (int, error) {
	return fixture.OpenTunDevice(b.tunName)
}
