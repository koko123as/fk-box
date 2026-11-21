package fk

import (
	"encoding/json"
	"fmt"

	"go.yaml.in/yaml/v3"
)

// CoreProfileStats counts a profile's groups, outbounds and rules for the
// dashboard card, as JSON {"groups":n,"outbounds":n,"rules":n}. mihomo:
// proxy-groups / proxies (+ providers) / rules; Xray: routing.balancers /
// outbounds / routing.rules.
func CoreProfileStats(core string, content string) (string, error) {
	var groups, outbounds, rules int
	switch core {
	case CoreMihomo:
		var root struct {
			Proxies   []any          `yaml:"proxies"`
			Providers map[string]any `yaml:"proxy-providers"`
			Groups    []any          `yaml:"proxy-groups"`
			Rules     []any          `yaml:"rules"`
		}
		if err := yaml.Unmarshal([]byte(normalizeCoreContent(content)), &root); err != nil {
			return "", err
		}
		groups, outbounds, rules = len(root.Groups), len(root.Proxies)+len(root.Providers), len(root.Rules)
	case CoreXray:
		var root struct {
			Outbounds []json.RawMessage `json:"outbounds"`
			Routing   struct {
				Balancers []json.RawMessage `json:"balancers"`
				Rules     []json.RawMessage `json:"rules"`
			} `json:"routing"`
		}
		if err := json.Unmarshal([]byte(normalizeCoreContent(content)), &root); err != nil {
			return "", err
		}
		groups, outbounds, rules = len(root.Routing.Balancers), len(root.Outbounds), len(root.Routing.Rules)
	default:
		return "", fmt.Errorf("unsupported core: %s", core)
	}
	encoded, err := json.Marshal(map[string]int{"groups": groups, "outbounds": outbounds, "rules": rules})
	return string(encoded), err
}
