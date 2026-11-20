//go:build !android

package libbox

import (
	"testing"

	"github.com/sagernet/sing-box/log"
)

func TestUIStandaloneHost(t *testing.T) {
	host := standaloneKernelHost()
	if host.Platform == nil || host.Lifecycle == nil || host.Log == nil || host.HTTP == nil || host.Commands == nil {
		t.Fatal("standalone host misses a service")
	}
	// Nothing runs: stopping is a no-op.
	if err := StandaloneCoreStop(); err != nil {
		t.Fatal(err)
	}
	if err := StandaloneCoreStart("nope", "{}"); err == nil {
		t.Fatal("unknown core must fail")
	}
	if fkhostLevel(log.LevelTrace) != hostLogDebug || fkhostLevel(log.LevelFatal) != hostLogError {
		t.Fatal("levels")
	}
}
