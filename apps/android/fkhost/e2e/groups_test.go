package e2e

import (
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/koko123as/fk-box/apps/android/fkhost/brainffi"
	"github.com/koko123as/fk-box/apps/android/fkhost/host"
	"github.com/koko123as/fk-box/apps/android/fkhost/internal/fixture"
	"github.com/koko123as/fk-box/apps/android/fkhost/plugin"
)

// slowHTTP is a loopback HTTP fixture answering 204 after a delay the test
// changes; one per node, behind that node's Shadowsocks server.
type slowHTTP struct {
	port  int
	delay atomic.Int64
	hits  atomic.Int64
}

func startSlowHTTP(t *testing.T) *slowHTTP {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	server := &slowHTTP{port: listener.Addr().(*net.TCPAddr).Port}
	httpServer := &http.Server{
		Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			server.hits.Add(1)
			time.Sleep(time.Duration(server.delay.Load()))
			w.WriteHeader(http.StatusNoContent)
		}),
		ReadHeaderTimeout: 10 * time.Second,
	}
	go func() { _ = httpServer.Serve(listener) }()
	t.Cleanup(func() { _ = httpServer.Close() })
	return server
}

type groupView struct {
	Groups []struct {
		Name          string  `json:"name"`
		Type          string  `json:"type"`
		Selectable    bool    `json:"selectable"`
		Now           *string `json:"now"`
		TestedSecsAgo *uint64 `json:"tested_secs_ago"`
		Members       []struct {
			Name    string  `json:"name"`
			Alive   *bool   `json:"alive"`
			DelayMs *uint32 `json:"delay_ms"`
		} `json:"members"`
	} `json:"groups"`
}

func readGroup(t *testing.T, name string) (now string, tested bool, raw string) {
	t.Helper()
	text, err := brainffi.Groups()
	if err != nil {
		t.Fatal(err)
	}
	var view groupView
	if err = json.Unmarshal([]byte(text), &view); err != nil {
		t.Fatalf("groups json: %v: %s", err, text)
	}
	for _, group := range view.Groups {
		if group.Name == name {
			if group.Selectable {
				t.Fatalf("%s is automatic but marked selectable: %s", name, text)
			}
			if group.Now != nil {
				now = *group.Now
			}
			return now, group.TestedSecsAgo != nil, text
		}
	}
	t.Fatalf("no group %s: %s", name, text)
	return "", false, text
}

func httpThroughTun(t *testing.T, url string) {
	t.Helper()
	client := &http.Client{Timeout: 15 * time.Second, Transport: &http.Transport{DisableKeepAlives: true, Proxy: nil}}
	response, err := client.Get(url)
	if err != nil {
		t.Fatalf("GET %s: %v", url, err)
	}
	_, _ = io.Copy(io.Discard, response.Body)
	response.Body.Close()
	if response.StatusCode != http.StatusNoContent {
		t.Fatalf("GET %s: status %d", url, response.StatusCode)
	}
}

// TestGroupSwitchEndToEnd: a url-test group over two real nodes (native
// Shadowsocks to two loopback HTTP fixtures). The brain measures them
// through the host's probe (the outbound's protected dialer), picks the
// faster one, and after the delays swap a new test moves the group to the
// other node: traffic entering the TUN follows, and the sing-box instance
// is the same one throughout (no restart, no reload).
func TestGroupSwitchEndToEnd(t *testing.T) {
	tunName := os.Getenv("FKHOST_E2E_TUN")
	if tunName == "" {
		t.Skip("FKHOST_E2E_TUN is not set (the CI job creates the TUN device)")
	}
	httpA, httpB := startSlowHTTP(t), startSlowHTTP(t)
	httpA.delay.Store(int64(600 * time.Millisecond))
	ssA := fixture.StartShadowsocks(t, "127.0.0.1", httpA.port)
	ssB := fixture.StartShadowsocks(t, "127.0.0.1", httpB.port)

	bridge := &tunBridge{name: tunName}
	defer bridge.Close()
	h, err := host.New(host.Options{Bridge: bridge})
	if err != nil {
		t.Fatal(err)
	}
	if err = brainffi.Attach(h); err != nil {
		t.Fatal("attach: ", err)
	}
	subscription := fmt.Sprintf(`proxies:
  - {name: node-a, type: ss, server: 127.0.0.1, port: %d, cipher: aes-128-gcm, password: %q}
  - {name: node-b, type: ss, server: 127.0.0.1, port: %d, cipher: aes-128-gcm, password: %q}
proxy-groups:
  - {name: Auto, type: url-test, proxies: [node-a, node-b], url: "http://198.51.100.7/generate_204", interval: 3600, tolerance: 50, timeout: 5000, lazy: false}
rules:
  - IP-CIDR,198.18.99.0/24,Auto
  - MATCH,REJECT
`, ssA, fixture.Password, ssB, fixture.Password)
	if err = brainffi.StartProfile(subscription, options); err != nil {
		t.Fatal("start profile: ", err)
	}
	defer func() {
		if err := brainffi.Stop(); err != nil {
			t.Log("stop: ", err)
		}
	}()
	instance := h.Box()
	if instance == nil {
		t.Fatal("host not running after start")
	}

	// The scheduler tests a new automatic group at once (within a tick).
	deadline := time.Now().Add(20 * time.Second)
	for {
		now, tested, raw := readGroup(t, "Auto")
		if tested {
			if now != "node-b" {
				t.Fatalf("first test picked %q, want node-b: %s", now, raw)
			}
			t.Logf("scheduled test: %s", raw)
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("the scheduler did not test the group: %s", raw)
		}
		time.Sleep(200 * time.Millisecond)
	}
	if httpA.hits.Load() == 0 || httpB.hits.Load() == 0 {
		t.Fatalf("probes did not reach both nodes: a %d b %d", httpA.hits.Load(), httpB.hits.Load())
	}
	if err = brainffi.SelectGroup("Auto", "node-a"); err == nil || !strings.Contains(err.Error(), "chooses its member itself") {
		t.Fatalf("selecting in an automatic group: %v", err)
	}
	protectedBefore := h.Platform().Protected()

	before := plugin.Stats()
	httpThroughTun(t, "http://198.18.99.20/through-b")
	middle := plugin.Stats()
	if middle.Routed["node-b"] <= before.Routed["node-b"] || middle.Routed["node-a"] != before.Routed["node-a"] {
		t.Fatalf("traffic did not follow the group to node-b: before %v after %v", before.Routed, middle.Routed)
	}

	// The delays swap; a test now moves the group to node-a.
	httpA.delay.Store(0)
	httpB.delay.Store(int64(600 * time.Millisecond))
	started := time.Now()
	if err = brainffi.TestGroup("Auto"); err != nil {
		t.Fatal("test group: ", err)
	}
	now, _, raw := readGroup(t, "Auto")
	if now != "node-a" {
		t.Fatalf("after the swap the group is on %q: %s", now, raw)
	}
	t.Logf("switched in a test round of %v: %s", time.Since(started), raw)
	httpThroughTun(t, "http://198.18.99.21/through-a")
	after := plugin.Stats()
	if after.Routed["node-a"] <= middle.Routed["node-a"] || after.Routed["node-b"] != middle.Routed["node-b"] {
		t.Fatalf("traffic did not follow the group to node-a: before %v after %v", middle.Routed, after.Routed)
	}
	if h.Box() != instance {
		t.Fatal("the sing-box instance changed: the switch restarted the service")
	}
	if h.Platform().Protected() <= protectedBefore {
		t.Fatal("no protected socket for the probes and the traffic")
	}
	t.Logf("E2E url-test switch without restart: node-b %d -> node-a %d connections, protected sockets %d",
		middle.Routed["node-b"]-before.Routed["node-b"], after.Routed["node-a"]-middle.Routed["node-a"],
		h.Platform().Protected()-protectedBefore)
}
