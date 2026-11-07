package libbox

// Adapted from FlClash be84736605527034689c95bf27828ff27d912c5e:
// core/service_check.go, core/service_rules.go, core/region.go and the
// request/scan parts of core/probe.go (GPL-3.0). The checks run through this
// probe's route (sing-box, Xray or mihomo, or the device network while the
// service is stopped) instead of mihomo's tunnel; FK-box does not report the
// route's exit chain, so items carry no chains or route hits.
import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"net/url"
	"regexp"
	"slices"
	"strings"
	"sync"
	"time"
)

const (
	uiServiceConcurrency   = 4
	uiServiceBudgetFactor  = 6
	uiServiceDefaultTimout = 10 * time.Second
	uiServiceHTMLMaxBody   = 512 * 1024
	uiServiceSmallMaxBody  = 16 * 1024
	uiServiceScanMaxBody   = 8 * 1024 * 1024
	uiServiceScanChunk     = 32 * 1024
	uiServiceScanOverlap   = 256

	uiServiceAvailable         = "available"
	uiServiceUnavailable       = "unavailable"
	uiServiceRestricted        = "restricted"
	uiServiceDisallowedIsp     = "disallowed-isp"
	uiServiceBlocked           = "blocked"
	uiServiceUnsupportedRegion = "unsupported-region"
	uiServiceOriginalsOnly     = "originals-only"
	uiServiceComingSoon        = "coming-soon"
	uiServiceTimeout           = "timeout"
	uiServiceFailed            = "failed"

	uiProbeErrorTimeout = "timeout"
	uiProbeErrorFailed  = "failed"
)

type uiServiceItem struct {
	Name      string `json:"name"`
	Status    string `json:"status"`
	Region    string `json:"region,omitempty"`
	Delay     int64  `json:"delay,omitempty"`
	CheckedAt int64  `json:"checkedAt"`
}

type uiServiceRequest struct {
	method     string
	url        string
	headers    map[string]string
	body       []byte
	maxBody    int64
	noRedirect bool
	until      func(tail string) bool
}

type uiServiceResult struct {
	StatusCode int
	Delay      int64
	Body       string
	URL        string
	Error      string

	header    http.Header
	lowerBody string
}

// uiServiceSender performs one request; tests replace it with a fake.
type uiServiceSender func(ctx context.Context, req uiServiceRequest) *uiServiceResult

type uiServiceEnv struct {
	ctx     context.Context
	timeout time.Duration
	send    uiServiceSender
	slots   chan struct{}
}

func (e uiServiceEnv) get(link string, maxBody int64) *uiServiceResult {
	return e.request(uiServiceRequest{method: http.MethodGet, url: link, maxBody: maxBody})
}

func (e uiServiceEnv) scan(link string, until func(tail string) bool) *uiServiceResult {
	return e.request(uiServiceRequest{method: http.MethodGet, url: link, maxBody: uiServiceScanMaxBody, until: until})
}

func (e uiServiceEnv) request(req uiServiceRequest) *uiServiceResult {
	headers := map[string]string{"User-Agent": uiProbeUserAgent}
	for name, value := range req.headers {
		headers[name] = value
	}
	req.headers = headers
	if e.ctx.Err() != nil {
		return nil
	}
	ctx, cancel := context.WithTimeout(e.ctx, e.timeout)
	defer cancel()
	return e.send(ctx, req)
}

type uiServiceChecker struct {
	name  string
	check func(uiServiceEnv) uiServiceItem
}

var uiServiceCheckers = []uiServiceChecker{
	{name: "google", check: uiCheckReachable("https://www.google.com/generate_204")},
	{name: "github", check: uiCheckReachable("https://github.com/")},
	{name: "youtube", check: uiCheckYouTubePremium},
	{name: "chatgpt", check: uiCheckChatGpt},
	{name: "claude", check: uiCheckClaude},
	{name: "gemini", check: uiCheckGemini},
	{name: "netflix", check: uiCheckNetflix},
	{name: "disney-plus", check: uiCheckDisneyPlus},
	{name: "prime-video", check: uiCheckPrimeVideo},
	{name: "spotify", check: uiCheckSpotify},
	{name: "tiktok", check: uiCheckTikTok},
	{name: "discord", check: uiCheckDiscord},
	{name: "bilibili", check: uiCheckBilibili},
}

// CheckServices runs FlClash's service checks for the comma-separated names
// (every service when empty) through this probe and returns a JSON array.
func (p *UIEgressProbe) CheckServices(names string, timeoutMillis int64) (string, error) {
	if timeoutMillis < 0 || timeoutMillis > 30000 {
		return "", errors.New("invalid service check timeout")
	}
	var wanted []string
	for _, name := range strings.Split(names, ",") {
		if name = strings.TrimSpace(name); name != "" {
			wanted = append(wanted, name)
		}
	}
	timeout := uiServiceDefaultTimout
	if timeoutMillis > 0 {
		timeout = time.Duration(timeoutMillis) * time.Millisecond
	}
	items := runUIServiceChecks(p.ctx, wanted, timeout, p.sendService)
	if !p.current() {
		return "", errors.New("service changed during service check")
	}
	encoded, err := json.Marshal(items)
	if err != nil {
		return "", err
	}
	return string(encoded), nil
}

func runUIServiceChecks(parent context.Context, names []string, timeout time.Duration, send uiServiceSender) []uiServiceItem {
	wanted := selectUIServiceCheckers(names)
	items := make([]uiServiceItem, len(wanted))
	if len(wanted) == 0 {
		return items
	}
	ctx, cancel := context.WithTimeout(parent, timeout*uiServiceBudgetFactor)
	defer cancel()
	slots := make(chan struct{}, uiServiceConcurrency)
	var running sync.WaitGroup
	for index, checker := range wanted {
		granted := false
		select {
		case slots <- struct{}{}:
			granted = true
		case <-ctx.Done():
		}
		running.Add(1)
		go func(index int, checker uiServiceChecker, granted bool) {
			defer running.Done()
			if granted {
				defer func() { <-slots }()
			}
			items[index] = runUIServiceCheck(ctx, checker, timeout, send)
		}(index, checker, granted)
	}
	running.Wait()
	return items
}

func runUIServiceCheck(ctx context.Context, checker uiServiceChecker, timeout time.Duration, send uiServiceSender) (item uiServiceItem) {
	item = uiServiceItem{Status: uiServiceFailed}
	defer func() {
		if recovered := recover(); recovered != nil {
			item = uiServiceItem{Status: uiServiceFailed}
		}
		item.Name = checker.name
		item.CheckedAt = time.Now().UnixMilli()
	}()
	if ctx.Err() != nil {
		return item
	}
	env := uiServiceEnv{ctx: ctx, timeout: timeout, send: send}
	item = checker.check(env)
	if item.Status == uiServiceTimeout && uiServiceHasBudget(ctx, timeout) {
		item = checker.check(env)
	}
	return item
}

func uiServiceHasBudget(ctx context.Context, timeout time.Duration) bool {
	deadline, ok := ctx.Deadline()
	return !ok || time.Until(deadline) >= timeout
}

func selectUIServiceCheckers(names []string) []uiServiceChecker {
	if len(names) == 0 {
		return uiServiceCheckers
	}
	wanted := make([]uiServiceChecker, 0, len(names))
	for _, name := range names {
		for _, checker := range uiServiceCheckers {
			if checker.name == name {
				wanted = append(wanted, checker)
				break
			}
		}
	}
	return wanted
}

// sendService is probe.go's runProbe over this probe's dialer.
func (p *UIEgressProbe) sendService(ctx context.Context, req uiServiceRequest) *uiServiceResult {
	result := &uiServiceResult{URL: req.url}
	fail := func(err error) *uiServiceResult {
		result.Error = uiProbeErrorKind(ctx, err)
		return result
	}
	if !p.current() {
		return fail(errors.New("service changed during service check"))
	}
	var body io.Reader
	if len(req.body) > 0 {
		body = bytes.NewReader(req.body)
	}
	method := req.method
	if method == "" {
		method = http.MethodGet
	}
	requestContext := context.WithValue(ctx, uiEgressRequestContextKey{}, ctx)
	request, err := http.NewRequestWithContext(requestContext, method, req.url, body)
	if err != nil {
		return fail(err)
	}
	for name, value := range req.headers {
		request.Header.Set(name, value)
	}
	client := *p.client
	if req.noRedirect {
		client.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	}
	start := time.Now()
	response, err := client.Do(request)
	if err != nil {
		return fail(err)
	}
	defer response.Body.Close()
	result.Delay = time.Since(start).Milliseconds()
	result.StatusCode = response.StatusCode
	result.header = response.Header
	result.URL = response.Request.URL.String()
	if req.maxBody > 0 {
		result.Body = readUIServiceBody(response.Body, req.maxBody, req.until)
	}
	return result
}

func uiProbeErrorKind(ctx context.Context, err error) string {
	var netErr net.Error
	if errors.Is(err, context.DeadlineExceeded) || ctx.Err() == context.DeadlineExceeded || (errors.As(err, &netErr) && netErr.Timeout()) {
		return uiProbeErrorTimeout
	}
	return uiProbeErrorFailed
}

func readUIServiceBody(body io.Reader, maxBody int64, until func(string) bool) string {
	limited := io.LimitReader(body, maxBody)
	if until == nil {
		payload, _ := io.ReadAll(limited)
		return string(payload)
	}
	var buffer bytes.Buffer
	chunk := make([]byte, uiServiceScanChunk)
	for {
		n, err := limited.Read(chunk)
		if n > 0 {
			start := max(0, buffer.Len()-uiServiceScanOverlap)
			buffer.Write(chunk[:n])
			if until(string(buffer.Bytes()[start:])) {
				break
			}
		}
		if err != nil {
			break
		}
	}
	return buffer.String()
}

// --- service_check.go helpers ---

func uiCheckReachable(link string) func(uiServiceEnv) uiServiceItem {
	return func(env uiServiceEnv) uiServiceItem {
		result := env.get(link, 0)
		item := uiItemFrom(result)
		if item.Status != "" {
			return item
		}
		item.Status = uiStatusFromCode(result.StatusCode)
		return item
	}
}

func uiItemFrom(result *uiServiceResult) uiServiceItem {
	if result == nil {
		return uiServiceItem{Status: uiServiceFailed}
	}
	item := uiServiceItem{Delay: result.Delay}
	switch result.Error {
	case "":
	case uiProbeErrorTimeout:
		item.Status = uiServiceTimeout
		item.Delay = 0
	default:
		item.Status = uiServiceFailed
		item.Delay = 0
	}
	return item
}

func uiStatusFromCode(code int) string {
	switch {
	case code >= 200 && code < 300:
		return uiServiceAvailable
	case code == http.StatusUnauthorized, code == http.StatusForbidden,
		code == http.StatusTooManyRequests, code == http.StatusUnavailableForLegalReasons:
		return uiServiceRestricted
	default:
		return uiServiceUnavailable
	}
}

func uiAnswered(item uiServiceItem, result *uiServiceResult) bool {
	return item.Status == "" && result != nil
}

func uiBodyContains(result *uiServiceResult, needles ...string) bool {
	if result.lowerBody == "" {
		result.lowerBody = strings.ToLower(result.Body)
	}
	for _, needle := range needles {
		if strings.Contains(result.lowerBody, needle) {
			return true
		}
	}
	return false
}

func uiTraceValue(body string, key string) string {
	for _, line := range strings.Split(body, "\n") {
		if rest, found := strings.CutPrefix(line, key+"="); found {
			return strings.TrimSpace(rest)
		}
	}
	return ""
}

// --- service_rules.go ---

const uiDisneyAuthHeader = "Bearer ZGlzbmV5JmJyb3dzZXImMS4wLjA.Cu56AgSfBTDag5NiRA81oLHkDZfu5L3CKadnefEAY84"

var (
	uiYouTubeRegionPatterns = []*regexp.Regexp{
		regexp.MustCompile(`(?i)id=["']country-code["'][^>]*>\s*([A-Za-z]{2,3})\s*<`),
		regexp.MustCompile(`"GL"\s*:\s*"([A-Za-z]{2})"`),
		regexp.MustCompile(`"countryCode"\s*:\s*"([A-Za-z]{2})"`),
		regexp.MustCompile(`"country_code"\s*:\s*"([A-Za-z]{2})"`),
	}
	uiTikTokRegionPattern    = regexp.MustCompile(`"webapp\.app-context"\s*:\s*\{[^{}]*?"region"\s*:\s*"([^"]*)"`)
	uiPrimeRegionPattern     = regexp.MustCompile(`"currentTerritory":"([^"]+)"`)
	uiDisneyAssertionPattern = regexp.MustCompile(`"assertion"\s*:\s*"([^"]+)"`)
	uiDisneyRefreshPattern   = regexp.MustCompile(`"refresh_token"\s*:\s*"([^"]+)"`)
	uiDisneyCountryPattern   = regexp.MustCompile(`"countryCode"\s*:\s*"([^"]+)"`)
	uiDisneySupportedPattern = regexp.MustCompile(`"inSupportedLocation"\s*:\s*(false|true)`)
	uiSpotifyCountryPattern  = regexp.MustCompile(`"countryCode"\s*:\s*"([^"]+)"`)
	uiDiscordCountryPattern  = regexp.MustCompile(`"country_code"\s*:\s*"([^"]+)"`)
	uiGeminiRegionMarker     = `,2,1,200,"`
	uiClaudeBlockedRegions   = []string{"AF", "BY", "CN", "CU", "HK", "IR", "KP", "MO", "RU", "SY"}
	uiGeminiBlockedRegions   = []string{"CHN", "RUS", "BLR", "CUB", "IRN", "PRK", "SYR", "HKG", "MAC"}
	uiBilibiliCodePattern    = regexp.MustCompile(`^\s*\{\s*"code"\s*:\s*(-?\d+)`)
)

func uiFirstSubmatch(pattern *regexp.Regexp, body string) string {
	match := pattern.FindStringSubmatch(body)
	if len(match) < 2 {
		return ""
	}
	return match[1]
}

func uiCheckClaude(env uiServiceEnv) uiServiceItem {
	result := env.get("https://claude.ai/cdn-cgi/trace", uiServiceSmallMaxBody)
	item := uiItemFrom(result)
	if !uiAnswered(item, result) {
		return item
	}
	region := uiNormalizeRegion(uiTraceValue(result.Body, "loc"))
	if region == "" {
		item.Status = uiServiceFailed
		return item
	}
	item.Region = region
	item.Status = uiServiceAvailable
	if slices.Contains(uiClaudeBlockedRegions, region) {
		item.Status = uiServiceUnsupportedRegion
	}
	return item
}

func uiCheckGemini(env uiServiceEnv) uiServiceItem {
	result := env.scan("https://gemini.google.com", func(tail string) bool {
		index := strings.Index(tail, uiGeminiRegionMarker)
		return index >= 0 && index+len(uiGeminiRegionMarker)+3 <= len(tail)
	})
	item := uiItemFrom(result)
	if !uiAnswered(item, result) {
		return item
	}
	index := strings.Index(result.Body, uiGeminiRegionMarker)
	if index < 0 || index+len(uiGeminiRegionMarker)+3 > len(result.Body) {
		item.Status = uiServiceFailed
		return item
	}
	start := index + len(uiGeminiRegionMarker)
	code := result.Body[start : start+3]
	if !uiIsASCIIUpper(code) {
		item.Status = uiServiceFailed
		return item
	}
	item.Region = uiNormalizeRegion(code)
	item.Status = uiServiceAvailable
	if slices.Contains(uiGeminiBlockedRegions, code) {
		item.Status = uiServiceUnsupportedRegion
	}
	return item
}

func uiCheckChatGpt(env uiServiceEnv) uiServiceItem {
	ios := env.get("https://ios.chat.openai.com/", uiServiceSmallMaxBody)
	item := uiItemFrom(ios)
	if uiAnswered(item, ios) {
		item.Status = uiChatGptIosStatus(ios)
	}
	trace := env.get("https://chat.openai.com/cdn-cgi/trace", uiServiceSmallMaxBody)
	if traceItem := uiItemFrom(trace); uiAnswered(traceItem, trace) {
		item.Region = uiNormalizeRegion(uiTraceValue(trace.Body, "loc"))
	}
	web := env.get("https://api.openai.com/compliance/cookie_requirements", uiServiceSmallMaxBody)
	if webItem := uiItemFrom(web); uiAnswered(webItem, web) && uiBodyContains(web, "unsupported_country") {
		item.Status = uiServiceUnsupportedRegion
	}
	return item
}

func uiChatGptIosStatus(result *uiServiceResult) string {
	switch {
	case uiBodyContains(result, "you may be connected to a disallowed isp"):
		return uiServiceDisallowedIsp
	case uiBodyContains(result, "sorry, you have been blocked"):
		return uiServiceBlocked
	case uiBodyContains(result, "request is not allowed. please try again later."):
		return uiServiceAvailable
	default:
		return uiServiceFailed
	}
}

func uiCheckYouTubePremium(env uiServiceEnv) uiServiceItem {
	result := env.get("https://www.youtube.com/premium?hl=en", uiServiceHTMLMaxBody)
	item := uiItemFrom(result)
	if !uiAnswered(item, result) {
		return item
	}
	for _, pattern := range uiYouTubeRegionPatterns {
		if code := uiFirstSubmatch(pattern, result.Body); code != "" {
			item.Region = uiNormalizeRegion(code)
			break
		}
	}
	switch {
	case uiBodyContains(result, "youtube premium is not available in your country",
		"premium is not available in your country", "premium is not available in your region"):
		item.Status = uiServiceUnsupportedRegion
	case result.StatusCode >= 200 && result.StatusCode < 300 &&
		uiBodyContains(result, "youtube premium", "ad-free", `"browseid":"spunlimited"`):
		item.Status = uiServiceAvailable
	default:
		item.Status = uiStatusFromCode(result.StatusCode)
	}
	return item
}

func uiCheckSpotify(env uiServiceEnv) uiServiceItem {
	result := env.get("https://www.spotify.com/api/content/v1/country-selector?platform=web&format=json", uiServiceSmallMaxBody)
	item := uiItemFrom(result)
	if !uiAnswered(item, result) {
		return item
	}
	item.Region = uiNormalizeRegion(uiSpotifyRegion(result))
	switch {
	case result.StatusCode == http.StatusForbidden || result.StatusCode == http.StatusUnavailableForLegalReasons:
		item.Status = uiServiceUnsupportedRegion
	case result.StatusCode < 200 || result.StatusCode >= 300:
		item.Status = uiStatusFromCode(result.StatusCode)
	case uiBodyContains(result, "not available in your country"):
		item.Status = uiServiceUnsupportedRegion
	default:
		item.Status = uiServiceAvailable
	}
	return item
}

func uiSpotifyRegion(result *uiServiceResult) string {
	if final, err := url.Parse(result.URL); err == nil {
		segments := strings.Split(strings.Trim(final.Path, "/"), "/")
		if first := segments[0]; first != "" && first != "api" {
			return strings.Split(first, "-")[0]
		}
	}
	return uiFirstSubmatch(uiSpotifyCountryPattern, result.Body)
}

func uiCheckTikTok(env uiServiceEnv) uiServiceItem {
	result := env.scan("https://www.tiktok.com/", uiTikTokRegionPattern.MatchString)
	item := uiItemFrom(result)
	if !uiAnswered(item, result) {
		return item
	}
	item.Status = uiTikTokStatus(result)
	item.Region = uiNormalizeRegion(uiFirstSubmatch(uiTikTokRegionPattern, result.Body))
	return item
}

func uiTikTokStatus(result *uiServiceResult) string {
	switch {
	case result.StatusCode == http.StatusForbidden || result.StatusCode == http.StatusUnavailableForLegalReasons:
		return uiServiceUnsupportedRegion
	case result.StatusCode < 200 || result.StatusCode >= 300:
		return uiServiceFailed
	case uiBodyContains(result, "access denied", "not available in your region", "tiktok is not available"):
		return uiServiceUnsupportedRegion
	default:
		return uiServiceAvailable
	}
}

func uiCheckBilibili(env uiServiceEnv) uiServiceItem {
	const mainland = "https://api.bilibili.com/pgc/player/web/playurl?avid=82846771&qn=0&type=&otype=json&ep_id=307247&fourk=1&fnver=0&fnval=16&module=bangumi"
	const hongKongMacauTaiwan = "https://api.bilibili.com/pgc/player/web/playurl?avid=18281381&cid=29892777&qn=0&type=&otype=json&ep_id=183799&fourk=1&fnver=0&fnval=16&module=bangumi"
	item := uiBilibiliEpisode(env, mainland)
	switch item.Status {
	case uiServiceAvailable:
		item.Region = "CN"
		return item
	case uiServiceUnsupportedRegion:
		return uiBilibiliEpisode(env, hongKongMacauTaiwan)
	default:
		return item
	}
}

func uiBilibiliEpisode(env uiServiceEnv, link string) uiServiceItem {
	result := env.get(link, uiServiceSmallMaxBody)
	item := uiItemFrom(result)
	if !uiAnswered(item, result) {
		return item
	}
	item.Status = uiBilibiliStatus(result.Body)
	return item
}

func uiBilibiliStatus(body string) string {
	switch uiFirstSubmatch(uiBilibiliCodePattern, body) {
	case "0":
		return uiServiceAvailable
	case "-10403":
		return uiServiceUnsupportedRegion
	default:
		return uiServiceFailed
	}
}

func uiCheckDiscord(env uiServiceEnv) uiServiceItem {
	result := env.get("https://discord.com/api/v9/auth/location-metadata", uiServiceSmallMaxBody)
	item := uiItemFrom(result)
	if !uiAnswered(item, result) {
		return item
	}
	item.Status = uiStatusFromCode(result.StatusCode)
	if item.Status == uiServiceAvailable {
		item.Region = uiNormalizeRegion(uiFirstSubmatch(uiDiscordCountryPattern, result.Body))
	}
	return item
}

func uiCheckPrimeVideo(env uiServiceEnv) uiServiceItem {
	result := env.scan("https://www.primevideo.com", func(tail string) bool {
		return strings.Contains(tail, "isServiceRestricted") || uiPrimeRegionPattern.MatchString(tail)
	})
	item := uiItemFrom(result)
	if !uiAnswered(item, result) {
		return item
	}
	if strings.Contains(result.Body, "isServiceRestricted") {
		item.Status = uiServiceUnsupportedRegion
		return item
	}
	region := uiFirstSubmatch(uiPrimeRegionPattern, result.Body)
	if region == "" {
		item.Status = uiServiceFailed
		return item
	}
	item.Region = uiNormalizeRegion(region)
	item.Status = uiServiceAvailable
	return item
}

func uiCheckNetflix(env uiServiceEnv) uiServiceItem {
	if item := uiNetflixFromCdn(env); item.Status == uiServiceAvailable || item.Status == uiServiceBlocked {
		return item
	}
	first := env.get("https://www.netflix.com/title/81280792", 0)
	firstItem := uiItemFrom(first)
	if !uiAnswered(firstItem, first) {
		return firstItem
	}
	second := env.get("https://www.netflix.com/title/70143836", 0)
	secondItem := uiItemFrom(second)
	if !uiAnswered(secondItem, second) {
		return secondItem
	}
	item := firstItem
	switch {
	case first.StatusCode == http.StatusNotFound && second.StatusCode == http.StatusNotFound:
		item.Status = uiServiceOriginalsOnly
	case first.StatusCode == http.StatusForbidden || second.StatusCode == http.StatusForbidden:
		item.Status = uiServiceUnsupportedRegion
	case uiNetflixServed(first.StatusCode) || uiNetflixServed(second.StatusCode):
		item.Status = uiServiceAvailable
		item.Region = uiNetflixRegion(env)
	default:
		item.Status = uiStatusFromCode(first.StatusCode)
	}
	return item
}

func uiNetflixServed(code int) bool {
	return code == http.StatusOK || code == http.StatusMovedPermanently
}

func uiNetflixRegion(env uiServiceEnv) string {
	result := env.request(uiServiceRequest{method: http.MethodGet, url: "https://www.netflix.com/title/80018499", noRedirect: true})
	if result == nil || result.Error != "" || result.header == nil {
		return ""
	}
	segments := strings.Split(result.header.Get("Location"), "/")
	if len(segments) < 4 {
		return ""
	}
	return uiNormalizeRegion(strings.Split(segments[3], "-")[0])
}

func uiNetflixFromCdn(env uiServiceEnv) uiServiceItem {
	const link = "https://api.fast.com/netflix/speedtest/v2?https=true&token=YXNkZmFzZGxmbnNkYWZoYXNkZmhrYWxm&urlCount=5"
	result := env.get(link, uiServiceSmallMaxBody)
	item := uiItemFrom(result)
	if !uiAnswered(item, result) {
		return item
	}
	if result.StatusCode == http.StatusForbidden {
		item.Status = uiServiceBlocked
		return item
	}
	var payload struct {
		Targets []struct {
			Location struct {
				Country string `json:"country"`
			} `json:"location"`
		} `json:"targets"`
	}
	if err := json.Unmarshal([]byte(result.Body), &payload); err != nil || len(payload.Targets) == 0 {
		item.Status = uiServiceFailed
		return item
	}
	item.Region = uiNormalizeRegion(payload.Targets[0].Location.Country)
	item.Status = uiServiceAvailable
	return item
}

func uiCheckDisneyPlus(env uiServiceEnv) uiServiceItem {
	device := env.request(uiServiceRequest{
		method: http.MethodPost,
		url:    "https://disney.api.edge.bamgrid.com/devices",
		headers: map[string]string{
			"authorization": uiDisneyAuthHeader,
			"content-type":  "application/json; charset=UTF-8",
		},
		body:    []byte(`{"deviceFamily":"browser","applicationRuntime":"chrome","deviceProfile":"windows","attributes":{}}`),
		maxBody: uiServiceSmallMaxBody,
	})
	item := uiItemFrom(device)
	if !uiAnswered(item, device) {
		return item
	}
	if device.StatusCode == http.StatusForbidden {
		item.Status = uiServiceBlocked
		return item
	}
	assertion := uiFirstSubmatch(uiDisneyAssertionPattern, device.Body)
	if assertion == "" {
		item.Status = uiServiceFailed
		return item
	}
	form := strings.NewReplacer("{assertion}", url.QueryEscape(assertion)).Replace(
		"grant_type=urn:ietf:params:oauth:grant-type:token-exchange" +
			"&latitude=0&longitude=0&platform=browser" +
			"&subject_token={assertion}" +
			"&subject_token_type=urn:bamtech:params:oauth:token-type:device",
	)
	token := env.request(uiServiceRequest{
		method: http.MethodPost,
		url:    "https://disney.api.edge.bamgrid.com/token",
		headers: map[string]string{
			"authorization": uiDisneyAuthHeader,
			"content-type":  "application/x-www-form-urlencoded",
		},
		body:    []byte(form),
		maxBody: uiServiceSmallMaxBody,
	})
	if tokenItem := uiItemFrom(token); !uiAnswered(tokenItem, token) {
		return tokenItem
	}
	if strings.Contains(token.Body, "forbidden-location") || strings.Contains(token.Body, "403 ERROR") {
		item.Status = uiServiceBlocked
		return item
	}
	refresh := uiFirstSubmatch(uiDisneyRefreshPattern, token.Body)
	if refresh == "" {
		item.Status = uiServiceFailed
		return item
	}
	query := `{"query":"mutation refreshToken($input: RefreshTokenInput!) { refreshToken(refreshToken: $input) { activeSession { sessionId } } }","variables":{"input":{"refreshToken":"` +
		refresh + `"}}}`
	session := env.request(uiServiceRequest{
		method: http.MethodPost,
		url:    "https://disney.api.edge.bamgrid.com/graph/v1/device/graphql",
		headers: map[string]string{
			"authorization": uiDisneyAuthHeader,
			"content-type":  "application/json",
		},
		body:    []byte(query),
		maxBody: uiServiceSmallMaxBody,
	})
	sessionItem := uiItemFrom(session)
	if !uiAnswered(sessionItem, session) || session.Body == "" || session.StatusCode >= 400 {
		item.Status = uiServiceFailed
		return item
	}
	region := uiNormalizeRegion(uiFirstSubmatch(uiDisneyCountryPattern, session.Body))
	if region == "" {
		item.Status = uiServiceUnavailable
		return item
	}
	item.Region = region
	switch uiFirstSubmatch(uiDisneySupportedPattern, session.Body) {
	case "true":
		item.Status = uiServiceAvailable
	case "false":
		item.Status = uiServiceComingSoon
	default:
		item.Status = uiServiceFailed
	}
	return item
}

// --- region.go ---

const uiISO3166Packed = "ABWAWAFGAFAGOAOAIAAIALAAXALBALANDADAREAEARGARARMAMASMASATAAQ" +
	"ATFTFATGAGAUSAUAUTATAZEAZBDIBIBELBEBENBJBESBQBFABFBGDBDBGRBG" +
	"BHRBHBHSBSBIHBABLMBLBLRBYBLZBZBMUBMBOLBOBRABRBRBBBBRNBNBTNBT" +
	"BVTBVBWABWCAFCFCANCACCKCCCHECHCHLCLCHNCNCIVCICMRCMCODCDCOGCG" +
	"COKCKCOLCOCOMKMCPVCVCRICRCUBCUCUWCWCXRCXCYMKYCYPCYCZECZDEUDE" +
	"DJIDJDMADMDNKDKDOMDODZADZECUECEGYEGERIERESHEHESPESESTEEETHET" +
	"FINFIFJIFJFLKFKFRAFRFROFOFSMFMGABGAGBRGBGEOGEGGYGGGHAGHGIBGI" +
	"GINGNGLPGPGMBGMGNBGWGNQGQGRCGRGRDGDGRLGLGTMGTGUFGFGUMGUGUYGY" +
	"HKGHKHMDHMHNDHNHRVHRHTIHTHUNHUIDNIDIMNIMINDINIOTIOIRLIEIRNIR" +
	"IRQIQISLISISRILITAITJAMJMJEYJEJORJOJPNJPKAZKZKENKEKGZKGKHMKH" +
	"KIRKIKNAKNKORKRKWTKWLAOLALBNLBLBRLRLBYLYLCALCLIELILKALKLSOLS" +
	"LTULTLUXLULVALVMACMOMAFMFMARMAMCOMCMDAMDMDGMGMDVMVMEXMXMHLMH" +
	"MKDMKMLIMLMLTMTMMRMMMNEMEMNGMNMNPMPMOZMZMRTMRMSRMSMTQMQMUSMU" +
	"MWIMWMYSMYMYTYTNAMNANCLNCNERNENFKNFNGANGNICNINIUNUNLDNLNORNO" +
	"NPLNPNRUNRNZLNZOMNOMPAKPKPANPAPCNPNPERPEPHLPHPLWPWPNGPGPOLPL" +
	"PRIPRPRKKPPRTPTPRYPYPSEPSPYFPFQATQAREUREROURORUSRURWARWSAUSA" +
	"SDNSDSENSNSGPSGSGSGSSHNSHSJMSJSLBSBSLESLSLVSVSMRSMSOMSOSPMPM" +
	"SRBRSSSDSSSTPSTSURSRSVKSKSVNSISWESESWZSZSXMSXSYCSCSYRSYTCATC" +
	"TCDTDTGOTGTHATHTJKTJTKLTKTKMTMTLSTLTONTOTTOTTTUNTNTURTRTUVTV" +
	"TWNTWTZATZUGAUGUKRUAUMIUMURYUYUSAUSUZBUZVATVAVCTVCVENVEVGBVG" +
	"VIRVIVNMVNVUTVUWLFWFWSMWSYEMYEZAFZAZMBZMZWEZW"

func uiNormalizeRegion(code string) string {
	code = strings.ToUpper(strings.TrimSpace(code))
	switch len(code) {
	case 2:
		if uiIsASCIIUpper(code) {
			return code
		}
		return ""
	case 3:
		if !uiIsASCIIUpper(code) {
			return ""
		}
		low, high := 0, len(uiISO3166Packed)/5
		for low < high {
			mid := (low + high) / 2
			entry := uiISO3166Packed[mid*5 : mid*5+3]
			switch {
			case entry < code:
				low = mid + 1
			case entry > code:
				high = mid
			default:
				return uiISO3166Packed[mid*5+3 : mid*5+5]
			}
		}
		return ""
	default:
		return ""
	}
}

func uiIsASCIIUpper(value string) bool {
	for _, char := range value {
		if char < 'A' || char > 'Z' {
			return false
		}
	}
	return true
}
