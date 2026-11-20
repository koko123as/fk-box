package libbox

import (
	"context"
	"errors"
	"os"
	"sync"
	"testing"
	"time"

	"github.com/sagernet/sing-box/daemon"
	"google.golang.org/grpc"
	"google.golang.org/grpc/metadata"
	"google.golang.org/protobuf/types/known/emptypb"
)

// fakeStream is a server stream that records what is sent to the client.
type fakeStream[T any] struct {
	ctx  context.Context
	sent chan *T
}

func newFakeStream[T any](ctx context.Context) *fakeStream[T] {
	return &fakeStream[T]{ctx: ctx, sent: make(chan *T, 64)}
}

func (s *fakeStream[T]) Send(message *T) error {
	s.sent <- message
	return nil
}
func (s *fakeStream[T]) Context() context.Context     { return s.ctx }
func (s *fakeStream[T]) SetHeader(metadata.MD) error  { return nil }
func (s *fakeStream[T]) SendHeader(metadata.MD) error { return nil }
func (s *fakeStream[T]) SetTrailer(metadata.MD)       {}
func (s *fakeStream[T]) SendMsg(any) error            { return nil }
func (s *fakeStream[T]) RecvMsg(any) error            { return nil }

var _ grpc.ServerStreamingServer[daemon.Groups] = (*fakeStream[daemon.Groups])(nil)

// setActiveCoreForTest installs running as the running Xray/mihomo core
// (nil: sing-box) and restores the previous one when the test ends.
func setActiveCoreForTest(t *testing.T, running *runningCore) {
	t.Helper()
	coreAccess.Lock()
	previous := activeCore
	activeCore = running
	coreAccess.Unlock()
	t.Cleanup(func() {
		coreAccess.Lock()
		activeCore = previous
		coreAccess.Unlock()
	})
}

func receive[T any](t *testing.T, stream *fakeStream[T], what string) *T {
	t.Helper()
	select {
	case message := <-stream.sent:
		return message
	case <-time.After(5 * time.Second):
		t.Fatalf("no %s sent", what)
		return nil
	}
}

func expectRunning(t *testing.T, result chan error, what string) {
	t.Helper()
	select {
	case err := <-result:
		t.Fatalf("%s ended: %v", what, err)
	case <-time.After(300 * time.Millisecond):
	}
}

// The reported bug: switching profiles while the service runs left the
// proxy page with "no proxy groups" and nodes that could not be selected.
// A reload stops the running core before the next one starts; in between
// the groups stream was served by sing-box, whose SubscribeGroups answers
// "invalid argument" while sing-box is idle, and the subscription ended for
// good (the app only subscribes again when the service status changes, and
// it stays started). The stream must survive the switch and serve the new
// core's groups.
func TestUICoreSwitchGroupsStreamFollowsReload(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	idle := daemon.NewStartedService(daemon.ServiceOptions{Context: ctx})
	service := newCoreStartedService(func() *daemon.StartedService { return idle })
	// mihomo with no groups loaded yet (its globals are empty in tests).
	setActiveCoreForTest(t, &runningCore{core: CoreMihomo})

	stream := newFakeStream[daemon.Groups](ctx)
	result := make(chan error, 1)
	go func() { result <- service.SubscribeGroups(&emptypb.Empty{}, stream) }()
	receive(t, stream, "first groups")

	// Reload: the old core stops, then the new one starts.
	beginCoreSwitch()
	coreAccess.Lock()
	activeCore = nil
	coreAccess.Unlock()
	notifyCoreChange()
	expectRunning(t, result, "groups stream during the switch")

	coreAccess.Lock()
	activeCore = &runningCore{core: CoreMihomo}
	coreAccess.Unlock()
	endCoreSwitch()
	receive(t, stream, "groups of the new core")
	expectRunning(t, result, "groups stream after the switch")

	cancel()
	select {
	case <-result:
	case <-time.After(5 * time.Second):
		t.Fatal("groups stream did not end with its client")
	}
}

// From Xray/mihomo to sing-box: the stream is moved to sing-box only once
// its start has returned, never while sing-box is still idle.
func TestUICoreSwitchConnectionsStreamToSingBox(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	idle := daemon.NewStartedService(daemon.ServiceOptions{Context: ctx})
	service := newCoreStartedService(func() *daemon.StartedService { return idle })
	setActiveCoreForTest(t, &runningCore{core: CoreXray, xrayTrack: newXrayTracker()})

	stream := newFakeStream[daemon.ConnectionEvents](ctx)
	result := make(chan error, 1)
	go func() {
		result <- service.SubscribeConnections(&daemon.SubscribeConnectionsRequest{Interval: int64(50 * time.Millisecond)}, stream)
	}()
	if first := receive(t, stream, "connection reset"); !first.Reset_ {
		t.Fatal("first connection events are not a reset")
	}

	beginCoreSwitch()
	coreAccess.Lock()
	activeCore = nil
	coreAccess.Unlock()
	notifyCoreChange()
	expectRunning(t, result, "connections stream during the switch")

	// The sing-box start failed (FATAL): the switch ends and the stream
	// reports sing-box's own answer instead of hanging.
	endCoreSwitch()
	select {
	case err := <-result:
		if err == nil {
			t.Fatal("connections stream ended without an error on a stopped sing-box")
		}
	case <-time.After(5 * time.Second):
		t.Fatal("connections stream did not end after a failed switch")
	}
}

// Without a switch in progress a sing-box stream that fails ends as before.
func TestUICoreSwitchStreamErrorWithoutSwitch(t *testing.T) {
	setActiveCoreForTest(t, nil)
	stream := newFakeStream[daemon.Groups](context.Background())
	want := errors.New("not started")
	err := switchStream(stream, func(grpc.ServerStreamingServer[daemon.Groups]) error {
		return want
	}, func(context.Context, *runningCore, func(*daemon.Groups) error) error {
		t.Error("other core served without one running")
		return nil
	})
	if err != want {
		t.Fatalf("switchStream: %v, want %v", err, want)
	}
}

// A sing-box stream that ends because sing-box is being reloaded (same core,
// new profile) is served again once the reload returns.
func TestUICoreSwitchStreamResumesAfterSingBoxReload(t *testing.T) {
	setActiveCoreForTest(t, nil)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	stream := newFakeStream[daemon.Groups](ctx)
	var access sync.Mutex
	calls := 0
	stopFirst := make(chan struct{})
	result := make(chan error, 1)
	go func() {
		result <- switchStream(stream, func(server grpc.ServerStreamingServer[daemon.Groups]) error {
			access.Lock()
			calls++
			call := calls
			access.Unlock()
			_ = server.Send(&daemon.Groups{Group: []*daemon.Group{{Tag: "proxy"}}})
			if call == 1 {
				select {
				case <-stopFirst:
					return os.ErrInvalid // the old sing-box instance is closing
				case <-server.Context().Done():
					return server.Context().Err()
				}
			}
			<-server.Context().Done()
			return server.Context().Err()
		}, func(context.Context, *runningCore, func(*daemon.Groups) error) error {
			t.Error("other core served without one running")
			return nil
		})
	}()
	receive(t, stream, "groups before the reload")

	beginCoreSwitch()
	close(stopFirst)
	expectRunning(t, result, "stream while sing-box reloads")
	endCoreSwitch()
	if groups := receive(t, stream, "groups after the reload"); len(groups.Group) != 1 {
		t.Fatalf("groups after the reload: %v", groups)
	}
	expectRunning(t, result, "stream after the reload")
	access.Lock()
	defer access.Unlock()
	if calls != 2 {
		t.Fatalf("sing-box stream served %d times, want 2", calls)
	}
}
