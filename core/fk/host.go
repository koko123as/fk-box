package fk

// The app side of fk-core. Under gomobile the app handed Go callback objects
// (UISettingsStore, UIEventSink) and libbox read sing-box itself; fk-core
// runs as its own process, so everything Go asks of the app goes through one
// Host: reverse calls over the protocol and UI events
// (docs/kernel-isolation.md section 8). cmd/fk-core implements it over the
// active protocol connection.
//
// Reverse calls the app answers:
//
//	settings.getBool    [key, default bool]   -> bool
//	settings.getLong    [key, default int64]  -> int64
//	settings.getString  [key, default string] -> string
//	singbox.checkConfig [content]             -> null, or the error
//	singbox.formatConfig [content]            -> string
//	singbox.hasTunInbound [content]           -> bool
//
// The sing-box calls are answered by the official libbox in the app
// (Libbox.checkConfig, Libbox.formatConfig, Libbox.hasTunInbound): fk-core
// never parses a sing-box profile itself.

import (
	"crypto/tls"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"
)

// Host is the app, as fk-core reaches it.
type Host interface {
	// Call is a reverse call to the app: method with its arguments as a JSON
	// array; the app's result as JSON, or its error.
	Call(method string, args ...any) (json.RawMessage, error)
	// Emit raises a UI event (protocol/methods.json "events").
	Emit(kind string, payload string)
}

var (
	hostAccess sync.RWMutex
	host       Host
)

// SetHost installs (or, with nil, removes) the app. Settings and UI events
// go to it from then on.
func SetHost(h Host) {
	hostAccess.Lock()
	host = h
	hostAccess.Unlock()
	if h == nil {
		SetUISettingsStore(nil)
		SetUIEventSink(nil)
		return
	}
	SetUISettingsStore(hostSettingsStore{})
	SetUIEventSink(hostEventSink{})
}

func currentHost() (Host, error) {
	hostAccess.RLock()
	h := host
	hostAccess.RUnlock()
	if h == nil {
		return nil, errors.New("the app is not connected")
	}
	return h, nil
}

// hostCall runs a reverse call and decodes its result into result (nil
// ignores it).
func hostCall(result any, method string, args ...any) error {
	h, err := currentHost()
	if err != nil {
		return err
	}
	raw, err := h.Call(method, args...)
	if err != nil {
		return err
	}
	if result == nil || len(raw) == 0 {
		return nil
	}
	if err = json.Unmarshal(raw, result); err != nil {
		return fmt.Errorf("%s: invalid answer: %w", method, err)
	}
	return nil
}

// hostSettingsStore is UISettingsStore over the host: a failed call answers
// the default, as a missing key does.
type hostSettingsStore struct{}

func (hostSettingsStore) GetBool(key string, defaultValue bool) bool {
	value := defaultValue
	if hostCall(&value, "settings.getBool", key, defaultValue) != nil {
		return defaultValue
	}
	return value
}

func (hostSettingsStore) GetLong(key string, defaultValue int64) int64 {
	value := defaultValue
	if hostCall(&value, "settings.getLong", key, defaultValue) != nil {
		return defaultValue
	}
	return value
}

func (hostSettingsStore) GetString(key string, defaultValue string) string {
	value := defaultValue
	if hostCall(&value, "settings.getString", key, defaultValue) != nil {
		return defaultValue
	}
	return value
}

// hostEventSink is UIEventSink over Host.Emit.
type hostEventSink struct{}

func (hostEventSink) Emit(kind string, payload string) {
	if h, err := currentHost(); err == nil {
		h.Emit(kind, payload)
	}
}

// ---- sing-box, answered by the official libbox in the app ----

// CheckConfig is libbox's CheckConfig, run by the app.
func CheckConfig(content string) error {
	return hostCall(nil, "singbox.checkConfig", content)
}

// FormatConfig is libbox's FormatConfig, run by the app.
func FormatConfig(content string) (*StringBox, error) {
	var formatted string
	if err := hostCall(&formatted, "singbox.formatConfig", content); err != nil {
		return nil, err
	}
	return wrapString(formatted), nil
}

// HasTunInbound is libbox's HasTunInbound, run by the app.
func HasTunInbound(content string) (bool, error) {
	var has bool
	if err := hostCall(&has, "singbox.hasTunInbound", content); err != nil {
		return false, err
	}
	return has, nil
}

// HasCoreTunInbound is libbox's HasTunInbound for a sing-box profile as
// fk-box runs it: the options libbox does not know (experimental.http_debug,
// experimental.http_rewrite) are dropped first (singbox_host.go).
func HasCoreTunInbound(content string) (bool, error) {
	content, err := singBoxHostContent(content)
	if err != nil {
		return false, fmt.Errorf("remove retired capture options: %w", err)
	}
	return HasTunInbound(content)
}

// ---- results ----

// StringBox is a string result, as libbox returned it.
type StringBox struct {
	Value string
}

func wrapString(value string) *StringBox {
	return &StringBox{Value: value}
}

// ---- paths and versions the process was started with ----

var (
	pathsAccess sync.RWMutex
	libraryDir  string
	filesDir    string
	workingDir  string
	tempDir     string

	coreVersion    = "dev"
	singBoxVersion string
)

// SetPaths sets the directories fk-core works with: libDir holds the kernel
// executables (the app's nativeLibraryDir: libxray.so, libmihomo.so,
// libxrayshare.so), files is the app's files directory, working the external
// working directory (libbox's Setup WorkingPath, where the geo data and
// mihomo's home live) and temp the directory for temporary files ("" is the
// system's).
func SetPaths(libDir, files, working, temp string) {
	pathsAccess.Lock()
	libraryDir, filesDir, workingDir, tempDir = libDir, files, working, temp
	pathsAccess.Unlock()
}

// SetVersion sets fk-core's version (hello's coreVersion) and the version of
// the official sing-box libbox the app ships (the sing-box subscription
// User-Agent).
func SetVersion(core string, singBox string) {
	pathsAccess.Lock()
	coreVersion, singBoxVersion = core, singBox
	pathsAccess.Unlock()
}

// Version is fk-core's version.
func Version() string {
	pathsAccess.RLock()
	defer pathsAccess.RUnlock()
	return coreVersion
}

func singBoxLibboxVersion() string {
	pathsAccess.RLock()
	defer pathsAccess.RUnlock()
	if singBoxVersion == "" {
		return "unknown"
	}
	return singBoxVersion
}

// coreWorkingPath is the app's working directory (libbox's sWorkingPath).
func coreWorkingPath() string {
	pathsAccess.RLock()
	defer pathsAccess.RUnlock()
	return workingDir
}

func coreFilesPath() string {
	pathsAccess.RLock()
	defer pathsAccess.RUnlock()
	return filesDir
}

func coreTempPath() string {
	pathsAccess.RLock()
	defer pathsAccess.RUnlock()
	if tempDir == "" {
		return os.TempDir()
	}
	return tempDir
}

// kernelExecutable is the path of an official kernel executable shipped in
// the app's native library directory.
func kernelExecutable(name string) (string, error) {
	pathsAccess.RLock()
	dir := libraryDir
	pathsAccess.RUnlock()
	if dir == "" {
		return "", errors.New("the native library directory is not set")
	}
	return strings.TrimRight(dir, "/") + "/" + name, nil
}

// ---- HTTP ----

// httpClient is the client for fk-box's own requests (geo data,
// subscriptions, WebDAV, update checks, probes): TLS 1.2 or newer with every
// secure cipher suite and no keep-alive, as the former fkhost.NewHTTPClient. The
// roots are Go's system roots (on Android Go reads /system/etc/security/cacerts).
type httpClient struct {
	Client    http.Client
	Transport http.Transport
	tls       tls.Config
}

const httpTLSHandshakeTimeout = 15 * time.Second // sing-box C.TCPTimeout

// newHTTPClient returns a client without a timeout; set Client.Timeout.
func newHTTPClient() *httpClient {
	c := new(httpClient)
	c.Client.Transport = &c.Transport
	c.Transport.ForceAttemptHTTP2 = true
	c.Transport.TLSHandshakeTimeout = httpTLSHandshakeTimeout
	c.Transport.TLSClientConfig = &c.tls
	c.Transport.DisableKeepAlives = true
	// fk-core is outside the VPN (decision D1): while a kernel runs, its
	// requests go through the kernel's local inbound and follow the
	// profile's rules, as they did inside the VPN.
	c.Transport.Proxy = serviceProxy
	c.tls.MinVersion = tls.VersionTLS12
	for _, suite := range tls.CipherSuites() {
		c.tls.CipherSuites = append(c.tls.CipherSuites, suite.ID)
	}
	return c
}

// Close releases idle connections.
func (c *httpClient) Close() {
	c.Transport.CloseIdleConnections()
}

// FileHost is a Host whose reverse calls may answer with a descriptor
// (the TUN, vpn.openTun).
type FileHost interface {
	CallFile(method string, args ...any) (json.RawMessage, *os.File, error)
}
