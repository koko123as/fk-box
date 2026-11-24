package mobile

import (
	"encoding/json"
	"net"
	"net/netip"
	"sync"
	"syscall"

	"github.com/koko123as/fk-box/apps/android/fkhost/host"
	"github.com/koko123as/fk-box/apps/android/fkhost/upstream"
)

// Platform is what the Android VpnService gives the kernel. Every method
// may be called from any goroutine.
type Platform interface {
	// Protect is VpnService.protect(fd): the socket bypasses the VPN.
	Protect(fd int32) error
	// OpenTun builds the VPN interface (VpnService.Builder.establish) and
	// returns its descriptor; the service keeps the ParcelFileDescriptor and
	// closes the previous one before it establishes a new interface.
	OpenTun(options *TunOptions) (int32, error)
	// FindConnectionOwner is ConnectivityManager.getConnectionOwnerUid
	// (Android 10+) and the packages of that uid.
	FindConnectionOwner(ipProtocol int32, sourceAddress string, sourcePort int32, destinationAddress string, destinationPort int32) (*ConnectionOwner, error)
	// NetworkInterfaces lists the device's networks as a JSON array of
	// {"name", "index", "mtu", "addresses": ["a/p"], "flags" (IFF_*),
	// "type" (0 Wi-Fi, 1 cellular, 2 ethernet, 3 other), "dns": [...],
	// "domains": [...], "gateways": [...], "metered"}.
	NetworkInterfaces() (string, error)
	// StartDefaultInterfaceMonitor reports the default network to listener
	// (now and on every change) until CloseDefaultInterfaceMonitor.
	StartDefaultInterfaceMonitor(listener InterfaceListener) error
	CloseDefaultInterfaceMonitor() error
}

// InterfaceListener receives the default network: its interface name and
// index, or index -1 when there is none.
type InterfaceListener interface {
	UpdateDefaultInterface(name string, index int32)
}

// ConnectionOwner is the answer of Platform.FindConnectionOwner: the uid and
// its packages, newline-separated.
type ConnectionOwner struct {
	Uid      int32
	Packages string
}

// TunOptions describes the VPN interface the kernel's tun inbound wants.
type TunOptions struct {
	request *host.TunRequest
}

// MTU of the interface.
func (o *TunOptions) MTU() int32 { return int32(o.request.MTU) }

// AutoRoute: route traffic into the interface (the routes below).
func (o *TunOptions) AutoRoute() bool { return o.request.AutoRoute }

// Plan is the interface as the VPN builder wants it, a JSON object with the
// keys of mobile-ffi vpn_routing.rs: mtu, autoRoute, dnsDisabled,
// inet4Address / inet6Address ([{"address", "prefix"}]), dnsServers,
// inet4RouteAddress / inet6RouteAddress (empty: the default route),
// inet4RouteExclude / inet6RouteExclude, inet4RouteRange / inet6RouteRange
// (the routes for Android < 13), include / exclude (packages).
func (o *TunOptions) Plan() string {
	type prefix struct {
		Address string `json:"address"`
		Prefix  int    `json:"prefix"`
	}
	prefixes := func(list []netip.Prefix, want4 bool) []prefix {
		out := []prefix{}
		for _, item := range list {
			if item.Addr().Unmap().Is4() != want4 {
				continue
			}
			out = append(out, prefix{Address: item.Addr().Unmap().String(), Prefix: item.Bits()})
		}
		return out
	}
	var dns []string
	if len(o.request.Inet4Address) > 0 {
		dns = append(dns, o.request.Inet4Address[0].Addr().Next().String())
	} else if len(o.request.Inet6Address) > 0 {
		dns = append(dns, o.request.Inet6Address[0].Addr().Next().String())
	}
	strings := func(list []string) []string {
		if list == nil {
			return []string{}
		}
		return list
	}
	plan := map[string]any{
		"mtu":               o.request.MTU,
		"autoRoute":         o.request.AutoRoute,
		"dnsDisabled":       false,
		"inet4Address":      prefixes(o.request.Inet4Address, true),
		"inet6Address":      prefixes(o.request.Inet6Address, false),
		"dnsServers":        strings(dns),
		"inet4RouteAddress": []prefix{},
		"inet6RouteAddress": []prefix{},
		"inet4RouteExclude": []prefix{},
		"inet6RouteExclude": []prefix{},
		"inet4RouteRange":   prefixes(o.request.Routes, true),
		"inet6RouteRange":   prefixes(o.request.Routes, false),
		"include":           strings(o.request.IncludePackage),
		"exclude":           strings(o.request.ExcludePackage),
	}
	data, _ := json.Marshal(plan)
	return string(data)
}

// platformBridge is the host.Bridge (with OwnerFinder and NetworkSource)
// over the current Platform, swapped at every Start; the host is created
// once per process.
type platformBridge struct {
	access   sync.RWMutex
	platform Platform
}

var (
	_ host.Bridge        = (*platformBridge)(nil)
	_ host.OwnerFinder   = (*platformBridge)(nil)
	_ host.NetworkSource = (*platformBridge)(nil)
)

func (b *platformBridge) set(platform Platform) {
	b.access.Lock()
	b.platform = platform
	b.access.Unlock()
}

func (b *platformBridge) current() (Platform, error) {
	b.access.RLock()
	defer b.access.RUnlock()
	if b.platform == nil {
		return nil, upstream.NewError("android: no platform (service not running)")
	}
	return b.platform, nil
}

func (b *platformBridge) Protect(fd int) error {
	platform, err := b.current()
	if err != nil {
		return err
	}
	return platform.Protect(int32(fd))
}

func (b *platformBridge) OpenTun(request *host.TunRequest) (int, error) {
	platform, err := b.current()
	if err != nil {
		return -1, err
	}
	fd, err := platform.OpenTun(&TunOptions{request: request})
	if err != nil {
		return -1, err
	}
	return int(fd), nil
}

func (b *platformBridge) FindConnectionOwner(request *upstream.FindConnectionOwnerRequest) (*upstream.ConnectionOwner, error) {
	platform, err := b.current()
	if err != nil {
		return nil, err
	}
	owner, err := platform.FindConnectionOwner(request.IpProtocol, request.SourceAddress, request.SourcePort, request.DestinationAddress, request.DestinationPort)
	if err != nil {
		return nil, err
	}
	if owner == nil {
		return nil, upstream.NewError("android: connection owner not found")
	}
	packages := splitLines(owner.Packages)
	result := &upstream.ConnectionOwner{UserId: owner.Uid, PackageNames: packages}
	if len(packages) > 0 {
		result.UserName = packages[0]
	}
	return result, nil
}

type networkInterface struct {
	Name      string   `json:"name"`
	Index     int      `json:"index"`
	MTU       int      `json:"mtu"`
	Addresses []string `json:"addresses"`
	Flags     uint32   `json:"flags"`
	Type      int      `json:"type"`
	DNS       []string `json:"dns"`
	Domains   []string `json:"domains"`
	Gateways  []string `json:"gateways"`
	Metered   bool     `json:"metered"`
}

func linkFlags(raw uint32) net.Flags {
	var flags net.Flags
	if raw&syscall.IFF_UP != 0 {
		flags |= net.FlagUp
	}
	if raw&syscall.IFF_RUNNING != 0 {
		flags |= net.FlagRunning
	}
	if raw&syscall.IFF_BROADCAST != 0 {
		flags |= net.FlagBroadcast
	}
	if raw&syscall.IFF_LOOPBACK != 0 {
		flags |= net.FlagLoopback
	}
	if raw&syscall.IFF_POINTOPOINT != 0 {
		flags |= net.FlagPointToPoint
	}
	if raw&syscall.IFF_MULTICAST != 0 {
		flags |= net.FlagMulticast
	}
	return flags
}

func (b *platformBridge) NetworkInterfaces() ([]upstream.NetworkInterface, error) {
	platform, err := b.current()
	if err != nil {
		return nil, err
	}
	text, err := platform.NetworkInterfaces()
	if err != nil {
		return nil, err
	}
	var list []networkInterface
	if err := json.Unmarshal([]byte(text), &list); err != nil {
		return nil, upstream.Cause(err, "android: network interfaces")
	}
	seen := make(map[string]bool)
	var interfaces []upstream.NetworkInterface
	for _, item := range list {
		if item.Name == "" || seen[item.Name] {
			continue
		}
		seen[item.Name] = true
		var addresses []netip.Prefix
		for _, address := range item.Addresses {
			if prefix, err := netip.ParsePrefix(address); err == nil {
				addresses = append(addresses, prefix)
			}
		}
		var gateways []netip.Addr
		for _, gateway := range item.Gateways {
			if addr, err := netip.ParseAddr(gateway); err == nil {
				gateways = append(gateways, addr.Unmap().WithZone(""))
			}
		}
		interfaces = append(interfaces, upstream.NetworkInterface{
			Interface: upstream.Interface{
				Index:     item.Index,
				MTU:       item.MTU,
				Name:      item.Name,
				Addresses: addresses,
				Flags:     linkFlags(item.Flags),
			},
			Type:             upstream.InterfaceType(item.Type),
			DNSServers:       item.DNS,
			DNSSearchDomains: item.Domains,
			Gateways:         gateways,
			Expensive:        item.Metered,
		})
	}
	return interfaces, nil
}

type interfaceListener struct {
	update func(interfaceName string, interfaceIndex int32)
}

func (l *interfaceListener) UpdateDefaultInterface(name string, index int32) {
	l.update(name, index)
}

func (b *platformBridge) StartDefaultInterfaceMonitor(update func(interfaceName string, interfaceIndex int32)) error {
	platform, err := b.current()
	if err != nil {
		return err
	}
	return platform.StartDefaultInterfaceMonitor(&interfaceListener{update: update})
}

func (b *platformBridge) CloseDefaultInterfaceMonitor() error {
	platform, err := b.current()
	if err != nil {
		return nil
	}
	return platform.CloseDefaultInterfaceMonitor()
}
