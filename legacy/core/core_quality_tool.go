package libbox

import (
	"encoding/json"
	"errors"
	"io"
	"sync"
)

// uiQualityRunner is one standalone test (NetworkQualityTest outside the service).
type uiQualityRunner interface {
	Start(configURL string, serial bool, maxRuntimeSeconds int32, http3 bool, handler NetworkQualityTestHandler)
	Cancel()
}

// uiQualityState is the Tools > Network quality state, a port of the old
// Kotlin NetworkQualityState; its JSON is the "quality" event payload.
type uiQualityState struct {
	Phase              int32  `json:"phase"`
	IdleLatencyMs      int32  `json:"idleLatencyMs"`
	DownloadCapacity   int64  `json:"downloadCapacity"`
	UploadCapacity     int64  `json:"uploadCapacity"`
	DownloadRPM        int32  `json:"downloadRPM"`
	UploadRPM          int32  `json:"uploadRPM"`
	IsRunning          bool   `json:"isRunning"`
	ConfigURL          string `json:"configURL"`
	Serial             bool   `json:"serial"`
	HTTP3              bool   `json:"http3"`
	ShowMeteredWarning bool   `json:"showMeteredWarning"`
	maxRuntime         int32
	outbound           string
}

func uiQualityInitialState() uiQualityState {
	return uiQualityState{Phase: -1, ConfigURL: NetworkQualityDefaultConfigURL, maxRuntime: 30}
}

// uiQualityMaxRuntime keeps the two offered runtimes: 60 s, else 30 s.
func uiQualityMaxRuntime(seconds int32) int32 {
	if seconds == 60 {
		return 60
	}
	return 30
}

// uiQualityTool runs one network quality test at a time, through the running
// service (a CommandClient session) or standalone. A metered network first
// asks for confirmation (the app reports whether the network is metered).
type uiQualityTool struct {
	access     sync.Mutex
	state      uiQualityState
	generation int64
	session    io.Closer
	standalone uiQualityRunner
	last       string

	emit          func(payload string)
	newStandalone func() uiQualityRunner
	startRemote   func(host UIToolHost, state uiQualityState, handler NetworkQualityTestHandler) (io.Closer, error)
}

func newUIQualityTool(emit func(string)) *uiQualityTool {
	return &uiQualityTool{
		state:         uiQualityInitialState(),
		emit:          emit,
		newStandalone: func() uiQualityRunner { return NewNetworkQualityTest() },
		startRemote: func(host UIToolHost, state uiQualityState, handler NetworkQualityTestHandler) (io.Closer, error) {
			client, err := host.CommandClient()
			if err != nil {
				return nil, err
			}
			session, err := client.StartNetworkQualityTest(state.ConfigURL, state.outbound, state.Serial, state.maxRuntime, state.HTTP3, handler)
			if err != nil {
				return nil, err
			}
			return session, nil
		},
	}
}

func (t *uiQualityTool) payloadLocked() string {
	content, _ := json.Marshal(t.state)
	return string(content)
}

func (t *uiQualityTool) changedLocked() {
	payload := t.payloadLocked()
	if payload == t.last {
		return
	}
	t.last = payload
	if t.emit != nil {
		t.emit(payload)
	}
}

func (t *uiQualityTool) payload() string {
	t.access.Lock()
	defer t.access.Unlock()
	return t.payloadLocked()
}

// request is tools.startQuality: it records the options, then asks for
// confirmation on a metered network or starts the test.
func (t *uiQualityTool) request(running, metered bool, configURL, outbound string, serial, http3 bool, seconds int32, host UIToolHost) error {
	t.access.Lock()
	if t.state.IsRunning {
		t.access.Unlock()
		return errors.New("A network quality test is already running")
	}
	t.state.ConfigURL = configURL
	t.state.outbound = outbound
	t.state.Serial = serial
	t.state.HTTP3 = http3
	t.state.maxRuntime = uiQualityMaxRuntime(seconds)
	if metered {
		t.state.ShowMeteredWarning = true
		t.changedLocked()
		t.access.Unlock()
		return nil
	}
	t.changedLocked()
	t.access.Unlock()
	t.start(running, host)
	return nil
}

// confirm is tools.confirmQuality: start after the metered warning.
func (t *uiQualityTool) confirm(running bool, host UIToolHost) {
	t.access.Lock()
	t.state.ShowMeteredWarning = false
	t.changedLocked()
	t.access.Unlock()
	t.start(running, host)
}

// dismiss is tools.dismissQuality.
func (t *uiQualityTool) dismiss() {
	t.access.Lock()
	t.state.ShowMeteredWarning = false
	t.changedLocked()
	t.access.Unlock()
}

func (t *uiQualityTool) start(running bool, host UIToolHost) {
	t.access.Lock()
	t.state.Phase = -1
	t.state.IdleLatencyMs = 0
	t.state.DownloadCapacity = 0
	t.state.UploadCapacity = 0
	t.state.DownloadRPM = 0
	t.state.UploadRPM = 0
	t.state.IsRunning = true
	t.changedLocked()
	t.generation++
	generation := t.generation
	previous := t.session
	t.session = nil
	state := t.state
	handler := &uiQualityHandler{tool: t, generation: generation, host: host}
	var runner uiQualityRunner
	if !running {
		runner = t.newStandalone()
		t.standalone = runner
	}
	t.access.Unlock()
	uiCloseQuietly(previous)
	if runner != nil {
		runner.Start(state.ConfigURL, state.Serial, state.maxRuntime, state.HTTP3, handler)
		return
	}
	go func() {
		session, err := t.startRemote(host, state, handler)
		t.access.Lock()
		if err != nil {
			if generation != t.generation || !t.state.IsRunning {
				t.access.Unlock()
				return
			}
			t.state.IsRunning = false
			t.changedLocked()
			t.access.Unlock()
			host.ReportError(err.Error())
			return
		}
		if generation == t.generation {
			t.session = session
			t.access.Unlock()
			return
		}
		t.access.Unlock()
		uiCloseQuietly(session)
	}()
}

// stopWith ends the current test; disconnected also forgets the outbound
// (the service went away), reset returns to the initial state.
func (t *uiQualityTool) stopWith(disconnected, reset bool) {
	t.access.Lock()
	t.generation++
	previous := t.session
	t.session = nil
	runner := t.standalone
	t.standalone = nil
	t.access.Unlock()
	uiCloseQuietly(previous)
	if runner != nil {
		runner.Cancel()
	}
	t.access.Lock()
	switch {
	case reset:
		t.state = uiQualityInitialState()
	default:
		t.state.IsRunning = false
		if disconnected {
			t.state.outbound = ""
		}
	}
	t.changedLocked()
	t.access.Unlock()
}

type uiQualityHandler struct {
	tool       *uiQualityTool
	generation int64
	host       UIToolHost
}

func (h *uiQualityHandler) currentLocked() bool {
	return h.generation == h.tool.generation && h.tool.state.IsRunning
}

func (h *uiQualityHandler) OnProgress(progress *NetworkQualityProgress) {
	if progress == nil {
		return
	}
	t := h.tool
	t.access.Lock()
	defer t.access.Unlock()
	if !h.currentLocked() {
		return
	}
	t.state.Phase = progress.Phase
	t.state.IdleLatencyMs = progress.IdleLatencyMs
	t.state.DownloadCapacity = progress.DownloadCapacity
	t.state.UploadCapacity = progress.UploadCapacity
	t.state.DownloadRPM = progress.DownloadRPM
	t.state.UploadRPM = progress.UploadRPM
	t.changedLocked()
}

func (h *uiQualityHandler) OnResult(result *NetworkQualityResult) {
	if result == nil {
		return
	}
	t := h.tool
	t.access.Lock()
	defer t.access.Unlock()
	if !h.currentLocked() {
		return
	}
	t.state.Phase = NetworkQualityPhaseDone
	t.state.IdleLatencyMs = result.IdleLatencyMs
	t.state.DownloadCapacity = result.DownloadCapacity
	t.state.UploadCapacity = result.UploadCapacity
	t.state.DownloadRPM = result.DownloadRPM
	t.state.UploadRPM = result.UploadRPM
	t.state.IsRunning = false
	t.changedLocked()
	t.standalone = nil
	t.session = nil
}

func (h *uiQualityHandler) OnError(message string) {
	t := h.tool
	t.access.Lock()
	if !h.currentLocked() {
		t.access.Unlock()
		return
	}
	t.state.IsRunning = false
	t.changedLocked()
	t.standalone = nil
	t.session = nil
	t.access.Unlock()
	if h.host != nil {
		h.host.ReportError(message)
	}
}

var uiQuality = newUIQualityTool(func(payload string) { _ = uiEmitRaw("quality", payload) })

// UIQualityStart is tools.startQuality (configURL already validated by the app).
func UIQualityStart(running bool, metered bool, configURL string, outbound string, serial bool, http3 bool, seconds int32, host UIToolHost) error {
	return uiQuality.request(running, metered, configURL, outbound, serial, http3, seconds, host)
}

// UIQualityRunning reports whether a test is running (checked before the URL).
func UIQualityRunning() bool {
	uiQuality.access.Lock()
	defer uiQuality.access.Unlock()
	return uiQuality.state.IsRunning
}

// UIQualityConfirm starts the test after the metered-network warning.
func UIQualityConfirm(running bool, host UIToolHost) { uiQuality.confirm(running, host) }

// UIQualityDismiss closes the metered-network warning.
func UIQualityDismiss() { uiQuality.dismiss() }

// UIQualityStop cancels the current test.
func UIQualityStop() { uiQuality.stopWith(false, false) }

// UIQualityDisconnected cancels the test and forgets the outbound.
func UIQualityDisconnected() { uiQuality.stopWith(true, false) }

// UIQualityReset cancels the test and returns to the initial state.
func UIQualityReset() { uiQuality.stopWith(false, true) }

// UIQualityPayload answers the current "quality" event payload (also used by diagnostics).
func UIQualityPayload() string { return uiQuality.payload() }
