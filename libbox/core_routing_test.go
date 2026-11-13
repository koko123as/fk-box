package libbox

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	mihomoRules "github.com/metacubex/mihomo/rules"
	mihomoRC "github.com/metacubex/mihomo/rules/common"
	"go.yaml.in/yaml/v3"
)

type fakeRoutingLists map[string]string

func (f fakeRoutingLists) load(url string) (*routingList, error) {
	text, ok := f[url]
	if !ok {
		return nil, fmt.Errorf("not found")
	}
	list := &routingList{url: url, hash: routingListHash(url), fetched: time.Now()}
	parseRoutingListText(text, &list.match)
	return list, nil
}

const testRoutingListURL = "https://lists.example.test/ads.txt"

var testRoutingLists = fakeRoutingLists{testRoutingListURL: strings.Join([]string{
	"! AdBlock header",
	"[Adblock Plus 2.0]",
	"||ads.example.com^",
	"||tracker.example.net^$third-party",
	"@@||allowed.example.com^",
	"example.com##.banner",
	"0.0.0.0 hosts.example.org",
	"127.0.0.1 localhost",
	"DOMAIN-SUFFIX,clash.example.com",
	"DOMAIN-KEYWORD,adkeyword",
	"IP-CIDR,203.0.113.0/24,no-resolve",
	"  - '+.payload.example.com'",
	"198.51.100.7",
	"plain.example.io",
}, "\n")}

func routingSetJSON(t *testing.T, set routingSet) string {
	t.Helper()
	encoded, err := json.Marshal(set)
	if err != nil {
		t.Fatal(err)
	}
	return string(encoded)
}

func testRoutingRules() []routingRule {
	return []routingRule{
		{Enabled: true, Name: "ads", Content: routingContentPreset, Preset: "ads-block", Target: routingTarget{Type: routingTargetBlock}},
		{Enabled: true, Name: "cn", Content: routingContentPreset, Preset: "cn-direct", Target: routingTarget{Type: routingTargetDirect}},
		{Enabled: true, Name: "sites", Content: routingContentMatch, Values: []string{"example.com", "full:a.example.org", "keyword:foo", "1.2.3.0/24", "geoip:jp", "bad value!"},
			Target: routingTarget{Type: routingTargetProxy}, Network: "tcp", Ports: "443,8000-9000"},
		{Enabled: true, Name: "list", Content: routingContentList, URL: testRoutingListURL, Target: routingTarget{Type: routingTargetBlock}},
		{Enabled: false, Name: "disabled", Content: routingContentAll, Target: routingTarget{Type: routingTargetBlock}},
	}
}

func TestCoreRoutingParseList(t *testing.T) {
	var match routingMatch
	parseRoutingListText(testRoutingLists[testRoutingListURL], &match)
	want := map[string][]string{
		"suffix":  {"ads.example.com", "clash.example.com", "payload.example.com", "plain.example.io"},
		"full":    {"hosts.example.org"},
		"keyword": {"adkeyword"},
		"cidr":    {"203.0.113.0/24", "198.51.100.7/32"},
	}
	got := map[string][]string{"suffix": match.suffix, "full": match.full, "keyword": match.keyword, "cidr": match.cidr}
	for key, values := range want {
		if strings.Join(got[key], ",") != strings.Join(values, ",") {
			t.Errorf("%s = %v, want %v", key, got[key], values)
		}
	}
}

func TestCoreRoutingValues(t *testing.T) {
	var match routingMatch
	for _, value := range []string{"+.a.com", "*.b.com", ".c.com", "domain:d.com", "full:e.com", "regexp:^f\\.", "geosite:CN", "geoip:private", "10.0.0.1", "fd00::/8"} {
		if err := match.addValue(value); err != nil {
			t.Fatalf("%s: %v", value, err)
		}
	}
	if strings.Join(match.suffix, ",") != "a.com,b.com,c.com,d.com" || match.full[0] != "e.com" || match.geosite[0] != "cn" ||
		match.cidr[0] != "10.0.0.1/32" || match.cidr[1] != "fd00::/8" || match.geoip[0] != "private" || match.regex[0] != "^f\\." {
		t.Fatalf("unexpected match %+v", match)
	}
	for _, value := range []string{"geosite:bad name", "regexp:(", "a b.com"} {
		if err := match.addValue(value); err == nil {
			t.Errorf("%s accepted", value)
		}
	}
	if ports, err := parseRoutingPorts("443, 8000-9000"); err != nil || len(ports) != 2 || ports[1].to != 9000 {
		t.Fatalf("ports %v %v", ports, err)
	}
	for _, bad := range []string{"0", "70000", "9-1", "x"} {
		if _, err := parseRoutingPorts(bad); err == nil {
			t.Errorf("port %s accepted", bad)
		}
	}
}

func TestCoreRoutingOffAndEmpty(t *testing.T) {
	content := `{"outbounds":[{"type":"direct","tag":"direct"}]}`
	for _, rules := range []string{"", `{"mode":"off","rules":[{"enabled":true,"content":"all","target":{"type":"block"}}]}`, `{"mode":"prepend","final":{"type":"config"},"rules":[]}`} {
		result, err := applyCoreRouting(CoreSingBox, content, rules, testRoutingLists)
		if err != nil || result.Content != content || result.Active || result.Applied != 0 {
			t.Fatalf("%q changed the profile: %+v %v", rules, result, err)
		}
	}
	if _, err := applyCoreRouting(CoreSingBox, content, `{"mode":"sideways"}`, testRoutingLists); err == nil {
		t.Fatal("unknown mode accepted")
	}
}

func TestCoreRoutingSingBox(t *testing.T) {
	profile, err := ConvertCoreShareLinksFor(CoreAuto, testVlessReality+"\n"+testShadowsocks)
	if err != nil || profile.Core != CoreSingBox {
		t.Fatalf("convert: %v %v", profile, err)
	}
	rules := testRoutingRules()
	rules = append(rules,
		routingRule{Enabled: true, Name: "app", Content: routingContentAll, Apps: []string{"com.example.app"}, Target: routingTarget{Type: routingTargetNode, Tag: "ss-node"}},
		routingRule{Enabled: true, Name: "missing", Content: routingContentAll, Target: routingTarget{Type: routingTargetNode, Tag: "nope"}},
	)
	set := routingSet{Mode: routingModePrepend, Final: routingTarget{Type: routingTargetDirect}, Rules: rules}
	result, err := applyCoreRouting(CoreSingBox, profile.Content, routingSetJSON(t, set), testRoutingLists)
	if err != nil {
		t.Fatal(err)
	}
	if result.Applied != 5 || !strings.Contains(result.Warnings, "nope") || !strings.Contains(result.Warnings, "bad value") {
		t.Fatalf("applied %d, warnings %q", result.Applied, result.Warnings)
	}
	if err := CheckConfig(result.Content); err != nil {
		t.Fatalf("sing-box rejected the compiled profile: %v\n%s", err, result.Content)
	}
	var root struct {
		Route struct {
			Rules   []map[string]any `json:"rules"`
			RuleSet []map[string]any `json:"rule_set"`
			Final   string           `json:"final"`
		} `json:"route"`
		Experimental map[string]any `json:"experimental"`
	}
	if err := json.Unmarshal([]byte(result.Content), &root); err != nil {
		t.Fatal(err)
	}
	r := root.Route.Rules
	// sniff and hijack-dns stay first, then the user rules, then the profile's own.
	if r[0]["action"] != "sniff" || r[1]["action"] != "hijack-dns" || r[2]["action"] != "reject" || r[3]["outbound"] != "direct" ||
		r[4]["outbound"] != "proxy" || r[5]["action"] != "reject" || r[6]["outbound"] != "ss-node" || r[7]["ip_is_private"] != true {
		t.Fatalf("rule order: %v", r)
	}
	if r[3]["ip_is_private"] != true || fmt.Sprint(r[4]["port"]) != "[443]" || fmt.Sprint(r[4]["port_range"]) != "[8000:9000]" ||
		fmt.Sprint(r[4]["network"]) != "[tcp]" || fmt.Sprint(r[6]["package_name"]) != "[com.example.app]" {
		t.Fatalf("rule fields: %v", r)
	}
	if fmt.Sprint(r[5]["domain_keyword"]) != "[adkeyword]" || !strings.Contains(fmt.Sprint(r[5]["domain_suffix"]), "ads.example.com") {
		t.Fatalf("list rule: %v", r[5])
	}
	if root.Route.Final != "direct" {
		t.Fatalf("final = %s", root.Route.Final)
	}
	tags := map[string]map[string]any{}
	for _, ruleSet := range root.Route.RuleSet {
		tags[ruleSet["tag"].(string)] = ruleSet
	}
	for _, tag := range []string{"fk-geosite-category-ads-all", "fk-geosite-cn", "fk-geoip-cn", "fk-geosite-private", "fk-geoip-jp"} {
		if tags[tag] == nil {
			t.Fatalf("missing rule-set %s in %v", tag, root.Route.RuleSet)
		}
	}
	if fmt.Sprint(tags["fk-geosite-cn"]["http_client"]) != "map[detour:proxy]" || tags["fk-geosite-cn"]["url"] != routingSingRuleSetBase+"geosite/cn.srs" {
		t.Fatalf("rule-set: %v", tags["fk-geosite-cn"])
	}
	if fmt.Sprint(root.Experimental["cache_file"]) != "map[enabled:true]" {
		t.Fatalf("cache_file: %v", root.Experimental)
	}

	// Override drops the profile's own rules but keeps sniff/hijack-dns.
	set.Mode = routingModeOverride
	set.Final = routingTarget{Type: routingTargetBlock}
	result, err = applyCoreRouting(CoreSingBox, profile.Content, routingSetJSON(t, set), testRoutingLists)
	if err != nil {
		t.Fatal(err)
	}
	if err := CheckConfig(result.Content); err != nil {
		t.Fatalf("override: %v", err)
	}
	if strings.Count(result.Content, `"ip_is_private": true`) != 1 || !strings.Contains(result.Content, `"hijack-dns"`) {
		t.Fatalf("override kept the profile rules:\n%s", result.Content)
	}
}

func TestCoreRoutingXray(t *testing.T) {
	profile, err := ConvertCoreShareLinksFor(CoreAuto, testVlessXHTTP+"\n"+testShadowsocks)
	if err != nil || profile.Core != CoreXray {
		t.Fatalf("convert: %v %v", profile, err)
	}
	rules := append(testRoutingRules(),
		routingRule{Enabled: true, Name: "app", Content: routingContentAll, Apps: []string{"com.example.app"}, Target: routingTarget{Type: routingTargetDirect}},
		routingRule{Enabled: true, Name: "node", Content: routingContentMatch, Values: []string{"node.example.com"}, Target: routingTarget{Type: routingTargetNode, Tag: "ss-node"}},
	)
	set := routingSet{Mode: routingModePrepend, Final: routingTarget{Type: routingTargetDirect}, Rules: rules}
	result, err := applyCoreRouting(CoreXray, profile.Content, routingSetJSON(t, set), testRoutingLists)
	if err != nil {
		t.Fatal(err)
	}
	if err := CheckCoreConfig(CoreXray, result.Content); err != nil {
		t.Fatalf("Xray rejected the compiled profile: %v\n%s", err, result.Content)
	}
	if result.Applied != 5 || !strings.Contains(result.Warnings, "cannot match Android apps") || strings.Contains(result.Warnings, "IPIfNonMatch") {
		t.Fatalf("applied %d, warnings %q", result.Applied, result.Warnings)
	}
	before, err := decodeOrderedJSON(profile.Content)
	if err != nil {
		t.Fatal(err)
	}
	var root struct {
		Routing struct {
			DomainStrategy string           `json:"domainStrategy"`
			Rules          []map[string]any `json:"rules"`
		} `json:"routing"`
	}
	if err := json.Unmarshal([]byte(result.Content), &root); err != nil {
		t.Fatal(err)
	}
	r := root.Routing.Rules
	// The profile's domainStrategy is kept (no forced IPIfNonMatch).
	if root.Routing.DomainStrategy != anyString(anyMap(before["routing"])["domainStrategy"]) {
		t.Fatalf("domainStrategy changed to %s", root.Routing.DomainStrategy)
	}
	// ads(domain) | cn(domain, ip) | sites(domain, ip) | list(domain, ip) | node(domain) | profile private | fallback
	if fmt.Sprint(r[0]["domain"]) != "[geosite:category-ads-all]" || r[0]["outboundTag"] != "block" ||
		r[1]["outboundTag"] != "direct" || fmt.Sprint(r[2]["ip"]) != "[geoip:cn geoip:private]" ||
		r[0]["ruleTag"] != "fk #1 ads" || r[1]["ruleTag"] != "fk #2 cn (domain)" || r[2]["ruleTag"] != "fk #2 cn (ip)" ||
		r[3]["balancerTag"] != "proxy" || r[3]["port"] != "443,8000-9000" || r[3]["network"] != "tcp" ||
		fmt.Sprint(r[3]["domain"]) != "[domain:example.com full:a.example.org keyword:foo]" || fmt.Sprint(r[4]["ip"]) != "[1.2.3.0/24 geoip:jp]" ||
		r[7]["outboundTag"] != "ss-node" {
		t.Fatalf("rules: %v", r)
	}
	last := r[len(r)-1]
	if last["network"] != "tcp,udp" || last["outboundTag"] != "direct" || last["balancerTag"] != nil {
		t.Fatalf("fallback: %v", last)
	}
	for _, rule := range r[:len(r)-1] {
		if rule["balancerTag"] == "proxy" && rule["network"] == "tcp,udp" && len(rule) <= 3 {
			t.Fatalf("the profile's catch-all survived: %v", r)
		}
	}
	tags := map[string]bool{}
	for _, rule := range r {
		tag, _ := rule["ruleTag"].(string)
		if tag != "" && tags[tag] {
			t.Fatalf("duplicate ruleTag %q in %v", tag, r)
		}
		tags[tag] = true
	}
}

func TestCoreRoutingMihomo(t *testing.T) {
	previous := sWorkingPath
	sWorkingPath = t.TempDir()
	defer func() { sWorkingPath = previous }()
	profile, err := ConvertCoreShareLinksFor(CoreMihomo, testVlessXHTTP+"\n"+testShadowsocks)
	if err != nil {
		t.Fatal(err)
	}
	rules := append(testRoutingRules(),
		routingRule{Enabled: true, Name: "apps", Content: routingContentMatch, Values: []string{"app.example.com"}, Apps: []string{"com.a", "com.b"}, Network: "udp", Target: routingTarget{Type: routingTargetNode, Tag: "AUTO"}},
		routingRule{Enabled: true, Name: "one app", Content: routingContentAll, Apps: []string{"com.c"}, Target: routingTarget{Type: routingTargetDirect}},
	)
	set := routingSet{Mode: routingModePrepend, Final: routingTarget{Type: routingTargetDirect}, Rules: rules}
	result, err := applyCoreRouting(CoreMihomo, profile.Content, routingSetJSON(t, set), testRoutingLists)
	if err != nil {
		t.Fatal(err)
	}
	if err := CheckCoreConfig(CoreMihomo, result.Content); err != nil {
		t.Fatalf("mihomo rejected the compiled profile: %v\n%s", err, result.Content)
	}
	if result.Applied != 6 {
		t.Fatalf("applied %d, warnings %q", result.Applied, result.Warnings)
	}
	raw, err := parseCheckedMihomoConfig(result.Content)
	if err != nil {
		t.Fatal(err)
	}
	names := map[string]bool{"DIRECT": true, "REJECT": true}
	for _, group := range raw.ProxyGroup {
		names[group["name"].(string)] = true
	}
	for _, proxy := range raw.Proxy {
		names[proxy["name"].(string)] = true
	}
	for name := range raw.RuleProvider {
		if !strings.HasPrefix(name, "fk-list-") {
			t.Fatalf("unexpected provider %s", name)
		}
	}
	for index, line := range raw.Rule {
		tp, payload, target, params := mihomoRC.ParseRulePayload(line, true)
		if !names[target] {
			t.Fatalf("rule %d %q: unknown target %s", index, line, target)
		}
		// GEOSITE/GEOIP load the geo databases, and RULE-SET needs the
		// provider runtime; the rest is parsed by mihomo's own rule parser.
		if strings.Contains(line, "GEOSITE") || strings.Contains(line, "GEOIP") || tp == "RULE-SET" {
			continue
		}
		if _, err := mihomoRules.ParseRule(tp, payload, target, params, nil); err != nil {
			t.Fatalf("rule %d %q: %v", index, line, err)
		}
	}
	want := []string{
		"GEOSITE,category-ads-all,REJECT",
		"GEOSITE,cn,DIRECT",
		"GEOIP,lan,DIRECT,no-resolve",
		"AND,((DOMAIN-SUFFIX,example.com),(NETWORK,TCP),(DST-PORT,443/8000-9000)),PROXY",
		"AND,((IP-CIDR,1.2.3.0/24,no-resolve),(NETWORK,TCP),(DST-PORT,443/8000-9000)),PROXY",
		"AND,((GEOIP,jp,no-resolve),(NETWORK,TCP),(DST-PORT,443/8000-9000)),PROXY",
		"AND,((DOMAIN-SUFFIX,app.example.com),(NETWORK,UDP),(OR,((PROCESS-NAME,com.a),(PROCESS-NAME,com.b)))),AUTO",
		"PROCESS-NAME,com.c,DIRECT",
		"MATCH,DIRECT",
	}
	joined := strings.Join(raw.Rule, "\n")
	for _, line := range want {
		if !strings.Contains(joined, line) {
			t.Fatalf("missing %q in\n%s", line, joined)
		}
	}
	if strings.Count(joined, "MATCH,") != 1 || !strings.HasSuffix(joined, "MATCH,DIRECT") {
		t.Fatalf("fallback:\n%s", joined)
	}
	provider := "fk-list-" + routingListHash(testRoutingListURL) + "-domain"
	if raw.RuleProvider[provider]["behavior"] != "domain" || !strings.Contains(joined, "RULE-SET,"+provider+",REJECT") {
		t.Fatalf("list provider: %v", raw.RuleProvider)
	}
	data, err := os.ReadFile(filepath.Join(GeoDataDir(), mihomoRoutingDir, routingListHash(testRoutingListURL)+"-domain.txt"))
	if err != nil || !strings.Contains(string(data), "+.ads.example.com\n") || !strings.Contains(string(data), "\nhosts.example.org\n") {
		t.Fatalf("provider file: %q %v", data, err)
	}
	// The profile's other keys are kept.
	var root map[string]any
	if err := yaml.Unmarshal([]byte(result.Content), &root); err != nil || root["mixed-port"] == nil || root["proxy-groups"] == nil {
		t.Fatalf("profile keys: %v %v", root, err)
	}
}

func TestCoreRoutingListCache(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, routingListHash("http://127.0.0.1:1/list.txt")+".txt")
	if err := os.WriteFile(path, []byte("||cached.example.com^\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	old := time.Now().Add(-48 * time.Hour)
	_ = os.Chtimes(path, old, old)
	cache := defaultRoutingLists()
	cache.dir = dir
	list, err := cache.load("http://127.0.0.1:1/list.txt")
	if err != nil || !list.stale || list.match.suffix[0] != "cached.example.com" {
		t.Fatalf("stale cache not used: %+v %v", list, err)
	}
	if _, err := cache.load("http://127.0.0.1:1/other.txt"); err == nil {
		t.Fatal("missing list without cache did not fail")
	}
	if _, err := cache.load("https://example.com/geosite.srs"); err == nil {
		t.Fatal("binary rule-set accepted")
	}
}

// An app-only rule: no destination entries, only apps (and possibly a
// network or ports). The UI saves it as content "all"; a "match" rule with no
// entries but apps reads the same. A rule whose apps are all invalid is
// skipped rather than becoming a catch-all.
func TestCoreRoutingAppOnly(t *testing.T) {
	previous := sWorkingPath
	sWorkingPath = t.TempDir()
	defer func() { sWorkingPath = previous }()
	rules := []routingRule{
		{Enabled: true, Name: "wechat", Content: routingContentAll, Apps: []string{"com.tencent.mm"}, Target: routingTarget{Type: routingTargetDirect}},
		{Enabled: true, Name: "game", Content: routingContentMatch, Apps: []string{"com.example.game"}, Network: "udp", Target: routingTarget{Type: routingTargetProxy}},
		{Enabled: true, Name: "broken", Content: routingContentMatch, Apps: []string{"not a package!"}, Target: routingTarget{Type: routingTargetBlock}},
	}
	set := routingSet{Mode: routingModePrepend, Final: routingTarget{Type: routingTargetConfig}, Rules: rules}

	singProfile, err := ConvertCoreShareLinksFor(CoreAuto, testVlessReality+"\n"+testShadowsocks)
	if err != nil || singProfile.Core != CoreSingBox {
		t.Fatalf("convert: %v %v", singProfile, err)
	}
	result, err := applyCoreRouting(CoreSingBox, singProfile.Content, routingSetJSON(t, set), testRoutingLists)
	if err != nil {
		t.Fatal(err)
	}
	if result.Applied != 2 || !strings.Contains(result.Warnings, "broken: no usable apps") {
		t.Fatalf("sing-box applied %d, warnings %q", result.Applied, result.Warnings)
	}
	var sing struct {
		Route struct {
			Rules []map[string]any `json:"rules"`
		} `json:"route"`
	}
	if err := json.Unmarshal([]byte(result.Content), &sing); err != nil {
		t.Fatal(err)
	}
	found := 0
	for _, rule := range sing.Route.Rules {
		packages := fmt.Sprint(rule["package_name"])
		if packages != "[com.tencent.mm]" && packages != "[com.example.game]" {
			continue
		}
		found++
		for _, key := range []string{"domain", "domain_suffix", "ip_cidr", "rule_set", "ip_is_private"} {
			if rule[key] != nil {
				t.Fatalf("app-only rule has %s: %v", key, rule)
			}
		}
		if packages == "[com.example.game]" && fmt.Sprint(rule["network"]) != "[udp]" {
			t.Fatalf("network missing: %v", rule)
		}
	}
	if found != 2 {
		t.Fatalf("app rules not found in %v", sing.Route.Rules)
	}
	for _, rule := range sing.Route.Rules {
		if rule["action"] == "reject" && rule["package_name"] == nil && len(rule) <= 2 {
			t.Fatalf("the broken rule became a catch-all: %v", rule)
		}
	}

	mihomoProfile, err := ConvertCoreShareLinksFor(CoreMihomo, testVlessXHTTP+"\n"+testShadowsocks)
	if err != nil {
		t.Fatal(err)
	}
	result, err = applyCoreRouting(CoreMihomo, mihomoProfile.Content, routingSetJSON(t, set), testRoutingLists)
	if err != nil {
		t.Fatal(err)
	}
	if err := CheckCoreConfig(CoreMihomo, result.Content); err != nil {
		t.Fatalf("mihomo rejected the compiled profile: %v\n%s", err, result.Content)
	}
	if result.Applied != 2 || !strings.Contains(result.Content, "PROCESS-NAME,com.tencent.mm,DIRECT") ||
		!strings.Contains(result.Content, "AND,((NETWORK,UDP),(PROCESS-NAME,com.example.game)),") ||
		strings.Contains(result.Content, "MATCH,REJECT") {
		t.Fatalf("mihomo applied %d, warnings %q\n%s", result.Applied, result.Warnings, result.Content)
	}

	xrayProfile, err := ConvertCoreShareLinksFor(CoreAuto, testVlessXHTTP+"\n"+testShadowsocks)
	if err != nil || xrayProfile.Core != CoreXray {
		t.Fatalf("convert: %v %v", xrayProfile, err)
	}
	result, err = applyCoreRouting(CoreXray, xrayProfile.Content, routingSetJSON(t, set), testRoutingLists)
	if err != nil {
		t.Fatal(err)
	}
	if result.Applied != 0 || !strings.Contains(result.Warnings, "cannot match Android apps") {
		t.Fatalf("xray applied %d, warnings %q", result.Applied, result.Warnings)
	}
}
