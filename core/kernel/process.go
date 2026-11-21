// Package kernel runs the official kernel executables (libxray.so,
// libmihomo.so) as child processes of fk-core and talks to them through
// their own control interfaces (docs/kernel-isolation.md sections 5 and 9).
// It never imports a kernel module.
package kernel

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"strings"
	"sync"
	"syscall"
	"time"
)

// Spec describes one kernel process.
type Spec struct {
	// Name is the kernel's name in errors and logs ("xray", "mihomo").
	Name string
	// Path is the executable, Args its arguments, Env its environment
	// (added to fk-core's own) and Dir its working directory.
	Path string
	Args []string
	Env  []string
	Dir  string
	// Tun, when set, is passed to the child as descriptor 3. The caller
	// keeps its own copy.
	Tun *os.File
	// Ready is called until it succeeds, the process exits or ReadyTimeout
	// passes; nil means ready at once.
	Ready        func(ctx context.Context) error
	ReadyTimeout time.Duration
	// OnLog receives every line the process writes to stdout or stderr.
	OnLog func(line string)
}

// Process is a running kernel.
type Process struct {
	name string
	cmd  *exec.Cmd
	done chan struct{}

	access   sync.Mutex
	waitErr  error
	stopping bool

	tailAccess sync.Mutex
	tail       []string
}

const tailLines = 200

// ErrExited is returned by Start when the process ends before it is ready.
var ErrExited = errors.New("exited before it was ready")

// Start starts the process and waits until it is ready. A process that is
// not ready in time is killed.
func Start(ctx context.Context, spec Spec) (*Process, error) {
	cmd := exec.Command(spec.Path, spec.Args...)
	cmd.Dir = spec.Dir
	cmd.Env = append(os.Environ(), spec.Env...)
	cmd.SysProcAttr = sysProcAttr()
	if spec.Tun != nil {
		cmd.ExtraFiles = []*os.File{spec.Tun}
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return nil, err
	}
	cmd.Stderr = cmd.Stdout
	process := &Process{name: spec.Name, cmd: cmd, done: make(chan struct{})}
	if err = cmd.Start(); err != nil {
		return nil, fmt.Errorf("start %s: %w", spec.Name, err)
	}
	logsDone := make(chan struct{})
	go func() {
		defer close(logsDone)
		process.readLogs(stdout, spec.OnLog)
	}()
	go func() {
		err := cmd.Wait()
		<-logsDone
		process.access.Lock()
		process.waitErr = err
		process.access.Unlock()
		close(process.done)
	}()
	if err = process.waitReady(ctx, spec); err != nil {
		process.Kill()
		return nil, err
	}
	return process, nil
}

func (p *Process) readLogs(reader io.Reader, onLog func(string)) {
	scanner := bufio.NewScanner(reader)
	scanner.Buffer(make([]byte, 64*1024), 1024*1024)
	for scanner.Scan() {
		line := strings.TrimRight(scanner.Text(), "\r")
		p.tailAccess.Lock()
		p.tail = append(p.tail, line)
		if len(p.tail) > tailLines {
			p.tail = p.tail[len(p.tail)-tailLines:]
		}
		p.tailAccess.Unlock()
		if onLog != nil {
			onLog(line)
		}
	}
	_, _ = io.Copy(io.Discard, reader)
}

func (p *Process) waitReady(ctx context.Context, spec Spec) error {
	if spec.Ready == nil {
		return nil
	}
	timeout := spec.ReadyTimeout
	if timeout <= 0 {
		timeout = 30 * time.Second
	}
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	var lastErr error
	for {
		attempt, attemptCancel := context.WithTimeout(ctx, 3*time.Second)
		lastErr = spec.Ready(attempt)
		attemptCancel()
		if lastErr == nil {
			return nil
		}
		select {
		case <-p.done:
			return fmt.Errorf("%s %w: %s", spec.Name, ErrExited, p.exitText())
		case <-ctx.Done():
			return fmt.Errorf("%s not ready after %s (%v)%s", spec.Name, timeout, lastErr, p.tailText(20))
		case <-time.After(200 * time.Millisecond):
		}
	}
}

func (p *Process) exitText() string {
	p.access.Lock()
	err := p.waitErr
	p.access.Unlock()
	text := "exit"
	if err != nil {
		text = err.Error()
	}
	return text + p.tailText(20)
}

func (p *Process) tailText(lines int) string {
	tail := p.Tail()
	if len(tail) > lines {
		tail = tail[len(tail)-lines:]
	}
	if len(tail) == 0 {
		return ""
	}
	return "\n" + strings.Join(tail, "\n")
}

// Tail is the last lines the process wrote.
func (p *Process) Tail() []string {
	p.tailAccess.Lock()
	defer p.tailAccess.Unlock()
	return append([]string(nil), p.tail...)
}

// Pid is the process id.
func (p *Process) Pid() int { return p.cmd.Process.Pid }

// Done is closed once the process has ended and been reaped.
func (p *Process) Done() <-chan struct{} { return p.done }

// Stopped reports whether Stop or Kill ended the process.
func (p *Process) Stopped() bool {
	p.access.Lock()
	defer p.access.Unlock()
	return p.stopping
}

// ExitText describes how the process ended and its last output.
func (p *Process) ExitText() string { return p.exitText() }

// Killed reports whether the process ended on SIGKILL that fk-core did not
// send (the system's low-memory killer).
func (p *Process) Killed() bool {
	p.access.Lock()
	defer p.access.Unlock()
	if p.stopping {
		return false
	}
	var exitErr *exec.ExitError
	if errors.As(p.waitErr, &exitErr) {
		if status, ok := exitErr.Sys().(syscall.WaitStatus); ok {
			return status.Signaled() && status.Signal() == syscall.SIGKILL
		}
	}
	return false
}

// Stop sends SIGTERM, then SIGKILL after grace, and returns once the
// process has been reaped.
func (p *Process) Stop(grace time.Duration) {
	p.access.Lock()
	p.stopping = true
	p.access.Unlock()
	select {
	case <-p.done:
		return
	default:
	}
	_ = p.cmd.Process.Signal(syscall.SIGTERM)
	select {
	case <-p.done:
	case <-time.After(grace):
		_ = p.cmd.Process.Kill()
		<-p.done
	}
}

// Kill ends the process at once and waits for it to be reaped.
func (p *Process) Kill() {
	p.access.Lock()
	p.stopping = true
	p.access.Unlock()
	_ = p.cmd.Process.Kill()
	<-p.done
}

// Memory is the process's resident and peak resident set size in bytes,
// from /proc/<pid>/status.
func Memory(pid int) (rss int64, peak int64) {
	data, err := os.ReadFile(fmt.Sprintf("/proc/%d/status", pid))
	if err != nil {
		return 0, 0
	}
	for _, line := range strings.Split(string(data), "\n") {
		field, value, ok := strings.Cut(line, ":")
		if !ok {
			continue
		}
		var kib int64
		if _, err := fmt.Sscan(strings.TrimSuffix(strings.TrimSpace(value), " kB"), &kib); err != nil {
			continue
		}
		switch field {
		case "VmRSS":
			rss = kib * 1024
		case "VmHWM":
			peak = kib * 1024
		}
	}
	return rss, peak
}
