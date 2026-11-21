package fk

import (
	"strings"
	"testing"
)

const testAllCores = "sing-box,xray,mihomo"

func TestUIPrepareCoreProfileRejectsOversizedContent(t *testing.T) {
	_, err := PrepareCoreProfile(CoreAuto, testAllCores, strings.Repeat("a", coreProfileLimit+1))
	if err == nil || err.Error() != "Configuration exceeds 16 MiB" {
		t.Fatalf("oversized content: %v", err)
	}
	// Bytes, not characters: 2-byte runes just past the limit fail too.
	_, err = PrepareCoreProfile(CoreAuto, testAllCores, strings.Repeat("é", coreProfileLimit/2+1))
	if err == nil || err.Error() != "Configuration exceeds 16 MiB" {
		t.Fatalf("oversized UTF-8 content: %v", err)
	}
}

func TestUIPrepareCoreProfileDropsRetiredCaptureOptions(t *testing.T) {
	original := `{"outbounds":[{"type":"direct","tag":"kept"}],"experimental":{"http_debug":{"scripts":[]}}}`
	prepared, err := PrepareCoreProfile(CoreAuto, testAllCores, original)
	if err != nil {
		t.Fatal(err)
	}
	want, _ := RemoveUIRetiredCaptureOptions(original)
	if prepared.Core != CoreSingBox || prepared.Content != want || strings.Contains(prepared.Content, "http_debug") {
		t.Fatalf("prepared = %s %q", prepared.Core, prepared.Content)
	}
	// Content without the retired option is kept byte for byte.
	plain := "{\n  \"outbounds\": [{\"type\": \"direct\", \"tag\": \"kept\"}]\n}\n"
	prepared, err = PrepareCoreProfile(CoreSingBox, testAllCores, plain)
	if err != nil || prepared.Content != plain {
		t.Fatalf("plain sing-box profile changed: %v %q", err, prepared)
	}
	// Other cores never go through the sing-box migration.
	requireKernels(t)
	xray := `{"outbounds":[{"protocol":"freedom","tag":"direct"}]}`
	prepared, err = PrepareCoreProfile(CoreAuto, testAllCores, xray)
	if err != nil || prepared.Core != CoreXray || prepared.Content != xray {
		t.Fatalf("xray profile: %v %v", err, prepared)
	}
}

func TestUIPrepareCoreProfileConvertsLinksForEnabledCores(t *testing.T) {
	prepared, err := PrepareCoreProfile(CoreAuto, testAllCores, testShadowsocks)
	if err != nil {
		t.Fatal(err)
	}
	if prepared.Core != CoreSingBox || DetectCoreConfig(prepared.Content) != CoreSingBox {
		t.Fatalf("links -> %s", prepared.Core)
	}
	// sing-box off: auto picks mihomo next.
	requireKernels(t)
	prepared, err = PrepareCoreProfile(CoreAuto, "xray, mihomo", testShadowsocks)
	if err != nil || prepared.Core != CoreMihomo {
		t.Fatalf("links with sing-box off: %v %v", err, prepared)
	}
	// A concrete core converts for that core, then must be turned on.
	_, err = PrepareCoreProfile(CoreXray, "sing-box", testShadowsocks)
	if err == nil || err.Error() != "The Xray core is turned off. Turn it on in Settings › General › Core, or pick another core for this profile." {
		t.Fatalf("xray off: %v", err)
	}
	_, err = PrepareCoreProfile(CoreAuto, "", testShadowsocks)
	if err == nil || err.Error() != "every core is turned off" {
		t.Fatalf("every core off: %v", err)
	}
}

func TestUIPrepareCoreProfileRequiresTheCoreToBeOn(t *testing.T) {
	cases := map[string]string{
		`{"outbounds":[{"type":"direct","tag":"direct"}]}`: "sing-box",
		`{"outbounds":[{"protocol":"freedom"}]}`:           "Xray",
		"mixed-port: 7890\nrules:\n  - MATCH,DIRECT\n":     "mihomo",
	}
	for content, label := range cases {
		_, err := PrepareCoreProfile(CoreAuto, "", content)
		want := "The " + label + " core is turned off. Turn it on in Settings › General › Core, or pick another core for this profile."
		if err == nil || err.Error() != want {
			t.Errorf("%s off: %v", label, err)
		}
	}
}

func TestUIPrepareCoreProfileChecksWithTheCoresParser(t *testing.T) {
	requireKernels(t)
	_, err := PrepareCoreProfile(CoreXray, testAllCores, `{"outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"a","port":"x"}]}}]}`)
	if err == nil {
		t.Fatal("bad Xray outbound was accepted")
	}
	if _, err = PrepareCoreProfile("other", testAllCores, `{}`); err == nil || err.Error() != "unknown core: other" {
		t.Fatalf("unknown core: %v", err)
	}
	// The core-off check comes before the parser.
	_, err = PrepareCoreProfile(CoreXray, "sing-box", `{"outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"a","port":"x"}]}}]}`)
	if err == nil || !strings.HasPrefix(err.Error(), "The Xray core is turned off.") {
		t.Fatalf("order: %v", err)
	}
}

func TestUICoreEnabledInFollowsSettings(t *testing.T) {
	if !coreEnabledIn("sing-box", "unknown") || coreEnabledIn("xray", "unknown") {
		t.Fatal("an unknown core follows the sing-box switch")
	}
	if coreLabel("unknown") != "sing-box" {
		t.Fatal("an unknown core is labelled sing-box")
	}
}
