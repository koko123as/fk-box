package libbox

// sing-box's start and stop on the CommandServer host (Android): fk-box runs
// sing-box itself (fkhost.SingBox) instead of libbox's
// daemon.StartedService.StartOrReloadService/CloseService. Everything that
// libbox's service did around a start is done here: the config snapshot for
// crash reports, the app's per-app and auto-redirect overrides on the tun
// inbound, the OOM killer service, the OOM recorder's reload window, the
// HTTP capture/rewrite stop and the rewrite activation that the httpdebug
// and http_rewrite patches used to add to libbox's CommandServer.

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

func (s *CommandServer) startSingBox(content string, serviceOptions *CoreServiceOptions) error {
	if serviceOptions == nil {
		serviceOptions = &CoreServiceOptions{}
	}
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
	running, err := fkhost.StartSingBox(fkhost.SingBoxOptions{
		Context:   s.ctx,
		Options:   options,
		LogWriter: coreLog,
		Reloading: reloading != nil,
	})
	if err != nil {
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

// NeedWIFIStateCore reports whether the running sing-box reads the Wi-Fi
// state (replaces CommandServer.NeedWIFIState, which reads libbox's service).
func (s *CommandServer) NeedWIFIStateCore() bool {
	running := runningSingBox()
	return running != nil && running.Box().Network().NeedWIFIState()
}

// SetCoreError writes an error of the app's own (a failed stop) to the
// service log (replaces CommandServer.SetError).
func (s *CommandServer) SetCoreError(message string) {
	coreLog.WriteMessage(log.LevelError, message)
}
