package libbox

// Phase 2: run Xray or mihomo in place of sing-box inside the same
// CommandServer. Only one core runs at a time. The VPN TUN is still opened by
// the app's VpnService (PlatformInterface.OpenTun) and its fd is handed to the
// running core directly; every outbound socket of Xray and mihomo is passed to
// PlatformInterface.AutoDetectInterfaceControl, which calls VpnService.protect.
//
// The kernels themselves are in core_kernel_singbox.go, core_kernel_xray.go
// and core_kernel_mihomo.go (one interface, core_kernel.go); they reach the
// app only through the host services in core_host.go.

import (
	"fmt"
	"net/netip"
	"sync"
	"syscall"
	"time"

	"github.com/sagernet/sing-box/daemon"
	E "github.com/sagernet/sing/common/exceptions"
	xrayCore "github.com/xtls/xray-core/core"
)

// The TUN every non-sing-box core gets. sing-box keeps using its own profile's
// tun inbound.
var (
	coreTunInet4 = netip.MustParsePrefix("172.19.0.1/30")
	coreTunInet6 = netip.MustParsePrefix("fdfe:dcba:9876::1/126")
)

const coreTunMTU = 9000

type runningCore struct {
	core      string
	xray      *xrayCore.Instance
	xrayTrack *xrayTracker
	mihomoLog chan struct{}
	tunFd     int
	startedAt time.Time
	// The level the core runs at, for GetDefaultLogLevel.
	logLevel daemon.LogLevel
	// unwatchNetwork ends the kernel's default-network watch.
	unwatchNetwork func()

	access           sync.Mutex
	xrayBalancers    []xrayBalancer
	xrayOutboundTags []string
	xrayProtocols    map[string]string
	xrayDelays       map[string]coreDelay
}

type xrayBalancer struct {
	tag      string
	strategy string
	members  []string
}

type coreDelay struct {
	delay int32
	at    time.Time
}

var (
	// coreLifecycle serializes starting and stopping Xray and mihomo;
	// coreAccess guards activeCore only.
	coreLifecycle sync.Mutex
	coreAccess    sync.Mutex
	activeCore    *runningCore
)

// StartOrReloadCoreService starts the profile on its core, stopping whichever
// core ran before. sing-box is started by the host's lifecycle
// (fkhost.StartSingBox).
//
// Starting and stopping are serialized by coreLifecycle, which is held for
// the whole switch; coreAccess only guards activeCore and is never held while
// a core starts or stops. A first mihomo start can take tens of seconds (its
// remote proxy and rule providers are downloaded inside ApplyConfig before it
// returns); holding coreAccess that long froze every reader of the running
// core with it: the dashboard's status, memory, connection and group streams,
// the egress and service-status probes, and the network callback
// (CoreDefaultNetworkChanged), whose thread also delivers the default
// network to the service's start and stop.
//
// Every open UI stream is held across the switch (beginCoreSwitch) and moved
// to the new core when it returns, whether it started or failed, so the
// groups, status, mode and connection subscriptions follow a reload instead
// of ending on the stopped core.
func (s *CommandServer) StartOrReloadCoreService(core string, configContent string, options *CoreServiceOptions) error {
	return startOrReloadCore(s.kernelHost, core, configContent, kernelOptions{host: options})
}

// startOrReloadCore is StartOrReloadCoreService for any host: libbox's
// CommandServer on Android, fk-box's standalone host on the desktop
// (core_standalone.go). hostOf is called once per start or stop.
func startOrReloadCore(hostOf func() kernelHost, core string, configContent string, kernelOpts kernelOptions) (err error) {
	beginCoreSwitch()
	defer endCoreSwitch()
	defer func() { coreStatus.startFinished(err) }()
	resetUIDNSQueryCount()
	kernel := kernelFor(core)
	if kernel == nil {
		return fmt.Errorf("unknown core: %s", core)
	}
	host := hostOf()
	if !kernel.NeedsTun() {
		// Not held across sing-box's own start: CloseService interrupts a
		// slow one (rule-set downloads) and must not wait for it here.
		stopOtherCore(hostOf)
		return kernel.Start(host, nil, configContent, nil, kernelOpts)
	}
	// Checked before anything is stopped (mihomo parses its YAML once here
	// and hands the checked raw config to its start).
	prepared, err := kernel.Prepare(configContent)
	if err != nil {
		return err
	}
	coreLifecycle.Lock()
	defer coreLifecycle.Unlock()
	// Stop sing-box (if it was running) before its TUN is replaced.
	singBoxKernel{}.Stop(host, nil)
	stopOtherCoreLocked(hostOf)

	useKernelHost(host)
	fd, err := host.Platform.OpenTun(hostTunRequest{Inet4: coreTunInet4, Inet6: coreTunInet6, MTU: coreTunMTU}, kernelOpts)
	if err != nil {
		return E.Cause(err, "open tun")
	}
	running := &runningCore{core: kernel.Name(), tunFd: fd}
	if err := kernel.Start(host, running, configContent, prepared, kernelOpts); err != nil {
		_ = syscall.Close(fd)
		return E.Cause(err, "start ", running.core)
	}
	running.startedAt = time.Now()
	running.unwatchNetwork = host.Platform.WatchDefaultNetwork(func() { kernel.DefaultNetworkChanged(running) })
	coreAccess.Lock()
	activeCore = running
	coreAccess.Unlock()
	host.Log.Write(hostLogInfo, running.core+" started")
	go host.Commands.CoreChanged()
	// Config parsing (rule sets, geo matchers) leaves garbage behind; give
	// it back now, as sing-box does after starting its instance.
	go releaseCoreMemory()
	return nil
}

// CloseCoreService stops whichever core is running.
func (s *CommandServer) CloseCoreService() error {
	return closeCore(s.kernelHost)
}

// closeCore is CloseCoreService for any host. The service status goes
// through STOPPING to IDLE unless the service was not starting or running.
func closeCore(hostOf func() kernelHost) error {
	if coreStatus.stopping() {
		defer coreStatus.set(daemon.ServiceStatus_IDLE)
	}
	if stopOtherCore(hostOf) {
		releaseCoreMemory()
	}
	return hostOf().Lifecycle.StopSingBox()
}

// stopOtherCore stops Xray or mihomo and reports whether one was running.
func stopOtherCore(hostOf func() kernelHost) bool {
	coreLifecycle.Lock()
	defer coreLifecycle.Unlock()
	return stopOtherCoreLocked(hostOf)
}

// stopOtherCoreLocked is stopOtherCore with coreLifecycle held. The core is
// taken out of activeCore first, so readers stop using it at once, and shut
// down without coreAccess held.
func stopOtherCoreLocked(hostOf func() kernelHost) bool {
	coreAccess.Lock()
	running := activeCore
	activeCore = nil
	coreAccess.Unlock()
	if running == nil {
		return false
	}
	host := hostOf()
	if running.unwatchNetwork != nil {
		running.unwatchNetwork()
	}
	if kernel := kernelFor(running.core); kernel != nil {
		kernel.Stop(host, running)
	}
	// running.tunFd is our own dup of the VpnService descriptor (Xray never
	// closes the fd it reads; mihomo closed its separate dup in Shutdown), so
	// it is closed exactly once here. Closing a descriptor twice could hit a
	// number Android reused for a tracked fd, which aborts the whole app.
	_ = syscall.Close(running.tunFd)
	host.Log.Write(hostLogInfo, running.core+" stopped")
	go host.Commands.CoreChanged()
	return true
}
