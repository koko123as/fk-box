package fk

// Test stand-ins for what fk-core reaches outside its own process.
//
//   - The app (Host): the sing-box reverse calls are answered by a stand-in
//     for the official libbox: checkConfig accepts any JSON object whose
//     outbounds and inbounds have a type, formatConfig re-indents the JSON,
//     hasTunInbound looks for an inbound of type tun. Settings and events are
//     left to each test (SetUISettingsStore, SetUIEventSink), as in legacy.
//   - The official kernels: tests that need libxray.so, libmihomo.so or
//     libxrayshare.so run only when FK_TEST_LIBDIR names a directory holding
//     them (CI's kernels job builds them); otherwise they are skipped.

import (
	"bytes"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"testing"
)

func TestMain(m *testing.M) {
	hostAccess.Lock()
	host = testHost{}
	hostAccess.Unlock()
	SetPaths(os.Getenv("FK_TEST_LIBDIR"), "", "", "")
	os.Exit(m.Run())
}

type testHost struct{}

func (testHost) Emit(string, string) {}

func (testHost) Call(method string, args ...any) (json.RawMessage, error) {
	content, _ := args[0].(string)
	switch method {
	case "singbox.checkConfig":
		if err := testSingBoxCheck(content); err != nil {
			return nil, err
		}
		return json.RawMessage("null"), nil
	case "singbox.formatConfig":
		var out bytes.Buffer
		if err := json.Indent(&out, []byte(content), "", "  "); err != nil {
			return nil, fmt.Errorf("decode config: %w", err)
		}
		out.WriteByte('\n')
		return json.Marshal(out.String())
	case "singbox.hasTunInbound":
		options, err := testSingBoxDecode(content)
		if err != nil {
			return nil, err
		}
		for _, inbound := range options.Inbounds {
			if inbound["type"] == "tun" {
				return json.RawMessage("true"), nil
			}
		}
		return json.RawMessage("false"), nil
	}
	return nil, fmt.Errorf("%s: not answered in tests", method)
}

type testSingBoxOptions struct {
	Inbounds  []map[string]any `json:"inbounds"`
	Outbounds []map[string]any `json:"outbounds"`
}

func testSingBoxDecode(content string) (*testSingBoxOptions, error) {
	var options testSingBoxOptions
	decoder := json.NewDecoder(bytes.NewReader([]byte(content)))
	if err := decoder.Decode(&options); err != nil {
		return nil, fmt.Errorf("decode config: %w", err)
	}
	return &options, nil
}

func testSingBoxCheck(content string) error {
	var root map[string]json.RawMessage
	if err := json.Unmarshal([]byte(content), &root); err != nil {
		return fmt.Errorf("decode config: %w", err)
	}
	for _, retired := range []string{"http_debug", "http_rewrite"} {
		if bytes.Contains(root["experimental"], []byte(`"`+retired+`"`)) {
			return fmt.Errorf("decode config: experimental: json: unknown field %q", retired)
		}
	}
	options, err := testSingBoxDecode(content)
	if err != nil {
		return err
	}
	for index, inbound := range options.Inbounds {
		if inbound["type"] == nil {
			return fmt.Errorf("initialize inbound[%d]: missing type", index)
		}
	}
	for index, outbound := range options.Outbounds {
		if outbound["type"] == nil {
			return fmt.Errorf("initialize outbound[%d]: missing type", index)
		}
	}
	return nil
}

// kernelsAvailable reports whether FK_TEST_LIBDIR holds the official kernels.
func kernelsAvailable() bool {
	dir := os.Getenv("FK_TEST_LIBDIR")
	if dir == "" {
		return false
	}
	for _, name := range []string{kernelXray, kernelMihomo, kernelXrayShare} {
		if _, err := os.Stat(filepath.Join(dir, name)); err != nil {
			return false
		}
	}
	return true
}

// requireKernels skips a test that needs the official kernels when they are
// not there.
func requireKernels(t *testing.T) {
	t.Helper()
	if !kernelsAvailable() {
		t.Skip("needs libxray.so, libmihomo.so and libxrayshare.so in FK_TEST_LIBDIR")
	}
}

// useTestWorkingDir points the working directory (legacy sWorkingPath) at a
// new temporary directory for one test.
func useTestWorkingDir(t *testing.T) string {
	t.Helper()
	dir := t.TempDir()
	pathsAccess.Lock()
	previous := workingDir
	workingDir = dir
	pathsAccess.Unlock()
	t.Cleanup(func() {
		pathsAccess.Lock()
		workingDir = previous
		pathsAccess.Unlock()
	})
	return dir
}
