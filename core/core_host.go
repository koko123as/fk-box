package libbox

// fk-box's host interfaces: the services a kernel adapter (core_kernel_*.go)
// gets from the program that hosts it. Kernels see only these interfaces and
// fk-box's own types; they never read libbox globals or libbox types (the
// PlatformInterface, the CommandServer, OverrideOptions, the HTTP client).
//
// The services are fk-box's own (experimental/fkhost): log, HTTP, platform,
// working directory. On Android libbox still provides what only it can give
// (the app's PlatformInterface object, the StartedService log the app reads,
// sing-box's start and stop, the command service); core/README.md has the
// status of moving the rest out of libbox.

import (
	"net/http"
	"net/netip"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/sagernet/sing-box/experimental/fkhost"
	"github.com/sagernet/sing-box/log"
)

// kernelHost bundles the host services one kernel start uses.
type kernelHost struct {
	Platform  hostPlatform
	Commands  hostCommandService
	Lifecycle hostLifecycle
	HTTP      hostHTTP
	Log       hostLog
}

// kernelOptions carries the host's own start options through a kernel to the
// host services that read them: the TUN's per-app lists (Platform.OpenTun)
// and sing-box's start (Lifecycle.StartSingBox). Kernels do not look inside.
// With libbox it holds the *OverrideOptions the app passed; its package
// iterators can be read only once, so only the service that uses them does.
type kernelOptions struct {
	host any
}

// hostTunRequest is the TUN a kernel wants: fk-box's fixed addresses and MTU.
type hostTunRequest struct {
	Inet4 netip.Prefix
	Inet6 netip.Prefix
	MTU   uint32
}

// hostConnectionOwner is the app that owns a connection.
type hostConnectionOwner struct {
	PackageNames []string
	UserName     string
}

// hostPlatform is the operating-system side: the TUN, keeping the kernel's
// own sockets off it, connection owners, and the default network.
type hostPlatform interface {
	// OpenTun opens the VPN TUN and returns a descriptor the caller owns
	// and closes.
	OpenTun(request hostTunRequest, options kernelOptions) (int, error)
	// Protect keeps an outbound socket off the TUN (Android: VpnService.protect).
	Protect(conn syscall.RawConn) error
	// ConnectionOwner returns the app that owns a connection, nil when none
	// is known. ok is false when the platform cannot look owners up.
	ConnectionOwner(protocol int32, source, destination netip.AddrPort) (owner *hostConnectionOwner, ok bool, err error)
	// WatchDefaultNetwork calls onChange whenever the underlying default
	// network is replaced (Wi-Fi to cellular and back) until cancel is called.
	WatchDefaultNetwork(onChange func()) (cancel func())
}

// hostCommandService is the control channel the UI reads the core through
// (libbox: the CommandServer's StartedService gRPC service).
type hostCommandService interface {
	// CoreChanged moves every open UI stream to the core that runs now.
	CoreChanged()
	// GroupsChanged makes the open groups streams read the groups again.
	GroupsChanged()
}

// hostLifecycle is the service lifecycle around the kernels.
type hostLifecycle interface {
	// StartSingBox starts or reloads sing-box inside the host's own service
	// (libbox: CommandServer.StartOrReloadService).
	StartSingBox(content string, options kernelOptions) error
	// StopSingBox stops it (libbox: CommandServer.CloseService).
	StopSingBox() error
	// WorkingDirectory is the app's working directory; kernels keep their
	// files below it.
	WorkingDirectory() string
}

// hostHTTP gives HTTP clients that trust the host's certificate store.
type hostHTTP interface {
	// NewClient returns a client with modern TLS and the given timeout, and
	// the function that releases it.
	NewClient(timeout time.Duration) (*http.Client, func())
}

// hostLogLevel is fk-box's log level (fkhost.Level).
type hostLogLevel = fkhost.Level

const (
	hostLogError = fkhost.LevelError
	hostLogWarn  = fkhost.LevelWarn
	hostLogInfo  = fkhost.LevelInfo
	hostLogDebug = fkhost.LevelDebug
)

// hostLog is where kernels log.
type hostLog interface {
	// Write adds a line to the service log the app shows.
	Write(level hostLogLevel, message string)
	// Diagnostic reports a problem to the process log only, not to the
	// app's log page.
	Diagnostic(message string)
}

// ---- the CommandServer host (Android) ----

// kernelHost returns the host services of this CommandServer.
func (s *CommandServer) kernelHost() kernelHost {
	return kernelHost{
		Platform:  fkhostPlatform{s.hostPlatform()},
		Commands:  libboxCommandService{},
		Lifecycle: libboxLifecycle{s},
		HTTP:      fkhostHTTP{},
		Log:       s.hostLog(),
	}
}

// Platform: fk-box's own (fkhost.Platform, one implementation per operating
// system). On Android it calls the app's platform implementation, the
// PlatformInterface object the app gives libbox (core_host_android.go).
type fkhostPlatform struct{ platform fkhost.Platform }

func (p fkhostPlatform) OpenTun(request hostTunRequest, options kernelOptions) (int, error) {
	tunRequest := fkhost.TunRequest{Inet4: request.Inet4, Inet6: request.Inet6, MTU: request.MTU}
	if override, _ := options.host.(*OverrideOptions); override != nil {
		tunRequest.IncludePackage = iteratorToArray(override.IncludePackage)
		tunRequest.ExcludePackage = iteratorToArray(override.ExcludePackage)
	}
	return p.platform.OpenTun(tunRequest)
}

func (p fkhostPlatform) Protect(conn syscall.RawConn) error { return p.platform.Protect(conn) }

func (p fkhostPlatform) ConnectionOwner(protocol int32, source, destination netip.AddrPort) (*hostConnectionOwner, bool, error) {
	owner, ok, err := p.platform.ConnectionOwner(protocol, source, destination)
	if owner == nil {
		return nil, ok, err
	}
	return &hostConnectionOwner{PackageNames: owner.PackageNames, UserName: owner.UserName}, ok, err
}

func (fkhostPlatform) WatchDefaultNetwork(onChange func()) func() {
	return fkhost.WatchDefaultNetwork(onChange)
}

// CoreDefaultNetworkChanged is called when the underlying default network is
// replaced (Wi-Fi to cellular and back). Connections of mihomo and Xray are
// still bound to the old network and would hang until their own timeouts;
// the running kernel closes them so apps reconnect at once. sing-box resets
// its own connections when its default interface changes. The app calls
// Fkbox.defaultNetworkChanged; this name stays for older callers.
func CoreDefaultNetworkChanged() { fkhost.DefaultNetworkChanged() }

type libboxCommandService struct{}

func (libboxCommandService) CoreChanged()   { notifyCoreChange() }
func (libboxCommandService) GroupsChanged() { notifyGroupsChange() }

// Lifecycle: on Android sing-box still starts and stops inside this
// CommandServer (daemon.StartedService); core/README.md says why (step 3).
// Xray and mihomo are started and stopped by fk-box itself (core_service.go),
// and the standalone host starts sing-box itself (core_standalone.go).
type libboxLifecycle struct{ server *CommandServer }

func (l libboxLifecycle) StartSingBox(content string, options kernelOptions) error {
	override, _ := options.host.(*OverrideOptions)
	return l.server.StartOrReloadService(content, override)
}

func (l libboxLifecycle) StopSingBox() error { return l.server.CloseService() }

// WorkingDirectory is fkhost's when the host set one (the desktop core),
// otherwise libbox's Setup path (Android: the app's files directory).
func (libboxLifecycle) WorkingDirectory() string { return coreWorkingPath() }

func coreWorkingPath() string {
	if directory := fkhost.WorkingDirectory(); directory != "" {
		return directory
	}
	return sWorkingPath
}

// HTTP: fk-box's own client (fkhost.NewHTTPClient), no longer libbox's.
type fkhostHTTP struct{}

func (fkhostHTTP) NewClient(timeout time.Duration) (*http.Client, func()) {
	return fkhost.NewHTTPClientTimeout(timeout)
}

// Log: fk-box's own log (fkhost.Log). sing-box still runs inside this
// CommandServer and the app's log page reads its StartedService log, so every
// fk-box line is forwarded there too; diagnostics go to sing-box's logger
// (logcat), as before.

type fkhostLog struct{}

func (fkhostLog) Write(level hostLogLevel, message string) { fkhost.Log.Write(level, message) }

func (fkhostLog) Diagnostic(message string) { fkhost.Log.Diagnostic(message) }

// hostLog connects fkhost.Log to this server's service log and returns it.
func (s *CommandServer) hostLog() hostLog {
	if server := s.StartedService; server != nil {
		fkhost.Log.SetForward(func(entry fkhost.Entry) {
			server.WriteMessage(libboxLogLevel(entry.Level), entry.Message)
		})
	}
	fkhost.Log.SetDiagnostic(func(message string) { log.Warn(message) })
	return fkhostLog{}
}

func libboxLogLevel(level hostLogLevel) log.Level {
	switch level {
	case hostLogError:
		return log.LevelError
	case hostLogWarn:
		return log.LevelWarn
	case hostLogDebug:
		return log.LevelDebug
	}
	return log.LevelInfo
}

// ---- the host the running kernel uses ----

// Xray's dialer controller, mihomo's socket hook and its package-name
// resolver are process-global and registered once (init); they look up the
// host of the kernel started last.
var kernelHostInUse atomic.Pointer[kernelHost]

func useKernelHost(host kernelHost) { kernelHostInUse.Store(&host) }

// protectKernelSocket keeps a kernel socket off the TUN; before any kernel
// started there is nothing to protect from.
func protectKernelSocket(conn syscall.RawConn) error {
	host := kernelHostInUse.Load()
	if host == nil || host.Platform == nil {
		return nil
	}
	return host.Platform.Protect(conn)
}
