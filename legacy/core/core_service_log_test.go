package libbox

import (
	"context"
	"testing"

	"github.com/sagernet/sing-box/daemon"
	"github.com/sagernet/sing-box/log"
)

// The service log stream: saved lines with a reset first, then new lines,
// a reset after Clear, and a full resend for a reader a ring behind.
func TestUICoreServiceLogStream(t *testing.T) {
	logs := &coreServiceLog{subscribers: make(map[*coreLogSubscriber]struct{})}
	var copied []string
	logs.copyTo = func(_ log.Level, message string) { copied = append(copied, message) }
	logs.WriteMessage(log.LevelInfo, "saved")

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	stream := newFakeStream[daemon.Log](ctx)
	result := make(chan error, 1)
	go func() { result <- logs.Serve(stream) }()

	first := receive(t, stream, "saved lines")
	if !first.Reset_ || len(first.Messages) != 1 || first.Messages[0].Message != "saved" {
		t.Fatalf("first message = %+v", first)
	}
	logs.WriteMessage(log.LevelWarn, "new")
	next := receive(t, stream, "new line")
	if next.Reset_ || len(next.Messages) != 1 || next.Messages[0].Message != "new" || next.Messages[0].Level != daemon.LogLevel(log.LevelWarn) {
		t.Fatalf("new line = %+v", next)
	}
	logs.Clear()
	cleared := receive(t, stream, "reset after clear")
	if !cleared.Reset_ || len(cleared.Messages) != 0 {
		t.Fatalf("after clear = %+v", cleared)
	}
	if len(copied) != 2 {
		t.Fatalf("copied %v, want both lines", copied)
	}
	cancel()
	if err := <-result; err == nil {
		t.Fatal("stream did not end with its client")
	}
}

func TestUICoreServiceLogRing(t *testing.T) {
	saved := sLogMaxLines
	sLogMaxLines = 3
	defer func() { sLogMaxLines = saved }()
	logs := &coreServiceLog{subscribers: make(map[*coreLogSubscriber]struct{})}
	for _, message := range []string{"1", "2", "3", "4", "5"} {
		logs.WriteMessage(log.LevelInfo, message)
	}
	got := logs.savedLocked()
	if len(got) != 3 || got[0].message != "3" || got[2].message != "5" {
		t.Fatalf("ring = %+v", got)
	}
}

// Every sing-box-only method reaches the sing-box service: with none
// running, the idle service answers as libbox's idle service did.
func TestUICoreCommandServiceForwardsToSingBox(t *testing.T) {
	idle := daemon.NewStartedService(daemon.ServiceOptions{Context: context.Background()})
	service := newCoreStartedService(func() *daemon.StartedService { return idle })
	if _, err := service.SetTailscaleExitNode(context.Background(), &daemon.SetTailscaleExitNodeRequest{}); err == nil {
		t.Fatal("idle service accepted a Tailscale action")
	}
	version, err := service.GetVersion(context.Background(), nil)
	if err != nil || version.ApiVersion != daemon.APIVersion {
		t.Fatalf("GetVersion = %v, %v", version, err)
	}
}
