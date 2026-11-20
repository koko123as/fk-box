package libbox

// mihomo: user rules become rules lines in front of the profile's own rules
// (the profile's rule-providers, sub-rules and groups stay as they are).
// A rule with network, port or app conditions is written as an AND logic
// rule. Apps match with PROCESS-NAME: in the cmfa build mihomo asks
// process.DefaultPackageNameResolver for the package name, which libbox
// answers through the platform's connection-owner lookup (core_routing_owner.go),
// as ClashMetaForAndroid does. Downloaded rule lists are written as local
// rule-provider files in the mihomo home directory (domain, ipcidr and
// classical behaviors), so large lists use mihomo's own set matchers.

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"go.yaml.in/yaml/v3"
)

const mihomoRoutingDir = "fk-routing"


func yamlMapValue(mapping *yaml.Node, key string) *yaml.Node {
	if mapping == nil || mapping.Kind != yaml.MappingNode {
		return nil
	}
	for i := 0; i+1 < len(mapping.Content); i += 2 {
		if mapping.Content[i].Value == key {
			return mapping.Content[i+1]
		}
	}
	return nil
}

func yamlSetMapValue(mapping *yaml.Node, key string, value *yaml.Node) {
	for i := 0; i+1 < len(mapping.Content); i += 2 {
		if mapping.Content[i].Value == key {
			mapping.Content[i+1] = value
			return
		}
	}
	mapping.Content = append(mapping.Content, &yaml.Node{Kind: yaml.ScalarNode, Tag: "!!str", Value: key}, value)
}

func yamlNames(sequence *yaml.Node) []string {
	var names []string
	if sequence == nil || sequence.Kind != yaml.SequenceNode {
		return nil
	}
	for _, item := range sequence.Content {
		if name := yamlMapValue(item, "name"); name != nil && name.Value != "" {
			names = append(names, name.Value)
		}
	}
	return names
}

type mihomoTargets struct {
	names     map[string]bool
	proxy     string
	providers []string // proxy-providers: their nodes cannot be rule targets
}

func newMihomoTargets(root *yaml.Node, rules []string) *mihomoTargets {
	targets := &mihomoTargets{names: map[string]bool{}}
	for name := range mihomoBuiltinOutbounds {
		targets.names[name] = true
	}
	proxies := yamlNames(yamlMapValue(root, "proxies"))
	groups := yamlNames(yamlMapValue(root, "proxy-groups"))
	for _, name := range append(proxies, groups...) {
		targets.names[name] = true
	}
	if node := yamlMapValue(root, "proxy-providers"); node != nil && node.Kind == yaml.MappingNode {
		for i := 0; i+1 < len(node.Content); i += 2 {
			targets.providers = append(targets.providers, node.Content[i].Value)
		}
	}
	for i := len(rules) - 1; i >= 0; i-- {
		if target, ok := mihomoMatchTarget(rules[i]); ok && !mihomoBuiltinOutbounds[strings.ToUpper(target)] {
			targets.proxy = target
			break
		}
	}
	if targets.proxy == "" && len(groups) > 0 {
		targets.proxy = groups[0]
	}
	if targets.proxy == "" && len(proxies) > 0 {
		targets.proxy = proxies[0]
	}
	return targets
}

func mihomoMatchTarget(rule string) (string, bool) {
	parts := strings.Split(rule, ",")
	if len(parts) >= 2 && strings.EqualFold(strings.TrimSpace(parts[0]), "MATCH") {
		return strings.TrimSpace(parts[1]), true
	}
	return "", false
}

func (t *mihomoTargets) target(target routingTarget) (string, error) {
	switch target.Type {
	case routingTargetProxy:
		if t.proxy == "" {
			return "", fmt.Errorf("the profile has no proxy or proxy group")
		}
		return t.proxy, nil
	case routingTargetDirect:
		return "DIRECT", nil
	case routingTargetBlock:
		return "REJECT", nil
	case routingTargetNode:
		if !t.names[target.Tag] {
			if len(t.providers) > 0 {
				// mihomo resolves rule targets among proxies and proxy-groups
				// only (config.parseRules), not the nodes of a proxy-provider.
				return "", fmt.Errorf("%q is not a proxy or group of the profile (nodes of proxy-providers %s cannot be rule targets in mihomo; choose the group that contains it)", target.Tag, strings.Join(t.providers, ", "))
			}
			return "", fmt.Errorf("proxy or group %q is not in the profile", target.Tag)
		}
		return target.Tag, nil
	}
	return "", fmt.Errorf("unknown target %s", target.Type)
}

// mihomoRoutingTargets lists the names a mihomo rule can target: the
// profile's proxies and proxy-groups (not the nodes of its proxy-providers).
func mihomoRoutingTargets(content string) ([]string, error) {
	var document yaml.Node
	if err := yaml.Unmarshal([]byte(normalizeCoreContent(content)), &document); err != nil {
		return nil, err
	}
	if document.Kind != yaml.DocumentNode || len(document.Content) == 0 {
		return nil, fmt.Errorf("not a YAML mapping")
	}
	root := document.Content[0]
	return append(yamlNames(yamlMapValue(root, "proxy-groups")), yamlNames(yamlMapValue(root, "proxies"))...), nil
}

// mihomoPayloadSafe: a payload inside AND,((...)) must not contain the
// characters that delimit logic rules. mihomo's logic parser splits on
// parentheses (rules/logic/logic.go format) and then reads each part with
// ParseRulePayload, which keeps commas in DOMAIN-REGEX payloads.
func mihomoPayloadSafe(kind, payload string) bool {
	if kind == "DOMAIN-REGEX" {
		return !strings.ContainsAny(payload, "()")
	}
	return !strings.ContainsAny(payload, ",()")
}

type mihomoProviders struct {
	home    string
	added   map[string]map[string]any
	used    map[string]bool
	written map[string]bool
}

type mihomoRuleProvider struct{ name, behavior string }

// list writes a downloaded list as up to three local providers and returns
// their names and behaviors.
func (p *mihomoProviders) list(list *routingList) ([]mihomoRuleProvider, error) {
	type part struct {
		behavior string
		lines    []string
	}
	var domains, cidrs, classical []string
	for _, value := range list.match.suffix {
		domains = append(domains, "+."+value)
	}
	domains = append(domains, list.match.full...)
	cidrs = append(cidrs, list.match.cidr...)
	for _, value := range list.match.keyword {
		classical = append(classical, "DOMAIN-KEYWORD,"+value)
	}
	for _, value := range list.match.regex {
		classical = append(classical, "DOMAIN-REGEX,"+value)
	}
	var names []mihomoRuleProvider
	for _, item := range []part{{"domain", domains}, {"ipcidr", cidrs}, {"classical", classical}} {
		if len(item.lines) == 0 {
			continue
		}
		name := "fk-list-" + list.hash + "-" + item.behavior
		relative := "./" + mihomoRoutingDir + "/" + list.hash + "-" + item.behavior + ".txt"
		if !p.written[name] {
			path := filepath.Join(p.home, mihomoRoutingDir, list.hash+"-"+item.behavior+".txt")
			if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
				return nil, err
			}
			if err := os.WriteFile(path, []byte(strings.Join(item.lines, "\n")+"\n"), 0o644); err != nil {
				return nil, err
			}
			p.written[name] = true
		}
		if p.used[name] && p.added[name] == nil {
			return nil, fmt.Errorf("rule-provider %s already exists in the profile", name)
		}
		p.added[name] = map[string]any{"type": "file", "behavior": item.behavior, "format": "text", "path": relative}
		names = append(names, mihomoRuleProvider{name, item.behavior})
	}
	return names, nil
}

func mihomoLine(matcher string, conditions []string, target string, params string) string {
	if len(conditions) == 0 {
		if matcher == "" {
			return "MATCH," + target
		}
		return matcher + "," + target + params
	}
	parts := append([]string(nil), conditions...)
	if matcher != "" {
		// Inside a logic rule a sub-rule carries its own params
		// ("(IP-CIDR,1.2.3.0/24,no-resolve)"), read by ParseRulePayload.
		parts = append([]string{matcher + params}, parts...)
	}
	if len(parts) == 1 {
		return parts[0] + "," + target
	}
	return "AND,((" + strings.Join(parts, "),(") + "))," + target
}

func applyMihomoRouting(content string, set *routingSet, rules []compiledRule, warn func(string)) (string, int, error) {
	var document yaml.Node
	if err := yaml.Unmarshal([]byte(normalizeCoreContent(content)), &document); err != nil {
		return "", 0, fmt.Errorf("mihomo profile: %w", err)
	}
	if document.Kind != yaml.DocumentNode || len(document.Content) == 0 || document.Content[0].Kind != yaml.MappingNode {
		return "", 0, fmt.Errorf("mihomo profile: not a YAML mapping")
	}
	root := document.Content[0]
	var existing []string
	if node := yamlMapValue(root, "rules"); node != nil && node.Kind == yaml.SequenceNode {
		for _, item := range node.Content {
			existing = append(existing, item.Value)
		}
	}
	targets := newMihomoTargets(root, existing)
	providers := &mihomoProviders{home: GeoDataDir(), added: map[string]map[string]any{}, used: map[string]bool{}, written: map[string]bool{}}
	if node := yamlMapValue(root, "rule-providers"); node != nil && node.Kind == yaml.MappingNode {
		for i := 0; i+1 < len(node.Content); i += 2 {
			providers.used[node.Content[i].Value] = true
		}
	}
	if mode := yamlMapValue(root, "find-process-mode"); mode != nil && strings.EqualFold(strings.TrimSpace(mode.Value), "off") {
		for _, rule := range rules {
			if len(rule.apps) > 0 {
				warn("the profile sets find-process-mode: off, so mihomo never looks up apps and rules with apps (" + rule.label + ") match nothing; remove it or set it to strict")
				break
			}
		}
	}
	var userRules []string
	applied := 0
	for _, rule := range rules {
		target, err := targets.target(rule.target)
		if err != nil {
			warn(rule.label + ": " + err.Error() + ", skipped")
			continue
		}
		var conditions []string
		if rule.network != "" {
			conditions = append(conditions, "NETWORK,"+strings.ToUpper(rule.network))
		}
		if len(rule.ports) > 0 {
			var parts []string
			for _, port := range rule.ports {
				parts = append(parts, port.String("-"))
			}
			conditions = append(conditions, "DST-PORT,"+strings.Join(parts, "/"))
		}
		if len(rule.apps) == 1 {
			conditions = append(conditions, "PROCESS-NAME,"+rule.apps[0])
		} else if len(rule.apps) > 1 {
			conditions = append(conditions, "OR,((PROCESS-NAME,"+strings.Join(rule.apps, "),(PROCESS-NAME,")+"))")
		}
		var lines []string
		add := func(kind, payload, params string) {
			if len(conditions) > 0 && !mihomoPayloadSafe(kind, payload) {
				warn(rule.label + ": " + payload + " cannot be combined with network/port/app conditions in mihomo, entry skipped")
				return
			}
			lines = append(lines, mihomoLine(kind+","+payload, conditions, target, params))
		}
		if rule.all {
			lines = append(lines, mihomoLine("", conditions, target, ""))
		} else {
			match := &rule.match
			for _, value := range match.geosite {
				add("GEOSITE", value, "")
			}
			for _, value := range match.suffix {
				add("DOMAIN-SUFFIX", value, "")
			}
			for _, value := range match.full {
				add("DOMAIN", value, "")
			}
			for _, value := range match.keyword {
				add("DOMAIN-KEYWORD", value, "")
			}
			for _, value := range match.regex {
				add("DOMAIN-REGEX", value, "")
			}
			for _, value := range match.cidr {
				add("IP-CIDR", value, ",no-resolve")
			}
			for _, value := range match.geoip {
				if value == "private" {
					// mihomo's pseudo country for private and LAN addresses.
					value = "lan"
				}
				// no-resolve: an IP rule matches connections to IP addresses
				// (and fake-ip/sniffed hosts mihomo already resolved) without
				// a DNS lookup for every domain that reaches it.
				add("GEOIP", value, ",no-resolve")
			}
			if rule.list != nil {
				names, err := providers.list(rule.list)
				if err != nil {
					warn(rule.label + ": " + err.Error() + ", list skipped")
				}
				for _, provider := range names {
					params := ""
					if provider.behavior == "ipcidr" {
						params = ",no-resolve"
					}
					add("RULE-SET", provider.name, params)
				}
			}
		}
		if len(lines) == 0 {
			continue
		}
		userRules = append(userRules, lines...)
		applied++
	}
	var tail []string
	if set.Mode == routingModePrepend {
		tail = existing
	} else if set.Final.Type == routingTargetConfig {
		for _, line := range existing {
			if _, ok := mihomoMatchTarget(line); ok {
				tail = append(tail, line)
			}
		}
	}
	if set.Final.Type != routingTargetConfig {
		target, err := targets.target(set.Final)
		if err != nil {
			warn("fallback: " + err.Error() + ", the profile's own fallback is kept")
		} else {
			var kept []string
			for _, line := range tail {
				if _, ok := mihomoMatchTarget(line); !ok {
					kept = append(kept, line)
				}
			}
			tail = append(kept, "MATCH,"+target)
		}
	}
	merged := append(userRules, tail...)
	sequence := &yaml.Node{Kind: yaml.SequenceNode, Tag: "!!seq"}
	for _, line := range merged {
		sequence.Content = append(sequence.Content, &yaml.Node{Kind: yaml.ScalarNode, Tag: "!!str", Value: line})
	}
	yamlSetMapValue(root, "rules", sequence)
	if len(providers.added) > 0 {
		node := yamlMapValue(root, "rule-providers")
		if node == nil || node.Kind != yaml.MappingNode {
			node = &yaml.Node{Kind: yaml.MappingNode, Tag: "!!map"}
			yamlSetMapValue(root, "rule-providers", node)
		}
		for _, name := range routingSortedKeys(func() map[string]bool {
			keys := map[string]bool{}
			for name := range providers.added {
				keys[name] = true
			}
			return keys
		}()) {
			var value yaml.Node
			if err := value.Encode(providers.added[name]); err != nil {
				return "", 0, err
			}
			yamlSetMapValue(node, name, &value)
		}
	}
	encoded, err := yaml.Marshal(&document)
	if err != nil {
		return "", 0, err
	}
	return string(encoded), applied, nil
}
