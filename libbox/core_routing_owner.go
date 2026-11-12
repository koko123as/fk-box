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
	E "github.com/sagernet/sing/common/exceptions"
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
// profile on disk is not touched. A routing problem that leaves the profile
// unusable is reported as a warning and the profile starts without the rules.
func (s *CommandServer) StartOrReloadCoreServiceWithRouting(core string, configContent string, rules string, options *OverrideOptions) error {
	if core == "" {
		core = CoreSingBox
	}
	result, routingErr := ApplyCoreRouting(core, configContent, rules)
	content := configContent
	if routingErr == nil {
		content = result.Content
	}
	if err := s.StartOrReloadCoreService(core, content, options); err != nil {
		if routingErr == nil && result.Active {
			return E.Cause(err, "with routing rules")
		}
		return err
	}
	label := map[string]string{CoreSingBox: "sing-box", CoreXray: "Xray", CoreMihomo: "mihomo"}[core]
	if routingErr != nil {
		s.StartedService.WriteMessage(log.LevelError, "分流规则未应用："+routingErr.Error())
		return nil
	}
	if result.Active {
		s.StartedService.WriteMessage(log.LevelInfo, fmt.Sprintf("已应用 %s 分流规则 %d 条", label, result.Applied))
	}
	for _, warning := range strings.Split(result.Warnings, "\n") {
		if warning != "" {
			s.StartedService.WriteMessage(log.LevelWarn, "分流规则："+warning)
		}
	}
	return nil
}
