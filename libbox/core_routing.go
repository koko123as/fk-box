package libbox

// Per-core routing rules (Settings › Routing rules).
//
// The app keeps one rule set per core and hands the set of the core that
// actually runs to ApplyCoreRouting, which compiles it into that core's own
// routing syntax and returns the content the core is started with. The profile
// on disk is never changed.
//
// The data model follows the design of Lemonochka/keqdroid (TrafficRoute /
// RoutingProfile: content, target, apps, network, ports, ordered; user rules
// before the provider's own rules); no code is taken from it. The compiled
// output follows each core's documented rule syntax:
//   - sing-box: route.rules with rule actions and remote binary rule-sets
//     (option/rule.go, option/rule_set.go; rule-sets from the sing branch of
//     MetaCubeX/meta-rules-dat, as sing-box's own docs and FlClash use them)
//   - Xray: routing.rules (infra/conf/router.go) with geosite:/geoip: read from
//     the shared geo directory
//   - mihomo: rules / rule-providers (rules/parser.go, config/config.go)

import (
	"bufio"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/netip"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	xrayConf "github.com/xtls/xray-core/infra/conf"
)

const (
	routingModePrepend  = "prepend"
	routingModeOverride = "override"
	routingModeOff      = "off"

	routingTargetConfig = "config"
	routingTargetProxy  = "proxy"
	routingTargetDirect = "direct"
	routingTargetBlock  = "block"
	routingTargetNode   = "node"

	routingContentAll    = "all"
	routingContentMatch  = "match"
	routingContentPreset = "preset"
	routingContentList   = "list"

	// Remote sing-box rule-sets: MetaCubeX/meta-rules-dat, sing branch (.srs).
	routingSingRuleSetBase = "https://raw.githubusercontent.com/MetaCubeX/meta-rules-dat/sing/geo/"

	routingListMaxBytes   = 16 << 20
	routingListMaxEntries = 300000
	routingListFresh      = 24 * time.Hour
	routingListTimeout    = 15 * time.Second
	// All lists of one start download together, a few at a time, within
	// routingListTotalTimeout; a list that misses it keeps its cached copy.
	routingListTotalTimeout = 20 * time.Second
	routingListParallel     = 4
)

// CoreRoutingResult is the content a core starts with after the routing rules
// were applied, the number of user rules that made it in, and one warning per
// line for rules that were skipped or adjusted.
type CoreRoutingResult struct {
	Content  string
	Applied  int32
	Warnings string
	// Active reports whether anything was changed (rules, final or override).
	Active bool
	// Error, set only by StartOrReloadCoreServiceWithRouting, is why the
	// profile runs without its routing rules (they could not be compiled, or
	// the core did not start with them); Fallback is set in the second case.
	Error    string
	Fallback bool
}

type routingTarget struct {
	Type string `json:"type"`
	Tag  string `json:"tag,omitempty"`
}

type routingRule struct {
	ID      string        `json:"id,omitempty"`
	Enabled bool          `json:"enabled"`
	Name    string        `json:"name,omitempty"`
	Content string        `json:"content"`
	Values  []string      `json:"values,omitempty"`
	Preset  string        `json:"preset,omitempty"`
	URL     string        `json:"url,omitempty"`
	Target  routingTarget `json:"target"`
	Apps    []string      `json:"apps,omitempty"`
	Network string        `json:"network,omitempty"`
	Ports   string        `json:"ports,omitempty"`
}

type routingSet struct {
	Mode  string        `json:"mode"`
	Final routingTarget `json:"final"`
	Rules []routingRule `json:"rules"`
}

// routingPresets: what each preset matches. The UI adds the preset with its
// default target, which the user may change.
var routingPresets = map[string][]string{
	// 绕过大陆
	"cn-direct": {"geosite:cn", "geoip:cn", "geosite:private", "geoip:private"},
	// GFW 列表走代理（界面另加一条“全部流量 → 直连”）
	"gfw-proxy": {"geosite:gfw"},
	// 广告拦截
	"ads-block": {"geosite:category-ads-all"},
	// 局域网直连
	"lan-direct": {"geosite:private", "geoip:private"},
}

// routingMatch is the destination part of a rule: any of these matches.
type routingMatch struct {
	suffix  []string
	full    []string
	keyword []string
	regex   []string
	geosite []string
	cidr    []string
	geoip   []string
}

func (m *routingMatch) empty() bool {
	return len(m.suffix)+len(m.full)+len(m.keyword)+len(m.regex)+len(m.geosite)+len(m.cidr)+len(m.geoip) == 0
}

func (m *routingMatch) hasDomain() bool {
	return len(m.suffix)+len(m.full)+len(m.keyword)+len(m.regex)+len(m.geosite) > 0
}

func (m *routingMatch) hasIP() bool {
	return len(m.cidr)+len(m.geoip) > 0
}

type portRange struct{ from, to uint16 }

// compiledRule is a user rule after its content was expanded.
type compiledRule struct {
	label   string
	all     bool
	match   routingMatch
	list    *routingList
	target  routingTarget
	apps    []string
	network string
	ports   []portRange
}

var (
	routingGeoNamePattern = regexp.MustCompile(`^[A-Za-z0-9!@._-]{1,64}$`)
	routingDomainPattern  = regexp.MustCompile(`^[A-Za-z0-9_*-]+(\.[A-Za-z0-9_*-]+)*\.?$`)
	routingPackagePattern = regexp.MustCompile(`^[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)*$`)
)

// addValue sorts one entry of a "domains / IPs / geo" rule into the match.
func (m *routingMatch) addValue(value string) error {
	value = strings.TrimSpace(value)
	if value == "" {
		return nil
	}
	lower := strings.ToLower(value)
	prefixed := func(prefix string) (string, bool) {
		if strings.HasPrefix(lower, prefix) {
			return strings.TrimSpace(value[len(prefix):]), true
		}
		return "", false
	}
	if name, ok := prefixed("geosite:"); ok {
		if !routingGeoNamePattern.MatchString(name) {
			return fmt.Errorf("invalid geosite name %q", name)
		}
		m.geosite = append(m.geosite, strings.ToLower(name))
		return nil
	}
	if name, ok := prefixed("geoip:"); ok {
		if !routingGeoNamePattern.MatchString(name) {
			return fmt.Errorf("invalid geoip name %q", name)
		}
		m.geoip = append(m.geoip, strings.ToLower(name))
		return nil
	}
	if rest, ok := prefixed("regexp:"); ok {
		if _, err := regexp.Compile(rest); err != nil || rest == "" {
			return fmt.Errorf("invalid regular expression %q", rest)
		}
		m.regex = append(m.regex, rest)
		return nil
	}
	if rest, ok := prefixed("keyword:"); ok {
		if rest == "" || strings.ContainsAny(rest, " ,()") {
			return fmt.Errorf("invalid keyword %q", rest)
		}
		m.keyword = append(m.keyword, strings.ToLower(rest))
		return nil
	}
	if rest, ok := prefixed("full:"); ok {
		return m.addDomain(rest, true)
	}
	if rest, ok := prefixed("domain:"); ok {
		return m.addDomain(rest, false)
	}
	if prefix, err := netip.ParsePrefix(value); err == nil {
		m.cidr = append(m.cidr, prefix.Masked().String())
		return nil
	}
	if addr, err := netip.ParseAddr(value); err == nil {
		m.cidr = append(m.cidr, netip.PrefixFrom(addr, addr.BitLen()).String())
		return nil
	}
	return m.addDomain(value, false)
}

func (m *routingMatch) addDomain(value string, full bool) error {
	value = strings.ToLower(strings.TrimSpace(value))
	for _, prefix := range []string{"+.", "*.", "."} {
		if strings.HasPrefix(value, prefix) {
			value = value[len(prefix):]
			full = false
			break
		}
	}
	value = strings.TrimSuffix(value, ".")
	if value == "" || !routingDomainPattern.MatchString(value) || strings.Contains(value, "*") {
		return fmt.Errorf("invalid domain %q", value)
	}
	if full {
		m.full = append(m.full, value)
	} else {
		m.suffix = append(m.suffix, value)
	}
	return nil
}

func parseRoutingPorts(text string) ([]portRange, error) {
	var ports []portRange
	for _, part := range strings.FieldsFunc(text, func(r rune) bool { return r == ',' || r == ' ' || r == '/' }) {
		from, to, isRange := strings.Cut(part, "-")
		if !isRange {
			to = from
		}
		low, err1 := strconv.Atoi(strings.TrimSpace(from))
		high, err2 := strconv.Atoi(strings.TrimSpace(to))
		if err1 != nil || err2 != nil || low < 1 || high > 65535 || low > high {
			return nil, fmt.Errorf("invalid port %q", part)
		}
		ports = append(ports, portRange{uint16(low), uint16(high)})
	}
	return ports, nil
}

func (p portRange) String(separator string) string {
	if p.from == p.to {
		return strconv.Itoa(int(p.from))
	}
	return strconv.Itoa(int(p.from)) + separator + strconv.Itoa(int(p.to))
}

func routingRuleLabel(index int, rule routingRule) string {
	if rule.Name != "" {
		return fmt.Sprintf("#%d %s", index+1, rule.Name)
	}
	return fmt.Sprintf("#%d", index+1)
}

// compileRules expands presets and lists and validates every enabled rule.
// A rule that cannot be used is skipped with a warning, not fatal.
func compileRules(set *routingSet, lists routingListLoader, warn func(string)) []compiledRule {
	if prefetcher, ok := lists.(routingListPrefetcher); ok {
		var urls []string
		for _, rule := range set.Rules {
			if rule.Enabled && rule.Content == routingContentList {
				urls = append(urls, rule.URL)
			}
		}
		if len(urls) > 1 {
			prefetcher.prefetch(urls)
		}
	}
	var compiled []compiledRule
	for index, rule := range set.Rules {
		if !rule.Enabled {
			continue
		}
		label := routingRuleLabel(index, rule)
		item := compiledRule{label: label, target: rule.Target}
		switch rule.Target.Type {
		case routingTargetProxy, routingTargetDirect, routingTargetBlock:
		case routingTargetNode:
			if strings.TrimSpace(rule.Target.Tag) == "" {
				warn(label + ": no node or group chosen, skipped")
				continue
			}
		default:
			warn(label + ": unknown target " + rule.Target.Type + ", skipped")
			continue
		}
		var err error
		switch rule.Content {
		case routingContentAll:
			item.all = true
		case routingContentPreset:
			values, ok := routingPresets[rule.Preset]
			if !ok {
				warn(label + ": unknown preset " + rule.Preset + ", skipped")
				continue
			}
			for _, value := range values {
				err = errors.Join(err, item.match.addValue(value))
			}
		case routingContentMatch:
			for _, value := range rule.Values {
				if valueErr := item.match.addValue(value); valueErr != nil {
					warn(label + ": " + valueErr.Error() + ", entry skipped")
				}
			}
			if item.match.empty() {
				warn(label + ": no usable entries, skipped")
				continue
			}
		case routingContentList:
			list, listErr := lists.load(rule.URL)
			if listErr != nil {
				warn(label + ": rule list " + rule.URL + ": " + listErr.Error() + ", skipped")
				continue
			}
			if list.stale {
				warn(label + ": rule list " + rule.URL + " could not be refreshed, using the copy from " + list.fetched.Format("2006-01-02 15:04"))
			}
			if list.match.empty() {
				warn(label + ": rule list " + rule.URL + " has no usable entries, skipped")
				continue
			}
			item.list = list
		default:
			warn(label + ": unknown content " + rule.Content + ", skipped")
			continue
		}
		if err != nil {
			warn(label + ": " + err.Error() + ", skipped")
			continue
		}
		for _, app := range rule.Apps {
			app = strings.TrimSpace(app)
			if app == "" {
				continue
			}
			if !routingPackagePattern.MatchString(app) {
				warn(label + ": invalid package name " + app + ", entry skipped")
				continue
			}
			item.apps = append(item.apps, app)
		}
		switch strings.ToLower(rule.Network) {
		case "", "any":
		case "tcp", "udp":
			item.network = strings.ToLower(rule.Network)
		default:
			warn(label + ": unknown network " + rule.Network + ", skipped")
			continue
		}
		if strings.TrimSpace(rule.Ports) != "" {
			ports, portErr := parseRoutingPorts(rule.Ports)
			if portErr != nil {
				warn(label + ": " + portErr.Error() + ", skipped")
				continue
			}
			item.ports = ports
		}
		compiled = append(compiled, item)
	}
	return compiled
}

// destination returns the full destination match of a rule (inline values and
// a downloaded list together).
func (r *compiledRule) destination() *routingMatch {
	if r.list == nil {
		return &r.match
	}
	merged := r.match
	merged.suffix = append(append([]string(nil), merged.suffix...), r.list.match.suffix...)
	merged.full = append(append([]string(nil), merged.full...), r.list.match.full...)
	merged.keyword = append(append([]string(nil), merged.keyword...), r.list.match.keyword...)
	merged.regex = append(append([]string(nil), merged.regex...), r.list.match.regex...)
	merged.cidr = append(append([]string(nil), merged.cidr...), r.list.match.cidr...)
	return &merged
}

func parseRoutingSet(text string) (*routingSet, error) {
	set := &routingSet{Mode: routingModePrepend, Final: routingTarget{Type: routingTargetConfig}}
	if strings.TrimSpace(text) == "" {
		set.Mode = routingModeOff
		return set, nil
	}
	if err := json.Unmarshal([]byte(text), set); err != nil {
		return nil, fmt.Errorf("routing rules: %w", err)
	}
	switch set.Mode {
	case "":
		set.Mode = routingModePrepend
	case routingModePrepend, routingModeOverride, routingModeOff:
	default:
		return nil, fmt.Errorf("routing rules: unknown mode %s", set.Mode)
	}
	switch set.Final.Type {
	case "":
		set.Final.Type = routingTargetConfig
	case routingTargetConfig, routingTargetProxy, routingTargetDirect, routingTargetBlock:
	case routingTargetNode:
		if strings.TrimSpace(set.Final.Tag) == "" {
			return nil, fmt.Errorf("routing rules: the fallback node is not set")
		}
	default:
		return nil, fmt.Errorf("routing rules: unknown fallback %s", set.Final.Type)
	}
	return set, nil
}

// ApplyCoreRouting compiles the routing rules of one core (the JSON the app
// keeps for it) into content for that core. Mode "off", or no enabled rule
// with the config's own fallback, returns the content unchanged.
func ApplyCoreRouting(core string, content string, rules string) (*CoreRoutingResult, error) {
	return applyCoreRouting(core, content, rules, defaultRoutingLists())
}

func applyCoreRouting(core string, content string, rules string, lists routingListLoader) (*CoreRoutingResult, error) {
	set, err := parseRoutingSet(rules)
	if err != nil {
		return nil, err
	}
	result := &CoreRoutingResult{Content: content}
	if set.Mode == routingModeOff {
		return result, nil
	}
	var warnings []string
	warn := func(message string) { warnings = append(warnings, message) }
	compiled := compileRules(set, lists, warn)
	if len(compiled) == 0 && set.Final.Type == routingTargetConfig && set.Mode == routingModePrepend {
		result.Warnings = strings.Join(warnings, "\n")
		return result, nil
	}
	var applied int
	switch core {
	case CoreSingBox:
		result.Content, applied, err = applySingBoxRouting(content, set, compiled, warn)
	case CoreXray:
		result.Content, applied, err = applyXrayRouting(content, set, compiled, warn)
		if err == nil {
			// Checks Xray makes only when its router starts.
			var config *xrayConf.Config
			if config, err = decodeXrayConfig(result.Content); err == nil && config.RouterConfig != nil {
				err = checkXrayRuleTags(config.RouterConfig)
			}
		}
	case CoreMihomo:
		result.Content, applied, err = applyMihomoRouting(content, set, compiled, warn)
	default:
		return nil, fmt.Errorf("routing rules: unknown core %s", core)
	}
	if err != nil {
		return nil, fmt.Errorf("routing rules: %w", err)
	}
	result.Applied = int32(applied)
	result.Active = true
	result.Warnings = strings.Join(warnings, "\n")
	return result, nil
}

// ---------------------------------------------------------------------------
// Rule lists by URL (AdBlock, hosts, plain domain/CIDR lists, Clash classical
// and domain text). Downloaded by libbox, cached under the working directory
// and refreshed once a day; a list that cannot be refreshed keeps its last
// copy.
// ---------------------------------------------------------------------------

type routingList struct {
	url     string
	hash    string
	match   routingMatch
	fetched time.Time
	stale   bool
}

type routingListLoader interface {
	load(url string) (*routingList, error)
}

// routingListPrefetcher is a loader that can download several lists at once
// before compileRules reads them one by one.
type routingListPrefetcher interface {
	prefetch(urls []string)
}

type routingListCache struct {
	dir    string
	client *http.Client
	now    func() time.Time
	// total bounds the downloads of one prefetch together.
	total  time.Duration
	access sync.Mutex
	loaded map[string]*routingList
	failed map[string]error
}

func defaultRoutingLists() *routingListCache {
	base := sWorkingPath
	if base == "" {
		base = os.TempDir()
	}
	return &routingListCache{
		dir:    filepath.Join(base, "routing", "lists"),
		client: &http.Client{Timeout: routingListTimeout},
		now:    time.Now,
		total:  routingListTotalTimeout,
		loaded: map[string]*routingList{},
		failed: map[string]error{},
	}
}

// prefetch loads the lists in parallel (routingListParallel at a time, all of
// them within c.total), so a start with several lists waits for the slowest
// one instead of their sum. Results, failures included, are kept for load.
func (c *routingListCache) prefetch(urls []string) {
	ctx, cancel := context.WithTimeout(context.Background(), c.total)
	defer cancel()
	seen := map[string]bool{}
	slots := make(chan struct{}, routingListParallel)
	var group sync.WaitGroup
	for _, url := range urls {
		url = strings.TrimSpace(url)
		if seen[url] {
			continue
		}
		seen[url] = true
		group.Add(1)
		go func() {
			defer group.Done()
			select {
			case slots <- struct{}{}:
				defer func() { <-slots }()
			case <-ctx.Done():
			}
			_, _ = c.loadContext(ctx, url)
		}()
	}
	group.Wait()
}

func routingListHash(url string) string {
	sum := sha256.Sum256([]byte(url))
	return hex.EncodeToString(sum[:8])
}

func (c *routingListCache) load(url string) (*routingList, error) {
	ctx, cancel := context.WithTimeout(context.Background(), c.total)
	defer cancel()
	return c.loadContext(ctx, url)
}

func (c *routingListCache) loadContext(ctx context.Context, url string) (*routingList, error) {
	url = strings.TrimSpace(url)
	if !strings.HasPrefix(url, "https://") && !strings.HasPrefix(url, "http://") {
		return nil, fmt.Errorf("only http(s) URLs are supported")
	}
	c.access.Lock()
	list, ok := c.loaded[url]
	failure := c.failed[url]
	c.access.Unlock()
	if ok {
		return list, nil
	}
	if failure != nil {
		return nil, failure
	}
	list, err := c.read(ctx, url)
	c.access.Lock()
	defer c.access.Unlock()
	if err != nil {
		c.failed[url] = err
		return nil, err
	}
	c.loaded[url] = list
	return list, nil
}

func (c *routingListCache) read(ctx context.Context, url string) (*routingList, error) {
	lower := strings.ToLower(strings.SplitN(url, "?", 2)[0])
	if strings.HasSuffix(lower, ".srs") || strings.HasSuffix(lower, ".mrs") || strings.HasSuffix(lower, ".dat") || strings.HasSuffix(lower, ".mmdb") {
		return nil, fmt.Errorf("binary rule-set formats are not supported; use a text list (AdBlock, hosts, domains/CIDR or Clash rules)")
	}
	hash := routingListHash(url)
	path := filepath.Join(c.dir, hash+".txt")
	info, statErr := os.Stat(path)
	var body []byte
	var fetched time.Time
	stale := false
	if statErr == nil && c.now().Sub(info.ModTime()) < routingListFresh {
		cached, err := os.ReadFile(path)
		if err == nil {
			body, fetched = cached, info.ModTime()
		}
	}
	if body == nil {
		downloaded, err := c.fetch(ctx, url)
		if err == nil {
			body, fetched = downloaded, c.now()
			if mkErr := os.MkdirAll(c.dir, 0o755); mkErr == nil {
				temp := path + ".tmp"
				if os.WriteFile(temp, downloaded, 0o644) == nil {
					_ = os.Rename(temp, path)
				}
			}
		} else if statErr == nil {
			cached, readErr := os.ReadFile(path)
			if readErr != nil {
				return nil, err
			}
			body, fetched, stale = cached, info.ModTime(), true
		} else {
			return nil, err
		}
	}
	list := &routingList{url: url, hash: hash, fetched: fetched, stale: stale}
	parseRoutingListText(string(body), &list.match)
	return list, nil
}

func (c *routingListCache) fetch(ctx context.Context, url string) ([]byte, error) {
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil, err
	}
	request.Header.Set("User-Agent", "FK-box")
	response, err := c.client.Do(request)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("HTTP %d", response.StatusCode)
	}
	body, err := io.ReadAll(io.LimitReader(response.Body, routingListMaxBytes+1))
	if err != nil {
		return nil, err
	}
	if len(body) > routingListMaxBytes {
		return nil, fmt.Errorf("larger than %d MiB", routingListMaxBytes>>20)
	}
	return body, nil
}

var routingHostsAddresses = map[string]bool{"0.0.0.0": true, "127.0.0.1": true, "::": true, "::1": true}

var routingHostsSkip = map[string]bool{"localhost": true, "localhost.localdomain": true, "local": true, "broadcasthost": true, "ip6-localhost": true, "ip6-loopback": true, "0.0.0.0": true}

// parseRoutingListText reads the common text list formats into a match:
//   - AdBlock: ||example.com^ (rules with $options, exceptions and cosmetic
//     filters are ignored)
//   - hosts: 0.0.0.0 example.com
//   - Clash classical: DOMAIN-SUFFIX,example.com / DOMAIN / DOMAIN-KEYWORD /
//     DOMAIN-REGEX / IP-CIDR / IP-CIDR6 (also inside a YAML payload list)
//   - Clash domain text: +.example.com, .example.com, example.com
//   - v2fly domain lists: full:, domain:, keyword:, regexp: (attributes such
//     as "@cn" ignored, include: skipped)
//   - plain IP / CIDR lines
func parseRoutingListText(text string, match *routingMatch) {
	scanner := bufio.NewScanner(strings.NewReader(text))
	scanner.Buffer(make([]byte, 64<<10), 1<<20)
	seen := map[string]bool{}
	count := 0
	add := func(kind, value string) {
		key := kind + "\x00" + value
		if seen[key] || count >= routingListMaxEntries {
			return
		}
		seen[key] = true
		count++
		switch kind {
		case "suffix":
			match.suffix = append(match.suffix, value)
		case "full":
			match.full = append(match.full, value)
		case "keyword":
			match.keyword = append(match.keyword, value)
		case "regex":
			match.regex = append(match.regex, value)
		case "cidr":
			match.cidr = append(match.cidr, value)
		}
	}
	domain := func(value string, full bool) {
		var single routingMatch
		if single.addDomain(value, full) != nil {
			return
		}
		if len(single.full) > 0 {
			add("full", single.full[0])
		} else {
			add("suffix", single.suffix[0])
		}
	}
	ip := func(value string) bool {
		if prefix, err := netip.ParsePrefix(value); err == nil {
			add("cidr", prefix.Masked().String())
			return true
		}
		if addr, err := netip.ParseAddr(value); err == nil {
			add("cidr", netip.PrefixFrom(addr, addr.BitLen()).String())
			return true
		}
		return false
	}
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "!") || strings.HasPrefix(line, "#") || strings.HasPrefix(line, "[") ||
			strings.HasPrefix(line, "//") || strings.HasPrefix(line, "@@") || strings.HasPrefix(line, "payload:") {
			continue
		}
		if index := strings.Index(line, " #"); index >= 0 {
			line = strings.TrimSpace(line[:index])
		}
		// YAML payload item: - 'DOMAIN-SUFFIX,example.com'
		if strings.HasPrefix(line, "- ") {
			line = strings.Trim(strings.TrimSpace(line[2:]), `'"`)
		}
		// v2fly attributes ("example.com @ads") qualify an entry; they do
		// not change what it matches.
		if index := strings.Index(line, " @"); index > 0 && !strings.Contains(line, ",") {
			line = strings.TrimSpace(line[:index])
		}
		// v2fly domain-list-community: full: / domain: / keyword: / regexp:
		// lines, attributes after " @" dropped; include: needs the other
		// list files and is skipped.
		if kind, value, ok := routingV2flyLine(line); ok {
			switch kind {
			case "full":
				domain(value, true)
			case "domain":
				domain(value, false)
			case "keyword":
				if value != "" && !strings.ContainsAny(value, " ,()") {
					add("keyword", strings.ToLower(value))
				}
			case "regexp":
				if _, err := regexp.Compile(value); err == nil && value != "" {
					add("regex", value)
				}
			}
			continue
		}
		if strings.HasPrefix(line, "||") {
			rest := line[2:]
			if strings.Contains(rest, "$") {
				continue
			}
			rest = strings.TrimSuffix(rest, "^")
			if strings.ContainsAny(rest, "^/*|") {
				continue
			}
			domain(rest, false)
			continue
		}
		if strings.Contains(line, "##") || strings.Contains(line, "#@#") || strings.HasPrefix(line, "|") || strings.HasPrefix(line, "/") {
			continue
		}
		if fields := strings.Fields(line); len(fields) >= 2 && routingHostsAddresses[fields[0]] {
			for _, host := range fields[1:] {
				if !routingHostsSkip[strings.ToLower(host)] {
					domain(host, true)
				}
			}
			continue
		}
		if kind, rest, ok := strings.Cut(line, ","); ok {
			payload := strings.TrimSpace(strings.SplitN(rest, ",", 2)[0])
			switch strings.ToUpper(strings.TrimSpace(kind)) {
			case "DOMAIN-SUFFIX", "HOST-SUFFIX":
				domain(payload, false)
			case "DOMAIN", "HOST":
				domain(payload, true)
			case "DOMAIN-KEYWORD", "HOST-KEYWORD":
				if payload != "" && !strings.ContainsAny(payload, " ,()") {
					add("keyword", strings.ToLower(payload))
				}
			case "DOMAIN-REGEX":
				// The pattern may contain commas (a{1,3}); DOMAIN-REGEX takes
				// no params, as in mihomo's ParseRulePayload.
				payload = strings.TrimSpace(rest)
				if _, err := regexp.Compile(payload); err == nil && payload != "" {
					add("regex", payload)
				}
			case "IP-CIDR", "IP-CIDR6", "IP6-CIDR":
				ip(payload)
			}
			continue
		}
		if ip(line) {
			continue
		}
		domain(line, false)
	}
}

// ---------------------------------------------------------------------------
// shared helpers
// ---------------------------------------------------------------------------

func routingSortedKeys(values map[string]bool) []string {
	keys := make([]string, 0, len(values))
	for key := range values {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	return keys
}

func routingUniqueTag(used map[string]bool, base string) string {
	tag := base
	for i := 2; used[tag]; i++ {
		tag = base + "-" + strconv.Itoa(i)
	}
	used[tag] = true
	return tag
}

// CoreRoutingTargets lists, one per line, the names a "node or group" rule of
// this core can target in the profile: sing-box outbound and endpoint tags,
// Xray outbound and balancer tags, mihomo proxies and proxy-groups (the nodes
// of mihomo proxy-providers cannot be rule targets, so they are left out).
func CoreRoutingTargets(core string, content string) (string, error) {
	var names []string
	switch core {
	case CoreSingBox, "":
		root, err := decodeOrderedJSON(content)
		if err != nil {
			return "", err
		}
		for _, key := range []string{"outbounds", "endpoints"} {
			for _, item := range anyList(root[key]) {
				outbound := anyMap(item)
				switch anyString(outbound["type"]) {
				case "dns", "block":
					continue
				}
				names = append(names, anyString(outbound["tag"]))
			}
		}
	case CoreXray:
		root, err := decodeOrderedJSON(content)
		if err != nil {
			return "", err
		}
		for _, item := range anyList(anyMap(root["routing"])["balancers"]) {
			names = append(names, anyString(anyMap(item)["tag"]))
		}
		for _, item := range anyList(root["outbounds"]) {
			outbound := anyMap(item)
			if anyString(outbound["protocol"]) == "dns" {
				continue
			}
			names = append(names, anyString(outbound["tag"]))
		}
	case CoreMihomo:
		var err error
		if names, err = mihomoRoutingTargets(content); err != nil {
			return "", err
		}
	default:
		return "", fmt.Errorf("unknown core %s", core)
	}
	seen := map[string]bool{}
	var unique []string
	for _, name := range names {
		if name != "" && !seen[name] {
			seen[name] = true
			unique = append(unique, name)
		}
	}
	return strings.Join(unique, "\n"), nil
}

// routingV2flyLine splits a v2fly domain-list-community line ("full:a.com
// @cn") into its type and value; ok is false for other lines.
func routingV2flyLine(line string) (kind string, value string, ok bool) {
	prefix, rest, found := strings.Cut(line, ":")
	if !found {
		return "", "", false
	}
	switch kind = strings.ToLower(strings.TrimSpace(prefix)); kind {
	case "full", "domain", "keyword", "regexp", "include":
	default:
		return "", "", false
	}
	value = strings.TrimSpace(rest)
	if index := strings.Index(value, " @"); index >= 0 {
		value = strings.TrimSpace(value[:index])
	}
	return kind, value, true
}
