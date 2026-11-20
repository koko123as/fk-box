package libbox

// The Connections page's stream: the connection store and the "connections"
// and "connectionStats" events (protocol/methods.json).
//
// The host keeps the core subscription (CommandClient, local or remote) and
// hands each ConnectionEvents batch straight back here, so rows are read from
// the Go objects and never field by field over JNI. The store is a port of
// libbox Connections.ApplyEvents (and of the former Kotlin ConnectionStore):
// NEW stores the snapshot, UPDATE adds the row's increments, CLOSED marks the
// row (or stores its snapshot) with zero rates; closed rows are evicted five
// minutes after they closed; DNS rows are counted, not listed. Rows keep their
// insertion order, as the Kotlin LinkedHashMap did.
//
// The host also keeps the visibility signals: whether the rows (Connections
// page) and the counts (dashboard card) are shown, and whether the app is in
// the foreground. While in the foreground one worker sends the current state
// at once, then each changed state at most once per second (changes in between
// are coalesced). Showing a kind sends it at once. An unchanged row is encoded
// once and its bytes reused; the UI always receives the full row list.

import (
	"bytes"
	"encoding/json"
	"strings"
	"sync"
	"time"
)

const (
	uiConnectionStreamInterval = time.Second
	uiConnectionClosedMaxAge   = int64((5 * time.Minute) / time.Millisecond)
)

type uiConnectionProcess struct {
	ID       int64    `json:"id"`
	Path     string   `json:"path"`
	User     string   `json:"user"`
	Packages []string `json:"packages"`
}

// uiConnectionRow is one entry of the "connections" payload (the Kotlin
// Connection.toWire).
type uiConnectionRow struct {
	ID                 string               `json:"id"`
	Inbound            string               `json:"inbound"`
	Network            string               `json:"network"`
	Source             string               `json:"source"`
	Destination        string               `json:"destination"`
	Domain             string               `json:"domain"`
	DisplayDestination string               `json:"displayDestination"`
	Protocol           string               `json:"protocol"`
	User               string               `json:"user"`
	CreatedAt          int64                `json:"createdAt"`
	ClosedAt           *int64               `json:"closedAt"`
	Upload             int64                `json:"upload"`
	Download           int64                `json:"download"`
	UploadTotal        int64                `json:"uploadTotal"`
	DownloadTotal      int64                `json:"downloadTotal"`
	Rule               string               `json:"rule"`
	Outbound           string               `json:"outbound"`
	Chain              []string             `json:"chain"`
	Process            *uiConnectionProcess `json:"process"`
}

// uiConnectionStats is the "connectionStats" payload.
type uiConnectionStats struct {
	Active int   `json:"active"`
	Recent int   `json:"recent"`
	DNS    int64 `json:"dns"`
}

type uiConnectionEntry struct {
	row          uiConnectionRow
	outboundType string
	encoded      []byte // nil until encoded; dropped when the row changes
}

func (e *uiConnectionEntry) isDNS() bool {
	return e.outboundType == "dns" || strings.EqualFold(e.row.Protocol, "dns") || strings.HasSuffix(e.row.Destination, ":53")
}

func uiNonNilStrings(values []string) []string {
	if values == nil {
		return []string{}
	}
	return append([]string(nil), values...)
}

// uiConnectionEntryFrom is the Kotlin Connection.from.
func uiConnectionEntryFrom(c *Connection) *uiConnectionEntry {
	row := uiConnectionRow{
		ID: c.ID, Inbound: c.Inbound, Network: c.Network, Source: c.Source, Destination: c.Destination,
		Domain: c.Domain, DisplayDestination: c.DisplayDestination(), Protocol: c.Protocol, User: c.User,
		CreatedAt: c.CreatedAt, Upload: c.Uplink, Download: c.Downlink, UploadTotal: c.UplinkTotal,
		DownloadTotal: c.DownlinkTotal, Rule: c.Rule, Outbound: c.Outbound, Chain: uiNonNilStrings(c.chainList),
	}
	if c.ClosedAt > 0 {
		closedAt := c.ClosedAt
		row.ClosedAt = &closedAt
	}
	if p := c.ProcessInfo; p != nil {
		row.Process = &uiConnectionProcess{ID: p.ProcessID, Path: p.ProcessPath, User: p.UserName, Packages: uiNonNilStrings(p.packageNames)}
	}
	return &uiConnectionEntry{row: row, outboundType: c.OutboundType}
}

type uiConnectionStream struct {
	interval time.Duration
	now      func() int64
	dnsCount func() int64 // the local core's DNS query count
	emit     func(kind string, payload json.RawMessage)

	control sync.Mutex // serializes the visibility setters

	access  sync.Mutex
	entries map[string]*uiConnectionEntry
	order   []string // insertion order; may hold ids no longer in entries
	stale   int      // ids in order that are no longer in entries
	version uint64   // bumped on every change of the visible state
	remote  bool

	// Owners (UI bridges) showing the rows, the stats, and in the foreground.
	rows, stats int
	foreground  int
	active      bool
	kick        chan struct{}
	stop        chan struct{}
	done        chan struct{}
}

func newUIConnectionStream(interval time.Duration, now func() int64, dnsCount func() int64, emit func(string, json.RawMessage)) *uiConnectionStream {
	return &uiConnectionStream{interval: interval, now: now, dnsCount: dnsCount, emit: emit, entries: make(map[string]*uiConnectionEntry)}
}

var uiConnections = newUIConnectionStream(uiConnectionStreamInterval,
	func() int64 { return time.Now().UnixMilli() },
	ReadUIDNSQueryCount,
	func(kind string, payload json.RawMessage) { _ = uiEmit(kind, payload) })

// changed records a change of the state and wakes the worker. Callers hold access.
func (s *uiConnectionStream) changed() {
	s.version++
	if s.kick == nil {
		return
	}
	select {
	case s.kick <- struct{}{}:
	default:
	}
}

func (s *uiConnectionStream) put(id string, entry *uiConnectionEntry) {
	if _, ok := s.entries[id]; !ok {
		s.order = append(s.order, id)
	}
	s.entries[id] = entry
}

// clearLocked empties the store; reports whether it held anything.
func (s *uiConnectionStream) clearLocked() bool {
	if len(s.entries) == 0 && len(s.order) == 0 {
		return false
	}
	held := len(s.entries) > 0
	s.entries = make(map[string]*uiConnectionEntry)
	s.order = nil
	s.stale = 0
	return held
}

// applyEvent applies one event; reports whether the store changed.
func (s *uiConnectionStream) applyEvent(event *ConnectionEvent) bool {
	switch event.Type {
	case ConnectionEventNew:
		if event.Connection == nil {
			return false
		}
		s.put(event.ID, uiConnectionEntryFrom(event.Connection))
	case ConnectionEventUpdate:
		current, ok := s.entries[event.ID]
		if !ok {
			return false
		}
		if event.UplinkDelta == 0 && event.DownlinkDelta == 0 && current.row.Upload == 0 && current.row.Download == 0 {
			return false
		}
		next := &uiConnectionEntry{row: current.row, outboundType: current.outboundType}
		next.row.Upload = event.UplinkDelta
		next.row.Download = event.DownlinkDelta
		next.row.UploadTotal += event.UplinkDelta
		next.row.DownloadTotal += event.DownlinkDelta
		s.entries[event.ID] = next
	case ConnectionEventClosed:
		var next *uiConnectionEntry
		if event.Connection != nil {
			next = uiConnectionEntryFrom(event.Connection)
		} else if current, ok := s.entries[event.ID]; ok {
			next = &uiConnectionEntry{row: current.row, outboundType: current.outboundType}
		} else {
			return false
		}
		next.row.ClosedAt = nil
		if event.ClosedAt > 0 {
			closedAt := event.ClosedAt
			next.row.ClosedAt = &closedAt
		}
		next.row.Upload, next.row.Download = 0, 0
		s.put(event.ID, next)
	default:
		return false
	}
	return true
}

// evictLocked drops rows closed more than five minutes before now.
func (s *uiConnectionStream) evictLocked(now int64) bool {
	evicted := false
	for id, entry := range s.entries {
		if entry.row.ClosedAt != nil && now-*entry.row.ClosedAt > uiConnectionClosedMaxAge {
			delete(s.entries, id)
			s.stale++
			evicted = true
		}
	}
	if s.stale > 0 && s.stale*2 >= len(s.order) {
		order := s.order[:0]
		for _, id := range s.order {
			if _, ok := s.entries[id]; ok {
				order = append(order, id)
			}
		}
		clear(s.order[len(order):])
		s.order, s.stale = order, 0
	}
	return evicted
}

func (s *uiConnectionStream) apply(events *ConnectionEvents, remote bool) {
	if events == nil {
		return
	}
	s.access.Lock()
	defer s.access.Unlock()
	s.remote = remote
	changed := false
	if events.Reset && s.clearLocked() {
		changed = true
	}
	for _, event := range events.events {
		if event != nil && s.applyEvent(event) {
			changed = true
		}
	}
	if s.evictLocked(s.now()) {
		changed = true
	}
	if changed {
		s.changed()
	}
}

func (s *uiConnectionStream) clear(remote bool) {
	s.access.Lock()
	defer s.access.Unlock()
	s.remote = remote
	if s.clearLocked() {
		s.changed()
	}
}

// each visits the stored rows in insertion order. Callers hold access.
func (s *uiConnectionStream) each(visit func(*uiConnectionEntry)) {
	seen := 0
	for _, id := range s.order {
		if seen == len(s.entries) {
			return
		}
		if entry, ok := s.entries[id]; ok {
			seen++
			visit(entry)
		}
	}
}

// rowsPayload encodes the visible (non-DNS) rows; unchanged rows reuse their bytes.
func (s *uiConnectionStream) rowsPayload() json.RawMessage {
	s.access.Lock()
	parts := make([][]byte, 0, len(s.entries))
	size := 2
	s.each(func(entry *uiConnectionEntry) {
		if entry.outboundType == "dns" {
			return
		}
		if entry.encoded == nil {
			encoded, err := json.Marshal(&entry.row)
			if err != nil {
				return
			}
			entry.encoded = encoded
		}
		parts = append(parts, entry.encoded)
		size += len(entry.encoded) + 1
	})
	s.access.Unlock()
	var buffer bytes.Buffer
	buffer.Grow(size)
	buffer.WriteByte('[')
	for i, part := range parts {
		if i > 0 {
			buffer.WriteByte(',')
		}
		buffer.Write(part)
	}
	buffer.WriteByte(']')
	return buffer.Bytes()
}

// statsPayload counts the visible rows; DNS is the local core's query count,
// or for a remote session the DNS rows the store holds.
func (s *uiConnectionStream) statsPayload() json.RawMessage {
	var stats uiConnectionStats
	s.access.Lock()
	var dnsRows int64
	s.each(func(entry *uiConnectionEntry) {
		if entry.isDNS() {
			dnsRows++
		}
		if entry.outboundType == "dns" {
			return
		}
		if entry.row.ClosedAt == nil {
			stats.Active++
		} else {
			stats.Recent++
		}
	})
	remote := s.remote
	s.access.Unlock()
	if remote {
		stats.DNS = dnsRows
	} else {
		stats.DNS = s.dnsCount()
	}
	payload, _ := json.Marshal(&stats)
	return payload
}

// send emits the shown kinds; reports whether anything was sent.
func (s *uiConnectionStream) send(rows, stats bool) bool {
	if rows {
		s.emit("connections", s.rowsPayload())
	}
	if stats {
		s.emit("connectionStats", s.statsPayload())
	}
	return rows || stats
}

func (s *uiConnectionStream) run(kick, stop, done chan struct{}) {
	defer close(done)
	first := true
	var sent uint64
	for {
		s.access.Lock()
		version, rows, stats := s.version, s.rows > 0, s.stats > 0
		s.access.Unlock()
		if first || version != sent {
			first = false
			sent = version
			if s.send(rows, stats) {
				timer := time.NewTimer(s.interval)
				select {
				case <-stop:
					timer.Stop()
					return
				case <-timer.C:
				}
				continue
			}
		}
		select {
		case <-stop:
			return
		case <-kick:
		}
	}
}

// setForeground counts an owner entering (true) or leaving the foreground;
// while any owner is in it the worker runs. Starting it sends the current
// state at once; stopping waits until it has exited, so nothing is sent after.
func (s *uiConnectionStream) setForeground(foreground bool) {
	s.control.Lock()
	defer s.control.Unlock()
	s.access.Lock()
	if foreground {
		s.foreground++
	} else if s.foreground > 0 {
		s.foreground--
	}
	active := s.foreground > 0
	if s.active == active {
		s.access.Unlock()
		return
	}
	s.active = active
	if active {
		s.kick = make(chan struct{}, 1)
		s.stop = make(chan struct{})
		s.done = make(chan struct{})
		kick, stop, done := s.kick, s.stop, s.done
		s.access.Unlock()
		go s.run(kick, stop, done)
		return
	}
	stop, done := s.stop, s.done
	s.kick, s.stop, s.done = nil, nil, nil
	s.access.Unlock()
	close(stop)
	<-done
}

// show counts an owner showing (true) or hiding a kind; showing sends it at
// once, also when it was already shown.
func (s *uiConnectionStream) show(rows bool, visible bool) {
	s.control.Lock()
	defer s.control.Unlock()
	s.access.Lock()
	count := &s.stats
	if rows {
		count = &s.rows
	}
	if visible {
		*count++
	} else if *count > 0 {
		*count--
	}
	s.access.Unlock()
	if visible {
		s.send(rows, !rows)
	}
}

// resend sends a shown kind again at once.
func (s *uiConnectionStream) resend(rows bool) {
	s.control.Lock()
	defer s.control.Unlock()
	s.send(rows, !rows)
}

// UIConnectionStreamApply applies one core ConnectionEvents batch, in arrival
// order (the host serializes calls). remote tells whether the batch comes from
// a remote-control session.
func UIConnectionStreamApply(events *ConnectionEvents, remote bool) {
	uiConnections.apply(events, remote)
}

// UIConnectionStreamClear empties the store (service stopped, disconnected).
func UIConnectionStreamClear(remote bool) { uiConnections.clear(remote) }

// UIConnectionStreamSetRowsVisible counts the host's owner (a UI bridge)
// showing or hiding the Connections page; call it on changes only. Showing
// sends the rows at once.
func UIConnectionStreamSetRowsVisible(visible bool) { uiConnections.show(true, visible) }

// UIConnectionStreamSetStatsVisible is UIConnectionStreamSetRowsVisible for
// the dashboard's connection statistics.
func UIConnectionStreamSetStatsVisible(visible bool) { uiConnections.show(false, visible) }

// UIConnectionStreamResendRows sends the rows again at once (a show while
// already shown).
func UIConnectionStreamResendRows() { uiConnections.resend(true) }

// UIConnectionStreamResendStats sends the statistics again at once.
func UIConnectionStreamResendStats() { uiConnections.resend(false) }

// UIConnectionStreamSetForeground counts the host's owner entering (true) or
// leaving the foreground, in pairs. While any owner is in it, changes stream
// (the current state at once); the last leave waits so nothing is sent after
// it returns.
func UIConnectionStreamSetForeground(foreground bool) { uiConnections.setForeground(foreground) }
