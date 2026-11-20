package libbox

import (
	"net/http"
	"net/netip"
	"sync"
	"syscall"
	"testing"
	"time"
)

// fakeHost records what kernels ask of their host; tests install it with
// useFakeKernelHost.
type fakeHost struct {
	access    sync.Mutex
	protected []uintptr
	owner     *hostConnectionOwner
	ownerErr  error
	noOwners  bool
	asked     []netip.AddrPort
	started   []string
	options   []kernelOptions
	stopped   int
	logs      []fakeLogLine
	diag      []string
	workDir   string
	changes   int
	groups    int
}

type fakeLogLine struct {
	level   hostLogLevel
	message string
}

func (h *fakeHost) kernelHost() kernelHost {
	return kernelHost{Platform: fakePlatform{h}, Commands: fakeCommands{h}, Lifecycle: fakeLifecycle{h}, HTTP: fakeHTTP{}, Log: fakeLog{h}}
}

type fakePlatform struct{ h *fakeHost }

func (p fakePlatform) OpenTun(hostTunRequest, kernelOptions) (int, error) {
	return -1, syscall.ENOSYS
}

func (p fakePlatform) Protect(conn syscall.RawConn) error {
	return conn.Control(func(fd uintptr) {
		p.h.access.Lock()
		p.h.protected = append(p.h.protected, fd)
		p.h.access.Unlock()
	})
}

func (p fakePlatform) ConnectionOwner(protocol int32, source, destination netip.AddrPort) (*hostConnectionOwner, bool, error) {
	p.h.access.Lock()
	defer p.h.access.Unlock()
	if p.h.noOwners {
		return nil, false, nil
	}
	p.h.asked = append(p.h.asked, source, destination)
	return p.h.owner, true, p.h.ownerErr
}

func (fakePlatform) WatchDefaultNetwork(onChange func()) func() {
	return libboxPlatform{}.WatchDefaultNetwork(onChange)
}

type fakeCommands struct{ h *fakeHost }

func (c fakeCommands) CoreChanged() {
	c.h.access.Lock()
	c.h.changes++
	c.h.access.Unlock()
}

func (c fakeCommands) GroupsChanged() {
	c.h.access.Lock()
	c.h.groups++
	c.h.access.Unlock()
}

type fakeLifecycle struct{ h *fakeHost }

func (l fakeLifecycle) StartSingBox(content string, options kernelOptions) error {
	l.h.access.Lock()
	defer l.h.access.Unlock()
	l.h.started = append(l.h.started, content)
	l.h.options = append(l.h.options, options)
	return nil
}

func (l fakeLifecycle) StopSingBox() error {
	l.h.access.Lock()
	defer l.h.access.Unlock()
	l.h.stopped++
	return nil
}

func (l fakeLifecycle) WorkingDirectory() string { return l.h.workDir }

type fakeHTTP struct{}

func (fakeHTTP) NewClient(timeout time.Duration) (*http.Client, func()) {
	return &http.Client{Timeout: timeout}, func() {}
}

type fakeLog struct{ h *fakeHost }

func (l fakeLog) Write(level hostLogLevel, message string) {
	l.h.access.Lock()
	defer l.h.access.Unlock()
	l.h.logs = append(l.h.logs, fakeLogLine{level, message})
}

func (l fakeLog) Diagnostic(message string) {
	l.h.access.Lock()
	defer l.h.access.Unlock()
	l.h.diag = append(l.h.diag, message)
}

// useFakeKernelHost makes h the host of the kernel "started last" (the one
// the process-global hooks use) and restores the previous one afterwards.
func useFakeKernelHost(t *testing.T, h *fakeHost) {
	t.Helper()
	previous := kernelHostInUse.Load()
	useKernelHost(h.kernelHost())
	t.Cleanup(func() { kernelHostInUse.Store(previous) })
}

// fakeRawConn hands Control a fixed descriptor.
type fakeRawConn struct{ fd uintptr }

func (c fakeRawConn) Control(f func(fd uintptr)) error { f(c.fd); return nil }
func (c fakeRawConn) Read(f func(fd uintptr) bool) error {
	f(c.fd)
	return nil
}
func (c fakeRawConn) Write(f func(fd uintptr) bool) error {
	f(c.fd)
	return nil
}

var _ syscall.RawConn = fakeRawConn{}

// Every registered kernel answers to its own id, and "" is sing-box.
func TestUIKernelSingBoxRegistry(t *testing.T) {
	for _, core := range []string{CoreSingBox, CoreXray, CoreMihomo} {
		kernel := kernelFor(core)
		if kernel == nil || kernel.Name() != core {
			t.Fatalf("kernelFor(%q) = %v", core, kernel)
		}
	}
	if kernel := kernelFor(""); kernel == nil || kernel.Name() != CoreSingBox {
		t.Fatalf(`kernelFor("") = %v, want sing-box`, kernel)
	}
	if kernel := kernelFor("auto"); kernel != nil {
		t.Fatalf(`kernelFor("auto") = %v, want none`, kernel)
	}
	if kernelFor(CoreSingBox).NeedsTun() || !kernelFor(CoreXray).NeedsTun() || !kernelFor(CoreMihomo).NeedsTun() {
		t.Fatal("only Xray and mihomo run on fk-box's TUN")
	}
}

// Watchers hear every default-network change until cancelled.
func TestUIKernelXrayNetworkWatch(t *testing.T) {
	calls := 0
	cancel := libboxPlatform{}.WatchDefaultNetwork(func() { calls++ })
	CoreDefaultNetworkChanged()
	CoreDefaultNetworkChanged()
	cancel()
	CoreDefaultNetworkChanged()
	if calls != 2 {
		t.Fatalf("watcher called %d times, want 2", calls)
	}
}
