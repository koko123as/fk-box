package fkhost

// sing-box as an ordinary kernel: fk-box creates, starts and closes its
// box.Box itself instead of handing the profile to libbox's
// daemon.StartedService. What the UI reads from a running sing-box (status,
// groups, clash mode, connections, outbounds and the endpoint services:
// Tailscale and Taildrop, USB/IP, OpenConnect, OpenVPN, network quality,
// STUN) is served by sing-box's own attached service over the box's context
// (daemon.NewAttachedService, the same service sing-box's "api" service
// exposes for a box it did not create); fk-box's command service
// (libbox core_command_server.go) forwards to it.

import (
	"context"
	"runtime/debug"
	"sync"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/common/urltest"
	"github.com/sagernet/sing-box/daemon"
	"github.com/sagernet/sing-box/experimental/deprecated"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/service/powerreport"
	"github.com/sagernet/sing/common"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/x/list"
	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/pause"
)

// SingBoxOptions is one sing-box start.
type SingBoxOptions struct {
	// Context is the host's base context: the protocol registries and, on
	// a platform, its adapter.PlatformInterface.
	Context context.Context
	// Options is the parsed profile, with the host's overrides applied.
	Options option.Options
	// LogWriter receives sing-box's log lines (the service log the app shows).
	LogWriter log.PlatformWriter
	// Reloading is true when the start replaces a running sing-box.
	Reloading bool
}

// SingBox is one running sing-box.
type SingBox struct {
	ctx           context.Context
	cancel        context.CancelFunc
	instance      *box.Box
	history       *urltest.HistoryStorage
	notes         *DeprecatedNotes
	attached      *daemon.StartedService
	pauseManager  pause.Manager
	pauseCallback *list.Element[pause.Callback]
	closeOnce     sync.Once
}

// StartSingBox creates and starts sing-box. On error nothing is left running.
func StartSingBox(options SingBoxOptions) (*SingBox, error) {
	ctx := service.ExtendContext(options.Context)
	notes := new(DeprecatedNotes)
	service.MustRegister[deprecated.Manager](ctx, notes)
	// The URL test history must be in the context before box.New: box.New
	// only adds one to its own copy, which the attached service cannot read.
	history := urltest.NewHistoryStorage()
	ctx = service.ContextWithPtr(ctx, history)
	ctx, cancel := context.WithCancel(ctx)
	instance, err := box.New(box.Options{
		Context:           ctx,
		Options:           options.Options,
		PlatformLogWriter: options.LogWriter,
	})
	if err != nil {
		cancel()
		history.Close()
		return nil, E.Cause(err, "create sing-box")
	}
	s := &SingBox{
		ctx:          ctx,
		cancel:       cancel,
		instance:     instance,
		history:      history,
		notes:        notes,
		pauseManager: service.FromContext[pause.Manager](ctx),
	}
	s.registerPowerReport(options.Reloading)
	log.SetStdLogger(instance.LogFactory().Logger())
	if err = instance.Start(); err != nil {
		s.Close()
		return nil, E.Cause(err, "start sing-box")
	}
	s.attached = daemon.NewAttachedService(ctx)
	debug.FreeOSMemory()
	return s, nil
}

// registerPowerReport records the start and the pause events in the host's
// power report, as libbox's service did.
func (s *SingBox) registerPowerReport(reloading bool) {
	powerManager := service.FromContext[*powerreport.Manager](s.ctx)
	if powerManager == nil {
		return
	}
	if recorder := powerManager.Recorder(); recorder != nil {
		recorder.RecordServiceStart(s.instance.CreatedAt(), reloading)
	}
	if s.pauseManager == nil {
		return
	}
	s.pauseCallback = s.pauseManager.RegisterCallback(func(event int) {
		if recorder := powerManager.Recorder(); recorder != nil {
			recorder.RecordPauseEvent(event)
		}
	})
}

// Box is the running box.
func (s *SingBox) Box() *box.Box { return s.instance }

// Context is the box's context (its services are registered there).
func (s *SingBox) Context() context.Context { return s.ctx }

// Service is sing-box's attached service over this box: the gRPC service
// implementation for status, groups, mode, connections, outbounds and the
// endpoint services.
func (s *SingBox) Service() *daemon.StartedService { return s.attached }

// PauseManager is the box's pause manager (nil without one).
func (s *SingBox) PauseManager() pause.Manager { return s.pauseManager }

// DeprecatedNotes returns the deprecation notes reported since the last call.
func (s *SingBox) DeprecatedNotes() []deprecated.Note { return s.notes.Take() }

// Close stops the box; streams of its attached service end.
func (s *SingBox) Close() error {
	var err error
	s.closeOnce.Do(func() {
		if s.attached != nil {
			s.attached.Close()
		}
		s.cancel()
		if s.pauseCallback != nil {
			s.pauseManager.UnregisterCallback(s.pauseCallback)
			s.pauseCallback = nil
		}
		s.history.Close()
		err = s.instance.Close()
		debug.FreeOSMemory()
	})
	return err
}

// DeprecatedNotes collects the deprecation notes sing-box reports while it
// starts (deprecated.Manager).
type DeprecatedNotes struct {
	access sync.Mutex
	notes  []deprecated.Note
}

func (m *DeprecatedNotes) ReportDeprecated(feature deprecated.Note) {
	m.access.Lock()
	defer m.access.Unlock()
	m.notes = common.Uniq(append(m.notes, feature))
}

// Take returns the collected notes and forgets them.
func (m *DeprecatedNotes) Take() []deprecated.Note {
	m.access.Lock()
	defer m.access.Unlock()
	notes := m.notes
	m.notes = nil
	return notes
}
