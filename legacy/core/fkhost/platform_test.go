package fkhost

import "testing"

func TestDefaultNetworkWatchers(t *testing.T) {
	calls := 0
	cancel := WatchDefaultNetwork(func() { calls++ })
	DefaultNetworkChanged()
	DefaultNetworkChanged()
	cancel()
	DefaultNetworkChanged()
	if calls != 2 {
		t.Fatalf("watcher called %d times, want 2", calls)
	}
}
