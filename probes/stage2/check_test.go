package stage2

import (
	stdjson "encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	box "github.com/sagernet/sing-box"
)

// checkContract checks the shape docs/architecture/brain-config.md fixes:
// one tun inbound, route.final fkbrain without route rules, one fkdns
// server without DNS rules, fkbrain first, no selector/urltest.
func checkContract(t *testing.T, content []byte) {
	t.Helper()
	var config map[string]any
	if err := stdjson.Unmarshal(content, &config); err != nil {
		t.Fatal("not JSON: ", err)
	}
	route, _ := config["route"].(map[string]any)
	if route == nil || route["final"] != TypeBrain {
		t.Fatalf("route.final is not fkbrain: %v", route)
	}
	if _, exists := route["rules"]; exists {
		t.Fatal("route.rules present")
	}
	if _, exists := route["rule_set"]; exists {
		t.Fatal("route.rule_set present")
	}
	dnsOptions, _ := config["dns"].(map[string]any)
	servers, _ := dnsOptions["servers"].([]any)
	if len(servers) != 1 {
		t.Fatalf("dns.servers: want one, got %d", len(servers))
	}
	if server, _ := servers[0].(map[string]any); server["type"] != TypeFKDNS {
		t.Fatalf("dns server is not fkdns: %v", server)
	}
	if _, exists := dnsOptions["rules"]; exists {
		t.Fatal("dns.rules present")
	}
	inbounds, _ := config["inbounds"].([]any)
	if len(inbounds) != 1 {
		t.Fatalf("inbounds: want one tun, got %d", len(inbounds))
	}
	if inbound, _ := inbounds[0].(map[string]any); inbound["type"] != "tun" {
		t.Fatalf("inbound is not tun: %v", inbound)
	}
	outbounds, _ := config["outbounds"].([]any)
	if len(outbounds) < 3 {
		t.Fatalf("outbounds: want fkbrain, nodes, direct, block; got %d", len(outbounds))
	}
	if first, _ := outbounds[0].(map[string]any); first["type"] != TypeBrain {
		t.Fatalf("first outbound is not fkbrain: %v", first)
	}
	for _, item := range outbounds {
		outbound, _ := item.(map[string]any)
		switch outbound["type"] {
		case "selector", "urltest":
			t.Fatalf("group outbound generated: %v", outbound["tag"])
		}
	}
}

func TestGeneratedConfigs(t *testing.T) {
	dir := os.Getenv("FKBOX_BRAIN_CONFIG_DIR")
	if dir == "" {
		t.Fatal("FKBOX_BRAIN_CONFIG_DIR is not set (the Rust tests write the configurations there)")
	}
	files, err := filepath.Glob(filepath.Join(dir, "*.json"))
	if err != nil {
		t.Fatal(err)
	}
	checked := 0
	for _, file := range files {
		base := filepath.Base(file)
		if strings.HasPrefix(base, "snapshot-") {
			continue
		}
		checked++
		t.Run(strings.TrimSuffix(base, ".json"), func(t *testing.T) {
			content, err := os.ReadFile(file)
			if err != nil {
				t.Fatal(err)
			}
			checkContract(t, content)
			ctx, _ := NewContext()
			options, err := ParseOptions(ctx, content)
			if err != nil {
				t.Fatal("sing-box parse: ", err)
			}
			instance, err := box.New(box.Options{Context: ctx, Options: options})
			if err != nil {
				t.Fatal("box.New: ", err)
			}
			if err = instance.Close(); err != nil {
				t.Log("close: ", err)
			}
			types := make(map[string]int)
			for _, outbound := range options.Outbounds {
				types[outbound.Type]++
			}
			for _, endpoint := range options.Endpoints {
				types["endpoint/"+endpoint.Type]++
			}
			t.Logf("CONFIG %s accepted: %v", base, types)
		})
	}
	if checked < 20 {
		t.Fatalf("only %d generated configurations in %s", checked, dir)
	}
}
