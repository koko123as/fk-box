package libbox

import (
	"encoding/json"
	"errors"
	"io"
	"sync"
)

// UIToolHost is what the Go tools need from the app: a command client for the
// running service (shared in remote mode, never disconnected by Go) and the
// app's error report path (the UI "error" event).
type UIToolHost interface {
	CommandClient() (*CommandClient, error)
	ReportError(message string)
}

// uiSTUNRunner is one standalone test (STUNTest outside the service).
type uiSTUNRunner interface {
	Start(server string, handler STUNTestHandler)
	Cancel()
}

// uiSTUNState is the Tools > STUN state, a port of the old Kotlin STUNTestState.
type uiSTUNState struct {
	Phase            int32  `json:"phase"`
	ExternalAddr     string `json:"externalAddr"`
	LatencyMs        int32  `json:"latencyMs"`
	NATTypeSupported bool   `json:"natTypeSupported"`
	IsRunning        bool   `json:"isRunning"`
	Server           string `json:"server"`
	natMapping       int32
	natFiltering     int32
}

type uiSTUNPayload struct {
	uiSTUNState
	Mapping   string `json:"mapping"`
	Filtering string `json:"filtering"`
}

func uiSTUNInitialState() uiSTUNState {
	return uiSTUNState{Phase: -1, Server: STUNDefaultServer}
}

// uiSTUNTool runs one STUN test at a time, either through the running service
// (a CommandClient session) or standalone. Each start takes a generation; a
// stop moves to the next one, so callbacks and sessions of an older test are
// ignored (sessions are closed), as the Kotlin SessionSlot did.
type uiSTUNTool struct {
	access     sync.Mutex
	state      uiSTUNState
	generation int64
	session    io.Closer
	standalone uiSTUNRunner
	last       string

	emit          func(payload string)
	newStandalone func() uiSTUNRunner
	startRemote   func(host UIToolHost, server, outbound string, handler STUNTestHandler) (io.Closer, error)
}

func newUISTUNTool(emit func(string)) *uiSTUNTool {
	return &uiSTUNTool{
		state:         uiSTUNInitialState(),
		emit:          emit,
		newStandalone: func() uiSTUNRunner { return NewSTUNTest() },
		startRemote: func(host UIToolHost, server, outbound string, handler STUNTestHandler) (io.Closer, error) {
			client, err := host.CommandClient()
			if err != nil {
				return nil, err
			}
			session, err := client.StartSTUNTest(server, outbound, handler)
			if err != nil {
				return nil, err
			}
			return session, nil
		},
	}
}

func (t *uiSTUNTool) payloadLocked() string {
	content, _ := json.Marshal(uiSTUNPayload{
		uiSTUNState: t.state,
		Mapping:     FormatNATMapping(t.state.natMapping),
		Filtering:   FormatNATFiltering(t.state.natFiltering),
	})
	return string(content)
}

// changedLocked sends the state when it differs from the last one sent
// (the Kotlin StateFlow only emitted distinct states).
func (t *uiSTUNTool) changedLocked() {
	payload := t.payloadLocked()
	if payload == t.last {
		return
	}
	t.last = payload
	if t.emit != nil {
		t.emit(payload)
	}
}

func (t *uiSTUNTool) payload() string {
	t.access.Lock()
	defer t.access.Unlock()
	return t.payloadLocked()
}

func (t *uiSTUNTool) diagnostics() string {
	t.access.Lock()
	defer t.access.Unlock()
	content, _ := json.Marshal(t.state)
	return string(content)
}

func (t *uiSTUNTool) start(running bool, server, outbound string, host UIToolHost) error {
	t.access.Lock()
	if t.state.IsRunning {
		t.access.Unlock()
		return errors.New("A STUN test is already running")
	}
	t.state = uiSTUNState{Phase: -1, IsRunning: true, Server: server}
	t.changedLocked()
	t.generation++
	generation := t.generation
	previous := t.session
	t.session = nil
	handler := &uiSTUNHandler{tool: t, generation: generation, host: host}
	var runner uiSTUNRunner
	if !running {
		runner = t.newStandalone()
		t.standalone = runner
	}
	t.access.Unlock()
	uiCloseQuietly(previous)
	if runner != nil {
		runner.Start(server, handler)
		return nil
	}
	go func() {
		session, err := t.startRemote(host, server, outbound, handler)
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
		// Cancelled or replaced while it was starting.
		t.access.Unlock()
		uiCloseQuietly(session)
	}()
	return nil
}

func (t *uiSTUNTool) stop() {
	t.stopWith(false)
}

// stopWith ends the current test; reset also returns to the initial state
// (a new Tools page, as a new Kotlin ViewModel did).
func (t *uiSTUNTool) stopWith(reset bool) {
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
	if reset {
		t.state = uiSTUNInitialState()
	} else {
		t.state.IsRunning = false
	}
	t.changedLocked()
	t.access.Unlock()
}

func uiCloseQuietly(closer io.Closer) {
	if closer != nil {
		_ = closer.Close()
	}
}

type uiSTUNHandler struct {
	tool       *uiSTUNTool
	generation int64
	host       UIToolHost
}

func (h *uiSTUNHandler) currentLocked() bool {
	return h.generation == h.tool.generation && h.tool.state.IsRunning
}

func (h *uiSTUNHandler) OnProgress(progress *STUNTestProgress) {
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
	t.state.ExternalAddr = progress.ExternalAddr
	t.state.LatencyMs = progress.LatencyMs
	t.state.natMapping = progress.NATMapping
	t.state.natFiltering = progress.NATFiltering
	t.changedLocked()
}

func (h *uiSTUNHandler) OnResult(result *STUNTestResult) {
	if result == nil {
		return
	}
	t := h.tool
	t.access.Lock()
	defer t.access.Unlock()
	if !h.currentLocked() {
		return
	}
	t.state.Phase = STUNPhaseDone
	t.state.IsRunning = false
	t.state.ExternalAddr = result.ExternalAddr
	t.state.LatencyMs = result.LatencyMs
	t.state.natMapping = result.NATMapping
	t.state.natFiltering = result.NATFiltering
	t.state.NATTypeSupported = result.NATTypeSupported
	t.changedLocked()
	t.standalone = nil
	t.session = nil
}

func (h *uiSTUNHandler) OnError(message string) {
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

var uiSTUN = newUISTUNTool(func(payload string) { _ = uiEmitRaw("stun", payload) })

// UIStunStart starts a STUN test: through the service's command client when
// running, otherwise standalone. Progress is sent as the "stun" UI event.
func UIStunStart(running bool, server string, outbound string, host UIToolHost) error {
	return uiSTUN.start(running, server, outbound, host)
}

// UIStunStop cancels the current STUN test.
func UIStunStop() { uiSTUN.stop() }

// UIStunReset cancels the test and returns to the initial state.
func UIStunReset() { uiSTUN.stopWith(true) }

// UIStunPayload answers the current "stun" event payload.
func UIStunPayload() string { return uiSTUN.payload() }

// UIStunDiagnostics answers the STUN state for diagnostics (no NAT texts).
func UIStunDiagnostics() string { return uiSTUN.diagnostics() }
