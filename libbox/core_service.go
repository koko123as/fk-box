package libbox

// Phase 2: run Xray or mihomo in place of sing-box inside the same
// CommandServer. Only one core runs at a time. The VPN TUN is still opened by
// the app's VpnService (PlatformInterface.OpenTun) and its fd is handed to the
// running core directly; every outbound socket of Xray and mihomo is passed to
// PlatformInterface.AutoDetectInterfaceControl, which calls VpnService.protect.
//
// References:
//   - Xray Android TUN: xray-core proxy/tun (tun_android.go reads xray.tun.fd)
//   - Xray protect hook: libXray controller/controller_android.go
//   - mihomo fd TUN and socket hook: mihomo listener/sing_tun (file-descriptor),
//     component/dialer.DefaultSocketHook, and ClashMetaForAndroid's use of the
//     cmfa build tag.

import (
	"encoding/json"
	"fmt"
	"net/netip"
	"os"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	mihomoDialer "github.com/metacubex/mihomo/component/dialer"
	mihomoConfig "github.com/metacubex/mihomo/config"
	mihomoConstant "github.com/metacubex/mihomo/constant"
	mihomoExecutor "github.com/metacubex/mihomo/hub/executor"
	mihomoLog "github.com/metacubex/mihomo/log"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	tun "github.com/sagernet/sing-tun"
	E "github.com/sagernet/sing/common/exceptions"
	xrayLogApp "github.com/xtls/xray-core/app/log"
	xrayCommonLog "github.com/xtls/xray-core/common/log"
	xrayPlatform "github.com/xtls/xray-core/common/platform"
	xrayCore "github.com/xtls/xray-core/core"
	xraySerial "github.com/xtls/xray-core/infra/conf/serial"
	xrayInternet "github.com/xtls/xray-core/transport/internet"
)

// The TUN every non-sing-box core gets. sing-box keeps using its own profile's
// tun inbound.
var (
	coreTunInet4 = netip.MustParsePrefix("172.19.0.1/30")
	coreTunInet6 = netip.MustParsePrefix("fdfe:dcba:9876::1/126")
	coreTunDNS   = "172.19.0.2"
)

const coreTunMTU = 9000

type runningCore struct {
	core      string
	xray      *xrayCore.Instance
	mihomoLog chan struct{}
	tunFd     int
	startedAt time.Time

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
	coreAccess    sync.Mutex
	activeCore    *runningCore
	coreProtector atomic.Pointer[PlatformInterface]
	coreLogSink   atomic.Pointer[CommandServer]
)

func init() {
	// Both hooks are process-global and cannot be unregistered, so they are
	// installed once and look up the current platform interface each time.
	_ = xrayInternet.RegisterDialerController(func(network, address string, conn syscall.RawConn) error {
		return protectCoreSocket(conn)
	})
	mihomoDialer.DefaultSocketHook = func(network, address string, conn syscall.RawConn) error {
		return protectCoreSocket(conn)
	}
	// Xray's console log goes to the service log instead of stdout.
	_ = xrayLogApp.RegisterHandlerCreator(xrayLogApp.LogType_Console, func(xrayLogApp.LogType, xrayLogApp.HandlerCreatorOptions) (xrayCommonLog.Handler, error) {
		return xrayLogHandler{}, nil
	})
}

func protectCoreSocket(conn syscall.RawConn) error {
	platform := coreProtector.Load()
	if platform == nil {
		return nil
	}
	var protectErr error
	err := conn.Control(func(fd uintptr) {
		protectErr = (*platform).AutoDetectInterfaceControl(int32(fd))
	})
	if err != nil {
		return err
	}
	return protectErr
}

type xrayLogHandler struct{}

func (xrayLogHandler) Handle(message xrayCommonLog.Message) {
	server := coreLogSink.Load()
	if server == nil {
		return
	}
	level := log.LevelInfo
	if general, ok := message.(*xrayCommonLog.GeneralMessage); ok {
		switch general.Severity {
		case xrayCommonLog.Severity_Error:
			level = log.LevelError
		case xrayCommonLog.Severity_Warning:
			level = log.LevelWarn
		case xrayCommonLog.Severity_Debug:
			level = log.LevelDebug
		}
	}
	server.StartedService.WriteMessage(level, "[xray] "+message.String())
}

// RunningCore reports which core is running: sing-box, xray, mihomo or "".
func (s *CommandServer) RunningCore() string {
	coreAccess.Lock()
	defer coreAccess.Unlock()
	if activeCore != nil {
		return activeCore.core
	}
	if s.StartedService.Instance() != nil {
		return CoreSingBox
	}
	return ""
}

// StartOrReloadCoreService starts the profile on its core, stopping whichever
// core ran before. sing-box goes through StartOrReloadService unchanged.
func (s *CommandServer) StartOrReloadCoreService(core string, configContent string, options *OverrideOptions) error {
	if core == CoreSingBox || core == "" {
		s.stopOtherCore()
		return s.StartOrReloadService(dropUnbuiltTunStack(configContent), options)
	}
	if core != CoreXray && core != CoreMihomo {
		return fmt.Errorf("unknown core: %s", core)
	}
	if err := CheckCoreConfig(core, configContent); err != nil {
		return err
	}
	// Stop sing-box (if it was running) before its TUN is replaced.
	_ = s.CloseService()
	s.stopOtherCore()

	coreAccess.Lock()
	defer coreAccess.Unlock()
	platform := s.platformInterface
	coreProtector.Store(&platform)
	coreLogSink.Store(s)
	fd, err := s.openCoreTun(options)
	if err != nil {
		return E.Cause(err, "open tun")
	}
	running := &runningCore{core: core, tunFd: fd}
	switch core {
	case CoreXray:
		err = startXrayCore(running, configContent)
	case CoreMihomo:
		err = startMihomoCore(s, running, configContent)
	}
	if err != nil {
		_ = syscall.Close(fd)
		return E.Cause(err, "start ", core)
	}
	running.startedAt = time.Now()
	activeCore = running
	s.StartedService.WriteMessage(log.LevelInfo, core+" started")
	go notifyCoreChange()
	return nil
}

// CloseCoreService stops whichever core is running.
func (s *CommandServer) CloseCoreService() error {
	s.stopOtherCore()
	return s.CloseService()
}

func (s *CommandServer) stopOtherCore() {
	coreAccess.Lock()
	defer coreAccess.Unlock()
	running := activeCore
	if running == nil {
		return
	}
	activeCore = nil
	switch running.core {
	case CoreXray:
		if running.xray != nil {
			_ = running.xray.Close()
		}
	case CoreMihomo:
		mihomoExecutor.Shutdown()
		if running.mihomoLog != nil {
			close(running.mihomoLog)
		}
	}
	// Neither core closes the fd it was given; this is our dup of the
	// VpnService descriptor, which the app closes itself.
	_ = syscall.Close(running.tunFd)
	s.StartedService.WriteMessage(log.LevelInfo, running.core+" stopped")
	go notifyCoreChange()
}

func (s *CommandServer) openCoreTun(options *OverrideOptions) (int, error) {
	opts := tun.Options{
		Inet4Address: []netip.Prefix{coreTunInet4},
		Inet6Address: []netip.Prefix{coreTunInet6},
		MTU:          coreTunMTU,
		AutoRoute:    true,
		DNSMode:      tun.DNSModeHijack,
	}
	if options != nil {
		opts.IncludePackage = iteratorToArray(options.IncludePackage)
		opts.ExcludePackage = iteratorToArray(options.ExcludePackage)
	}
	routeRanges, err := opts.BuildAutoRouteRanges(true)
	if err != nil {
		return -1, err
	}
	tunFd, err := s.platformInterface.OpenTun(&tunOptions{&opts, routeRanges, option.TunPlatformOptions{}})
	if err != nil {
		return -1, err
	}
	if name, err := getTunnelName(tunFd); err == nil {
		s.platformInterface.RegisterMyInterface(name)
	}
	return dup(int(tunFd))
}

// Xray: the profile's inbounds are replaced by the TUN inbound; DNS sent to
// the TUN's DNS address goes to a dns outbound.
func startXrayCore(running *runningCore, content string) error {
	var root map[string]any
	if err := json.Unmarshal([]byte(normalizeCoreContent(content)), &root); err != nil {
		return err
	}
	root["inbounds"] = []any{map[string]any{
		"tag":      "tun-in",
		"protocol": "tun",
		"settings": map[string]any{"name": "xray0", "MTU": coreTunMTU},
		"sniffing": map[string]any{"enabled": true, "destOverride": []string{"http", "tls", "quic"}},
	}}
	outbounds, _ := root["outbounds"].([]any)
	hasDNS := false
	for _, item := range outbounds {
		if outbound, ok := item.(map[string]any); ok && outbound["tag"] == "dns-out" {
			hasDNS = true
		}
	}
	if !hasDNS {
		root["outbounds"] = append(outbounds, map[string]any{"tag": "dns-out", "protocol": "dns"})
	}
	routing, _ := root["routing"].(map[string]any)
	if routing == nil {
		routing = map[string]any{}
	}
	rules, _ := routing["rules"].([]any)
	routing["rules"] = append([]any{map[string]any{
		"inboundTag": []string{"tun-in"}, "port": "53", "outboundTag": "dns-out",
	}}, rules...)
	root["routing"] = routing
	logConfig, _ := root["log"].(map[string]any)
	if logConfig == nil {
		logConfig = map[string]any{"loglevel": "warning"}
	}
	// Console output goes to the service log; files are not writable here.
	delete(logConfig, "access")
	delete(logConfig, "error")
	root["log"] = logConfig
	encoded, err := json.Marshal(root)
	if err != nil {
		return err
	}
	_ = os.Setenv(xrayPlatform.TunFdKey, strconv.Itoa(running.tunFd))
	_ = os.Setenv("xray.location.asset", coreAssetDir(sWorkingPath, CoreXray))
	config, err := xraySerial.LoadJSONConfig(strings.NewReader(string(encoded)))
	if err != nil {
		return err
	}
	instance, err := xrayCore.New(config)
	if err != nil {
		return err
	}
	if err := instance.Start(); err != nil {
		_ = instance.Close()
		return err
	}
	running.xray = instance
	readXrayLayout(running, root)
	return nil
}

// readXrayLayout records outbound tags and balancers (selectors are tag
// prefixes, as Xray matches them) for the groups view.
func readXrayLayout(running *runningCore, root map[string]any) {
	running.xrayProtocols = map[string]string{}
	outbounds, _ := root["outbounds"].([]any)
	for _, item := range outbounds {
		outbound, _ := item.(map[string]any)
		tag, _ := outbound["tag"].(string)
		protocol, _ := outbound["protocol"].(string)
		if tag == "" {
			continue
		}
		running.xrayProtocols[tag] = protocol
		if protocol != "dns" && protocol != "blackhole" && protocol != "freedom" {
			running.xrayOutboundTags = append(running.xrayOutboundTags, tag)
		}
	}
	routing, _ := root["routing"].(map[string]any)
	balancers, _ := routing["balancers"].([]any)
	for _, item := range balancers {
		balancer, _ := item.(map[string]any)
		tag, _ := balancer["tag"].(string)
		if tag == "" {
			continue
		}
		strategy := "random"
		if config, ok := balancer["strategy"].(map[string]any); ok {
			if kind, ok := config["type"].(string); ok && kind != "" {
				strategy = kind
			}
		}
		selectors, _ := balancer["selector"].([]any)
		var members []string
		for _, outboundTag := range running.xrayOutboundTags {
			for _, selector := range selectors {
				if prefix, ok := selector.(string); ok && strings.HasPrefix(outboundTag, prefix) {
					members = append(members, outboundTag)
					break
				}
			}
		}
		running.xrayBalancers = append(running.xrayBalancers, xrayBalancer{tag: tag, strategy: strategy, members: members})
	}
}

// mihomo: the profile's tun section is replaced by an fd TUN without
// auto-route or interface detection (the VpnService owns routing, and netlink
// is not available to apps on recent Android). External controllers are
// turned off; the app reads mihomo in process.
func startMihomoCore(s *CommandServer, running *runningCore, content string) error {
	mihomoConstant.SetHomeDir(coreAssetDir(sWorkingPath, CoreMihomo))
	raw, err := mihomoConfig.UnmarshalRawConfig([]byte(normalizeCoreContent(content)))
	if err != nil {
		return err
	}
	raw.Tun.Enable = true
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
				level := log.LevelInfo
				switch event.LogLevel {
				case mihomoLog.ERROR:
					level = log.LevelError
				case mihomoLog.WARNING:
					level = log.LevelWarn
				case mihomoLog.DEBUG:
					level = log.LevelDebug
				}
				s.StartedService.WriteMessage(level, "[mihomo] "+event.Payload)
			}
		}
	}(running.mihomoLog)
	mihomoExecutor.ApplyConfig(config, true)
	return nil
}

