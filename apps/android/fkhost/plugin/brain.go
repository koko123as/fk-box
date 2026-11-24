package plugin

import (
	"context"
	"errors"
	"net"
	"slices"
	"sync"
	"sync/atomic"
	"time"

	"github.com/koko123as/fk-box/apps/android/fkhost/upstream"
)

// BrainOptions: the outbound table decide() indexes into
// (docs/architecture/brain-config.md).
type BrainOptions struct {
	Outbounds []string `json:"outbounds"`
}

// The sniffers sing-box's router uses when a sniff rule names none
// (route/route.go actionSniff, defaultPacketSniffers).
var (
	streamSniffers = []upstream.StreamSniffer{
		upstream.TLSClientHello,
		upstream.HTTPHost,
		upstream.StreamDomainNameQuery,
		upstream.BitTorrent,
		upstream.SSH,
		upstream.RDP,
	}
	packetSniffers = []upstream.PacketSniffer{
		upstream.DomainNameQuery,
		upstream.QUICClientHello,
		upstream.STUNMessage,
		upstream.UTP,
		upstream.UDPTracker,
		upstream.DTLSRecord,
		upstream.NTP,
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
	upstream.Adapter
	ctx         context.Context
	logger      upstream.ContextLogger
	manager     upstream.OutboundManager
	inbounds    upstream.InboundManager
	connections upstream.ConnectionManager
	dnsRouter   upstream.DNSRouter
	generation  uint32
	inboundOnce sync.Once
	inboundTags []string
}

var (
	_ upstream.ConnectionHandler       = (*Brain)(nil)
	_ upstream.PacketConnectionHandler = (*Brain)(nil)
)

func NewBrain(ctx context.Context, router upstream.Router, logger upstream.ContextLogger, tag string, options BrainOptions) (upstream.Outbound, error) {
	if len(options.Outbounds) == 0 {
		return nil, upstream.NewError("fkbrain: empty outbound table")
	}
	seen := make(map[string]bool, len(options.Outbounds))
	for _, name := range options.Outbounds {
		if name == "" || name == tag || seen[name] {
			return nil, upstream.NewError("fkbrain: empty, self or duplicate tag in the outbound table: ", name)
		}
		seen[name] = true
	}
	brain := &Brain{
		Adapter:     upstream.NewAdapter(TypeBrain, tag, []string{upstream.NetworkTCP, upstream.NetworkUDP}, options.Outbounds),
		ctx:         ctx,
		logger:      logger,
		manager:     upstream.FromContext[upstream.OutboundManager](ctx),
		inbounds:    upstream.FromContext[upstream.InboundManager](ctx),
		connections: upstream.FromContext[upstream.ConnectionManager](ctx),
		dnsRouter:   upstream.FromContext[upstream.DNSRouter](ctx),
		generation:  pendingGeneration.Load(),
	}
	if brain.manager == nil || brain.connections == nil || brain.dnsRouter == nil {
		return nil, upstream.NewError("fkbrain: missing outbound manager, connection manager or DNS router")
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
	outbound upstream.Outbound
	err      error
}

// decide asks the brain about one connection.
func (b *Brain) decide(metadata *upstream.InboundContext) verdict {
	holder := currentDecider.Load()
	if holder == nil {
		counters.decideFailed.Add(1)
		return verdict{action: ActionReject, err: upstream.NewError("fkbrain: no brain attached")}
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
		return verdict{action: ActionReject, err: upstream.NewError("fkbrain: decide failed with code ", code)}
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
		return verdict{action: ActionReject, err: upstream.NewError("fkbrain: unknown action ", decision.Action)}
	}
	tag, loaded := outboundTag(decision.Generation, decision.Outbound)
	if !loaded {
		counters.decideFailed.Add(1)
		return verdict{action: ActionReject, err: upstream.NewError("fkbrain: no outbound ", decision.Outbound, " in generation ", decision.Generation)}
	}
	selected, loaded := b.manager.Outbound(tag)
	if !loaded {
		counters.decideFailed.Add(1)
		return verdict{action: ActionReject, err: upstream.NewError("fkbrain: outbound not running: ", tag)}
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
func unfake(metadata *upstream.InboundContext) bool {
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
	metadata.Destination = upstream.Socksaddr{Fqdn: name, Port: metadata.Destination.Port}
	metadata.FakeIP = true
	return true
}

var errStaleFakeIP = upstream.NewError("fkbrain: stale fake-ip address (unknown to the brain)")

func rejectError(action uint16) error {
	if action == ActionRejectDrop {
		return &upstream.RejectedError{Cause: upstream.ErrDrop}
	}
	return &upstream.RejectedError{Cause: upstream.ErrReset}
}

func (b *Brain) NewConnection(ctx context.Context, conn net.Conn, metadata upstream.InboundContext, onClose upstream.CloseHandlerFunc) {
	counters.connectionsTCP.Add(1)
	onClose = upstream.AppendClose(onClose, func(error) { counters.connectionsTCP.Add(-1) })
	if !unfake(&metadata) {
		counters.rejected.Add(1)
		upstream.CloseOnHandshakeFailure(conn, onClose, errStaleFakeIP)
		return
	}
	if metadata.Protocol == "" && !upstream.Skip(&metadata) {
		sniffBuffer := upstream.NewPacket()
		_ = upstream.PeekStream(ctx, &metadata, conn, nil, sniffBuffer, upstream.ReadPayloadTimeout, streamSniffers...)
		if !sniffBuffer.IsEmpty() {
			conn = upstream.NewCachedConn(conn, sniffBuffer)
		} else {
			sniffBuffer.Release()
		}
	}
	// DNS to port 53 is the brain's DNS (mihomo dns-hijack any:53, the
	// Android service's hijack-dns today); the rules decide everything else.
	if metadata.Protocol == upstream.ProtocolDNS && metadata.Destination.Port == 53 {
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
		upstream.CloseOnHandshakeFailure(conn, onClose, rejectError(result.action))
		return
	}
	counters.routed(result.outbound.Tag())
	metadata.Outbound = result.outbound.Tag()
	conn = upstream.NewInt64CounterConn(conn, []*atomic.Int64{&counters.uplink}, []*atomic.Int64{&counters.downlink})
	if handler, isHandler := result.outbound.(upstream.ConnectionHandler); isHandler {
		handler.NewConnection(ctx, conn, metadata, onClose)
		return
	}
	b.connections.NewConnection(ctx, result.outbound, conn, metadata, onClose)
}

func (b *Brain) hijackStream(ctx context.Context, conn net.Conn, metadata upstream.InboundContext, onClose upstream.CloseHandlerFunc) {
	counters.dnsHijacked.Add(1)
	ctx = withClientQuery(ctx)
	metadata.Destination = upstream.Socksaddr{}
	err := upstream.ReportConnHandshakeSuccess(conn, conn)
	if err != nil {
		upstream.CloseOnHandshakeFailure(conn, onClose, upstream.Cause(err, "report handshake success"))
		return
	}
	for {
		_ = conn.SetReadDeadline(time.Now().Add(upstream.DNSTimeout))
		err = upstream.HandleStreamDNSRequest(ctx, b.dnsRouter, conn, metadata)
		if err != nil {
			break
		}
	}
	conn.Close()
	if upstream.IsClosedOrCanceled(err) {
		err = nil
	}
	onClose(err)
}

func (b *Brain) NewPacketConnection(ctx context.Context, conn upstream.PacketConn, metadata upstream.InboundContext, onClose upstream.CloseHandlerFunc) {
	counters.connectionsUDP.Add(1)
	onClose = upstream.AppendClose(onClose, func(error) { counters.connectionsUDP.Add(-1) })
	if !unfake(&metadata) {
		counters.rejected.Add(1)
		upstream.CloseOnHandshakeFailure(conn, onClose, errStaleFakeIP)
		return
	}
	var packets []*upstream.PacketBuffer
	if metadata.Protocol == "" {
		var err error
		packets, err = b.sniffPackets(ctx, conn, &metadata)
		if err != nil {
			upstream.ReleaseMultiPacketBuffer(packets)
			upstream.CloseOnHandshakeFailure(conn, onClose, err)
			return
		}
	}
	if metadata.Protocol == upstream.ProtocolDNS && metadata.Destination.Port == 53 {
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
		upstream.ReleaseMultiPacketBuffer(packets)
		upstream.CloseOnHandshakeFailure(conn, onClose, rejectError(result.action))
		return
	}
	counters.routed(result.outbound.Tag())
	metadata.Outbound = result.outbound.Tag()
	for _, packet := range slices.Backward(packets) {
		conn = upstream.NewCachedPacketConn(conn, packet.Buffer, packet.Destination)
		upstream.PutPacketBuffer(packet)
	}
	conn = upstream.NewInt64CounterPacketConn(conn, []*atomic.Int64{&counters.uplink}, nil, []*atomic.Int64{&counters.downlink}, nil)
	if handler, isHandler := result.outbound.(upstream.PacketConnectionHandler); isHandler {
		handler.NewPacketConnection(ctx, conn, metadata, onClose)
		return
	}
	b.connections.NewPacketConnection(ctx, result.outbound, conn, metadata, onClose)
}

// sniffPackets reads the first packet (more for a fragmented QUIC hello)
// within sing-box's payload timeout; no packet in time is not an error.
func (b *Brain) sniffPackets(ctx context.Context, conn upstream.PacketConn, metadata *upstream.InboundContext) ([]*upstream.PacketBuffer, error) {
	var packets []*upstream.PacketBuffer
	for len(packets) < maxSniffPackets {
		buffer := upstream.NewPacket()
		_ = conn.SetReadDeadline(time.Now().Add(upstream.ReadPayloadTimeout))
		destination, err := conn.ReadPacket(buffer)
		_ = conn.SetReadDeadline(time.Time{})
		if err != nil {
			buffer.Release()
			if upstream.IsTimeout(err) {
				return packets, nil
			}
			return packets, err
		}
		packet := upstream.NewPacketBuffer()
		*packet = upstream.PacketBuffer{Buffer: buffer, Destination: destination}
		packets = append(packets, packet)
		var sniffErr error
		if len(packets) > 1 {
			sniffErr = upstream.PeekPacket(ctx, metadata, buffer.Bytes(), upstream.QUICClientHello)
		} else {
			sniffErr = upstream.PeekPacket(ctx, metadata, buffer.Bytes(), packetSniffers...)
		}
		if !errors.Is(sniffErr, upstream.ErrNeedMoreData) {
			return packets, nil
		}
	}
	return packets, nil
}

func (b *Brain) hijackPacket(ctx context.Context, conn upstream.PacketConn, packets []*upstream.PacketBuffer, metadata upstream.InboundContext, onClose upstream.CloseHandlerFunc) {
	counters.dnsHijacked.Add(1)
	ctx = withClientQuery(ctx)
	err := upstream.ReportPacketConnHandshakeSuccess(conn, nil)
	if err != nil {
		upstream.ReleaseMultiPacketBuffer(packets)
		err = upstream.Cause(err, "report handshake success")
	} else {
		err = upstream.NewDNSPacketConnection(ctx, b.dnsRouter, conn, packets, metadata)
	}
	if err != nil && upstream.IsClosedOrCanceled(err) {
		err = nil
	}
	upstream.CloseOnHandshakeFailure(conn, onClose, err)
}

// DialContext / ListenPacket serve callers that dial fkbrain directly
// (nothing in the generated configuration does): decide on the destination
// alone, no sniffing.
func (b *Brain) DialContext(ctx context.Context, network string, destination upstream.Socksaddr) (net.Conn, error) {
	metadata := upstream.InboundContext{Network: upstream.NetworkName(network), Destination: destination}
	result := b.decide(&metadata)
	if result.action != ActionRoute {
		return nil, upstream.Errors(result.err, rejectError(result.action))
	}
	return result.outbound.DialContext(ctx, network, destination)
}

func (b *Brain) ListenPacket(ctx context.Context, destination upstream.Socksaddr) (net.PacketConn, error) {
	metadata := upstream.InboundContext{Network: upstream.NetworkUDP, Destination: destination}
	result := b.decide(&metadata)
	if result.action != ActionRoute {
		return nil, upstream.Errors(result.err, rejectError(result.action))
	}
	return result.outbound.ListenPacket(ctx, destination)
}
