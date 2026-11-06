package libbox

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestUISubscriptionHeaders(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.UserAgent() != "clash.meta" {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		user, password, ok := r.BasicAuth()
		if r.URL.Path == "/auth" && (!ok || user != "name" || password != "secret") {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		// Providers send lower-case names; keep them out of canonical form.
		w.Header()["subscription-userinfo"] = []string{" upload=1; download=2; total=10; expire=0 "}
		w.Header()["PROFILE-UPDATE-INTERVAL"] = []string{"12"}
		w.Header().Set("Profile-Web-Page-Url", "https://example.com/panel")
		_, _ = w.Write([]byte("proxies: []\n"))
	}))
	defer server.Close()
	response, err := UISubscriptionGet(server.URL+"/sub", "clash.meta")
	if err != nil {
		t.Fatal(err)
	}
	if response.Content != "proxies: []\n" || response.UserInfo != "upload=1; download=2; total=10; expire=0" ||
		response.UpdateInterval != "12" || response.WebPage != "https://example.com/panel" {
		t.Fatalf("unexpected response: %+v", response)
	}
	authURL := strings.Replace(server.URL, "http://", "http://name:secret@", 1) + "/auth"
	if _, err := UISubscriptionGet(authURL, "clash.meta"); err != nil {
		t.Fatalf("URL credentials: %v", err)
	}
}

func TestUISubscriptionMissingHeadersAndErrors(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/missing" {
			w.WriteHeader(http.StatusNotFound)
			_, _ = w.Write([]byte("no such subscription"))
			return
		}
		_, _ = w.Write([]byte("{}"))
	}))
	defer server.Close()
	response, err := UISubscriptionGet(server.URL, "sing-box")
	if err != nil || response.Content != "{}" || response.UserInfo != "" || response.UpdateInterval != "" || response.WebPage != "" {
		t.Fatalf("response=%+v err=%v", response, err)
	}
	if _, err := UISubscriptionGet(server.URL+"/missing", "sing-box"); err == nil || !strings.Contains(err.Error(), "404") || !strings.Contains(err.Error(), "no such subscription") {
		t.Fatalf("expected HTTP 404 error, got %v", err)
	}
	for _, link := range []string{"ftp://example.com/sub", "https://", "not a url"} {
		if _, err := UISubscriptionGet(link, "sing-box"); err == nil {
			t.Fatalf("%s: expected an address error", link)
		}
	}
}
