package fkhost

import (
	"context"
	"crypto/tls"
	"net/http"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/certificate"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/logger"
)

// HTTPClient is an HTTP client for fk-box's own requests (geo data,
// subscriptions, WebDAV, update checks, probes): TLS 1.2 or newer with every
// secure cipher suite, no keep-alive, and on Android the system and user
// certificate store (sing-box's certificate store, the one libbox's HTTP
// client used). Close releases it.
type HTTPClient struct {
	Client    http.Client
	Transport http.Transport
	tls       tls.Config
	scope     *adapter.Scope
}

// NewHTTPClient returns a client without a timeout; set Client.Timeout.
func NewHTTPClient() *HTTPClient {
	c := new(HTTPClient)
	c.Client.Transport = &c.Transport
	c.Transport.ForceAttemptHTTP2 = true
	c.Transport.TLSHandshakeTimeout = C.TCPTimeout
	c.Transport.TLSClientConfig = &c.tls
	c.Transport.DisableKeepAlives = true
	c.tls.MinVersion = tls.VersionTLS12
	for _, suite := range tls.CipherSuites() {
		c.tls.CipherSuites = append(c.tls.CipherSuites, suite.ID)
	}
	if C.IsAndroid {
		store, err := certificate.NewStore(context.Background(), logger.NOP(), option.CertificateOptions{})
		if err == nil {
			c.scope = adapter.NewScope(context.Background(), log.NewNOPFactory().Logger())
			err = store.Start(adapter.StartStateInitialize, c.scope)
		}
		if err != nil {
			// libbox's client panicked here; fall back to Go's own roots.
			Log.Diagnostic("certificate store: " + err.Error())
		} else {
			c.tls.RootCAs = store.Pool()
		}
	}
	return c
}

// Close releases the certificate store and idle connections.
func (c *HTTPClient) Close() {
	if c.scope != nil {
		_ = c.scope.Close()
	}
	c.Transport.CloseIdleConnections()
}

// NewHTTPClientTimeout returns a client with the given timeout and the
// function that releases it.
func NewHTTPClientTimeout(timeout time.Duration) (*http.Client, func()) {
	c := NewHTTPClient()
	c.Client.Timeout = timeout
	return &c.Client, c.Close
}
