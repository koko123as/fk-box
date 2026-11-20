package fk

import (
	"bytes"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
)

// The request sequence and status rules of the app's BoxWebDAV.kt.
func TestUIWebDAVBackupTransfers(t *testing.T) {
	var access sync.Mutex
	var requests []string
	var stored []byte
	collection := false
	status := map[string]int{}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		access.Lock()
		defer access.Unlock()
		requests = append(requests, r.Method+" "+r.URL.Path)
		if user, password, ok := r.BasicAuth(); !ok || user != "account" || password != "secret" {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		if code := status[r.Method]; code != 0 {
			w.WriteHeader(code)
			return
		}
		switch r.Method {
		case http.MethodOptions:
			w.WriteHeader(http.StatusOK)
		case "MKCOL":
			if collection {
				w.WriteHeader(http.StatusMethodNotAllowed)
				return
			}
			collection = true
			w.WriteHeader(http.StatusCreated)
		case http.MethodPut:
			stored, _ = io.ReadAll(r.Body)
			w.WriteHeader(http.StatusCreated)
		case http.MethodGet:
			_, _ = w.Write(stored)
		}
	}))
	defer server.Close()
	directory, file := server.URL+"/dav/B-box/", server.URL+"/dav/B-box/fk%20box.json"
	if err := UIWebDAVCheck(server.URL+"/dav/", "account", "secret"); err != nil {
		t.Fatal(err)
	}
	// The second upload finds the directory (405) and still writes.
	for _, body := range []string{`{"format":"bbox-1"}`, `{"format":"bbox-1","profiles":[]}`} {
		if err := UIWebDAVUpload(directory, file, "account", "secret", []byte(body)); err != nil {
			t.Fatal(err)
		}
	}
	content, err := UIWebDAVDownload(file, "account", "secret")
	if err != nil || !bytes.Equal(content, []byte(`{"format":"bbox-1","profiles":[]}`)) {
		t.Fatalf("content=%q err=%v", content, err)
	}
	want := "OPTIONS /dav/,MKCOL /dav/B-box/,PUT /dav/B-box/fk box.json,MKCOL /dav/B-box/,PUT /dav/B-box/fk box.json,GET /dav/B-box/fk box.json"
	if got := strings.Join(requests, ","); got != want {
		t.Fatalf("requests %s", got)
	}

	for _, test := range []struct {
		run  func() error
		set  map[string]int
		want string
	}{
		{func() error { return UIWebDAVCheck(server.URL, "account", "wrong") }, nil, "WebDAV OPTIONS failed: HTTP 401"},
		{func() error { return UIWebDAVUpload(directory, file, "account", "secret", nil) }, map[string]int{"MKCOL": 409}, "WebDAV MKCOL failed: HTTP 409"},
		{func() error { return UIWebDAVUpload(directory, file, "account", "secret", nil) }, map[string]int{"PUT": 405}, "WebDAV PUT failed: HTTP 405"},
		{func() error { _, err := UIWebDAVDownload(file, "account", "secret"); return err }, map[string]int{"GET": 404}, "WebDAV GET failed: HTTP 404"},
		// A 2xx other than 200 is no download.
		{func() error { _, err := UIWebDAVDownload(file, "account", "secret"); return err }, map[string]int{"GET": 204}, "WebDAV download failed: HTTP 204"},
		{func() error { return UIWebDAVCheck(server.URL, "account", "secret") }, map[string]int{"OPTIONS": 207}, ""},
	} {
		access.Lock()
		status = test.set
		requests = nil
		access.Unlock()
		err := test.run()
		if test.want == "" && err != nil || test.want != "" && (err == nil || err.Error() != test.want) {
			t.Fatalf("got %v, want %q", err, test.want)
		}
	}
	// An oversized backup is refused before any request.
	access.Lock()
	status, requests = nil, nil
	access.Unlock()
	if err := UIWebDAVUpload(directory, file, "account", "secret", make([]byte, uiBackupLimit+1)); err == nil || err.Error() != "Backup exceeds 64 MiB" || len(requests) != 0 {
		t.Fatalf("got %v after %v", err, requests)
	}
}
