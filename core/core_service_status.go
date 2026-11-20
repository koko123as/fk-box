package libbox

// The service status of the StartedService gRPC service (SubscribeServiceStatus):
// fk-box's own, for every kernel. libbox's service reported STARTING, STARTED,
// STOPPING, IDLE and FATAL (with the error) for the sing-box it ran; the same
// states are now kept here around fk-box's core switch (core_service.go):
// STARTING while a start or reload is in progress (beginCoreSwitch), then
// STARTED, or FATAL with the error of the last start when it failed, or IDLE
// when a stop interrupted it; STOPPING and IDLE around a stop; FATAL for an
// error the app reports itself (SetCoreError).

import (
	"sync"

	"github.com/sagernet/sing-box/daemon"
	"google.golang.org/grpc"
)

type coreServiceStatus struct {
	access  sync.Mutex
	current *daemon.ServiceStatus
	changed chan struct{}
	// startErr is the error of the last start in the running switch.
	startErr error
}

var coreStatus = newCoreServiceStatus()

func newCoreServiceStatus() *coreServiceStatus {
	return &coreServiceStatus{
		current: &daemon.ServiceStatus{Status: daemon.ServiceStatus_IDLE},
		changed: make(chan struct{}),
	}
}

func (c *coreServiceStatus) setLocked(status *daemon.ServiceStatus) {
	c.current = status
	close(c.changed)
	c.changed = make(chan struct{})
}

func (c *coreServiceStatus) set(status daemon.ServiceStatus_Type) {
	c.access.Lock()
	defer c.access.Unlock()
	c.setLocked(&daemon.ServiceStatus{Status: status})
}

// fatal reports an error: the service is not running because of it.
func (c *coreServiceStatus) fatal(message string) {
	c.access.Lock()
	defer c.access.Unlock()
	c.setLocked(&daemon.ServiceStatus{Status: daemon.ServiceStatus_FATAL, ErrorMessage: message})
}

// switchStarted marks the first start of a switch (STARTING).
func (c *coreServiceStatus) switchStarted() {
	c.access.Lock()
	defer c.access.Unlock()
	c.startErr = nil
	c.setLocked(&daemon.ServiceStatus{Status: daemon.ServiceStatus_STARTING})
}

// startFinished records the result of one start inside a switch; the last
// one decides the status when the switch ends (the routing fallback start
// replaces the failed first start).
func (c *coreServiceStatus) startFinished(err error) {
	c.access.Lock()
	defer c.access.Unlock()
	c.startErr = err
}

// switchFinished settles the status when the last start of a switch
// returned: running tells whether a kernel runs now.
func (c *coreServiceStatus) switchFinished(running bool) {
	c.access.Lock()
	defer c.access.Unlock()
	switch {
	case running:
		c.setLocked(&daemon.ServiceStatus{Status: daemon.ServiceStatus_STARTED})
	case c.startErr != nil:
		c.setLocked(&daemon.ServiceStatus{Status: daemon.ServiceStatus_FATAL, ErrorMessage: c.startErr.Error()})
	default:
		c.setLocked(&daemon.ServiceStatus{Status: daemon.ServiceStatus_IDLE})
	}
	c.startErr = nil
}

// stopping marks a stop; it returns false (and changes nothing) when the
// service was not starting or running, so a FATAL status stays visible after
// the app's stop that follows a failed start, as with libbox's service.
func (c *coreServiceStatus) stopping() bool {
	c.access.Lock()
	defer c.access.Unlock()
	switch c.current.Status {
	case daemon.ServiceStatus_IDLE, daemon.ServiceStatus_FATAL:
		return false
	}
	c.setLocked(&daemon.ServiceStatus{Status: daemon.ServiceStatus_STOPPING})
	return true
}

func (c *coreServiceStatus) load() (*daemon.ServiceStatus, <-chan struct{}) {
	c.access.Lock()
	defer c.access.Unlock()
	return c.current, c.changed
}

// Serve is the SubscribeServiceStatus stream: the current status, then every
// change (states that change faster than the client reads are coalesced to
// the latest one).
func (c *coreServiceStatus) Serve(server grpc.ServerStreamingServer[daemon.ServiceStatus]) error {
	var sent *daemon.ServiceStatus
	for {
		current, changed := c.load()
		if current != sent {
			if err := server.Send(current); err != nil {
				return err
			}
			sent = current
		}
		select {
		case <-changed:
		case <-server.Context().Done():
			return server.Context().Err()
		}
	}
}

// coreKernelRunning reports whether a kernel runs now: Xray or mihomo, or
// the sing-box of the CommandServer host. The standalone host (desktop
// fk-core) serves no StartedService, so its sing-box is not looked at.
func coreKernelRunning() bool {
	return otherCore() != nil || runningSingBox() != nil
}
