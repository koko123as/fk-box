package fk

// Xray and mihomo as child processes of fk-core (docs/kernel-isolation.md
// sections 4, 5 and 9, phase P3). The app asks for a start with
// serviceStart; fk-core opens the TUN through the app (reverse call
// vpn.openTun, the descriptor comes back over SCM_RIGHTS), writes the
// configuration the kernel runs, starts the official executable with the TUN
// as descriptor 3, waits until its control interface answers and serves the
// StartedService gRPC API on the app's command.sock (core_service_grpc.go).
// A kernel that dies is restarted with backoff; one that keeps dying turns
// the service FATAL and the app stops it (reverse call service.fatal).

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	daemonpb "github.com/koko123as/fk-box/core/internal/daemonpb"
	"github.com/koko123as/fk-box/core/kernel"
)

const (
	serviceTunMTU      = 9000
	serviceReadyXray   = 30 * time.Second
	serviceReadyMihomo = 90 * time.Second
	serviceStopGrace   = 5 * time.Second
	restartWindow      = 10 * time.Minute
	restartLimit       = 5
	serviceTestURL     = "https://www.gstatic.com/generate_204"
)

var (
	xrayTunInet4 = netip.MustParsePrefix("172.19.0.1/30")
	tunInet6     = netip.MustParsePrefix("fdfe:dcba:9876::1/126")
	// mihomo's TUN address is the first /30 of its fake-ip range.
	mihomoDefaultFakeIP = netip.MustParsePrefix("198.18.0.1/16")
)

// ServiceOptions are the app's settings for a start (JSON from the app).
type ServiceOptions struct {
	// DNS is the current underlying network's DNS servers.
	DNS []string `json:"dns"`
	// CommandSecret is libbox's command server secret, checked on
	// command.sock as libbox does ("" for none).
	CommandSecret string `json:"commandSecret"`
}

// serviceKernel is the running kernel.
type serviceKernel struct {
	core      string
	dir       string
	config    string
	content   string
	tun       *os.File
	process   atomic.Pointer[kernel.Process]
	startedAt time.Time
	logLevel  daemonpb.LogLevel

	mihomo *kernel.Mihomo
	xray   *kernel.Xray
	layout *xrayLayout

	// The local test/return inbound: 127.0.0.1:port with per-user routing
	// (Xray) or a mixed listener (mihomo).
	localPort     int
	localPassword string

	delayAccess sync.Mutex
	delays      map[string]serviceDelay

	restarts []time.Time
	stopped  chan struct{}
}

type serviceDelay struct {
	delay int32
	at    time.Time
}

var (
	// serviceLifecycle serializes starts and stops; serviceAccess guards
	// activeKernel and serviceDNS only.
	serviceLifecycle sync.Mutex
	serviceAccess    sync.RWMutex
	activeKernel     *serviceKernel
	serviceDNS       []string
	serviceSecret    string
)

func runningKernel() *serviceKernel {
	serviceAccess.RLock()
	defer serviceAccess.RUnlock()
	return activeKernel
}

func randomHex(n int) string {
	buffer := make([]byte, n)
	_, _ = rand.Read(buffer)
	return hex.EncodeToString(buffer)
}

func freeLocalPort() (int, error) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return 0, err
	}
	defer listener.Close()
	return listener.Addr().(*net.TCPAddr).Port, nil
}

// ServiceStart starts (or reloads) content on core ("xray" or "mihomo") with
// the routing rules applied and options (ServiceOptions as JSON). The rules
// never keep the profile from running: when they cannot be compiled, or the
// kernel does not start with them, it starts without them and the result
// says why (CoreRoutingResult.Error, Fallback).
func ServiceStart(core string, content string, rules string, options string) (*CoreRoutingResult, error) {
	if core != CoreXray && core != CoreMihomo {
		return nil, fmt.Errorf("serviceStart: %s is not run by fk-core", core)
	}
	var parsed ServiceOptions
	if strings.TrimSpace(options) != "" {
		if err := json.Unmarshal([]byte(options), &parsed); err != nil {
			return nil, fmt.Errorf("serviceStart: options: %w", err)
		}
	}
	serviceLifecycle.Lock()
	defer serviceLifecycle.Unlock()
	serviceAccess.Lock()
	serviceDNS = cleanDNS(parsed.DNS)
	serviceSecret = parsed.CommandSecret
	serviceAccess.Unlock()

	serviceStatus.set(daemonpb.ServiceStatus_STARTING, "")
	result := &CoreRoutingResult{Content: content}
	withRules := content
	if strings.TrimSpace(rules) != "" {
		routed, err := ApplyCoreRouting(core, content, rules)
		if err != nil {
			result.Error = err.Error()
		} else {
			result = routed
			withRules = routed.Content
		}
	}
	err := startKernelLocked(core, withRules)
	if err != nil && withRules != content {
		ruleErr := err
		if err = startKernelLocked(core, content); err == nil {
			result.Content = content
			result.Error = ruleErr.Error()
			result.Fallback = true
		}
	}
	if err != nil {
		serviceStatus.set(daemonpb.ServiceStatus_IDLE, "")
		CloseCommandServer()
		notifyServiceChange()
		return nil, err
	}
	serviceStatus.set(daemonpb.ServiceStatus_STARTED, "")
	notifyServiceChange()
	return result, nil
}

// startKernelLocked stops the running kernel and starts content on core.
func startKernelLocked(core string, content string) error {
	stopKernelLocked()
	if err := ensureCommandServer(); err != nil {
		return fmt.Errorf("command server: %w", err)
	}
	running := &serviceKernel{core: core, content: content, delays: map[string]serviceDelay{}, stopped: make(chan struct{})}
	running.dir = coreAssetDir(coreWorkingPath(), core)
	port, err := freeLocalPort()
	if err != nil {
		return err
	}
	running.localPort = port
	running.localPassword = randomHex(12)
	var inet4 netip.Prefix
	switch core {
	case CoreXray:
		inet4 = xrayTunInet4
		if err = writeXrayConfig(running); err != nil {
			return err
		}
	case CoreMihomo:
		if inet4, err = writeMihomoConfig(running); err != nil {
			return err
		}
	}
	tun, err := openServiceTun(inet4)
	if err != nil {
		return fmt.Errorf("open tun: %w", err)
	}
	running.tun = tun
	if err = startProcess(running); err != nil {
		_ = tun.Close()
		cleanupKernel(running)
		return fmt.Errorf("start %s: %w", core, err)
	}
	running.startedAt = time.Now()
	serviceAccess.Lock()
	activeKernel = running
	serviceAccess.Unlock()
	serviceLog.write(daemonpb.LogLevel_INFO, core+" started")
	go superviseKernel(running)
	return nil
}

// openServiceTun asks the app for the VPN TUN with inet4 and the shared IPv6
// address; the app adds the DNS address (inet4's next address), routes and
// per-app lists.
func openServiceTun(inet4 netip.Prefix) (*os.File, error) {
	h, err := currentHost()
	if err != nil {
		return nil, err
	}
	files, ok := h.(FileHost)
	if !ok {
		return nil, errors.New("the app cannot pass descriptors")
	}
	spec := map[string]any{
		"inet4": inet4.String(),
		"inet6": tunInet6.String(),
		"dns":   inet4.Addr().Next().String(),
		"mtu":   serviceTunMTU,
	}
	_, file, err := files.CallFile("vpn.openTun", spec)
	if err != nil {
		return nil, err
	}
	if file == nil {
		return nil, errors.New("the app returned no descriptor")
	}
	return file, nil
}

func startProcess(running *serviceKernel) error {
	var spec kernel.Spec
	switch running.core {
	case CoreXray:
		path, err := kernelExecutable(kernelXray)
		if err != nil {
			return err
		}
		socket := filepath.Join(running.dir, "xray-api.sock")
		_ = os.Remove(socket)
		// The client is made once: gRPC reconnects to a restarted kernel.
		if running.xray == nil {
			client, err := kernel.DialXray(socket)
			if err != nil {
				return err
			}
			running.xray = client
		}
		client := running.xray
		spec = kernel.Spec{
			Name: "xray",
			Path: path,
			Args: []string{"run", "-c", running.config},
			Env:  []string{"XRAY_TUN_FD=3", "XRAY_LOCATION_ASSET=" + GeoDataDir()},
			Dir:  running.dir,
			Ready: func(ctx context.Context) error {
				_, err := client.Goroutines(ctx)
				return err
			},
			ReadyTimeout: serviceReadyXray,
			OnLog:        func(line string) { serviceLog.writeXray(line) },
		}
	case CoreMihomo:
		path, err := kernelExecutable(kernelMihomo)
		if err != nil {
			return err
		}
		socket := filepath.Join(running.dir, "mihomo.sock")
		_ = os.Remove(socket)
		if running.mihomo == nil {
			running.mihomo = kernel.NewMihomo(socket)
		}
		client := running.mihomo
		spec = kernel.Spec{
			Name: "mihomo",
			Path: path,
			Args: []string{"-d", running.dir, "-f", running.config},
			Env:  []string{"SSL_CERT_DIR=/system/etc/security/cacerts"},
			Dir:  running.dir,
			Ready: func(ctx context.Context) error {
				_, err := client.Version(ctx)
				return err
			},
			ReadyTimeout: serviceReadyMihomo,
			OnLog:        func(line string) { serviceLog.writeMihomo(line) },
		}
	}
	spec.Tun = running.tun
	process, err := kernel.Start(context.Background(), spec)
	if err != nil {
		return err
	}
	running.process.Store(process)
	return nil
}

// superviseKernel restarts a kernel that exited on its own, with backoff, at
// most restartLimit times in restartWindow; after that the service is FATAL.
func superviseKernel(running *serviceKernel) {
	for {
		process := running.process.Load()
		select {
		case <-running.stopped:
			return
		case <-process.Done():
		}
		if process.Stopped() {
			return
		}
		reason := process.ExitText()
		if process.Killed() {
			reason = "killed by the system (low memory)\n" + reason
			writeKernelOOMReport(running, process)
		}
		serviceLog.write(daemonpb.LogLevel_ERROR, running.core+" exited: "+reason)
		now := time.Now()
		var recent []time.Time
		for _, at := range running.restarts {
			if now.Sub(at) < restartWindow {
				recent = append(recent, at)
			}
		}
		running.restarts = recent
		if len(recent) >= restartLimit {
			failKernel(running, fmt.Sprintf("%s keeps exiting: %s", running.core, firstLine(reason)))
			return
		}
		delay := time.Second << len(recent)
		running.restarts = append(running.restarts, now)
		serviceStatus.set(daemonpb.ServiceStatus_STARTING, "")
		select {
		case <-running.stopped:
			return
		case <-time.After(delay):
		}
		serviceLifecycle.Lock()
		if runningKernel() != running {
			serviceLifecycle.Unlock()
			return
		}
		err := startProcess(running)
		serviceLifecycle.Unlock()
		if err != nil {
			serviceLog.write(daemonpb.LogLevel_ERROR, "restart "+running.core+": "+err.Error())
			// startProcess left no process: the old one counts again.
			continue
		}
		serviceStatus.set(daemonpb.ServiceStatus_STARTED, "")
		serviceLog.write(daemonpb.LogLevel_WARN, running.core+" restarted")
		notifyServiceChange()
	}
}

func failKernel(running *serviceKernel, message string) {
	serviceLifecycle.Lock()
	if runningKernel() == running {
		stopKernelLocked()
	}
	serviceLifecycle.Unlock()
	serviceStatus.set(daemonpb.ServiceStatus_FATAL, message)
	notifyServiceChange()
	go func() { _ = hostCall(nil, "service.fatal", message) }()
}

func firstLine(text string) string {
	line, _, _ := strings.Cut(strings.TrimSpace(text), "\n")
	return line
}

// writeKernelOOMReport records a kernel the system killed: the time, its
// last memory reading, its last log lines and (mihomo) the last connection
// snapshot.
func writeKernelOOMReport(running *serviceKernel, process *kernel.Process) {
	dir := filepath.Join(coreFilesPath(), "oom_reports")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return
	}
	var report strings.Builder
	fmt.Fprintf(&report, "kernel: %s\ntime: %s\nlast memory: %d bytes\n\n", running.core, time.Now().Format(time.RFC3339), lastKernelMemory.Load())
	report.WriteString(strings.Join(process.Tail(), "\n"))
	if snapshot := lastMihomoConnections.Load(); snapshot != nil {
		report.WriteString("\n\nconnections:\n")
		encoded, _ := json.MarshalIndent(snapshot, "", "  ")
		report.Write(encoded)
	}
	name := filepath.Join(dir, fmt.Sprintf("%s-%d.txt", running.core, time.Now().Unix()))
	_ = os.WriteFile(name, []byte(report.String()), 0o600)
}

// stopKernelLocked stops the running kernel, if any, and waits for it.
func stopKernelLocked() {
	serviceAccess.Lock()
	running := activeKernel
	activeKernel = nil
	serviceAccess.Unlock()
	if running == nil {
		return
	}
	close(running.stopped)
	if process := running.process.Load(); process != nil {
		process.Stop(serviceStopGrace)
	}
	if running.tun != nil {
		_ = running.tun.Close()
	}
	cleanupKernel(running)
	serviceLog.write(daemonpb.LogLevel_INFO, running.core+" stopped")
	notifyServiceChange()
}

func cleanupKernel(running *serviceKernel) {
	if running.xray != nil {
		_ = running.xray.Close()
	}
	if running.mihomo != nil {
		running.mihomo.Close()
	}
	lastMihomoConnections.Store(nil)
}

// ServiceStop stops the running kernel.
func ServiceStop() error {
	serviceLifecycle.Lock()
	defer serviceLifecycle.Unlock()
	serviceStatus.set(daemonpb.ServiceStatus_STOPPING, "")
	stopKernelLocked()
	serviceStatus.set(daemonpb.ServiceStatus_IDLE, "")
	CloseCommandServer()
	return nil
}

// ServiceRunning is the core fk-core runs ("" for none).
func ServiceRunning() string {
	if running := runningKernel(); running != nil {
		return running.core
	}
	return ""
}

// ServiceDefaultNetworkChanged is called with the new underlying network's
// DNS servers (JSON array). mihomo resolves through them (its linux build
// cannot read Android's system DNS), so its configuration is regenerated and
// reloaded; Xray resolves through Android's resolver and needs nothing.
func ServiceDefaultNetworkChanged(dns string) error {
	var servers []string
	if strings.TrimSpace(dns) != "" {
		if err := json.Unmarshal([]byte(dns), &servers); err != nil {
			return err
		}
	}
	servers = cleanDNS(servers)
	serviceAccess.Lock()
	changed := strings.Join(servers, ",") != strings.Join(serviceDNS, ",")
	serviceDNS = servers
	serviceAccess.Unlock()
	if !changed {
		return nil
	}
	serviceLifecycle.Lock()
	defer serviceLifecycle.Unlock()
	running := runningKernel()
	if running == nil || running.core != CoreMihomo || len(servers) == 0 {
		return nil
	}
	if _, err := writeMihomoConfig(running); err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	return running.mihomo.Reload(ctx, running.config)
}

func currentDNS() []string {
	serviceAccess.RLock()
	defer serviceAccess.RUnlock()
	return append([]string(nil), serviceDNS...)
}

func cleanDNS(servers []string) []string {
	var clean []string
	for _, server := range servers {
		server = strings.TrimSpace(server)
		if addr, err := netip.ParseAddr(server); err == nil && !addr.IsLoopback() && !addr.IsUnspecified() {
			clean = append(clean, addr.WithZone("").String())
		}
	}
	return clean
}

// ServiceMemory is the running kernel process's resident memory in bytes
// (0 when none runs).
func ServiceMemory() int64 {
	running := runningKernel()
	if running == nil || running.process.Load() == nil {
		return 0
	}
	rss, _ := kernel.Memory(running.process.Load().Pid())
	lastKernelMemory.Store(rss)
	return rss
}

// ServiceLocalProxy is the running kernel's local proxy for fk-core's and
// the app's own requests (the app is outside its VPN): an
// "http://user:password@127.0.0.1:port" URL whose traffic follows the
// profile's ordinary rules, or "" when no kernel runs.
func ServiceLocalProxy() string {
	if running := runningKernel(); running != nil {
		return fmt.Sprintf("http://%s:%s@127.0.0.1:%d", localReturnUser, running.localPassword, running.localPort)
	}
	if local := singBoxLocalProxy(); local != nil {
		return fmt.Sprintf("http://%s:%s@127.0.0.1:%d", localReturnUser, local.password, local.port)
	}
	return ""
}

// serviceProxy is http.Transport.Proxy for fk-core's own requests: the
// running kernel's local inbound, or none (direct) when no kernel runs.
func serviceProxy(*http.Request) (*url.URL, error) {
	local := ServiceLocalProxy()
	if local == "" {
		return nil, nil
	}
	return url.Parse(local)
}
