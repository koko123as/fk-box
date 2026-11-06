package libbox

import (
	"encoding/json"
	"testing"

	tun "github.com/sagernet/sing-tun"
)

func TestDropUnbuiltTunStack(t *testing.T) {
	stackOf := func(content string) []any {
		var root struct {
			Inbounds []map[string]any `json:"inbounds"`
		}
		if err := json.Unmarshal([]byte(dropUnbuiltTunStack(content)), &root); err != nil {
			t.Fatal(err)
		}
		stacks := make([]any, len(root.Inbounds))
		for i, inbound := range root.Inbounds {
			stacks[i] = inbound["stack"]
		}
		return stacks
	}
	in := `{"inbounds":[{"type":"tun","tag":"tun-in","mtu":9000},{"type":"mixed","tag":"mixed-in","listen_port":2412}],"outbounds":[{"type":"direct"}]}`
	if got := stackOf(in); got[0] != "system" || got[1] != nil {
		t.Fatalf("unset stack: %v", got)
	}
	if !tun.WithGVisor {
		if got := stackOf(`{"inbounds":[{"type":"tun","stack":"mixed"},{"type":"tun","stack":"gvisor"}]}`); got[0] != "system" || got[1] != "system" {
			t.Fatalf("unbuilt stack: %v", got)
		}
	}
	for _, kept := range []string{`{"inbounds":[{"type":"tun","stack":"system"}]}`, `{"inbounds":[{"type":"tun","stack":"go"}]}`, "not json", `{"outbounds":[]}`} {
		if dropUnbuiltTunStack(kept) != kept {
			t.Fatalf("must not touch %s", kept)
		}
	}
}

func TestConvertCoreShareLinksEnabled(t *testing.T) {
	link := "vless://a219bbfa-3a4a-4e24-a5e4-a4bdd2eb647d@example.com:443?encryption=none&type=ws&path=%2Fws&host=example.com&security=tls&sni=example.com#node"
	for enabled, want := range map[string]string{"sing-box,xray,mihomo": CoreSingBox, "xray,mihomo": CoreMihomo, "xray": CoreXray} {
		profile, err := ConvertCoreShareLinksEnabled(CoreAuto, enabled, link)
		if err != nil {
			t.Fatal(enabled, err)
		}
		if profile.Core != want {
			t.Fatalf("%s: got %s want %s", enabled, profile.Core, want)
		}
	}
	xhttp := "vless://a219bbfa-3a4a-4e24-a5e4-a4bdd2eb647d@example.com:443?encryption=none&type=xhttp&path=%2Fx&security=tls&sni=example.com#x"
	if _, err := ConvertCoreShareLinksEnabled(CoreAuto, "sing-box,mihomo", xhttp); err == nil {
		t.Fatal("xhttp must need Xray")
	}
}

func TestCoreProfileStats(t *testing.T) {
	yaml := "proxies:\n  - {name: a, type: ss, server: 1.1.1.1, port: 1, cipher: aes-128-gcm, password: x}\n  - {name: b, type: ss, server: 1.1.1.1, port: 2, cipher: aes-128-gcm, password: x}\nproxy-groups:\n  - {name: g, type: select, proxies: [a, b]}\nrules:\n  - MATCH,g\n"
	stats, err := CoreProfileStats(CoreMihomo, yaml)
	if err != nil || stats != `{"groups":1,"outbounds":2,"rules":1}` {
		t.Fatal(stats, err)
	}
	xray := `{"outbounds":[{"protocol":"freedom","tag":"direct"},{"protocol":"blackhole","tag":"block"}],"routing":{"balancers":[{"tag":"b","selector":["d"]}],"rules":[{"outboundTag":"direct"},{"balancerTag":"b"}]}}`
	stats, err = CoreProfileStats(CoreXray, xray)
	if err != nil || stats != `{"groups":1,"outbounds":2,"rules":2}` {
		t.Fatal(stats, err)
	}
}
