// Package mobile is the Android face of the fk-box kernel (stage 6 of the
// single-kernel design, docs/architecture/brain-android.md): gomobile binds
// it into fkhost.aar (Java package io.fkbox.mobile, libfkhost.so, arm64-v8a
// only), which replaces the three-core libbox.aar.
//
// One host per process: the first Start creates the Go host (package host:
// its own box.Context, daemon.StartedService and Android PlatformInterface)
// and attaches it to the Rust brain (package brainffi). The brain imports the
// profile, generates the sing-box configuration and starts the host; every
// connection is decided by the brain's decide through fkbrain, every DNS
// query answered by the brain through fkdns. The VpnService implements
// Platform: protect, the TUN descriptor, the connection owner and the
// network state. No CommandServer, no local listener, no subprocess.
//
// The API keeps to the types gomobile binds: strings, numbers, booleans,
// errors, structs of those and interfaces. Lists cross as newline-separated
// text or JSON (control path only, never per connection).
package mobile

import (
	"encoding/json"
	"os"
	"runtime"
	"runtime/debug"
	"strings"
	"sync"

	"github.com/koko123as/fk-box/apps/android/fkhost/brainffi"
	"github.com/koko123as/fk-box/apps/android/fkhost/host"
	"github.com/koko123as/fk-box/apps/android/fkhost/plugin"

	C "github.com/sagernet/sing-box/constant"
	E "github.com/sagernet/sing/common/exceptions"
)

var (
	access   sync.Mutex
	instance *host.Host
	bridge   = &platformBridge{}
	running  bool
)

// Setup prepares the process for the host: Go's temporary directory (the
// default /data/local/tmp is not writable for apps) and the working
// directory relative paths of sing-box resolve against. Idempotent.
func Setup(workingDir string, tempDir string) error {
	if tempDir != "" {
		if err := os.MkdirAll(tempDir, 0o700); err != nil {
			return err
		}
		if err := os.Setenv("TMPDIR", tempDir); err != nil {
			return err
		}
	}
	if workingDir != "" {
		if err := os.MkdirAll(workingDir, 0o700); err != nil {
			return err
		}
		if err := os.Chdir(workingDir); err != nil {
			return err
		}
	}
	return nil
}

func ensureHost() (*host.Host, error) {
	if instance != nil {
		return instance, nil
	}
	created, err := host.New(host.Options{Bridge: bridge, LogMaxLines: 3000})
	if err != nil {
		return nil, err
	}
	if err := brainffi.Attach(created); err != nil {
		return nil, E.Cause(err, "attach the brain")
	}
	instance = created
	return created, nil
}

// Start imports profile (a subscription: Clash/mihomo YAML, sing-box JSON
// or share links) in the brain and runs it. options is the JSON object of
// the brain's profile options (docs/architecture/brain-android.md: dns,
// group_store, geosite/geoip/mmdb paths, tun settings) or empty. platform
// is the VpnService; it is kept until the next Start.
func Start(platform Platform, profile string, options string) error {
	if platform == nil {
		return E.New("start: no platform")
	}
	access.Lock()
	defer access.Unlock()
	bridge.set(platform)
	if _, err := ensureHost(); err != nil {
		return err
	}
	if err := brainffi.StartProfile(profile, options); err != nil {
		return err
	}
	running = true
	return nil
}

// Reload replaces the running profile without stopping the service. On
// failure before the new configuration was checked the old one keeps
// running; a failure after the check leaves nothing running
// (brain-host.md).
func Reload(profile string, options string) error {
	access.Lock()
	defer access.Unlock()
	if instance == nil || !running {
		return E.New("reload: not running")
	}
	if err := brainffi.ReloadProfile(profile, options); err != nil {
		if instance.Box() == nil {
			running = false
		}
		return err
	}
	return nil
}

// Stop stops the running profile; the host stays attached for the next
// Start. Stopping a stopped host is not an error.
func Stop() error {
	access.Lock()
	defer access.Unlock()
	if instance == nil || !running {
		bridge.set(nil)
		return nil
	}
	running = false
	err := brainffi.Stop()
	bridge.set(nil)
	debug.FreeOSMemory()
	return err
}

// Running reports whether a profile runs.
func Running() bool {
	access.Lock()
	defer access.Unlock()
	return running && instance != nil && instance.Box() != nil
}

// Groups returns the proxy groups as the brain's JSON document
// (docs/architecture/brain-groups.md); an empty list when nothing runs.
func Groups() (string, error) {
	return brainffi.Groups()
}

// SelectGroup selects member in a select group; automatic groups refuse
// (AGENTS.md). Takes effect for new connections at once, no reload.
func SelectGroup(group string, member string) error {
	return brainffi.SelectGroup(group, member)
}

// TestGroup runs a delay test of group (empty: every group) and waits for
// it, up to the group's timeout. Never call it on the UI thread.
func TestGroup(group string) error {
	return brainffi.TestGroup(group)
}

// Stats are the traffic and runtime counters of the running host.
type Stats struct {
	UplinkBytes    int64
	DownlinkBytes  int64
	UplinkRate     int64
	DownlinkRate   int64
	ConnectionsTCP int32
	ConnectionsUDP int32
	MemoryInuse    int64
	Goroutines     int32
	Running        bool
}

// ReadStats reads the counters; rates are per second since the previous
// call (one reader: the service's notification or the dashboard).
func ReadStats() *Stats {
	access.Lock()
	current := instance
	active := running
	access.Unlock()
	if current == nil {
		var memory runtime.MemStats
		runtime.ReadMemStats(&memory)
		return &Stats{MemoryInuse: int64(memory.HeapInuse + memory.StackInuse), Goroutines: int32(runtime.NumGoroutine())}
	}
	stats := current.Stats()
	return &Stats{
		UplinkBytes:    int64(stats.UplinkBytes),
		DownlinkBytes:  int64(stats.DownlinkBytes),
		UplinkRate:     int64(stats.UplinkRate),
		DownlinkRate:   int64(stats.DownlinkRate),
		ConnectionsTCP: int32(stats.ConnectionsTCP),
		ConnectionsUDP: int32(stats.ConnectionsUDP),
		MemoryInuse:    int64(stats.MemoryInuse),
		Goroutines:     int32(stats.Goroutines),
		Running:        active && current.Box() != nil,
	}
}

type logLine struct {
	Level   int    `json:"level"`
	Message string `json:"message"`
}

type logBatch struct {
	Total int       `json:"total"`
	Lines []logLine `json:"lines"`
}

// Logs returns the kernel's log ring (the last 3000 lines) from index from
// on, as JSON {"total": n, "lines": [{"level", "message"}]}; total is the
// ring's size, so a caller asks again with from = total. Levels are
// sing-box's (0 panic .. 6 trace).
func Logs(from int32) string {
	access.Lock()
	current := instance
	access.Unlock()
	batch := logBatch{Lines: []logLine{}}
	if current != nil {
		saved := current.Service().SavedLog()
		batch.Total = len(saved)
		start := int(from)
		if start < 0 || start > len(saved) {
			start = 0
		}
		for _, entry := range saved[start:] {
			batch.Lines = append(batch.Lines, logLine{Level: int(entry.Level), Message: entry.Message})
		}
	}
	data, _ := json.Marshal(batch)
	return string(data)
}

// ClearLogs empties the log ring.
func ClearLogs() {
	access.Lock()
	current := instance
	access.Unlock()
	if current != nil {
		_, _ = current.Service().ClearLogs(nil, nil)
	}
}

// FreeMemory returns Go's idle heap to the system.
func FreeMemory() {
	debug.FreeOSMemory()
}

// Version is the kernel's version line: sing-box and the brain ABI.
func Version() string {
	return C.Version
}

// BrainABI is the brain's C ABI version (brain-ffi.md).
func BrainABI() int32 {
	return int32(plugin.ABIVersion)
}

func splitLines(text string) []string {
	var out []string
	for _, line := range strings.Split(text, "\n") {
		if line = strings.TrimSpace(line); line != "" {
			out = append(out, line)
		}
	}
	return out
}
