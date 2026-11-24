package host

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strconv"
	"testing"
	"time"

	"github.com/koko123as/fk-box/apps/android/fkhost/internal/fixture"
	"github.com/koko123as/fk-box/apps/android/fkhost/plugin"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing/service"

	mDNS "github.com/miekg/dns"
)

func fixtureAnswer(request *mDNS.Msg) *mDNS.Msg {
	response := new(mDNS.Msg)
	response.SetReply(request)
	for _, question := range request.Question {
		if question.Qtype == mDNS.TypeA {
			response.Answer = append(response.Answer, &mDNS.A{
				Hdr: mDNS.RR_Header{Name: question.Name, Rrtype: mDNS.TypeA, Class: mDNS.ClassINET, Ttl: 60},
				A:   net.IPv4(127, 0, 0, 1).To4(),
			})
		}
	}
	return response
}

// The host's exchange (the brain's only way out for DNS) over every
// upstream kind, through a sing-box outbound: plain UDP (the DNS fixture),
// DNS over TLS and DNS over HTTPS (local TLS fixtures whose certificate the
// test trusts), each socket from the protected dialer.
func TestExchangeUpstreams(t *testing.T) {
	dns := fixture.StartDNS(t)
	doh := httptest.NewTLSServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		body, _ := io.ReadAll(request.Body)
		var query mDNS.Msg
		if request.Method != http.MethodPost || request.Header.Get("Content-Type") != "application/dns-message" || query.Unpack(body) != nil {
			http.Error(writer, "bad DoH request", http.StatusBadRequest)
			return
		}
		packed, _ := fixtureAnswer(&query).Pack()
		writer.Header().Set("Content-Type", "application/dns-message")
		_, _ = writer.Write(packed)
	}))
	defer doh.Close()
	listener, err := tls.Listen("tcp", "127.0.0.1:0", &tls.Config{Certificates: doh.TLS.Certificates})
	if err != nil {
		t.Fatal(err)
	}
	dot := &mDNS.Server{Listener: listener, Net: "tcp-tls", Handler: mDNS.HandlerFunc(func(writer mDNS.ResponseWriter, request *mDNS.Msg) {
		_ = writer.WriteMsg(fixtureAnswer(request))
	})}
	go func() { _ = dot.ActivateAndServe() }()
	defer dot.Shutdown()
	roots := x509.NewCertPool()
	roots.AddCert(doh.Certificate())
	plugin.SetDNSRootCAs(roots)
	defer plugin.SetDNSRootCAs(nil)

	instance, bridge, ctx := newTestBox(t, clientConfig(dns.Port, `{"type": "direct", "tag": "plain"}`))
	direct := outboundOf(t, instance, "fk-direct")
	clients := &plugin.DoHClients{}
	defer clients.Reset()
	exchange := func(upstream string) {
		t.Helper()
		query := new(mDNS.Msg)
		query.SetQuestion("upstream.fixture.test.", mDNS.TypeA)
		packed, _ := query.Pack()
		requestCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
		defer cancel()
		before := bridge.Count()
		reply, err := plugin.ExchangeVia(requestCtx, direct, upstream, packed, clients)
		if err != nil {
			t.Fatalf("%s: %v", upstream, err)
		}
		var response mDNS.Msg
		if err = response.Unpack(reply); err != nil || len(response.Answer) != 1 || response.Id != query.Id {
			t.Fatalf("%s: reply %v %v", upstream, err, response.String())
		}
		if bridge.Count() <= before && upstream[:5] != "https" {
			t.Fatalf("%s: no protected socket", upstream)
		}
	}
	dohAddress := doh.Listener.Addr().String()
	exchange("udp://127.0.0.1:" + strconv.Itoa(dns.Port))
	exchange("tls://" + listener.Addr().String())
	exchange("tls://" + listener.Addr().String() + "?sni=127.0.0.1")
	exchange("https://" + dohAddress + "/dns-query")
	exchange("https://" + dohAddress + "/dns-query")
	for _, bad := range []string{"udp://fixture.test:53", "quic://127.0.0.1", "ftp://1.1.1.1"} {
		if _, err := plugin.ExchangeVia(ctx, direct, bad, []byte{0, 1}, nil); err == nil {
			t.Fatalf("%s accepted", bad)
		}
	}
	// fkdns without a brain fails instead of asking anyone else.
	plugin.SetDNSBrain(nil)
	if _, err := service.FromContext[adapter.DNSRouter](ctx).Lookup(ctx, "nobrain.fixture.test", adapter.DNSQueryOptions{}); err == nil {
		t.Fatal("fkdns answered without a brain")
	}
	t.Logf("STAGE4 host exchange: udp, tls (ip / sni), https (pooled) through fk-direct ok, protected %d", bridge.Count())
}
