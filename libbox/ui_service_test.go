package libbox

import (
	"context"
	"net/http"
	"strings"
	"sync"
	"testing"
	"time"
)

// Fake responses keyed by URL; unknown URLs fail like a refused dial.
func fakeUIServiceSender(t *testing.T, responses map[string]*uiServiceResult) (uiServiceSender, func() []string) {
	var mu sync.Mutex
	var seen []string
	return func(ctx context.Context, req uiServiceRequest) *uiServiceResult {
			mu.Lock()
			seen = append(seen, req.method+" "+req.url)
			mu.Unlock()
			if req.headers["User-Agent"] == "" {
				t.Errorf("%s sent without a user agent", req.url)
			}
			response, ok := responses[req.url]
			if !ok {
				return &uiServiceResult{URL: req.url, Error: uiProbeErrorFailed}
			}
			copied := *response
			if copied.URL == "" {
				copied.URL = req.url
			}
			if req.maxBody > 0 && int64(len(copied.Body)) > req.maxBody {
				copied.Body = copied.Body[:req.maxBody]
			}
			if req.maxBody == 0 {
				copied.Body = ""
			}
			return &copied
		}, func() []string {
			mu.Lock()
			defer mu.Unlock()
			return append([]string(nil), seen...)
		}
}

func TestUIServiceRules(t *testing.T) {
	responses := map[string]*uiServiceResult{
		"https://www.google.com/generate_204":                    {StatusCode: 204, Delay: 42},
		"https://github.com/":                                    {StatusCode: 429},
		"https://claude.ai/cdn-cgi/trace":                        {StatusCode: 200, Body: "ip=1.1.1.1\nloc=HK\n"},
		"https://gemini.google.com":                              {StatusCode: 200, Body: strings.Repeat("x", 40000) + `,2,1,200,"JPN"]`},
		"https://ios.chat.openai.com/":                           {StatusCode: 403, Body: "Request is not allowed. Please try again later."},
		"https://chat.openai.com/cdn-cgi/trace":                  {StatusCode: 200, Body: "loc=US\n"},
		"https://api.openai.com/compliance/cookie_requirements":  {StatusCode: 200, Body: `{}`},
		"https://www.tiktok.com/":                                {StatusCode: 200, Body: `{"webapp.app-context":{"region":"SG","x":1}}`},
		"https://discord.com/api/v9/auth/location-metadata":      {StatusCode: 200, Body: `{"country_code":"de"}`},
		"https://www.primevideo.com":                             {StatusCode: 200, Body: `"currentTerritory":"GB"`},
		"https://www.youtube.com/premium?hl=en":                  {StatusCode: 200, Body: `"GL":"TW" YouTube Premium`},
		"https://www.spotify.com/api/content/v1/country-selector?platform=web&format=json": {StatusCode: 200, URL: "https://www.spotify.com/fr-fr/api", Body: `{}`},
	}
	send, _ := fakeUIServiceSender(t, responses)
	items := runUIServiceChecks(context.Background(), []string{"google", "github", "claude", "gemini", "chatgpt", "tiktok", "discord", "prime-video", "youtube", "spotify", "unknown"}, time.Second, send)
	want := map[string][2]string{
		"google":      {uiServiceAvailable, ""},
		"github":      {uiServiceRestricted, ""},
		"claude":      {uiServiceUnsupportedRegion, "HK"},
		"gemini":      {uiServiceAvailable, "JP"},
		"chatgpt":     {uiServiceAvailable, "US"},
		"tiktok":      {uiServiceAvailable, "SG"},
		"discord":     {uiServiceAvailable, "DE"},
		"prime-video": {uiServiceAvailable, "GB"},
		"youtube":     {uiServiceAvailable, "TW"},
		"spotify":     {uiServiceAvailable, "FR"},
	}
	if len(items) != len(want) {
		t.Fatalf("got %d items, want %d (unknown names are skipped)", len(items), len(want))
	}
	for _, item := range items {
		expected := want[item.Name]
		if item.Status != expected[0] || item.Region != expected[1] {
			t.Errorf("%s = %s/%s, want %s/%s", item.Name, item.Status, item.Region, expected[0], expected[1])
		}
		if item.CheckedAt <= 0 {
			t.Errorf("%s has no check time", item.Name)
		}
	}
	if items[0].Delay != 42 {
		t.Errorf("google delay = %d", items[0].Delay)
	}
}

func TestUIServiceNetflixAndBilibili(t *testing.T) {
	responses := map[string]*uiServiceResult{
		"https://api.fast.com/netflix/speedtest/v2?https=true&token=YXNkZmFzZGxmbnNkYWZoYXNkZmhrYWxm&urlCount=5": {StatusCode: 500, Body: "x"},
		"https://www.netflix.com/title/81280792": {StatusCode: 404},
		"https://www.netflix.com/title/70143836": {StatusCode: 404},
		"https://api.bilibili.com/pgc/player/web/playurl?avid=82846771&qn=0&type=&otype=json&ep_id=307247&fourk=1&fnver=0&fnval=16&module=bangumi":                {StatusCode: 200, Body: `{"code":-10403}`},
		"https://api.bilibili.com/pgc/player/web/playurl?avid=18281381&cid=29892777&qn=0&type=&otype=json&ep_id=183799&fourk=1&fnver=0&fnval=16&module=bangumi": {StatusCode: 200, Body: `{"code":0}`},
	}
	send, _ := fakeUIServiceSender(t, responses)
	items := runUIServiceChecks(context.Background(), []string{"netflix", "bilibili"}, time.Second, send)
	if items[0].Status != uiServiceOriginalsOnly {
		t.Errorf("netflix = %s", items[0].Status)
	}
	if items[1].Status != uiServiceAvailable || items[1].Region != "" {
		t.Errorf("bilibili = %s/%s", items[1].Status, items[1].Region)
	}
}

func TestUIServiceTimeoutRetriesOnce(t *testing.T) {
	var mu sync.Mutex
	calls := 0
	send := func(ctx context.Context, req uiServiceRequest) *uiServiceResult {
		mu.Lock()
		calls++
		mu.Unlock()
		return &uiServiceResult{URL: req.url, Error: uiProbeErrorTimeout, Delay: 9}
	}
	items := runUIServiceChecks(context.Background(), []string{"google"}, 10*time.Millisecond, send)
	if items[0].Status != uiServiceTimeout || items[0].Delay != 0 {
		t.Fatalf("got %+v", items[0])
	}
	if calls != 2 {
		t.Fatalf("timeout retried %d times", calls-1)
	}
}

func TestUIServiceRegionAndBody(t *testing.T) {
	for code, want := range map[string]string{"jpn": "JP", "us": "US", "XXX": "", "u1": "", "HKG": "HK"} {
		if got := uiNormalizeRegion(code); got != want {
			t.Errorf("uiNormalizeRegion(%q) = %q, want %q", code, got, want)
		}
	}
	body := strings.Repeat("a", 100*1024) + "MARK" + strings.Repeat("b", 100*1024)
	scanned := readUIServiceBody(strings.NewReader(body), uiServiceScanMaxBody, func(tail string) bool { return strings.Contains(tail, "MARK") })
	if !strings.Contains(scanned, "MARK") || len(scanned) >= len(body) {
		t.Fatalf("scan read %d bytes", len(scanned))
	}
	if got := uiStatusFromCode(http.StatusUnavailableForLegalReasons); got != uiServiceRestricted {
		t.Fatalf("451 = %s", got)
	}
}
