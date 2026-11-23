package plugin

import (
	"context"
	"net"
	"sync/atomic"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/log"
	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/service"
)

// Decision is what the brain (Rust in the target design) returns for one
// connection: reject it, or route it to Outbounds[Index].
type Decision struct {
	Reject bool
	Index  int
}

// Decider is called synchronously for every connection the brain outbound
// receives. The probe wires it to the Rust callback in ffi/.
type Decider func(metadata *adapter.InboundContext) Decision

var currentDecider atomic.Pointer[Decider]

// SetDecider installs the process-wide decide function.
func SetDecider(decider Decider) { currentDecider.Store(&decider) }

// BrainOutboundOptions lists the candidate outbound tags; Decision.Index
// points into it, so the FFI carries an index instead of a string.
type BrainOutboundOptions struct {
	Outbounds []string `json:"outbounds"`
}

// BrainOutbound is set as route.final: sing-box hands it the whole connection
// (route/route.go calls adapter.ConnectionHandler.NewConnection when the last
// outbound implements it), it asks the decider, then forwards to the chosen
// outbound through the connection manager — the same call the router makes.
type BrainOutbound struct {
	outbound.Adapter
	manager     adapter.OutboundManager
	connections adapter.ConnectionManager
	logger      log.ContextLogger
	tags        []string
	decided     atomic.Int64
	rejected    atomic.Int64
}

var (
	_ adapter.ConnectionHandler       = (*BrainOutbound)(nil)
	_ adapter.PacketConnectionHandler = (*BrainOutbound)(nil)
)

func NewBrainOutbound(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, options BrainOutboundOptions) (adapter.Outbound, error) {
	if len(options.Outbounds) == 0 {
		return nil, E.New("fkbrain: no outbounds")
	}
	manager := service.FromContext[adapter.OutboundManager](ctx)
	connections := service.FromContext[adapter.ConnectionManager](ctx)
	if manager == nil || connections == nil {
		return nil, E.New("fkbrain: missing outbound or connection manager")
	}
	return &BrainOutbound{
		Adapter:     outbound.NewAdapter(TypeBrain, tag, []string{N.NetworkTCP, N.NetworkUDP}, options.Outbounds),
		manager:     manager,
		connections: connections,
		logger:      logger,
		tags:        options.Outbounds,
	}, nil
}

func (b *BrainOutbound) Decided() int64  { return b.decided.Load() }
func (b *BrainOutbound) Rejected() int64 { return b.rejected.Load() }

func (b *BrainOutbound) decide(metadata *adapter.InboundContext) (adapter.Outbound, error) {
	decider := currentDecider.Load()
	if decider == nil {
		return nil, E.New("fkbrain: no decider registered")
	}
	decision := (*decider)(metadata)
	b.decided.Add(1)
	if decision.Reject {
		b.rejected.Add(1)
		return nil, E.New("fkbrain: rejected by decider")
	}
	if decision.Index < 0 || decision.Index >= len(b.tags) {
		return nil, E.New("fkbrain: decider returned index out of range")
	}
	selected, loaded := b.manager.Outbound(b.tags[decision.Index])
	if !loaded {
		return nil, E.New("fkbrain: outbound not found: ", b.tags[decision.Index])
	}
	return selected, nil
}

func (b *BrainOutbound) NewConnection(ctx context.Context, conn net.Conn, metadata adapter.InboundContext, onClose N.CloseHandlerFunc) {
	selected, err := b.decide(&metadata)
	if err != nil {
		conn.Close()
		onClose(err)
		return
	}
	metadata.Outbound = selected.Tag()
	if handler, isHandler := selected.(adapter.ConnectionHandler); isHandler {
		handler.NewConnection(ctx, conn, metadata, onClose)
		return
	}
	b.connections.NewConnection(ctx, selected, conn, metadata, onClose)
}

func (b *BrainOutbound) NewPacketConnection(ctx context.Context, conn N.PacketConn, metadata adapter.InboundContext, onClose N.CloseHandlerFunc) {
	selected, err := b.decide(&metadata)
	if err != nil {
		conn.Close()
		onClose(err)
		return
	}
	metadata.Outbound = selected.Tag()
	if handler, isHandler := selected.(adapter.PacketConnectionHandler); isHandler {
		handler.NewPacketConnection(ctx, conn, metadata, onClose)
		return
	}
	b.connections.NewPacketConnection(ctx, selected, conn, metadata, onClose)
}

// DialContext covers callers that dial the brain directly (no inbound
// metadata beyond the destination).
func (b *BrainOutbound) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	metadata := adapter.InboundContext{Network: N.NetworkName(network), Destination: destination}
	selected, err := b.decide(&metadata)
	if err != nil {
		return nil, err
	}
	return selected.DialContext(ctx, network, destination)
}

func (b *BrainOutbound) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	metadata := adapter.InboundContext{Network: N.NetworkUDP, Destination: destination}
	selected, err := b.decide(&metadata)
	if err != nil {
		return nil, err
	}
	return selected.ListenPacket(ctx, destination)
}
