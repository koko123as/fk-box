package fk

// The route to the capture proxy in each kernel's configuration: TCP to the
// captured ports leaves through a SOCKS5 outbound to the proxy, ahead of the
// profile's own rules. The proxy's upstream comes back through the kernel's
// local inbound, which these rules leave out, so a connection is captured once.

import (
	"errors"
	"fmt"
	"strconv"
	"strings"

	"go.yaml.in/yaml/v3"
)

const captureBlockTag = "fkbox-capture-block"

var errCapturePackages = errors.New("capturing selected apps needs a sing-box profile: Xray and mihomo cannot match apps without root")

func capturePorts(route *captureRoute) []any {
	ports := make([]any, 0, len(route.scope.Ports))
	for _, port := range route.scope.Ports {
		ports = append(ports, int(port))
	}
	return ports
}

// singBoxCaptureRoute adds the capture outbound and rules to a sing-box profile.
func singBoxCaptureRoute(root map[string]any, route *captureRoute) {
	root["outbounds"] = append(anyList(root["outbounds"]), map[string]any{
		"type": "socks", "tag": captureOutboundTag, "server": "127.0.0.1", "server_port": route.port,
		"version": "5", "username": localReturnUser, "password": route.password,
	})
	notLocal := map[string]any{"inbound": []any{singBoxLocalTag}, "invert": true}
	match := func(network string, ports []any) map[string]any {
		rule := map[string]any{"network": []any{network}, "port": ports}
		if len(route.scope.Packages) > 0 {
			rule["package_name"] = route.scope.Packages
		}
		return rule
	}
	rules := []any{map[string]any{
		"type": "logical", "mode": "and",
		"rules":    []any{notLocal, match("tcp", capturePorts(route))},
		"outbound": captureOutboundTag,
	}}
	if route.scope.BlockQUIC {
		rules = append(rules, map[string]any{
			"type": "logical", "mode": "and",
			"rules":  []any{notLocal, match("udp", []any{443})},
			"action": "reject",
		})
	}
	routing := anyMap(root["route"])
	if routing == nil {
		routing = map[string]any{}
		root["route"] = routing
	}
	routing["rules"] = append(rules, anyList(routing["rules"])...)
}

// xrayCaptureRoute adds the capture outbound and rules to an Xray configuration.
func xrayCaptureRoute(root map[string]any, route *captureRoute) error {
	if len(route.scope.Packages) > 0 {
		return errCapturePackages
	}
	outbounds := append(anyList(root["outbounds"]), map[string]any{
		"protocol": "socks", "tag": captureOutboundTag,
		"settings": map[string]any{"address": "127.0.0.1", "port": route.port, "user": localReturnUser, "pass": route.password},
	})
	ports := make([]string, 0, len(route.scope.Ports))
	for _, port := range route.scope.Ports {
		ports = append(ports, strconv.Itoa(int(port)))
	}
	rules := []any{map[string]any{
		"type": "field", "inboundTag": []any{xrayTunInboundTag}, "network": "tcp",
		"port": strings.Join(ports, ","), "outboundTag": captureOutboundTag,
	}}
	if route.scope.BlockQUIC {
		outbounds = append(outbounds, map[string]any{"protocol": "blackhole", "tag": captureBlockTag})
		rules = append(rules, map[string]any{
			"type": "field", "inboundTag": []any{xrayTunInboundTag}, "network": "udp", "port": "443", "outboundTag": captureBlockTag,
		})
	}
	root["outbounds"] = outbounds
	routing := anyMap(root["routing"])
	if routing == nil {
		routing = map[string]any{}
		root["routing"] = routing
	}
	routing["rules"] = append(rules, anyList(routing["rules"])...)
	return nil
}

// mihomoCaptureRoute adds the capture proxy and rules to a mihomo configuration.
func mihomoCaptureRoute(root *yaml.Node, route *captureRoute) error {
	if len(route.scope.Packages) > 0 {
		return errCapturePackages
	}
	proxies := yamlMapValue(root, "proxies")
	if proxies == nil || proxies.Kind != yaml.SequenceNode {
		proxies = &yaml.Node{Kind: yaml.SequenceNode}
		yamlSetMapValue(root, "proxies", proxies)
	}
	proxy := &yaml.Node{Kind: yaml.MappingNode}
	for _, pair := range [][2]string{
		{"name", captureOutboundTag}, {"type", "socks5"}, {"server", "127.0.0.1"},
		{"port", strconv.Itoa(route.port)}, {"username", localReturnUser}, {"password", route.password},
	} {
		yamlSetMapValue(proxy, pair[0], yamlScalar(pair[1]))
	}
	proxies.Content = append(proxies.Content, proxy)

	notLocal := fmt.Sprintf("(NOT,((IN-NAME,%s)))", mihomoLocalListener)
	var added []*yaml.Node
	for _, port := range route.scope.Ports {
		added = append(added, yamlScalar(fmt.Sprintf("AND,((NETWORK,TCP),(DST-PORT,%d),%s),%s", port, notLocal, captureOutboundTag)))
	}
	if route.scope.BlockQUIC {
		added = append(added, yamlScalar(fmt.Sprintf("AND,((NETWORK,UDP),(DST-PORT,443),%s),REJECT", notLocal)))
	}
	rules := yamlMapValue(root, "rules")
	if rules == nil || rules.Kind != yaml.SequenceNode {
		rules = &yaml.Node{Kind: yaml.SequenceNode}
		yamlSetMapValue(root, "rules", rules)
	}
	rules.Content = append(added, rules.Content...)
	return nil
}
