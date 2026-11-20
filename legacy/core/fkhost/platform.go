package fkhost

import (
	"errors"
	"net/netip"
	"sync"
	"syscall"
)

// TunRequest is the TUN a kernel asks the platform for.
type TunRequest struct {
	Inet4 netip.Prefix
	Inet6 netip.Prefix
	MTU   uint32
	// The apps whose traffic goes through (or around) the TUN; only
	// platforms with per-app VPNs (Android) read them.
	IncludePackage []string
	ExcludePackage []string
}

// ConnectionOwner is the app that owns a connection.
type ConnectionOwner struct {
	PackageNames []string
	UserName     string
}

// Platform is the operating-system side of the host: the TUN, keeping the
// kernel's own sockets off it, and the owners of connections. Each operating
// system has its own implementation (platform_<os>.go); kernels never see
// which one runs.
type Platform interface {
	// OpenTun opens the TUN and returns a descriptor the caller owns and
	// closes.
	OpenTun(request TunRequest) (int, error)
	// Protect keeps an outbound socket off the TUN.
	Protect(conn syscall.RawConn) error
	// ConnectionOwner returns the app that owns a connection, nil when none
	// is known. ok is false when the platform cannot look owners up.
	ConnectionOwner(protocol int32, source, destination netip.AddrPort) (owner *ConnectionOwner, ok bool, err error)
}

// ErrUnsupported is returned by a platform that cannot do what was asked.
var ErrUnsupported = errors.New("not supported on this platform")

// protectRawConn runs protect on the socket's descriptor.
func protectRawConn(conn syscall.RawConn, protect func(fd int) error) error {
	var protectErr error
	err := conn.Control(func(fd uintptr) {
		protectErr = protect(int(fd))
	})
	if err != nil {
		return err
	}
	return protectErr
}

// The platform reports default-network changes (Wi-Fi to cellular and back)
// through DefaultNetworkChanged: Android from the app's ConnectivityManager
// callback (Fkbox.defaultNetworkChanged); every watcher hears each one.
var (
	networkAccess   sync.Mutex
	networkNext     uint64
	networkWatchers = map[uint64]func(){}
)

// WatchDefaultNetwork calls onChange whenever the underlying default network
// is replaced, until cancel is called.
func WatchDefaultNetwork(onChange func()) (cancel func()) {
	networkAccess.Lock()
	id := networkNext
	networkNext++
	networkWatchers[id] = onChange
	networkAccess.Unlock()
	return func() {
		networkAccess.Lock()
		delete(networkWatchers, id)
		networkAccess.Unlock()
	}
}

// DefaultNetworkChanged tells every watcher that the default network was
// replaced.
func DefaultNetworkChanged() {
	networkAccess.Lock()
	watchers := make([]func(), 0, len(networkWatchers))
	for _, onChange := range networkWatchers {
		watchers = append(watchers, onChange)
	}
	networkAccess.Unlock()
	for _, onChange := range watchers {
		onChange()
	}
}
