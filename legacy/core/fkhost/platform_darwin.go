//go:build darwin

package fkhost

// macOS and iOS: the TUN belongs to the app's NetworkExtension
// (NEPacketTunnelProvider). The extension applies the tunnel settings and
// gives fk-box the utun descriptor behind its packet flow through
// PacketTunnel; fk-box's own sockets are bound to the physical default
// interface (IP_BOUND_IF / IPV6_BOUND_IF) so they bypass the tunnel.

import (
	"errors"
	"net/netip"
	"sync"
	"syscall"

	"golang.org/x/sys/unix"
)

// PacketTunnel is the NetworkExtension side, implemented by the app.
type PacketTunnel interface {
	// OpenTun applies request (addresses, MTU, default routes) as the
	// tunnel's network settings and returns the utun descriptor of the
	// packet flow; the extension keeps it.
	OpenTun(request TunRequest) (int32, error)
	// DefaultInterfaceIndex is the index of the physical default interface,
	// 0 when there is none.
	DefaultInterfaceIndex() int32
}

var (
	packetTunnelAccess sync.RWMutex
	packetTunnel       PacketTunnel
)

// SetPacketTunnel installs (nil removes) the NetworkExtension's tunnel.
func SetPacketTunnel(tunnel PacketTunnel) {
	packetTunnelAccess.Lock()
	packetTunnel = tunnel
	packetTunnelAccess.Unlock()
}

func currentPacketTunnel() PacketTunnel {
	packetTunnelAccess.RLock()
	defer packetTunnelAccess.RUnlock()
	return packetTunnel
}

// ErrNoPacketTunnel is returned before the extension installed its tunnel.
var ErrNoPacketTunnel = errors.New("darwin: no NetworkExtension packet tunnel (SetPacketTunnel)")

// NewPlatform is the Apple platform.
func NewPlatform() Platform { return darwinPlatform{} }

type darwinPlatform struct{}

func (darwinPlatform) OpenTun(request TunRequest) (int, error) {
	tunnel := currentPacketTunnel()
	if tunnel == nil {
		return -1, ErrNoPacketTunnel
	}
	fd, err := tunnel.OpenTun(request)
	if err != nil {
		return -1, err
	}
	// The extension keeps its descriptor; the kernel gets its own.
	return unix.Dup(int(fd))
}

func (darwinPlatform) Protect(conn syscall.RawConn) error {
	tunnel := currentPacketTunnel()
	if tunnel == nil {
		return nil
	}
	index := int(tunnel.DefaultInterfaceIndex())
	if index == 0 {
		return nil
	}
	return protectRawConn(conn, func(fd int) error {
		err := unix.SetsockoptInt(fd, unix.IPPROTO_IP, unix.IP_BOUND_IF, index)
		if err == nil {
			return nil
		}
		// An IPv6 socket takes the IPv6 option.
		return unix.SetsockoptInt(fd, unix.IPPROTO_IPV6, unix.IPV6_BOUND_IF, index)
	})
}

func (darwinPlatform) ConnectionOwner(int32, netip.AddrPort, netip.AddrPort) (*ConnectionOwner, bool, error) {
	return nil, false, nil
}
