package plugin

import (
	"context"
	"errors"
	"net"
	"slices"
	"sync"
	"sync/atomic"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/common/sniff"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/log"
	dnsOutbound "github.com/sagernet/sing-box/protocol/dns"
	R "github.com/sagernet/sing-box/route/rule"
	"github.com/sagernet/sing/common/buf"
	"github.com/sagernet/sing/common/bufio"
	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/service"
)

// BrainOptions: the outbound table decide() indexes into
// (docs/architecture/brain-config.md).
type BrainOptions struct {
	Outbounds []string `json:"outbounds"`
}

// The sniffers sing-box's router uses when a sniff rule names none
// (route/route.go actionSniff, defaultPacketSniffers).
var (
	streamSniffers = []sniff.StreamSniffer{
		sniff.TLSClientHello,
		sniff.HTTPHost,
		sniff.StreamDomainNameQuery,
		sniff.BitTorrent,
		sniff.SSH,
		sniff.RDP,
	}
	packetSniffers = []sniff.PacketSniffer{
		sniff.DomainNameQuery,
		sniff.QUICClientHello,
		sniff.STUNMessage,
		sniff.UTP,
		sniff.UDPTracker,
		sniff.DTLSRecord,
		sniff.NTP,
	}
)

// QUIC client hellos can span packets; this many are read at most.
const maxSniffPackets = 4

// Brain is the fkbrain outbound: route.final of the generated
// configuration, so sing-box hands it every connection whole
// (route/route.go: the last outbound of the chain implements
// adapter.ConnectionHandler). There are no route rules, so sing-box neither
// sniffs nor hijacks DNS: fkbrain does both itself, fills the metadata
// record, asks decide() once, and passes the connection to the chosen
// outbound through the connection manager (as the router would).
//
// A failed decide (no brain, no snapshot, an error code, an unknown
// generation or index) rejects the connection: fail closed, never direct.
type Brain struct {
	outbound.Adapter
	ctx         context.Context
	logger      log.ContextLogger
	manager     adapter.OutboundManager
	inbounds    adapter.InboundManager
	connections adapter.ConnectionManager
	dnsRouter   adapter.DNSRouter
	generation  uint32
	inboundOnce sync.Once
	inboundTags []string
}

var (
	_ adapter.ConnectionHandler       = (*Brain)(nil)
	_ adapter.PacketConnectionHandler = (*Brain)(nil)
)

func NewBrain(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, options BrainOptions) (adapter.Outbound, error) {
	if len(options.Outbounds) == 0 {
		return nil, E.New("fkbrain: empty outbound table")
	}
	seen := make(map[string]bool, len(options.Outbounds))
	for _, name := range options.Outbounds {
		if name == "" || name == tag || seen[name] {
			return nil, E.New("fkbrain: empty, self or duplicate tag in the outbound table: ", name)
		}
		seen[name] = true
	}
	brain := &Brain{
		Adapter:     outbound.NewAdapter(TypeBrain, tag, []string{N.NetworkTCP, N.NetworkUDP}, options.Outbounds),
		ctx:         ctx,
		logger:      logger,
		manager:     service.FromContext[adapter.OutboundManager](ctx),
		inbounds:    service.FromContext[adapter.InboundManager](ctx),
		connections: service.FromContext[adapter.ConnectionManager](ctx),
		dnsRouter:   service.FromContext[adapter.DNSRouter](ctx),
		generation:  pendingGeneration.Load(),
	}
	if brain.manager == nil || brain.connections == nil || brain.dnsRouter == nil {
		return nil, E.New("fkbrain: missing outbound manager, connection manager or DNS router")
	}
	registerGeneration(brain.generation, options.Outbounds)
	return brain, nil
}

// Generation is the configuration generation this instance was created for.
func (b *Brain) Generation() uint32 { return b.generation }

func (b *Brain) inboundIndex(tag string) uint32 {
	b.inboundOnce.Do(func() {
		if b.inbounds == nil {
			return
		}
		for _, inbound := range b.inbounds.Inbounds() {
			b.inboundTags = append(b.inboundTags, inbound.Tag())
		}
	})
	if index := slices.Index(b.inboundTags, tag); index >= 0 {
		return uint32(index)
	}
	return InboundUnknown
}

type verdict struct {
	action   uint16
	outbound adapter.Outbound
	err      error
}

// decide asks the brain about one connection.
func (b *Brain) decide(metadata *adapter.InboundContext) verdict {
	holder := currentDecider.Load()
	if holder == nil {
		counters.decideFailed.Add(1)
		return verdict{action: ActionReject, err: E.New("fkbrain: no brain attached")}
	}
	meta := metaPool.Get().(*Meta)
	meta.fill(metadata, b.inboundIndex(metadata.Inbound))
	var decision Decision
	start := time.Now()
	code := holder.Decide(meta, &decision)
	counters.observeDecide(time.Since(start))
	metaPool.Put(meta)
	if code != CodeOK {
		counters.decideFailed.Add(1)
		return verdict{action: ActionReject, err: E.New("fkbrain: decide failed with code ", code)}
	}
	if decision.Flags&DecisionDNSMiss != 0 {
		counters.dnsMiss.Add(1)
	}
	switch decision.Action {
	case ActionReject, ActionRejectDrop, ActionHijackDNS:
		return verdict{action: decision.Action}
	case ActionRoute:
	default:
		counters.decideFailed.Add(1)
		return verdict{action: ActionReject, err: E.New("fkbrain: unknown action ", decision.Action)}
	}
	tag, loaded := outboundTag(decision.Generation, decision.Outbound)
	if !loaded {
		counters.decideFailed.Add(1)
		return verdict{action: ActionReject, err: E.New("fkbrain: no outbound ", decision.Outbound, " in generation ", decision.Generation)}
	}
	selected, loaded := b.manager.Outbound(tag)
	if !loaded {
		counters.decideFailed.Add(1)
		return verdict{action: ActionReject, err: E.New("fkbrain: outbound not running: ", tag)}
	}
	return verdict{action: ActionRoute, outbound: selected}
}

// unfake rewrites a fake-ip destination to the name it stands for (the
// brain's reverse lookup, memory only): decide then sees the domain, the
// outbound dials the name (a proxy resolves it remotely; direct resolves it
// through fkdns for real), and RouteOriginalDestination keeps the address
// the app used, so sing-box's connection manager translates UDP replies
// back to it (the same fields its own route options set). A stale fake
// address (inside the range, unknown to the brain) cannot be routed: false.
func unfake(metadata *adapter.InboundContext) bool {
	brain := dnsBrain()
	if brain == nil || !metadata.Destination.IsIP() {
		return true
	}
	name, fake := brain.Reverse(metadata.Destination.Addr)
	if !fake {
		return true
	}
	if name == "" {
		counters.fakeIPStale.Add(1)
		return false
	}
	counters.fakeIP.Add(1)
	metadata.RouteOriginalDestination = metadata.Destination
	metadata.Destination = M.Socksaddr{Fqdn: name, Port: metadata.Destination.Port}
	metadata.FakeIP = true
	return true
}

var errStaleFakeIP = E.New("fkbrain: stale fake-ip address (unknown to the brain)")

func rejectError(action uint16) error {
	if action == ActionRejectDrop {
		return &R.RejectedError{Cause: R.ErrDrop}
	}
	return &R.RejectedError{Cause: R.ErrReset}
}

func (b *Brain) NewConnection(ctx context.Context, conn net.Conn, metadata adapter.InboundContext, onClose N.CloseHandlerFunc) {
	counters.connectionsTCP.Add(1)
	onClose = N.AppendClose(onClose, func(error) { counters.connectionsTCP.Add(-1) })
	if !unfake(&metadata) {
		counters.rejected.Add(1)
		N.CloseOnHandshakeFailure(conn, onClose, errStaleFakeIP)
		return
	}
	if metadata.Protocol == "" && !sniff.Skip(&metadata) {
		sniffBuffer := buf.NewPacket()
		_ = sniff.PeekStream(ctx, &metadata, conn, nil, sniffBuffer, C.ReadPayloadTimeout, streamSniffers...)
		if !sniffBuffer.IsEmpty() {
			conn = bufio.NewCachedConn(conn, sniffBuffer)
		} else {
			sniffBuffer.Release()
		}
	}
	// DNS to port 53 is the brain's DNS (mihomo dns-hijack any:53, the
	// Android service's hijack-dns today); the rules decide everything else.
	if metadata.Protocol == C.ProtocolDNS && metadata.Destination.Port == 53 {
		b.hijackStream(ctx, conn, metadata, onClose)
		return
	}
	result := b.decide(&metadata)
	switch result.action {
	case ActionHijackDNS:
		b.hijackStream(ctx, conn, metadata, onClose)
		return
	case ActionReject, ActionRejectDrop:
		counters.rejected.Add(1)
		if result.err != nil {
			b.logger.ErrorContext(ctx, result.err)
		}
		N.CloseOnHandshakeFailure(conn, onClose, rejectError(result.action))
		return
	}
	counters.routed(result.outbound.Tag())
	metadata.Outbound = result.outbound.Tag()
	conn = bufio.NewInt64CounterConn(conn, []*atomic.Int64{&counters.uplink}, []*atomic.Int64{&counters.downlink})
	if handler, isHandler := result.outbound.(adapter.ConnectionHandler); isHandler {
		handler.NewConnection(ctx, conn, metadata, onClose)
		return
	}
	b.connections.NewConnection(ctx, result.outbound, conn, metadata, onClose)
}

func (b *Brain) hijackStream(ctx context.Context, conn net.Conn, metadata adapter.InboundContext, onClose N.CloseHandlerFunc) {
	counters.dnsHijacked.Add(1)
	ctx = withClientQuery(ctx)
	metadata.Destination = M.Socksaddr{}
	err := N.ReportConnHandshakeSuccess(conn, conn)
	if err != nil {
		N.CloseOnHandshakeFailure(conn, onClose, E.Cause(err, "report handshake success"))
		return
	}
	for {
		_ = conn.SetReadDeadline(time.Now().Add(C.DNSTimeout))
		err = dnsOutbound.HandleStreamDNSRequest(ctx, b.dnsRouter, conn, metadata)
		if err != nil {
			break
		}
	}
	conn.Close()
	if E.IsClosedOrCanceled(err) {
		err = nil
	}
	onClose(err)
}

func (b *Brain) NewPacketConnection(ctx context.Context, conn N.PacketConn, metadata adapter.InboundContext, onClose N.CloseHandlerFunc) {
	counters.connectionsUDP.Add(1)
	onClose = N.AppendClose(onClose, func(error) { counters.connectionsUDP.Add(-1) })
	if !unfake(&metadata) {
		counters.rejected.Add(1)
		N.CloseOnHandshakeFailure(conn, onClose, errStaleFakeIP)
		return
	}
	var packets []*N.PacketBuffer
	if metadata.Protocol == "" {
		var err error
		packets, err = b.sniffPackets(ctx, conn, &metadata)
		if err != nil {
			N.ReleaseMultiPacketBuffer(packets)
			N.CloseOnHandshakeFailure(conn, onClose, err)
			return
		}
	}
	if metadata.Protocol == C.ProtocolDNS && metadata.Destination.Port == 53 {
		b.hijackPacket(ctx, conn, packets, metadata, onClose)
		return
	}
	result := b.decide(&metadata)
	switch result.action {
	case ActionHijackDNS:
		b.hijackPacket(ctx, conn, packets, metadata, onClose)
		return
	case ActionReject, ActionRejectDrop:
		counters.rejected.Add(1)
		if result.err != nil {
			b.logger.ErrorContext(ctx, result.err)
		}
		N.ReleaseMultiPacketBuffer(packets)
		N.CloseOnHandshakeFailure(conn, onClose, rejectError(result.action))
		return
	}
	counters.routed(result.outbound.Tag())
	metadata.Outbound = result.outbound.Tag()
	for _, packet := range slices.Backward(packets) {
		conn = bufio.NewCachedPacketConn(conn, packet.Buffer, packet.Destination)
		N.PutPacketBuffer(packet)
	}
	conn = bufio.NewInt64CounterPacketConn(conn, []*atomic.Int64{&counters.uplink}, nil, []*atomic.Int64{&counters.downlink}, nil)
	if handler, isHandler := result.outbound.(adapter.PacketConnectionHandler); isHandler {
		handler.NewPacketConnection(ctx, conn, metadata, onClose)
		return
	}
	b.connections.NewPacketConnection(ctx, result.outbound, conn, metadata, onClose)
}

// sniffPackets reads the first packet (more for a fragmented QUIC hello)
// within sing-box's payload timeout; no packet in time is not an error.
func (b *Brain) sniffPackets(ctx context.Context, conn N.PacketConn, metadata *adapter.InboundContext) ([]*N.PacketBuffer, error) {
	var packets []*N.PacketBuffer
	for len(packets) < maxSniffPackets {
		buffer := buf.NewPacket()
		_ = conn.SetReadDeadline(time.Now().Add(C.ReadPayloadTimeout))
		destination, err := conn.ReadPacket(buffer)
		_ = conn.SetReadDeadline(time.Time{})
		if err != nil {
			buffer.Release()
			if E.IsTimeout(err) {
				return packets, nil
			}
			return packets, err
		}
		packet := N.NewPacketBuffer()
		*packet = N.PacketBuffer{Buffer: buffer, Destination: destination}
		packets = append(packets, packet)
		var sniffErr error
		if len(packets) > 1 {
			sniffErr = sniff.PeekPacket(ctx, metadata, buffer.Bytes(), sniff.QUICClientHello)
		} else {
			sniffErr = sniff.PeekPacket(ctx, metadata, buffer.Bytes(), packetSniffers...)
		}
		if !errors.Is(sniffErr, sniff.ErrNeedMoreData) {
			return packets, nil
		}
	}
	return packets, nil
}

func (b *Brain) hijackPacket(ctx context.Context, conn N.PacketConn, packets []*N.PacketBuffer, metadata adapter.InboundContext, onClose N.CloseHandlerFunc) {
	counters.dnsHijacked.Add(1)
	ctx = withClientQuery(ctx)
	err := N.ReportPacketConnHandshakeSuccess(conn, nil)
	if err != nil {
		N.ReleaseMultiPacketBuffer(packets)
		err = E.Cause(err, "report handshake success")
	} else {
		err = dnsOutbound.NewDNSPacketConnection(ctx, b.dnsRouter, conn, packets, metadata)
	}
	if err != nil && E.IsClosedOrCanceled(err) {
		err = nil
	}
	N.CloseOnHandshakeFailure(conn, onClose, err)
}

// DialContext / ListenPacket serve callers that dial fkbrain directly
// (nothing in the generated configuration does): decide on the destination
// alone, no sniffing.
func (b *Brain) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	metadata := adapter.InboundContext{Network: N.NetworkName(network), Destination: destination}
	result := b.decide(&metadata)
	if result.action != ActionRoute {
		return nil, E.Errors(result.err, rejectError(result.action))
	}
	return result.outbound.DialContext(ctx, network, destination)
}

func (b *Brain) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	metadata := adapter.InboundContext{Network: N.NetworkUDP, Destination: destination}
	result := b.decide(&metadata)
	if result.action != ActionRoute {
		return nil, E.Errors(result.err, rejectError(result.action))
	}
	return result.outbound.ListenPacket(ctx, destination)
}
