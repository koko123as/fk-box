package libbox

import (
	"encoding/json"
	"errors"
	"io"
	"math"
	"sync"
	"testing"
	"time"
)

type uiPingTestHarness struct {
	access   sync.Mutex
	sent     []map[string]any
	handlers []TailscalePingHandler
	closers  []*uiSTUNTestCloser
}

func (h *uiPingTestHarness) last() map[string]any {
	h.access.Lock()
	defer h.access.Unlock()
	return h.sent[len(h.sent)-1]
}

func (h *uiPingTestHarness) handler(i int) TailscalePingHandler {
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		h.access.Lock()
		if len(h.handlers) > i {
			r := h.handlers[i]
			h.access.Unlock()
			return r
		}
		h.access.Unlock()
		time.Sleep(5 * time.Millisecond)
	}
	return nil
}

func uiPingTestTool() (*uiPingTool, *uiPingTestHarness) {
	h := &uiPingTestHarness{}
	tool := newUIPingTool(func(payload string) {
		var value map[string]any
		_ = json.Unmarshal([]byte(payload), &value)
		h.access.Lock()
		h.sent = append(h.sent, value)
		h.access.Unlock()
	})
	tool.startRemote = func(_ UIToolHost, endpoint, peerIP string, handler TailscalePingHandler) (io.Closer, error) {
		closer := &uiSTUNTestCloser{closed: make(chan struct{})}
		h.access.Lock()
		h.handlers = append(h.handlers, handler)
		h.closers = append(h.closers, closer)
		h.access.Unlock()
		return closer, nil
	}
	return tool, h
}

func TestUITailscalePingPayload(t *testing.T) {
	tool, _ := uiPingTestTool()
	var value map[string]any
	_ = json.Unmarshal([]byte(tool.payload()), &value)
	if len(value) != 6 || value["isRunning"] != false || value["latencyMs"].(float64) != 0 || value["error"] != "" {
		t.Fatalf("initial %v", value)
	}
	tool.state.LatencyMs = math.NaN()
	_ = json.Unmarshal([]byte(tool.payload()), &value)
	if value["latencyMs"] != nil {
		t.Fatalf("NaN latency %v", value)
	}
}

func TestUITailscalePingResults(t *testing.T) {
	tool, h := uiPingTestTool()
	tool.start("ts", "100.64.0.2", &uiSTUNTestHost{})
	handler := h.handler(0)
	handler.OnPingResult(&TailscalePingResult{LatencyMs: 12.5, IsDirect: true, Endpoint: "1.2.3.4:41641", DERPRegionCode: "sfo"})
	last := h.last()
	if last["isRunning"] != true || last["latencyMs"].(float64) != 12.5 || last["isDirect"] != true || last["endpoint"] != "1.2.3.4:41641" || last["derpRegionCode"] != "sfo" {
		t.Fatalf("result %v", last)
	}
	// An error result keeps the last values and shows the error.
	handler.OnPingResult(&TailscalePingResult{Error: "timeout", LatencyMs: 99})
	last = h.last()
	if last["error"] != "timeout" || last["latencyMs"].(float64) != 12.5 {
		t.Fatalf("error result %v", last)
	}
	// A new ping replaces the session and clears the error.
	tool.start("ts", "100.64.0.3", &uiSTUNTestHost{})
	select {
	case <-h.closers[0].closed:
	case <-time.After(5 * time.Second):
		t.Fatal("old session not closed")
	}
	if h.last()["error"] != "" {
		t.Fatal("error not cleared")
	}
	count := len(h.sent)
	handler.OnPingResult(&TailscalePingResult{LatencyMs: 1})
	if len(h.sent) != count {
		t.Fatal("stale result applied")
	}
	h.handler(1).OnError("stream closed")
	if h.last()["isRunning"] != false {
		t.Fatal("still running after error")
	}
}

func TestUITailscalePingStopAndFailure(t *testing.T) {
	tool, h := uiPingTestTool()
	tool.start("ts", "ip", &uiSTUNTestHost{})
	h.handler(0)
	tool.stopWith(false)
	select {
	case <-h.closers[0].closed:
	case <-time.After(5 * time.Second):
		t.Fatal("session not closed on stop")
	}
	if h.last()["isRunning"] != false {
		t.Fatal("running after stop")
	}
	host := &uiSTUNTestHost{}
	tool.startRemote = func(UIToolHost, string, string, TailscalePingHandler) (io.Closer, error) {
		return nil, errors.New("no service")
	}
	tool.start("ts", "ip", host)
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		if h.last()["isRunning"] == false {
			break
		}
		time.Sleep(5 * time.Millisecond)
	}
	if h.last()["isRunning"] != false || len(host.reported()) != 0 {
		t.Fatal("failed start must stop silently")
	}
	tool.stopWith(true)
	var value map[string]any
	_ = json.Unmarshal([]byte(tool.payload()), &value)
	if value["latencyMs"].(float64) != 0 || value["endpoint"] != "" {
		t.Fatalf("reset %v", value)
	}
}
