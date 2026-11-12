package libbox

// Xray: user rules become routing.rules field rules in front of the profile's
// own rules (startXrayCore later puts its DNS rule ahead of all of them).
// Conditions inside one Xray rule are all required, so a rule that matches
// both domains and IPs is written as two rules, with distinct ruleTags
// ("… (domain)", "… (ip)"), since Xray rejects a repeated ruleTag. geosite:/geoip: are read from
// the shared geo directory (core_geo.go). Targets use outboundTag, or
// balancerTag when the profile routes through a balancer (link-converted and
// merged-array profiles do), so existing balancers keep working.

import (
	"encoding/json"
	"fmt"
	"sort"
	"strconv"
	"strings"
)

var xrayNonProxyProtocols = map[string]bool{"freedom": true, "blackhole": true, "dns": true, "loopback": true}

// xrayMatcherKeys are the rule fields that narrow what a rule matches; a rule
// with none of them except a network covering tcp and udp is a catch-all.
var xrayMatcherKeys = []string{"domain", "domains", "ip", "port", "sourceIP", "source", "sourcePort", "user", "vlessRoute", "inboundTag", "protocol", "attrs", "localIP", "localPort", "process", "localOS", "webhook"}

// A port condition covering every port ("0-65535", "1-65535", as v2rayN
// writes its catch-all) narrows nothing either.
func xrayIsCatchAll(rule map[string]any) bool {
	if rule == nil || (anyString(rule["outboundTag"]) == "" && anyString(rule["balancerTag"]) == "") {
		return false
	}
	for _, key := range xrayMatcherKeys {
		value, ok := rule[key]
		if !ok {
			continue
		}
		if key == "port" && xrayAllPorts(value) {
			continue
		}
		return false
	}
	value, ok := rule["network"]
	if !ok {
		return true
	}
	networks := map[string]bool{}
	var parts []string
	switch typed := value.(type) {
	case string:
		parts = strings.Split(typed, ",")
	case []any:
		for _, item := range typed {
			parts = append(parts, fmt.Sprint(item))
		}
	default:
		return false
	}
	for _, part := range parts {
		networks[strings.ToLower(strings.TrimSpace(part))] = true
	}
	return networks["tcp"] && networks["udp"]
}

// xrayAllPorts reports whether an Xray port condition (a number, "a-b",
// comma-separated list of either) covers 1-65535.
func xrayAllPorts(value any) bool {
	var text string
	switch typed := value.(type) {
	case string:
		text = typed
	case json.Number:
		text = typed.String()
	case float64:
		text = strconv.FormatFloat(typed, 'f', -1, 64)
	default:
		return false
	}
	type span struct{ from, to int }
	var spans []span
	for _, part := range strings.Split(text, ",") {
		part = strings.TrimSpace(part)
		if part == "" {
			continue
		}
		from, to, isRange := strings.Cut(part, "-")
		if !isRange {
			to = from
		}
		low, err1 := strconv.Atoi(strings.TrimSpace(from))
		high, err2 := strconv.Atoi(strings.TrimSpace(to))
		if err1 != nil || err2 != nil || low > high {
			return false
		}
		spans = append(spans, span{low, high})
	}
	sort.Slice(spans, func(i, j int) bool { return spans[i].from < spans[j].from })
	next := 1
	for _, item := range spans {
		if item.from > next {
			return false
		}
		if item.to+1 > next {
			next = item.to + 1
		}
	}
	return next > 65535
}

type xrayTargets struct {
	outbounds map[string]string // tag -> protocol
	balancers map[string]bool
	proxy     map[string]any // {"outboundTag": x} or {"balancerTag": x}
	direct    string
	block     string
	added     []any
}

func newXrayTargets(root map[string]any, routing map[string]any) *xrayTargets {
	targets := &xrayTargets{outbounds: map[string]string{}, balancers: map[string]bool{}}
	var first, firstProxy string
	for index, item := range anyList(root["outbounds"]) {
		outbound := anyMap(item)
		tag, protocol := anyString(outbound["tag"]), anyString(outbound["protocol"])
		if tag == "" {
			continue
		}
		targets.outbounds[tag] = protocol
		if index == 0 {
			first = tag
		}
		switch protocol {
		case "freedom":
			if targets.direct == "" {
				targets.direct = tag
			}
		case "blackhole":
			if targets.block == "" {
				targets.block = tag
			}
		}
		if !xrayNonProxyProtocols[protocol] && firstProxy == "" {
			firstProxy = tag
		}
	}
	for _, item := range anyList(routing["balancers"]) {
		if tag := anyString(anyMap(item)["tag"]); tag != "" {
			targets.balancers[tag] = true
		}
	}
	// The profile's main route: its last catch-all rule, a single balancer,
	// the default (first) outbound, then the first proxy outbound.
	rules := anyList(routing["rules"])
	for i := len(rules) - 1; i >= 0 && targets.proxy == nil; i-- {
		rule := anyMap(rules[i])
		if !xrayIsCatchAll(rule) {
			continue
		}
		if tag := anyString(rule["balancerTag"]); tag != "" {
			targets.proxy = map[string]any{"balancerTag": tag}
		} else if tag := anyString(rule["outboundTag"]); !xrayNonProxyProtocols[targets.outbounds[tag]] {
			targets.proxy = map[string]any{"outboundTag": tag}
		}
	}
	if targets.proxy == nil && len(targets.balancers) == 1 {
		for tag := range targets.balancers {
			targets.proxy = map[string]any{"balancerTag": tag}
		}
	}
	if targets.proxy == nil && first != "" && !xrayNonProxyProtocols[targets.outbounds[first]] {
		targets.proxy = map[string]any{"outboundTag": first}
	}
	if targets.proxy == nil && firstProxy != "" {
		targets.proxy = map[string]any{"outboundTag": firstProxy}
	}
	return targets
}

func (t *xrayTargets) used() map[string]bool {
	used := map[string]bool{}
	for tag := range t.outbounds {
		used[tag] = true
	}
	return used
}

func (t *xrayTargets) target(target routingTarget) (map[string]any, error) {
	switch target.Type {
	case routingTargetProxy:
		if t.proxy == nil {
			return nil, fmt.Errorf("the profile has no proxy outbound")
		}
		return t.proxy, nil
	case routingTargetDirect:
		if t.direct == "" {
			t.direct = routingUniqueTag(t.used(), "fk-direct")
			t.outbounds[t.direct] = "freedom"
			t.added = append(t.added, map[string]any{"tag": t.direct, "protocol": "freedom"})
		}
		return map[string]any{"outboundTag": t.direct}, nil
	case routingTargetBlock:
		if t.block == "" {
			t.block = routingUniqueTag(t.used(), "fk-block")
			t.outbounds[t.block] = "blackhole"
			t.added = append(t.added, map[string]any{"tag": t.block, "protocol": "blackhole"})
		}
		return map[string]any{"outboundTag": t.block}, nil
	case routingTargetNode:
		if t.balancers[target.Tag] {
			return map[string]any{"balancerTag": target.Tag}, nil
		}
		if _, ok := t.outbounds[target.Tag]; ok {
			return map[string]any{"outboundTag": target.Tag}, nil
		}
		return nil, fmt.Errorf("outbound or balancer %q is not in the profile", target.Tag)
	}
	return nil, fmt.Errorf("unknown target %s", target.Type)
}

func xrayDomains(match *routingMatch) []string {
	var domains []string
	for _, value := range match.suffix {
		domains = append(domains, "domain:"+value)
	}
	for _, value := range match.full {
		domains = append(domains, "full:"+value)
	}
	for _, value := range match.keyword {
		domains = append(domains, "keyword:"+value)
	}
	for _, value := range match.regex {
		domains = append(domains, "regexp:"+value)
	}
	for _, value := range match.geosite {
		domains = append(domains, "geosite:"+value)
	}
	return domains
}

func xrayIPs(match *routingMatch) []string {
	ips := append([]string(nil), match.cidr...)
	for _, value := range match.geoip {
		ips = append(ips, "geoip:"+value)
	}
	return ips
}

func applyXrayRouting(content string, set *routingSet, rules []compiledRule, warn func(string)) (string, int, error) {
	root, err := decodeOrderedJSON(content)
	if err != nil {
		return "", 0, fmt.Errorf("Xray profile: %w", err)
	}
	routing := anyMap(root["routing"])
	if routing == nil {
		routing = map[string]any{}
	}
	targets := newXrayTargets(root, routing)
	existing := anyList(routing["rules"])
	// Xray refuses a config whose rules repeat a ruleTag (router.ReloadRules:
	// "duplicate ruleTag"), so every tag written here is unique, also against
	// the profile's own.
	ruleTags := map[string]bool{}
	for _, item := range existing {
		if tag := anyString(anyMap(item)["ruleTag"]); tag != "" {
			ruleTags[tag] = true
		}
	}
	uniqueRuleTag := func(base string) string {
		tag := base
		for i := 2; ruleTags[tag]; i++ {
			tag = fmt.Sprintf("%s [%d]", base, i)
		}
		ruleTags[tag] = true
		return tag
	}
	var userRules []any
	applied := 0
	for _, rule := range rules {
		if len(rule.apps) > 0 {
			warn(rule.label + ": Xray cannot match Android apps, skipped (use Access control or the sing-box / mihomo core)")
			continue
		}
		target, err := targets.target(rule.target)
		if err != nil {
			warn(rule.label + ": " + err.Error() + ", skipped")
			continue
		}
		conditions := map[string]any{}
		if rule.network != "" {
			conditions["network"] = rule.network
		}
		if len(rule.ports) > 0 {
			var parts []string
			for _, port := range rule.ports {
				parts = append(parts, port.String("-"))
			}
			conditions["port"] = strings.Join(parts, ",")
		}
		// suffix tells apart the two Xray rules one user rule becomes when it
		// matches domains and IPs (conditions inside one Xray rule are ANDed).
		build := func(key string, values []string, suffix string) map[string]any {
			entry := map[string]any{}
			for k, v := range conditions {
				entry[k] = v
			}
			if key != "" {
				entry[key] = values
			} else if len(conditions) == 0 {
				entry["network"] = "tcp,udp"
			}
			for k, v := range target {
				entry[k] = v
			}
			if rule.label != "" {
				entry["ruleTag"] = uniqueRuleTag("fk " + rule.label + suffix)
			}
			return entry
		}
		if rule.all {
			userRules = append(userRules, build("", nil, ""))
		} else {
			destination := rule.destination()
			domains, ips := xrayDomains(destination), xrayIPs(destination)
			domainSuffix, ipSuffix := "", ""
			if len(domains) > 0 && len(ips) > 0 {
				domainSuffix, ipSuffix = " (domain)", " (ip)"
			}
			if len(domains) > 0 {
				userRules = append(userRules, build("domain", domains, domainSuffix))
			}
			if len(ips) > 0 {
				userRules = append(userRules, build("ip", ips, ipSuffix))
			}
		}
		applied++
	}
	var tail []any
	if set.Mode == routingModePrepend {
		tail = existing
	} else if set.Final.Type == routingTargetConfig {
		// Override keeps the profile's own fallback route.
		for _, item := range existing {
			if xrayIsCatchAll(anyMap(item)) {
				tail = append(tail, item)
			}
		}
	}
	if set.Final.Type != routingTargetConfig {
		target, err := targets.target(set.Final)
		if err != nil {
			warn("fallback: " + err.Error() + ", the profile's own fallback is kept")
		} else {
			// The user's fallback replaces the profile's catch-all rules.
			var kept []any
			for _, item := range tail {
				if !xrayIsCatchAll(anyMap(item)) {
					kept = append(kept, item)
				}
			}
			final := map[string]any{"network": "tcp,udp", "ruleTag": uniqueRuleTag("fk fallback")}
			for k, v := range target {
				final[k] = v
			}
			tail = append(kept, final)
		}
	}
	routing["rules"] = append(userRules, tail...)
	// The profile's domainStrategy is kept. With AsIs (Xray's default) an IP
	// rule matches connections to IP addresses (and to domains the profile
	// resolves itself) without a DNS lookup for every unmatched domain, the
	// way sing-box ip_cidr and mihomo no-resolve rules behave; a profile that
	// wants IP rules to match sniffed domains as well sets IPIfNonMatch or
	// IPOnDemand itself.
	root["routing"] = routing
	if len(targets.added) > 0 {
		root["outbounds"] = append(anyList(root["outbounds"]), targets.added...)
	}
	encoded, err := encodeRoutingJSON(root)
	if err != nil {
		return "", 0, err
	}
	return encoded, applied, nil
}
