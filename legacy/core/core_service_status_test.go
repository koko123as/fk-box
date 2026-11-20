package libbox

import (
	"context"
	"errors"
	"testing"

	"github.com/sagernet/sing-box/daemon"
)

// A switch reports STARTING, then STARTED or FATAL from its last start; a
// stop after a failed start keeps FATAL, a stop of a running service goes
// through STOPPING to IDLE.
func TestUICoreServiceStatusTransitions(t *testing.T) {
	status := newCoreServiceStatus()
	expect := func(want daemon.ServiceStatus_Type, what string) *daemon.ServiceStatus {
		t.Helper()
		current, _ := status.load()
		if current.Status != want {
			t.Fatalf("%s: status %v, want %v", what, current.Status, want)
		}
		return current
	}
	expect(daemon.ServiceStatus_IDLE, "initial")

	status.switchStarted()
	expect(daemon.ServiceStatus_STARTING, "switch started")
	status.startFinished(errors.New("bad profile"))
	status.switchFinished(false)
	if current := expect(daemon.ServiceStatus_FATAL, "failed start"); current.ErrorMessage != "bad profile" {
		t.Fatalf("error message %q", current.ErrorMessage)
	}
	if status.stopping() {
		t.Fatal("a stop after a failed start replaced FATAL")
	}
	expect(daemon.ServiceStatus_FATAL, "stop after failure")

	// The routing fallback: the first start fails, the second runs.
	status.switchStarted()
	status.startFinished(errors.New("rules"))
	status.startFinished(nil)
	status.switchFinished(true)
	expect(daemon.ServiceStatus_STARTED, "fallback start")

	if !status.stopping() {
		t.Fatal("a stop of a running service did not report STOPPING")
	}
	expect(daemon.ServiceStatus_STOPPING, "stopping")
	status.set(daemon.ServiceStatus_IDLE)
	expect(daemon.ServiceStatus_IDLE, "stopped")

	// A start a stop interrupted returns no error and leaves nothing running.
	status.switchStarted()
	status.startFinished(nil)
	status.switchFinished(false)
	expect(daemon.ServiceStatus_IDLE, "interrupted start")

	status.fatal("android: close service: failed")
	expect(daemon.ServiceStatus_FATAL, "app error")
}

// The stream sends the current status, then each change.
func TestUICoreServiceStatusStream(t *testing.T) {
	status := newCoreServiceStatus()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	stream := newFakeStream[daemon.ServiceStatus](ctx)
	result := make(chan error, 1)
	go func() { result <- status.Serve(stream) }()
	if first := receive(t, stream, "current status"); first.Status != daemon.ServiceStatus_IDLE {
		t.Fatalf("first status %v", first.Status)
	}
	status.switchStarted()
	if next := receive(t, stream, "STARTING"); next.Status != daemon.ServiceStatus_STARTING {
		t.Fatalf("next status %v", next.Status)
	}
	status.switchFinished(true)
	if next := receive(t, stream, "STARTED"); next.Status != daemon.ServiceStatus_STARTED {
		t.Fatalf("next status %v", next.Status)
	}
	cancel()
	if err := <-result; err == nil {
		t.Fatal("stream did not end with its client")
	}
}

// A stop interrupts the sing-box start in progress: its context is
// cancelled and the start learns it was interrupted.
func TestUICoreSingBoxStartInterrupted(t *testing.T) {
	ctx, start := beginSingBoxStart(context.Background())
	interruptSingBoxStart()
	if ctx.Err() == nil {
		t.Fatal("the start's context was not cancelled")
	}
	if !endSingBoxStart(start) {
		t.Fatal("the start was not told it was interrupted")
	}
	ctx, start = beginSingBoxStart(context.Background())
	if endSingBoxStart(start) {
		t.Fatal("an uninterrupted start was reported interrupted")
	}
	// Once a start ended, a stop leaves its (now running) box's context alone.
	interruptSingBoxStart()
	if ctx.Err() != nil {
		t.Fatal("a finished start's context was cancelled")
	}
	start.cancel()
}

// With no sing-box running the device and network calls do nothing and the
// OOM report has no connection list.
func TestUICoreDeviceCallsWithoutSingBox(t *testing.T) {
	server := &CommandServer{}
	server.WakeNowCore()
	server.ResetNetworkCore()
	server.UpdateWIFIStateCore()
	if server.NeedWIFIStateCore() || server.NeedFindProcessCore() {
		t.Fatal("no sing-box runs, yet it needs the Wi-Fi state or process search")
	}
	if coreOOMConnectionsContent() != nil {
		t.Fatal("OOM connection list without a running sing-box")
	}
}
