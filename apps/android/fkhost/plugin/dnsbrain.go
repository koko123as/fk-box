package plugin

import (
	"bytes"
	"context"
	"crypto/tls"
	"crypto/x509"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"sync"
	"sync/atomic"
	"time"

	up "github.com/koko123as/fk-box/apps/android/fkhost/upstream"
)

// DNSBrain is the brain's DNS (crates/brain-ffi, stage 4): it answers whole
// DNS messages (hosts, cache, fake-ip, the choice of upstream and outbound)
// and maps fake-ip addresses back to names. brainffi wires it to the Rust
// library; tests use Go fakes.
type DNSBrain interface {
	// Exchange answers one DNS message; client is set for an app's query
	// (fkbrain's port-53 hijack), which may get a fake-ip answer.
	Exchange(ctx context.Context, message []byte, client bool) ([]byte, error)
	// Reverse: ("", false) for an address outside the fake-ip ranges,
	// (name, true) for an allocated one, ("", true) for a stale one.
	Reverse(addr netip.Addr) (string, bool)
}

type dnsBrainHolder struct{ DNSBrain }

var currentDNSBrain atomic.Pointer[dnsBrainHolder]

// SetDNSBrain installs the process-wide DNS brain (nil removes it).
func SetDNSBrain(brain DNSBrain) {
	if brain == nil {
		currentDNSBrain.Store(nil)
		return
	}
	currentDNSBrain.Store(&dnsBrainHolder{brain})
}

func dnsBrain() DNSBrain {
	if holder := currentDNSBrain.Load(); holder != nil {
		return holder.DNSBrain
	}
	return nil
}

var dnsRootCAs atomic.Pointer[x509.CertPool]

// SetDNSRootCAs replaces the system roots for tls:// and https:// upstreams
// (nil: the system roots). For tests against local fixtures.
func SetDNSRootCAs(pool *x509.CertPool) { dnsRootCAs.Store(pool) }

func upstreamTLS(serverName string) *tls.Config {
	return &tls.Config{ServerName: serverName, MinVersion: tls.VersionTLS12, RootCAs: dnsRootCAs.Load()}
}

type clientQueryKey struct{}

// withClientQuery marks the context of an app's DNS query (fkbrain's
// hijack); sing-box's DNS router passes the context through to fkdns.
func withClientQuery(ctx context.Context) context.Context {
	return context.WithValue(ctx, clientQueryKey{}, true)
}

func isClientQuery(ctx context.Context) bool {
	client, _ := ctx.Value(clientQueryKey{}).(bool)
	return client
}

// Upstream is one parsed DNS upstream address of the brain's settings:
// udp:// and tcp:// (port 53), tls:// (853) and https:// (443, path
// /dns-query by default), always with an IP address; tls/https may name
// the certificate's server name with ?sni=.
type Upstream struct {
	Scheme     string
	Server     up.Socksaddr
	ServerName string
	Path       string
}

// ParseUpstream reads an upstream address.
func ParseUpstream(upstream string) (Upstream, error) {
	parsed, err := url.Parse(upstream)
	if err != nil {
		return Upstream{}, up.Cause(err, "upstream ", upstream)
	}
	result := Upstream{Scheme: parsed.Scheme}
	var port uint16
	switch parsed.Scheme {
	case "udp", "tcp":
		port = 53
	case "tls":
		port = 853
	case "https":
		port = 443
		result.Path = parsed.EscapedPath()
		if result.Path == "" {
			result.Path = "/dns-query"
		}
	default:
		return Upstream{}, up.NewError("upstream ", upstream, ": the scheme must be udp, tcp, tls or https")
	}
	result.Server = up.ParseSocksaddrHostPortStr(parsed.Hostname(), parsed.Port())
	if !result.Server.IsIP() {
		return Upstream{}, up.NewError("upstream ", upstream, ": the address must be an IP")
	}
	if result.Server.Port == 0 {
		result.Server.Port = port
	}
	result.ServerName = parsed.Query().Get("sni")
	if result.ServerName == "" {
		result.ServerName = result.Server.Addr.String()
	}
	return result, nil
}

// ExchangeVia sends one DNS message to upstream through outbound (whose
// dialer is the protected one) and returns the reply. DoH clients are kept
// per outbound and upstream in clients (nil: one per call).
func ExchangeVia(ctx context.Context, outbound up.Outbound, upstream string, message []byte, clients *DoHClients) ([]byte, error) {
	parsed, err := ParseUpstream(upstream)
	if err != nil {
		return nil, err
	}
	if _, hasDeadline := ctx.Deadline(); !hasDeadline {
		var cancel context.CancelFunc
		ctx, cancel = context.WithTimeout(ctx, up.DNSTimeout)
		defer cancel()
	}
	switch parsed.Scheme {
	case "udp":
		packetConn, err := outbound.ListenPacket(ctx, parsed.Server)
		if err != nil {
			return nil, err
		}
		conn := up.NewBindPacketConn(packetConn, parsed.Server.UDPAddr())
		defer conn.Close()
		return ExchangeMessage(ctx, conn, false, message)
	case "tcp":
		conn, err := outbound.DialContext(ctx, up.NetworkTCP, parsed.Server)
		if err != nil {
			return nil, err
		}
		defer conn.Close()
		return ExchangeMessage(ctx, conn, true, message)
	case "tls":
		conn, err := outbound.DialContext(ctx, up.NetworkTCP, parsed.Server)
		if err != nil {
			return nil, err
		}
		tlsConn := tls.Client(conn, upstreamTLS(parsed.ServerName))
		defer tlsConn.Close()
		if err = tlsConn.HandshakeContext(ctx); err != nil {
			return nil, up.Cause(err, "tls handshake with ", parsed.Server)
		}
		return ExchangeMessage(ctx, tlsConn, true, message)
	default:
		var client *http.Client
		if clients != nil {
			client = clients.get(outbound, parsed)
		} else {
			client = newDoHClient(outbound, parsed)
			defer client.CloseIdleConnections()
		}
		return exchangeHTTPS(ctx, client, parsed, message)
	}
}

func newDoHClient(outbound up.Outbound, upstream Upstream) *http.Client {
	return &http.Client{Transport: &http.Transport{
		DialContext: func(ctx context.Context, network, address string) (net.Conn, error) {
			return outbound.DialContext(ctx, up.NetworkTCP, upstream.Server)
		},
		TLSClientConfig:     upstreamTLS(upstream.ServerName),
		ForceAttemptHTTP2:   true,
		MaxIdleConnsPerHost: 2,
		IdleConnTimeout:     90 * time.Second,
	}}
}

func exchangeHTTPS(ctx context.Context, client *http.Client, upstream Upstream, message []byte) ([]byte, error) {
	endpoint := "https://" + upstream.Server.String() + upstream.Path
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(message))
	if err != nil {
		return nil, err
	}
	request.Host = upstream.ServerName
	request.Header.Set("Content-Type", "application/dns-message")
	request.Header.Set("Accept", "application/dns-message")
	response, err := client.Do(request)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return nil, up.NewError("DoH ", endpoint, ": HTTP ", response.StatusCode)
	}
	return io.ReadAll(io.LimitReader(response.Body, 65535))
}

// DoHClients keeps one HTTP client (and its connections) per outbound and
// DoH upstream of the running configuration; Reset drops them all (the
// host calls it when the configuration changes).
type DoHClients struct {
	access  sync.Mutex
	clients map[dohKey]*http.Client
}

type dohKey struct {
	outbound up.Outbound
	upstream Upstream
}

func (c *DoHClients) get(outbound up.Outbound, upstream Upstream) *http.Client {
	c.access.Lock()
	defer c.access.Unlock()
	if c.clients == nil {
		c.clients = make(map[dohKey]*http.Client)
	}
	key := dohKey{outbound, upstream}
	client, loaded := c.clients[key]
	if !loaded {
		client = newDoHClient(outbound, upstream)
		c.clients[key] = client
	}
	return client
}

func (c *DoHClients) Reset() {
	c.access.Lock()
	clients := c.clients
	c.clients = nil
	c.access.Unlock()
	for _, client := range clients {
		client.CloseIdleConnections()
	}
}
