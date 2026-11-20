//go:build !android

package fkhost

import (
	"net/netip"
	"syscall"
)

// NewPlatform is the platform of a system fk-box does not open a TUN on yet.
func NewPlatform() Platform { return unsupportedPlatform{} }

type unsupportedPlatform struct{}

func (unsupportedPlatform) OpenTun(TunRequest) (int, error) { return -1, ErrUnsupported }

func (unsupportedPlatform) Protect(syscall.RawConn) error { return nil }

func (unsupportedPlatform) ConnectionOwner(int32, netip.AddrPort, netip.AddrPort) (*ConnectionOwner, bool, error) {
	return nil, false, nil
}
