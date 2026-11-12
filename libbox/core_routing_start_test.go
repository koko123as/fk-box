package libbox

// Routing rules started for real: the compiled Xray configuration goes through
// xrayCore.New (which initialises the router and so runs the checks
// router.ReloadRules makes, such as unique ruleTags) for every preset and for
// mixed rule sets, so a compiler change that Xray refuses to start with fails
// here instead of on the phone.

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	mihomoConstant "github.com/metacubex/mihomo/constant"
	mihomoRules "github.com/metacubex/mihomo/rules"
	mihomoRC "github.com/metacubex/mihomo/rules/common"
	xrayGeodata "github.com/xtls/xray-core/common/geodata"
	xrayCore "github.com/xtls/xray-core/core"
	xrayConf "github.com/xtls/xray-core/infra/conf"
	xraySerial "github.com/xtls/xray-core/infra/conf/serial"
	"google.golang.org/protobuf/proto"
)

// writeXrayTestGeoData writes small geosite.dat / geoip.dat files with every
// code the presets and tests use into the shared geo directory.
func writeXrayTestGeoData(t *testing.T) {
	t.Helper()
	sites := &xrayGeodata.GeoSiteList{}
	for code, domain := range map[string]string{"CN": "example.cn", "PRIVATE": "lan", "GFW": "example.org", "CATEGORY-ADS-ALL": "ads.example.net", "GEOLOCATION-!CN": "example.com"} {
		sites.Entry = append(sites.Entry, &xrayGeodata.GeoSite{Code: code, Domain: []*xrayGeodata.Domain{{Type: xrayGeodata.Domain_Domain, Value: domain}}})
	}
	ips := &xrayGeodata.GeoIPList{}
	for code, ip := range map[string][]byte{"CN": {1, 0, 1, 0}, "PRIVATE": {10, 0, 0, 0}, "JP": {1, 0, 16, 0}} {
		ips.Entry = append(ips.Entry, &xrayGeodata.GeoIP{Code: code, Cidr: []*xrayGeodata.CIDR{{Ip: ip, Prefix: 24}}})
	}
	dir := GeoDataDir()
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	for name, message := range map[string]proto.Message{"geosite.dat": sites, "geoip.dat": ips} {
		data, err := proto.Marshal(message)
		if err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(dir, name), data, 0o644); err != nil {
			t.Fatal(err)
		}
	}
	t.Setenv("xray.location.asset", dir)
}

// startXrayForTest builds the configuration startXrayCore would start (minus
// the TUN inbound, which needs a file descriptor) and creates the instance.
func startXrayForTest(content string) error {
	root, _, err := xrayStartRoot(content)
	if err != nil {
		return err
	}
	root["inbounds"] = []any{}
	encoded, err := json.Marshal(root)
	if err != nil {
		return err
	}
	config, err := xraySerial.LoadJSONConfig(strings.NewReader(string(encoded)))
	if err != nil {
		return err
	}
	instance, err := xrayCore.New(config)
	if err != nil {
		return err
	}
	return instance.Close()
}

func TestCoreRoutingXrayStarts(t *testing.T) {
	previous := sWorkingPath
	sWorkingPath = t.TempDir()
	defer func() { sWorkingPath = previous }()
	writeXrayTestGeoData(t)

	profile, err := ConvertCoreShareLinksFor(CoreAuto, testVlessXHTTP+"\n"+testShadowsocks)
	if err != nil || profile.Core != CoreXray {
		t.Fatalf("convert: %v %v", profile, err)
	}
	// The profile itself starts.
	if err := startXrayForTest(profile.Content); err != nil {
		t.Fatalf("profile without rules: %v", err)
	}
	// A repeated ruleTag is what Xray refuses (router.ReloadRules), and the
	// profile check reports it before the core is started.
	duplicate := `{"outbounds":[{"tag":"direct","protocol":"freedom"}],"routing":{"rules":[` +
		`{"domain":["example.com"],"outboundTag":"direct","ruleTag":"x"},{"ip":["1.1.1.1"],"outboundTag":"direct","ruleTag":"x"}]}}`
	if err := startXrayForTest(duplicate); err == nil || !strings.Contains(err.Error(), "duplicate ruleTag") {
		t.Fatalf("Xray accepted a duplicate ruleTag: %v", err)
	}
	if err := CheckCoreConfig(CoreXray, duplicate); err == nil || !strings.Contains(err.Error(), "duplicate ruleTag") {
		t.Fatalf("checkXrayConfig accepted a duplicate ruleTag: %v", err)
	}

	type namedSet struct {
		name string
		set  routingSet
	}
	var sets []namedSet
	for id := range routingPresets {
		for _, mode := range []string{routingModePrepend, routingModeOverride} {
			sets = append(sets, namedSet{"preset " + id + " " + mode, routingSet{Mode: mode, Final: routingTarget{Type: routingTargetConfig}, Rules: []routingRule{
				{Enabled: true, Name: id, Content: routingContentPreset, Preset: id, Target: routingTarget{Type: routingTargetDirect}},
			}}})
		}
	}
	// Every preset at once, the same preset twice and unnamed rules.
	var all []routingRule
	for id := range routingPresets {
		all = append(all, routingRule{Enabled: true, Name: "same", Content: routingContentPreset, Preset: id, Target: routingTarget{Type: routingTargetProxy}})
	}
	all = append(all,
		routingRule{Enabled: true, Content: routingContentPreset, Preset: "cn-direct", Target: routingTarget{Type: routingTargetDirect}},
		routingRule{Enabled: true, Content: routingContentPreset, Preset: "cn-direct", Target: routingTarget{Type: routingTargetBlock}},
	)
	sets = append(sets,
		namedSet{"all presets", routingSet{Mode: routingModePrepend, Final: routingTarget{Type: routingTargetDirect}, Rules: all}},
		namedSet{"mixed prepend", routingSet{Mode: routingModePrepend, Final: routingTarget{Type: routingTargetDirect}, Rules: append(testRoutingRules(),
			routingRule{Enabled: true, Name: "node", Content: routingContentMatch, Values: []string{"node.example.com", "198.51.100.0/24"}, Target: routingTarget{Type: routingTargetNode, Tag: "ss-node"}},
			routingRule{Enabled: true, Name: "fallback", Content: routingContentAll, Ports: "1-65535", Target: routingTarget{Type: routingTargetProxy}},
		)}},
		namedSet{"mixed override", routingSet{Mode: routingModeOverride, Final: routingTarget{Type: routingTargetConfig}, Rules: testRoutingRules()}},
		namedSet{"mixed override block", routingSet{Mode: routingModeOverride, Final: routingTarget{Type: routingTargetBlock}, Rules: testRoutingRules()}},
	)
	for _, item := range sets {
		result, err := applyCoreRouting(CoreXray, profile.Content, routingSetJSON(t, item.set), testRoutingLists)
		if err != nil {
			t.Fatalf("%s: %v", item.name, err)
		}
		if !result.Active {
			t.Fatalf("%s: nothing applied", item.name)
		}
		if err := CheckCoreConfig(CoreXray, result.Content); err != nil {
			t.Fatalf("%s: check: %v\n%s", item.name, err, result.Content)
		}
		if err := startXrayForTest(result.Content); err != nil {
			t.Fatalf("%s: Xray did not start: %v\n%s", item.name, err, result.Content)
		}
	}

	// A profile whose own rules already use the tags the compiler writes.
	taken := `{"outbounds":[{"tag":"proxy","protocol":"socks","settings":{"servers":[{"address":"127.0.0.1","port":1080}]}},{"tag":"direct","protocol":"freedom"}],` +
		`"routing":{"rules":[{"domain":["a.example"],"outboundTag":"direct","ruleTag":"fk #1 cn (domain)"},{"network":"tcp,udp","outboundTag":"proxy","ruleTag":"fk fallback"}]}}`
	set := routingSet{Mode: routingModePrepend, Final: routingTarget{Type: routingTargetDirect}, Rules: []routingRule{
		{Enabled: true, Name: "cn", Content: routingContentPreset, Preset: "cn-direct", Target: routingTarget{Type: routingTargetDirect}},
	}}
	result, err := applyCoreRouting(CoreXray, taken, routingSetJSON(t, set), testRoutingLists)
	if err != nil {
		t.Fatal(err)
	}
	if err := startXrayForTest(result.Content); err != nil {
		t.Fatalf("taken tags: %v\n%s", err, result.Content)
	}
}

func TestCoreRoutingXrayCatchAll(t *testing.T) {
	for _, rule := range []map[string]any{
		{"outboundTag": "proxy"},
		{"outboundTag": "proxy", "network": "tcp,udp"},
		{"outboundTag": "proxy", "network": "udp, TCP"},
		{"outboundTag": "proxy", "network": []any{"tcp", "udp"}},
		{"outboundTag": "proxy", "port": "0-65535"},
		{"outboundTag": "proxy", "port": "1-65535", "network": "tcp,udp"},
		{"balancerTag": "b", "port": "1-1000,1001-65535"},
		{"outboundTag": "proxy", "port": json.Number("1-65535")},
	} {
		if !xrayIsCatchAll(rule) {
			t.Errorf("%v is a catch-all", rule)
		}
	}
	for _, rule := range []map[string]any{
		{"outboundTag": "proxy", "port": "1-1000"},
		{"outboundTag": "proxy", "port": "2-65535"},
		{"outboundTag": "proxy", "network": "tcp"},
		{"outboundTag": "proxy", "port": "0-65535", "domain": []any{"a.com"}},
		{"port": "0-65535"},
	} {
		if xrayIsCatchAll(rule) {
			t.Errorf("%v is not a catch-all", rule)
		}
	}
	// v2rayN writes its catch-all with a port range: the user's fallback
	// replaces it in prepend mode, and override keeps it as the fallback.
	profile := `{"outbounds":[{"tag":"proxy","protocol":"socks","settings":{"servers":[{"address":"127.0.0.1","port":1080}]}},{"tag":"direct","protocol":"freedom"},{"tag":"block","protocol":"blackhole"}],` +
		`"routing":{"rules":[{"domain":["a.example"],"outboundTag":"block"},{"type":"field","port":"0-65535","outboundTag":"proxy"}]}}`
	rules := []routingRule{{Enabled: true, Name: "x", Content: routingContentMatch, Values: []string{"b.example"}, Target: routingTarget{Type: routingTargetDirect}}}
	rulesOf := func(content string) []map[string]any {
		var root struct {
			Routing struct {
				Rules []map[string]any `json:"rules"`
			} `json:"routing"`
		}
		if err := json.Unmarshal([]byte(content), &root); err != nil {
			t.Fatal(err)
		}
		return root.Routing.Rules
	}
	result, err := applyCoreRouting(CoreXray, profile, routingSetJSON(t, routingSet{Mode: routingModePrepend, Final: routingTarget{Type: routingTargetDirect}, Rules: rules}), testRoutingLists)
	if err != nil {
		t.Fatal(err)
	}
	r := rulesOf(result.Content)
	if len(r) != 3 || r[1]["outboundTag"] != "block" || r[2]["outboundTag"] != "direct" || r[2]["ruleTag"] != "fk fallback" {
		t.Fatalf("prepend: %v", r)
	}
	result, err = applyCoreRouting(CoreXray, profile, routingSetJSON(t, routingSet{Mode: routingModeOverride, Final: routingTarget{Type: routingTargetConfig}, Rules: rules}), testRoutingLists)
	if err != nil {
		t.Fatal(err)
	}
	r = rulesOf(result.Content)
	if len(r) != 2 || r[1]["port"] != "0-65535" || r[1]["outboundTag"] != "proxy" {
		t.Fatalf("override: %v", r)
	}
	// The profile's main route is found through that catch-all too.
	result, err = applyCoreRouting(CoreXray, `{"outbounds":[{"tag":"direct","protocol":"freedom"},{"tag":"main","protocol":"socks","settings":{"servers":[{"address":"127.0.0.1","port":1080}]}}],`+
		`"routing":{"rules":[{"port":"1-65535","network":"tcp,udp","outboundTag":"main"}]}}`,
		routingSetJSON(t, routingSet{Mode: routingModePrepend, Final: routingTarget{Type: routingTargetConfig}, Rules: []routingRule{{Enabled: true, Name: "p", Content: routingContentMatch, Values: []string{"c.example"}, Target: routingTarget{Type: routingTargetProxy}}}}), testRoutingLists)
	if err != nil {
		t.Fatal(err)
	}
	if r = rulesOf(result.Content); r[0]["outboundTag"] != "main" {
		t.Fatalf("proxy target: %v", r)
	}
}

func TestCoreRoutingXrayRuleTagCheck(t *testing.T) {
	var config xrayConf.RouterConfig
	if err := json.Unmarshal([]byte(`{"rules":[{"ruleTag":"a","outboundTag":"x"},{"outboundTag":"x"},{"outboundTag":"x"},{"ruleTag":"b","outboundTag":"x"}]}`), &config); err != nil {
		t.Fatal(err)
	}
	if err := checkXrayRuleTags(&config); err != nil {
		t.Fatalf("unique tags rejected: %v", err)
	}
	config.RuleList = append(config.RuleList, json.RawMessage(`{"ruleTag":"a","outboundTag":"y"}`))
	if err := checkXrayRuleTags(&config); err == nil {
		t.Fatal("duplicate ruleTag accepted")
	}
}

func TestCoreRoutingSingBoxSniff(t *testing.T) {
	profile := `{"outbounds":[{"type":"socks","tag":"proxy","server":"127.0.0.1","server_port":1080},{"type":"direct","tag":"direct"}],` +
		`"route":{"rules":[{"protocol":"dns","action":"hijack-dns"},{"domain_suffix":["a.example"],"outbound":"direct"},{"inbound":["tun-in"],"action":"sniff"}],"final":"proxy"}}`
	set := routingSet{Mode: routingModePrepend, Final: routingTarget{Type: routingTargetConfig}, Rules: []routingRule{
		{Enabled: true, Name: "d", Content: routingContentMatch, Values: []string{"b.example"}, Target: routingTarget{Type: routingTargetDirect}},
	}}
	result, err := applyCoreRouting(CoreSingBox, profile, routingSetJSON(t, set), testRoutingLists)
	if err != nil {
		t.Fatal(err)
	}
	if err := CheckConfig(result.Content); err != nil {
		t.Fatalf("sing-box rejected: %v\n%s", err, result.Content)
	}
	var root struct {
		Route struct {
			Rules []map[string]any `json:"rules"`
		} `json:"route"`
	}
	if err := json.Unmarshal([]byte(result.Content), &root); err != nil {
		t.Fatal(err)
	}
	r := root.Route.Rules
	// A plain sniff goes in front, before the user rule; the profile's own
	// (conditional) sniff further down stays where it was.
	if len(r) != 5 || r[0]["action"] != "sniff" || len(r[0]) != 1 || r[1]["action"] != "hijack-dns" || fmt.Sprint(r[2]["domain_suffix"]) != "[b.example]" || r[4]["action"] != "sniff" {
		t.Fatalf("rules: %v", r)
	}
	// A plain sniff already at the head is used as it is.
	profile = strings.Replace(profile, `"rules":[`, `"rules":[{"action":"sniff","timeout":"1s"},`, 1)
	result, err = applyCoreRouting(CoreSingBox, profile, routingSetJSON(t, set), testRoutingLists)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Count(result.Content, `"sniff"`) != 2 {
		t.Fatalf("sniff added twice:\n%s", result.Content)
	}
}

func TestCoreRoutingMihomoResolve(t *testing.T) {
	previous := sWorkingPath
	sWorkingPath = t.TempDir()
	defer func() { sWorkingPath = previous }()
	profile := strings.Join([]string{
		"find-process-mode: off",
		"proxies:",
		"  - {name: node, type: socks5, server: 127.0.0.1, port: 1080}",
		"proxy-providers:",
		"  sub: {type: file, path: ./sub.yaml}",
		"proxy-groups:",
		"  - {name: PROXY, type: select, proxies: [node], use: [sub]}",
		"rules:",
		"  - MATCH,PROXY",
	}, "\n")
	set := routingSet{Mode: routingModePrepend, Final: routingTarget{Type: routingTargetConfig}, Rules: []routingRule{
		{Enabled: true, Name: "cn", Content: routingContentPreset, Preset: "cn-direct", Target: routingTarget{Type: routingTargetDirect}},
		{Enabled: true, Name: "ip", Content: routingContentMatch, Values: []string{"1.2.3.0/24", "regexp:^a{1,3}\\.example$"}, Network: "tcp", Target: routingTarget{Type: routingTargetProxy}},
		{Enabled: true, Name: "app", Content: routingContentAll, Apps: []string{"com.example"}, Target: routingTarget{Type: routingTargetDirect}},
		{Enabled: true, Name: "provider node", Content: routingContentAll, Target: routingTarget{Type: routingTargetNode, Tag: "sub-node-1"}},
		{Enabled: true, Name: "list", Content: routingContentList, URL: testRoutingListURL, Target: routingTarget{Type: routingTargetBlock}},
	}}
	result, err := applyCoreRouting(CoreMihomo, profile, routingSetJSON(t, set), testRoutingLists)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(result.Warnings, "find-process-mode: off") || !strings.Contains(result.Warnings, "proxy-providers sub") {
		t.Fatalf("warnings %q", result.Warnings)
	}
	raw, err := parseCheckedMihomoConfig(result.Content)
	if err != nil {
		t.Fatalf("mihomo rejected: %v\n%s", err, result.Content)
	}
	joined := strings.Join(raw.Rule, "\n")
	for _, line := range []string{
		"GEOIP,cn,DIRECT,no-resolve",
		"GEOIP,lan,DIRECT,no-resolve",
		"AND,((IP-CIDR,1.2.3.0/24,no-resolve),(NETWORK,TCP)),PROXY",
		"AND,((DOMAIN-REGEX,^a{1,3}\\.example$),(NETWORK,TCP)),PROXY",
		"RULE-SET,fk-list-" + routingListHash(testRoutingListURL) + "-ipcidr,REJECT,no-resolve",
		"RULE-SET,fk-list-" + routingListHash(testRoutingListURL) + "-domain,REJECT",
	} {
		if !strings.Contains(joined, line) {
			t.Fatalf("missing %q in\n%s", line, joined)
		}
	}
	// None of the IP rules asks mihomo to resolve a domain.
	metadata := &mihomoConstant.Metadata{NetWork: mihomoConstant.TCP, Host: "aa.example", DstPort: 443}
	for _, line := range raw.Rule {
		tp, payload, target, params := mihomoRC.ParseRulePayload(line, true)
		if tp != "IP-CIDR" && tp != "AND" {
			continue
		}
		rule, err := mihomoRules.ParseRule(tp, payload, target, params, nil)
		if err != nil {
			t.Fatalf("%q: %v", line, err)
		}
		resolved := false
		matched, _ := rule.Match(metadata, mihomoConstant.RuleMatchHelper{ResolveIP: func() { resolved = true }, FindProcess: func() {}})
		if resolved {
			t.Fatalf("%q resolves the domain", line)
		}
		if strings.Contains(line, "DOMAIN-REGEX") && !matched {
			t.Fatalf("%q does not match aa.example", line)
		}
	}
	targets, err := CoreRoutingTargets(CoreMihomo, profile)
	if err != nil || targets != "PROXY\nnode" {
		t.Fatalf("targets %q %v", targets, err)
	}
}

func TestCoreRoutingTargets(t *testing.T) {
	singBox := `{"outbounds":[{"type":"selector","tag":"select","outbounds":["a"]},{"type":"socks","tag":"a"},{"type":"direct","tag":"direct"},{"type":"dns","tag":"dns"}],"endpoints":[{"type":"wireguard","tag":"wg"}]}`
	if names, err := CoreRoutingTargets(CoreSingBox, singBox); err != nil || names != "select\na\ndirect\nwg" {
		t.Fatalf("sing-box %q %v", names, err)
	}
	xray := `{"outbounds":[{"tag":"proxy","protocol":"vless"},{"tag":"dns-out","protocol":"dns"}],"routing":{"balancers":[{"tag":"auto","selector":["proxy"]}]}}`
	if names, err := CoreRoutingTargets(CoreXray, xray); err != nil || names != "auto\nproxy" {
		t.Fatalf("xray %q %v", names, err)
	}
}

func TestCoreRoutingListFormats(t *testing.T) {
	var match routingMatch
	parseRoutingListText(strings.Join([]string{
		"# v2fly",
		"full:full.example.com",
		"domain:suffix.example.com @cn",
		"keyword:kw",
		"regexp:^r{1,3}\\.example\\.com$",
		"include:other-list",
		"bare.example.net @ads",
		"DOMAIN-REGEX,^c{2,4}\\.example$",
		"  - 'DOMAIN-REGEX,^y(a|b)\\.example$'",
	}, "\n"), &match)
	if strings.Join(match.full, ",") != "full.example.com" || strings.Join(match.suffix, ",") != "suffix.example.com,bare.example.net" ||
		strings.Join(match.keyword, ",") != "kw" || strings.Join(match.regex, " ") != "^r{1,3}\\.example\\.com$ ^c{2,4}\\.example$ ^y(a|b)\\.example$" {
		t.Fatalf("match %+v", match)
	}
}

func TestCoreRoutingListsParallel(t *testing.T) {
	var running, peak atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		now := running.Add(1)
		defer running.Add(-1)
		for {
			old := peak.Load()
			if now <= old || peak.CompareAndSwap(old, now) {
				break
			}
		}
		if strings.Contains(r.URL.Path, "slow") {
			select {
			case <-time.After(5 * time.Second):
			case <-r.Context().Done():
				return
			}
		} else {
			time.Sleep(300 * time.Millisecond)
		}
		fmt.Fprintf(w, "||%s.example.com^\n", strings.Trim(r.URL.Path, "/"))
	}))
	defer server.Close()
	cache := defaultRoutingLists()
	cache.dir = t.TempDir()
	cache.total = time.Second
	var rules []routingRule
	for i := range 4 {
		rules = append(rules, routingRule{Enabled: true, Name: fmt.Sprint(i), Content: routingContentList, URL: fmt.Sprintf("%s/l%d", server.URL, i), Target: routingTarget{Type: routingTargetBlock}})
	}
	rules = append(rules, routingRule{Enabled: true, Name: "slow", Content: routingContentList, URL: server.URL + "/slow", Target: routingTarget{Type: routingTargetBlock}})
	var warnings []string
	started := time.Now()
	compiled := compileRules(&routingSet{Mode: routingModePrepend, Rules: rules}, cache, func(message string) { warnings = append(warnings, message) })
	elapsed := time.Since(started)
	if len(compiled) != 4 || len(warnings) != 1 || !strings.Contains(warnings[0], "slow") {
		t.Fatalf("compiled %d, warnings %v", len(compiled), warnings)
	}
	if elapsed > 3*time.Second || peak.Load() < 2 {
		t.Fatalf("lists were not downloaded in parallel within the bound: %v, peak %d", elapsed, peak.Load())
	}
}

func TestCoreRoutingRuleSetDownloadError(t *testing.T) {
	if !routingIsRuleSetDownload("start service: initialize rule-set: initial rule-set: fk-geosite-cn: Get \"https://raw.githubusercontent.com/...\": EOF") {
		t.Fatal("rule-set download error not recognised")
	}
	if routingIsRuleSetDownload("start service: create outbound proxy: unknown type") {
		t.Fatal("other error taken for a rule-set download")
	}
}
