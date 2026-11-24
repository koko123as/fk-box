package fixture

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	stdjson "encoding/json"
	"encoding/pem"
	"math/big"
	"net"
	"os"
	"path/filepath"
	"testing"
	"time"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/include"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/json"
	"github.com/sagernet/sing/service"
)

// ServerName is the name in the fixture TLS certificate (clients skip
// verification; the name is only sent as SNI).
const ServerName = "fixture.test"

// TLSFiles writes a self-signed ECDSA certificate for ServerName and
// 127.0.0.1 and returns the certificate and key paths (stage 7: TLS and
// QUIC protocol servers).
func TLSFiles(t *testing.T) (string, string) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	template := &x509.Certificate{
		SerialNumber:          big.NewInt(time.Now().UnixNano()),
		Subject:               pkix.Name{CommonName: ServerName},
		DNSNames:              []string{ServerName},
		IPAddresses:           []net.IP{net.IPv4(127, 0, 0, 1)},
		NotBefore:             time.Now().Add(-time.Hour),
		NotAfter:              time.Now().Add(24 * time.Hour),
		KeyUsage:              x509.KeyUsageDigitalSignature | x509.KeyUsageCertSign,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		BasicConstraintsValid: true,
		IsCA:                  true,
	}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	keyDER, err := x509.MarshalECPrivateKey(key)
	if err != nil {
		t.Fatal(err)
	}
	directory := t.TempDir()
	certPath := filepath.Join(directory, "cert.pem")
	keyPath := filepath.Join(directory, "key.pem")
	if err = os.WriteFile(certPath, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}), 0o600); err != nil {
		t.Fatal(err)
	}
	if err = os.WriteFile(keyPath, pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: keyDER}), 0o600); err != nil {
		t.Fatal(err)
	}
	return certPath, keyPath
}

// MasqueKeys returns a MASQUE client key pair as mihomo reads it (base64
// SEC 1 private key, base64 PKIX public key); construct-only tests.
func MasqueKeys(t *testing.T) (string, string) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	private, err := x509.MarshalECPrivateKey(key)
	if err != nil {
		t.Fatal(err)
	}
	public, err := x509.MarshalPKIXPublicKey(&key.PublicKey)
	if err != nil {
		t.Fatal(err)
	}
	return base64.StdEncoding.EncodeToString(private), base64.StdEncoding.EncodeToString(public)
}

// StartSingBoxServer runs one sing-box inbound (inbound: its JSON object
// without tag/listen/listen_port) on 127.0.0.1 whose route sends every TCP
// and UDP connection to the echo port. network picks the free port's kind
// ("udp" for QUIC protocols).
func StartSingBoxServer(t *testing.T, inbound string, network string, echoPort int) int {
	t.Helper()
	var object map[string]any
	if err := stdjson.Unmarshal([]byte(inbound), &object); err != nil {
		t.Fatal("server inbound: ", err)
	}
	port := FreePort(t, network)
	object["tag"] = "in"
	object["listen"] = "127.0.0.1"
	object["listen_port"] = port
	config, err := stdjson.Marshal(map[string]any{
		"log":       map[string]any{"level": "warn"},
		"inbounds":  []any{object},
		"outbounds": []any{map[string]any{"type": "direct", "tag": "direct"}},
		"route": map[string]any{
			"rules": []any{map[string]any{"network": []string{"tcp", "udp"}, "action": "route-options", "override_address": "127.0.0.1", "override_port": echoPort}},
			"final": "direct",
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	ctx := include.Context(service.ContextWithDefaultRegistry(context.Background()))
	options, err := json.UnmarshalExtendedContext[option.Options](ctx, config)
	if err != nil {
		t.Fatal("server config: ", err)
	}
	instance, err := box.New(box.Options{Context: ctx, Options: options})
	if err != nil {
		t.Fatal("server: ", err)
	}
	if err = instance.Start(); err != nil {
		t.Fatal("server start: ", err)
	}
	t.Cleanup(func() { instance.Close() })
	return port
}
