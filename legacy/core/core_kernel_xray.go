package libbox

// Xray as a kernel: it runs on fk-box's TUN (the descriptor goes to Xray's
// tun inbound through xray.tun.fd), every outbound socket is protected
// through the host, and its console log goes to the service log.
//
// References: Xray Android TUN, xray-core proxy/tun (tun_android.go reads
// xray.tun.fd); the protect hook, libXray controller/controller_android.go.

import (
	"encoding/json"
	"fmt"
	"os"
	"strconv"
	"strings"
	"syscall"

	xrayLogApp "github.com/xtls/xray-core/app/log"
	xrayCommonLog "github.com/xtls/xray-core/common/log"
	xrayPlatform "github.com/xtls/xray-core/common/platform"
	xrayCore "github.com/xtls/xray-core/core"
	xraySerial "github.com/xtls/xray-core/infra/conf/serial"
	xrayInternet "github.com/xtls/xray-core/transport/internet"
)

type xrayKernel struct{}

func (xrayKernel) Name() string { return CoreXray }

func (xrayKernel) NeedsTun() bool { return true }

func (xrayKernel) Prepare(content string) (any, error) {
	return nil, CheckCoreConfig(CoreXray, content)
}

func (xrayKernel) Start(host kernelHost, running *runningCore, content string, _ any, _ kernelOptions) error {
	return startXrayCore(host, running, content)
}

// Stop closes the instance; Xray never closes the TUN descriptor it reads.
func (xrayKernel) Stop(_ kernelHost, running *runningCore) {
	if running.xray != nil {
		_ = running.xray.Close()
	}
}

// DefaultNetworkChanged closes the tracked connections, which are still bound
// to the old network and would hang until their own timeouts.
func (xrayKernel) DefaultNetworkChanged(running *runningCore) {
	if running.xrayTrack != nil {
		running.xrayTrack.closeAll()
	}
}

func init() {
	// Process-global and never unregistered: installed once, it protects
	// through the host of the kernel started last.
	_ = xrayInternet.RegisterDialerController(func(network, address string, conn syscall.RawConn) error {
		return protectKernelSocket(conn)
	})
	// Xray's console log goes to the service log instead of stdout.
	_ = xrayLogApp.RegisterHandlerCreator(xrayLogApp.LogType_Console, func(xrayLogApp.LogType, xrayLogApp.HandlerCreatorOptions) (xrayCommonLog.Handler, error) {
		return xrayLogHandler{}, nil
	})
}

type xrayLogHandler struct{}

func (xrayLogHandler) Handle(message xrayCommonLog.Message) {
	host := kernelHostInUse.Load()
	if host == nil || host.Log == nil {
		return
	}
	level := hostLogInfo
	if general, ok := message.(*xrayCommonLog.GeneralMessage); ok {
		switch general.Severity {
		case xrayCommonLog.Severity_Error:
			level = hostLogError
		case xrayCommonLog.Severity_Warning:
			level = hostLogWarn
		case xrayCommonLog.Severity_Debug:
			level = hostLogDebug
		}
	}
	host.Log.Write(level, "[xray] "+message.String())
}

// Xray: the profile's inbounds are replaced by the TUN inbound; DNS sent to
// the TUN's DNS address goes to a dns outbound. xrayStartRoot builds the JSON
// the core is started with (also used by the routing tests, which start the
// same configuration with xrayCore.New).
func xrayStartRoot(content string) (map[string]any, map[string]any, error) {
	var root map[string]any
	if err := json.Unmarshal([]byte(normalizeCoreContent(content)), &root); err != nil {
		return nil, nil, err
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
	logConfig := xrayLogConfigWithDefault(root)
	// Console output goes to the service log; files are not writable here.
	xrayApplyAccessLog(logConfig)
	delete(logConfig, "error")
	root["log"] = logConfig
	return root, logConfig, nil
}

func startXrayCore(host kernelHost, running *runningCore, content string) error {
	root, logConfig, err := xrayStartRoot(content)
	if err != nil {
		return err
	}
	encoded, err := json.Marshal(root)
	if err != nil {
		return err
	}
	_ = os.Setenv(xrayPlatform.TunFdKey, strconv.Itoa(running.tunFd))
	// geoip.dat / geosite.dat: the shared geo directory (core_geo.go).
	_ = os.Setenv("xray.location.asset", GeoDataDir())
	config, err := xraySerial.LoadJSONConfig(strings.NewReader(string(encoded)))
	if err != nil {
		return err
	}
	instance, err := xrayCore.New(config)
	if err != nil {
		return err
	}
	// Connection list and traffic: wrap the TUN inbound's dispatcher first.
	if tracker, err := installXrayTracker(instance, "tun-in"); err == nil {
		running.xrayTrack = tracker
	} else {
		running.xrayTrack = newXrayTracker()
		host.Log.Diagnostic(fmt.Sprint("xray connection tracking unavailable: ", err))
	}
	if err := instance.Start(); err != nil {
		_ = instance.Close()
		return err
	}
	running.xray = instance
	running.logLevel = xrayDaemonLogLevel(fmt.Sprint(logConfig["loglevel"]))
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
