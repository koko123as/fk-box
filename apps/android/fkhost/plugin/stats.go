package plugin

import (
	"sync"
	"sync/atomic"
	"time"
)

// Process-wide counters of the plugins: what fkb_host_api.stats reports
// (bytes and live connections through fkbrain) and what the tests assert
// (decisions, protected dials of each plugin, Xray dialer selection).
type pluginCounters struct {
	uplink         atomic.Int64
	downlink       atomic.Int64
	connectionsTCP atomic.Int64
	connectionsUDP atomic.Int64
	decided        atomic.Int64
	decideNanos    atomic.Int64
	decideFailed   atomic.Int64
	rejected       atomic.Int64
	dnsHijacked    atomic.Int64

	access   sync.Mutex
	byTarget map[string]int64
}

var counters = &pluginCounters{byTarget: make(map[string]int64)}

func (c *pluginCounters) observeDecide(elapsed time.Duration) {
	c.decided.Add(1)
	c.decideNanos.Add(int64(elapsed))
}

func (c *pluginCounters) routed(tag string) {
	c.access.Lock()
	c.byTarget[tag]++
	c.access.Unlock()
}

// Snapshot is a copy of the counters.
type Snapshot struct {
	Uplink         int64
	Downlink       int64
	ConnectionsTCP int64
	ConnectionsUDP int64
	Decided        int64
	DecideNanos    int64
	DecideFailed   int64
	Rejected       int64
	DNSHijacked    int64
	// Connections fkbrain handed to each outbound tag.
	Routed map[string]int64
}

// Stats returns the current counters.
func Stats() Snapshot {
	counters.access.Lock()
	routed := make(map[string]int64, len(counters.byTarget))
	for tag, count := range counters.byTarget {
		routed[tag] = count
	}
	counters.access.Unlock()
	return Snapshot{
		Uplink:         counters.uplink.Load(),
		Downlink:       counters.downlink.Load(),
		ConnectionsTCP: counters.connectionsTCP.Load(),
		ConnectionsUDP: counters.connectionsUDP.Load(),
		Decided:        counters.decided.Load(),
		DecideNanos:    counters.decideNanos.Load(),
		DecideFailed:   counters.decideFailed.Load(),
		Rejected:       counters.rejected.Load(),
		DNSHijacked:    counters.dnsHijacked.Load(),
		Routed:         routed,
	}
}
