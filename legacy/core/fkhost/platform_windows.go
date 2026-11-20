//go:build windows

package fkhost

// Windows: the TUN is a wintun adapter, whose packets are read through a
// wintun session rather than a file descriptor. fk-box's Xray and mihomo
// adapters take a descriptor (the Android VpnService and Linux model), so
// OpenTun is not available here yet; sing-box, which opens wintun itself from
// its tun inbound, runs in the standalone host. See core/README.md.

import (
	"errors"
	"net/netip"
	"syscall"
)

// ErrWintunNeedsSession says why the Windows platform opens no TUN yet.
var ErrWintunNeedsSession = errors.New("windows: wintun has no descriptor; Xray and mihomo need a session adapter (not implemented)")

// NewPlatform is the Windows platform.
func NewPlatform() Platform { return windowsPlatform{} }

type windowsPlatform struct{}

func (windowsPlatform) OpenTun(TunRequest) (int, error) { return -1, ErrWintunNeedsSession }

// Protect has nothing to do while fk-box routes nothing into a TUN here.
func (windowsPlatform) Protect(syscall.RawConn) error { return nil }

func (windowsPlatform) ConnectionOwner(int32, netip.AddrPort, netip.AddrPort) (*ConnectionOwner, bool, error) {
	return nil, false, nil
}
