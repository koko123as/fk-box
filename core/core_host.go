package libbox

// fk-box's host interfaces: the services a kernel adapter (core_kernel_*.go)
// gets from the program that hosts it. Kernels see only these interfaces and
// fk-box's own types; they never read libbox globals or libbox types (the
// PlatformInterface, the CommandServer, OverrideOptions, the HTTP client).
//
// Today every host service is still provided by libbox: libboxHost below
// delegates each call to the libbox code that did the job before, so behavior
// is unchanged. core/README.md describes the plan to move these services out
// of libbox one by one.

import (
	"net/http"
	"net/netip"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/sagernet/sing-box/experimental/fkhost"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	tun "github.com/sagernet/sing-tun"
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

// ---- libbox implementation ----

// kernelHost returns the host services of this CommandServer.
func (s *CommandServer) kernelHost() kernelHost {
	return kernelHost{
		Platform:  libboxPlatform{s},
		Commands:  libboxCommandService{},
		Lifecycle: libboxLifecycle{s},
		HTTP:      libboxHTTP{},
		Log:       s.hostLog(),
	}
}

type libboxPlatform struct{ server *CommandServer }

func (p libboxPlatform) OpenTun(request hostTunRequest, options kernelOptions) (int, error) {
	opts := tun.Options{
		Inet4Address: []netip.Prefix{request.Inet4},
		Inet6Address: []netip.Prefix{request.Inet6},
		MTU:          request.MTU,
		AutoRoute:    true,
		DNSMode:      tun.DNSModeHijack,
	}
	if override, _ := options.host.(*OverrideOptions); override != nil {
		opts.IncludePackage = iteratorToArray(override.IncludePackage)
		opts.ExcludePackage = iteratorToArray(override.ExcludePackage)
	}
	routeRanges, err := opts.BuildAutoRouteRanges(true)
	if err != nil {
		return -1, err
	}
	tunFd, err := p.server.platformInterface.OpenTun(&tunOptions{&opts, routeRanges, option.TunPlatformOptions{}})
	if err != nil {
		return -1, err
	}
	if name, err := getTunnelName(tunFd); err == nil {
		p.server.platformInterface.RegisterMyInterface(name)
	}
	return dup(int(tunFd))
}

func (p libboxPlatform) Protect(conn syscall.RawConn) error {
	platform := p.server.platformInterface
	if platform == nil {
		return nil
	}
	var protectErr error
	err := conn.Control(func(fd uintptr) {
		protectErr = platform.AutoDetectInterfaceControl(int32(fd))
	})
	if err != nil {
		return err
	}
	return protectErr
}

func (p libboxPlatform) ConnectionOwner(protocol int32, source, destination netip.AddrPort) (*hostConnectionOwner, bool, error) {
	platform := p.server.platformInterface
	if platform == nil {
		return nil, false, nil
	}
	owner, err := platform.FindConnectionOwner(protocol, source.Addr().String(), int32(source.Port()), destination.Addr().String(), int32(destination.Port()))
	if err != nil {
		return nil, true, err
	}
	if owner == nil {
		return nil, true, nil
	}
	return &hostConnectionOwner{PackageNames: owner.androidPackageNames, UserName: owner.UserName}, true, nil
}

// The app reports default-network changes through CoreDefaultNetworkChanged
// (its ConnectivityManager callback); every watcher hears each one.
var (
	hostNetworkAccess   sync.Mutex
	hostNetworkNext     uint64
	hostNetworkWatchers = map[uint64]func(){}
)

func (libboxPlatform) WatchDefaultNetwork(onChange func()) func() {
	hostNetworkAccess.Lock()
	id := hostNetworkNext
	hostNetworkNext++
	hostNetworkWatchers[id] = onChange
	hostNetworkAccess.Unlock()
	return func() {
		hostNetworkAccess.Lock()
		delete(hostNetworkWatchers, id)
		hostNetworkAccess.Unlock()
	}
}

// CoreDefaultNetworkChanged is called when the underlying default network is
// replaced (Wi-Fi to cellular and back). Connections of mihomo and Xray are
// still bound to the old network and would hang until their own timeouts;
// the running kernel closes them so apps reconnect at once. sing-box resets
// its own connections when its default interface changes.
func CoreDefaultNetworkChanged() {
	hostNetworkAccess.Lock()
	watchers := make([]func(), 0, len(hostNetworkWatchers))
	for _, onChange := range hostNetworkWatchers {
		watchers = append(watchers, onChange)
	}
	hostNetworkAccess.Unlock()
	for _, onChange := range watchers {
		onChange()
	}
}

type libboxCommandService struct{}

func (libboxCommandService) CoreChanged()   { notifyCoreChange() }
func (libboxCommandService) GroupsChanged() { notifyGroupsChange() }

type libboxLifecycle struct{ server *CommandServer }

func (l libboxLifecycle) StartSingBox(content string, options kernelOptions) error {
	override, _ := options.host.(*OverrideOptions)
	return l.server.StartOrReloadService(content, override)
}

func (l libboxLifecycle) StopSingBox() error { return l.server.CloseService() }

func (libboxLifecycle) WorkingDirectory() string { return sWorkingPath }

type libboxHTTP struct{}

func (libboxHTTP) NewClient(timeout time.Duration) (*http.Client, func()) {
	client := NewHTTPClient().(*httpClient)
	client.ModernTLS()
	client.client.Timeout = timeout
	return &client.client, client.Close
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
