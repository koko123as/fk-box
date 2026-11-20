package libbox

import (
	"encoding/json"
	"io"
	"math"
	"sync"
)

// uiPingState is the Tailscale ping state, a port of the old Kotlin
// TailscalePingState; its JSON is the "ping" event payload.
type uiPingState struct {
	IsRunning      bool
	LatencyMs      float64
	IsDirect       bool
	DERPRegionCode string
	Endpoint       string
	Error          string
}

func (s uiPingState) MarshalJSON() ([]byte, error) {
	// A non-finite latency was sent as null by the Kotlin bridge.
	var latency any = s.LatencyMs
	if math.IsNaN(s.LatencyMs) || math.IsInf(s.LatencyMs, 0) {
		latency = nil
	}
	return json.Marshal(struct {
		IsRunning      bool   `json:"isRunning"`
		LatencyMs      any    `json:"latencyMs"`
		IsDirect       bool   `json:"isDirect"`
		DERPRegionCode string `json:"derpRegionCode"`
		Endpoint       string `json:"endpoint"`
		Error          string `json:"error"`
	}{s.IsRunning, latency, s.IsDirect, s.DERPRegionCode, s.Endpoint, s.Error})
}

// uiPingTool runs one Tailscale ping session at a time through the running
// service. Each start takes a generation; a stop moves to the next one, so
// callbacks and sessions of an older ping are ignored (sessions are closed).
type uiPingTool struct {
	access     sync.Mutex
	state      uiPingState
	generation int64
	session    io.Closer
	last       string

	emit        func(payload string)
	startRemote func(host UIToolHost, endpoint, peerIP string, handler TailscalePingHandler) (io.Closer, error)
}

func newUIPingTool(emit func(string)) *uiPingTool {
	return &uiPingTool{
		emit: emit,
		startRemote: func(host UIToolHost, endpoint, peerIP string, handler TailscalePingHandler) (io.Closer, error) {
			client, err := host.CommandClient()
			if err != nil {
				return nil, err
			}
			session, err := client.StartTailscalePing(endpoint, peerIP, handler)
			if err != nil {
				return nil, err
			}
			return session, nil
		},
	}
}

func (t *uiPingTool) payloadLocked() string {
	content, _ := json.Marshal(t.state)
	return string(content)
}

func (t *uiPingTool) changedLocked() {
	payload := t.payloadLocked()
	if payload == t.last {
		return
	}
	t.last = payload
	if t.emit != nil {
		t.emit(payload)
	}
}

func (t *uiPingTool) payload() string {
	t.access.Lock()
	defer t.access.Unlock()
	return t.payloadLocked()
}

// start replaces any running ping (tools.tailscalePing stops first).
func (t *uiPingTool) start(endpoint, peerIP string, host UIToolHost) {
	t.access.Lock()
	t.state.IsRunning = true
	t.state.Error = ""
	t.changedLocked()
	t.generation++
	generation := t.generation
	previous := t.session
	t.session = nil
	t.access.Unlock()
	uiCloseQuietly(previous)
	handler := &uiPingHandler{tool: t, generation: generation}
	go func() {
		session, err := t.startRemote(host, endpoint, peerIP, handler)
		t.access.Lock()
		if err != nil {
			// Start failures were never reported, only stop the ping.
			if generation == t.generation && t.state.IsRunning {
				t.state.IsRunning = false
				t.changedLocked()
			}
			t.access.Unlock()
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

// stopWith ends the current ping; reset returns to the initial state.
func (t *uiPingTool) stopWith(reset bool) {
	t.access.Lock()
	t.generation++
	previous := t.session
	t.session = nil
	t.access.Unlock()
	uiCloseQuietly(previous)
	t.access.Lock()
	if reset {
		t.state = uiPingState{}
	} else {
		t.state.IsRunning = false
	}
	t.changedLocked()
	t.access.Unlock()
}

type uiPingHandler struct {
	tool       *uiPingTool
	generation int64
}

func (h *uiPingHandler) currentLocked() bool {
	return h.generation == h.tool.generation && h.tool.state.IsRunning
}

func (h *uiPingHandler) OnPingResult(result *TailscalePingResult) {
	if result == nil {
		return
	}
	t := h.tool
	t.access.Lock()
	defer t.access.Unlock()
	if !h.currentLocked() {
		return
	}
	if result.Error != "" {
		t.state.Error = result.Error
		t.changedLocked()
		return
	}
	t.state.LatencyMs = result.LatencyMs
	t.state.IsDirect = result.IsDirect
	t.state.DERPRegionCode = result.DERPRegionCode
	t.state.Endpoint = result.Endpoint
	t.state.Error = ""
	t.changedLocked()
}

func (h *uiPingHandler) OnError(message string) {
	t := h.tool
	t.access.Lock()
	defer t.access.Unlock()
	if !h.currentLocked() {
		return
	}
	t.state.IsRunning = false
	t.changedLocked()
	t.session = nil
}

var uiPing = newUIPingTool(func(payload string) { _ = uiEmitRaw("ping", payload) })

// UITailscalePingStart pings peerIP through endpoint, replacing any running ping.
func UITailscalePingStart(endpoint string, peerIP string, host UIToolHost) {
	uiPing.start(endpoint, peerIP, host)
}

// UITailscalePingStop stops the running ping.
func UITailscalePingStop() { uiPing.stopWith(false) }

// UITailscalePingReset stops the ping and returns to the initial state.
func UITailscalePingReset() { uiPing.stopWith(true) }

// UITailscalePingPayload answers the current "ping" event payload.
func UITailscalePingPayload() string { return uiPing.payload() }
