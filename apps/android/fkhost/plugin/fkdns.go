package plugin

import (
	"context"
	"encoding/binary"
	"io"
	"net"
	"sync/atomic"
	"time"

	"github.com/sagernet/sing-box/adapter"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/dns"
	"github.com/sagernet/sing-box/log"
	E "github.com/sagernet/sing/common/exceptions"

	mDNS "github.com/miekg/dns"
)

// FKDNSOptions: the stage 2 contract has none (the brain's DNS settings
// travel with the profile options, docs/architecture/brain-dns.md).
type FKDNSOptions struct{}

// FKDNS is the only DNS server of the generated configuration. Every query
// sing-box makes (an app's query hijacked by fkbrain, a domain destination
// or an outbound's server name to resolve) goes to the brain's
// dns_message, which answers from hosts, its cache or fake-ip, or asks an
// upstream through the host's exchange (an outbound's protected dialer).
// fkdns itself opens no socket and never falls back to the system
// resolver: without a brain a query fails.
type FKDNS struct {
	dns.TransportAdapter
	queries atomic.Int64
	clients atomic.Int64
}

var _ adapter.DNSTransport = (*FKDNS)(nil)

func NewFKDNS(ctx context.Context, logger log.ContextLogger, tag string, options FKDNSOptions) (adapter.DNSTransport, error) {
	return &FKDNS{TransportAdapter: dns.NewTransportAdapter(TypeFKDNS, tag, nil)}, nil
}

func (t *FKDNS) Start(stage adapter.StartStage, scope *adapter.Scope) error { return nil }

func (t *FKDNS) Reset() {}

// Queries is the number of messages this transport passed to the brain;
// ClientQueries those that came from apps.
func (t *FKDNS) Queries() int64       { return t.queries.Load() }
func (t *FKDNS) ClientQueries() int64 { return t.clients.Load() }

func (t *FKDNS) Exchange(ctx context.Context, message *mDNS.Msg) (*mDNS.Msg, error) {
	brain := dnsBrain()
	if brain == nil {
		return nil, E.New("fkdns: no brain attached")
	}
	t.queries.Add(1)
	client := isClientQuery(ctx)
	if client {
		t.clients.Add(1)
	}
	request, err := message.Pack()
	if err != nil {
		return nil, err
	}
	if _, hasDeadline := ctx.Deadline(); !hasDeadline {
		var cancel context.CancelFunc
		ctx, cancel = context.WithTimeout(ctx, C.DNSTimeout)
		defer cancel()
	}
	reply, err := brain.Exchange(ctx, request, client)
	if err != nil {
		return nil, E.Cause(err, "fkdns")
	}
	var response mDNS.Msg
	if err = response.Unpack(reply); err != nil {
		return nil, E.Cause(err, "fkdns: brain reply")
	}
	return &response, nil
}

func (t *FKDNS) ExchangeAsync(ctx context.Context, message *mDNS.Msg, callback func(response *mDNS.Msg, err error)) {
	go func() { callback(t.Exchange(ctx, message)) }()
}

// ExchangeMessage sends one DNS message on conn and reads the reply:
// length-prefixed on a stream, one datagram otherwise (replies with
// another ID are skipped).
func ExchangeMessage(ctx context.Context, conn net.Conn, stream bool, request []byte) ([]byte, error) {
	if len(request) < 2 {
		return nil, E.New("short DNS message")
	}
	if deadline, ok := ctx.Deadline(); ok {
		_ = conn.SetDeadline(deadline)
	} else {
		_ = conn.SetDeadline(time.Now().Add(C.DNSTimeout))
	}
	if stream {
		framed := make([]byte, 2+len(request))
		binary.BigEndian.PutUint16(framed, uint16(len(request)))
		copy(framed[2:], request)
		if _, err := conn.Write(framed); err != nil {
			return nil, err
		}
		var length [2]byte
		if _, err := io.ReadFull(conn, length[:]); err != nil {
			return nil, err
		}
		reply := make([]byte, binary.BigEndian.Uint16(length[:]))
		if _, err := io.ReadFull(conn, reply); err != nil {
			return nil, err
		}
		return reply, nil
	}
	if _, err := conn.Write(request); err != nil {
		return nil, err
	}
	buffer := make([]byte, 65535)
	for {
		n, err := conn.Read(buffer)
		if err != nil {
			return nil, err
		}
		if n >= 2 && buffer[0] == request[0] && buffer[1] == request[1] {
			return append([]byte(nil), buffer[:n]...), nil
		}
	}
}
