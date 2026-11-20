package libbox

import (
	"encoding/json"
	"fmt"
	"reflect"
	"runtime"
	"sync"
	"testing"
	"time"
)

type uiConnectionTestSink struct {
	access sync.Mutex
	events []string // kind + " " + payload
}

func (k *uiConnectionTestSink) emit(kind string, payload json.RawMessage) {
	k.access.Lock()
	k.events = append(k.events, kind+" "+string(payload))
	k.access.Unlock()
}

func (k *uiConnectionTestSink) take() []string {
	k.access.Lock()
	defer k.access.Unlock()
	events := k.events
	k.events = nil
	return events
}

type uiConnectionTestClock struct{ now int64 }

func uiConnectionTestStream(interval time.Duration) (*uiConnectionStream, *uiConnectionTestSink, *uiConnectionTestClock) {
	sink := &uiConnectionTestSink{}
	clock := &uiConnectionTestClock{now: 10_000}
	stream := newUIConnectionStream(interval, func() int64 { return clock.now }, func() int64 { return 42 }, sink.emit)
	return stream, sink, clock
}

func uiConnectionTestConn(id string) *Connection {
	return &Connection{
		ID: id, Inbound: "tun-in", Network: "tcp", Source: "10.0.0.2:1000", Destination: "1.1.1.1:443",
		Protocol: "tls", CreatedAt: 1, Uplink: 5, Downlink: 7, UplinkTotal: 100, DownlinkTotal: 200,
		Rule: "final", Outbound: "proxy", OutboundType: "direct", chainList: []string{"proxy"},
	}
}

func uiConnectionTestEvents(reset bool, events ...*ConnectionEvent) *ConnectionEvents {
	return &ConnectionEvents{Reset: reset, events: events}
}

func uiConnectionNew(c *Connection) *ConnectionEvent {
	return &ConnectionEvent{Type: ConnectionEventNew, ID: c.ID, Connection: c}
}

func uiConnectionUpdate(id string, up, down int64) *ConnectionEvent {
	return &ConnectionEvent{Type: ConnectionEventUpdate, ID: id, UplinkDelta: up, DownlinkDelta: down}
}

func uiConnectionClosed(id string, c *Connection, closedAt int64) *ConnectionEvent {
	return &ConnectionEvent{Type: ConnectionEventClosed, ID: id, Connection: c, ClosedAt: closedAt}
}

func uiConnectionTestRows(t *testing.T, s *uiConnectionStream) []map[string]any {
	t.Helper()
	var rows []map[string]any
	if err := json.Unmarshal(s.rowsPayload(), &rows); err != nil {
		t.Fatal(err)
	}
	return rows
}

func uiConnectionTestIDs(t *testing.T, s *uiConnectionStream) []string {
	ids := []string{}
	for _, row := range uiConnectionTestRows(t, s) {
		ids = append(ids, row["id"].(string))
	}
	return ids
}

func TestUIConnectionsUpdateAppliesDeltasAndKeepsUnchangedRows(t *testing.T) {
	s, _, _ := uiConnectionTestStream(time.Hour)
	s.apply(uiConnectionTestEvents(false, uiConnectionNew(uiConnectionTestConn("a")), uiConnectionNew(uiConnectionTestConn("b"))), false)
	s.rowsPayload()
	before := s.entries["b"]
	encodedB := s.entries["b"].encoded
	s.apply(uiConnectionTestEvents(false, uiConnectionUpdate("a", 10, 20)), false)
	rows := uiConnectionTestRows(t, s)
	a := rows[0]
	if a["upload"] != 10.0 || a["download"] != 20.0 || a["uploadTotal"] != 110.0 || a["downloadTotal"] != 220.0 {
		t.Fatalf("a = %v", a)
	}
	if s.entries["b"] != before || &s.entries["b"].encoded[0] != &encodedB[0] {
		t.Fatal("unchanged row was rebuilt or re-encoded")
	}
}

func TestUIConnectionsClosedMarksRowAndZeroesRates(t *testing.T) {
	s, _, _ := uiConnectionTestStream(time.Hour)
	s.apply(uiConnectionTestEvents(false, uiConnectionNew(uiConnectionTestConn("a")), uiConnectionClosed("a", nil, 9_000)), false)
	a := uiConnectionTestRows(t, s)[0]
	if a["closedAt"] != 9_000.0 || a["upload"] != 0.0 || a["download"] != 0.0 || a["uploadTotal"] != 100.0 {
		t.Fatalf("a = %v", a)
	}
	// A closed snapshot for an unknown id is stored as is (appended).
	s.apply(uiConnectionTestEvents(false, uiConnectionClosed("c", uiConnectionTestConn("c"), 9_500)), false)
	rows := uiConnectionTestRows(t, s)
	if len(rows) != 2 || rows[1]["id"] != "c" || rows[1]["closedAt"] != 9_500.0 || rows[1]["upload"] != 0.0 {
		t.Fatalf("rows = %v", rows)
	}
	// closedAt 0 leaves the row open-looking (Kotlin takeIf { it > 0 }).
	s.apply(uiConnectionTestEvents(false, uiConnectionNew(uiConnectionTestConn("z")), uiConnectionClosed("z", nil, 0)), false)
	if z := uiConnectionTestRows(t, s)[2]; z["closedAt"] != nil {
		t.Fatalf("z = %v", z)
	}
}

func TestUIConnectionsUnknownIDsAndInvalidEventsAreIgnored(t *testing.T) {
	s, _, _ := uiConnectionTestStream(time.Hour)
	s.apply(uiConnectionTestEvents(false,
		uiConnectionUpdate("x", 1, 1), uiConnectionClosed("x", nil, 1),
		&ConnectionEvent{Type: ConnectionEventNew, ID: "x"}, &ConnectionEvent{Type: 9, ID: "x"}, nil), false)
	if s.version != 0 || len(uiConnectionTestIDs(t, s)) != 0 {
		t.Fatal("ignored events changed the store")
	}
}

func TestUIConnectionsOrderIsInsertionOrderAndNewKeepsPosition(t *testing.T) {
	s, _, _ := uiConnectionTestStream(time.Hour)
	s.apply(uiConnectionTestEvents(false, uiConnectionNew(uiConnectionTestConn("a")), uiConnectionNew(uiConnectionTestConn("b")), uiConnectionNew(uiConnectionTestConn("c"))), false)
	replaced := uiConnectionTestConn("a")
	replaced.Rule = "other"
	s.apply(uiConnectionTestEvents(false, uiConnectionNew(replaced)), false)
	if ids := uiConnectionTestIDs(t, s); !reflect.DeepEqual(ids, []string{"a", "b", "c"}) {
		t.Fatalf("ids = %v", ids)
	}
	if uiConnectionTestRows(t, s)[0]["rule"] != "other" {
		t.Fatal("NEW did not replace the row")
	}
}

func TestUIConnectionsDNSHiddenAndCounted(t *testing.T) {
	s, _, _ := uiConnectionTestStream(time.Hour)
	dns := uiConnectionTestConn("d")
	dns.OutboundType = "dns"
	port := uiConnectionTestConn("p")
	port.Destination, port.Protocol = "8.8.8.8:53", "DNS"
	s.apply(uiConnectionTestEvents(false, uiConnectionNew(uiConnectionTestConn("a")), uiConnectionNew(dns), uiConnectionNew(port)), true)
	if ids := uiConnectionTestIDs(t, s); !reflect.DeepEqual(ids, []string{"a", "p"}) {
		t.Fatalf("ids = %v", ids)
	}
	if got := string(s.statsPayload()); got != `{"active":2,"recent":0,"dns":2}` {
		t.Fatalf("remote stats = %s", got)
	}
	s.apply(uiConnectionTestEvents(false), false)
	if got := string(s.statsPayload()); got != `{"active":2,"recent":0,"dns":42}` {
		t.Fatalf("local stats = %s", got)
	}
}

func TestUIConnectionsClosedRowsEvictedAfterFiveMinutes(t *testing.T) {
	s, _, clock := uiConnectionTestStream(time.Hour)
	dns := uiConnectionTestConn("dns")
	dns.OutboundType = "dns"
	s.apply(uiConnectionTestEvents(false, uiConnectionNew(dns), uiConnectionNew(uiConnectionTestConn("a")), uiConnectionClosed("a", nil, 1_000)), false)
	clock.now = 1_000 + uiConnectionClosedMaxAge
	s.apply(uiConnectionTestEvents(false), false)
	if ids := uiConnectionTestIDs(t, s); !reflect.DeepEqual(ids, []string{"a"}) {
		t.Fatalf("ids = %v", ids)
	}
	version := s.version
	clock.now++
	s.apply(uiConnectionTestEvents(false), false)
	if len(uiConnectionTestIDs(t, s)) != 0 || s.version == version {
		t.Fatal("closed row not evicted")
	}
	// Re-adding an evicted id appends it once.
	s.apply(uiConnectionTestEvents(false, uiConnectionNew(uiConnectionTestConn("b")), uiConnectionNew(uiConnectionTestConn("a"))), false)
	if ids := uiConnectionTestIDs(t, s); !reflect.DeepEqual(ids, []string{"b", "a"}) {
		t.Fatalf("ids = %v", ids)
	}
}

func TestUIConnectionsResetAndClearOrdering(t *testing.T) {
	s, _, _ := uiConnectionTestStream(time.Hour)
	s.apply(uiConnectionTestEvents(false, uiConnectionNew(uiConnectionTestConn("a"))), false)
	s.apply(uiConnectionTestEvents(true, uiConnectionNew(uiConnectionTestConn("b")), uiConnectionUpdate("a", 1, 1)), false)
	if ids := uiConnectionTestIDs(t, s); !reflect.DeepEqual(ids, []string{"b"}) {
		t.Fatalf("after reset ids = %v", ids)
	}
	s.clear(false)
	version := s.version
	s.clear(false) // clearing an empty store is not a change
	if s.version != version {
		t.Fatal("empty clear bumped the version")
	}
	s.apply(uiConnectionTestEvents(false, uiConnectionUpdate("b", 1, 1), uiConnectionNew(uiConnectionTestConn("c"))), false)
	if ids := uiConnectionTestIDs(t, s); !reflect.DeepEqual(ids, []string{"c"}) {
		t.Fatalf("after clear ids = %v", ids)
	}
	// A zero update on an idle row is not a change (the Kotlin state was equal).
	s.apply(uiConnectionTestEvents(false, uiConnectionClosed("c", nil, 9_999)), false)
	version = s.version
	s.apply(uiConnectionTestEvents(false, uiConnectionUpdate("c", 0, 0)), false)
	if s.version != version {
		t.Fatal("idle zero update bumped the version")
	}
}

func TestUIConnectionsPayloadEncoding(t *testing.T) {
	s, _, _ := uiConnectionTestStream(time.Hour)
	withProcess := uiConnectionTestConn("a")
	withProcess.Destination, withProcess.Domain = "1.1.1.1:443", "one.one"
	withProcess.ProcessInfo = &ProcessInfo{ProcessID: 7, UserName: "u0_a1", ProcessPath: "/system/bin/app", packageNames: []string{"com.example"}}
	bare := uiConnectionTestConn("b")
	bare.chainList = nil
	bare.ProcessInfo = &ProcessInfo{}
	bare.ClosedAt = 9_000
	none := uiConnectionTestConn("c")
	none.Domain = "<x&y>"
	s.apply(uiConnectionTestEvents(false, uiConnectionNew(withProcess), uiConnectionNew(bare), uiConnectionNew(none)), false)
	want := `[` +
		`{"id":"a","inbound":"tun-in","network":"tcp","source":"10.0.0.2:1000","destination":"1.1.1.1:443","domain":"one.one","displayDestination":"one.one:443","protocol":"tls","user":"","createdAt":1,"closedAt":null,"upload":5,"download":7,"uploadTotal":100,"downloadTotal":200,"rule":"final","outbound":"proxy","chain":["proxy"],"process":{"id":7,"path":"/system/bin/app","user":"u0_a1","packages":["com.example"]}},` +
		`{"id":"b","inbound":"tun-in","network":"tcp","source":"10.0.0.2:1000","destination":"1.1.1.1:443","domain":"","displayDestination":"1.1.1.1:443","protocol":"tls","user":"","createdAt":1,"closedAt":9000,"upload":5,"download":7,"uploadTotal":100,"downloadTotal":200,"rule":"final","outbound":"proxy","chain":[],"process":{"id":0,"path":"","user":"","packages":[]}},` +
		`{"id":"c","inbound":"tun-in","network":"tcp","source":"10.0.0.2:1000","destination":"1.1.1.1:443","domain":"<x&y>","displayDestination":"<x&y>:443","protocol":"tls","user":"","createdAt":1,"closedAt":null,"upload":5,"download":7,"uploadTotal":100,"downloadTotal":200,"rule":"final","outbound":"proxy","chain":["proxy"],"process":null}` +
		`]`
	// Compare as JSON values (Go escapes <, > and & as \u003c..., the same strings).
	var got, expected any
	if err := json.Unmarshal(s.rowsPayload(), &got); err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal([]byte(want), &expected); err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(got, expected) {
		t.Fatalf("payload\n got %s\nwant %s", s.rowsPayload(), want)
	}
	if string(s.rowsPayload()) != string(s.rowsPayload()) {
		t.Fatal("cached payload differs")
	}
	if got := string(s.statsPayload()); got != `{"active":2,"recent":1,"dns":42}` {
		t.Fatalf("stats = %s", got)
	}
	empty, _, _ := uiConnectionTestStream(time.Hour)
	if got := string(empty.rowsPayload()); got != `[]` {
		t.Fatalf("empty rows = %s", got)
	}
}

func uiConnectionTestWait(t *testing.T, sink *uiConnectionTestSink, n int) []string {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	var events []string
	for time.Now().Before(deadline) {
		events = append(events, sink.take()...)
		if len(events) >= n {
			return events
		}
		time.Sleep(2 * time.Millisecond)
	}
	t.Fatalf("got %d events, want %d: %v", len(events), n, events)
	return nil
}

func TestUIConnectionsShowSendsAtOnceAndForegroundStreamsChanges(t *testing.T) {
	s, sink, _ := uiConnectionTestStream(30 * time.Millisecond)
	s.apply(uiConnectionTestEvents(false, uiConnectionNew(uiConnectionTestConn("a"))), false)
	s.show(true, true)
	if events := sink.take(); len(events) != 1 || events[0][:12] != "connections " {
		t.Fatalf("show events = %v", events)
	}
	s.resend(true)
	if events := sink.take(); len(events) != 1 {
		t.Fatalf("resend events = %v", events)
	}
	s.show(false, true)
	if events := sink.take(); len(events) != 1 || events[0] != `connectionStats {"active":1,"recent":0,"dns":42}` {
		t.Fatalf("stats show events = %v", events)
	}
	// Entering the foreground sends the current state (rows then stats).
	s.setForeground(true)
	events := uiConnectionTestWait(t, sink, 2)
	if events[0][:12] != "connections " || events[1][:16] != "connectionStats " {
		t.Fatalf("foreground events = %v", events)
	}
	// Changes inside one interval are coalesced into one send.
	for i := 0; i < 5; i++ {
		s.apply(uiConnectionTestEvents(false, uiConnectionUpdate("a", int64(i+1), 0)), false)
	}
	events = uiConnectionTestWait(t, sink, 2)
	time.Sleep(80 * time.Millisecond)
	events = append(events, sink.take()...)
	if len(events) != 2 {
		t.Fatalf("coalesced events = %v", events)
	}
	var rows []map[string]any
	_ = json.Unmarshal([]byte(events[0][len("connections "):]), &rows)
	if rows[0]["upload"] != 5.0 || rows[0]["uploadTotal"] != 115.0 {
		t.Fatalf("coalesced rows = %v", rows)
	}
	// Hidden kinds are not sent; leaving the foreground stops all sends.
	s.show(false, false)
	s.apply(uiConnectionTestEvents(false, uiConnectionUpdate("a", 9, 0)), false)
	events = uiConnectionTestWait(t, sink, 1)
	if len(events) != 1 || events[0][:12] != "connections " {
		t.Fatalf("rows-only events = %v", events)
	}
	s.setForeground(false)
	s.apply(uiConnectionTestEvents(false, uiConnectionUpdate("a", 10, 0)), false)
	time.Sleep(80 * time.Millisecond)
	if events := sink.take(); len(events) != 0 {
		t.Fatalf("sent after leaving the foreground: %v", events)
	}
}

func TestUIConnectionsForegroundIsCountedPerOwner(t *testing.T) {
	s, sink, _ := uiConnectionTestStream(time.Millisecond)
	s.show(true, true)
	s.setForeground(true)
	s.setForeground(true)
	s.setForeground(false) // one owner leaves; the other still streams
	sink.take()
	s.apply(uiConnectionTestEvents(false, uiConnectionNew(uiConnectionTestConn("a"))), false)
	uiConnectionTestWait(t, sink, 1)
	s.setForeground(false)
	s.setForeground(false) // unbalanced extra leave is ignored
	if s.active || s.foreground != 0 {
		t.Fatal("still active")
	}
}

func TestUIConnectionsNoGoroutineLeakOnStopRestart(t *testing.T) {
	s, _, _ := uiConnectionTestStream(time.Millisecond)
	s.show(true, true)
	before := runtime.NumGoroutine()
	for i := 0; i < 50; i++ {
		s.setForeground(true)
		s.apply(uiConnectionTestEvents(false, uiConnectionNew(uiConnectionTestConn(fmt.Sprint(i)))), false)
		s.setForeground(false)
	}
	time.Sleep(20 * time.Millisecond)
	if after := runtime.NumGoroutine(); after > before {
		t.Fatalf("goroutines %d -> %d", before, after)
	}
}

func TestUIConnectionsConcurrentApplyAndSend(t *testing.T) {
	s, _, _ := uiConnectionTestStream(time.Microsecond)
	s.show(true, true)
	s.show(false, true)
	s.setForeground(true)
	var wait sync.WaitGroup
	for w := 0; w < 4; w++ {
		wait.Add(1)
		go func(w int) {
			defer wait.Done()
			for i := 0; i < 200; i++ {
				id := fmt.Sprint(w, "-", i%20)
				s.apply(uiConnectionTestEvents(i%50 == 0, uiConnectionNew(uiConnectionTestConn(id)), uiConnectionUpdate(id, 1, 1), uiConnectionClosed(id, nil, int64(i))), false)
				if i%30 == 0 {
					s.clear(false)
				}
				s.resend(i%2 == 0)
			}
		}(w)
	}
	wait.Wait()
	s.setForeground(false)
}

func BenchmarkUIConnectionsThousandsOfRows(b *testing.B) {
	s, _, _ := uiConnectionTestStream(time.Hour)
	var news []*ConnectionEvent
	for i := 0; i < 5000; i++ {
		news = append(news, uiConnectionNew(uiConnectionTestConn(fmt.Sprint(i))))
	}
	s.apply(uiConnectionTestEvents(false, news...), false)
	s.rowsPayload()
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		var updates []*ConnectionEvent
		for j := 0; j < 200; j++ {
			updates = append(updates, uiConnectionUpdate(fmt.Sprint((i*200+j)%5000), 1, 1))
		}
		s.apply(uiConnectionTestEvents(false, updates...), false)
		s.rowsPayload()
		s.statsPayload()
	}
}
