package libbox

// The dashboard Memory card's stream: the "memory" event (protocol/methods.json).
//
// The host keeps the Android signals only: for each of its owners (a UI
// bridge) whether the card is subscribed, the app is in the foreground and the
// screen is on (UIDashboardMemorySetSignals), plus where the numbers come from
// (UIDashboardMemorySetSource: remote control and the remote core's memory,
// and whether the local service runs). While some owner has all three signals
// one worker samples at once, then every two seconds; when the signals return
// after a pause the next sample is again immediate. Stopping waits for the
// worker, so no sample is sent after the setter that hid the card returns.
//
// Core memory is the Go runtime's (FlClash splits the process RSS the same
// way: app = RSS - core runtime).

import (
	"bufio"
	"encoding/json"
	"errors"
	"os"
	"runtime/debug"
	"strconv"
	"strings"
	"sync"
	"time"
)

const uiDashboardMemoryInterval = 2 * time.Second

// uiMemorySource is what the host knows about the memory being shown.
type uiMemorySource struct {
	remote       bool
	remoteMemory int64
	running      bool
}

// uiMemoryPayload is the "memory" event payload. A remote session carries
// total, core and remote only.
type uiMemoryPayload struct {
	Total        int64  `json:"total"`
	RSS          *int64 `json:"rss,omitempty"`
	App          *int64 `json:"app,omitempty"`
	Core         int64  `json:"core"`
	Remote       bool   `json:"remote"`
	Running      *bool  `json:"running,omitempty"`
	HeapInuse    *int64 `json:"heapInuse,omitempty"`
	HeapIdle     *int64 `json:"heapIdle,omitempty"`
	Stack        *int64 `json:"stack,omitempty"`
	RuntimeOther *int64 `json:"runtimeOther,omitempty"`
	Goroutines   *int32 `json:"goroutines,omitempty"`
}

// uiMemorySample builds the payload (port of the Kotlin BoxDashboard.memory).
func uiMemorySample(source uiMemorySource, rss int64, core *UIRuntimeMemory) *uiMemoryPayload {
	if source.remote {
		return &uiMemoryPayload{Remote: true, Total: source.remoteMemory, Core: source.remoteMemory}
	}
	// What the kernel keeps resident for Go's own mappings can exceed Go's
	// accounting (huge pages refilling released memory); count that as core
	// rather than letting it inflate the app share. The excess is runtime
	// memory outside the heap and stack rows.
	accounted := max(core.Total, core.Resident)
	excess := max(0, core.Resident-core.Total)
	app := max(0, rss-accounted)
	running := source.running
	other := core.RuntimeOther + excess
	heapInuse, heapIdle, stack, goroutines := core.HeapInuse, core.HeapIdle, core.StackInuse, core.Goroutines
	return &uiMemoryPayload{
		Total: max(rss, accounted), RSS: &rss, App: &app, Core: accounted, Running: &running,
		HeapInuse: &heapInuse, HeapIdle: &heapIdle, Stack: &stack, RuntimeOther: &other, Goroutines: &goroutines,
	}
}

// readProcessRSS is VmRSS of /proc/self/status in bytes, 0 when unreadable.
func readProcessRSS(path string) int64 {
	file, err := os.Open(path)
	if err != nil {
		return 0
	}
	defer file.Close()
	scanner := bufio.NewScanner(file)
	for scanner.Scan() {
		line := scanner.Text()
		if !strings.HasPrefix(line, "VmRSS:") {
			continue
		}
		fields := strings.Fields(strings.TrimPrefix(line, "VmRSS:"))
		if len(fields) == 0 {
			return 0
		}
		value, err := strconv.ParseInt(fields[0], 10, 64)
		if err != nil {
			return 0
		}
		return value * 1024
	}
	return 0
}

type uiDashboardMemory struct {
	interval time.Duration
	after    func(time.Duration) <-chan time.Time
	sample   func(uiMemorySource) *uiMemoryPayload
	emit     func(*uiMemoryPayload)

	control sync.Mutex // serializes worker starts and stops

	access  sync.Mutex
	owners  map[int32]bool // owner -> subscribed && foreground && screen on
	source  uiMemorySource
	running bool // a worker is active
	stop    chan struct{}
	done    chan struct{}
}

func newUIDashboardMemory(interval time.Duration, after func(time.Duration) <-chan time.Time,
	sample func(uiMemorySource) *uiMemoryPayload, emit func(*uiMemoryPayload),
) *uiDashboardMemory {
	return &uiDashboardMemory{interval: interval, after: after, sample: sample, emit: emit, owners: map[int32]bool{}}
}

func (m *uiDashboardMemory) current() *uiMemoryPayload {
	m.access.Lock()
	source := m.source
	m.access.Unlock()
	return m.sample(source)
}

func (m *uiDashboardMemory) run(stop, done chan struct{}) {
	defer close(done)
	for {
		payload := m.current()
		// A hide that raced the sample wins: nothing is sent after it.
		select {
		case <-stop:
			return
		default:
		}
		m.emit(payload)
		select {
		case <-stop:
			return
		case <-m.after(m.interval):
		}
	}
}

func (m *uiDashboardMemory) setSignals(owner int32, subscribed, foreground, screenOn bool) {
	m.control.Lock()
	defer m.control.Unlock()
	m.access.Lock()
	if subscribed || foreground || screenOn {
		m.owners[owner] = subscribed && foreground && screenOn
	} else {
		delete(m.owners, owner)
	}
	active := false
	for _, value := range m.owners {
		active = active || value
	}
	if active == m.running {
		m.access.Unlock()
		return
	}
	m.running = active
	if active {
		m.stop, m.done = make(chan struct{}), make(chan struct{})
		stop, done := m.stop, m.done
		m.access.Unlock()
		go m.run(stop, done)
		return
	}
	stop, done := m.stop, m.done
	m.stop, m.done = nil, nil
	m.access.Unlock()
	close(stop)
	<-done
}

func (m *uiDashboardMemory) setSource(source uiMemorySource) {
	m.access.Lock()
	m.source = source
	m.access.Unlock()
}

func (m *uiDashboardMemory) release(collect func()) (*uiMemoryPayload, error) {
	m.access.Lock()
	remote := m.source.remote
	m.access.Unlock()
	if remote {
		return nil, errors.New("Remote memory collection is not supported")
	}
	collect()
	payload := m.current()
	m.emit(payload)
	return payload, nil
}

var uiDashboardMemoryStream = newUIDashboardMemory(uiDashboardMemoryInterval, time.After,
	func(source uiMemorySource) *uiMemoryPayload {
		if source.remote {
			return uiMemorySample(source, 0, nil)
		}
		return uiMemorySample(source, readProcessRSS("/proc/self/status"), ReadUIRuntimeMemory())
	},
	func(payload *uiMemoryPayload) { _ = uiEmit("memory", payload) })

// UIDashboardMemorySetSignals records one host owner's Android signals: the
// Memory card is subscribed, the app is in the foreground and the screen is
// on. Memory streams while any owner has all three; all false forgets the
// owner. Turning the stream off waits until its worker has exited.
func UIDashboardMemorySetSignals(owner int32, subscribed bool, foreground bool, screenOn bool) {
	uiDashboardMemoryStream.setSignals(owner, subscribed, foreground, screenOn)
}

// UIDashboardMemorySetSource records where memory is read: a remote session
// shows remoteMemory (the remote core's status), otherwise this process is
// sampled and running says whether the local service is started.
func UIDashboardMemorySetSource(remote bool, remoteMemory int64, running bool) {
	uiDashboardMemoryStream.setSource(uiMemorySource{remote: remote, remoteMemory: remoteMemory, running: running})
}

// UIDashboardReleaseMemory returns freed Go memory to the OS, sends a fresh
// "memory" event and answers the same payload as JSON.
func UIDashboardReleaseMemory() (string, error) {
	payload, err := uiDashboardMemoryStream.release(debug.FreeOSMemory)
	if err != nil {
		return "", err
	}
	encoded, err := json.Marshal(payload)
	return string(encoded), err
}
