package host

import (
	"context"
	"fmt"
	"net/netip"
	"os"
	"sync"
	"sync/atomic"
	"syscall"
	"unsafe"

	"golang.org/x/sys/unix"

	"github.com/koko123as/fk-box/apps/android/fkhost/upstream"
)

// Bridge is what the Android shell provides (through the JNI bridge in
// stage 6): VpnService.protect, the TUN descriptor from
// VpnService.Builder.establish, and optionally the connection owner and the
// network state. Its methods may be called from any goroutine.
type Bridge interface {
	// Protect is VpnService.protect(fd): the socket bypasses the VPN.
	Protect(fd int) error
	// OpenTun establishes the VPN interface and returns its descriptor
	// (the host duplicates it; the bridge keeps its own).
	OpenTun(request *TunRequest) (int, error)
}

// OwnerFinder is ConnectivityManager.getConnectionOwnerUid + the package
// names of that uid (Android 10+).
type OwnerFinder interface {
	FindConnectionOwner(request *upstream.FindConnectionOwnerRequest) (*upstream.ConnectionOwner, error)
}

// NetworkSource reports the device's interfaces and default network
// (ConnectivityManager callbacks). Without it sing-box watches the system
// itself (netlink; restricted for apps on Android 11+).
type NetworkSource interface {
	NetworkInterfaces() ([]upstream.NetworkInterface, error)
	// StartDefaultInterfaceMonitor begins calling update with the default
	// network's interface (index -1: none) until Close.
	StartDefaultInterfaceMonitor(update func(interfaceName string, interfaceIndex int32)) error
	CloseDefaultInterfaceMonitor() error
}

// TunRequest is what VpnService.Builder needs, from the tun inbound.
type TunRequest struct {
	MTU            uint32
	Inet4Address   []netip.Prefix
	Inet6Address   []netip.Prefix
	AutoRoute      bool
	StrictRoute    bool
	Routes         []netip.Prefix
	IncludePackage []string
	ExcludePackage []string
}

// Platform is the Android adapter.PlatformInterface of the host (option A:
// fk-box's own, not libbox's). Shell, SSH, Tailscale, bridges, neighbour
// discovery and auto-redirect are not offered (the generated configuration
// uses none of them).
type Platform struct {
	bridge         Bridge
	networkManager upstream.NetworkManager
	protected      atomic.Int64
	access         sync.Mutex
	tunAddresses   []netip.Addr
}

var _ upstream.PlatformInterface = (*Platform)(nil)

func NewPlatform(bridge Bridge) *Platform {
	return &Platform{bridge: bridge}
}

// Protected counts sockets handed to Bridge.Protect (every sing-box dialer
// socket with auto_detect_interface, plugin dialers included).
func (p *Platform) Protected() int64 { return p.protected.Load() }

func (p *Platform) Initialize(networkManager upstream.NetworkManager) error {
	p.networkManager = networkManager
	return nil
}

func (p *Platform) UsePlatformAutoDetectInterfaceControl() bool { return true }

func (p *Platform) AutoDetectInterfaceControl(fd int) error {
	p.protected.Add(1)
	return p.bridge.Protect(fd)
}

func (p *Platform) UsePlatformInterface() bool { return true }

func (p *Platform) OpenInterface(options *upstream.TunOptions, platformOptions upstream.TunPlatformOptions) (upstream.Tun, error) {
	if len(options.IncludeUID) > 0 || len(options.ExcludeUID) > 0 {
		return nil, upstream.NewError("platform: unsupported uid options")
	}
	routes, err := options.BuildAutoRouteRanges(true)
	if err != nil {
		return nil, err
	}
	fd, err := p.bridge.OpenTun(&TunRequest{
		MTU:            options.MTU,
		Inet4Address:   options.Inet4Address,
		Inet6Address:   options.Inet6Address,
		AutoRoute:      options.AutoRoute,
		StrictRoute:    options.StrictRoute,
		Routes:         routes,
		IncludePackage: options.IncludePackage,
		ExcludePackage: options.ExcludePackage,
	})
	if err != nil {
		return nil, upstream.Cause(err, "open tun")
	}
	name, err := tunName(fd)
	if err != nil {
		return nil, upstream.Cause(err, "query tun name")
	}
	options.Name = name
	if options.InterfaceMonitor != nil {
		options.InterfaceMonitor.RegisterMyInterface(name)
	}
	dupFd, err := syscall.Dup(fd)
	if err != nil {
		return nil, upstream.Cause(err, "dup tun descriptor")
	}
	options.FileDescriptor = dupFd
	var addresses []netip.Addr
	for _, prefix := range options.Inet4Address {
		addresses = append(addresses, prefix.Addr())
	}
	for _, prefix := range options.Inet6Address {
		addresses = append(addresses, prefix.Addr())
	}
	p.access.Lock()
	p.tunAddresses = addresses
	p.access.Unlock()
	return upstream.NewTun(*options)
}

func tunName(fd int) (string, error) {
	var ifr [unix.IFNAMSIZ + 64]byte
	_, _, errno := unix.Syscall(unix.SYS_IOCTL, uintptr(fd), uintptr(unix.TUNGETIFF), uintptr(unsafe.Pointer(&ifr[0])))
	if errno != 0 {
		return "", fmt.Errorf("TUNGETIFF: %w", errno)
	}
	return unix.ByteSliceToString(ifr[:]), nil
}

func (p *Platform) ProcessPlatformOptions(options upstream.TunPlatformOptions) error { return nil }

func (p *Platform) MyInterfaceAddress() []netip.Addr {
	p.access.Lock()
	defer p.access.Unlock()
	return append([]netip.Addr(nil), p.tunAddresses...)
}

func (p *Platform) UsePlatformDefaultInterfaceMonitor() bool {
	_, ok := p.bridge.(NetworkSource)
	return ok
}

func (p *Platform) CreateDefaultInterfaceMonitor(logger upstream.Logger) upstream.DefaultInterfaceMonitor {
	return &defaultInterfaceMonitor{platform: p, source: p.bridge.(NetworkSource), logger: logger}
}

func (p *Platform) UsePlatformNetworkInterfaces() bool {
	_, ok := p.bridge.(NetworkSource)
	return ok
}

func (p *Platform) NetworkInterfaces() ([]upstream.NetworkInterface, error) {
	source, ok := p.bridge.(NetworkSource)
	if !ok {
		return nil, os.ErrInvalid
	}
	return source.NetworkInterfaces()
}

func (p *Platform) UnderNetworkExtension() bool { return false }

func (p *Platform) NetworkExtensionIncludeAllNetworks() bool { return false }

func (p *Platform) ClearDNSCache() {}

func (p *Platform) RequestPermissionForWIFIState() error { return nil }

func (p *Platform) ReadWIFIState(ctx context.Context) upstream.WIFIState {
	return upstream.WIFIState{}
}

func (p *Platform) UsePlatformConnectionOwnerFinder() bool {
	_, ok := p.bridge.(OwnerFinder)
	return ok
}

func (p *Platform) FindConnectionOwner(request *upstream.FindConnectionOwnerRequest) (*upstream.ConnectionOwner, error) {
	finder, ok := p.bridge.(OwnerFinder)
	if !ok {
		return nil, os.ErrInvalid
	}
	return finder.FindConnectionOwner(request)
}

func (p *Platform) UsePlatformWIFIMonitor() bool { return false }

func (p *Platform) UsePlatformNotification() bool { return false }

func (p *Platform) SendNotification(notification *upstream.Notification) error { return nil }

func (p *Platform) CancelNotification(identifier string, typeID int32) error { return nil }

func (p *Platform) UsePlatformNeighborResolver() bool { return false }

func (p *Platform) StartNeighborMonitor(listener upstream.NeighborUpdateListener) error {
	return os.ErrInvalid
}

func (p *Platform) CloseNeighborMonitor(listener upstream.NeighborUpdateListener) error { return nil }

func (p *Platform) UsePlatformShell() bool { return false }

func (p *Platform) CheckPlatformShell() error { return os.ErrInvalid }

func (p *Platform) OpenShellSession(user *upstream.PlatformUser, command string, env []string, term string, rows int32, cols int32) (upstream.ShellSession, error) {
	return nil, os.ErrInvalid
}

func (p *Platform) LookupUser(username string) (*upstream.PlatformUser, error) {
	return nil, os.ErrInvalid
}

func (p *Platform) LookupSFTPServer() (string, error) { return "", os.ErrInvalid }

func (p *Platform) ReadSystemSSHHostKey() ([]byte, error) { return nil, os.ErrInvalid }

func (p *Platform) TailscaleHostname() string { return "" }

func (p *Platform) UsePlatformBridge() bool { return false }

func (p *Platform) CreateBridge(options upstream.BridgeOptions) (upstream.BridgeSession, error) {
	return nil, os.ErrInvalid
}

func (p *Platform) UsePlatformAutoRedirect() bool { return false }

func (p *Platform) CreateAutoRedirect(options upstream.AutoRedirectOptions) (upstream.AutoRedirectSession, error) {
	return nil, os.ErrInvalid
}

// defaultInterfaceMonitor follows the bridge's default network (the
// structure of libbox's platformDefaultInterfaceMonitor, without its
// Darwin and power-report parts).
type defaultInterfaceMonitor struct {
	platform     *Platform
	source       NetworkSource
	logger       upstream.Logger
	access       sync.Mutex
	callbacks    upstream.List[upstream.DefaultInterfaceUpdateCallback]
	current      *upstream.Interface
	initialized  bool
	myInterfaces []string
}

var _ upstream.DefaultInterfaceMonitor = (*defaultInterfaceMonitor)(nil)

func (m *defaultInterfaceMonitor) Start() error {
	return m.source.StartDefaultInterfaceMonitor(m.update)
}

func (m *defaultInterfaceMonitor) Close() error {
	return m.source.CloseDefaultInterfaceMonitor()
}

func (m *defaultInterfaceMonitor) update(interfaceName string, interfaceIndex int32) {
	if networkManager := m.platform.networkManager; networkManager != nil {
		if err := networkManager.UpdateInterfaces(); err != nil {
			m.logger.Error(upstream.Cause(err, "update interfaces"))
		}
	}
	m.access.Lock()
	if interfaceIndex < 0 {
		m.current = nil
		m.initialized = true
		callbacks := m.callbacks.Array()
		m.access.Unlock()
		for _, callback := range callbacks {
			callback(nil, 0)
		}
		return
	}
	if m.platform.networkManager == nil {
		m.access.Unlock()
		return
	}
	updated, err := m.platform.networkManager.InterfaceFinder().ByIndex(int(interfaceIndex))
	if err != nil {
		m.access.Unlock()
		m.logger.Error(upstream.Cause(err, "find updated interface: ", interfaceName))
		return
	}
	previous := m.current
	m.current = updated
	if m.initialized && previous != nil && previous.Name == updated.Name && previous.Index == updated.Index {
		m.access.Unlock()
		return
	}
	m.initialized = true
	callbacks := m.callbacks.Array()
	m.access.Unlock()
	for _, callback := range callbacks {
		callback(updated, 0)
	}
}

func (m *defaultInterfaceMonitor) DefaultInterface() *upstream.Interface {
	m.access.Lock()
	defer m.access.Unlock()
	return m.current
}

func (m *defaultInterfaceMonitor) OverrideAndroidVPN() bool { return false }

func (m *defaultInterfaceMonitor) AndroidVPNEnabled() bool { return false }

func (m *defaultInterfaceMonitor) RegisterCallback(callback upstream.DefaultInterfaceUpdateCallback) *upstream.Element[upstream.DefaultInterfaceUpdateCallback] {
	m.access.Lock()
	defer m.access.Unlock()
	return m.callbacks.PushBack(callback)
}

func (m *defaultInterfaceMonitor) UnregisterCallback(element *upstream.Element[upstream.DefaultInterfaceUpdateCallback]) {
	m.access.Lock()
	defer m.access.Unlock()
	m.callbacks.Remove(element)
}

func (m *defaultInterfaceMonitor) RegisterMyInterface(interfaceName string) {
	m.access.Lock()
	defer m.access.Unlock()
	m.myInterfaces = append(m.myInterfaces, interfaceName)
}

func (m *defaultInterfaceMonitor) MyInterfaces() []string {
	m.access.Lock()
	defer m.access.Unlock()
	return append([]string(nil), m.myInterfaces...)
}
