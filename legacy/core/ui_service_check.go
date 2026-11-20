package libbox

// Service status checks adapted from FlClash v0.9.3
// (be84736605527034689c95bf27828ff27d912c5e): core/service_check.go,
// core/service_rules.go, core/region.go and the request half of core/probe.go
// (GPL-3.0). Requests go through the dashboard egress probe (UIEgressProbe):
// the running core's routing, or the device network while it is stopped.
import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"regexp"
	"slices"
	"strings"
	"time"
)

const (
	// FlClash serviceSweepBudgetFactor: a rule like Disney+ spends several
	// requests in sequence, so one check gets a multiple of the request timeout.
	uiServiceBudgetFactor = 6

	uiServiceHtmlMaxBody  = 512 * 1024
	uiServiceSmallMaxBody = 16 * 1024
	// Gemini's region sits past 800 KiB, Prime Video's at times past 4 MiB.
	uiServiceScanMaxBody = 8 * 1024 * 1024

	uiServiceScanChunk   = 32 * 1024
	uiServiceScanOverlap = 256
)

// Statuses a check reports; the UI maps each to a label (FlClash
// ServiceProbeStatus ids).
const (
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
)

// UIServiceCheck is one service's answer: its status, the region the
// service placed the visitor in (ISO 3166 alpha-2, empty when unknown), the
// latency of its first answered request and when the check finished.
type UIServiceCheck struct {
	Name        string
	Status      string
	Region      string
	DelayMillis int64
	CheckedAt   int64
}

type uiServiceChecker struct {
	name  string
	check func(uiServiceEnv) UIServiceCheck
}

// The same services, order and rules as FlClash's serviceCheckers.
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


// ServiceCheck runs one service's rule through this probe. timeoutMillis
// bounds each request; the whole check gets six times that, and a timeout is
// tried once more while that budget lasts (FlClash runServiceCheck).
func (p *UIEgressProbe) ServiceCheck(name string, timeoutMillis int64) (*UIServiceCheck, error) {
	if timeoutMillis < 1 || timeoutMillis > 10000 {
		return nil, fmt.Errorf("invalid probe timeout")
	}
	var checker *uiServiceChecker
	for index := range uiServiceCheckers {
		if uiServiceCheckers[index].name == name {
			checker = &uiServiceCheckers[index]
			break
		}
	}
	if checker == nil {
		return nil, fmt.Errorf("unknown service %q", name)
	}
	timeout := time.Duration(timeoutMillis) * time.Millisecond
	ctx, cancel := context.WithTimeout(p.ctx, timeout*uiServiceBudgetFactor)
	defer cancel()
	item := runUIServiceCheck(ctx, *checker, uiServiceEnv{ctx: ctx, timeout: timeout, send: p.serviceSend})
	if !p.current() {
		return nil, fmt.Errorf("service changed during egress check")
	}
	return &item, nil
}

func runUIServiceCheck(ctx context.Context, checker uiServiceChecker, env uiServiceEnv) (item UIServiceCheck) {
	item = UIServiceCheck{Status: uiServiceFailed}
	defer func() {
		if recovered := recover(); recovered != nil {
			item = UIServiceCheck{Status: uiServiceFailed}
		}
		item.Name = checker.name
		item.CheckedAt = time.Now().UnixMilli()
		if item.Status != uiServiceAvailable && item.Status != uiServiceUnsupportedRegion &&
			item.Status != uiServiceComingSoon && item.Status != uiServiceOriginalsOnly {
			// A region is only shown with an answer that depends on it.
			item.Region = ""
		}
	}()
	if ctx.Err() != nil {
		return item
	}
	item = checker.check(env)
	if item.Status == uiServiceTimeout && uiServiceHasBudget(ctx, env.timeout) {
		item = checker.check(env)
	}
	return item
}

func uiServiceHasBudget(ctx context.Context, timeout time.Duration) bool {
	deadline, ok := ctx.Deadline()
	return !ok || time.Until(deadline) >= timeout
}

// ---------------------------------------------------------------------------
// Requests (FlClash probeRequest / ProbeResult / runProbe)
// ---------------------------------------------------------------------------

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
	Url        string
	Error      string

	header    http.Header
	lowerBody string
}

const (
	uiProbeErrorTimeout = "timeout"
	uiProbeErrorFailed  = "failed"
)

type uiServiceEnv struct {
	ctx     context.Context
	timeout time.Duration
	send    func(ctx context.Context, timeout time.Duration, req uiServiceRequest) *uiServiceResult
}

func (e uiServiceEnv) get(link string, maxBody int64) *uiServiceResult {
	return e.do(uiServiceRequest{method: http.MethodGet, url: link, maxBody: maxBody})
}

func (e uiServiceEnv) scan(link string, until func(tail string) bool) *uiServiceResult {
	return e.do(uiServiceRequest{method: http.MethodGet, url: link, maxBody: uiServiceScanMaxBody, until: until})
}

func (e uiServiceEnv) do(req uiServiceRequest) *uiServiceResult {
	headers := map[string]string{"User-Agent": uiProbeUserAgent}
	for name, value := range req.headers {
		headers[name] = value
	}
	req.headers = headers
	return e.send(e.ctx, e.timeout, req)
}

func uiProbeErrorKind(err error) string {
	var netErr net.Error
	if errors.Is(err, context.DeadlineExceeded) || (errors.As(err, &netErr) && netErr.Timeout()) {
		return uiProbeErrorTimeout
	}
	return uiProbeErrorFailed
}

// serviceSend is runProbe over this probe's transport: the request's own
// deadline, redirects followed unless asked not to, and the body read only up
// to maxBody (or until the scan finds what it looks for). Delay is the time
// to the final response's headers.
func (p *UIEgressProbe) serviceSend(parent context.Context, timeout time.Duration, req uiServiceRequest) *uiServiceResult {
	result := &uiServiceResult{Url: req.url}
	fail := func(err error) *uiServiceResult {
		result.Error = uiProbeErrorKind(err)
		return result
	}
	ctx, cancel := context.WithTimeout(parent, timeout)
	defer cancel()
	var body io.Reader
	if len(req.body) > 0 {
		body = bytes.NewReader(req.body)
	}
	method := req.method
	if method == "" {
		method = http.MethodGet
	}
	request, err := p.newMethodRequest(ctx, method, req.url, body)
	if err != nil {
		return fail(err)
	}
	for name, value := range req.headers {
		request.Header.Set(name, value)
	}
	client := *p.client
	client.Timeout = 0
	if req.noRedirect {
		client.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	} else {
		client.CheckRedirect = func(next *http.Request, via []*http.Request) error {
			if len(via) >= 10 {
				return fmt.Errorf("stopped after 10 redirects")
			}
			// Each hop keeps the request context the probe's dialer needs.
			return nil
		}
	}
	started := time.Now()
	response, err := client.Do(request)
	if err != nil {
		if ctx.Err() != nil {
			return fail(ctx.Err())
		}
		return fail(err)
	}
	defer response.Body.Close()
	result.Delay = max(time.Since(started).Milliseconds(), 1)
	result.StatusCode = response.StatusCode
	result.header = response.Header
	if response.Request != nil && response.Request.URL != nil {
		result.Url = response.Request.URL.String()
	}
	if req.maxBody > 0 {
		result.Body = readUIServiceBody(response.Body, req.maxBody, req.until)
	}
	return result
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

// ---------------------------------------------------------------------------
// Rules (FlClash core/service_check.go and core/service_rules.go)
// ---------------------------------------------------------------------------

// uiDisneyAuthHeader is the public browser client token Disney+ hands to any
// web visitor; the device and token calls below are rejected without it.
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

	// A playable episode runs past the small body cap; only its leading code is read.
	uiBilibiliCodePattern = regexp.MustCompile(`^\s*\{\s*"code"\s*:\s*(-?\d+)`)
)

// uiCheckReachable is the rule for a target with no unlock semantics of its own.
func uiCheckReachable(link string) func(uiServiceEnv) UIServiceCheck {
	return func(env uiServiceEnv) UIServiceCheck {
		result := env.get(link, 0)
		item := uiItemFrom(result)
		if item.Status != "" {
			return item
		}
		item.Status = uiStatusFromCode(result.StatusCode)
		return item
	}
}

func uiItemFrom(result *uiServiceResult) UIServiceCheck {
	if result == nil {
		return UIServiceCheck{Status: uiServiceFailed}
	}
	item := UIServiceCheck{DelayMillis: result.Delay}
	switch result.Error {
	case "":
	case uiProbeErrorTimeout:
		item.Status = uiServiceTimeout
		item.DelayMillis = 0
	default:
		item.Status = uiServiceFailed
		item.DelayMillis = 0
	}
	return item
}

func uiStatusFromCode(code int) string {
	switch {
	case code >= 200 && code < 300:
		return uiServiceAvailable
	case code == http.StatusUnauthorized,
		code == http.StatusForbidden,
		code == http.StatusTooManyRequests,
		code == http.StatusUnavailableForLegalReasons:
		return uiServiceRestricted
	default:
		return uiServiceUnavailable
	}
}

func uiAnswered(item UIServiceCheck, result *uiServiceResult) bool {
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

func uiFirstSubmatch(pattern *regexp.Regexp, body string) string {
	match := pattern.FindStringSubmatch(body)
	if len(match) < 2 {
		return ""
	}
	return match[1]
}

func uiCheckClaude(env uiServiceEnv) UIServiceCheck {
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

func uiCheckGemini(env uiServiceEnv) UIServiceCheck {
	result := env.scan("https://gemini.google.com", func(tail string) bool {
		index := strings.Index(tail, uiGeminiRegionMarker)
		return index >= 0 && index+len(uiGeminiRegionMarker)+3 <= len(tail)
	})
	item := uiItemFrom(result)
	if !uiAnswered(item, result) {
		return item
	}
	item.Status, item.Region = uiGeminiStatus(result.Body)
	return item
}

func uiGeminiStatus(body string) (string, string) {
	index := strings.Index(body, uiGeminiRegionMarker)
	if index < 0 || index+len(uiGeminiRegionMarker)+3 > len(body) {
		return uiServiceFailed, ""
	}
	start := index + len(uiGeminiRegionMarker)
	code := body[start : start+3]
	if !uiIsASCIIUpper(code) {
		return uiServiceFailed, ""
	}
	if slices.Contains(uiGeminiBlockedRegions, code) {
		return uiServiceUnsupportedRegion, uiNormalizeRegion(code)
	}
	return uiServiceAvailable, uiNormalizeRegion(code)
}

// uiCheckChatGpt folds the iOS and web results into one entry, as FlClash does.
func uiCheckChatGpt(env uiServiceEnv) UIServiceCheck {
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
		// Being turned away as an API client is what a reachable region looks like.
		return uiServiceAvailable
	default:
		return uiServiceFailed
	}
}

func uiCheckYouTubePremium(env uiServiceEnv) UIServiceCheck {
	result := env.get("https://www.youtube.com/premium?hl=en", uiServiceHtmlMaxBody)
	item := uiItemFrom(result)
	if !uiAnswered(item, result) {
		return item
	}
	item.Status, item.Region = uiYouTubeStatus(result)
	return item
}

func uiYouTubeStatus(result *uiServiceResult) (string, string) {
	region := ""
	for _, pattern := range uiYouTubeRegionPatterns {
		if code := uiFirstSubmatch(pattern, result.Body); code != "" {
			region = uiNormalizeRegion(code)
			break
		}
	}
	switch {
	case uiBodyContains(result,
		"youtube premium is not available in your country",
		"premium is not available in your country",
		"premium is not available in your region"):
		return uiServiceUnsupportedRegion, region
	case result.StatusCode >= 200 && result.StatusCode < 300 &&
		uiBodyContains(result, "youtube premium", "ad-free", `"browseid":"spunlimited"`):
		return uiServiceAvailable, region
	default:
		return uiStatusFromCode(result.StatusCode), region
	}
}

func uiCheckSpotify(env uiServiceEnv) UIServiceCheck {
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

// uiSpotifyRegion prefers the country Spotify redirected the request into.
func uiSpotifyRegion(result *uiServiceResult) string {
	if final, err := url.Parse(result.Url); err == nil {
		segments := strings.Split(strings.Trim(final.Path, "/"), "/")
		if first := segments[0]; first != "" && first != "api" {
			return strings.Split(first, "-")[0]
		}
	}
	return uiFirstSubmatch(uiSpotifyCountryPattern, result.Body)
}

func uiCheckTikTok(env uiServiceEnv) UIServiceCheck {
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

func uiCheckBilibili(env uiServiceEnv) UIServiceCheck {
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

func uiBilibiliEpisode(env uiServiceEnv, link string) UIServiceCheck {
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

func uiCheckDiscord(env uiServiceEnv) UIServiceCheck {
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

func uiCheckPrimeVideo(env uiServiceEnv) UIServiceCheck {
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

func uiCheckNetflix(env uiServiceEnv) UIServiceCheck {
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
	item.Status = uiNetflixTitleStatus(first.StatusCode, second.StatusCode)
	if item.Status == uiServiceAvailable {
		item.Region = uiNetflixRegion(env)
	}
	return item
}

func uiNetflixTitleStatus(first, second int) string {
	served := func(code int) bool { return code == http.StatusOK || code == http.StatusMovedPermanently }
	switch {
	case first == http.StatusNotFound && second == http.StatusNotFound:
		return uiServiceOriginalsOnly
	case first == http.StatusForbidden || second == http.StatusForbidden:
		return uiServiceUnsupportedRegion
	case served(first) || served(second):
		return uiServiceAvailable
	default:
		return uiStatusFromCode(first)
	}
}

// uiNetflixRegion reads the country out of the redirect Netflix would send a
// visitor to, so the redirect is not followed.
func uiNetflixRegion(env uiServiceEnv) string {
	result := env.do(uiServiceRequest{method: http.MethodGet, url: "https://www.netflix.com/title/80018499", noRedirect: true})
	if result == nil || result.Error != "" || result.header == nil {
		return ""
	}
	segments := strings.Split(result.header.Get("Location"), "/")
	if len(segments) < 4 {
		return ""
	}
	return uiNormalizeRegion(strings.Split(segments[3], "-")[0])
}

func uiNetflixFromCdn(env uiServiceEnv) UIServiceCheck {
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

// uiCheckDisneyPlus walks the browser hand-shake: register a device, exchange
// the assertion for a refresh token, then ask the session which region it
// landed in.
func uiCheckDisneyPlus(env uiServiceEnv) UIServiceCheck {
	device := env.do(uiServiceRequest{
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
	form := "grant_type=urn:ietf:params:oauth:grant-type:token-exchange" +
		"&latitude=0&longitude=0&platform=browser" +
		"&subject_token=" + url.QueryEscape(assertion) +
		"&subject_token_type=urn:bamtech:params:oauth:token-type:device"
	token := env.do(uiServiceRequest{
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
	session := env.do(uiServiceRequest{
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
	item.Status, item.Region = uiDisneySessionStatus(session.Body)
	return item
}

func uiDisneySessionStatus(body string) (string, string) {
	region := uiNormalizeRegion(uiFirstSubmatch(uiDisneyCountryPattern, body))
	if region == "" {
		return uiServiceUnavailable, ""
	}
	switch uiFirstSubmatch(uiDisneySupportedPattern, body) {
	case "true":
		return uiServiceAvailable, region
	case "false":
		return uiServiceComingSoon, region
	default:
		return uiServiceFailed, region
	}
}

// ---------------------------------------------------------------------------
// Regions (FlClash core/region.go)
// ---------------------------------------------------------------------------

// Five bytes per entry, alpha-3 then alpha-2, sorted by alpha-3.
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

// uiNormalizeRegion folds a country code to alpha-2, or "" when it is not one.
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
