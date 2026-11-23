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

// ChainOutboundOptions: a plugin outbound that dials through another outbound
// picked by tag at dial time via adapter.OutboundManager (item 2).
type ChainOutboundOptions struct {
	Via string `json:"via"`
}

type ChainOutbound struct {
	outbound.Adapter
	manager adapter.OutboundManager
	via     string
	dials   atomic.Int64
}

func NewChainOutbound(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, options ChainOutboundOptions) (adapter.Outbound, error) {
	if options.Via == "" {
		return nil, E.New("fkchain: missing via")
	}
	manager := service.FromContext[adapter.OutboundManager](ctx)
	if manager == nil {
		return nil, E.New("fkchain: no outbound manager in context")
	}
	return &ChainOutbound{
		// Declaring the dependency makes the manager start "via" first.
		Adapter: outbound.NewAdapter(TypeChain, tag, []string{N.NetworkTCP, N.NetworkUDP}, []string{options.Via}),
		manager: manager,
		via:     options.Via,
	}, nil
}

func (c *ChainOutbound) Dials() int64 { return c.dials.Load() }

func (c *ChainOutbound) target() (adapter.Outbound, error) {
	target, loaded := c.manager.Outbound(c.via)
	if !loaded {
		return nil, E.New("fkchain: outbound not found: ", c.via)
	}
	return target, nil
}

func (c *ChainOutbound) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	target, err := c.target()
	if err != nil {
		return nil, err
	}
	c.dials.Add(1)
	return target.DialContext(ctx, network, destination)
}

func (c *ChainOutbound) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	target, err := c.target()
	if err != nil {
		return nil, err
	}
	c.dials.Add(1)
	return target.ListenPacket(ctx, destination)
}
