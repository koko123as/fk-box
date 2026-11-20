package libbox

// mihomo as a kernel, embedded the way ClashMetaForAndroid embeds it (cmfa
// build tag): it runs on its own dup of fk-box's TUN, its sockets are
// protected through the host, and PROCESS-NAME rules ask the host for the
// connection's app (core_routing_owner.go).
//
// References: mihomo listener/sing_tun (file-descriptor),
// component/dialer.DefaultSocketHook, and ClashMetaForAndroid's use of the
// cmfa build tag.

import (
	"context"
	"fmt"
	"net/netip"
	"syscall"

	mihomoDialer "github.com/metacubex/mihomo/component/dialer"
	mihomoResolver "github.com/metacubex/mihomo/component/resolver"
	mihomoConfig "github.com/metacubex/mihomo/config"
	mihomoConstant "github.com/metacubex/mihomo/constant"
	mihomoExecutor "github.com/metacubex/mihomo/hub/executor"
	mihomoListener "github.com/metacubex/mihomo/listener"
	mihomoLog "github.com/metacubex/mihomo/log"
	mihomoTunnel "github.com/metacubex/mihomo/tunnel"
	mDNS "github.com/miekg/dns"
	E "github.com/sagernet/sing/common/exceptions"
)

type mihomoKernel struct{}

func (mihomoKernel) Name() string { return CoreMihomo }

func (mihomoKernel) NeedsTun() bool { return true }

// Prepare parses the YAML once: the checked raw config is handed to Start
// instead of being parsed again.
func (mihomoKernel) Prepare(content string) (any, error) {
	raw, err := parseCheckedMihomoConfig(content)
	if err != nil {
		return nil, err
	}
	return raw, nil
}

func (mihomoKernel) Start(host kernelHost, running *runningCore, content string, prepared any, _ kernelOptions) error {
	raw, _ := prepared.(*mihomoConfig.RawConfig)
	return startMihomoCore(host, running, content, raw)
}

// Stop shuts mihomo down and releases every port and the state the profile
// held; mihomo closes its own dup of the TUN descriptor.
func (mihomoKernel) Stop(_ kernelHost, running *runningCore) {
	mihomoExecutor.Shutdown()
	mihomoCloseListeners()
	mihomoReleaseState()
	if running.mihomoLog != nil {
		close(running.mihomoLog)
	}
}

// DefaultNetworkChanged drops the DNS cache and the connections bound to the
// old network, so apps reconnect at once.
func (mihomoKernel) DefaultNetworkChanged(*runningCore) {
	flushCoreDNS()
	closeAllMihomoConnections()
}

func init() {
	// Process-global and never unregistered: installed once, it protects
	// through the host of the kernel started last.
	mihomoDialer.DefaultSocketHook = func(network, address string, conn syscall.RawConn) error {
		return protectKernelSocket(conn)
	}
}

// mihomo: the profile's tun section is replaced by an fd TUN without
// auto-route or interface detection (the VpnService owns routing, and netlink
// is not available to apps on recent Android). External controllers are
// turned off; the app reads mihomo in process.
func startMihomoCore(host kernelHost, running *runningCore, content string, raw *mihomoConfig.RawConfig) error {
	mihomoConstant.SetHomeDir(coreAssetDir(host.Lifecycle.WorkingDirectory(), CoreMihomo))
	if raw == nil {
		parsed, err := mihomoConfig.UnmarshalRawConfig([]byte(normalizeCoreContent(content)))
		if err != nil {
			return err
		}
		raw = parsed
	}
	if !mihomoSetsLogLevel(content) {
		raw.LogLevel = mihomoLog.WARNING
	}
	raw.Tun.Enable = true
	// The real descriptor is set after parsing: mihomo gets a dup of its own,
	// since its TUN closes the descriptor it is given.
	raw.Tun.FileDescriptor = running.tunFd
	raw.Tun.Device = ""
	raw.Tun.Stack = mihomoConstant.TunSystem
	raw.Tun.AutoRoute = false
	raw.Tun.AutoRedirect = false
	raw.Tun.AutoDetectInterface = false
	raw.Tun.MTU = coreTunMTU
	raw.Tun.Inet6Address = []netip.Prefix{coreTunInet6}
	if len(raw.Tun.DNSHijack) == 0 {
		raw.Tun.DNSHijack = []string{"0.0.0.0:53"}
	}
	// redir/tproxy need root and iptables, which a VpnService app lacks.
	raw.RedirPort = 0
	raw.TProxyPort = 0
	raw.Interface = ""
	raw.RoutingMark = 0
	raw.ExternalController = ""
	raw.ExternalControllerTLS = ""
	raw.ExternalControllerUnix = ""
	raw.ExternalControllerPipe = ""
	raw.ExternalUI = ""
	config, err := mihomoConfig.ParseRawConfig(raw)
	if err != nil {
		return err
	}
	// parseTun derives the TUN address from the fake-ip range; the VpnService
	// assigned coreTunInet4, and the system stack must listen there.
	config.General.Tun.Inet4Address = []netip.Prefix{coreTunInet4}
	config.General.Tun.Inet6Address = []netip.Prefix{coreTunInet6}
	running.logLevel = mihomoDaemonLogLevel(config.General.LogLevel)
	running.mihomoLog = make(chan struct{})
	subscription := mihomoLog.Subscribe()
	go func(done chan struct{}) {
		defer mihomoLog.UnSubscribe(subscription)
		for {
			select {
			case <-done:
				return
			case event, ok := <-subscription:
				if !ok {
					return
				}
				// mihomo publishes every event whatever its level and only
				// filters its own stdout print; apply the same rule here so
				// debug/info lines below the configured level are not
				// formatted into the service log.
				if event.LogLevel < mihomoLog.Level() {
					continue
				}
				level := hostLogInfo
				switch event.LogLevel {
				case mihomoLog.ERROR:
					level = hostLogError
				case mihomoLog.WARNING:
					level = hostLogWarn
				case mihomoLog.DEBUG:
					level = hostLogDebug
				}
				host.Log.Write(level, "[mihomo] "+event.Payload)
			}
		}
	}(running.mihomoLog)
	mihomoFd, err := dup(running.tunFd)
	if err != nil {
		close(running.mihomoLog)
		running.mihomoLog = nil
		return E.Cause(err, "dup tun")
	}
	config.General.Tun.FileDescriptor = mihomoFd
	if remote := mihomoRemoteProviders(raw); remote > 0 {
		// executor.loadProvider waits for each one that has no cached copy
		// yet (up to 20 s each): on a profile's first start this is what
		// the start is waiting for.
		host.Log.Write(hostLogInfo, fmt.Sprintf("[mihomo] loading %d remote providers; a first start downloads them", remote))
	}
	// The TUN starts only once ApplyConfig has returned and the DNS counter
	// wraps the resolver ApplyConfig installed: the TUN's DNS hijack reads
	// resolver.DefaultService on every query, so replacing it while the
	// TUN already served would race those reads.
	tunConf := config.General.Tun
	config.General.Tun.Enable = false
	mihomoExecutor.ApplyConfig(config, true)
	countMihomoDNS()
	config.General.Tun = tunConf
	mihomoListener.ReCreateTun(tunConf, mihomoTunnel.Tunnel)
	if !mihomoListener.GetTunConf().Enable {
		// ReCreateTun logs its error and leaves the TUN off; without it
		// nothing is routed, so the start fails instead of looking fine.
		mihomoExecutor.Shutdown()
		mihomoCloseListeners()
		mihomoReleaseState()
		close(running.mihomoLog)
		running.mihomoLog = nil
		return E.New("mihomo TUN did not start; see the log")
	}
	return nil
}

// mihomoRemoteProviders counts the profile's http proxy and rule providers.
func mihomoRemoteProviders(raw *mihomoConfig.RawConfig) int {
	count := 0
	for _, providers := range []map[string]map[string]any{raw.ProxyProvider, raw.RuleProvider} {
		for _, provider := range providers {
			if kind, _ := provider["type"].(string); kind == "http" {
				count++
			}
		}
	}
	return count
}

// countingMihomoDNS counts the queries mihomo's dns-hijack answers: the TUN
// relays them through resolver.ServeMsg, i.e. resolver.DefaultService, which
// ApplyConfig has just replaced (or cleared when DNS is disabled).
type countingMihomoDNS struct{ mihomoResolver.Service }

func (c countingMihomoDNS) ServeMsg(ctx context.Context, msg *mDNS.Msg) (*mDNS.Msg, error) {
	uiOtherDNSQueries.Add(1)
	return c.Service.ServeMsg(ctx, msg)
}

func countMihomoDNS() {
	service := mihomoResolver.DefaultService
	if _, counted := service.(countingMihomoDNS); service != nil && !counted {
		mihomoResolver.DefaultService = countingMihomoDNS{service}
	}
}
