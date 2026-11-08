package libbox

import (
	"bytes"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"

	mihomoRouter "github.com/metacubex/mihomo/component/geodata/router"
	"google.golang.org/protobuf/proto"
)

func testGeoIPData(t *testing.T, prefix uint32) []byte {
	t.Helper()
	data, err := proto.Marshal(&mihomoRouter.GeoIPList{Entry: []*mihomoRouter.GeoIP{{
		CountryCode: "CN",
		Cidr:        []*mihomoRouter.CIDR{{Ip: []byte{1, 0, 1, 0}, Prefix: prefix}},
	}}})
	if err != nil {
		t.Fatal(err)
	}
	return data
}

func testGeoSiteData(t *testing.T) []byte {
	t.Helper()
	data, err := proto.Marshal(&mihomoRouter.GeoSiteList{Entry: []*mihomoRouter.GeoSite{{
		CountryCode: "CN",
		Domain:      []*mihomoRouter.Domain{{Type: mihomoRouter.Domain_Full, Value: "example.cn"}},
	}}})
	if err != nil {
		t.Fatal(err)
	}
	return data
}

func TestCoreGeoResourceSpecs(t *testing.T) {
	for name, file := range map[string]string{
		GeoResourceGeoIP: "geoip.dat", GeoResourceGeoSite: "geosite.dat",
		GeoResourceMMDB: "geoip.metadb", GeoResourceASN: "ASN.mmdb",
	} {
		if GeoResourceFileName(name) != file {
			t.Errorf("%s file = %q, want %q", name, GeoResourceFileName(name), file)
		}
		if GeoResourceDefaultURL(name) == "" {
			t.Errorf("%s has no default URL", name)
		}
	}
	if GeoResourceFileName("nope") != "" {
		t.Error("unknown resource has a file name")
	}
}

func writeGeoTestFile(t *testing.T, data []byte) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "data")
	if err := os.WriteFile(path, data, 0o644); err != nil {
		t.Fatal(err)
	}
	return path
}

func updateGeoForTest(dir string, name string, link string) (*GeoUpdateResult, error) {
	return NewGeoUpdate().Fetch(dir, name, link, "", "", "")
}

func TestCoreGeoVerifyRejectsWrongData(t *testing.T) {
	html := writeGeoTestFile(t, []byte("<!DOCTYPE html><html><body>rate limited</body></html>"))
	for _, name := range []string{GeoResourceGeoIP, GeoResourceGeoSite, GeoResourceMMDB, GeoResourceASN} {
		if err := geoResourceSpecs[name].verify(html); err == nil {
			t.Errorf("%s accepted an HTML page", name)
		}
	}
	if err := verifyGeoIPFile(writeGeoTestFile(t, testGeoIPData(t, 24))); err != nil {
		t.Errorf("valid GeoIP rejected: %v", err)
	}
	if err := verifyGeoSiteFile(writeGeoTestFile(t, testGeoSiteData(t))); err != nil {
		t.Errorf("valid GeoSite rejected: %v", err)
	}
	if err := verifyGeoIPFile(writeGeoTestFile(t, nil)); err == nil {
		t.Error("empty GeoIP accepted")
	}
	// A cut-off download frames as a truncated field and is refused, even
	// though the first entry is intact.
	full := append(testGeoIPData(t, 24), testGeoIPData(t, 16)...)
	if err := verifyGeoIPFile(writeGeoTestFile(t, full[:len(full)-3])); err == nil {
		t.Error("truncated GeoIP accepted")
	}
	// Entries without a country code or address are skipped, a later valid
	// one is found.
	empty, _ := proto.Marshal(&mihomoRouter.GeoIPList{Entry: []*mihomoRouter.GeoIP{{CountryCode: "XX"}, {Cidr: []*mihomoRouter.CIDR{{Ip: []byte{1, 2, 3, 4}}}}}})
	if err := verifyGeoIPFile(writeGeoTestFile(t, empty)); err == nil {
		t.Error("GeoIP without usable entries accepted")
	}
	if err := verifyGeoIPFile(writeGeoTestFile(t, append(empty, testGeoIPData(t, 8)...))); err != nil {
		t.Errorf("GeoIP with a later usable entry rejected: %v", err)
	}
}

func TestCoreGeoUpdateKeepsOldFileOnFailure(t *testing.T) {
	dir := t.TempDir()
	old := testGeoIPData(t, 16)
	target := filepath.Join(dir, "geoip.dat")
	if err := os.WriteFile(target, old, 0o644); err != nil {
		t.Fatal(err)
	}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/error":
			w.WriteHeader(http.StatusBadGateway)
		case "/html":
			_, _ = w.Write([]byte("<html>not a database</html>"))
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer server.Close()
	for _, path := range []string{"/error", "/html"} {
		if _, err := updateGeoForTest(dir, GeoResourceGeoIP, server.URL+path); err == nil {
			t.Fatalf("%s: update succeeded", path)
		}
		current, err := os.ReadFile(target)
		if err != nil || !bytes.Equal(current, old) {
			t.Fatalf("%s: old file changed (err %v)", path, err)
		}
	}
	if _, err := updateGeoForTest(dir, "nope", server.URL); err == nil {
		t.Fatal("unknown resource updated")
	}
	if _, err := updateGeoForTest(dir, GeoResourceGeoIP, "ftp://example.com/geoip.dat"); err == nil {
		t.Fatal("non-HTTP address accepted")
	}
	entries, _ := os.ReadDir(dir)
	if len(entries) != 1 {
		t.Fatalf("temporary files left behind: %v", entries)
	}
}

func TestCoreGeoUpdateReplacesAndSkips(t *testing.T) {
	dir := t.TempDir()
	fresh := testGeoSiteData(t)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write(fresh)
	}))
	defer server.Close()
	// mihomo's own spelling of the file, as it downloads it.
	variant := filepath.Join(dir, "GeoSite.dat")
	if err := os.WriteFile(variant, []byte("stale"), 0o644); err != nil {
		t.Fatal(err)
	}
	result, err := updateGeoForTest(dir, GeoResourceGeoSite, server.URL+"/geosite.dat")
	if err != nil {
		t.Fatal(err)
	}
	if result.Skipped || result.Size != int64(len(fresh)) || len(result.Sha256) != 64 {
		t.Fatalf("unexpected result: %+v", result)
	}
	current, err := os.ReadFile(filepath.Join(dir, "geosite.dat"))
	if err != nil || !bytes.Equal(current, fresh) {
		t.Fatalf("file not replaced (err %v)", err)
	}
	if err := verifyGeoSiteFile(filepath.Join(dir, "geosite.dat")); err != nil {
		t.Fatal(err)
	}
	// On a case-insensitive file system the variant is the same file.
	if info, err := os.Stat(variant); err == nil {
		target, _ := os.Stat(filepath.Join(dir, "geosite.dat"))
		if !os.SameFile(info, target) {
			t.Fatal("stale GeoSite.dat left beside geosite.dat")
		}
	}
	again, err := updateGeoForTest(dir, GeoResourceGeoSite, server.URL+"/geosite.dat")
	if err != nil || !again.Skipped || again.Sha256 != result.Sha256 {
		t.Fatalf("unchanged download not skipped: %+v, %v", again, err)
	}
}

func TestCoreGeoConditionalUpdate(t *testing.T) {
	dir := t.TempDir()
	fresh := testGeoIPData(t, 24)
	var requests, conditional int
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests++
		if r.Header.Get("If-None-Match") == `"v1"` {
			conditional++
			w.WriteHeader(http.StatusNotModified)
			return
		}
		w.Header().Set("ETag", `"v1"`)
		w.Header().Set("Last-Modified", "Sun, 08 Nov 2026 00:00:00 GMT")
		_, _ = w.Write(fresh)
	}))
	defer server.Close()
	first, err := updateGeoForTest(dir, GeoResourceGeoIP, server.URL)
	if err != nil || first.Skipped || first.Etag != `"v1"` || first.LastModified == "" {
		t.Fatalf("first update: %+v, %v", first, err)
	}
	second, err := NewGeoUpdate().Fetch(dir, GeoResourceGeoIP, server.URL, first.Sha256, first.Etag, first.LastModified)
	if err != nil || !second.Skipped || second.Sha256 != first.Sha256 || second.Size != int64(len(fresh)) || conditional != 1 {
		t.Fatalf("conditional update: %+v, %v (conditional requests %d)", second, err, conditional)
	}
	// The recorded hash no longer matches the file (someone else replaced
	// it): no conditional request, the file is downloaded and restored.
	if err := os.WriteFile(filepath.Join(dir, "geoip.dat"), testGeoIPData(t, 8), 0o644); err != nil {
		t.Fatal(err)
	}
	third, err := NewGeoUpdate().Fetch(dir, GeoResourceGeoIP, server.URL, first.Sha256, first.Etag, first.LastModified)
	if err != nil || third.Skipped || conditional != 1 {
		t.Fatalf("stale state update: %+v, %v", third, err)
	}
	if current, _ := os.ReadFile(filepath.Join(dir, "geoip.dat")); !bytes.Equal(current, fresh) {
		t.Fatal("file not restored")
	}
	if requests != 3 {
		t.Fatalf("requests = %d", requests)
	}
}

func TestCoreGeoUpdateCancel(t *testing.T) {
	dir := t.TempDir()
	started := make(chan struct{})
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Length", "1000000")
		_, _ = w.Write(make([]byte, 1000))
		w.(http.Flusher).Flush()
		close(started)
		<-r.Context().Done()
	}))
	defer server.Close()
	update := NewGeoUpdate()
	go func() {
		<-started
		update.Cancel()
	}()
	if _, err := update.Fetch(dir, GeoResourceGeoIP, server.URL, "", "", ""); err == nil {
		t.Fatal("cancelled update succeeded")
	}
	if entries, _ := os.ReadDir(dir); len(entries) != 0 {
		t.Fatalf("files left behind: %v", entries)
	}
}

func TestCoreGeoUpdateRejectsShortBody(t *testing.T) {
	dir := t.TempDir()
	data := testGeoSiteData(t)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Hijack to send fewer bytes than announced.
		conn, buffer, _ := w.(http.Hijacker).Hijack()
		_, _ = buffer.WriteString("HTTP/1.1 200 OK\r\nContent-Length: 999\r\n\r\n")
		_, _ = buffer.Write(data)
		_ = buffer.Flush()
		_ = conn.Close()
	}))
	defer server.Close()
	if _, err := updateGeoForTest(dir, GeoResourceGeoSite, server.URL); err == nil {
		t.Fatal("short body accepted")
	}
	if entries, _ := os.ReadDir(dir); len(entries) != 0 {
		t.Fatalf("files left behind: %v", entries)
	}
}
