package libbox

// sing-box's start and stop on the CommandServer host (Android): fk-box runs
// sing-box itself (fkhost.SingBox); libbox's own service runs nothing. What
// a start needs around box.New is done here: the config snapshot for crash
// reports, the app's per-app and auto-redirect overrides on the tun inbound,
// the OOM killer service, the OOM recorder's reload window, the HTTP
// capture/rewrite stop and the rewrite activation, and the interruption of
// a slow start by a stop.

import (
	"context"
	"path/filepath"
	"sync"
	"sync/atomic"

	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/daemon"
	"github.com/sagernet/sing-box/experimental/fkhost"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/service/powerreport"
	"github.com/sagernet/sing/common"
)

var (
	// singBoxLifecycle serializes sing-box starts and stops; singBoxRunning
	// is read without it.
	singBoxLifecycle sync.Mutex
	singBoxRunning   atomic.Pointer[fkhost.SingBox]

	// singBoxStarting is the start in progress, which a stop or the next
	// start interrupts (rule-set downloads can keep box.Start busy for long)
	// instead of waiting for it, as libbox's service did.
	singBoxStartAccess sync.Mutex
	singBoxStarting    *singBoxStart

	idleSingBoxOnce    sync.Once
	idleSingBoxService *daemon.StartedService
)

// runningSingBox is the running sing-box, nil when none runs.
func runningSingBox() *fkhost.SingBox { return singBoxRunning.Load() }

// singBoxService is the service the command service forwards sing-box calls
// to: the running box's attached service, or an idle one (status IDLE, its
// streams answer "invalid argument") when sing-box does not run.
func singBoxService() *daemon.StartedService {
	if running := runningSingBox(); running != nil {
		return running.Service()
	}
	idleSingBoxOnce.Do(func() {
		idleSingBoxService = daemon.NewStartedService(daemon.ServiceOptions{Context: context.Background()})
	})
	return idleSingBoxService
}

// coreSingBoxLifecycle is the hostLifecycle of the CommandServer host.
type coreSingBoxLifecycle struct{ server *CommandServer }

func (l coreSingBoxLifecycle) StartSingBox(content string, options kernelOptions) error {
	serviceOptions, _ := options.host.(*CoreServiceOptions)
	return l.server.startSingBox(content, serviceOptions)
}

func (l coreSingBoxLifecycle) StopSingBox() error { return l.server.stopSingBox() }

func (coreSingBoxLifecycle) WorkingDirectory() string { return coreWorkingPath() }

// singBoxStart is one start in progress.
type singBoxStart struct {
	cancel      context.CancelFunc
	interrupted bool
}

// interruptSingBoxStart cancels the start in progress, if any: its box.Start
// returns early and the start leaves nothing running.
func interruptSingBoxStart() {
	singBoxStartAccess.Lock()
	defer singBoxStartAccess.Unlock()
	if start := singBoxStarting; start != nil {
		start.interrupted = true
		start.cancel()
		singBoxStarting = nil
	}
}

// beginSingBoxStart registers a start that interruptSingBoxStart can cancel
// and returns the context sing-box runs in.
func beginSingBoxStart(parent context.Context) (context.Context, *singBoxStart) {
	ctx, cancel := context.WithCancel(parent)
	start := &singBoxStart{cancel: cancel}
	singBoxStartAccess.Lock()
	singBoxStarting = start
	singBoxStartAccess.Unlock()
	return ctx, start
}

// endSingBoxStart unregisters a start and reports whether it was
// interrupted. Once it returns, the context is the running box's for good.
func endSingBoxStart(start *singBoxStart) bool {
	singBoxStartAccess.Lock()
	defer singBoxStartAccess.Unlock()
	if singBoxStarting == start {
		singBoxStarting = nil
	}
	return start.interrupted
}

func (s *CommandServer) startSingBox(content string, serviceOptions *CoreServiceOptions) error {
	if serviceOptions == nil {
		serviceOptions = &CoreServiceOptions{}
	}
	// A start still in progress (a reload right after a start) is replaced.
	interruptSingBoxStart()
	uiCaptureLifecycle.Lock()
	defer uiCaptureLifecycle.Unlock()
	preparedRewrite, err := s.prepareUIHTTPRewrite(content, serviceOptions)
	if err != nil {
		return err
	}
	stopUIHTTPProcessing()
	saveConfigSnapshot(content)
	if s.powerManager.Recorder() != nil {
		copyConfigSnapshot(filepath.Join(sWorkingPath, powerreport.DraftDirectoryName))
	}
	options, err := parseCoreConfig(s.ctx, content)
	if err != nil {
		return err
	}
	applyCoreServiceOptions(&options, serviceOptions)

	singBoxLifecycle.Lock()
	defer singBoxLifecycle.Unlock()
	reloading := detachSingBox()
	if reloading != nil {
		// The OOM recorder ignores the memory peak of the replacement.
		s.oomRecorder.BeginReload()
		defer s.oomRecorder.EndReload()
		_ = reloading.Close()
	} else {
		// A fresh start begins with an empty log, as libbox's service did.
		coreLog.Clear()
	}
	startCtx, start := beginSingBoxStart(s.ctx)
	running, err := fkhost.StartSingBox(fkhost.SingBoxOptions{
		Context:   startCtx,
		Options:   options,
		LogWriter: coreLog,
		Reloading: reloading != nil,
	})
	if endSingBoxStart(start) {
		// A stop (or the next start) came first: leave nothing running.
		if running != nil {
			_ = running.Close()
		}
		start.cancel()
		coreLog.WriteMessage(log.LevelInfo, "sing-box start interrupted")
		return nil
	}
	if err != nil {
		start.cancel()
		coreLog.WriteMessage(log.LevelError, err.Error())
		return err
	}
	singBoxRunning.Store(running)
	activateUIHTTPRewrite(preparedRewrite)
	return nil
}

// detachSingBox takes the running sing-box out of singBoxRunning (callers
// hold singBoxLifecycle) and moves the open UI streams off it before it is
// closed, so a stop leaves them on the idle service instead of ending them.
func detachSingBox() *fkhost.SingBox {
	running := singBoxRunning.Swap(nil)
	if running != nil {
		notifyCoreChange()
	}
	return running
}

func (s *CommandServer) stopSingBox() error {
	interruptSingBoxStart()
	uiCaptureLifecycle.Lock()
	defer uiCaptureLifecycle.Unlock()
	stopUIHTTPProcessing()
	singBoxLifecycle.Lock()
	defer singBoxLifecycle.Unlock()
	if running := detachSingBox(); running != nil {
		return running.Close()
	}
	return nil
}

// applyCoreServiceOptions applies the app's overrides to the profile, as
// libbox's service did: auto redirect and the per-app lists on the first
// tun inbound, and the OOM killer service when the setup enables it.
func applyCoreServiceOptions(options *option.Options, serviceOptions *CoreServiceOptions) {
	for _, inbound := range options.Inbounds {
		if tunOptions, isTUN := inbound.Options.(*option.TunInboundOptions); isTUN {
			tunOptions.AutoRedirect = serviceOptions.AutoRedirect && tunOptions.AutoRoute
			tunOptions.IncludePackage = append(tunOptions.IncludePackage, iteratorToArray(serviceOptions.IncludePackage)...)
			tunOptions.ExcludePackage = append(tunOptions.ExcludePackage, iteratorToArray(serviceOptions.ExcludePackage)...)
			break
		}
	}
	if sOOMKillerEnabled && !common.Any(options.Services, func(it option.Service) bool { return it.Type == C.TypeOOMKiller }) {
		options.Services = append(options.Services, option.Service{
			Type: C.TypeOOMKiller,
			Options: &option.OOMKillerServiceOptions{
				KillerDisabled:      sOOMKillerDisabled,
				MemoryLimitOverride: uint64(sOOMMemoryLimit),
			},
		})
	}
}

// SetCoreError reports an error of the app's own (a failed stop): the
// service status becomes FATAL with it and it goes to the service log
// (replaces CommandServer.SetError).
func (s *CommandServer) SetCoreError(message string) {
	coreStatus.fatal(message)
	coreLog.WriteMessage(log.LevelError, message)
}

// ---- device and network events from the app ----
//
// libbox's CommandServer.Pause, Wake, WakeNow, ResetNetwork,
// UpdateWIFIState, NeedWIFIState and NeedFindProcess act on the sing-box of
// libbox's own service, which no longer runs anything; the app calls these
// instead. They act on the running sing-box; Xray and mihomo have no pause
// manager, Wi-Fi state or process search, so for them only ResetNetworkCore
// does something (it drops the connections bound to the old network, as a
// default network change does).

// PauseCore records the device's sleep in the power report and, on
// Android, closes sing-box's idle connections and pauses it (device idle).
func (s *CommandServer) PauseCore() {
	if recorder := s.powerManager.Recorder(); recorder != nil {
		recorder.RecordDeviceSleep()
	}
	if !(C.IsAndroid || C.IsIos) || C.IsTvOS {
		return
	}
	running := runningSingBox()
	if running == nil || running.PauseManager() == nil {
		return
	}
	running.Box().CloseIdleConnections()
	running.PauseManager().DevicePause()
}

// WakeCore records the device's wake in the power report and, on Android,
// resumes sing-box.
func (s *CommandServer) WakeCore() {
	if recorder := s.powerManager.Recorder(); recorder != nil {
		recorder.RecordDeviceWake()
	}
	if !C.IsAndroid {
		return
	}
	s.WakeNowCore()
}

// WakeNowCore resumes sing-box without recording a wake.
func (s *CommandServer) WakeNowCore() {
	if running := runningSingBox(); running != nil && running.PauseManager() != nil {
		running.PauseManager().DeviceWake()
	}
}

// ResetNetworkCore resets the running kernel's connections: sing-box's
// network manager, or the default-network handling of Xray and mihomo.
func (s *CommandServer) ResetNetworkCore() {
	if running := otherCore(); running != nil {
		if kernel := kernelFor(running.core); kernel != nil {
			kernel.DefaultNetworkChanged(running)
		}
		return
	}
	if running := runningSingBox(); running != nil {
		running.Box().Network().ResetNetwork(context.Background())
	}
}

// UpdateWIFIStateCore makes the running sing-box read the Wi-Fi state again.
func (s *CommandServer) UpdateWIFIStateCore() {
	if running := runningSingBox(); running != nil {
		running.Box().Network().UpdateWIFIState(context.Background())
	}
}

// NeedWIFIStateCore reports whether the running sing-box reads the Wi-Fi
// state (wifi_ssid/wifi_bssid rules).
func (s *CommandServer) NeedWIFIStateCore() bool {
	running := runningSingBox()
	return running != nil && running.Box().Network().NeedWIFIState()
}

// NeedFindProcessCore reports whether the running sing-box searches the
// process of a connection (process and package rules).
func (s *CommandServer) NeedFindProcessCore() bool {
	running := runningSingBox()
	return running != nil && running.Box().Router().NeedFindProcess()
}
