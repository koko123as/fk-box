package fk

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
)

// Ported from the app's BoxUpdatesTest (JVM), plus the network flow the app
// could not test on the JVM.

func uiUpdateNames(names ...string) map[string]struct{} {
	set := make(map[string]struct{}, len(names))
	for _, name := range names {
		set[name] = struct{}{}
	}
	return set
}

func TestUIUpdatesLatestDownloadCandidatePointsAtTheTagPage(t *testing.T) {
	candidate := uiUpdateCandidateOf("io.fkbox.app", 750, "1.15.0", "io.fkbox.app", nil, "", nil, uiUpdateAssetPrefix)
	if candidate == nil || candidate.url != "https://github.com/koko123as/fk-box/releases/tag/1.15.0" {
		t.Fatalf("candidate %+v", candidate)
	}
	for _, rejected := range []*uiUpdateCandidate{
		uiUpdateCandidateOf("other.app", 750, "1.15.0", "io.fkbox.app", nil, "", nil, uiUpdateAssetPrefix),
		uiUpdateCandidateOf("io.fkbox.app", 0, "1.15.0", "io.fkbox.app", nil, "", nil, uiUpdateAssetPrefix),
		uiUpdateCandidateOf("io.fkbox.app", 750, " \u3000", "io.fkbox.app", nil, "", nil, uiUpdateAssetPrefix),
	} {
		if rejected != nil {
			t.Fatalf("accepted %+v", rejected)
		}
	}
}

func TestUIUpdatesNewestPrefersHigherCodeThenTheAPICopy(t *testing.T) {
	latest := &uiUpdateCandidate{version: "1.15.0", code: 750, url: "a"}
	api := &uiUpdateCandidate{version: "1.15.0", code: 750, url: "b", notes: "notes", assetNames: uiUpdateNames("fk-box-1.15.0-arm64-v8a.apk")}
	prerelease := &uiUpdateCandidate{version: "1.15.1-alpha.1", code: 751, url: "c", assetNames: uiUpdateNames()}
	if uiUpdateNewest([]*uiUpdateCandidate{latest, api}) != api || uiUpdateNewest([]*uiUpdateCandidate{api, latest}) != api {
		t.Fatal("tie should pick the API copy")
	}
	if uiUpdateNewest([]*uiUpdateCandidate{latest, api, prerelease}) != prerelease || uiUpdateNewest([]*uiUpdateCandidate{latest}) != latest {
		t.Fatal("newest by code")
	}
	other := &uiUpdateCandidate{version: "x", code: 750, url: "d", assetNames: uiUpdateNames()}
	if uiUpdateNewest([]*uiUpdateCandidate{api, other}) != api || uiUpdateNewest(nil) != nil {
		t.Fatal("first of equals wins")
	}
}

func TestUIUpdatesReleaseAssetsUnderEitherPrefix(t *testing.T) {
	// Releases since the rename use fk-box-*; older ones FK-box-*. The current name wins.
	if name, prefix, ok := uiUpdateMetadataAsset([]string{"FK-box-version-metadata.json", "fk-box-version-metadata.json"}); !ok || name != "fk-box-version-metadata.json" || prefix != "fk-box" {
		t.Fatal(name, prefix)
	}
	if name, prefix, ok := uiUpdateMetadataAsset([]string{"FK-box-1.15.0-arm64-v8a.apk", "FK-box-version-metadata.json"}); !ok || name != "FK-box-version-metadata.json" || prefix != "FK-box" {
		t.Fatal(name, prefix)
	}
	if _, _, ok := uiUpdateMetadataAsset([]string{"SFA-1.15.0-arm64-v8a.apk", "version-metadata.json"}); ok {
		t.Fatal("foreign metadata accepted")
	}
	apk := func(c *uiUpdateCandidate, abi string) string {
		name, _ := uiUpdateAPKAsset(c, abi)
		return name
	}
	renamed := &uiUpdateCandidate{version: "2026.11.20", code: 26112001, assetNames: uiUpdateNames("fk-box-2026.11.20-arm64-v8a.apk", "fk-box-version-metadata.json"), assetPrefix: "fk-box"}
	if apk(renamed, "arm64-v8a") != "fk-box-2026.11.20-arm64-v8a.apk" || apk(renamed, "x86_64") != "" {
		t.Fatal("renamed release")
	}
	old := &uiUpdateCandidate{version: "1.15.0", code: 750, assetNames: uiUpdateNames("FK-box-1.15.0-arm64-v8a.apk"), assetPrefix: "FK-box"}
	if apk(old, "arm64-v8a") != "FK-box-1.15.0-arm64-v8a.apk" {
		t.Fatal("old release")
	}
	// latest/download lists no assets: the APK follows the metadata's prefix, ARM64 only.
	latestOld := &uiUpdateCandidate{version: "1.15.0", code: 750, assetPrefix: "FK-box"}
	if apk(latestOld, "arm64-v8a") != "FK-box-1.15.0-arm64-v8a.apk" || apk(latestOld, "armeabi-v7a") != "" {
		t.Fatal("latest old")
	}
	latestNew := &uiUpdateCandidate{version: "2026.11.20", code: 26112001, assetPrefix: "fk-box"}
	if apk(latestNew, "arm64-v8a") != "fk-box-2026.11.20-arm64-v8a.apk" {
		t.Fatal("latest new")
	}
}

func TestUIUpdatesAPIRateLimitIsNamed(t *testing.T) {
	if got := uiUpdateStatusMessage("https://api.github.com/x", 403); got != "Release request failed: GitHub API rate limit (HTTP 403)" {
		t.Fatal(got)
	}
	if got := uiUpdateStatusMessage("https://github.com/x", 404); got != "Release request failed: HTTP 404" {
		t.Fatal(got)
	}
}

func TestUIUpdatesMetadataFieldsReadAsOrgJSON(t *testing.T) {
	read := func(text string) *uiUpdateCandidate {
		metadata, err := uiUpdateJSONObject([]byte(text))
		if err != nil {
			t.Fatal(err)
		}
		return uiUpdateCandidateFrom(metadata, "io.fkbox.app", nil, "", nil, "fk-box")
	}
	// optLong reads numeric strings and decimals; optString turns numbers into text.
	if c := read(`{"application_id":"io.fkbox.app","version_code":"752.9","version_name":2026.1}`); c == nil || c.code != 752 || c.version != "2026.1" {
		t.Fatalf("%+v", c)
	}
	if c := read(`{"application_id":"io.fkbox.app","version_code":7.5e2,"version_name":null} trailing`); c == nil || c.code != 750 || c.version != "null" {
		t.Fatalf("%+v", c)
	}
	for _, text := range []string{
		`{"application_id":"io.fkbox.app","version_code":true,"version_name":"1"}`,
		`{"application_id":"io.fkbox.app","version_code":-1,"version_name":"1"}`,
		`{"application_id":"io.fkbox.app","version_name":"1"}`,
		`{"version_code":1,"version_name":"1"}`,
	} {
		if c := read(text); c != nil {
			t.Fatalf("%s: %+v", text, c)
		}
	}
	for text, want := range map[string]string{
		"<!DOCTYPE html><html>": "Value <!DOCTYPE of type java.lang.String cannot be converted to JSONObject",
		"[1]":                   "Value [1] of type org.json.JSONArray cannot be converted to JSONObject",
		"  ":                    "End of input at character 2 of   ",
		"null":                  "Value null of type org.json.JSONObject$1 cannot be converted to JSONObject",
		"12":                    "Value 12 of type java.lang.Integer cannot be converted to JSONObject",
	} {
		if _, err := uiUpdateJSONObject([]byte(text)); err == nil || err.Error() != want {
			t.Fatalf("%q: %v", text, err)
		}
	}
}

// uiUpdateTestServer serves both sources: latest/download (by prefix) and the REST list.
type uiUpdateTestServer struct {
	latest   map[string]string // metadata asset name -> body; missing is 404
	releases string
	apiCode  int
	requests atomic.Int32
}

func (s *uiUpdateTestServer) start(t *testing.T) {
	var server *httptest.Server
	server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		s.requests.Add(1)
		switch {
		case strings.HasPrefix(r.URL.Path, "/releases/latest/download/"):
			name := strings.TrimPrefix(r.URL.Path, "/releases/latest/download/")
			body, ok := s.latest[name]
			if !ok {
				w.WriteHeader(http.StatusNotFound)
				return
			}
			// GitHub redirects to its asset origin.
			_ = body
			http.Redirect(w, r, "/assets/"+name+"?latest", http.StatusFound)
		case strings.HasPrefix(r.URL.Path, "/assets/"):
			name := strings.TrimPrefix(r.URL.Path, "/assets/")
			if r.URL.RawQuery == "latest" {
				_, _ = w.Write([]byte(s.latest[name]))
				return
			}
			_, _ = w.Write([]byte(s.latest["api:"+name]))
		case r.URL.Path == "/api":
			if s.apiCode != 0 {
				w.WriteHeader(s.apiCode)
				return
			}
			_, _ = w.Write([]byte(strings.ReplaceAll(s.releases, "SERVER", server.URL)))
		default:
			w.WriteHeader(http.StatusTeapot)
		}
	}))
	t.Cleanup(server.Close)
	page, api := uiUpdateReleasesPage, uiUpdateReleasesAPI
	uiUpdateReleasesPage, uiUpdateReleasesAPI = server.URL+"/releases", server.URL+"/api"
	t.Cleanup(func() { uiUpdateReleasesPage, uiUpdateReleasesAPI = page, api })
}

func uiUpdateCheckJSON(t *testing.T, app *uiInstalledApp) map[string]any {
	t.Helper()
	result, err := uiCheckUpdatesFor(app)
	if err != nil {
		t.Fatal(err)
	}
	encoded, _ := json.Marshal(result)
	var decoded map[string]any
	_ = json.Unmarshal(encoded, &decoded)
	return decoded
}

const uiUpdateTestRelease = `[
 {"draft": true, "html_url": "SERVER/draft", "assets": [{"name": "fk-box-version-metadata.json", "browser_download_url": "SERVER/assets/draft.json"}]},
 {"draft": "FALSE", "html_url": "SERVER/releases/tag/2026.11.21-beta", "body": "beta notes",
  "assets": [{"name": "fk-box-2026.11.21-beta-x86_64.apk"}, {"name": "fk-box-version-metadata.json", "browser_download_url": "SERVER/assets/beta.json"}]},
 {"html_url": "SERVER/releases/tag/2026.11.20", "body": null,
  "assets": [{"name": "fk-box-2026.11.20-arm64-v8a.apk"}, {"name": "fk-box-version-metadata.json", "browser_download_url": "SERVER/assets/stable.json"}]},
 {"html_url": "SERVER/old", "assets": [{"name": "FK-box-version-metadata.json", "browser_download_url": "SERVER/assets/broken.json"}]},
 {"html_url": "SERVER/none", "assets": null}
]`

func TestUIUpdatesCheckPrefersTheNewestSource(t *testing.T) {
	server := &uiUpdateTestServer{
		latest: map[string]string{
			"fk-box-version-metadata.json": `{"application_id":"io.fkbox.app","version_code":26112001,"version_name":"2026.11.20"}`,
			"api:draft.json":               `{"application_id":"io.fkbox.app","version_code":99999999,"version_name":"draft"}`,
			"api:beta.json":                `{"application_id":"io.fkbox.app","version_code":26112101,"version_name":"2026.11.21-beta"}`,
			"api:stable.json":              `{"application_id":"io.fkbox.app","version_code":26112001,"version_name":"2026.11.20"}`,
			"api:broken.json":              `not json`,
		},
		releases: uiUpdateTestRelease,
	}
	server.start(t)
	// The prerelease is newest; this device's ABIs list no x86_64, so it has no APK.
	got := uiUpdateCheckJSON(t, &uiInstalledApp{applicationID: "io.fkbox.app", versionCode: 26112001, abis: []string{"arm64-v8a", "armeabi-v7a"}})
	if got["available"] != true || got["hasMetadata"] != true || got["version"] != "2026.11.21-beta" || got["versionCode"] != float64(26112101) ||
		got["notes"] != "beta notes" || !strings.HasSuffix(got["url"].(string), "/releases/tag/2026.11.21-beta") {
		t.Fatalf("%v", got)
	}
	if value, present := got["downloadUrl"]; !present || value != nil {
		t.Fatalf("downloadUrl %v present=%v, want null", value, present)
	}
	got = uiUpdateCheckJSON(t, &uiInstalledApp{applicationID: "io.fkbox.app", versionCode: 26112101, abis: []string{"x86_64"}})
	if got["available"] != false || got["downloadUrl"] != uiUpdateReleasesPage+"/download/2026.11.21-beta/fk-box-2026.11.21-beta-x86_64.apk" {
		t.Fatalf("%v", got)
	}
	// Another app's metadata is no candidate: nothing found is not an error.
	got = uiUpdateCheckJSON(t, &uiInstalledApp{applicationID: "other.app", versionCode: 1, abis: []string{"arm64-v8a"}})
	if len(got) != 3 || got["available"] != false || got["hasMetadata"] != false || got["url"] != uiUpdateReleasesPage {
		t.Fatalf("%v", got)
	}
}

func TestUIUpdatesLatestOnlyWhenTheAPIFails(t *testing.T) {
	server := &uiUpdateTestServer{
		latest:  map[string]string{"FK-box-version-metadata.json": `{"application_id":"io.fkbox.app","version_code":750,"version_name":"1.15.0"}`},
		apiCode: http.StatusForbidden,
	}
	server.start(t)
	got := uiUpdateCheckJSON(t, &uiInstalledApp{applicationID: "io.fkbox.app", versionCode: 700, abis: []string{"armeabi-v7a", "arm64-v8a"}})
	if got["available"] != true || got["notes"] != "" || got["url"] != uiUpdateReleasesPage+"/tag/1.15.0" ||
		got["downloadUrl"] != uiUpdateReleasesPage+"/download/1.15.0/FK-box-1.15.0-arm64-v8a.apk" {
		t.Fatalf("%v", got)
	}
}

func TestUIUpdatesBothSourcesFailReportsTheFirst(t *testing.T) {
	server := &uiUpdateTestServer{latest: map[string]string{}, apiCode: http.StatusForbidden}
	server.start(t)
	_, err := uiCheckUpdatesFor(&uiInstalledApp{applicationID: "io.fkbox.app", versionCode: 1})
	if err == nil || err.Error() != "Release request failed: HTTP 404" {
		t.Fatalf("got %v", err)
	}
	// A 404 is final: each prefix asked once, the API once.
	if n := server.requests.Load(); n != 3 {
		t.Fatalf("%d requests", n)
	}
	server.apiCode, server.latest = http.StatusBadGateway, map[string]string{"fk-box-version-metadata.json": `<html>`}
	server.requests.Store(0)
	_, err = uiCheckUpdatesFor(&uiInstalledApp{applicationID: "io.fkbox.app", versionCode: 1})
	if err == nil || err.Error() != "Value <html> of type java.lang.String cannot be converted to JSONObject" {
		t.Fatalf("got %v", err)
	}
	// latest: redirect + asset; API: a 502 is tried twice.
	if n := server.requests.Load(); n != 4 {
		t.Fatalf("%d requests", n)
	}
}

func TestUIUpdatesDroppedConnectionIsRetriedOnce(t *testing.T) {
	var calls atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if calls.Add(1) == 1 {
			connection, _, _ := w.(http.Hijacker).Hijack()
			_ = connection.Close()
			return
		}
		_, _ = w.Write([]byte("ok"))
	}))
	defer server.Close()
	body, err := uiUpdateGet(server.URL)
	if err != nil || string(body) != "ok" || calls.Load() != 2 {
		t.Fatalf("body=%q err=%v calls=%d", body, err, calls.Load())
	}
}

func TestUIUpdatesMethodNeedsAppInfo(t *testing.T) {
	uiAppInfoAccess.Lock()
	saved := uiAppInfo
	uiAppInfo = nil
	uiAppInfoAccess.Unlock()
	defer func() {
		uiAppInfoAccess.Lock()
		uiAppInfo = saved
		uiAppInfoAccess.Unlock()
	}()
	if _, err := UIInvoke("checkUpdates", "{}"); err == nil {
		t.Fatal("checkUpdates without app information")
	}
	SetUIAppInfo("io.fkbox.app", 42, "arm64-v8a\n\narmeabi-v7a\n")
	info, err := uiInstalledAppInfo()
	if err != nil || info.applicationID != "io.fkbox.app" || info.versionCode != 42 || strings.Join(info.abis, ",") != "arm64-v8a,armeabi-v7a" {
		t.Fatalf("%+v %v", info, err)
	}
}
