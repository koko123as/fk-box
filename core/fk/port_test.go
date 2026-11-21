package fk

// Tests for what the fk-core port replaced (no legacy counterpart).

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestSingBoxHostContentDropsUnknownOptions(t *testing.T) {
	content := `{"log":{"level":"info"},"experimental":{"cache_file":{"enabled":true},"http_rewrite":{"rules":[1]},"http_debug":{}}}`
	cleaned, err := singBoxHostContent(content)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(cleaned, "http_rewrite") || strings.Contains(cleaned, "http_debug") || !strings.Contains(cleaned, "cache_file") {
		t.Fatalf("cleaned %s", cleaned)
	}
	if err = checkSingBoxConfig(content); err != nil {
		t.Fatalf("libbox saw an option it does not know: %v", err)
	}
	plain := `{"outbounds":[{"type":"direct"}]}`
	if cleaned, _ = singBoxHostContent(plain); cleaned != plain {
		t.Fatalf("plain profile changed: %s", cleaned)
	}
}

func TestFormatSingBoxConfigKeepsHTTPRewrite(t *testing.T) {
	for _, content := range []string{
		`{"experimental":{"cache_file":{"enabled":true},"http_rewrite":{"rules":[{"url":"a"}]}},"log":{}}`,
		`{"experimental":{"http_rewrite":{"rules":[]}},"log":{}}`,
	} {
		formatted, err := formatSingBoxConfig(content)
		if err != nil {
			t.Fatal(err)
		}
		var root struct {
			Experimental map[string]json.RawMessage `json:"experimental"`
		}
		if err = json.Unmarshal([]byte(formatted.Value), &root); err != nil {
			t.Fatalf("%v\n%s", err, formatted.Value)
		}
		if root.Experimental["http_rewrite"] == nil || !strings.HasPrefix(formatted.Value, "{\n  \"") {
			t.Fatalf("http_rewrite lost or not indented:\n%s", formatted.Value)
		}
	}
}

func TestMihomoErrorText(t *testing.T) {
	output := "\x1b[36mINFO\x1b[0m time=\"2026-11-21T05:00:00Z\" level=info msg=\"Start initial configuration in progress\"\n" +
		"time=\"2026-11-21T05:00:00Z\" level=error msg=\"proxy group[0]: 'PROXY' not found \\\"x\\\"\"\n" +
		"configuration file /tmp/mihomo-check-1.yaml test failed\n"
	if got := mihomoErrorText(output); got != `proxy group[0]: 'PROXY' not found "x"` {
		t.Fatalf("got %q", got)
	}
	if got := mihomoErrorText("panic: bad\nconfiguration file x.yaml test failed\n"); got != "panic: bad" {
		t.Fatalf("got %q", got)
	}
}

func TestKernelVersions(t *testing.T) {
	if got := parseXrayVersion("Xray 26.11.9 (Xray, Penetrates Everything.) 676eaf6 (go1.27.3 android/arm64)\nA unified platform for anti-censorship.\n", nil); got != "26.11.9" {
		t.Fatalf("xray %q", got)
	}
	if got := parseMihomoVersion("Mihomo Meta v1.19.34 linux arm64 with go1.27.3 fk-box\nUse tags: with_gvisor\n", nil); got != "1.19.34" {
		t.Fatalf("mihomo %q", got)
	}
	if parseMihomoVersion("Mihomo Meta unknown version linux arm64", nil) != "unknown" || parseXrayVersion("", nil) != "unknown" {
		t.Fatal("unknown versions")
	}
}

func TestGeoProtoDecode(t *testing.T) {
	entry, err := decodeGeoIP(testGeoIPEntry("CN", testCIDR{ip: []byte{1, 0, 1, 0}, prefix: 24}))
	if err != nil || entry.countryCode != "CN" || len(entry.cidrIPs) != 1 || len(entry.cidrIPs[0]) != 4 {
		t.Fatalf("%+v %v", entry, err)
	}
	if _, err = decodeGeoIP([]byte{0x0a, 0x05, 'C'}); err == nil {
		t.Fatal("truncated entry decoded")
	}
	if _, err = decodeGeoIP([]byte{0x0a, 0x01, 0xff}); err == nil {
		t.Fatal("invalid UTF-8 country code decoded")
	}
}

func TestUILANKeepsUnrelatedBytes(t *testing.T) {
	profile := `{"inbounds":[{"type":"mixed","tag":"m","listen":"127.0.0.1","listen_port":2080,"x_custom":18446744073709551615}],"route":{"final":"direct"}}`
	updated, err := SetUILANProxyEnabled(profile, true, 0)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(updated.Value, "18446744073709551615") || !strings.Contains(updated.Value, `"listen": "0.0.0.0"`) {
		t.Fatalf("updated:\n%s", updated.Value)
	}
	if strings.Index(updated.Value, `"inbounds"`) > strings.Index(updated.Value, `"route"`) {
		t.Fatalf("member order changed:\n%s", updated.Value)
	}
}
