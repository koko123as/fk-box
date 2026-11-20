//go:build android

package libbox

// Android's fkhost platform calls the app's platform implementation: the
// PlatformInterface object the app gives this CommandServer (VPNService.kt),
// the same one sing-box uses while it still runs inside libbox.

import (
	"net/netip"

	"github.com/sagernet/sing-box/experimental/fkhost"
	"github.com/sagernet/sing-box/option"
	tun "github.com/sagernet/sing-tun"
)

func (s *CommandServer) hostPlatform() fkhost.Platform { return fkhost.NewPlatform(androidVPN{s}) }

type androidVPN struct{ server *CommandServer }

func (v androidVPN) OpenTun(options *tun.Options, routeRanges []netip.Prefix) (int32, error) {
	platform := v.server.platformInterface
	tunFd, err := platform.OpenTun(&tunOptions{options, routeRanges, option.TunPlatformOptions{}})
	if err != nil {
		return -1, err
	}
	if name, err := getTunnelName(tunFd); err == nil {
		platform.RegisterMyInterface(name)
	}
	return tunFd, nil
}

func (v androidVPN) Protect(fd int32) error {
	if platform := v.server.platformInterface; platform != nil {
		return platform.AutoDetectInterfaceControl(fd)
	}
	return nil
}

func (v androidVPN) FindConnectionOwner(protocol int32, sourceAddress string, sourcePort int32, destinationAddress string, destinationPort int32) (*fkhost.ConnectionOwner, bool, error) {
	platform := v.server.platformInterface
	if platform == nil {
		return nil, false, nil
	}
	owner, err := platform.FindConnectionOwner(protocol, sourceAddress, sourcePort, destinationAddress, destinationPort)
	if err != nil {
		return nil, true, err
	}
	if owner == nil {
		return nil, true, nil
	}
	return &fkhost.ConnectionOwner{PackageNames: owner.androidPackageNames, UserName: owner.UserName}, true, nil
}
