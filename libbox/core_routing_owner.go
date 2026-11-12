package libbox

// App matching for mihomo and the start entry that applies routing rules.
//
// mihomo built with the cmfa tag (as here) does not read /proc for process
// names; it asks process.DefaultPackageNameResolver, which ClashMetaForAndroid
// implements with ConnectivityManager.getConnectionOwnerUid and the package
// manager. libbox answers it the same way through
// PlatformInterface.FindConnectionOwner, which the app already implements for
// sing-box's package_name rules, so PROCESS-NAME,<package> matches.

import (
	"fmt"
	"net/netip"
	"strings"
	"syscall"

	mihomoProcess "github.com/metacubex/mihomo/component/process"
	mihomoConstant "github.com/metacubex/mihomo/constant"
	"github.com/sagernet/sing-box/log"
)

func init() {
	mihomoProcess.DefaultPackageNameResolver = mihomoPackageName
}

func mihomoAddrPort(raw interface{ String() string }, ip netip.Addr, port uint16) (netip.Addr, uint16) {
	if raw != nil {
		if parsed, err := netip.ParseAddrPort(raw.String()); err == nil {
			return parsed.Addr().Unmap(), parsed.Port()
		}
	}
	return ip.Unmap(), port
}

func mihomoPackageName(metadata *mihomoConstant.Metadata) (string, error) {
	platform := coreProtector.Load()
	if platform == nil || *platform == nil {
		return "", mihomoProcess.ErrPlatformNotSupport
	}
	protocol := int32(syscall.IPPROTO_TCP)
	if metadata.NetWork == mihomoConstant.UDP {
		protocol = syscall.IPPROTO_UDP
	}
	// The addresses the app's socket used: the TUN-side source and the
	// original (possibly fake-ip) destination, before mihomo rewrote them.
	var rawSrc, rawDst interface{ String() string }
	if metadata.RawSrcAddr != nil {
		rawSrc = metadata.RawSrcAddr
	}
	if metadata.RawDstAddr != nil {
		rawDst = metadata.RawDstAddr
	}
	src, srcPort := mihomoAddrPort(rawSrc, metadata.SrcIP, metadata.SrcPort)
	dst, dstPort := mihomoAddrPort(rawDst, metadata.DstIP, metadata.DstPort)
	if !src.IsValid() || !dst.IsValid() {
		return "", mihomoProcess.ErrNotFound
	}
	owner, err := (*platform).FindConnectionOwner(protocol, src.String(), int32(srcPort), dst.String(), int32(dstPort))
	if err != nil {
		return "", err
	}
	if owner == nil {
		return "", mihomoProcess.ErrNotFound
	}
	if len(owner.androidPackageNames) > 0 {
		return owner.androidPackageNames[0], nil
	}
	if owner.UserName != "" {
		return owner.UserName, nil
	}
	return "", mihomoProcess.ErrNotFound
}

// StartOrReloadCoreServiceWithRouting applies the routing rules of the core
// that runs (rules: that core's rule set as JSON, "" for none) to the content
// handed to the core, then starts it like StartOrReloadCoreService. The
// profile on disk is not touched.
//
// The routing rules never keep a profile from running: when they cannot be
// compiled, or the core refuses to start with them (for example a remote
// sing-box rule-set whose first download fails, which sing-box treats as a
// start error in route/rule/rule_set_remote.go), the profile is started as it
// is and the returned result carries the reason in Error (Fallback is set when
// the core was started a second time without the rules). Only an error of the
// profile itself is returned as an error. The app shows Error and Warnings on
// the routing page and as a notice.
func (s *CommandServer) StartOrReloadCoreServiceWithRouting(core string, configContent string, rules string, options *OverrideOptions) (*CoreRoutingResult, error) {
	if core == "" {
		core = CoreSingBox
	}
	label := map[string]string{CoreSingBox: "sing-box", CoreXray: "Xray", CoreMihomo: "mihomo"}[core]
	result, routingErr := ApplyCoreRouting(core, configContent, rules)
	if routingErr != nil {
		if err := s.StartOrReloadCoreService(core, configContent, options); err != nil {
			return nil, err
		}
		failed := &CoreRoutingResult{Content: configContent, Error: routingErr.Error()}
		s.StartedService.WriteMessage(log.LevelError, "分流规则未应用："+failed.Error)
		return failed, nil
	}
	if !result.Active {
		if err := s.StartOrReloadCoreService(core, configContent, options); err != nil {
			return nil, err
		}
		s.writeRoutingWarnings(result)
		return result, nil
	}
	if startErr := s.StartOrReloadCoreService(core, result.Content, options); startErr != nil {
		if err := s.StartOrReloadCoreService(core, configContent, options); err != nil {
			// The profile does not start without the rules either: its own error.
			return nil, err
		}
		reason := startErr.Error()
		if routingIsRuleSetDownload(reason) {
			reason = "a remote rule-set could not be downloaded (" + reason + "); the rules are applied again at the next start or reload"
		} else {
			reason = label + " did not start with the rules: " + reason
		}
		fallback := &CoreRoutingResult{Content: configContent, Warnings: result.Warnings, Error: reason, Fallback: true}
		s.StartedService.WriteMessage(log.LevelError, "分流规则未应用，已按原配置启动："+reason)
		s.writeRoutingWarnings(fallback)
		return fallback, nil
	}
	s.StartedService.WriteMessage(log.LevelInfo, fmt.Sprintf("已应用 %s 分流规则 %d 条", label, result.Applied))
	s.writeRoutingWarnings(result)
	return result, nil
}

// routingIsRuleSetDownload recognises sing-box's start error for a remote
// rule-set whose initial download failed ("initial rule-set: <tag>: ...").
func routingIsRuleSetDownload(message string) bool {
	return strings.Contains(message, "initial rule-set")
}

func (s *CommandServer) writeRoutingWarnings(result *CoreRoutingResult) {
	for _, warning := range strings.Split(result.Warnings, "\n") {
		if warning != "" {
			s.StartedService.WriteMessage(log.LevelWarn, "分流规则："+warning)
		}
	}
}
