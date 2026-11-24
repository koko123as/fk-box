package plugin

import (
	"context"
	"encoding/binary"
	"io"
	"net"
	"net/url"
	"sync"
	"sync/atomic"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/dialer"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/dns"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"

	mDNS "github.com/miekg/dns"
)

// DefaultFKDNSUpstream is the placeholder upstream of stage 3; stage 4
// replaces the transport's body with the brain's resolve().
const DefaultFKDNSUpstream = "udp://1.1.1.1:53"

// FKDNSOptions: the stage 2 contract has none; `upstream` is the stage 3
// placeholder's upstream (udp:// or tcp:// with an IP address), for tests
// and until the brain resolves (stage 4).
type FKDNSOptions struct {
	Upstream string `json:"upstream,omitempty"`
}

// FKDNS is the only DNS server of the generated configuration. Stage 3
// placeholder: every query goes to one upstream, through a sing-box dialer
// (the platform's protect via auto_detect_interface), never the system
// resolver. Its address must be an IP, so the dialer needs no resolver.
type FKDNS struct {
	dns.TransportAdapter
	ctx      context.Context
	network  string
	server   M.Socksaddr
	dialOnce sync.Once
	dialer   N.Dialer
	dialErr  error
	queries  atomic.Int64
}

var _ adapter.DNSTransport = (*FKDNS)(nil)

// ParseUpstream reads udp://ip:port or tcp://ip:port (port 53 by default).
func ParseUpstream(upstream string) (string, M.Socksaddr, error) {
	parsed, err := url.Parse(upstream)
	if err != nil {
		return "", M.Socksaddr{}, E.Cause(err, "upstream ", upstream)
	}
	network := parsed.Scheme
	if network != N.NetworkUDP && network != N.NetworkTCP {
		return "", M.Socksaddr{}, E.New("upstream ", upstream, ": only udp:// and tcp:// (stage 4 adds the rest)")
	}
	server := M.ParseSocksaddrHostPortStr(parsed.Hostname(), parsed.Port())
	if server.Port == 0 {
		server.Port = 53
	}
	if !server.IsIP() {
		return "", M.Socksaddr{}, E.New("upstream ", upstream, ": the address must be an IP")
	}
	return network, server, nil
}

func NewFKDNS(ctx context.Context, logger log.ContextLogger, tag string, options FKDNSOptions) (adapter.DNSTransport, error) {
	upstream := options.Upstream
	if upstream == "" {
		upstream = DefaultFKDNSUpstream
	}
	network, server, err := ParseUpstream(upstream)
	if err != nil {
		return nil, E.Cause(err, "fkdns")
	}
	return &FKDNS{
		TransportAdapter: dns.NewTransportAdapter(TypeFKDNS, tag, nil),
		ctx:              ctx,
		network:          network,
		server:           server,
	}, nil
}

func (t *FKDNS) Start(stage adapter.StartStage, scope *adapter.Scope) error { return nil }

func (t *FKDNS) Reset() {}

// Queries is the number of exchanges this transport made.
func (t *FKDNS) Queries() int64 { return t.queries.Load() }

func (t *FKDNS) getDialer() (N.Dialer, error) {
	t.dialOnce.Do(func() {
		t.dialer, t.dialErr = dialer.NewDefault(t.ctx, option.DialerOptions{})
	})
	return t.dialer, t.dialErr
}

func (t *FKDNS) Exchange(ctx context.Context, message *mDNS.Msg) (*mDNS.Msg, error) {
	t.queries.Add(1)
	upstream, err := t.getDialer()
	if err != nil {
		return nil, E.Cause(err, "fkdns: create dialer")
	}
	request, err := message.Pack()
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithTimeout(ctx, C.DNSTimeout)
	defer cancel()
	conn, err := upstream.DialContext(ctx, t.network, t.server)
	if err != nil {
		return nil, E.Cause(err, "fkdns: dial ", t.server)
	}
	defer conn.Close()
	reply, err := ExchangeMessage(ctx, conn, t.network == N.NetworkTCP, request)
	if err != nil {
		return nil, E.Cause(err, "fkdns: exchange with ", t.server)
	}
	var response mDNS.Msg
	if err = response.Unpack(reply); err != nil {
		return nil, err
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
