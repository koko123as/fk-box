// Package host is fk-box's Go kernel host (option A of the single-kernel
// design, docs/architecture/brain-host.md): its own box.Context on the
// upstream include registries plus the four plugins, sing-box's public
// daemon.StartedService for the lifecycle, and its own Android
// adapter.PlatformInterface. No libbox CommandServer, no patch, no local
// listener: the brain drives it through the fkb_host_api table (package
// brainffi) and every connection reaches the plugins in-process.
package host

import (
	"context"
	"io"
	"net"
	"net/http"
	"runtime"
	"sync"
	"time"

	"github.com/koko123as/fk-box/apps/android/fkhost/plugin"
	"github.com/koko123as/fk-box/apps/android/fkhost/upstream"
)

// NewContext builds the box context: the upstream include registries plus
// fkbrain/fkdns/mihomo/xray (public outbound.Register /
// dns.RegisterTransport / box.Context), with platform registered.
func NewContext(platform upstream.PlatformInterface) context.Context {
	ctx := upstream.ContextWithDefaultRegistry(context.Background())
	outboundRegistry := upstream.OutboundRegistry()
	plugin.RegisterOutbounds(outboundRegistry)
	dnsRegistry := upstream.DNSTransportRegistry()
	plugin.RegisterDNSTransports(dnsRegistry)
	ctx = upstream.Context(ctx, upstream.InboundRegistry(), outboundRegistry, upstream.EndpointRegistry(), dnsRegistry, upstream.ServiceRegistry(), upstream.CertificateProviderRegistry())
	if platform != nil {
		upstream.MustRegister[upstream.PlatformInterface](ctx, platform)
	}
	return ctx
}

// Options of a host.
type Options struct {
	Bridge      Bridge
	LogMaxLines int
	Debug       bool
}

// Host runs one sing-box configuration at a time.
type Host struct {
	ctx       context.Context
	platform  *Platform
	service   *upstream.StartedService
	lifecycle sync.Mutex

	statsAccess sync.Mutex
	lastSample  time.Time
	lastUp      int64
	lastDown    int64

	dohClients plugin.DoHClients
}

type platformHandler struct{}

func (platformHandler) WriteDebugMessage(message string) {}

func (platformHandler) ConnectSSHAgent() (int32, error) { return -1, upstream.NewError("no SSH agent") }

func New(options Options) (*Host, error) {
	if options.Bridge == nil {
		return nil, upstream.NewError("host: no platform bridge")
	}
	if options.LogMaxLines == 0 {
		options.LogMaxLines = 3000
	}
	platform := NewPlatform(options.Bridge)
	ctx := NewContext(platform)
	return &Host{
		ctx:      ctx,
		platform: platform,
		service: upstream.NewStartedService(upstream.ServiceOptions{
			Context:     ctx,
			Handler:     platformHandler{},
			Debug:       options.Debug,
			LogMaxLines: options.LogMaxLines,
		}),
	}, nil
}

func (h *Host) Context() context.Context        { return h.ctx }
func (h *Host) Platform() *Platform             { return h.platform }
func (h *Host) Service() *upstream.StartedService { return h.service }

// Start runs config as generation (fkbrain registers its outbound table
// under it). The configuration is checked first (parsed and created, then
// closed), so a bad one never stops the running one.
func (h *Host) Start(config string, generation uint32) error {
	h.lifecycle.Lock()
	defer h.lifecycle.Unlock()
	plugin.SetPendingGeneration(generation)
	if err := h.service.CheckConfig(h.ctx, config); err != nil {
		return upstream.Cause(err, "check configuration")
	}
	plugin.SetPendingGeneration(generation)
	h.dohClients.Reset()
	return h.service.StartOrReloadService(h.ctx, config, nil)
}

// Reload replaces the running configuration (StartOrReloadService closes
// the old instance first; the check above keeps a bad configuration from
// getting that far). A failure after the check leaves no configuration
// running and is reported.
func (h *Host) Reload(config string, generation uint32) error {
	return h.Start(config, generation)
}

// Stop closes the running configuration.
func (h *Host) Stop() error {
	h.lifecycle.Lock()
	defer h.lifecycle.Unlock()
	h.dohClients.Reset()
	return h.service.CloseService()
}

// Box is the running sing-box instance, nil when stopped.
func (h *Host) Box() *upstream.Box {
	instance := h.service.Instance()
	if instance == nil {
		return nil
	}
	return instance.Box()
}

func (h *Host) outbound(tag string) (upstream.Outbound, error) {
	instance := h.Box()
	if instance == nil {
		return nil, upstream.NewError("not running")
	}
	outbound, loaded := instance.Outbound().Outbound(tag)
	if !loaded {
		return nil, errNotFound{tag}
	}
	return outbound, nil
}

type errNotFound struct{ tag string }

func (e errNotFound) Error() string { return "outbound not found: " + e.tag }

// IsNotFound reports an unknown outbound tag (FKB_E_NOT_FOUND).
func IsNotFound(err error) bool {
	_, ok := err.(errNotFound)
	return ok
}

// Probe makes one HTTP(S) GET to url through the outbound and returns the
// status and the time to the response headers (a delay test). Names in
// url are resolved by the outbound (remote proxies) or fkdns (direct).
func (h *Host) Probe(ctx context.Context, tag string, url string) (int, time.Duration, error) {
	outbound, err := h.outbound(tag)
	if err != nil {
		return -1, 0, err
	}
	transport := &http.Transport{
		DialContext: func(ctx context.Context, network, address string) (net.Conn, error) {
			return outbound.DialContext(ctx, network, upstream.ParseSocksaddr(address))
		},
		DisableKeepAlives: true,
		ForceAttemptHTTP2: true,
	}
	defer transport.CloseIdleConnections()
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return -1, 0, err
	}
	start := time.Now()
	response, err := transport.RoundTrip(request)
	if err != nil {
		return -1, 0, err
	}
	latency := time.Since(start)
	_, _ = io.Copy(io.Discard, io.LimitReader(response.Body, 64*1024))
	response.Body.Close()
	return response.StatusCode, latency, nil
}

// Exchange sends one DNS message to upstream (udp://, tcp://, tls:// or
// https:// with an IP address) through the outbound, whose dialer is the
// protected one, and returns the reply: the brain's only way to the
// network for DNS. DoH connections are kept per outbound until the
// configuration changes.
func (h *Host) Exchange(ctx context.Context, tag string, upstream string, message []byte) ([]byte, error) {
	outbound, err := h.outbound(tag)
	if err != nil {
		return nil, err
	}
	return plugin.ExchangeVia(ctx, outbound, upstream, message, &h.dohClients)
}

// Stats mirrors fkb_stats.
type Stats struct {
	UplinkBytes    uint64
	DownlinkBytes  uint64
	UplinkRate     uint64
	DownlinkRate   uint64
	ConnectionsTCP uint32
	ConnectionsUDP uint32
	MemoryInuse    uint64
	Goroutines     uint32
}

// Stats reads the counters; rates are since the previous call.
func (h *Host) Stats() Stats {
	counters := plugin.Stats()
	var memory runtime.MemStats
	runtime.ReadMemStats(&memory)
	stats := Stats{
		UplinkBytes:    uint64(counters.Uplink),
		DownlinkBytes:  uint64(counters.Downlink),
		ConnectionsTCP: uint32(max(counters.ConnectionsTCP, 0)),
		ConnectionsUDP: uint32(max(counters.ConnectionsUDP, 0)),
		MemoryInuse:    memory.HeapInuse + memory.StackInuse,
		Goroutines:     uint32(runtime.NumGoroutine()),
	}
	h.statsAccess.Lock()
	now := time.Now()
	if !h.lastSample.IsZero() {
		if elapsed := now.Sub(h.lastSample).Seconds(); elapsed > 0 {
			stats.UplinkRate = uint64(float64(max(counters.Uplink-h.lastUp, 0)) / elapsed)
			stats.DownlinkRate = uint64(float64(max(counters.Downlink-h.lastDown, 0)) / elapsed)
		}
	}
	h.lastSample, h.lastUp, h.lastDown = now, counters.Uplink, counters.Downlink
	h.statsAccess.Unlock()
	return stats
}
