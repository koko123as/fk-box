package fk

// Runs the official kernels as fk-core runs them. Set FKBOX_KERNEL_DIR to a
// directory holding libmihomo.so and libxray.so built for this machine (the
// upstream linux builds); the test is skipped otherwise. The TUN is a
// socket pair: mihomo's gVisor stack reads it like a TUN that stays silent.

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"syscall"
	"testing"
	"time"

	daemonpb "github.com/koko123as/fk-box/core/internal/daemonpb"
	"github.com/stretchr/testify/require"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/protobuf/types/known/emptypb"
)

type testTunHost struct {
	calls []string
}

func (h *testTunHost) Call(method string, args ...any) (json.RawMessage, error) {
	h.calls = append(h.calls, method)
	return json.RawMessage("null"), nil
}

func (h *testTunHost) Emit(string, string) {}

func (h *testTunHost) CallFile(method string, args ...any) (json.RawMessage, *os.File, error) {
	h.calls = append(h.calls, method)
	pair, err := syscall.Socketpair(syscall.AF_UNIX, syscall.SOCK_SEQPACKET, 0)
	if err != nil {
		return nil, nil, err
	}
	go func() {
		// Keep the peer open for the life of the test process.
		buffer := make([]byte, 65536)
		for {
			if _, err := syscall.Read(pair[1], buffer); err != nil {
				return
			}
		}
	}()
	return json.RawMessage("null"), os.NewFile(uintptr(pair[0]), "tun"), nil
}

func setupKernelTest(t *testing.T) string {
	dir := os.Getenv("FKBOX_KERNEL_DIR")
	if dir == "" {
		t.Skip("FKBOX_KERNEL_DIR not set")
	}
	files := t.TempDir()
	pathsAccess.RLock()
	previousPaths := [4]string{libraryDir, filesDir, workingDir, tempDir}
	pathsAccess.RUnlock()
	previousHost, _ := currentHost()
	SetPaths(dir, files, t.TempDir(), t.TempDir())
	SetHost(&testTunHost{})
	t.Cleanup(func() {
		_ = ServiceStop()
		CloseCommandServer()
		SetHost(previousHost)
		SetPaths(previousPaths[0], previousPaths[1], previousPaths[2], previousPaths[3])
	})
	return files
}

func TestServiceMihomoChild(t *testing.T) {
	files := setupKernelTest(t)
	profile := `
mode: rule
proxies:
  - {name: a, type: socks5, server: 127.0.0.1, port: 1}
  - {name: b, type: socks5, server: 127.0.0.1, port: 2}
proxy-groups:
  - {name: PROXY, type: select, proxies: [a, b]}
rules:
  - MATCH,PROXY
`
	result, err := ServiceStart(CoreMihomo, profile, "", `{"dns":["223.5.5.5"]}`)
	require.NoError(t, err)
	require.NotNil(t, result)
	require.Equal(t, CoreMihomo, ServiceRunning())
	require.NotZero(t, ServiceMemory())
	require.True(t, strings.HasPrefix(ServiceLocalProxy(), "http://fkbox-return:"))

	conn, err := grpc.NewClient("unix://"+filepath.Join(files, "command.sock"), grpc.WithTransportCredentials(insecure.NewCredentials()))
	require.NoError(t, err)
	defer conn.Close()
	client := daemonpb.NewStartedServiceClient(conn)
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()

	groupsStream, err := client.SubscribeGroups(ctx, &emptypb.Empty{})
	require.NoError(t, err)
	groups, err := groupsStream.Recv()
	require.NoError(t, err)
	var proxy *daemonpb.Group
	for _, group := range groups.Group {
		if group.Tag == "PROXY" {
			proxy = group
		}
	}
	require.NotNil(t, proxy, "groups: %v", groups)
	require.Equal(t, "a", proxy.Selected)
	require.True(t, proxy.Selectable)

	_, err = client.SelectOutbound(ctx, &daemonpb.SelectOutboundRequest{GroupTag: "PROXY", OutboundTag: "b"})
	require.NoError(t, err)
	groups, err = groupsStream.Recv()
	require.NoError(t, err)
	for _, group := range groups.Group {
		if group.Tag == "PROXY" {
			require.Equal(t, "b", group.Selected)
		}
	}

	mode, err := client.GetClashModeStatus(ctx, &emptypb.Empty{})
	require.NoError(t, err)
	require.Equal(t, "rule", mode.CurrentMode)

	statusStream, err := client.SubscribeStatus(ctx, &daemonpb.SubscribeStatusRequest{Interval: int64(200 * time.Millisecond)})
	require.NoError(t, err)
	status, err := statusStream.Recv()
	require.NoError(t, err)
	require.NotZero(t, status.Memory)
	require.True(t, status.TrafficAvailable)

	connections, err := client.SubscribeConnections(ctx, &daemonpb.SubscribeConnectionsRequest{})
	require.NoError(t, err)
	first, err := connections.Recv()
	require.NoError(t, err)
	require.True(t, first.Reset_)

	serviceStatus, err := client.SubscribeServiceStatus(ctx, &emptypb.Empty{})
	require.NoError(t, err)
	current, err := serviceStatus.Recv()
	require.NoError(t, err)
	require.Equal(t, daemonpb.ServiceStatus_STARTED, current.Status)

	// A reload replaces the process.
	_, err = ServiceStart(CoreMihomo, profile, "", `{"dns":["223.5.5.5"]}`)
	require.NoError(t, err)
	require.NoError(t, ServiceDefaultNetworkChanged(`["1.1.1.1"]`))
	require.NoError(t, ServiceStop())
	require.Equal(t, "", ServiceRunning())
}

func TestServiceMihomoBadProfile(t *testing.T) {
	setupKernelTest(t)
	_, err := ServiceStart(CoreMihomo, "proxies: [", "", "")
	require.Error(t, err)
	require.Equal(t, "", ServiceRunning())
}

func TestXrayConfigChecksWithKernel(t *testing.T) {
	setupKernelTest(t)
	running := &serviceKernel{
		core:          CoreXray,
		dir:           t.TempDir(),
		localPort:     10808,
		localPassword: "secret",
		content: `{
  "outbounds": [
    {"tag": "proxy-a", "protocol": "socks", "settings": {"servers": [{"address": "127.0.0.1", "port": 1}]}},
    {"tag": "proxy-b", "protocol": "socks", "settings": {"servers": [{"address": "127.0.0.1", "port": 2}]}},
    {"tag": "direct", "protocol": "freedom"}
  ],
  "routing": {
    "balancers": [{"tag": "auto", "selector": ["proxy-"]}],
    "rules": [{"network": "tcp,udp", "balancerTag": "auto"}]
  }
}`,
	}
	require.NoError(t, writeXrayConfig(running))
	require.Equal(t, []string{"proxy-a", "proxy-b"}, running.layout.balancers[0].members)
	require.Len(t, running.layout.testUsers, 3)
	// Xray's own check accepts the generated configuration (its linux build
	// cannot open the TUN, so only -test runs here).
	generated, err := os.ReadFile(running.config)
	require.NoError(t, err)
	require.NoError(t, checkXrayConfig(string(generated)))
}
