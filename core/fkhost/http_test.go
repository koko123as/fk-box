package fkhost

import (
	"crypto/tls"
	"testing"
	"time"
)

func TestHTTPClientModernTLS(t *testing.T) {
	client, release := NewHTTPClientTimeout(3 * time.Second)
	defer release()
	if client.Timeout != 3*time.Second {
		t.Fatal("timeout")
	}
}

func TestHTTPClientTLSConfig(t *testing.T) {
	c := NewHTTPClient()
	defer c.Close()
	if c.tls.MinVersion != tls.VersionTLS12 || len(c.tls.CipherSuites) != len(tls.CipherSuites()) {
		t.Fatalf("tls: min %x, %d suites", c.tls.MinVersion, len(c.tls.CipherSuites))
	}
	if !c.Transport.DisableKeepAlives || c.Transport.TLSClientConfig != &c.tls {
		t.Fatal("transport")
	}
}
