package libbox

// fk-box's command service: the StartedService gRPC service the app's
// CommandClient (libbox, local socket) and remote controllers read the core
// through, on the same socket and with the same interceptors as libbox's
// CommandServer.Start, which serves libbox's own daemon.StartedService and
// is not used. The app starts it with StartCoreCommandServer and closes the
// server with CloseCoreCommandServer.
//
// coreStartedService (core_api.go, core_extra.go) answers every method: from
// Xray or mihomo while one of them runs, from the running sing-box's
// attached service otherwise (core_singbox_host.go), and the service-wide
// ones (version, log) itself.

import (
	"context"
	"encoding/json"
	"net"
	"os"
	"path/filepath"
	"sort"
	"strconv"

	"github.com/sagernet/sing-box/common/trafficcontrol"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/daemon"
	"github.com/sagernet/sing-box/experimental/fkhost"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/service/oomkiller"
	"github.com/sagernet/sing/common/byteformats"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/service"
	"google.golang.org/grpc"
	"google.golang.org/grpc/health"
	"google.golang.org/grpc/health/grpc_health_v1"
	"google.golang.org/protobuf/types/known/emptypb"
)

// StartCoreCommandServer listens on the command socket (or the configured
// local port) and serves fk-box's command service and libbox's managed
// service (stop, reload, system proxy, debug crash, OOM report).
func (s *CommandServer) StartCoreCommandServer() error {
	if s.listener != nil {
		return E.New("command server already started")
	}
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
	// log keeps a copy for the OOM and power reports and the debug output.
	if started := s.StartedService; started != nil {
		coreLog.setCopy(started.WriteMessage)
	}
	s.useCoreOOMRecorder()
	// A new server starts idle, as libbox's service did (a FATAL status of
	// the previous service does not carry over).
	if !coreKernelRunning() {
		coreStatus.set(daemon.ServiceStatus_IDLE)
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
	coreLog.setCopy(nil)
	s.Close()
}

// useCoreOOMRecorder replaces the OOM recorder NewCommandServer created with
// one whose reports list the connections of fk-box's running sing-box:
// libbox's recorder reads them from libbox's own service, which runs nothing
// now. Everything else in the report (memory profiles, runtime info, config
// snapshot, the log) is libbox's. Called before anything runs: the old
// recorder has no draft yet, and sing-box's OOM killer service reads the new
// one from the context when a box is created.
func (s *CommandServer) useCoreOOMRecorder() {
	options := OOMRecorderOptions(s.StartedService)
	snapshot := options.SnapshotCallback
	options.SnapshotCallback = func(directory string, prefix string) {
		snapshot(directory, prefix)
		if content := coreOOMConnectionsContent(); content != nil {
			writeReportFile(directory, prefix+".connections.json", content)
		}
	}
	recorder := oomkiller.NewRecorder(options)
	if s.oomRecorder != nil {
		_ = s.oomRecorder.Close()
	}
	service.MustRegister[*oomkiller.Recorder](s.ctx, recorder)
	recorder.Start()
	s.oomRecorder = recorder
	s.managedService = daemon.NewManagedService(daemon.ManagedServiceOptions{
		Handler:     (*platformHandler)(s),
		Debug:       sDebug,
		OOMRecorder: recorder,
	})
}

// coreOOMConnectionsContent is libbox's OOM connection list
// (oomConnectionsContent) for the running sing-box, nil when none runs.
func coreOOMConnectionsContent() []byte {
	running := runningSingBox()
	if running == nil {
		return nil
	}
	trafficManager := service.PtrFromContext[trafficcontrol.Manager](running.Context())
	if trafficManager == nil {
		return nil
	}
	connections := trafficManager.Connections()
	sort.Slice(connections, func(i, j int) bool {
		return connections[i].CreatedAt.Before(connections[j].CreatedAt)
	})
	uploadTotal, downloadTotal := trafficManager.Total()
	data, err := json.MarshalIndent(oomConnectionsInfo{
		UploadTotal:       byteformats.FormatBytes(uint64(uploadTotal)),
		DownloadTotal:     byteformats.FormatBytes(uint64(downloadTotal)),
		Connections:       buildOOMConnections(connections),
		ClosedConnections: buildOOMConnections(trafficManager.ClosedConnections()),
	}, "", "  ")
	if err != nil {
		return nil
	}
	return data
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
