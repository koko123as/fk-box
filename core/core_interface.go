package libbox

import (
	"context"
	"errors"

	"github.com/sagernet/sing-box/daemon"
)

// Core is what a client drives: the hello handshake, the service lifecycle,
// outbound selection and the UI streams. Every client speaks the same
// protocol (protocol/methods.json) to it.
//
// Android runs the core in its own process through gomobile: InProcessCore
// wraps the CommandServer and the Go-side streams that the Kotlin host
// already calls, so nothing changes there. Desktop clients run the core as
// the child process fk-core (core/fkcore), which serves the same protocol
// over stdio or a local socket (fkhost.ProtocolServer, core/README.md).
//
// Streams deliver their events (protocol "events") to the sink installed with
// SetEventSink; Subscribe* turns each one on while a page shows it.
type Core interface {
	// Hello runs the "hello" method: JSON {protocolVersion, client,
	// clientVersion, capabilities} in, {protocolVersion, coreVersion, ok,
	// capabilities} out, or the protocol version mismatch error.
	Hello(request string) (string, error)
	// Start starts the profile on its core ("auto", "sing-box", "xray" or "mihomo").
	Start(core string, config string, options *OverrideOptions) error
	// Reload replaces the running profile; a stopped service is started.
	Reload(core string, config string, options *OverrideOptions) error
	// Stop stops whichever core is running.
	Stop() error
	// SelectOutbound picks tag in selector group (the "switch node" action).
	SelectOutbound(group string, tag string) error
	// SubscribeConnections streams the connection rows ("connections" events).
	SubscribeConnections(visible bool)
	// SubscribeLogs streams the log lines ("logs" events).
	SubscribeLogs(visible bool)
	// SubscribeDashboard streams the dashboard's memory card for one owner
	// while it is visible, the app is in the foreground and the screen is on.
	SubscribeDashboard(owner int32, visible bool, foreground bool, screenOn bool)
	// SetEventSink installs (nil removes) the receiver of the core's events.
	SetEventSink(sink UIEventSink)
}

// InProcessCore is the Core running in the client's own process around one
// CommandServer.
type InProcessCore struct {
	server *CommandServer
}

var _ Core = (*InProcessCore)(nil)

// NewInProcessCore wraps server, the CommandServer the platform started.
func NewInProcessCore(server *CommandServer) *InProcessCore {
	return &InProcessCore{server: server}
}

func (c *InProcessCore) Hello(request string) (string, error) {
	return UIInvoke("hello", request)
}

func (c *InProcessCore) Start(core string, config string, options *OverrideOptions) error {
	if c.server == nil {
		return errInProcessCoreNoServer
	}
	return c.server.StartOrReloadCoreService(core, config, options)
}

func (c *InProcessCore) Reload(core string, config string, options *OverrideOptions) error {
	return c.Start(core, config, options)
}

func (c *InProcessCore) Stop() error {
	if c.server == nil {
		return errInProcessCoreNoServer
	}
	return c.server.CloseCoreService()
}

func (c *InProcessCore) SelectOutbound(group string, tag string) error {
	if c.server == nil {
		return errInProcessCoreNoServer
	}
	service := &coreStartedService{c.server.StartedService}
	_, err := service.SelectOutbound(context.Background(), &daemon.SelectOutboundRequest{GroupTag: group, OutboundTag: tag})
	return err
}

func (c *InProcessCore) SubscribeConnections(visible bool) {
	UIConnectionStreamSetRowsVisible(visible)
}

func (c *InProcessCore) SubscribeLogs(visible bool) {
	UILogStreamSetVisible(visible)
}

func (c *InProcessCore) SubscribeDashboard(owner int32, visible bool, foreground bool, screenOn bool) {
	UIDashboardMemorySetSignals(owner, visible, foreground, screenOn)
}

func (c *InProcessCore) SetEventSink(sink UIEventSink) {
	SetUIEventSink(sink)
}

var errInProcessCoreNoServer = errors.New("core: no command server")
