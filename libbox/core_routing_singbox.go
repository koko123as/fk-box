package libbox

// sing-box: user rules become route.rules entries with rule actions (route /
// reject), placed after the profile's leading sniff / hijack-dns / resolve
// rules so domain matching and DNS keep working. geosite:/geoip: become remote
// binary rule-sets from MetaCubeX/meta-rules-dat (sing branch), downloaded
// through the profile's proxy outbound and kept in the cache file; sing-box
// 1.12 removed the legacy geosite/geoip databases, so none is used.

import (
	"bytes"
	"encoding/json"
	"fmt"
	"strings"
)

func decodeOrderedJSON(content string) (map[string]any, error) {
	decoder := json.NewDecoder(strings.NewReader(normalizeCoreContent(content)))
	decoder.UseNumber()
	var root map[string]any
	if err := decoder.Decode(&root); err != nil {
		return nil, err
	}
	if root == nil {
		return nil, fmt.Errorf("not a JSON object")
	}
	return root, nil
}

func encodeRoutingJSON(root map[string]any) (string, error) {
	var buffer bytes.Buffer
	encoder := json.NewEncoder(&buffer)
	encoder.SetEscapeHTML(false)
	encoder.SetIndent("", "  ")
	if err := encoder.Encode(root); err != nil {
		return "", err
	}
	return buffer.String(), nil
}

func anyList(value any) []any {
	list, _ := value.([]any)
	return list
}

func anyMap(value any) map[string]any {
	object, _ := value.(map[string]any)
	return object
}

func anyString(value any) string {
	text, _ := value.(string)
	return text
}

// singBoxLeadingRules counts the rules at the head of route.rules that must
// stay in front of every user rule.
func singBoxLeadingRules(rules []any) int {
	count := 0
	for _, item := range rules {
		switch anyString(anyMap(item)["action"]) {
		case "sniff", "hijack-dns", "resolve":
			count++
		default:
			return count
		}
	}
	return count
}

type singBoxTargets struct {
	tags     map[string]string // tag -> type
	proxy    string
	direct   string
	addedOut []any
}

func newSingBoxTargets(root map[string]any) *singBoxTargets {
	targets := &singBoxTargets{tags: map[string]string{}}
	var firstSelector, firstURLTest, firstProxy string
	for _, key := range []string{"outbounds", "endpoints"} {
		for _, item := range anyList(root[key]) {
			outbound := anyMap(item)
			tag, kind := anyString(outbound["tag"]), anyString(outbound["type"])
			if tag == "" {
				continue
			}
			targets.tags[tag] = kind
			switch kind {
			case "direct":
				if targets.direct == "" {
					targets.direct = tag
				}
			case "block", "dns":
			case "selector":
				if firstSelector == "" {
					firstSelector = tag
				}
			case "urltest":
				if firstURLTest == "" {
					firstURLTest = tag
				}
			default:
				if firstProxy == "" {
					firstProxy = tag
				}
			}
		}
	}
	final := anyString(anyMap(root["route"])["final"])
	switch kind := targets.tags[final]; {
	case final != "" && kind != "" && kind != "direct" && kind != "block" && kind != "dns":
		targets.proxy = final
	case firstSelector != "":
		targets.proxy = firstSelector
	case firstURLTest != "":
		targets.proxy = firstURLTest
	default:
		targets.proxy = firstProxy
	}
	return targets
}

// outbound maps a target to an outbound tag; "" with ok means reject.
func (t *singBoxTargets) outbound(target routingTarget) (string, error) {
	switch target.Type {
	case routingTargetProxy:
		if t.proxy == "" {
			return "", fmt.Errorf("the profile has no proxy outbound")
		}
		return t.proxy, nil
	case routingTargetDirect:
		if t.direct == "" {
			used := map[string]bool{}
			for tag := range t.tags {
				used[tag] = true
			}
			t.direct = routingUniqueTag(used, "fk-direct")
			t.tags[t.direct] = "direct"
			t.addedOut = append(t.addedOut, map[string]any{"type": "direct", "tag": t.direct})
		}
		return t.direct, nil
	case routingTargetBlock:
		return "", nil
	case routingTargetNode:
		if _, ok := t.tags[target.Tag]; !ok {
			return "", fmt.Errorf("outbound %q is not in the profile", target.Tag)
		}
		return target.Tag, nil
	}
	return "", fmt.Errorf("unknown target %s", target.Type)
}

// singBoxPlainSniff: a sniff action that applies to every connection (only
// sniff options, no match conditions).
func singBoxPlainSniff(rule map[string]any) bool {
	if anyString(rule["action"]) != "sniff" {
		return false
	}
	for key := range rule {
		switch key {
		case "action", "sniffer", "timeout", "type":
		default:
			return false
		}
	}
	return anyString(rule["type"]) == "" || anyString(rule["type"]) == "default"
}

func singBoxAction(outbound string) map[string]any {
	if outbound == "" {
		return map[string]any{"action": "reject"}
	}
	return map[string]any{"action": "route", "outbound": outbound}
}

type singBoxRuleSets struct {
	used    map[string]bool
	byURL   map[string]string
	added   []any
	detour  string
	present map[string]bool
}

func (s *singBoxRuleSets) tag(kind, name string) string {
	url := routingSingRuleSetBase + kind + "/" + name + ".srs"
	if tag, ok := s.byURL[url]; ok {
		return tag
	}
	tag := routingUniqueTag(s.used, "fk-"+kind+"-"+name)
	s.byURL[url] = tag
	ruleSet := map[string]any{"type": "remote", "tag": tag, "format": "binary", "url": url, "update_interval": "1d"}
	if s.detour != "" {
		ruleSet["http_client"] = map[string]any{"detour": s.detour}
	}
	s.added = append(s.added, ruleSet)
	return tag
}

func applySingBoxRouting(content string, set *routingSet, rules []compiledRule, warn func(string)) (string, int, error) {
	root, err := decodeOrderedJSON(content)
	if err != nil {
		return "", 0, fmt.Errorf("sing-box profile: %w", err)
	}
	route := anyMap(root["route"])
	if route == nil {
		route = map[string]any{}
	}
	targets := newSingBoxTargets(root)
	ruleSets := &singBoxRuleSets{used: map[string]bool{}, byURL: map[string]string{}, present: map[string]bool{}, detour: targets.proxy}
	for _, item := range anyList(route["rule_set"]) {
		definition := anyMap(item)
		tag := anyString(definition["tag"])
		ruleSets.used[tag] = true
		if url := anyString(definition["url"]); url != "" {
			ruleSets.byURL[url] = tag
		}
	}
	existing := anyList(route["rules"])
	leading := singBoxLeadingRules(existing)
	// Domain rules need the domain a sniff action finds (rule actions run in
	// order, so a sniff further down comes too late for the user rules);
	// one sniff without conditions in the leading rules covers every
	// connection.
	hasSniff := false
	for _, item := range existing[:leading] {
		if singBoxPlainSniff(anyMap(item)) {
			hasSniff = true
		}
	}
	var userRules []any
	needsSniff := false
	applied := 0
	for _, rule := range rules {
		outbound, err := targets.outbound(rule.target)
		if err != nil {
			warn(rule.label + ": " + err.Error() + ", skipped")
			continue
		}
		entry := map[string]any{}
		if !rule.all {
			destination := rule.destination()
			if destination.hasDomain() {
				needsSniff = true
			}
			putList(entry, "domain", destination.full)
			putList(entry, "domain_suffix", destination.suffix)
			putList(entry, "domain_keyword", destination.keyword)
			putList(entry, "domain_regex", destination.regex)
			putList(entry, "ip_cidr", destination.cidr)
			var sets []string
			for _, name := range destination.geosite {
				sets = append(sets, ruleSets.tag("geosite", name))
			}
			for _, name := range destination.geoip {
				if name == "private" {
					entry["ip_is_private"] = true
					continue
				}
				sets = append(sets, ruleSets.tag("geoip", name))
			}
			putList(entry, "rule_set", sets)
		}
		putList(entry, "package_name", rule.apps)
		if rule.network != "" {
			entry["network"] = []string{rule.network}
		}
		var ports []any
		var portRanges []string
		for _, port := range rule.ports {
			if port.from == port.to {
				ports = append(ports, int(port.from))
			} else {
				portRanges = append(portRanges, port.String(":"))
			}
		}
		if len(ports) > 0 {
			entry["port"] = ports
		}
		putList(entry, "port_range", portRanges)
		for key, value := range singBoxAction(outbound) {
			entry[key] = value
		}
		userRules = append(userRules, entry)
		applied++
	}

	head := append([]any(nil), existing[:leading]...)
	tail := existing[leading:]
	if needsSniff && !hasSniff {
		head = append([]any{map[string]any{"action": "sniff"}}, head...)
	}
	var finalRule []any
	if set.Final.Type != routingTargetConfig {
		outbound, err := targets.outbound(set.Final)
		if err != nil {
			warn("fallback: " + err.Error() + ", the profile's own fallback is kept")
		} else if outbound == "" {
			finalRule = append(finalRule, map[string]any{"action": "reject"})
		} else {
			route["final"] = outbound
		}
	}
	merged := append([]any{}, head...)
	merged = append(merged, userRules...)
	if set.Mode == routingModePrepend {
		merged = append(merged, tail...)
	}
	merged = append(merged, finalRule...)
	route["rules"] = merged
	if len(ruleSets.added) > 0 {
		route["rule_set"] = append(anyList(route["rule_set"]), ruleSets.added...)
		// Remote rule-sets are kept in the cache file between starts, so they
		// are downloaded once and then refreshed daily.
		experimental := anyMap(root["experimental"])
		if experimental == nil {
			experimental = map[string]any{}
		}
		if _, ok := experimental["cache_file"]; !ok {
			experimental["cache_file"] = map[string]any{"enabled": true}
			root["experimental"] = experimental
		}
	}
	if len(targets.addedOut) > 0 {
		root["outbounds"] = append(anyList(root["outbounds"]), targets.addedOut...)
	}
	root["route"] = route
	encoded, err := encodeRoutingJSON(root)
	if err != nil {
		return "", 0, err
	}
	return encoded, applied, nil
}

func putList(entry map[string]any, key string, values []string) {
	if len(values) > 0 {
		entry[key] = values
	}
}
