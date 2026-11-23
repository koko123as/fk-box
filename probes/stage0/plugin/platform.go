// Package plugin is the stage-0 feasibility probe for the "single sing-box
// kernel + Rust brain" design. Everything here uses only exported sing-box,
// mihomo and Xray APIs; nothing in the upstream trees is patched.
package plugin

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

// ProbePlatform stands in for the Android host. AutoDetectInterfaceControl is
// the hook libbox forwards to VpnService.protect(fd); here it only records the
// descriptors so a test can prove a socket went through sing-box's protected
// dialer.
type ProbePlatform struct {
	protected atomic.Int64
	access    sync.Mutex
	fds       []int
}

var _ adapter.PlatformInterface = (*ProbePlatform)(nil)

func (p *ProbePlatform) Protected() int64 { return p.protected.Load() }

func (p *ProbePlatform) Initialize(networkManager adapter.NetworkManager) error { return nil }

func (p *ProbePlatform) UsePlatformAutoDetectInterfaceControl() bool { return true }

func (p *ProbePlatform) AutoDetectInterfaceControl(fd int) error {
	p.protected.Add(1)
	p.access.Lock()
	p.fds = append(p.fds, fd)
	p.access.Unlock()
	return nil
}

func (p *ProbePlatform) UsePlatformInterface() bool { return false }

func (p *ProbePlatform) OpenInterface(options *tun.Options, platformOptions option.TunPlatformOptions) (tun.Tun, error) {
	return nil, os.ErrInvalid
}

func (p *ProbePlatform) ProcessPlatformOptions(options option.TunPlatformOptions) error { return nil }

func (p *ProbePlatform) UsePlatformDefaultInterfaceMonitor() bool { return false }

func (p *ProbePlatform) CreateDefaultInterfaceMonitor(logger logger.Logger) tun.DefaultInterfaceMonitor {
	return nil
}

func (p *ProbePlatform) UsePlatformNetworkInterfaces() bool { return false }

func (p *ProbePlatform) NetworkInterfaces() ([]adapter.NetworkInterface, error) {
	return nil, os.ErrInvalid
}

func (p *ProbePlatform) UnderNetworkExtension() bool { return false }

func (p *ProbePlatform) NetworkExtensionIncludeAllNetworks() bool { return false }

func (p *ProbePlatform) ClearDNSCache() {}

func (p *ProbePlatform) RequestPermissionForWIFIState() error { return nil }

func (p *ProbePlatform) ReadWIFIState(ctx context.Context) adapter.WIFIState {
	return adapter.WIFIState{}
}

func (p *ProbePlatform) UsePlatformConnectionOwnerFinder() bool { return false }

func (p *ProbePlatform) FindConnectionOwner(request *adapter.FindConnectionOwnerRequest) (*adapter.ConnectionOwner, error) {
	return nil, os.ErrInvalid
}

func (p *ProbePlatform) UsePlatformWIFIMonitor() bool { return false }

func (p *ProbePlatform) UsePlatformNotification() bool { return false }

func (p *ProbePlatform) SendNotification(notification *adapter.Notification) error { return nil }

func (p *ProbePlatform) CancelNotification(identifier string, typeID int32) error { return nil }

func (p *ProbePlatform) MyInterfaceAddress() []netip.Addr { return nil }

func (p *ProbePlatform) UsePlatformNeighborResolver() bool { return false }

func (p *ProbePlatform) StartNeighborMonitor(listener adapter.NeighborUpdateListener) error {
	return os.ErrInvalid
}

func (p *ProbePlatform) CloseNeighborMonitor(listener adapter.NeighborUpdateListener) error {
	return nil
}

func (p *ProbePlatform) UsePlatformShell() bool { return false }

func (p *ProbePlatform) CheckPlatformShell() error { return os.ErrInvalid }

func (p *ProbePlatform) OpenShellSession(user *adapter.PlatformUser, command string, env []string, term string, rows int32, cols int32) (adapter.ShellSession, error) {
	return nil, os.ErrInvalid
}

func (p *ProbePlatform) LookupUser(username string) (*adapter.PlatformUser, error) {
	return nil, os.ErrInvalid
}

func (p *ProbePlatform) LookupSFTPServer() (string, error) { return "", os.ErrInvalid }

func (p *ProbePlatform) ReadSystemSSHHostKey() ([]byte, error) { return nil, os.ErrInvalid }

func (p *ProbePlatform) TailscaleHostname() string { return "" }

func (p *ProbePlatform) UsePlatformBridge() bool { return false }

func (p *ProbePlatform) CreateBridge(options adapter.BridgeOptions) (adapter.BridgeSession, error) {
	return nil, os.ErrInvalid
}

func (p *ProbePlatform) UsePlatformAutoRedirect() bool { return false }

func (p *ProbePlatform) CreateAutoRedirect(options adapter.AutoRedirectOptions) (adapter.AutoRedirectSession, error) {
	return nil, os.ErrInvalid
}
