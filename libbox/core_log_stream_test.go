package libbox

import (
	"encoding/json"
	"fmt"
	"runtime"
	"sync"
	"testing"
	"time"
)

func uiLogTestStream(limit int) *uiLogStream {
	return newUILogStream(limit, time.Hour, func() int64 { return 123 }, func(*uiLogUpdate) {})
}

func uiLogTestAppend(s *uiLogStream, messages ...string) {
	for _, m := range messages {
		s.stage(5, m)
	}
	s.commit()
}

func uiLogTestIDs(u *uiLogUpdate) []int64 {
	ids := []int64{}
	for _, e := range u.Entries {
		ids = append(ids, e.ID)
	}
	return ids
}

func uiLogTestRange(from, to int) []string {
	var out []string
	for i := from; i <= to; i++ {
		out = append(out, fmt.Sprint(i))
	}
	return out
}

func TestUILogStreamSnapshotThenOnlyNewRecordsAndTheRetainedBoundary(t *testing.T) {
	s := uiLogTestStream(3)
	s.setDefaultLevel(4)
	uiLogTestAppend(s, "1", "2", "3")
	snapshot := s.update()
	if !snapshot.Reset || fmt.Sprint(uiLogTestIDs(snapshot)) != "[1 2 3]" {
		t.Fatalf("snapshot %+v", snapshot)
	}
	if e := snapshot.Entries[0]; e.Level != "Debug" || e.ReceivedAt != 123 || e.Message != "1" {
		t.Fatalf("entry %+v", e)
	}
	uiLogTestAppend(s, "4")
	delta := s.update()
	if delta.Reset || *delta.FirstID != 2 || fmt.Sprint(uiLogTestIDs(delta)) != "[4]" || *delta.DefaultLogLevel != "Info" {
		t.Fatalf("delta %+v", delta)
	}
	if s.update() != nil {
		t.Fatal("unchanged buffer sent again")
	}
}

func TestUILogStreamLevelChangesDoNotRetransmitTheBuffer(t *testing.T) {
	s := uiLogTestStream(10)
	uiLogTestAppend(s, "a", "b")
	if u := s.update(); u.DefaultLogLevel != nil {
		t.Fatalf("level before the core reported one: %v", *u.DefaultLogLevel)
	}
	for _, level := range []int32{2, 6, 9} {
		s.setDefaultLevel(level)
		u := s.update()
		if u == nil || u.Reset || len(u.Entries) != 0 || *u.DefaultLogLevel != uiLogLevelLabel(level) {
			t.Fatalf("level %d: %+v", level, u)
		}
	}
	if uiLogLevelLabel(9) != "Default" || uiLogLevelLabel(3) != "Warn" {
		t.Fatal("level labels")
	}
}

func TestUILogStreamClearReconnectAndNewBuffersResynchronize(t *testing.T) {
	s := uiLogTestStream(10)
	uiLogTestAppend(s, "10", "11")
	s.update()
	s.clear()
	cleared := s.update()
	if !cleared.Reset || cleared.FirstID != nil || len(cleared.Entries) != 0 {
		t.Fatalf("cleared %+v", cleared)
	}
	uiLogTestAppend(s, "12")
	if u := s.update(); u.Reset || fmt.Sprint(uiLogTestIDs(u)) != "[3]" {
		t.Fatalf("after clear %+v", u)
	}
	s.initialized = false // a new visible session
	uiLogTestAppend(s, "13")
	if u := s.update(); !u.Reset || len(u.Entries) != 2 {
		t.Fatalf("resumed %+v", u)
	}
	s.clear()
	s.clear() // already empty: no change to send
	if u := s.update(); !u.Reset || len(u.Entries) != 0 {
		t.Fatalf("second clear %+v", u)
	}
	if s.update() != nil {
		t.Fatal("empty clear sent")
	}
}

func TestUILogStreamFullRingAddsOneEncodedRecordPerUpdate(t *testing.T) {
	s := uiLogTestStream(3000)
	uiLogTestAppend(s, uiLogTestRange(1, 3000)...)
	s.update()
	uiLogTestAppend(s, "3001")
	u := s.update()
	if *u.FirstID != 2 || len(u.Entries) != 1 || u.Entries[0].ID != 3001 {
		t.Fatalf("full ring %+v", u.Entries)
	}
}

func TestUILogStreamRingBoundsOversizedBatchesAndIDsSurviveClear(t *testing.T) {
	s := uiLogTestStream(3)
	uiLogTestAppend(s, "old")
	uiLogTestAppend(s, uiLogTestRange(1, 10)...)
	u := s.update()
	if fmt.Sprint(u.Entries[0].Message, u.Entries[1].Message, u.Entries[2].Message) != "8 9 10" || len(u.Entries) != 3 {
		t.Fatalf("ring %+v", u.Entries)
	}
	// Only the kept lines of a batch are numbered (Kotlin takeLast).
	if u.Entries[2].ID != 4 {
		t.Fatalf("ids %v", uiLogTestIDs(u))
	}
	s.clear()
	uiLogTestAppend(s, "new")
	if u := s.update(); u.Entries[0].ID != 5 {
		t.Fatalf("after clear %+v", u)
	}
}

func TestUILogStreamStripsANSI(t *testing.T) {
	s := uiLogTestStream(5)
	uiLogTestAppend(s, "\x1b[32mdebug\x1b[0m", "\x1b[1;31mx\x1b[m", "plain \x1b[2K")
	u := s.update()
	if u.Entries[0].Message != "debug" || u.Entries[1].Message != "x" || u.Entries[2].Message != "plain \x1b[2K" {
		t.Fatalf("ansi %q", []string{u.Entries[0].Message, u.Entries[1].Message, u.Entries[2].Message})
	}
}

// The payload is the JSON the Kotlin map produced: same keys, nulls and [].
func TestUILogStreamPayloadEncoding(t *testing.T) {
	s := uiLogTestStream(5)
	data, _ := json.Marshal(s.update())
	if string(data) != `{"reset":true,"firstId":null,"defaultLogLevel":null,"entries":[]}` {
		t.Fatal(string(data))
	}
	s.setDefaultLevel(0)
	uiLogTestAppend(s, "m")
	data, _ = json.Marshal(s.update())
	if string(data) != `{"reset":false,"firstId":1,"defaultLogLevel":"Panic","entries":[{"id":1,"level":"Debug","message":"m","receivedAt":123}]}` {
		t.Fatal(string(data))
	}
}

type uiLogRecorder struct {
	sync.Mutex
	updates []*uiLogUpdate
	at      []time.Time
	signal  chan struct{}
}

func (r *uiLogRecorder) emit(u *uiLogUpdate) {
	r.Lock()
	r.updates = append(r.updates, u)
	r.at = append(r.at, time.Now())
	r.Unlock()
	select {
	case r.signal <- struct{}{}:
	default:
	}
}

func (r *uiLogRecorder) wait(t *testing.T, n int) []*uiLogUpdate {
	t.Helper()
	deadline := time.After(5 * time.Second)
	for {
		r.Lock()
		if len(r.updates) >= n {
			out := append([]*uiLogUpdate(nil), r.updates...)
			r.Unlock()
			return out
		}
		r.Unlock()
		select {
		case <-r.signal:
		case <-deadline:
			t.Fatalf("waited for %d updates", n)
		}
	}
}

func TestUILogStreamCoalescesSendsAtLeastAnIntervalApart(t *testing.T) {
	r := &uiLogRecorder{signal: make(chan struct{}, 1)}
	interval := 150 * time.Millisecond
	s := newUILogStream(100, interval, func() int64 { return 1 }, r.emit)
	uiLogTestAppend(s, "1")
	s.setVisible(true)
	defer s.setVisible(false)
	first := r.wait(t, 1)[0]
	if !first.Reset || len(first.Entries) != 1 {
		t.Fatalf("snapshot %+v", first)
	}
	for i := 2; i <= 5; i++ {
		uiLogTestAppend(s, fmt.Sprint(i))
	}
	s.setDefaultLevel(4)
	updates := r.wait(t, 2)
	time.Sleep(interval / 2)
	r.Lock()
	defer r.Unlock()
	if len(r.updates) != 2 || fmt.Sprint(uiLogTestIDs(updates[1])) != "[2 3 4 5]" || *updates[1].DefaultLogLevel != "Info" {
		t.Fatalf("coalesced %d %+v", len(r.updates), updates[1])
	}
	if gap := r.at[1].Sub(r.at[0]); gap < interval {
		t.Fatalf("sends %v apart", gap)
	}
}

func TestUILogStreamVisibilityRestartsWithSnapshotAndHideStopsSends(t *testing.T) {
	r := &uiLogRecorder{signal: make(chan struct{}, 1)}
	s := newUILogStream(100, time.Hour, func() int64 { return 1 }, r.emit)
	s.setVisible(true)
	r.wait(t, 1)
	uiLogTestAppend(s, "1") // inside the interval: held
	s.setVisible(true)      // already shown: no restart
	s.setVisible(false)
	uiLogTestAppend(s, "2")
	s.clear()
	uiLogTestAppend(s, "3")
	r.Lock()
	if len(r.updates) != 1 {
		t.Fatalf("sent while hidden: %d", len(r.updates))
	}
	r.Unlock()
	// Showing again sends at once, even within the interval of the last send.
	s.setVisible(true)
	u := r.wait(t, 2)[1]
	s.setVisible(false)
	if !u.Reset || fmt.Sprint(uiLogTestIDs(u)) != "[3]" {
		t.Fatalf("resnapshot %+v", u)
	}
}

func TestUILogStreamNoGoroutineLeakOnStopRestart(t *testing.T) {
	before := runtime.NumGoroutine()
	s := newUILogStream(10, time.Hour, func() int64 { return 1 }, func(*uiLogUpdate) {})
	var wg sync.WaitGroup
	for i := 0; i < 4; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			for j := 0; j < 200; j++ {
				s.setVisible((i+j)%2 == 0)
				uiLogTestAppend(s, "x")
				s.setDefaultLevel(int32(j % 7))
			}
		}(i)
	}
	wg.Wait()
	s.setVisible(false)
	deadline := time.Now().Add(2 * time.Second)
	for runtime.NumGoroutine() > before && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	if n := runtime.NumGoroutine(); n > before {
		t.Fatalf("goroutines %d -> %d", before, n)
	}
	if s.kick != nil || s.stop != nil || s.done != nil {
		t.Fatal("worker channels kept after hide")
	}
}
