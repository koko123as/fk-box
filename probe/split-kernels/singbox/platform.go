package main

/*
#cgo CFLAGS: -I${SRCDIR}/../include
#define FK_KERNEL_TYPES_ONLY
#include <stdlib.h>
#include "fk_kernel.h"

static int32_t fk_call_protect(const fk_host *h, int32_t fd) {
    return h->protect ? h->protect(h->ctx, fd) : 0;
}
static int32_t fk_call_open_tun(const fk_host *h, const char *options, int32_t *fd) {
    return h->open_tun ? h->open_tun(h->ctx, options, fd) : -1;
}
static int32_t fk_call_find_owner(const fk_host *h, int32_t proto, const char *src, int32_t sport,
                                  const char *dst, int32_t dport, int32_t *uid, char *pkg, int32_t cap) {
    return h->find_owner ? h->find_owner(h->ctx, proto, src, sport, dst, dport, uid, pkg, cap) : -1;
}
static int32_t fk_call_interfaces(const fk_host *h, char *out, int32_t cap) {
    return h->interfaces ? h->interfaces(h->ctx, out, cap) : -1;
}
*/
import "C"

import (
	"context"
	"encoding/json"
	"net"
	"net/netip"
	"os"
	"sync"
	"unsafe"

	"github.com/sagernet/sing-box/adapter"
	C2 "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
	tun "github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/control"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/logger"
	"github.com/sagernet/sing/common/x/list"
	"golang.org/x/sys/unix"
)

var errUnsupported = E.New("not supported by fk-box")

// hostPlatform is adapter.PlatformInterface over the fk_host callbacks: TUN
// from VpnService, socket protection, connection owners and the default
// network. Everything that is not proxying (shell, bridge, notifications,
// auto-redirect, Wi-Fi, neighbours) is reported unsupported.
type hostPlatform struct {
	host           *C.fk_host
	networkManager adapter.NetworkManager
	access         sync.Mutex
	monitor        *hostMonitor
	tunAddress     []netip.Addr
}

func cString(s string) *C.char { return C.CString(s) }

func (p *hostPlatform) Initialize(networkManager adapter.NetworkManager) error {
	p.networkManager = networkManager
	return nil
}

func (p *hostPlatform) UsePlatformAutoDetectInterfaceControl() bool { return true }

func (p *hostPlatform) AutoDetectInterfaceControl(fd int) error {
	if C.fk_call_protect(p.host, C.int32_t(fd)) != 0 {
		return E.New("protect socket ", fd)
	}
	return nil
}

func (p *hostPlatform) UsePlatformInterface() bool { return true }

func (p *hostPlatform) OpenInterface(options *tun.Options, platformOptions option.TunPlatformOptions) (tun.Tun, error) {
	routes, err := options.BuildAutoRouteRanges(true)
	if err != nil {
		return nil, err
	}
	dns, err := options.DNSServerAddress()
	if err != nil {
		return nil, err
	}
	request, _ := json.Marshal(map[string]any{
		"mtu": options.MTU, "inet4": options.Inet4Address, "inet6": options.Inet6Address,
		"dns": dns, "routes": routes, "include_package": options.IncludePackage,
		"exclude_package": options.ExcludePackage, "http_proxy": platformOptions.HTTPProxy,
	})
	text := cString(string(request))
	defer C.free(unsafe.Pointer(text))
	var fd C.int32_t
	if C.fk_call_open_tun(p.host, text, &fd) != 0 {
		return nil, E.New("open tun")
	}
	ifreq, err := unix.NewIfreq("")
	if err != nil {
		return nil, err
	}
	if err = unix.IoctlIfreq(int(fd), unix.TUNGETIFF, ifreq); err != nil {
		return nil, E.Cause(err, "query tun name")
	}
	options.Name = ifreq.Name()
	options.InterfaceMonitor.RegisterMyInterface(options.Name)
	dupFd, err := unix.Dup(int(fd))
	if err != nil {
		return nil, err
	}
	options.FileDescriptor = dupFd
	p.access.Lock()
	p.tunAddress = nil
	for _, prefix := range append(options.Inet4Address, options.Inet6Address...) {
		p.tunAddress = append(p.tunAddress, prefix.Addr())
	}
	p.access.Unlock()
	return tun.New(*options)
}

func (p *hostPlatform) ProcessPlatformOptions(option.TunPlatformOptions) error { return nil }

func (p *hostPlatform) UsePlatformDefaultInterfaceMonitor() bool { return true }

func (p *hostPlatform) CreateDefaultInterfaceMonitor(l logger.Logger) tun.DefaultInterfaceMonitor {
	p.access.Lock()
	defer p.access.Unlock()
	p.monitor = &hostMonitor{platform: p, logger: l}
	return p.monitor
}

func (p *hostPlatform) defaultInterfaceChanged(name string, index int) {
	p.access.Lock()
	monitor := p.monitor
	p.access.Unlock()
	if monitor != nil {
		monitor.update(name, index)
	}
}

type hostInterface struct {
	Index     int      `json:"index"`
	MTU       int      `json:"mtu"`
	Name      string   `json:"name"`
	Addresses []string `json:"addresses"`
	Flags     uint32   `json:"flags"`
	Type      uint8    `json:"type"`
	DNS       []string `json:"dns"`
	Metered   bool     `json:"metered"`
}

func (p *hostPlatform) UsePlatformNetworkInterfaces() bool { return true }

func (p *hostPlatform) NetworkInterfaces() ([]adapter.NetworkInterface, error) {
	buffer := make([]byte, 64*1024)
	n := C.fk_call_interfaces(p.host, (*C.char)(unsafe.Pointer(&buffer[0])), C.int32_t(len(buffer)))
	if n < 0 {
		return nil, E.New("read network interfaces")
	}
	var raw []hostInterface
	if err := json.Unmarshal(buffer[:n], &raw); err != nil {
		return nil, err
	}
	interfaces := make([]adapter.NetworkInterface, 0, len(raw))
	for _, it := range raw {
		var addresses []netip.Prefix
		for _, address := range it.Addresses {
			if prefix, err := netip.ParsePrefix(address); err == nil {
				addresses = append(addresses, prefix)
			}
		}
		interfaces = append(interfaces, adapter.NetworkInterface{
			Interface:  control.Interface{Index: it.Index, MTU: it.MTU, Name: it.Name, Addresses: addresses, Flags: net.Flags(it.Flags)},
			Type:       C2.InterfaceType(it.Type),
			DNSServers: it.DNS,
			Expensive:  it.Metered,
		})
	}
	return interfaces, nil
}

func (p *hostPlatform) UnderNetworkExtension() bool              { return false }
func (p *hostPlatform) NetworkExtensionIncludeAllNetworks() bool { return false }
func (p *hostPlatform) ClearDNSCache()                           {}
func (p *hostPlatform) RequestPermissionForWIFIState() error     { return nil }
func (p *hostPlatform) ReadWIFIState(context.Context) adapter.WIFIState {
	return adapter.WIFIState{}
}

func (p *hostPlatform) UsePlatformConnectionOwnerFinder() bool { return true }

func (p *hostPlatform) FindConnectionOwner(request *adapter.FindConnectionOwnerRequest) (*adapter.ConnectionOwner, error) {
	src, dst := cString(request.SourceAddress), cString(request.DestinationAddress)
	defer C.free(unsafe.Pointer(src))
	defer C.free(unsafe.Pointer(dst))
	var uid C.int32_t
	var pkg [256]C.char
	if C.fk_call_find_owner(p.host, C.int32_t(request.IpProtocol), src, C.int32_t(request.SourcePort),
		dst, C.int32_t(request.DestinationPort), &uid, &pkg[0], C.int32_t(len(pkg))) != 0 {
		return nil, os.ErrNotExist
	}
	owner := &adapter.ConnectionOwner{UserId: int32(uid)}
	if name := C.GoString(&pkg[0]); name != "" {
		owner.PackageNames = []string{name}
	}
	return owner, nil
}

func (p *hostPlatform) UsePlatformWIFIMonitor() bool       { return false }
func (p *hostPlatform) UsePlatformNotification() bool      { return false }
func (p *hostPlatform) SendNotification(*adapter.Notification) error { return errUnsupported }
func (p *hostPlatform) CancelNotification(string, int32) error       { return errUnsupported }

func (p *hostPlatform) MyInterfaceAddress() []netip.Addr {
	p.access.Lock()
	defer p.access.Unlock()
	return p.tunAddress
}

func (p *hostPlatform) UsePlatformNeighborResolver() bool                          { return false }
func (p *hostPlatform) StartNeighborMonitor(adapter.NeighborUpdateListener) error { return errUnsupported }
func (p *hostPlatform) CloseNeighborMonitor(adapter.NeighborUpdateListener) error { return nil }
func (p *hostPlatform) UsePlatformShell() bool                                     { return false }
func (p *hostPlatform) CheckPlatformShell() error                                  { return errUnsupported }
func (p *hostPlatform) OpenShellSession(*adapter.PlatformUser, string, []string, string, int32, int32) (adapter.ShellSession, error) {
	return nil, errUnsupported
}
func (p *hostPlatform) LookupUser(string) (*adapter.PlatformUser, error) { return nil, errUnsupported }
func (p *hostPlatform) LookupSFTPServer() (string, error)                { return "", errUnsupported }
func (p *hostPlatform) ReadSystemSSHHostKey() ([]byte, error)            { return nil, errUnsupported }
func (p *hostPlatform) TailscaleHostname() string                        { return "" }
func (p *hostPlatform) UsePlatformBridge() bool                          { return false }
func (p *hostPlatform) CreateBridge(adapter.BridgeOptions) (adapter.BridgeSession, error) {
	return nil, errUnsupported
}
func (p *hostPlatform) UsePlatformAutoRedirect() bool { return false }
func (p *hostPlatform) CreateAutoRedirect(adapter.AutoRedirectOptions) (adapter.AutoRedirectSession, error) {
	return nil, errUnsupported
}

// hostMonitor is tun.DefaultInterfaceMonitor fed by fk_network_changed
// (Android's ConnectivityManager callback, forwarded by Rust).
type hostMonitor struct {
	platform     *hostPlatform
	logger       logger.Logger
	access       sync.Mutex
	callbacks    list.List[tun.DefaultInterfaceUpdateCallback]
	current      *control.Interface
	myInterfaces []string
}

func (m *hostMonitor) Start() error { return nil }
func (m *hostMonitor) Close() error { return nil }

func (m *hostMonitor) DefaultInterface() *control.Interface {
	m.access.Lock()
	defer m.access.Unlock()
	return m.current
}

func (m *hostMonitor) OverrideAndroidVPN() bool { return false }
func (m *hostMonitor) AndroidVPNEnabled() bool  { return false }

func (m *hostMonitor) RegisterCallback(callback tun.DefaultInterfaceUpdateCallback) *list.Element[tun.DefaultInterfaceUpdateCallback] {
	m.access.Lock()
	defer m.access.Unlock()
	return m.callbacks.PushBack(callback)
}

func (m *hostMonitor) UnregisterCallback(element *list.Element[tun.DefaultInterfaceUpdateCallback]) {
	m.access.Lock()
	defer m.access.Unlock()
	m.callbacks.Remove(element)
}

func (m *hostMonitor) RegisterMyInterface(name string) {
	m.access.Lock()
	defer m.access.Unlock()
	m.myInterfaces = append(m.myInterfaces, name)
}

func (m *hostMonitor) MyInterfaces() []string {
	m.access.Lock()
	defer m.access.Unlock()
	return m.myInterfaces
}

func (m *hostMonitor) update(name string, index int) {
	manager := m.platform.networkManager
	if manager != nil {
		if err := manager.UpdateInterfaces(); err != nil {
			m.logger.Error(E.Cause(err, "update interfaces"))
		}
	}
	var next *control.Interface
	if index >= 0 && manager != nil {
		found, err := manager.InterfaceFinder().ByIndex(index)
		if err != nil {
			m.logger.Error(E.Cause(err, "find default interface ", name))
			return
		}
		next = found
	}
	m.access.Lock()
	m.current = next
	callbacks := m.callbacks.Array()
	m.access.Unlock()
	for _, callback := range callbacks {
		callback(next, 0)
	}
}
