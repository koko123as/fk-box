package plugin

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"net"
	"time"

	"github.com/sagernet/sing-box/adapter"
	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/service"
)

// Test fixtures only: a loopback echo server standing in for "the internet"
// and helpers to build a probe box. Nothing here is a proxy inbound.

// StartEchoServer listens on 127.0.0.1:0 and echoes every TCP stream.
func StartEchoServer() (net.Listener, error) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return nil, err
	}
	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			go func() {
				defer conn.Close()
				_, _ = io.Copy(conn, conn)
			}()
		}
	}()
	return listener, nil
}

// EchoRoundTrip writes payload on conn and expects it back.
func EchoRoundTrip(conn net.Conn, payload []byte) error {
	_ = conn.SetDeadline(time.Now().Add(15 * time.Second))
	if _, err := conn.Write(payload); err != nil {
		return E.Cause(err, "write")
	}
	got := make([]byte, len(payload))
	if _, err := io.ReadFull(conn, got); err != nil {
		return E.Cause(err, "read")
	}
	if !bytes.Equal(got, payload) {
		return E.New("echo mismatch")
	}
	return nil
}

// NewProbeContext builds a context with upstream registries plus the
// plugins, and the probe platform registered as adapter.PlatformInterface.
func NewProbeContext(ownRegistries bool) (context.Context, *ProbePlatform, error) {
	ctx := service.ContextWithDefaultRegistry(context.Background())
	if ownRegistries {
		ctx = NewContextOwnRegistries(ctx)
	} else {
		ctx = includeContext(ctx)
		if err := InjectIntoContext(ctx); err != nil {
			return nil, nil, err
		}
	}
	platform := new(ProbePlatform)
	service.MustRegister[adapter.PlatformInterface](ctx, platform)
	return ctx, platform, nil
}

// RouteThrough injects a connection into sing-box's router (no listener) and
// runs an echo round trip over it.
func RouteThrough(ctx context.Context, router adapter.Router, destination string, payload []byte) error {
	client, server := net.Pipe()
	defer client.Close()
	closed := make(chan error, 1)
	go router.RouteConnectionEx(ctx, server, adapter.InboundContext{
		Inbound:     "fk-probe",
		InboundType: "fk-probe",
		Network:     "tcp",
		Source:      M.ParseSocksaddr("172.19.0.1:40000"),
		Destination: M.ParseSocksaddr(destination),
	}, func(err error) { closed <- err })
	result := make(chan error, 1)
	go func() { result <- EchoRoundTrip(client, payload) }()
	select {
	case err := <-result:
		return err
	case err := <-closed:
		if err == nil {
			err = fmt.Errorf("closed")
		}
		return err
	case <-time.After(20 * time.Second):
		return fmt.Errorf("timeout")
	}
}
