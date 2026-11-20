package libbox

// Connection tracking for Xray, which has no connection list of its own: the
// TUN inbound's dispatcher is wrapped (before the instance starts) so each
// connection is recorded with its traffic and the outbound routing picked.

import (
	"context"
	"fmt"
	"reflect"
	"strings"
	"sync"
	"sync/atomic"
	"time"
	"unsafe"

	"github.com/google/uuid"
	"github.com/sagernet/sing-box/daemon"

	"github.com/xtls/xray-core/common/buf"
	xrayNet "github.com/xtls/xray-core/common/net"
	xraySession "github.com/xtls/xray-core/common/session"
	xrayCore "github.com/xtls/xray-core/core"
	xrayInbound "github.com/xtls/xray-core/features/inbound"
	xrayRouting "github.com/xtls/xray-core/features/routing"
	xrayProxy "github.com/xtls/xray-core/proxy"
	xrayTun "github.com/xtls/xray-core/proxy/tun"
	"github.com/xtls/xray-core/transport"
)

type xrayTracker struct {
	access     sync.Mutex
	conns      map[string]*xrayConn
	closedUp   atomic.Int64
	closedDown atomic.Int64
}

type xrayConn struct {
	id        string
	network   string
	source    xrayNet.Destination
	target    xrayNet.Destination
	created   time.Time
	outbounds []*xraySession.Outbound
	content   *xraySession.Content
	cancel    context.CancelFunc
	link      *transport.Link
	up, down  atomic.Int64
	// route is what Xray's dispatcher decided (sniffed protocol, domain,
	// outbound tag). The dispatcher writes outbounds and content from its own
	// goroutine without synchronization, so they are read only by
	// settleRoute, on the first downlink write, which happens after routing;
	// readers of the connection list load this copy instead.
	route     atomic.Pointer[xrayRoute]
	routeOnce sync.Once
}

type xrayRoute struct {
	domain, outbound, protocol string
}

func (c *xrayConn) settleRoute() {
	c.routeOnce.Do(func() {
		route := &xrayRoute{}
		if len(c.outbounds) > 0 {
			outbound := c.outbounds[len(c.outbounds)-1]
			if outbound.Target.Address != nil && outbound.Target.Address.Family().IsDomain() {
				route.domain = outbound.Target.Address.Domain()
			} else if outbound.RouteTarget.Address != nil && outbound.RouteTarget.Address.Family().IsDomain() {
				route.domain = outbound.RouteTarget.Address.Domain()
			}
			route.outbound = outbound.Tag
		}
		if c.content != nil {
			route.protocol = c.content.Protocol
		}
		c.route.Store(route)
	})
}

func newXrayTracker() *xrayTracker {
	return &xrayTracker{conns: map[string]*xrayConn{}}
}

func (t *xrayTracker) snapshot() []*xrayConn {
	t.access.Lock()
	defer t.access.Unlock()
	list := make([]*xrayConn, 0, len(t.conns))
	for _, conn := range t.conns {
		list = append(list, conn)
	}
	return list
}

func (t *xrayTracker) totals() (up, down int64, count int) {
	// Summed under the lock instead of copying the map into a slice first;
	// each entry is two atomic loads.
	up, down = t.closedUp.Load(), t.closedDown.Load()
	t.access.Lock()
	for _, conn := range t.conns {
		up += conn.up.Load()
		down += conn.down.Load()
	}
	count = len(t.conns)
	t.access.Unlock()
	return
}

func (t *xrayTracker) close(id string) {
	t.access.Lock()
	conn := t.conns[id]
	t.access.Unlock()
	if conn != nil {
		conn.interrupt()
	}
}

func (t *xrayTracker) closeAll() {
	for _, conn := range t.snapshot() {
		conn.interrupt()
	}
}

func (c *xrayConn) interrupt() {
	c.cancel()
	if closer, ok := c.link.Reader.(interface{ Interrupt() }); ok {
		closer.Interrupt()
	}
	if closer, ok := c.link.Writer.(interface{ Close() error }); ok {
		_ = closer.Close()
	}
}

func (c *xrayConn) connection(protocols map[string]string) *daemon.Connection {
	connection := &daemon.Connection{
		Id:            c.id,
		Inbound:       "tun-in",
		InboundType:   "tun",
		Network:       c.network,
		Source:        c.source.NetAddr(),
		Destination:   c.target.NetAddr(),
		CreatedAt:     c.created.UnixMilli(),
		UplinkTotal:   c.up.Load(),
		DownlinkTotal: c.down.Load(),
		IpVersion:     4,
	}
	if c.target.Address != nil && c.target.Address.Family().IsIPv6() {
		connection.IpVersion = 6
	}
	// Until the first downlink write the route is not known yet.
	if route := c.route.Load(); route != nil {
		connection.Domain = route.domain
		if route.outbound != "" {
			connection.Outbound = route.outbound
			connection.OutboundType = protocols[route.outbound]
			connection.ChainList = []string{route.outbound}
		}
		connection.Protocol = route.protocol
	}
	return connection
}

// trackingDispatcher wraps the dispatcher the TUN inbound hands connections to.
type trackingDispatcher struct {
	xrayRouting.Dispatcher
	tracker *xrayTracker
}

func (d *trackingDispatcher) DispatchLink(ctx context.Context, destination xrayNet.Destination, link *transport.Link) error {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	outbounds := xraySession.OutboundsFromContext(ctx)
	if len(outbounds) == 0 {
		outbounds = []*xraySession.Outbound{{}}
		ctx = xraySession.ContextWithOutbounds(ctx, outbounds)
	}
	content := xraySession.ContentFromContext(ctx)
	if content == nil {
		content = new(xraySession.Content)
		ctx = xraySession.ContextWithContent(ctx, content)
	}
	conn := &xrayConn{
		id: uuid.NewString(), network: strings.ToLower(destination.Network.SystemString()),
		target: destination, created: time.Now(), outbounds: outbounds, content: content, cancel: cancel,
	}
	if inbound := xraySession.InboundFromContext(ctx); inbound != nil {
		conn.source = inbound.Source
	}
	// DNS for the dashboard count (ui_dns.go): Xray answers port 53 through
	// its dns outbound, one datagram per UDP query; a TCP flow counts once.
	var datagrams *atomic.Int64
	if destination.Port == 53 {
		if destination.Network == xrayNet.Network_UDP {
			datagrams = &uiOtherDNSQueries
		} else {
			uiOtherDNSQueries.Add(1)
		}
	}
	link = &transport.Link{
		Reader: &countingReader{TimeoutReader: asTimeoutReader(link.Reader), counter: &conn.up, datagrams: datagrams},
		Writer: &countingWriter{Writer: link.Writer, counter: &conn.down, settle: conn.settleRoute},
	}
	conn.link = link
	d.tracker.access.Lock()
	d.tracker.conns[conn.id] = conn
	d.tracker.access.Unlock()
	defer func() {
		d.tracker.access.Lock()
		delete(d.tracker.conns, conn.id)
		d.tracker.access.Unlock()
		d.tracker.closedUp.Add(conn.up.Load())
		d.tracker.closedDown.Add(conn.down.Load())
	}()
	return d.Dispatcher.DispatchLink(ctx, destination, link)
}

type countingReader struct {
	buf.TimeoutReader
	counter *atomic.Int64
	// datagrams, when set, counts the UDP packets read (DNS queries).
	datagrams *atomic.Int64
}

func (r *countingReader) count(mb buf.MultiBuffer) {
	r.counter.Add(int64(mb.Len()))
	if r.datagrams != nil && len(mb) > 0 {
		r.datagrams.Add(int64(len(mb)))
	}
}

func (r *countingReader) ReadMultiBuffer() (buf.MultiBuffer, error) {
	mb, err := r.TimeoutReader.ReadMultiBuffer()
	r.count(mb)
	return mb, err
}

func (r *countingReader) ReadMultiBufferTimeout(timeout time.Duration) (buf.MultiBuffer, error) {
	mb, err := r.TimeoutReader.ReadMultiBufferTimeout(timeout)
	r.count(mb)
	return mb, err
}

func (r *countingReader) Interrupt() {
	if interrupter, ok := r.TimeoutReader.(interface{ Interrupt() }); ok {
		interrupter.Interrupt()
	}
}

type countingWriter struct {
	buf.Writer
	counter *atomic.Int64
	// settle copies the routing result on the first write: the outbound
	// writes the downlink only after the dispatcher routed the connection.
	settle func()
}

func (w *countingWriter) WriteMultiBuffer(mb buf.MultiBuffer) error {
	if w.settle != nil {
		w.settle()
	}
	w.counter.Add(int64(mb.Len()))
	return w.Writer.WriteMultiBuffer(mb)
}

func (w *countingWriter) Close() error {
	if closer, ok := w.Writer.(interface{ Close() error }); ok {
		return closer.Close()
	}
	return nil
}

// plainTimeoutReader gives a reader without a timeout read one, which the
// dispatcher's sniffer requires.
type plainTimeoutReader struct{ buf.Reader }

func (r plainTimeoutReader) ReadMultiBufferTimeout(time.Duration) (buf.MultiBuffer, error) {
	return r.Reader.ReadMultiBuffer()
}

func asTimeoutReader(reader buf.Reader) buf.TimeoutReader {
	if timeout, ok := reader.(buf.TimeoutReader); ok {
		return timeout
	}
	return plainTimeoutReader{reader}
}

// installXrayTracker swaps the TUN handler's dispatcher for the tracking one.
// Called between xrayCore.New and Start, before the stack accepts traffic.
func installXrayTracker(instance *xrayCore.Instance, tag string) (*xrayTracker, error) {
	manager, ok := instance.GetFeature(xrayInbound.ManagerType()).(xrayInbound.Manager)
	if !ok {
		return nil, fmt.Errorf("no inbound manager")
	}
	handler, err := manager.GetHandler(context.Background(), tag)
	if err != nil {
		return nil, err
	}
	holder, ok := handler.(interface{ GetInbound() xrayProxy.Inbound })
	if !ok {
		return nil, fmt.Errorf("unexpected inbound handler %T", handler)
	}
	inbound := holder.GetInbound()
	tun, ok := inbound.(*xrayTun.Handler)
	if !ok {
		return nil, fmt.Errorf("unexpected tun handler %T", inbound)
	}
	field := reflect.ValueOf(tun).Elem().FieldByName("dispatcher")
	if !field.IsValid() {
		return nil, fmt.Errorf("tun handler has no dispatcher field")
	}
	pointer := (*xrayRouting.Dispatcher)(unsafe.Pointer(field.UnsafeAddr()))
	if *pointer == nil {
		return nil, fmt.Errorf("tun handler not initialized")
	}
	tracker := newXrayTracker()
	*pointer = &trackingDispatcher{Dispatcher: *pointer, tracker: tracker}
	return tracker, nil
}
