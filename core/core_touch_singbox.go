package libbox

// Every sing-box and libbox symbol fk-box's Go code uses, in one place. When
// an upgrade renames, removes or changes one of them, the build fails here
// first, with the symbol's name in the error; fix the line and the code that
// uses it (see "升级内核" in libbox.md). tools/verify_touchpoints.py checks
// that every package-qualified sing-box symbol fk-box uses is listed below
// (and, with the core checked out, every libbox one).
//
// Nothing here runs: the declarations are blank and the function is never
// called. Signatures are spelled out for the libbox internals the host
// services (core_host.go) and the kernels' start rely on; the rest assert
// that the symbol exists with the same kind.

import (
	"context"
	"net"
	"net/netip"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/certificate"
	"github.com/sagernet/sing-box/common/networkquality"
	"github.com/sagernet/sing-box/common/sniff"
	"github.com/sagernet/sing-box/common/stun"
	"github.com/sagernet/sing-box/common/urltest"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/daemon"
	"github.com/sagernet/sing-box/experimental/deprecated"
	"github.com/sagernet/sing-box/experimental/locale"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/schema"
	"github.com/sagernet/sing-box/service/oomkiller"
	"github.com/sagernet/sing-box/service/powerreport"
	tun "github.com/sagernet/sing-tun"
	"google.golang.org/grpc"
)

// ---- libbox (experimental/libbox): host services and shared helpers ----

var (
	_ func(*CommandServer)                                                                   = (*CommandServer).Close
	_ func(PlatformInterface, TunOptions) (int32, error)                                     = PlatformInterface.OpenTun
	_ func(PlatformInterface, string)                                                        = PlatformInterface.RegisterMyInterface
	_ func(PlatformInterface, int32) error                                                   = PlatformInterface.AutoDetectInterfaceControl
	_ func(PlatformInterface, int32, string, int32, string, int32) (*ConnectionOwner, error) = PlatformInterface.FindConnectionOwner

	_ func(int) (int, error)      = dup
	_ func(int32) (string, error) = getTunnelName
	_                             = iteratorToArray[string]

	_ func(string) error                                    = CheckConfig
	_ func(string) (*StringBox, error)                      = FormatConfig
	_ func(string) *StringBox                               = wrapString
	_ func(PlatformInterface) context.Context               = baseContext
	_ func(context.Context, string) (option.Options, error) = parseConfig
	_ func() string                                         = Version
	_ string                                                = sWorkingPath

	// sing-box's start (core_singbox_host.go) and the command service
	// (core_command_server.go): what libbox's StartOrReloadService and
	// CommandServer.Start used.
	_ func(string)                                                                      = saveConfigSnapshot
	_ func(string)                                                                      = copyConfigSnapshot
	_ func(context.Context, any, *grpc.UnaryServerInfo, grpc.UnaryHandler) (any, error) = unaryAuthInterceptor
	_ func(any, grpc.ServerStream, *grpc.StreamServerInfo, grpc.StreamHandler) error    = streamAuthInterceptor
	_ uint16                                                                            = sCommandServerListenPort
	_ string                                                                            = sBasePath
	_ int                                                                               = sUserID
	_ int                                                                               = sGroupID
	_ int                                                                               = sLogMaxLines
	_ bool                                                                              = sOOMKillerEnabled
	_ bool                                                                              = sOOMKillerDisabled
	_ int64                                                                             = sOOMMemoryLimit

	_ *CommandClient
	_ *Connection
	_ *ConnectionEvent
	_ *ConnectionEvents
	_ = ConnectionEventNew
	_ = ConnectionEventUpdate
	_ = ConnectionEventClosed
	_ StringIterator

	_ = NetworkQualityDefaultConfigURL
	_ = NetworkQualityPhaseDone
	_ *NetworkQualityProgress
	_ *NetworkQualityResult
	_ NetworkQualityTestHandler
	_ = NewNetworkQualityTest
	_ = NewSTUNTest
	_ = STUNDefaultServer
	_ = STUNPhaseDone
	_ STUNTestHandler
	_ *STUNTestProgress
	_ *STUNTestResult
	_ = FormatNATFiltering
	_ = FormatNATMapping
	_ *OpenConnectAuthChallenge
	_ *OpenConnectEndpointStatus
	_ *OpenConnectStatusUpdate
	_ *OpenVPNEndpointStatus
	_ *OpenVPNStatusUpdate
	_ *TailscaleEndpointStatus
	_ *TailscalePeer
	_ TailscalePingHandler
	_ *TailscalePingResult
	_ *TailscaleStatusUpdate
)

// touchLibboxFields names the unexported libbox fields fk-box reads. Never called.
func touchLibboxFields(s *CommandServer, owner *ConnectionOwner, box *StringBox) {
	var _ context.Context = s.ctx
	var _ PlatformInterface = s.platformInterface
	var _ *daemon.StartedService = s.StartedService
	var _ *powerreport.Manager = s.powerManager
	var _ *oomkiller.Recorder = s.oomRecorder
	var _ *daemon.ManagedService = s.managedService
	var _ *grpc.Server = s.grpcServer
	var _ net.Listener = s.listener
	var _ []string = owner.androidPackageNames
	var _ string = owner.UserName
	var _ string = box.Value
	// The TUN options libbox hands the platform: field order and types.
	_ = tunOptions{(*tun.Options)(nil), []netip.Prefix(nil), option.TunPlatformOptions{}}
}

// ---- sing-tun (pinned by sing-box): the TUN fk-box opens for Xray and mihomo ----

var (
	_ func(*tun.Options, bool) ([]netip.Prefix, error) = (*tun.Options).BuildAutoRouteRanges
	_                                                  = tun.DNSModeHijack
	_                                                  = tun.WithGVisor
)

// ---- sing-box packages ----

var (
	// daemon: the StartedService gRPC service fk-box serves
	// (core_command_server.go); sing-box calls are forwarded to the running
	// box's attached service (fkhost.SingBox) or an idle one.
	_ func(*daemon.StartedService, log.Level, string) = (*daemon.StartedService).WriteMessage
	_ func(context.Context) *daemon.StartedService    = daemon.NewAttachedService
	_ func(*daemon.StartedService)                    = (*daemon.StartedService).Close
	_ daemon.UnimplementedStartedServiceServer
	_ = daemon.RegisterStartedServiceServer
	_ = daemon.RegisterManagedServiceServer
	_ = daemon.UnaryLocaleInterceptor
	_ = daemon.StreamLocaleInterceptor
	_ = daemon.StartedService_ServiceDesc
	_ = daemon.ManagedService_ServiceDesc
	_ = daemon.APIVersion
	_ *daemon.Version
	_ *daemon.Log
	_ *daemon.Log_Message
	_ *daemon.DeprecatedWarning
	_ *daemon.NotificationEvent
	_ = (*daemon.StartedService).SubscribeNotifications
	_ = (*daemon.StartedService).StartTailscalePing
	_ = (*daemon.StartedService).SetTailscaleExitNode
	_ = (*daemon.StartedService).TailscaleLogout
	_ = (*daemon.StartedService).GetTailscaleCertificate
	_ = (*daemon.StartedService).StartTailscaleSSHSession
	_ = (*daemon.StartedService).MarkTaildropInboxRead
	_ = (*daemon.StartedService).SendTaildropFiles
	_ = (*daemon.StartedService).DownloadTaildropFile
	_ = (*daemon.StartedService).DeleteTaildropFile
	_ = (*daemon.StartedService).CancelTaildropReceiving
	_ = (*daemon.StartedService).SubmitOpenConnectAuthResponse
	_ = (*daemon.StartedService).CancelOpenConnectAuthChallenge
	_ = (*daemon.StartedService).SubmitOpenVPNChallengeResponse
	_ = (*daemon.StartedService).CancelOpenVPNChallenge
	_ *daemon.TailscalePingRequest
	_ *daemon.TailscalePingResponse
	_ *daemon.SetTailscaleExitNodeRequest
	_ *daemon.TailscaleLogoutRequest
	_ *daemon.TailscaleCertificateRequest
	_ *daemon.TailscaleCertificate
	_ *daemon.TailscaleSSHClientMessage
	_ *daemon.TailscaleSSHServerMessage
	_ *daemon.MarkTaildropInboxReadRequest
	_ *daemon.TaildropSendClientMessage
	_ *daemon.TaildropSendServerMessage
	_ *daemon.DownloadTaildropFileRequest
	_ *daemon.DownloadTaildropFileChunk
	_ *daemon.DeleteTaildropFileRequest
	_ *daemon.CancelTaildropReceivingRequest
	_ *daemon.OpenConnectAuthResponseSubmission
	_ *daemon.OpenConnectAuthChallengeCancel
	_ *daemon.OpenVPNChallengeSubmission
	_ *daemon.OpenVPNChallengeCancel
	_ = (*daemon.StartedService).SubscribeServiceStatus
	_ = (*daemon.StartedService).GetStartedAt
	_ = (*daemon.StartedService).SubscribeStatus
	_ = (*daemon.StartedService).SubscribeGroups
	_ = (*daemon.StartedService).SelectOutbound
	_ = (*daemon.StartedService).URLTest
	_ = (*daemon.StartedService).GetClashModeStatus
	_ = (*daemon.StartedService).SubscribeClashMode
	_ = (*daemon.StartedService).SetClashMode
	_ = (*daemon.StartedService).SubscribeConnections
	_ = (*daemon.StartedService).CloseConnection
	_ = (*daemon.StartedService).CloseAllConnections
	_ = (*daemon.StartedService).SubscribeOutbounds
	_ = (*daemon.StartedService).SubscribeTailscaleStatus
	_ = (*daemon.StartedService).SubscribeOpenConnectStatus
	_ = (*daemon.StartedService).SubscribeOpenVPNStatus
	_ = (*daemon.StartedService).SubscribeTaildropInbox
	_ = (*daemon.StartedService).SetGroupExpand
	_ = (*daemon.StartedService).GetDefaultLogLevel
	_ = (*daemon.StartedService).GetDeprecatedWarnings
	_ = (*daemon.StartedService).StartNetworkQualityTest
	_ = (*daemon.StartedService).StartSTUNTest
	_ = (*daemon.StartedService).SubscribeUSBIPServerStatus
	_ = (*daemon.StartedService).ProvideUSBDevices
	_ = daemon.NewStartedService
	_ *daemon.ServiceOptions

	_ *daemon.ClashMode
	_ *daemon.ClashModeStatus
	_ *daemon.CloseConnectionRequest
	_ *daemon.Connection
	_ *daemon.ConnectionEvent
	_ = daemon.ConnectionEventType_CONNECTION_EVENT_CLOSED
	_ = daemon.ConnectionEventType_CONNECTION_EVENT_NEW
	_ = daemon.ConnectionEventType_CONNECTION_EVENT_UPDATE
	_ *daemon.ConnectionEvents
	_ *daemon.DefaultLogLevel
	_ *daemon.DeprecatedWarnings
	_ *daemon.Group
	_ *daemon.GroupItem
	_ *daemon.Groups
	_ *daemon.LogLevel
	_ = daemon.LogLevel_DEBUG
	_ = daemon.LogLevel_ERROR
	_ = daemon.LogLevel_INFO
	_ = daemon.LogLevel_PANIC
	_ = daemon.LogLevel_WARN
	_ *daemon.NetworkQualityTestProgress
	_ *daemon.NetworkQualityTestRequest
	_ = daemon.NewNetworkQualityTestProgress
	_ = daemon.NewNetworkQualityTestResult
	_ = daemon.NewSTUNTestProgress
	_ = daemon.NewSTUNTestResult
	_ *daemon.OpenConnectStatusUpdate
	_ *daemon.OpenVPNStatusUpdate
	_ *daemon.OutboundList
	_ *daemon.STUNTestProgress
	_ *daemon.STUNTestRequest
	_ *daemon.SelectOutboundRequest
	_ *daemon.ServiceStatus
	_ = daemon.ServiceStatus_STARTED
	_ *daemon.SetGroupExpandRequest
	_ *daemon.StartedAt
	_ *daemon.Status
	_ *daemon.SubscribeConnectionsRequest
	_ *daemon.SubscribeStatusRequest
	_ *daemon.SubscribeTaildropInboxRequest
	_ *daemon.TaildropInbox
	_ *daemon.TailscaleStatusUpdate
	_ *daemon.URLTestRequest
	_ *daemon.USBIPServerStatusUpdate
	_ *daemon.USBProviderMessage
	_ *daemon.USBServerMessage

	// log: the service log levels and the process logger.
	_ *log.Level
	_ = log.LevelDebug
	_ = log.LevelError
	_ = log.LevelInfo
	_ = log.LevelWarn
	_ = log.Warn

	// option, constant: the LAN proxy binding and the TUN platform options.
	_ *option.HTTPInboundOptions
	_ *option.HTTPMixedInboundOptions
	_ *option.Inbound
	_ *option.ListenOptions
	_ *option.ListenOptionsWrapper
	_ *option.SocksInboundOptions
	_ *option.TunPlatformOptions
	_ = C.TypeHTTP
	_ = C.TypeMixed
	_ = C.TypeSOCKS
	_ = C.ProtocolQUIC

	// common/networkquality, common/stun: the quality and STUN tools.
	_ = networkquality.NewHTTPClient
	_ = networkquality.NewOptionalHTTP3Factory
	_ *networkquality.Options
	_ *networkquality.Progress
	_ = networkquality.Run
	_ *stun.Options
	_ *stun.Progress
	_ = stun.Run

	// adapter, common/sniff: the HTTP capture route hook (core/route).
	_ *adapter.ConnectionHandler
	_ *adapter.InboundContext
	_ *adapter.Outbound
	_ = adapter.RootPoolFromContext
	_ = adapter.WithContext
	_ = sniff.ErrNeedMoreData
	_ = sniff.HTTPHost
	_ = sniff.PeekStream
	_ = sniff.QUICClientHello
	_ = sniff.TLSClientHello

	// fkhost (experimental/fkhost): fk-box's HTTP client on sing-box's
	// certificate store, as libbox's client did.
	_ = adapter.NewScope
	_ *adapter.Scope
	_ = (*adapter.Scope).Close
	_ = adapter.StartStateInitialize
	_ = certificate.NewStore
	_ = (*certificate.Store).Start
	_ = (*certificate.Store).Pool
	_ = C.IsAndroid
	_ = C.TCPTimeout
	_ = log.NewNOPFactory
	_ *option.CertificateOptions

	// box, log, option, constant, locale: sing-box as an ordinary kernel
	// (fkhost.SingBox, core_singbox_host.go, core_standalone.go).
	_ func(box.Options) (*box.Box, error) = box.New
	_ func(*box.Box) error                = (*box.Box).Start
	_ func(*box.Box) error                = (*box.Box).Close
	_                                     = (*box.Box).CreatedAt
	_                                     = (*box.Box).LogFactory
	_                                     = (*box.Box).Network
	_                                     = (*box.Box).Router
	_ log.PlatformWriter                  = nil
	_                                     = log.LevelPanic
	_                                     = log.LevelFatal
	_                                     = log.SetStdLogger
	_ *option.Options
	_ *option.Service
	_ *option.TunInboundOptions
	_ *option.OOMKillerServiceOptions
	_ = C.TypeOOMKiller
	_ = C.Version
	_ = locale.FromContext
	_ = urltest.NewHistoryStorage
	_ *urltest.HistoryStorage
	_ deprecated.Manager
	_ *deprecated.Note
	_ *powerreport.Manager
	_ = powerreport.DraftDirectoryName
	_ *oomkiller.Recorder

	// schema: the http_rewrite option schema (core/option).
	_ *schema.Builder
	_ *schema.Node
	_ = schema.StrictObject
)
