package fk

// LAN sharing changes sing-box's native proxy inbound listen addresses.
// There is no Mihomo allow-lan flag or single global mixed-port.
//
// fk-core reads and edits the profile as generic JSON with sing-box's field
// names (inbounds[].type, tag, listen, listen_port, bind_interface, netns,
// users, tls.enabled) instead of sing-box's option types; every other byte of
// the profile is kept as it was.
import (
	"bytes"
	"encoding/json"
	"fmt"
	"math"
	"net"
	"net/netip"
	"slices"
	"strings"
)

type uiLANAddress struct {
	Address   string `json:"address"`
	Interface string `json:"interface"`
}

type uiLANListener struct {
	Type           string   `json:"type"`
	Tag            string   `json:"tag"`
	Listen         string   `json:"listen"`
	Port           uint16   `json:"port"`
	BindInterface  string   `json:"bindInterface"`
	LAN            bool     `json:"lan"`
	TLS            bool     `json:"tls"`
	Authentication bool     `json:"authentication"`
	Endpoints      []string `json:"endpoints"`
}

func uiProxyInbound(inboundType string) bool {
	return inboundType == "mixed" || inboundType == "http" || inboundType == "socks"
}

// uiLANInbound is one inbound of a sing-box profile: its members as raw JSON
// and the listen fields read from them.
type uiLANInbound struct {
	raw           json.RawMessage // nil for an inbound fk-box adds
	members       map[string]json.RawMessage
	kind          string
	tag           string
	hasListen     bool // a listen field that is not null
	listen        netip.Addr
	hasPort       bool // listen options: sing-box's ListenOptions apply
	port          uint16
	bindInterface string
	netns         string
	tls           bool
	users         bool
}

// uiLANMember finds a member the way encoding/json matches a struct field:
// the exact name first, then any case-insensitive match.
func uiLANMember(members map[string]json.RawMessage, name string) (string, json.RawMessage, bool) {
	if value, ok := members[name]; ok {
		return name, value, true
	}
	for key, value := range members {
		if strings.EqualFold(key, name) {
			return key, value, true
		}
	}
	return "", nil, false
}

func uiLANIsNull(value json.RawMessage) bool {
	return bytes.Equal(bytes.TrimSpace(value), []byte("null"))
}

// uiLANParseInbounds reads a sing-box profile's inbounds. The profile must
// be a JSON object; listen and listen_port are checked as sing-box decodes
// them (an IP address, a port that fits in 16 bits).
func uiLANParseInbounds(content string) (map[string]json.RawMessage, []*uiLANInbound, error) {
	if core := DetectCoreConfig(content); core == CoreXray || core == CoreMihomo {
		return nil, nil, fmt.Errorf("decode config: not a sing-box profile (%s)", core)
	}
	cleaned, err := RemoveUIRetiredCaptureOptions(content)
	if err != nil {
		return nil, nil, fmt.Errorf("remove retired capture options: %w", err)
	}
	root, err := unmarshalExtendedRaw([]byte(cleaned))
	if err != nil {
		return nil, nil, fmt.Errorf("decode config: %w", err)
	}
	if root == nil {
		root = map[string]json.RawMessage{}
	}
	_, raw, ok := uiLANMember(root, "inbounds")
	if !ok || uiLANIsNull(raw) {
		return root, nil, nil
	}
	var items []json.RawMessage
	if err = json.Unmarshal(raw, &items); err != nil {
		return nil, nil, fmt.Errorf("decode config: inbounds: %w", err)
	}
	inbounds := make([]*uiLANInbound, 0, len(items))
	for index, item := range items {
		inbound := &uiLANInbound{raw: bytes.TrimSpace(item)}
		if err = json.Unmarshal(item, &inbound.members); err != nil || inbound.members == nil {
			return nil, nil, fmt.Errorf("decode config: inbounds[%d]: not an object", index)
		}
		if err = inbound.read(); err != nil {
			return nil, nil, fmt.Errorf("decode config: inbounds[%d]: %w", index, err)
		}
		inbounds = append(inbounds, inbound)
	}
	return root, inbounds, nil
}

func (i *uiLANInbound) read() error {
	text := func(name string) (string, error) {
		_, raw, ok := uiLANMember(i.members, name)
		if !ok || uiLANIsNull(raw) {
			return "", nil
		}
		var value string
		if err := json.Unmarshal(raw, &value); err != nil {
			return "", fmt.Errorf("%s: %w", name, err)
		}
		return value, nil
	}
	var err error
	if i.kind, err = text("type"); err != nil {
		return err
	}
	if i.tag, err = text("tag"); err != nil {
		return err
	}
	if i.bindInterface, err = text("bind_interface"); err != nil {
		return err
	}
	if i.netns, err = text("netns"); err != nil {
		return err
	}
	if _, raw, ok := uiLANMember(i.members, "listen"); ok && !uiLANIsNull(raw) {
		var value string
		if err = json.Unmarshal(raw, &value); err != nil {
			return fmt.Errorf("listen: %w", err)
		}
		if i.listen, err = netip.ParseAddr(value); err != nil {
			return fmt.Errorf("listen: %w", err)
		}
		i.hasListen = true
	}
	if _, raw, ok := uiLANMember(i.members, "listen_port"); ok {
		i.hasPort = true
		if !uiLANIsNull(raw) {
			var value float64
			if err = json.Unmarshal(raw, &value); err != nil {
				return fmt.Errorf("listen_port: %w", err)
			}
			if value < 0 || value > math.MaxUint16 || value != math.Trunc(value) {
				return fmt.Errorf("listen_port: %v is not a port", value)
			}
			i.port = uint16(value)
		}
	}
	if !i.hasPort && (i.hasListen || uiProxyInbound(i.kind)) {
		// A mixed, http or socks inbound, or any with a listen address,
		// has sing-box's listen options even with the port left out.
		i.hasPort = true
	}
	if _, raw, ok := uiLANMember(i.members, "users"); ok && !uiLANIsNull(raw) {
		var users []json.RawMessage
		if err = json.Unmarshal(raw, &users); err != nil {
			return fmt.Errorf("users: %w", err)
		}
		i.users = len(users) > 0
	}
	if _, raw, ok := uiLANMember(i.members, "tls"); ok && !uiLANIsNull(raw) {
		var tls struct {
			Enabled bool `json:"enabled"`
		}
		if err = json.Unmarshal(raw, &tls); err != nil {
			return fmt.Errorf("tls: %w", err)
		}
		i.tls = tls.Enabled
	}
	return nil
}

// listenAddress is sing-box's Listen.Build: an omitted listen is loopback
// (common/listener/listener_tcp.go).
func (i *uiLANInbound) listenAddress() netip.Addr {
	if !i.hasListen {
		return netip.MustParseAddr("127.0.0.1")
	}
	return i.listen.Unmap()
}

// ReadUILANProxyListeners expands wildcard binds to device addresses, while
// respecting explicit addresses, address families and bind_interface.
// Port zero is assigned by the OS and cannot be advertised from configuration.
func ReadUILANProxyListeners(configContent, interfaceAddresses string) (*StringBox, error) {
	// Xray and mihomo profiles have no sing-box inbounds to list or toggle.
	if core := DetectCoreConfig(configContent); core == CoreXray || core == CoreMihomo {
		return wrapString("[]"), nil
	}
	_, inbounds, err := uiLANParseInbounds(configContent)
	if err != nil {
		return nil, err
	}
	var addresses []uiLANAddress
	if err = json.Unmarshal([]byte(interfaceAddresses), &addresses); err != nil {
		return nil, err
	}
	listeners := make([]uiLANListener, 0)
	for _, inbound := range inbounds {
		if !uiProxyInbound(inbound.kind) {
			continue
		}
		listen := inbound.listenAddress()
		item := uiLANListener{
			Type: inbound.kind, Tag: inbound.tag, Listen: listen.String(),
			Port: inbound.port, BindInterface: inbound.bindInterface,
			LAN:       !listen.IsLoopback() && (listen.IsUnspecified() || listen.IsGlobalUnicast() || listen.IsLinkLocalUnicast()),
			Endpoints: make([]string, 0),
		}
		switch inbound.kind {
		case "mixed", "http":
			item.TLS = inbound.tls
			item.Authentication = inbound.users
		case "socks":
			item.Authentication = inbound.users
		}
		if item.LAN && item.Port != 0 && inbound.netns == "" {
			for _, device := range addresses {
				if item.BindInterface != "" && item.BindInterface != device.Interface {
					continue
				}
				address, parseErr := netip.ParseAddr(device.Address)
				if parseErr != nil {
					continue
				}
				address = address.Unmap()
				if !address.IsGlobalUnicast() {
					continue
				}
				if listen.IsUnspecified() {
					// sing-box uses net.Listen("tcp"); :: accepts IPv4 on Android's
					// dual-stack socket, whereas 0.0.0.0 accepts only IPv4.
					if listen.Is4() && !address.Is4() {
						continue
					}
				} else if listen != address {
					continue
				}
				endpoint := net.JoinHostPort(address.String(), fmt.Sprint(item.Port))
				if !slices.Contains(item.Endpoints, endpoint) {
					item.Endpoints = append(item.Endpoints, endpoint)
				}
			}
		}
		listeners = append(listeners, item)
	}
	encoded, err := json.Marshal(listeners)
	if err != nil {
		return nil, err
	}
	return wrapString(string(encoded)), nil
}

// SetUILANProxyEnabled preserves ports, tags, authentication, TLS and routing.
// An explicit LAN bind is retained when enabling; disabling restricts proxy
// inbounds to loopback. initialPort is used only when no proxy inbound exists.
// The result is the profile with only those listen fields changed (and the
// new inbound appended), indented with two spaces.
func SetUILANProxyEnabled(configContent string, enabled bool, initialPort int32) (*StringBox, error) {
	root, inbounds, err := uiLANParseInbounds(configContent)
	if err != nil {
		return nil, err
	}
	found := false
	changed := false
	for _, inbound := range inbounds {
		if !uiProxyInbound(inbound.kind) {
			continue
		}
		found = true
		address := inbound.listenAddress()
		if enabled && !address.IsLoopback() || !enabled && address.IsLoopback() {
			continue
		}
		var next netip.Addr
		if enabled {
			if address.Is4() {
				next = netip.IPv4Unspecified()
			} else {
				next = netip.IPv6Unspecified()
			}
		} else if address.Is4() {
			next = netip.MustParseAddr("127.0.0.1")
		} else {
			next = netip.IPv6Loopback()
		}
		key, _, ok := uiLANMember(inbound.members, "listen")
		if !ok {
			key = "listen"
		}
		encoded, _ := json.Marshal(next.String())
		inbound.members[key] = encoded
		inbound.hasListen, inbound.listen = true, next
		changed = true
	}
	if enabled && !found {
		if initialPort < 1 || initialPort > 65535 {
			return nil, fmt.Errorf("choose a proxy port between 1 and 65535")
		}
		tags := make(map[string]bool)
		for _, inbound := range inbounds {
			tags[inbound.tag] = true
			if inbound.hasPort && inbound.port == uint16(initialPort) {
				return nil, fmt.Errorf("port %d is already used by this profile", initialPort)
			}
		}
		tag := "b-box-lan"
		for suffix := 2; tags[tag]; suffix++ {
			tag = fmt.Sprintf("b-box-lan-%d", suffix)
		}
		encodedTag, _ := json.Marshal(tag)
		inbounds = append(inbounds, &uiLANInbound{
			kind: "mixed", tag: tag, hasListen: true, listen: netip.IPv4Unspecified(), hasPort: true, port: uint16(initialPort),
			members: map[string]json.RawMessage{
				"type": json.RawMessage(`"mixed"`), "tag": encodedTag,
				"listen": json.RawMessage(`"0.0.0.0"`), "listen_port": json.RawMessage(fmt.Sprint(initialPort)),
			},
		})
		changed = true
	}
	if !changed {
		cleaned, err := RemoveUIRetiredCaptureOptions(configContent)
		if err != nil {
			return nil, err
		}
		return wrapString(uiLANIndent(cleaned)), nil
	}
	encoded, err := uiLANEncode(configContent, root, inbounds)
	if err != nil {
		return nil, err
	}
	return wrapString(encoded), nil
}

// uiLANEncode writes the profile back: the original members in their order
// with inbounds replaced, each inbound's members in their order with a
// changed listen in place (or, when the inbound had none, at its end).
func uiLANEncode(original string, root map[string]json.RawMessage, inbounds []*uiLANInbound) (string, error) {
	var list bytes.Buffer
	list.WriteByte('[')
	for index, inbound := range inbounds {
		if index > 0 {
			list.WriteByte(',')
		}
		encoded, err := uiLANEncodeInbound(inbound)
		if err != nil {
			return "", err
		}
		list.Write(encoded)
	}
	list.WriteByte(']')
	cleaned, err := RemoveUIRetiredCaptureOptions(original)
	if err != nil {
		return "", err
	}
	inboundsKey, _, hasInbounds := uiLANMember(root, "inbounds")
	result, err := rewriteJSONObject([]byte(strings.TrimSpace(strings.TrimPrefix(cleaned, "\ufeff"))), func(name string, value json.RawMessage) (json.RawMessage, bool, error) {
		if hasInbounds && name == inboundsKey {
			return list.Bytes(), true, nil
		}
		return value, true, nil
	})
	if err != nil {
		return "", err
	}
	if !hasInbounds {
		result = appendJSONMember(result, `"inbounds":`+list.String())
	}
	var out bytes.Buffer
	if err = json.Indent(&out, result, "", "  "); err != nil {
		return "", err
	}
	out.WriteByte('\n')
	return out.String(), nil
}

func uiLANEncodeInbound(inbound *uiLANInbound) ([]byte, error) {
	if inbound.raw == nil {
		// A new inbound: sing-box's field order.
		var out bytes.Buffer
		out.WriteByte('{')
		for index, key := range []string{"type", "tag", "listen", "listen_port"} {
			if index > 0 {
				out.WriteByte(',')
			}
			fmt.Fprintf(&out, "%q:%s", key, inbound.members[key])
		}
		out.WriteByte('}')
		return out.Bytes(), nil
	}
	listenKey, listenValue, _ := uiLANMember(inbound.members, "listen")
	hadListen := false
	result, err := rewriteJSONObject(inbound.raw, func(name string, value json.RawMessage) (json.RawMessage, bool, error) {
		if name == listenKey {
			hadListen = true
			return listenValue, true, nil
		}
		return value, true, nil
	})
	if err != nil {
		return nil, err
	}
	if !hadListen && listenValue != nil {
		result = appendJSONMember(result, `"listen":`+string(listenValue))
	}
	return result, nil
}

// uiLANIndent is content indented with two spaces, or content itself when it
// is not JSON.
func uiLANIndent(content string) string {
	var out bytes.Buffer
	if json.Indent(&out, []byte(strings.TrimSpace(strings.TrimPrefix(content, "\ufeff"))), "", "  ") != nil {
		return content
	}
	out.WriteByte('\n')
	return out.String()
}
