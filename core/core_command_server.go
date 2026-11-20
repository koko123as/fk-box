package libbox

// fk-box's command service: the StartedService gRPC service the app's
// CommandClient (libbox, local socket) and remote controllers read the core
// through. It replaces libbox's CommandServer.Start, which served libbox's
// own daemon.StartedService (and needed tools/patch_multicore.py to put
// coreStartedService in front of it). The app starts it with
// StartCoreCommandServer instead of CommandServer.start and closes the
// server with CloseCoreCommandServer.
//
// coreStartedService (core_api.go, core_extra.go) answers every method: from
// Xray or mihomo while one of them runs, from the running sing-box's
// attached service otherwise (core_singbox_host.go), and the service-wide
// ones (version, log) itself.

import (
	"context"
	"net"
	"os"
	"path/filepath"
	"strconv"

	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/daemon"
	"github.com/sagernet/sing-box/experimental/fkhost"
	"github.com/sagernet/sing-box/log"
	E "github.com/sagernet/sing/common/exceptions"
	"google.golang.org/grpc"
	"google.golang.org/grpc/health"
	"google.golang.org/grpc/health/grpc_health_v1"
	"google.golang.org/protobuf/types/known/emptypb"
)

// StartCoreCommandServer listens on the command socket (or the configured
// local port) and serves fk-box's command service and libbox's managed
// service (stop, reload, system proxy, debug crash, OOM report).
func (s *CommandServer) StartCoreCommandServer() error {
	var (
		listener net.Listener
		err      error
	)
	if sCommandServerListenPort == 0 {
		sockPath := filepath.Join(sBasePath, "command.sock")
		os.Remove(sockPath)
		listener, err = net.ListenUnix("unix", &net.UnixAddr{Name: sockPath, Net: "unix"})
		if err != nil {
			return E.Cause(err, "listen command server")
		}
		if sUserID != os.Getuid() {
			if err = os.Chown(sockPath, sUserID, sGroupID); err != nil {
				listener.Close()
				os.Remove(sockPath)
				return E.Cause(err, "chown")
			}
		}
	} else {
		listener, err = net.Listen("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(int(sCommandServerListenPort))))
		if err != nil {
			return E.Cause(err, "listen command server")
		}
	}
	// Every fk-box line goes to the service log; libbox's StartedService
	// log keeps a copy for libbox's OOM report and debug output.
	if started := s.StartedService; started != nil {
		coreLog.copyTo = started.WriteMessage
	}
	fkhost.Log.SetForward(func(entry fkhost.Entry) { coreLog.WriteMessage(coreLogLevel(entry.Level), entry.Message) })
	fkhost.Log.SetDiagnostic(func(message string) { log.Warn(message) })

	s.listener = listener
	s.grpcServer = grpc.NewServer(
		grpc.ChainUnaryInterceptor(unaryAuthInterceptor, daemon.UnaryLocaleInterceptor),
		grpc.ChainStreamInterceptor(streamAuthInterceptor, daemon.StreamLocaleInterceptor),
	)
	daemon.RegisterStartedServiceServer(s.grpcServer, newCoreStartedService(singBoxService))
	daemon.RegisterManagedServiceServer(s.grpcServer, s.managedService)
	healthServer := health.NewServer()
	healthServer.SetServingStatus(daemon.StartedService_ServiceDesc.ServiceName, grpc_health_v1.HealthCheckResponse_SERVING)
	healthServer.SetServingStatus(daemon.ManagedService_ServiceDesc.ServiceName, grpc_health_v1.HealthCheckResponse_SERVING)
	grpc_health_v1.RegisterHealthServer(s.grpcServer, healthServer)
	go s.grpcServer.Serve(listener)
	return nil
}

// CloseCoreCommandServer stops whichever core still runs (with it the HTTP
// capture and rewrite), then the server (CommandServer.Close).
func (s *CommandServer) CloseCoreCommandServer() {
	_ = s.CloseCoreService()
	s.Close()
}

// newCoreStartedService returns the command service; singBox gives the
// service sing-box calls go to.
func newCoreStartedService(singBox func() *daemon.StartedService) *coreStartedService {
	return &coreStartedService{singBox: singBox}
}

func (s *coreStartedService) GetVersion(context.Context, *emptypb.Empty) (*daemon.Version, error) {
	return &daemon.Version{Version: C.Version, ApiVersion: daemon.APIVersion}, nil
}

// SubscribeLog streams the service log of every core.
func (s *coreStartedService) SubscribeLog(_ *emptypb.Empty, server grpc.ServerStreamingServer[daemon.Log]) error {
	return coreLog.Serve(server)
}

func (s *coreStartedService) ClearLogs(context.Context, *emptypb.Empty) (*emptypb.Empty, error) {
	coreLog.Clear()
	return &emptypb.Empty{}, nil
}

// SubscribeNotifications: Android shows sing-box's notifications through
// the platform interface directly; the stream is the sing-box service's.
func (s *coreStartedService) SubscribeNotifications(empty *emptypb.Empty, server grpc.ServerStreamingServer[daemon.NotificationEvent]) error {
	return s.singBox().SubscribeNotifications(empty, server)
}
