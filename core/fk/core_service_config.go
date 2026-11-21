package fk

// The configuration a kernel child process runs (docs/kernel-isolation.md
// section 6): the profile with fk-box's TUN, control interface and local
// test/return inbound injected. The profile on disk is never changed.

import (
	"encoding/json"
	"fmt"
	"net/netip"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"go.yaml.in/yaml/v3"
)

const (
	xrayTunInboundTag   = "tun-in"
	xrayLocalInboundTag = "fkbox-local"
	localReturnUser     = "fkbox-return"
	// mihomo's local listener.
	mihomoLocalListener = "fkbox-local"
)

// xrayLayout is what the groups view shows for Xray: its outbounds and
// balancers (whose selectors are outbound tag prefixes).
type xrayLayout struct {
	outbounds []string
	protocols map[string]string
	balancers []xrayBalancer
	// testUsers maps an outbound or balancer tag to the user of the local
	// inbound whose traffic routing sends there.
	testUsers map[string]string
}

type xrayBalancer struct {
	tag      string
	strategy string
	members  []string
}

func writeKernelFile(dir, name string, data []byte) (string, error) {
	path := filepath.Join(dir, name)
	temp := path + ".tmp"
	if err := os.WriteFile(temp, data, 0o600); err != nil {
		return "", err
	}
	if err := os.Rename(temp, path); err != nil {
		_ = os.Remove(temp)
		return "", err
	}
	return path, nil
}

// writeXrayConfig writes running.content for Xray: the inbounds become the
// TUN inbound (descriptor 3) and the local inbound, DNS to the TUN's DNS
// address goes to a dns outbound, the API listens on xray-api.sock and the
// TUN inbound's traffic is counted.
func writeXrayConfig(running *serviceKernel) error {
	root, _, err := xrayStartRoot(running.content)
	if err != nil {
		return err
	}
	layout := readXrayLayout(root)
	running.layout = layout
	running.logLevel = xrayServiceLogLevel(root)

	// Local inbound: one user per outbound and balancer for delay tests,
	// and the return user, which no extra rule matches.
	accounts := []any{map[string]any{"user": localReturnUser, "pass": running.localPassword}}
	var testRules []any
	index := 0
	addUser := func(tag string, rule map[string]any) {
		index++
		user := "t" + strconv.Itoa(index)
		layout.testUsers[tag] = user
		accounts = append(accounts, map[string]any{"user": user, "pass": running.localPassword})
		rule["inboundTag"] = []string{xrayLocalInboundTag}
		rule["user"] = []string{user}
		testRules = append(testRules, rule)
	}
	for _, tag := range layout.outbounds {
		addUser(tag, map[string]any{"outboundTag": tag})
	}
	for _, balancer := range layout.balancers {
		addUser(balancer.tag, map[string]any{"balancerTag": balancer.tag})
	}
	inbounds, _ := root["inbounds"].([]any)
	root["inbounds"] = append(inbounds, map[string]any{
		"tag":      xrayLocalInboundTag,
		"protocol": "http",
		"listen":   "127.0.0.1",
		"port":     running.localPort,
		"settings": map[string]any{"accounts": accounts, "allowTransparent": false},
	})
	route, err := currentCaptureRoute()
	if err != nil {
		return err
	}
	if route != nil {
		if err = xrayCaptureRoute(root, route); err != nil {
			return err
		}
	}
	routing, _ := root["routing"].(map[string]any)
	rules, _ := routing["rules"].([]any)
	routing["rules"] = append(testRules, rules...)

	root["api"] = map[string]any{
		"tag":      "fkbox-api",
		"listen":   filepath.Join(running.dir, "xray-api.sock"),
		"services": []string{"StatsService", "RoutingService"},
	}
	root["stats"] = map[string]any{}
	policy, _ := root["policy"].(map[string]any)
	if policy == nil {
		policy = map[string]any{}
	}
	system, _ := policy["system"].(map[string]any)
	if system == nil {
		system = map[string]any{}
	}
	system["statsInboundUplink"] = true
	system["statsInboundDownlink"] = true
	policy["system"] = system
	root["policy"] = policy

	encoded, err := json.Marshal(root)
	if err != nil {
		return err
	}
	running.config, err = writeKernelFile(running.dir, "fkbox-run.json", encoded)
	return err
}

// xrayStartRoot builds the configuration Xray is started with from the
// profile: its inbounds are replaced by the TUN inbound and DNS sent to the
// TUN's DNS address goes to a dns outbound.
func xrayStartRoot(content string) (map[string]any, map[string]any, error) {
	var root map[string]any
	if err := json.Unmarshal([]byte(normalizeCoreContent(content)), &root); err != nil {
		return nil, nil, err
	}
	root["inbounds"] = []any{map[string]any{
		"tag":      xrayTunInboundTag,
		"protocol": "tun",
		"settings": map[string]any{"name": "xray0", "MTU": serviceTunMTU},
		"sniffing": map[string]any{"enabled": true, "destOverride": []string{"http", "tls", "quic"}},
	}}
	outbounds, _ := root["outbounds"].([]any)
	hasDNS := false
	for _, item := range outbounds {
		if outbound, ok := item.(map[string]any); ok && outbound["tag"] == "dns-out" {
			hasDNS = true
		}
	}
	if !hasDNS {
		root["outbounds"] = append(outbounds, map[string]any{"tag": "dns-out", "protocol": "dns"})
	}
	routing, _ := root["routing"].(map[string]any)
	if routing == nil {
		routing = map[string]any{}
	}
	rules, _ := routing["rules"].([]any)
	routing["rules"] = append([]any{map[string]any{
		"inboundTag": []string{xrayTunInboundTag}, "port": "53", "outboundTag": "dns-out",
	}}, rules...)
	root["routing"] = routing
	logConfig := xrayLogConfigWithDefault(root)
	// Console output goes to the service log; files are not writable here.
	xrayApplyAccessLog(logConfig)
	delete(logConfig, "error")
	root["log"] = logConfig
	return root, logConfig, nil
}

// readXrayLayout reads the outbound tags and balancers of root.
func readXrayLayout(root map[string]any) *xrayLayout {
	layout := &xrayLayout{protocols: map[string]string{}, testUsers: map[string]string{}}
	outbounds, _ := root["outbounds"].([]any)
	for _, item := range outbounds {
		outbound, _ := item.(map[string]any)
		tag, _ := outbound["tag"].(string)
		protocol, _ := outbound["protocol"].(string)
		if tag == "" {
			continue
		}
		layout.protocols[tag] = protocol
		if protocol != "dns" && protocol != "blackhole" && protocol != "freedom" && protocol != "loopback" {
			layout.outbounds = append(layout.outbounds, tag)
		}
	}
	routing, _ := root["routing"].(map[string]any)
	balancers, _ := routing["balancers"].([]any)
	for _, item := range balancers {
		balancer, _ := item.(map[string]any)
		tag, _ := balancer["tag"].(string)
		if tag == "" {
			continue
		}
		strategy := "random"
		if config, ok := balancer["strategy"].(map[string]any); ok {
			if kind, ok := config["type"].(string); ok && kind != "" {
				strategy = kind
			}
		}
		selectors, _ := balancer["selector"].([]any)
		var members []string
		for _, outboundTag := range layout.outbounds {
			for _, selector := range selectors {
				if prefix, ok := selector.(string); ok && strings.HasPrefix(outboundTag, prefix) {
					members = append(members, outboundTag)
					break
				}
			}
		}
		layout.balancers = append(layout.balancers, xrayBalancer{tag: tag, strategy: strategy, members: members})
	}
	return layout
}

// writeMihomoConfig writes running.content for mihomo and returns the TUN
// address: the TUN takes descriptor 3 with the routing left to the VPN, the
// controller listens on mihomo.sock only, the local listener is added, and
// the system name servers (which mihomo's linux build cannot read on
// Android) become the current network's.
func writeMihomoConfig(running *serviceKernel) (netip.Prefix, error) {
	var document yaml.Node
	if err := yaml.Unmarshal([]byte(normalizeCoreContent(running.content)), &document); err != nil {
		return netip.Prefix{}, err
	}
	if document.Kind != yaml.DocumentNode || len(document.Content) == 0 || document.Content[0].Kind != yaml.MappingNode {
		return netip.Prefix{}, fmt.Errorf("mihomo: the profile is not a mapping")
	}
	root := document.Content[0]
	if !mihomoSetsLogLevel(running.content) {
		yamlSetMapValue(root, "log-level", yamlScalar("warning"))
	}
	running.logLevel = mihomoServiceLogLevel(yamlString(yamlMapValue(root, "log-level")))
	for _, key := range []string{"external-controller", "external-controller-tls", "external-controller-pipe", "external-ui", "interface-name"} {
		yamlDeleteKey(root, key)
	}
	for _, key := range []string{"redir-port", "tproxy-port", "routing-mark"} {
		yamlDeleteKey(root, key)
	}
	yamlSetMapValue(root, "external-controller-unix", yamlScalar(filepath.Join(running.dir, "mihomo.sock")))

	dns := yamlMapValue(root, "dns")
	fakeIP := mihomoDefaultFakeIP
	if dns != nil && dns.Kind == yaml.MappingNode {
		if text := yamlString(yamlMapValue(dns, "fake-ip-range")); text != "" {
			if prefix, err := netip.ParsePrefix(text); err == nil && prefix.Addr().Is4() {
				fakeIP = prefix
			}
		}
	}
	inet4 := netip.PrefixFrom(fakeIP.Masked().Addr().Next(), 30)
	servers := currentDNS()
	if dns == nil || dns.Kind != yaml.MappingNode {
		dns = &yaml.Node{Kind: yaml.MappingNode}
		yamlSetMapValue(root, "dns", dns)
	}
	// The TUN's DNS hijack needs mihomo's resolver.
	yamlSetMapValue(dns, "enable", yamlScalar("true"))
	if len(servers) > 0 {
		replaceMihomoSystemDNS(dns, servers)
		if yamlMapValue(dns, "default-nameserver") == nil {
			yamlSetMapValue(dns, "default-nameserver", yamlSequence(servers))
		}
		if seq := yamlMapValue(dns, "nameserver"); seq == nil || len(seq.Content) == 0 {
			yamlSetMapValue(dns, "nameserver", yamlSequence(servers))
		}
	}

	tun := yamlMapValue(root, "tun")
	if tun == nil || tun.Kind != yaml.MappingNode {
		tun = &yaml.Node{Kind: yaml.MappingNode}
		yamlSetMapValue(root, "tun", tun)
	}
	yamlSetMapValue(tun, "enable", yamlScalar("true"))
	yamlSetMapValue(tun, "file-descriptor", yamlScalar("3"))
	// The app is outside its own VPN (decision D1): only gVisor sees the
	// TUN's traffic then (P0 results, decision D3).
	yamlSetMapValue(tun, "stack", yamlScalar("gvisor"))
	yamlSetMapValue(tun, "mtu", yamlScalar(strconv.Itoa(serviceTunMTU)))
	for _, key := range []string{"auto-route", "auto-redirect", "auto-detect-interface"} {
		yamlSetMapValue(tun, key, yamlScalar("false"))
	}
	for _, key := range []string{"device", "inet4-address", "inet6-address", "route-address", "include-package", "exclude-package"} {
		yamlDeleteKey(tun, key)
	}
	if hijack := yamlMapValue(tun, "dns-hijack"); hijack == nil || len(hijack.Content) == 0 {
		yamlSetMapValue(tun, "dns-hijack", yamlSequence([]string{"any:53"}))
	}

	listeners := yamlMapValue(root, "listeners")
	if listeners == nil || listeners.Kind != yaml.SequenceNode {
		listeners = &yaml.Node{Kind: yaml.SequenceNode}
		yamlSetMapValue(root, "listeners", listeners)
	}
	local := &yaml.Node{Kind: yaml.MappingNode}
	yamlSetMapValue(local, "name", yamlScalar(mihomoLocalListener))
	yamlSetMapValue(local, "type", yamlScalar("mixed"))
	yamlSetMapValue(local, "listen", yamlScalar("127.0.0.1"))
	yamlSetMapValue(local, "port", yamlScalar(strconv.Itoa(running.localPort)))
	users := &yaml.Node{Kind: yaml.SequenceNode}
	user := &yaml.Node{Kind: yaml.MappingNode}
	yamlSetMapValue(user, "username", yamlScalar(localReturnUser))
	yamlSetMapValue(user, "password", yamlScalar(running.localPassword))
	users.Content = append(users.Content, user)
	yamlSetMapValue(local, "users", users)
	listeners.Content = append(listeners.Content, local)

	route, err := currentCaptureRoute()
	if err != nil {
		return netip.Prefix{}, err
	}
	if route != nil {
		if err = mihomoCaptureRoute(root, route); err != nil {
			return netip.Prefix{}, err
		}
	}

	encoded, err := yaml.Marshal(&document)
	if err != nil {
		return netip.Prefix{}, err
	}
	running.config, err = writeKernelFile(running.dir, "fkbox-run.yaml", encoded)
	return inet4, err
}

// replaceMihomoSystemDNS swaps "system", "system://" and "dhcp://" name
// servers for servers in every name server list of dns.
func replaceMihomoSystemDNS(dns *yaml.Node, servers []string) {
	for _, key := range []string{"default-nameserver", "nameserver", "fallback", "proxy-server-nameserver", "direct-nameserver"} {
		list := yamlMapValue(dns, key)
		if list == nil || list.Kind != yaml.SequenceNode {
			continue
		}
		var content []*yaml.Node
		replaced := false
		for _, item := range list.Content {
			value := strings.ToLower(strings.TrimSpace(item.Value))
			if value == "system" || strings.HasPrefix(value, "system://") || strings.HasPrefix(value, "dhcp://") {
				if !replaced {
					content = append(content, yamlSequence(servers).Content...)
					replaced = true
				}
				continue
			}
			content = append(content, item)
		}
		list.Content = content
	}
}

func yamlScalar(value string) *yaml.Node {
	return &yaml.Node{Kind: yaml.ScalarNode, Value: value}
}

func yamlSequence(values []string) *yaml.Node {
	node := &yaml.Node{Kind: yaml.SequenceNode}
	for _, value := range values {
		node.Content = append(node.Content, yamlScalar(value))
	}
	return node
}

func yamlString(node *yaml.Node) string {
	if node == nil || node.Kind != yaml.ScalarNode {
		return ""
	}
	return node.Value
}

func yamlDeleteKey(mapping *yaml.Node, key string) {
	for i := 0; i+1 < len(mapping.Content); i += 2 {
		if mapping.Content[i].Value == key {
			mapping.Content = append(mapping.Content[:i], mapping.Content[i+2:]...)
			return
		}
	}
}
