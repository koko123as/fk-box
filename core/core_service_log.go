package libbox

// The service log: sing-box's own lines (box.Options.PlatformLogWriter) and
// every fk-box line (fkhost.Log: Xray, mihomo, the kernel switch) in one ring,
// served as the StartedService SubscribeLog stream by fk-box's command
// service (core_command_server.go). libbox's StartedService log is no longer
// read by the UI; each line is still copied there because libbox's OOM
// recorder (created by NewCommandServer) attaches that log to its reports.

import (
	"sync"

	"github.com/sagernet/sing-box/daemon"
	"github.com/sagernet/sing-box/experimental/fkhost"
	"github.com/sagernet/sing-box/log"
	"google.golang.org/grpc"
)

const coreServiceLogDefaultLines = 3000

type coreLogEntry struct {
	level   log.Level
	message string
}

type coreLogSubscriber struct {
	signal  chan struct{}
	pending []coreLogEntry
	reset   bool
}

type coreServiceLog struct {
	access      sync.Mutex
	entries     []coreLogEntry
	start       int
	subscribers map[*coreLogSubscriber]struct{}
	// copyTo receives every line as well (Android: libbox's StartedService
	// log, which libbox's OOM report attaches and, in debug builds, passes
	// to the app's debug output).
	copyTo func(level log.Level, message string)
}

var coreLog = &coreServiceLog{subscribers: make(map[*coreLogSubscriber]struct{})}

func (l *coreServiceLog) maxLines() int {
	if sLogMaxLines > 0 {
		return sLogMaxLines
	}
	return coreServiceLogDefaultLines
}

// WriteMessage adds a line (log.PlatformWriter).
func (l *coreServiceLog) WriteMessage(level log.Level, message string) {
	entry := coreLogEntry{level, message}
	l.access.Lock()
	limit := l.maxLines()
	if len(l.entries) < limit {
		l.entries = append(l.entries, entry)
	} else {
		l.entries[l.start] = entry
		l.start = (l.start + 1) % len(l.entries)
	}
	for subscriber := range l.subscribers {
		if subscriber.reset {
			continue
		}
		if len(subscriber.pending) >= limit {
			// The reader fell a whole ring behind: send it the ring again.
			subscriber.pending = nil
			subscriber.reset = true
		} else {
			subscriber.pending = append(subscriber.pending, entry)
		}
		select {
		case subscriber.signal <- struct{}{}:
		default:
		}
	}
	copyTo := l.copyTo
	l.access.Unlock()
	if copyTo != nil {
		copyTo(level, message)
	}
}

// Clear empties the ring; every subscriber receives a reset.
func (l *coreServiceLog) Clear() {
	l.access.Lock()
	clear(l.entries)
	l.entries = l.entries[:0]
	l.start = 0
	for subscriber := range l.subscribers {
		subscriber.pending = nil
		subscriber.reset = true
		select {
		case subscriber.signal <- struct{}{}:
		default:
		}
	}
	l.access.Unlock()
}

func (l *coreServiceLog) savedLocked() []coreLogEntry {
	saved := make([]coreLogEntry, 0, len(l.entries))
	saved = append(saved, l.entries[l.start:]...)
	return append(saved, l.entries[:l.start]...)
}

func coreLogMessages(entries []coreLogEntry) []*daemon.Log_Message {
	messages := make([]*daemon.Log_Message, len(entries))
	for i, entry := range entries {
		messages[i] = &daemon.Log_Message{Level: daemon.LogLevel(entry.level), Message: entry.message}
	}
	return messages
}

// Serve is the SubscribeLog stream: the saved lines with a reset, then the
// new lines as they come (batched), a reset after Clear.
func (l *coreServiceLog) Serve(server grpc.ServerStreamingServer[daemon.Log]) error {
	subscriber := &coreLogSubscriber{signal: make(chan struct{}, 1)}
	l.access.Lock()
	saved := l.savedLocked()
	l.subscribers[subscriber] = struct{}{}
	l.access.Unlock()
	defer func() {
		l.access.Lock()
		delete(l.subscribers, subscriber)
		l.access.Unlock()
	}()
	if err := server.Send(&daemon.Log{Messages: coreLogMessages(saved), Reset_: true}); err != nil {
		return err
	}
	for {
		select {
		case <-server.Context().Done():
			return server.Context().Err()
		case <-subscriber.signal:
		}
		l.access.Lock()
		pending, reset := subscriber.pending, subscriber.reset
		subscriber.pending, subscriber.reset = nil, false
		if reset {
			pending = l.savedLocked()
		}
		l.access.Unlock()
		if !reset && len(pending) == 0 {
			continue
		}
		if err := server.Send(&daemon.Log{Messages: coreLogMessages(pending), Reset_: reset}); err != nil {
			return err
		}
	}
}

// coreLogLevel is a fk-box line's level as a sing-box level.
func coreLogLevel(level fkhost.Level) log.Level {
	switch level {
	case fkhost.LevelError:
		return log.LevelError
	case fkhost.LevelWarn:
		return log.LevelWarn
	case fkhost.LevelDebug:
		return log.LevelDebug
	}
	return log.LevelInfo
}
