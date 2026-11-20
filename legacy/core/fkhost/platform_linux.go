//go:build linux && !android

package fkhost

// Linux (desktop): fk-box opens its own TUN on /dev/net/tun and routes
// everything into it from a routing table of its own, except packets carrying
// fk-box's firewall mark: the kernel's own sockets get the mark (Protect,
// SO_MARK) and keep using the main table. Needs CAP_NET_ADMIN.
//
//	ip rule  add pref 8999 lookup main suppress_prefixlength 0
//	ip rule  add pref 9000 not fwmark 0x2023 lookup 2023
//	ip route add default dev fkbox0 table 2023   (IPv4 and IPv6)
//
// Connection owners are not looked up on Linux.

import (
	"errors"
	"net"
	"net/netip"
	"sync"
	"syscall"

	"github.com/sagernet/netlink"
	"golang.org/x/sys/unix"
)

const (
	linuxTunName      = "fkbox0"
	linuxRouteTable   = 2023
	linuxMark         = 0x2023
	linuxRulePriority = 9000
)

// NewPlatform is the Linux platform.
func NewPlatform() Platform { return &linuxPlatform{} }

type linuxPlatform struct {
	access sync.Mutex
	routed bool
}

func (p *linuxPlatform) OpenTun(request TunRequest) (int, error) {
	fd, err := unix.Open("/dev/net/tun", unix.O_RDWR|unix.O_CLOEXEC, 0)
	if err != nil {
		return -1, err
	}
	link, err := linuxConfigureTun(fd, request)
	if err != nil {
		_ = unix.Close(fd)
		return -1, err
	}
	p.access.Lock()
	defer p.access.Unlock()
	if err = linuxRoute(link.Attrs().Index); err != nil {
		_ = unix.Close(fd)
		return -1, err
	}
	p.routed = true
	return fd, nil
}

func linuxConfigureTun(fd int, request TunRequest) (netlink.Link, error) {
	ifreq, err := unix.NewIfreq(linuxTunName)
	if err != nil {
		return nil, err
	}
	ifreq.SetUint16(unix.IFF_TUN | unix.IFF_NO_PI)
	if err = unix.IoctlIfreq(fd, unix.TUNSETIFF, ifreq); err != nil {
		return nil, err
	}
	link, err := netlink.LinkByName(linuxTunName)
	if err != nil {
		return nil, err
	}
	if request.MTU > 0 {
		if err = netlink.LinkSetMTU(link, int(request.MTU)); err != nil {
			return nil, err
		}
	}
	if request.Inet4.IsValid() {
		if err = linuxAddAddress(link, request.Inet4); err != nil {
			return nil, err
		}
	}
	if request.Inet6.IsValid() {
		// A system with IPv6 turned off keeps IPv4 working.
		if err := linuxAddAddress(link, request.Inet6); err != nil {
			Log.Diagnostic("fkbox0: IPv6 address: " + err.Error())
		}
	}
	return link, netlink.LinkSetUp(link)
}

func linuxAddAddress(link netlink.Link, prefix netip.Prefix) error {
	err := netlink.AddrAdd(link, &netlink.Addr{IPNet: linuxIPNet(prefix)})
	if errors.Is(err, unix.EEXIST) {
		return nil
	}
	return err
}

func linuxIPNet(prefix netip.Prefix) *net.IPNet {
	return &net.IPNet{IP: prefix.Addr().AsSlice(), Mask: net.CIDRMask(prefix.Bits(), prefix.Addr().BitLen())}
}

// linuxRoute points fk-box's table at the TUN and (re)installs the rules.
// The table's routes go away with the TUN; the rules are replaced each time.
func linuxRoute(index int) error {
	for _, family := range []int{unix.AF_INET, unix.AF_INET6} {
		zero := netip.PrefixFrom(netip.IPv4Unspecified(), 0)
		if family == unix.AF_INET6 {
			zero = netip.PrefixFrom(netip.IPv6Unspecified(), 0)
		}
		route := &netlink.Route{LinkIndex: index, Dst: linuxIPNet(zero), Table: linuxRouteTable, Family: family}
		err := netlink.RouteAdd(route)
		if err != nil && !errors.Is(err, unix.EEXIST) {
			if family == unix.AF_INET6 {
				Log.Diagnostic("fkbox0: IPv6 route: " + err.Error())
				continue
			}
			return err
		}
		for _, rule := range linuxRules(family) {
			_ = netlink.RuleDel(rule)
			if err := netlink.RuleAdd(rule); err != nil && !errors.Is(err, unix.EEXIST) {
				if family == unix.AF_INET6 {
					Log.Diagnostic("fkbox0: IPv6 rule: " + err.Error())
					break
				}
				return err
			}
		}
	}
	return nil
}

func linuxRules(family int) []*netlink.Rule {
	// Routes more specific than the default (LAN, local) stay on the main table.
	suppress := netlink.NewRule()
	suppress.Family = family
	suppress.Priority = linuxRulePriority - 1
	suppress.Table = unix.RT_TABLE_MAIN
	suppress.SuppressPrefixlen = 0
	// Everything not marked goes to the TUN.
	toTun := netlink.NewRule()
	toTun.Family = family
	toTun.Priority = linuxRulePriority
	toTun.Table = linuxRouteTable
	toTun.Mark = linuxMark
	toTun.MarkSet = true
	toTun.Invert = true
	return []*netlink.Rule{suppress, toTun}
}

// Protect marks the socket so the main table routes it. Before a TUN was
// opened there is nothing to keep off it.
func (p *linuxPlatform) Protect(conn syscall.RawConn) error {
	p.access.Lock()
	routed := p.routed
	p.access.Unlock()
	if !routed {
		return nil
	}
	return protectRawConn(conn, func(fd int) error {
		return unix.SetsockoptInt(fd, unix.SOL_SOCKET, unix.SO_MARK, linuxMark)
	})
}

func (*linuxPlatform) ConnectionOwner(int32, netip.AddrPort, netip.AddrPort) (*ConnectionOwner, bool, error) {
	return nil, false, nil
}
