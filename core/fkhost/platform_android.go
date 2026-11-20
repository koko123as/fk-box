//go:build android

package fkhost

import (
	"net/netip"
	"syscall"

	tun "github.com/sagernet/sing-tun"
)

// AndroidVPN is the app's VpnService side, called straight from the app's
// platform implementation (libbox adapts the PlatformInterface object the
// app already gives sing-box; see core_host_android.go).
type AndroidVPN interface {
	// OpenTun builds the VPN interface from options (addresses, MTU, routes,
	// per-app lists) with VpnService.Builder and returns its descriptor,
	// which the service keeps.
	OpenTun(options *tun.Options, routeRanges []netip.Prefix) (int32, error)
	// Protect is VpnService.protect.
	Protect(fd int32) error
	// FindConnectionOwner is ConnectivityManager.getConnectionOwnerUid and
	// the package manager. ok is false when the app gives no owners.
	FindConnectionOwner(protocol int32, sourceAddress string, sourcePort int32, destinationAddress string, destinationPort int32) (owner *ConnectionOwner, ok bool, err error)
}

// NewPlatform is Android's platform: the VpnService's TUN, protect and
// connection owners.
func NewPlatform(vpn AndroidVPN) Platform { return androidPlatform{vpn} }

type androidPlatform struct{ vpn AndroidVPN }

func (p androidPlatform) OpenTun(request TunRequest) (int, error) {
	options := tun.Options{
		Inet4Address:   []netip.Prefix{request.Inet4},
		Inet6Address:   []netip.Prefix{request.Inet6},
		MTU:            request.MTU,
		AutoRoute:      true,
		DNSMode:        tun.DNSModeHijack,
		IncludePackage: request.IncludePackage,
		ExcludePackage: request.ExcludePackage,
	}
	routeRanges, err := options.BuildAutoRouteRanges(true)
	if err != nil {
		return -1, err
	}
	fd, err := p.vpn.OpenTun(&options, routeRanges)
	if err != nil {
		return -1, err
	}
	// The VpnService keeps its descriptor; the kernel gets its own.
	return syscall.Dup(int(fd))
}

func (p androidPlatform) Protect(conn syscall.RawConn) error {
	return protectRawConn(conn, func(fd int) error { return p.vpn.Protect(int32(fd)) })
}

func (p androidPlatform) ConnectionOwner(protocol int32, source, destination netip.AddrPort) (*ConnectionOwner, bool, error) {
	return p.vpn.FindConnectionOwner(protocol, source.Addr().String(), int32(source.Port()), destination.Addr().String(), int32(destination.Port()))
}
