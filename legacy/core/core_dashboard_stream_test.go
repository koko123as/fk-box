package libbox

import (
	"encoding/json"
	"os"
	"path/filepath"
	"runtime"
	"testing"
	"time"
)

// uiDashboardTestClock hands each timer the worker waits on to the test, which
// fires it; nothing waits on real time.
type uiDashboardTestClock struct {
	timers    chan chan time.Time
	durations chan time.Duration
}

func newUIDashboardTestClock() *uiDashboardTestClock {
	return &uiDashboardTestClock{timers: make(chan chan time.Time, 16), durations: make(chan time.Duration, 16)}
}

func (c *uiDashboardTestClock) after(d time.Duration) <-chan time.Time {
	timer := make(chan time.Time, 1)
	c.durations <- d
	c.timers <- timer
	return timer
}

func (c *uiDashboardTestClock) fire(t *testing.T) {
	t.Helper()
	select {
	case timer := <-c.timers:
		timer <- time.Time{}
	case <-time.After(5 * time.Second):
		t.Fatal("worker is not waiting for its interval")
	}
}

type uiDashboardTestStream struct {
	*uiDashboardMemory
	clock    *uiDashboardTestClock
	payloads chan *uiMemoryPayload
}

func newUIDashboardTestStream() *uiDashboardTestStream {
	clock := newUIDashboardTestClock()
	payloads := make(chan *uiMemoryPayload, 64)
	stream := newUIDashboardMemory(uiDashboardMemoryInterval, clock.after,
		func(source uiMemorySource) *uiMemoryPayload {
			return uiMemorySample(source, 100, &UIRuntimeMemory{Total: 40, HeapInuse: 20, HeapIdle: 5, StackInuse: 5, RuntimeOther: 10, Goroutines: 7})
		},
		func(p *uiMemoryPayload) { payloads <- p })
	return &uiDashboardTestStream{stream, clock, payloads}
}

func (s *uiDashboardTestStream) expectSample(t *testing.T) *uiMemoryPayload {
	t.Helper()
	select {
	case p := <-s.payloads:
		return p
	case <-time.After(5 * time.Second):
		t.Fatal("no memory sample")
		return nil
	}
}

func (s *uiDashboardTestStream) expectNone(t *testing.T) {
	t.Helper()
	select {
	case <-s.payloads:
		t.Fatal("unexpected memory sample")
	case <-time.After(30 * time.Millisecond):
	}
}

// Port of VisiblePollingTest.samplesOnlyWithVisibleForegroundConsumersAndAnAwakeScreen.
func TestUIDashboardMemorySamplesOnlyWhenSubscribedForegroundAndScreenOn(t *testing.T) {
	s := newUIDashboardTestStream()
	s.setSignals(1, false, true, true)
	s.expectNone(t)
	s.setSignals(1, true, true, true)
	s.expectSample(t)
	s.clock.fire(t)
	s.expectSample(t)

	signals := func(off int) (bool, bool, bool) {
		values := [3]bool{true, true, true}
		values[off] = false
		return values[0], values[1], values[2]
	}
	for off := range 3 {
		a, b, c := signals(off)
		s.setSignals(1, a, b, c) // returns only after the worker exited
		select {
		case timer := <-s.clock.timers:
			timer <- time.Time{} // a stale timer fires into nothing
		default:
		}
		s.expectNone(t)
		// The pause restarts the cycle: resuming samples at once.
		s.setSignals(1, true, true, true)
		s.expectSample(t)
	}
	s.setSignals(1, false, false, false)
}

func TestUIDashboardMemoryImmediateSampleOnResumeAndTwoSecondInterval(t *testing.T) {
	s := newUIDashboardTestStream()
	s.setSignals(1, true, true, true)
	s.expectSample(t)
	if d := <-s.clock.durations; d != 2*time.Second {
		t.Fatalf("interval %v", d)
	}
	s.expectNone(t) // nothing before the interval elapses
	s.clock.fire(t)
	s.expectSample(t)
	s.setSignals(1, true, false, true)
	s.setSignals(1, true, true, true)
	s.expectSample(t) // without waiting for any timer
	s.setSignals(1, false, false, false)
}

// Port of VisiblePollingTest.hidingCancelsPendingWorkAndResumeStartsAFreshSample:
// a sample in flight when the card hides is never delivered.
func TestUIDashboardMemoryHideDropsSampleInFlight(t *testing.T) {
	started := make(chan struct{}, 4)
	release := make(chan struct{})
	payloads := make(chan *uiMemoryPayload, 4)
	clock := newUIDashboardTestClock()
	first := true
	m := newUIDashboardMemory(time.Second, clock.after, func(uiMemorySource) *uiMemoryPayload {
		started <- struct{}{}
		if first {
			first = false
			<-release
		}
		return &uiMemoryPayload{Remote: true}
	}, func(p *uiMemoryPayload) { payloads <- p })
	m.setSignals(1, true, true, true)
	<-started
	hidden := make(chan struct{})
	go func() { m.setSignals(1, false, true, true); close(hidden) }()
	// The hide closes stop before the sample returns; it must not be sent.
	time.Sleep(20 * time.Millisecond)
	close(release)
	<-hidden
	select {
	case <-payloads:
		t.Fatal("sample delivered after hide")
	default:
	}
	m.setSignals(1, true, true, true)
	<-started
	select {
	case <-payloads:
	case <-time.After(5 * time.Second):
		t.Fatal("no fresh sample on resume")
	}
	m.setSignals(1, false, false, false)
}

func TestUIDashboardMemoryOwnersAreIndependent(t *testing.T) {
	s := newUIDashboardTestStream()
	s.setSignals(1, true, true, true)
	s.expectSample(t)
	// A recreated bridge subscribes; the old one's late release keeps it running.
	s.setSignals(2, true, true, true)
	s.setSignals(1, false, false, false)
	s.expectNone(t)
	s.clock.fire(t)
	s.expectSample(t)
	s.setSignals(2, false, false, false)
	s.access.Lock()
	running, owners := s.running, len(s.owners)
	s.access.Unlock()
	if running || owners != 0 {
		t.Fatalf("running=%v owners=%d", running, owners)
	}
}

func TestUIDashboardMemoryPayloadEqualsKotlinShape(t *testing.T) {
	local := uiMemorySample(uiMemorySource{running: true}, 100,
		&UIRuntimeMemory{Total: 40, HeapInuse: 20, HeapIdle: 5, StackInuse: 5, RuntimeOther: 10, Goroutines: 7, Resident: 50})
	encoded, _ := json.Marshal(local)
	var got map[string]any
	_ = json.Unmarshal(encoded, &got)
	want := map[string]any{"total": 100.0, "rss": 100.0, "app": 50.0, "core": 50.0, "remote": false, "running": true,
		"heapInuse": 20.0, "heapIdle": 5.0, "stack": 5.0, "runtimeOther": 20.0, "goroutines": 7.0}
	if len(got) != len(want) {
		t.Fatalf("keys %v", got)
	}
	for k, v := range want {
		if got[k] != v {
			t.Fatalf("%s = %v, want %v (%s)", k, got[k], v, encoded)
		}
	}
	// Go accounting above RSS: app never negative, total is the larger.
	big := uiMemorySample(uiMemorySource{}, 10, &UIRuntimeMemory{Total: 40})
	if big.Total != 40 || *big.App != 0 || big.Core != 40 || *big.Running {
		t.Fatalf("%+v", big)
	}
	remote, _ := json.Marshal(uiMemorySample(uiMemorySource{remote: true, remoteMemory: 77, running: true}, 0, nil))
	if string(remote) != `{"total":77,"core":77,"remote":true}` {
		t.Fatalf("remote %s", remote)
	}
}

func TestUIDashboardMemorySourceAppliesToNextSample(t *testing.T) {
	s := newUIDashboardTestStream()
	s.setSource(uiMemorySource{remote: true, remoteMemory: 5})
	s.setSignals(1, true, true, true)
	if p := s.expectSample(t); !p.Remote || p.Total != 5 {
		t.Fatalf("%+v", p)
	}
	s.setSource(uiMemorySource{running: true})
	s.clock.fire(t)
	if p := s.expectSample(t); p.Remote || !*p.Running || p.Total != 100 {
		t.Fatalf("%+v", p)
	}
	s.setSignals(1, false, false, false)
}

func TestUIDashboardMemoryRelease(t *testing.T) {
	s := newUIDashboardTestStream()
	s.setSource(uiMemorySource{remote: true})
	collected := 0
	if _, err := s.release(func() { collected++ }); err == nil || collected != 0 {
		t.Fatal("remote release must fail without collecting")
	}
	s.setSource(uiMemorySource{})
	p, err := s.release(func() { collected++ })
	if err != nil || collected != 1 || p.Total != 100 {
		t.Fatalf("%v %d %+v", err, collected, p)
	}
	if s.expectSample(t) != p {
		t.Fatal("release must emit the answered payload")
	}
}

func TestUIDashboardMemoryReadsProcessRSS(t *testing.T) {
	path := filepath.Join(t.TempDir(), "status")
	_ = os.WriteFile(path, []byte("Name:\tx\nVmPeak:\t 9 kB\nVmRSS:\t   1234 kB\nVmData: 1 kB\n"), 0o600)
	if got := readProcessRSS(path); got != 1234*1024 {
		t.Fatalf("rss %d", got)
	}
	if readProcessRSS(filepath.Join(t.TempDir(), "missing")) != 0 {
		t.Fatal("missing status must read 0")
	}
}

func TestUIDashboardMemoryNoGoroutineLeak(t *testing.T) {
	m := newUIDashboardMemory(time.Millisecond, time.After,
		func(uiMemorySource) *uiMemoryPayload { return &uiMemoryPayload{} }, func(*uiMemoryPayload) {})
	before := runtime.NumGoroutine()
	for i := range 200 {
		owner := int32(i % 3)
		m.setSignals(owner, true, true, true)
		if i%2 == 0 {
			m.setSignals(owner, true, false, true)
		}
		m.setSignals(owner, false, false, false)
	}
	m.access.Lock()
	running := m.running
	m.access.Unlock()
	if running {
		t.Fatal("worker still marked running")
	}
	deadline := time.Now().Add(2 * time.Second)
	for runtime.NumGoroutine() > before && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	if after := runtime.NumGoroutine(); after > before {
		t.Fatalf("goroutines %d -> %d", before, after)
	}
}
