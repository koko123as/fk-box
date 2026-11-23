package plugin

import (
	"context"
	"net/netip"
	"sync/atomic"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/dns"
	"github.com/sagernet/sing-box/log"
	E "github.com/sagernet/sing/common/exceptions"

	mDNS "github.com/miekg/dns"
)

// FKDNSOptions configures the probe DNS transport: every A query is answered
// with Address. In the target design this is where Rust's DNS answer would be
// handed back to sing-box.
type FKDNSOptions struct {
	Address string `json:"address"`
}

type FKDNSTransport struct {
	dns.TransportAdapter
	address netip.Addr
	queries atomic.Int64
}

var _ adapter.DNSTransport = (*FKDNSTransport)(nil)

func NewFKDNSTransport(ctx context.Context, logger log.ContextLogger, tag string, options FKDNSOptions) (adapter.DNSTransport, error) {
	address, err := netip.ParseAddr(options.Address)
	if err != nil || !address.Is4() {
		return nil, E.New("fkdns: address must be an IPv4 address")
	}
	return &FKDNSTransport{
		TransportAdapter: dns.NewTransportAdapter(TypeFKDNS, tag, nil),
		address:          address,
	}, nil
}

func (t *FKDNSTransport) Start(stage adapter.StartStage, scope *adapter.Scope) error { return nil }

func (t *FKDNSTransport) Reset() {}

func (t *FKDNSTransport) Queries() int64 { return t.queries.Load() }

func (t *FKDNSTransport) Exchange(ctx context.Context, message *mDNS.Msg) (*mDNS.Msg, error) {
	t.queries.Add(1)
	response := new(mDNS.Msg)
	response.SetReply(message)
	for _, question := range message.Question {
		if question.Qtype != mDNS.TypeA {
			continue
		}
		response.Answer = append(response.Answer, &mDNS.A{
			Hdr: mDNS.RR_Header{Name: question.Name, Rrtype: mDNS.TypeA, Class: mDNS.ClassINET, Ttl: 60},
			A:   t.address.AsSlice(),
		})
	}
	return response, nil
}

func (t *FKDNSTransport) ExchangeAsync(ctx context.Context, message *mDNS.Msg, callback func(response *mDNS.Msg, err error)) {
	callback(t.Exchange(ctx, message))
}
