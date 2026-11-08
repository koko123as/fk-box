package libbox

// FlClash's provider.sideLoad, ruleSet.dump, ruleSet.compile and (for
// mihomo) dns.clearCache, ported from FlClash v0.9.3 core/provider.go,
// core/rule_set.go and core/connection.go (be84736, GPL-3.0) onto upstream
// mihomo. FlClash's Go core keeps its home in mihomo's constant.Path; FK-box's
// mihomo home is the core asset directory, so the rule-set cache directory is
// passed in from the host (FlClash's home, where lib/models/common.dart's
// ClashProviderExt looks for it).

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/netip"
	"os"
	"path/filepath"
	"slices"
	"strings"

	mihomoProviderAdapter "github.com/metacubex/mihomo/adapter/provider"
	"github.com/metacubex/mihomo/common/utils"
	mihomoYAML "github.com/metacubex/mihomo/common/yaml"
	"github.com/metacubex/mihomo/component/resolver"
	"github.com/metacubex/mihomo/component/resource"
	cp "github.com/metacubex/mihomo/constant/provider"
	rp "github.com/metacubex/mihomo/rules/provider"
)

var (
	errMihomoRuleSetEmpty   = errors.New("rule set has no usable entries")
	errMihomoRuleSetMixed   = errors.New("rule set mixes domains and IP ranges")
	errMihomoRuleSetInvalid = errors.New("rule set is not a readable mrs file")
)

var mihomoZstdMagic = []byte{0x28, 0xB5, 0x2F, 0xFD}

// SideLoadMihomoProvider replaces an external provider's content with data,
// as FlClash's provider.sideLoad does (mihomo's Fetcher.SideUpdate).
func SideLoadMihomoProvider(name string, data string) error {
	provider, ok := mihomoExternalProviders()[name]
	if !ok {
		return fmt.Errorf("provider %s is not loaded", name)
	}
	var err error
	switch typed := provider.(type) {
	case *mihomoProviderAdapter.ProxySetProvider:
		_, _, err = typed.SideUpdate([]byte(data))
	case *rp.RuleSetProvider:
		_, _, err = typed.SideUpdate([]byte(data))
	default:
		return fmt.Errorf("provider %s can not be side-loaded", name)
	}
	return err
}

// DumpMihomoRuleSet returns the text of a binary (mrs) rule set, as FlClash's
// ruleSet.dump does.
func DumpMihomoRuleSet(path string) (string, error) {
	buf, err := os.ReadFile(path)
	if err != nil {
		return "", err
	}
	var errs []error
	for _, behavior := range []cp.RuleBehavior{cp.Domain, cp.IPCIDR} {
		var text strings.Builder
		err := rp.ConvertToMrs(buf, behavior, cp.MrsRule, &text)
		if err == nil {
			return text.String(), nil
		}
		errs = append(errs, err)
	}
	return "", errors.Join(errs...)
}

type mihomoRuleSetInfo struct {
	Behavior string `json:"behavior"`
	Format   string `json:"format"`
}

// mihomoRuleSetCachePath is a name, never a path; the directory and the .mrs
// beside the source match ClashProviderExt in FlClash's lib/models/common.dart.
func mihomoRuleSetCachePath(homeDir string, name string) (string, bool) {
	if homeDir == "" || name == "" || name[0] == '.' {
		return "", false
	}
	for _, r := range name {
		if !(r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z' || r >= '0' && r <= '9' || r == '.' || r == '-' || r == '_') {
			return "", false
		}
	}
	return filepath.Join(homeDir, "providers", "rules", name), true
}

// CompileMihomoRuleSet downloads (when url is set) or reads the rule set
// cached under homeDir, classifies it and writes the compiled .mrs beside it,
// as FlClash's ruleSet.compile does; it returns the RuleSetInfo JSON.
func CompileMihomoRuleSet(homeDir string, name string, url string) (string, error) {
	source, ok := mihomoRuleSetCachePath(homeDir, name)
	if !ok {
		return "", errors.New("invalid rule set name")
	}
	var buf []byte
	var err error
	if url != "" {
		buf, _, err = resource.NewHTTPVehicle(url, source, "", nil, resource.DefaultHttpTimeout, 0).
			Read(context.Background(), utils.HashType{})
	} else {
		buf, err = os.ReadFile(source)
	}
	if err != nil {
		return "", err
	}
	info, mrs, err := compileMihomoRuleSet(buf, url == "")
	if err != nil {
		return "", err
	}
	if url != "" {
		if err := writeMihomoRuleSetFile(source, buf); err != nil {
			return "", err
		}
	}
	if mrs != nil {
		if err := writeMihomoRuleSetFile(source+".mrs", mrs); err != nil {
			return "", err
		}
	}
	encoded, err := json.Marshal(info)
	return string(encoded), err
}

// ClearMihomoDNSCache empties mihomo's resolver caches (FlClash's
// dns.clearCache); harmless when mihomo is not running.
func ClearMihomoDNSCache() {
	resolver.ClearCache()
}

// ClearDNSCache is FlClash's dns.clearCache for the running sing-box
// instance: its router's network reset drops the DNS cache (and the process
// and HTTP client caches that go with it).
func (s *CommandServer) ClearDNSCache() {
	instance := s.Instance()
	if instance == nil || instance.Box() == nil {
		return
	}
	instance.Box().Router().ResetNetwork()
}

func writeMihomoRuleSetFile(path string, buf []byte) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	temp := path + ".tmp"
	_ = os.Remove(temp)
	file, err := os.OpenFile(temp, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o644)
	if err != nil {
		return err
	}
	_, err = file.Write(buf)
	if closeErr := file.Close(); err == nil {
		err = closeErr
	}
	if err != nil {
		_ = os.Remove(temp)
		return err
	}
	if err := os.Rename(temp, path); err != nil {
		_ = os.Remove(temp)
		return err
	}
	return nil
}

func compileMihomoRuleSet(buf []byte, allowEmpty bool) (*mihomoRuleSetInfo, []byte, error) {
	if bytes.HasPrefix(buf, mihomoZstdMagic) {
		for _, behavior := range []cp.RuleBehavior{cp.Domain, cp.IPCIDR} {
			if rp.ConvertToMrs(buf, behavior, cp.MrsRule, io.Discard) == nil {
				return mihomoRuleSetInfoOf(cp.MrsRule, behavior), nil, nil
			}
		}
		return nil, nil, errMihomoRuleSetInvalid
	}
	format, entries := mihomoRuleSetEntries(buf)
	if len(entries) == 0 && allowEmpty {
		return mihomoRuleSetInfoOf(format, cp.Classical), nil, nil
	}
	behavior, keys, err := classifyMihomoRuleSet(entries)
	if err != nil {
		return nil, nil, err
	}
	info := mihomoRuleSetInfoOf(format, behavior)
	if behavior == cp.Classical {
		return info, nil, nil
	}
	var mrs bytes.Buffer
	if err := rp.ConvertToMrs([]byte(strings.Join(keys, "\n")), behavior, cp.TextRule, &mrs); err != nil {
		return nil, nil, errMihomoRuleSetEmpty
	}
	return info, mrs.Bytes(), nil
}

func mihomoRuleSetInfoOf(format cp.RuleFormat, behavior cp.RuleBehavior) *mihomoRuleSetInfo {
	formats := map[cp.RuleFormat]string{cp.YamlRule: "yaml", cp.TextRule: "text", cp.MrsRule: "mrs"}
	return &mihomoRuleSetInfo{Behavior: strings.ToLower(behavior.String()), Format: formats[format]}
}

func mihomoRuleSetEntries(buf []byte) (cp.RuleFormat, []string) {
	var schema struct {
		Payload *[]string `yaml:"payload"`
		Rules   *[]string `yaml:"rules"`
	}
	if mihomoYAML.Unmarshal(buf, &schema) == nil && (schema.Payload != nil || schema.Rules != nil) {
		var entries []string
		for _, list := range []*[]string{schema.Payload, schema.Rules} {
			if list == nil {
				continue
			}
			for _, entry := range *list {
				if entry = strings.TrimSpace(entry); entry != "" {
					entries = append(entries, entry)
				}
			}
		}
		return cp.YamlRule, entries
	}
	var entries []string
	for _, line := range strings.Split(string(buf), "\n") {
		line = strings.TrimSpace(line)
		if line == "" || line[0] == '#' || strings.HasPrefix(line, "//") {
			continue
		}
		entries = append(entries, line)
	}
	return cp.TextRule, entries
}

func classifyMihomoRuleSet(entries []string) (cp.RuleBehavior, []string, error) {
	if len(entries) == 0 {
		return cp.Classical, nil, errMihomoRuleSetEmpty
	}
	if slices.ContainsFunc(entries, func(entry string) bool { return strings.Contains(entry, ",") }) {
		behavior, keys := classifyMihomoClassical(entries)
		return behavior, keys, nil
	}
	cidrs := make([]string, 0, len(entries))
	for _, entry := range entries {
		if cidr, ok := parseMihomoCIDR(entry); ok {
			cidrs = append(cidrs, cidr)
		}
	}
	switch len(cidrs) {
	case 0:
		return cp.Domain, entries, nil
	case len(entries):
		return cp.IPCIDR, cidrs, nil
	}
	return cp.Classical, nil, errMihomoRuleSetMixed
}

func classifyMihomoClassical(entries []string) (cp.RuleBehavior, []string) {
	domains := make([]string, 0, len(entries))
	cidrs := make([]string, 0, len(entries))
	for _, entry := range entries {
		ruleType, value, ok := strings.Cut(entry, ",")
		if !ok || strings.Contains(value, ",") {
			return cp.Classical, nil
		}
		value = strings.TrimSpace(value)
		switch strings.ToUpper(strings.TrimSpace(ruleType)) {
		case "DOMAIN":
			if !plainMihomoDomain(value) {
				return cp.Classical, nil
			}
			domains = append(domains, value)
		case "DOMAIN-SUFFIX":
			if !plainMihomoDomain(value) {
				return cp.Classical, nil
			}
			domains = append(domains, "+."+value)
		case "IP-CIDR", "IP-CIDR6":
			if _, err := netip.ParsePrefix(value); err != nil {
				return cp.Classical, nil
			}
			cidrs = append(cidrs, value)
		default:
			return cp.Classical, nil
		}
	}
	switch {
	case len(cidrs) == 0:
		return cp.Domain, domains
	case len(domains) == 0:
		return cp.IPCIDR, cidrs
	}
	return cp.Classical, nil
}

// A domain set reads a leading dot, "+" and "*" as wildcards.
func plainMihomoDomain(value string) bool {
	return value != "" && value[0] != '.' && !strings.ContainsAny(value, "+*/")
}

func parseMihomoCIDR(entry string) (string, bool) {
	if _, err := netip.ParsePrefix(entry); err == nil {
		return entry, true
	}
	if addr, err := netip.ParseAddr(entry); err == nil {
		addr = addr.WithZone("")
		return netip.PrefixFrom(addr, addr.BitLen()).String(), true
	}
	return "", false
}
