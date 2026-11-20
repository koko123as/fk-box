package libbox

import (
	"encoding/json"
	"errors"
	"io"
	"testing"
	"time"
)

type uiQualityTestRunner struct {
	handler   NetworkQualityTestHandler
	args      []any
	cancelled bool
}

func (r *uiQualityTestRunner) Start(configURL string, serial bool, maxRuntimeSeconds int32, http3 bool, handler NetworkQualityTestHandler) {
	r.args = []any{configURL, serial, maxRuntimeSeconds, http3}
	r.handler = handler
}

func (r *uiQualityTestRunner) Cancel() { r.cancelled = true }

func uiQualityTestTool() (*uiQualityTool, *[]map[string]any, *[]*uiQualityTestRunner) {
	sent := &[]map[string]any{}
	runners := &[]*uiQualityTestRunner{}
	tool := newUIQualityTool(func(payload string) {
		var value map[string]any
		_ = json.Unmarshal([]byte(payload), &value)
		*sent = append(*sent, value)
	})
	tool.newStandalone = func() uiQualityRunner {
		r := &uiQualityTestRunner{}
		*runners = append(*runners, r)
		return r
	}
	return tool, sent, runners
}

func TestUIQualityInitialPayload(t *testing.T) {
	tool, _, _ := uiQualityTestTool()
	var value map[string]any
	if err := json.Unmarshal([]byte(tool.payload()), &value); err != nil {
		t.Fatal(err)
	}
	if len(value) != 11 || value["phase"].(float64) != -1 || value["configURL"] != NetworkQualityDefaultConfigURL ||
		value["isRunning"] != false || value["serial"] != false || value["http3"] != false || value["showMeteredWarning"] != false {
		t.Fatalf("initial payload %v", value)
	}
}

func TestUIQualityStandaloneRun(t *testing.T) {
	tool, sent, runners := uiQualityTestTool()
	host := &uiSTUNTestHost{}
	if err := tool.request(false, false, "https://q.example/config", "", true, true, 60, host); err != nil {
		t.Fatal(err)
	}
	if err := tool.request(false, false, "x", "", false, false, 30, host); err == nil || err.Error() != "A network quality test is already running" {
		t.Fatalf("second start: %v", err)
	}
	r := (*runners)[0]
	if r.args[0] != "https://q.example/config" || r.args[1] != true || r.args[2] != int32(60) || r.args[3] != true {
		t.Fatalf("runner args %v", r.args)
	}
	r.handler.OnProgress(&NetworkQualityProgress{Phase: 1, DownloadCapacity: 1000, IdleLatencyMs: 9})
	r.handler.OnResult(&NetworkQualityResult{DownloadCapacity: 2000, UploadCapacity: 300, DownloadRPM: 7, UploadRPM: 8, IdleLatencyMs: 10})
	last := (*sent)[len(*sent)-1]
	if last["phase"].(float64) != float64(NetworkQualityPhaseDone) || last["isRunning"] != false || last["downloadCapacity"].(float64) != 2000 ||
		last["uploadCapacity"].(float64) != 300 || last["downloadRPM"].(float64) != 7 || last["uploadRPM"].(float64) != 8 ||
		last["idleLatencyMs"].(float64) != 10 || last["serial"] != true || last["http3"] != true {
		t.Fatalf("result payload %v", last)
	}
	// Other runtimes fall back to 30 s.
	_ = tool.request(false, false, "u", "", false, false, 45, host)
	if (*runners)[1].args[2] != int32(30) {
		t.Fatal((*runners)[1].args)
	}
}

func TestUIQualityMeteredWarning(t *testing.T) {
	tool, sent, runners := uiQualityTestTool()
	host := &uiSTUNTestHost{}
	_ = tool.request(false, true, "u", "", false, false, 30, host)
	last := (*sent)[len(*sent)-1]
	if last["showMeteredWarning"] != true || last["isRunning"] != false || len(*runners) != 0 {
		t.Fatalf("metered payload %v", last)
	}
	tool.dismiss()
	if (*sent)[len(*sent)-1]["showMeteredWarning"] != false || len(*runners) != 0 {
		t.Fatal("dismiss")
	}
	_ = tool.request(false, true, "u", "", false, false, 30, host)
	tool.confirm(false, host)
	last = (*sent)[len(*sent)-1]
	if last["showMeteredWarning"] != false || last["isRunning"] != true || len(*runners) != 1 {
		t.Fatalf("confirm payload %v", last)
	}
}

func TestUIQualityErrorStopAndReset(t *testing.T) {
	tool, sent, runners := uiQualityTestTool()
	host := &uiSTUNTestHost{}
	_ = tool.request(false, false, "u", "", false, false, 30, host)
	(*runners)[0].handler.OnError("boom")
	if got := host.reported(); len(got) != 1 || got[0] != "boom" {
		t.Fatalf("errors %v", got)
	}
	_ = tool.request(false, false, "u", "", false, false, 30, host)
	r := (*runners)[1]
	tool.stopWith(false, false)
	if !r.cancelled {
		t.Fatal("not cancelled")
	}
	count := len(*sent)
	r.handler.OnError("context canceled")
	r.handler.OnProgress(&NetworkQualityProgress{Phase: 2})
	if len(*sent) != count || len(host.reported()) != 1 {
		t.Fatal("cancelled test still reported")
	}
	_ = tool.request(false, false, "custom", "", true, false, 30, host)
	tool.stopWith(false, true)
	last := (*sent)[len(*sent)-1]
	if last["configURL"] != NetworkQualityDefaultConfigURL || last["serial"] != false || last["isRunning"] != false {
		t.Fatalf("reset payload %v", last)
	}
}

func TestUIQualityRemoteSession(t *testing.T) {
	tool, _, _ := uiQualityTestTool()
	host := &uiSTUNTestHost{}
	release := make(chan struct{})
	closer := &uiSTUNTestCloser{closed: make(chan struct{})}
	var got uiQualityState
	tool.startRemote = func(_ UIToolHost, state uiQualityState, _ NetworkQualityTestHandler) (io.Closer, error) {
		got = state
		<-release
		return closer, nil
	}
	_ = tool.request(true, false, "u", "proxy", false, true, 60, host)
	tool.stopWith(true, false)
	close(release)
	select {
	case <-closer.closed:
	case <-time.After(5 * time.Second):
		t.Fatal("stale session not closed")
	}
	if got.outbound != "proxy" || got.maxRuntime != 60 || !got.HTTP3 {
		t.Fatalf("remote args %+v", got)
	}
	tool.startRemote = func(UIToolHost, uiQualityState, NetworkQualityTestHandler) (io.Closer, error) {
		return nil, errors.New("service unavailable")
	}
	_ = tool.request(true, false, "u", "", false, false, 30, host)
	deadline := time.Now().Add(5 * time.Second)
	for len(host.reported()) == 0 && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	if r := host.reported(); len(r) != 1 || r[0] != "service unavailable" {
		t.Fatalf("errors %v", r)
	}
}
