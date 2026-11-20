package libbox

// The Logs page's stream: a bounded ring of the core's log lines and the
// cursor that turns it into "logs" events (protocol/methods.json).
//
// The host keeps the core subscription (CommandClient) and the visibility
// gating; it feeds raw lines in batches (UILogStreamAppend ... Commit), clears,
// the default level, and whether the page is shown. While shown, one worker
// sends a snapshot at once, then only new lines plus the oldest kept id and the
// default level, at most once per second; lines arriving in between are
// coalesced into the next send. A level-only change re-encodes no lines.

import (
	"regexp"
	"strings"
	"sync"
	"time"
)

const (
	uiLogStreamLimit    = 3000
	uiLogStreamInterval = time.Second
)

var uiLogANSI = regexp.MustCompile("\x1b\\[[;\\d]*m")

type uiLogRecord struct {
	ID         int64  `json:"id"`
	Level      string `json:"level"`
	Message    string `json:"message"`
	ReceivedAt int64  `json:"receivedAt"`
}

// uiLogUpdate is the "logs" event payload.
type uiLogUpdate struct {
	Reset           bool          `json:"reset"`
	FirstID         *int64        `json:"firstId"`
	DefaultLogLevel *string       `json:"defaultLogLevel"`
	Entries         []uiLogRecord `json:"entries"`
}

type uiLogStaged struct {
	level   string
	message string
}

type uiLogStream struct {
	limit    int
	interval time.Duration
	now      func() int64
	emit     func(update *uiLogUpdate)

	control sync.Mutex // serializes SetVisible

	access  sync.Mutex
	ring    []uiLogRecord
	head    int // index of the oldest record
	size    int
	nextID  int64
	staged  []uiLogStaged
	level   *string
	visible bool
	kick    chan struct{}
	stop    chan struct{}
	done    chan struct{}

	// The cursor of the current visible session (port of the Kotlin LogStream).
	initialized bool
	firstID     *int64
	lastID      *int64
	sentLevel   *string
}

func newUILogStream(limit int, interval time.Duration, now func() int64, emit func(*uiLogUpdate)) *uiLogStream {
	if limit <= 0 {
		panic("log stream limit must be positive")
	}
	return &uiLogStream{limit: limit, interval: interval, now: now, emit: emit, ring: make([]uiLogRecord, limit)}
}

var uiLogs = newUILogStream(uiLogStreamLimit, uiLogStreamInterval,
	func() int64 { return time.Now().UnixMilli() },
	func(update *uiLogUpdate) { _ = uiEmit("logs", update) })

// uiLogLevelLabel is the Kotlin LogLevel.from(priority).label.
func uiLogLevelLabel(priority int32) string {
	switch priority {
	case 0:
		return "Panic"
	case 1:
		return "Fatal"
	case 2:
		return "Error"
	case 3:
		return "Warn"
	case 4:
		return "Info"
	case 5:
		return "Debug"
	case 6:
		return "Trace"
	}
	return "Default"
}

func uiLogStripANSI(message string) string {
	if strings.IndexByte(message, 0x1b) < 0 {
		return message
	}
	return uiLogANSI.ReplaceAllString(message, "")
}

// signal wakes the worker; a pending wake-up already covers this change.
// Callers hold access.
func (s *uiLogStream) signal() {
	if s.kick == nil {
		return
	}
	select {
	case s.kick <- struct{}{}:
	default:
	}
}

func (s *uiLogStream) stage(level int32, message string) {
	s.access.Lock()
	s.staged = append(s.staged, uiLogStaged{uiLogLevelLabel(level), message})
	s.access.Unlock()
}

// commit appends the staged batch; of an oversized batch only the newest
// limit lines are kept (and numbered).
func (s *uiLogStream) commit() {
	s.access.Lock()
	defer s.access.Unlock()
	batch := s.staged
	s.staged = nil
	if len(batch) == 0 {
		return
	}
	if len(batch) > s.limit {
		batch = batch[len(batch)-s.limit:]
	}
	for _, entry := range batch {
		s.nextID++
		record := uiLogRecord{ID: s.nextID, Level: entry.level, Message: uiLogStripANSI(entry.message), ReceivedAt: s.now()}
		if s.size == s.limit {
			s.ring[s.head] = record
			s.head = (s.head + 1) % s.limit
		} else {
			s.ring[(s.head+s.size)%s.limit] = record
			s.size++
		}
	}
	s.signal()
}

func (s *uiLogStream) clear() {
	s.access.Lock()
	defer s.access.Unlock()
	if s.size == 0 {
		// The Kotlin state did not change, so nothing was sent.
		return
	}
	for i := range s.ring {
		s.ring[i] = uiLogRecord{}
	}
	s.head, s.size = 0, 0
	s.signal()
}

func (s *uiLogStream) setDefaultLevel(level int32) {
	label := uiLogLevelLabel(level)
	s.access.Lock()
	defer s.access.Unlock()
	if s.level != nil && *s.level == label {
		return
	}
	s.level = &label
	s.signal()
}

func (s *uiLogStream) at(i int) *uiLogRecord { return &s.ring[(s.head+i)%s.limit] }

func uiLogSameID(a, b *int64) bool {
	return a == nil && b == nil || a != nil && b != nil && *a == *b
}

func uiLogSameLevel(a, b *string) bool {
	return a == nil && b == nil || a != nil && b != nil && *a == *b
}

// update is the Kotlin LogStream.update over the ring: nil when nothing
// changed since the last send, else the records after the last sent id.
func (s *uiLogStream) update() *uiLogUpdate {
	s.access.Lock()
	defer s.access.Unlock()
	var first, last *int64
	if s.size > 0 {
		f, l := s.at(0).ID, s.at(s.size-1).ID
		first, last = &f, &l
	}
	if s.initialized && uiLogSameID(first, s.firstID) && uiLogSameID(last, s.lastID) && uiLogSameLevel(s.level, s.sentLevel) {
		return nil
	}
	previousLast := s.lastID
	reset := !s.initialized || last == nil || previousLast != nil && *last < *previousLast
	start := s.size
	if reset || previousLast == nil {
		start = 0
	} else {
		// Usually only the newest batch is visited; retained entries are not re-encoded.
		for start > 0 && s.at(start-1).ID > *previousLast {
			start--
		}
	}
	entries := make([]uiLogRecord, 0, s.size-start)
	for i := start; i < s.size; i++ {
		entries = append(entries, *s.at(i))
	}
	s.initialized = true
	s.firstID = first
	s.lastID = last
	s.sentLevel = s.level
	return &uiLogUpdate{Reset: reset, FirstID: first, DefaultLogLevel: s.level, Entries: entries}
}

func (s *uiLogStream) run(kick, stop, done chan struct{}) {
	defer close(done)
	for {
		if update := s.update(); update != nil {
			s.emit(update)
			timer := time.NewTimer(s.interval)
			select {
			case <-stop:
				timer.Stop()
				return
			case <-timer.C:
			}
		}
		select {
		case <-stop:
			return
		case <-kick:
		}
	}
}

// setVisible starts the worker (which sends a snapshot at once) or stops it
// and waits until it has exited, so no send follows a hide.
func (s *uiLogStream) setVisible(visible bool) {
	s.control.Lock()
	defer s.control.Unlock()
	s.access.Lock()
	if s.visible == visible {
		s.access.Unlock()
		return
	}
	s.visible = visible
	s.initialized = false
	if visible {
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

// UILogStreamAppend stages one raw core log line (level is the core priority)
// for the next UILogStreamCommit.
func UILogStreamAppend(level int32, message string) { uiLogs.stage(level, message) }

// UILogStreamCommit appends the staged lines to the ring as one batch.
func UILogStreamCommit() { uiLogs.commit() }

// UILogStreamClear empties the ring (the core's clear-logs broadcast); a
// shown page receives a reset.
func UILogStreamClear() { uiLogs.clear() }

// UILogStreamSetDefaultLevel records the core's default log level.
func UILogStreamSetDefaultLevel(level int32) { uiLogs.setDefaultLevel(level) }

// UILogStreamSetVisible starts or stops streaming to the UI. The host passes
// true only while the Logs page is shown and the app is in the foreground;
// every start sends a fresh snapshot.
func UILogStreamSetVisible(visible bool) { uiLogs.setVisible(visible) }
