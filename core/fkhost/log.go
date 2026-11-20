package fkhost

import (
	"fmt"
	"os"
	"sync"
	"time"
)

// Level is a log line's severity.
type Level uint8

const (
	LevelError Level = iota
	LevelWarn
	LevelInfo
	LevelDebug
)

func (l Level) String() string {
	switch l {
	case LevelError:
		return "error"
	case LevelWarn:
		return "warn"
	case LevelDebug:
		return "debug"
	}
	return "info"
}

// Priority is the level as a sing-box log priority (the UI log stream's
// numbering): error 2, warn 3, info 4, debug 5.
func (l Level) Priority() int32 {
	switch l {
	case LevelError:
		return 2
	case LevelWarn:
		return 3
	case LevelDebug:
		return 5
	}
	return 4
}

// Entry is one log line.
type Entry struct {
	ID      int64
	Level   Level
	Message string
	Time    time.Time
}

// LogLimit is how many recent lines Log keeps.
const LogLimit = 1000

// Log is the process's fk-box log: the kernels write here.
var Log = NewLogger(LogLimit)

type logSink struct {
	id   uint64
	sink func(Entry)
}

// Logger keeps the newest lines in a ring and hands each new line to the
// host's forward (the service log the app shows) and to every subscriber.
type Logger struct {
	access     sync.Mutex
	limit      int
	ring       []Entry
	head       int
	size       int
	nextID     int64
	forward    func(Entry)
	sinks      []logSink
	nextSink   uint64
	diagnostic func(string)
}

// NewLogger returns a logger keeping the newest limit lines.
func NewLogger(limit int) *Logger {
	if limit <= 0 {
		limit = 1
	}
	return &Logger{limit: limit, ring: make([]Entry, limit)}
}

// Write adds a line. The forward and the subscribers run on the caller's
// goroutine, in that order, without the logger's lock held.
func (l *Logger) Write(level Level, message string) {
	l.access.Lock()
	entry := Entry{ID: l.nextID, Level: level, Message: message, Time: time.Now()}
	l.nextID++
	if l.size < l.limit {
		l.ring[(l.head+l.size)%l.limit] = entry
		l.size++
	} else {
		l.ring[l.head] = entry
		l.head = (l.head + 1) % l.limit
	}
	forward := l.forward
	sinks := append([]logSink(nil), l.sinks...)
	l.access.Unlock()
	if forward != nil {
		forward(entry)
	}
	for _, sink := range sinks {
		sink.sink(entry)
	}
}

// Recent returns the kept lines, oldest first.
func (l *Logger) Recent() []Entry {
	l.access.Lock()
	defer l.access.Unlock()
	entries := make([]Entry, l.size)
	for i := range entries {
		entries[i] = l.ring[(l.head+i)%l.limit]
	}
	return entries
}

// SetForward sets (nil removes) where every line also goes: the embedding
// host's own service log (Android: the command service's log, which the
// app's log page reads). It replaces the previous forward.
func (l *Logger) SetForward(forward func(Entry)) {
	l.access.Lock()
	l.forward = forward
	l.access.Unlock()
}

// Subscribe calls sink with every new line until cancel is called.
func (l *Logger) Subscribe(sink func(Entry)) (cancel func()) {
	l.access.Lock()
	id := l.nextSink
	l.nextSink++
	l.sinks = append(l.sinks, logSink{id, sink})
	l.access.Unlock()
	return func() {
		l.access.Lock()
		defer l.access.Unlock()
		for i, item := range l.sinks {
			if item.id == id {
				l.sinks = append(l.sinks[:i:i], l.sinks[i+1:]...)
				return
			}
		}
	}
}

// SetDiagnostic sets (nil removes) the process log Diagnostic writes to
// (Android: sing-box's logger, which ends in logcat).
func (l *Logger) SetDiagnostic(diagnostic func(string)) {
	l.access.Lock()
	l.diagnostic = diagnostic
	l.access.Unlock()
}

// Diagnostic reports a problem to the process log only, not to the service
// log the app shows; without a process log it goes to standard error.
func (l *Logger) Diagnostic(message string) {
	l.access.Lock()
	diagnostic := l.diagnostic
	l.access.Unlock()
	if diagnostic != nil {
		diagnostic(message)
		return
	}
	fmt.Fprintln(os.Stderr, "fk-box:", message)
}
