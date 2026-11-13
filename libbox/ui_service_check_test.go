package libbox

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

// A rule run against canned answers: each URL maps to one result.
func uiServiceFake(answers map[string]*uiServiceResult) uiServiceEnv {
	return uiServiceEnv{
		ctx:     context.Background(),
		timeout: time.Second,
		send: func(_ context.Context, _ time.Duration, req uiServiceRequest) *uiServiceResult {
			for prefix, answer := range answers {
				if strings.HasPrefix(req.url, prefix) {
					copied := *answer
					if copied.Url == "" {
						copied.Url = req.url
					}
					return &copied
				}
			}
			return &uiServiceResult{Url: req.url, Error: uiProbeErrorFailed}
		},
	}
}

func uiServiceRun(t *testing.T, name string, answers map[string]*uiServiceResult) UIServiceCheck {
	t.Helper()
	for _, checker := range uiServiceCheckers {
		if checker.name == name {
			return runUIServiceCheck(context.Background(), checker, uiServiceFake(answers))
		}
	}
	t.Fatalf("no checker %s", name)
	return UIServiceCheck{}
}

func TestUIServiceCheckersMatchFlClash(t *testing.T) {
	want := "google,github,youtube,chatgpt,claude,gemini,netflix,disney-plus,prime-video,spotify,tiktok,discord,bilibili"
	if got := UIServiceNames(); got != want {
		t.Fatalf("services %s", got)
	}
}

func TestUIServiceRules(t *testing.T) {
	ok := func(body string) *uiServiceResult { return &uiServiceResult{StatusCode: 200, Delay: 42, Body: body} }
	cases := []struct {
		name    string
		answers map[string]*uiServiceResult
		status  string
		region  string
	}{
		{"google", map[string]*uiServiceResult{"https://www.google.com/": {StatusCode: 204, Delay: 30}}, uiServiceAvailable, ""},
		{"github", map[string]*uiServiceResult{"https://github.com/": {StatusCode: 429, Delay: 30}}, uiServiceRestricted, ""},
		{"github", map[string]*uiServiceResult{"https://github.com/": {Error: uiProbeErrorTimeout}}, uiServiceTimeout, ""},
		{"claude", map[string]*uiServiceResult{"https://claude.ai/": ok("fl=1\nloc=JP\n")}, uiServiceAvailable, "JP"},
		{"claude", map[string]*uiServiceResult{"https://claude.ai/": ok("loc=HK\n")}, uiServiceUnsupportedRegion, "HK"},
		{"gemini", map[string]*uiServiceResult{"https://gemini.google.com": ok(`xx,2,1,200,"USA"yy`)}, uiServiceAvailable, "US"},
		{"gemini", map[string]*uiServiceResult{"https://gemini.google.com": ok(`xx,2,1,200,"CHN"yy`)}, uiServiceUnsupportedRegion, "CN"},
		{"chatgpt", map[string]*uiServiceResult{
			"https://ios.chat.openai.com/": ok("Request is not allowed. Please try again later."),
			"https://chat.openai.com/":     ok("loc=SG\n"),
			"https://api.openai.com/":      ok(`{"cookie_consent":false}`),
		}, uiServiceAvailable, "SG"},
		{"chatgpt", map[string]*uiServiceResult{
			"https://ios.chat.openai.com/": ok("Request is not allowed. Please try again later."),
			"https://chat.openai.com/":     ok("loc=CN\n"),
			"https://api.openai.com/":      ok(`{"error":"unsupported_country"}`),
		}, uiServiceUnsupportedRegion, "CN"},
		{"chatgpt", map[string]*uiServiceResult{"https://ios.chat.openai.com/": ok("You may be connected to a disallowed ISP")}, uiServiceDisallowedIsp, ""},
		{"youtube", map[string]*uiServiceResult{"https://www.youtube.com/": ok(`"GL":"TW" YouTube Premium ad-free`)}, uiServiceAvailable, "TW"},
		{"youtube", map[string]*uiServiceResult{"https://www.youtube.com/": ok(`"GL":"CN" Premium is not available in your country`)}, uiServiceUnsupportedRegion, "CN"},
		{"spotify", map[string]*uiServiceResult{"https://www.spotify.com/": {StatusCode: 200, Delay: 5, Url: "https://www.spotify.com/jp-ja/api/x", Body: "{}"}}, uiServiceAvailable, "JP"},
		{"spotify", map[string]*uiServiceResult{"https://www.spotify.com/": {StatusCode: 403, Delay: 5}}, uiServiceUnsupportedRegion, ""},
		{"tiktok", map[string]*uiServiceResult{"https://www.tiktok.com/": ok(`"webapp.app-context":{"region":"SG","x":1}`)}, uiServiceAvailable, "SG"},
		{"discord", map[string]*uiServiceResult{"https://discord.com/": ok(`{"country_code":"US"}`)}, uiServiceAvailable, "US"},
		{"bilibili", map[string]*uiServiceResult{"https://api.bilibili.com/": ok(`{"code":0,"message":"0"}`)}, uiServiceAvailable, "CN"},
		{"bilibili", map[string]*uiServiceResult{"https://api.bilibili.com/": ok(`{"code":-10403}`)}, uiServiceUnsupportedRegion, ""},
		{"prime-video", map[string]*uiServiceResult{"https://www.primevideo.com": ok(`"currentTerritory":"DE"`)}, uiServiceAvailable, "DE"},
		{"prime-video", map[string]*uiServiceResult{"https://www.primevideo.com": ok(`isServiceRestricted`)}, uiServiceUnsupportedRegion, ""},
		{"netflix", map[string]*uiServiceResult{"https://api.fast.com/": ok(`{"targets":[{"location":{"country":"JP"}}]}`)}, uiServiceAvailable, "JP"},
		{"netflix", map[string]*uiServiceResult{"https://api.fast.com/": {StatusCode: 403, Delay: 3}}, uiServiceBlocked, ""},
		{"netflix", map[string]*uiServiceResult{
			"https://api.fast.com/":                  ok("not json"),
			"https://www.netflix.com/title/81280792": {StatusCode: 404, Delay: 3},
			"https://www.netflix.com/title/70143836": {StatusCode: 404, Delay: 3},
		}, uiServiceOriginalsOnly, ""},
	}
	for index, c := range cases {
		item := uiServiceRun(t, c.name, c.answers)
		if item.Name != c.name || item.Status != c.status || item.Region != c.region || item.CheckedAt == 0 {
			t.Fatalf("case %d (%s): %+v, want %s %q", index, c.name, item, c.status, c.region)
		}
		if item.Status == uiServiceAvailable && item.DelayMillis <= 0 {
			t.Fatalf("case %d (%s): no delay", index, c.name)
		}
	}
	if status, region := uiDisneySessionStatus(`{"countryCode":"GB","inSupportedLocation":true}`); status != uiServiceAvailable || region != "GB" {
		t.Fatalf("disney %s %s", status, region)
	}
	if status, _ := uiDisneySessionStatus(`{"countryCode":"KR","inSupportedLocation":false}`); status != uiServiceComingSoon {
		t.Fatalf("disney coming soon %s", status)
	}
	if status := uiNetflixTitleStatus(403, 200); status != uiServiceUnsupportedRegion {
		t.Fatalf("netflix %s", status)
	}
	for in, out := range map[string]string{"usa": "US", "HKG": "HK", "jp": "JP", "XYZ": "", "1A": "", "": ""} {
		if got := uiNormalizeRegion(in); got != out {
			t.Fatalf("region %q -> %q", in, got)
		}
	}
}

func TestUIServiceTimeoutRetriesWithinBudget(t *testing.T) {
	calls := 0
	env := uiServiceEnv{ctx: context.Background(), timeout: time.Millisecond, send: func(context.Context, time.Duration, uiServiceRequest) *uiServiceResult {
		calls++
		if calls == 1 {
			return &uiServiceResult{Error: uiProbeErrorTimeout}
		}
		return &uiServiceResult{StatusCode: 204, Delay: 7}
	}}
	item := runUIServiceCheck(context.Background(), uiServiceCheckers[0], env)
	if calls != 2 || item.Status != uiServiceAvailable || item.DelayMillis != 7 {
		t.Fatalf("calls=%d item=%+v", calls, item)
	}
}

func TestUIServiceSendThroughProbe(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/post":
			body, _ := io.ReadAll(r.Body)
			if r.Method != http.MethodPost || r.Header.Get("authorization") != "x" || string(body) != "payload" {
				w.WriteHeader(400)
				return
			}
			_, _ = w.Write([]byte(`{"ok":true}`))
		case "/redirect":
			http.Redirect(w, r, "/landing/jp-ja/", http.StatusFound)
		case "/landing/jp-ja/":
			_, _ = w.Write([]byte("landed"))
		case "/scan":
			_, _ = w.Write([]byte(strings.Repeat("a", 100*1024) + "MARK" + strings.Repeat("b", 400*1024)))
		}
	}))
	defer server.Close()
	probe := NewUIDirectEgressProbe()
	defer probe.Close()
	env := uiServiceEnv{ctx: context.Background(), timeout: 5 * time.Second, send: probe.serviceSend}

	post := env.do(uiServiceRequest{method: http.MethodPost, url: server.URL + "/post", headers: map[string]string{"authorization": "x"}, body: []byte("payload"), maxBody: 1024})
	if post.Error != "" || post.StatusCode != 200 || post.Body != `{"ok":true}` || post.Delay < 1 {
		t.Fatalf("post: %+v", post)
	}
	followed := env.get(server.URL+"/redirect", 1024)
	if followed.StatusCode != 200 || followed.Body != "landed" || !strings.HasSuffix(followed.Url, "/landing/jp-ja/") {
		t.Fatalf("redirect: %+v", followed)
	}
	held := env.do(uiServiceRequest{url: server.URL + "/redirect", noRedirect: true})
	if held.StatusCode != http.StatusFound || held.header.Get("Location") != "/landing/jp-ja/" || held.Body != "" {
		t.Fatalf("no redirect: %+v", held)
	}
	scanned := env.scan(server.URL+"/scan", func(tail string) bool { return strings.Contains(tail, "MARK") })
	if !strings.Contains(scanned.Body, "MARK") || len(scanned.Body) >= 500*1024 {
		t.Fatalf("scan read %d bytes", len(scanned.Body))
	}
	headers := env.get(server.URL+"/scan", 0)
	if headers.StatusCode != 200 || headers.Body != "" {
		t.Fatalf("headers only: %+v", headers.StatusCode)
	}
	if _, err := probe.ServiceCheck("nope", 1000); err == nil {
		t.Fatal("unknown service accepted")
	}
	if _, err := probe.ServiceCheck("google", 0); err == nil {
		t.Fatal("zero timeout accepted")
	}
}
