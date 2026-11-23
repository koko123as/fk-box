// Package stage2 checks the sing-box configurations the Rust brain generates
// (crates/client-core/src/config/brain) against sing-box 1.15.0-alpha.13:
// the fkbrain, fkdns, mihomo and xray types are registered through the
// public registries as option-only placeholders, the way the stage 0 probe
// registered its plugins, and every configuration is parsed and created
// with box.New. Nothing in the upstream trees is patched.
package stage2

import (
	"context"
	"net/netip"
	"os"
	"sync"
	"sync/atomic"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/option"
	tun "github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/logger"
)

// Platform stands in for the Android host. AutoDetectInterfaceControl is
// the hook libbox forwards to VpnService.protect(fd); here it only records the
// descriptors so a test can prove a socket went through sing-box's protected
// dialer.
type Platform struct {
	protected atomic.Int64
	access    sync.Mutex
	fds       []int
}

var _ adapter.PlatformInterface = (*Platform)(nil)

func (p *Platform) Protected() int64 { return p.protected.Load() }

func (p *Platform) Initialize(networkManager adapter.NetworkManager) error { return nil }

func (p *Platform) UsePlatformAutoDetectInterfaceControl() bool { return true }

func (p *Platform) AutoDetectInterfaceControl(fd int) error {
	p.protected.Add(1)
	p.access.Lock()
	p.fds = append(p.fds, fd)
	p.access.Unlock()
	return nil
}

func (p *Platform) UsePlatformInterface() bool { return false }

func (p *Platform) OpenInterface(options *tun.Options, platformOptions option.TunPlatformOptions) (tun.Tun, error) {
	return nil, os.ErrInvalid
}

func (p *Platform) ProcessPlatformOptions(options option.TunPlatformOptions) error { return nil }

func (p *Platform) UsePlatformDefaultInterfaceMonitor() bool { return false }

func (p *Platform) CreateDefaultInterfaceMonitor(logger logger.Logger) tun.DefaultInterfaceMonitor {
	return nil
}

func (p *Platform) UsePlatformNetworkInterfaces() bool { return false }

func (p *Platform) NetworkInterfaces() ([]adapter.NetworkInterface, error) {
	return nil, os.ErrInvalid
}

func (p *Platform) UnderNetworkExtension() bool { return false }

func (p *Platform) NetworkExtensionIncludeAllNetworks() bool { return false }

func (p *Platform) ClearDNSCache() {}

func (p *Platform) RequestPermissionForWIFIState() error { return nil }

func (p *Platform) ReadWIFIState(ctx context.Context) adapter.WIFIState {
	return adapter.WIFIState{}
}

func (p *Platform) UsePlatformConnectionOwnerFinder() bool { return false }

func (p *Platform) FindConnectionOwner(request *adapter.FindConnectionOwnerRequest) (*adapter.ConnectionOwner, error) {
	return nil, os.ErrInvalid
}

func (p *Platform) UsePlatformWIFIMonitor() bool { return false }

func (p *Platform) UsePlatformNotification() bool { return false }

func (p *Platform) SendNotification(notification *adapter.Notification) error { return nil }

func (p *Platform) CancelNotification(identifier string, typeID int32) error { return nil }

func (p *Platform) MyInterfaceAddress() []netip.Addr { return nil }

func (p *Platform) UsePlatformNeighborResolver() bool { return false }

func (p *Platform) StartNeighborMonitor(listener adapter.NeighborUpdateListener) error {
	return os.ErrInvalid
}

func (p *Platform) CloseNeighborMonitor(listener adapter.NeighborUpdateListener) error {
	return nil
}

func (p *Platform) UsePlatformShell() bool { return false }

func (p *Platform) CheckPlatformShell() error { return os.ErrInvalid }

func (p *Platform) OpenShellSession(user *adapter.PlatformUser, command string, env []string, term string, rows int32, cols int32) (adapter.ShellSession, error) {
	return nil, os.ErrInvalid
}

func (p *Platform) LookupUser(username string) (*adapter.PlatformUser, error) {
	return nil, os.ErrInvalid
}

func (p *Platform) LookupSFTPServer() (string, error) { return "", os.ErrInvalid }

func (p *Platform) ReadSystemSSHHostKey() ([]byte, error) { return nil, os.ErrInvalid }

func (p *Platform) TailscaleHostname() string { return "" }

func (p *Platform) UsePlatformBridge() bool { return false }

func (p *Platform) CreateBridge(options adapter.BridgeOptions) (adapter.BridgeSession, error) {
	return nil, os.ErrInvalid
}

func (p *Platform) UsePlatformAutoRedirect() bool { return false }

func (p *Platform) CreateAutoRedirect(options adapter.AutoRedirectOptions) (adapter.AutoRedirectSession, error) {
	return nil, os.ErrInvalid
}
