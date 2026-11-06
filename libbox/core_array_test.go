package libbox

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"strings"
	"testing"

	mihomoLog "github.com/metacubex/mihomo/log"
	"github.com/sagernet/sing-box/daemon"
	xrayCore "github.com/xtls/xray-core/core"
	xraySerial "github.com/xtls/xray-core/infra/conf/serial"
)

// testMasqueKeys returns a freshly generated, throwaway P-256 key pair in the
// base64 DER forms MASQUE's "warp" settings take. No real credentials.
func testMasqueKeys(t *testing.T) (string, string) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	private, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		t.Fatal(err)
	}
	peer, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	public, err := x509.MarshalPKIXPublicKey(&peer.PublicKey)
	if err != nil {
		t.Fatal(err)
	}
	return base64.StdEncoding.EncodeToString(private), base64.StdEncoding.EncodeToString(public)
}

// testMasqueConfig is a synthetic v2rayNG-style Xray config with a MASQUE
// outbound (fake documentation addresses, generated keys).
func testMasqueConfig(t *testing.T, remarks string) map[string]any {
	privateKey, publicKey := testMasqueKeys(t)
	config := map[string]any{
		"_AsteriskNG_test": []string{"synthetic"},
		"log":              map[string]any{"loglevel": "warning"},
		"dns":              map[string]any{"servers": []any{"1.1.1.1"}},
		"inbounds": []any{map[string]any{
			"tag": "socks", "port": 10808, "listen": "127.0.0.1", "protocol": "socks",
			"settings": map[string]any{"auth": "noauth", "udp": true},
		}},
		"outbounds": []any{
			map[string]any{
				"tag":      "proxy",
				"protocol": "masque",
				"settings": map[string]any{"address": "203.0.113.20", "port": 443, "remoteDNS": []string{"1.1.1.1", "2606:4700:4700::1111"}},
				"streamSettings": map[string]any{
					"method":   "masque",
					"security": "tls",
					"tlsSettings": map[string]any{
						"serverName": "masque.example.test",
						"alpn":       []string{"h3"},
					},
					"masqueSettings": map[string]any{"warp": map[string]any{
						"privateKey": privateKey,
						"publicKey":  publicKey,
						"address":    []string{"172.16.0.2/32", "2001:db8::2/128"},
					}},
				},
			},
			map[string]any{"tag": "direct", "protocol": "freedom"},
			map[string]any{"tag": "block", "protocol": "blackhole"},
		},
		"routing": map[string]any{
			"domainStrategy": "AsIs",
			"rules":          []any{map[string]any{"type": "field", "outboundTag": "direct", "domain": []string{"domain:example.org"}}},
		},
		"policy": map[string]any{"system": map[string]any{"statsOutboundUplink": true, "statsOutboundDownlink": true}},
		"stats":  map[string]any{},
	}
	if remarks != "" {
		config["remarks"] = remarks
	}
	return config
}

func mustJSON(t *testing.T, value any) string {
	t.Helper()
	encoded, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	return string(encoded)
}

func TestCheckXrayMasqueOutbound(t *testing.T) {
	content := mustJSON(t, testMasqueConfig(t, "masque-a"))
	if core := DetectCoreConfig(content); core != CoreXray {
		t.Fatalf("masque config detected as %s", core)
	}
	if core, err := ResolveProfileCore(CoreAuto, content); err != nil || core != CoreXray {
		t.Fatalf("resolve: %s %v", core, err)
	}
	if err := CheckCoreConfig(CoreXray, content); err != nil {
		t.Fatal(err)
	}
	// The whole config (with the TUN-free inbounds) builds an Xray instance:
	// the masque proxy and transport are linked in.
	config, err := xraySerial.LoadJSONConfig(strings.NewReader(content))
	if err != nil {
		t.Fatal(err)
	}
	instance, err := xrayCore.New(config)
	if err != nil {
		t.Fatal(err)
	}
	_ = instance.Close()

	bad := testMasqueConfig(t, "")
	bad["outbounds"].([]any)[0].(map[string]any)["streamSettings"].(map[string]any)["masqueSettings"] =
		map[string]any{"warp": map[string]any{"privateKey": "not a key", "publicKey": "not a key", "address": []string{"172.16.0.2"}}}
	if err := CheckCoreConfig(CoreXray, mustJSON(t, bad)); err == nil {
		t.Fatal("masque outbound with invalid keys was accepted")
	}
}

func TestCoreConfigArraySplit(t *testing.T) {
	singBox := map[string]any{"remarks": "sing", "outbounds": []any{map[string]any{"type": "direct", "tag": "direct"}}}
	array := "\ufeff[" + strings.Join([]string{
		mustJSON(t, testMasqueConfig(t, " masque-a ")),
		mustJSON(t, testMasqueConfig(t, "")),
		mustJSON(t, singBox),
	}, ",\r\n") + "]"
	if core := DetectCoreConfig(array); core != CoreArray {
		t.Fatalf("array detected as %s", core)
	}
	if _, err := ResolveProfileCore(CoreAuto, array); err == nil {
		t.Fatal("array resolved without splitting")
	}
	if err := CheckCoreConfig(CoreArray, array); err == nil {
		t.Fatal("array checked as one profile")
	}
	entries, err := SplitCoreConfigArray(array)
	if err != nil {
		t.Fatal(err)
	}
	if entries.Len() != 3 {
		t.Fatalf("len = %d", entries.Len())
	}
	wantRemarks := []string{"masque-a", "", "sing"}
	wantCore := []string{CoreXray, CoreXray, CoreSingBox}
	for i := int32(0); i < entries.Len(); i++ {
		if entries.Remarks(i) != wantRemarks[i] || entries.Core(i) != wantCore[i] {
			t.Fatalf("entry %d: %q %s", i, entries.Remarks(i), entries.Core(i))
		}
		if err := CheckCoreConfig(entries.Core(i), entries.Content(i)); err != nil {
			t.Fatalf("entry %d: %v", i, err)
		}
		if DetectCoreConfig(entries.Content(i)) != wantCore[i] {
			t.Fatalf("entry %d content re-detects differently", i)
		}
	}
	// Xray keeps remarks (subscription updates match on it); sing-box would reject it.
	if CoreConfigRemarks(entries.Content(0)) != "masque-a" {
		t.Fatalf("xray entry lost its remarks")
	}
	if strings.Contains(entries.Content(2), "remarks") {
		t.Fatalf("sing-box entry kept remarks: %s", entries.Content(2))
	}
	if !strings.HasPrefix(entries.Content(0), "{\n  \"") {
		t.Fatalf("entry not indented: %.20q", entries.Content(0))
	}

	for _, content := range []string{`[]`, `["vless://x"]`, `[{"a":1}, 2]`, `{"outbounds":[]}`, `[{"a":1}`} {
		if DetectCoreConfig(content) == CoreArray {
			t.Errorf("%q detected as array", content)
		}
		if _, err := SplitCoreConfigArray(content); err == nil {
			t.Errorf("%q was split", content)
		}
	}
	if CoreConfigRemarks(`{"remarks": 5}`) != "" || CoreConfigRemarks(`[{"remarks":"x"}]`) != "" {
		t.Fatal("remarks read from the wrong shape")
	}
}

func TestCoreDefaultLogLevel(t *testing.T) {
	cases := map[string]string{
		`{}`:                                     "warn",
		`{"log":null}`:                           "warn",
		`{"log":{"disabled":false}}`:             "warn",
		`{"log":{"level":"debug"}}`:              "debug",
		`{"log":{"level":"info","timestamp":1}}`: "info",
	}
	for in, want := range cases {
		var root struct {
			Log map[string]any `json:"log"`
		}
		out := withDefaultSingBoxLogLevel(in)
		if err := json.Unmarshal([]byte(out), &root); err != nil {
			t.Fatalf("%s -> %s: %v", in, out, err)
		}
		if root.Log["level"] != want {
			t.Errorf("%s -> %s, want level %s", in, out, want)
		}
	}
	explicit := `{"log":{"level":"trace"},"outbounds":[{"type":"direct"}]}`
	if withDefaultSingBoxLogLevel(explicit) != explicit {
		t.Fatal("an explicit sing-box level was rewritten")
	}
	if withDefaultSingBoxLogLevel("not json") != "not json" || withDefaultSingBoxLogLevel(`[1]`) != `[1]` {
		t.Fatal("non-object content changed")
	}
	big := `{"outbounds":[{"type":"direct","tag":"a","routing_mark":18446744073709551615}]}`
	if !strings.Contains(withDefaultSingBoxLogLevel(big), "18446744073709551615") {
		t.Fatal("numbers were not kept byte for byte")
	}

	if level := xrayLogConfigWithDefault(map[string]any{})["loglevel"]; level != "warning" {
		t.Fatalf("xray default %v", level)
	}
	if level := xrayLogConfigWithDefault(map[string]any{"log": map[string]any{"loglevel": "debug"}})["loglevel"]; level != "debug" {
		t.Fatalf("xray explicit level replaced: %v", level)
	}
	if xrayDaemonLogLevel("warning") != daemon.LogLevel_WARN || xrayDaemonLogLevel("debug") != daemon.LogLevel_DEBUG {
		t.Fatal("xray level mapping")
	}
	if mihomoSetsLogLevel("mode: rule\nproxies: []\n") || !mihomoSetsLogLevel("log-level: info\nproxies: []\n") {
		t.Fatal("mihomo log-level presence")
	}
	if mihomoDaemonLogLevel(mihomoLog.WARNING) != daemon.LogLevel_WARN || mihomoDaemonLogLevel(mihomoLog.INFO) != daemon.LogLevel_INFO {
		t.Fatal("mihomo level mapping")
	}
}
