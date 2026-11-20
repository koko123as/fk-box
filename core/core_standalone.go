//go:build !android

package libbox

// The standalone host: fk-box runs the kernels itself, without libbox's
// CommandServer (the desktop core, cmd/fk-core). Here sing-box is an
// ordinary kernel: fk-box starts it with fkhost.StartSingBox (it opens its
// own TUN from the profile's tun inbound, as sing-box does on desktop
// systems), and Xray and mihomo run on the TUN of fkhost's platform for this
// operating system. Android uses the CommandServer host instead
// (core_singbox_host.go); this file is not built there.

import (
	"sync"

	"github.com/sagernet/sing-box/experimental/fkhost"
	"github.com/sagernet/sing-box/log"
)

var (
	standalonePlatformOnce  sync.Once
	standalonePlatformValue fkhost.Platform
)

func standaloneKernelHost() kernelHost {
	standalonePlatformOnce.Do(func() { standalonePlatformValue = fkhost.NewPlatform() })
	return kernelHost{
		Platform:  fkhostPlatform{standalonePlatformValue},
		Commands:  standaloneCommands{},
		Lifecycle: standaloneLifecycle{},
		HTTP:      fkhostHTTP{},
		Log:       fkhostLog{},
	}
}

// StandaloneCoreStart starts the profile on its core ("auto" or "" detects
// it), replacing whatever ran before.
func StandaloneCoreStart(core string, content string) error {
	resolved, err := ResolveProfileCore(core, content)
	if err != nil {
		return err
	}
	return startOrReloadCore(standaloneKernelHost, resolved, content, kernelOptions{})
}

// StandaloneCoreStop stops whichever core runs.
func StandaloneCoreStop() error {
	return closeCore(standaloneKernelHost)
}

// The desktop core has no StartedService streams to move between cores; its
// client reads the core through the protocol (fkhost.ProtocolServer).
type standaloneCommands struct{}

func (standaloneCommands) CoreChanged()   {}
func (standaloneCommands) GroupsChanged() {}

var (
	standaloneAccess sync.Mutex
	standaloneBox    *fkhost.SingBox
)

type standaloneLifecycle struct{}

func (standaloneLifecycle) StartSingBox(content string, _ kernelOptions) error {
	standaloneAccess.Lock()
	defer standaloneAccess.Unlock()
	_ = closeStandaloneBoxLocked()
	ctx := baseContext(nil)
	options, err := parseCoreConfig(ctx, content)
	if err != nil {
		return err
	}
	instance, err := fkhost.StartSingBox(fkhost.SingBoxOptions{Context: ctx, Options: options, LogWriter: standaloneLogWriter{}})
	if err != nil {
		return err
	}
	standaloneBox = instance
	fkhost.Log.Write(fkhost.LevelInfo, "sing-box started")
	return nil
}

func (standaloneLifecycle) StopSingBox() error {
	standaloneAccess.Lock()
	defer standaloneAccess.Unlock()
	return closeStandaloneBoxLocked()
}

func closeStandaloneBoxLocked() error {
	if standaloneBox == nil {
		return nil
	}
	err := standaloneBox.Close()
	standaloneBox = nil
	fkhost.Log.Write(fkhost.LevelInfo, "sing-box stopped")
	return err
}

func (standaloneLifecycle) WorkingDirectory() string { return coreWorkingPath() }

// standaloneLogWriter puts sing-box's log lines into fk-box's log.
type standaloneLogWriter struct{}

func (standaloneLogWriter) WriteMessage(level log.Level, message string) {
	fkhost.Log.Write(fkhostLevel(level), message)
}

func fkhostLevel(level log.Level) fkhost.Level {
	switch level {
	case log.LevelPanic, log.LevelFatal, log.LevelError:
		return fkhost.LevelError
	case log.LevelWarn:
		return fkhost.LevelWarn
	case log.LevelInfo:
		return fkhost.LevelInfo
	}
	return fkhost.LevelDebug
}
