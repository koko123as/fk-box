package libbox

import (
	"encoding/json"
	"errors"
	"io"
	"sync"
	"testing"
	"time"
)

type uiSTUNTestRunner struct {
	handler   STUNTestHandler
	server    string
	cancelled bool
}

func (r *uiSTUNTestRunner) Start(server string, handler STUNTestHandler) {
	r.server = server
	r.handler = handler
}

func (r *uiSTUNTestRunner) Cancel() { r.cancelled = true }

type uiSTUNTestHost struct {
	access sync.Mutex
	errors []string
}

func (h *uiSTUNTestHost) CommandClient() (*CommandClient, error) { return nil, errors.New("unused") }

func (h *uiSTUNTestHost) ReportError(message string) {
	h.access.Lock()
	h.errors = append(h.errors, message)
	h.access.Unlock()
}

func (h *uiSTUNTestHost) reported() []string {
	h.access.Lock()
	defer h.access.Unlock()
	return append([]string(nil), h.errors...)
}

type uiSTUNTestCloser struct{ closed chan struct{} }

func (c *uiSTUNTestCloser) Close() error {
	close(c.closed)
	return nil
}

func uiSTUNTestTool() (*uiSTUNTool, *[]map[string]any, *[]*uiSTUNTestRunner) {
	sent := &[]map[string]any{}
	runners := &[]*uiSTUNTestRunner{}
	tool := newUISTUNTool(func(payload string) {
		var value map[string]any
		_ = json.Unmarshal([]byte(payload), &value)
		*sent = append(*sent, value)
	})
	tool.newStandalone = func() uiSTUNRunner {
		r := &uiSTUNTestRunner{}
		*runners = append(*runners, r)
		return r
	}
	return tool, sent, runners
}

func TestUIStunInitialPayload(t *testing.T) {
	tool, _, _ := uiSTUNTestTool()
	var value map[string]any
	if err := json.Unmarshal([]byte(tool.payload()), &value); err != nil {
		t.Fatal(err)
	}
	if value["phase"].(float64) != -1 || value["server"] != STUNDefaultServer || value["isRunning"] != false ||
		value["externalAddr"] != "" || value["latencyMs"].(float64) != 0 || value["natTypeSupported"] != false {
		t.Fatalf("initial payload %v", value)
	}
	if value["mapping"] != FormatNATMapping(0) || value["filtering"] != FormatNATFiltering(0) {
		t.Fatalf("NAT texts %v", value)
	}
	var diag map[string]any
	_ = json.Unmarshal([]byte(tool.diagnostics()), &diag)
	if _, ok := diag["mapping"]; ok || len(diag) != 6 {
		t.Fatalf("diagnostics %v", diag)
	}
}

func TestUIStunStandaloneProgressAndResult(t *testing.T) {
	tool, sent, runners := uiSTUNTestTool()
	host := &uiSTUNTestHost{}
	if err := tool.start(false, "stun.example:3478", "", host); err != nil {
		t.Fatal(err)
	}
	if err := tool.start(false, "x", "", host); err == nil || err.Error() != "A STUN test is already running" {
		t.Fatalf("second start: %v", err)
	}
	r := (*runners)[0]
	if r.server != "stun.example:3478" {
		t.Fatal(r.server)
	}
	r.handler.OnProgress(&STUNTestProgress{Phase: STUNPhaseNATMapping, ExternalAddr: "1.2.3.4:5", LatencyMs: 12, NATMapping: 1})
	r.handler.OnResult(&STUNTestResult{ExternalAddr: "1.2.3.4:5", LatencyMs: 13, NATMapping: 1, NATFiltering: 2, NATTypeSupported: true})
	last := (*sent)[len(*sent)-1]
	if last["phase"].(float64) != float64(STUNPhaseDone) || last["isRunning"] != false || last["latencyMs"].(float64) != 13 ||
		last["natTypeSupported"] != true || last["mapping"] != FormatNATMapping(1) || last["filtering"] != FormatNATFiltering(2) ||
		last["server"] != "stun.example:3478" {
		t.Fatalf("result payload %v", last)
	}
	// Late callbacks after the result are ignored.
	count := len(*sent)
	r.handler.OnError("late")
	if len(*sent) != count || len(host.reported()) != 0 {
		t.Fatal("late callback changed state")
	}
	// A new start resets the fields but keeps going.
	if err := tool.start(false, "stun.example:3478", "", host); err != nil {
		t.Fatal(err)
	}
	first := (*sent)[count]
	if first["phase"].(float64) != -1 || first["isRunning"] != true || first["externalAddr"] != "" {
		t.Fatalf("restart payload %v", first)
	}
}

func TestUIStunErrorAndStop(t *testing.T) {
	tool, sent, runners := uiSTUNTestTool()
	host := &uiSTUNTestHost{}
	_ = tool.start(false, "s", "", host)
	(*runners)[0].handler.OnError("boom")
	if got := host.reported(); len(got) != 1 || got[0] != "boom" {
		t.Fatalf("errors %v", got)
	}
	if (*sent)[len(*sent)-1]["isRunning"] != false {
		t.Fatal("still running after error")
	}
	_ = tool.start(false, "s", "", host)
	r := (*runners)[1]
	tool.stop()
	if !r.cancelled {
		t.Fatal("standalone not cancelled")
	}
	count := len(*sent)
	r.handler.OnError("context canceled")
	r.handler.OnProgress(&STUNTestProgress{Phase: 1})
	if len(*sent) != count || len(host.reported()) != 1 {
		t.Fatal("cancelled test still reported")
	}
	tool.stop()
	if len(*sent) != count {
		t.Fatal("identical state sent twice")
	}
}

func TestUIStunRemoteSessionLifecycle(t *testing.T) {
	tool, sent, _ := uiSTUNTestTool()
	host := &uiSTUNTestHost{}
	release := make(chan struct{})
	closer := &uiSTUNTestCloser{closed: make(chan struct{})}
	var gotOutbound string
	tool.startRemote = func(_ UIToolHost, server, outbound string, handler STUNTestHandler) (io.Closer, error) {
		gotOutbound = outbound
		<-release
		return closer, nil
	}
	_ = tool.start(true, "s", "proxy", host)
	// Cancelled while the session is opening: it is closed when it arrives.
	tool.stop()
	close(release)
	select {
	case <-closer.closed:
	case <-time.After(5 * time.Second):
		t.Fatal("stale session not closed")
	}
	if gotOutbound != "proxy" {
		t.Fatal(gotOutbound)
	}
	// A failed start reports its error once and stops running.
	tool.startRemote = func(UIToolHost, string, string, STUNTestHandler) (io.Closer, error) {
		return nil, errors.New("service unavailable")
	}
	_ = tool.start(true, "s", "", host)
	deadline := time.Now().Add(5 * time.Second)
	for len(host.reported()) == 0 && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	if got := host.reported(); len(got) != 1 || got[0] != "service unavailable" {
		t.Fatalf("errors %v", got)
	}
	tool.access.Lock()
	running := tool.state.IsRunning
	tool.access.Unlock()
	if running {
		t.Fatal("still running")
	}
	_ = sent
}

func TestUIStunReset(t *testing.T) {
	tool, sent, _ := uiSTUNTestTool()
	_ = tool.start(false, "custom", "", &uiSTUNTestHost{})
	tool.stopWith(true)
	last := (*sent)[len(*sent)-1]
	if last["server"] != STUNDefaultServer || last["phase"].(float64) != -1 || last["isRunning"] != false {
		t.Fatalf("reset payload %v", last)
	}
}
