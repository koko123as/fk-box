// fdexec is the P0 feasibility probe for docs/kernel-isolation.md: it receives
// a TUN descriptor from the Android app over a Unix socket (SCM_RIGHTS, sent
// with LocalSocket.setFileDescriptorsForSend) and runs an official, unmodified
// kernel executable with that descriptor as fd 3, the way fk-core will.
//
// Usage: fdexec -socket PATH -seconds N -env K=V... -- KERNEL ARGS...
package main

import (
	"bufio"
	"flag"
	"fmt"
	"io"
	"net"
	"os"
	"os/exec"
	"strings"
	"syscall"
	"time"
)

type envList []string

func (e *envList) String() string     { return strings.Join(*e, ",") }
func (e *envList) Set(v string) error { *e = append(*e, v); return nil }

func main() {
	socketPath := flag.String("socket", "", "Unix socket path to receive the TUN descriptor on")
	seconds := flag.Int("seconds", 20, "how long the kernel runs before SIGTERM")
	var env envList
	flag.Var(&env, "env", "extra environment for the kernel (repeatable)")
	flag.Parse()
	if *socketPath == "" || flag.NArg() == 0 {
		fmt.Println("FDEXEC usage error")
		os.Exit(2)
	}
	_ = os.Remove(*socketPath)
	listener, err := net.ListenUnix("unix", &net.UnixAddr{Name: *socketPath, Net: "unix"})
	if err != nil {
		fmt.Println("FDEXEC listen failed:", err)
		os.Exit(1)
	}
	fmt.Println("FDEXEC listening", *socketPath)
	listener.SetDeadline(time.Now().Add(60 * time.Second))
	conn, err := listener.AcceptUnix()
	if err != nil {
		fmt.Println("FDEXEC accept failed:", err)
		os.Exit(1)
	}
	tunFile, err := receiveFD(conn)
	conn.Close()
	listener.Close()
	if err != nil {
		fmt.Println("FDEXEC receive failed:", err)
		os.Exit(1)
	}
	fmt.Println("FDEXEC received fd", tunFile.Fd())

	cmd := exec.Command(flag.Arg(0), flag.Args()[1:]...)
	cmd.Env = append(os.Environ(), env...)
	cmd.ExtraFiles = []*os.File{tunFile} // fd 3 in the child
	stdout, _ := cmd.StdoutPipe()
	cmd.Stderr = cmd.Stdout
	if err := cmd.Start(); err != nil {
		fmt.Println("FDEXEC start failed:", err)
		os.Exit(1)
	}
	tunFile.Close() // the child holds its own copy
	fmt.Println("FDEXEC kernel pid", cmd.Process.Pid)
	done := make(chan struct{})
	go func() {
		pipe(stdout)
		close(done)
	}()
	exited := make(chan error, 1)
	go func() { <-done; exited <- cmd.Wait() }()
	select {
	case err := <-exited:
		fmt.Println("FDEXEC kernel exited early:", err)
		os.Exit(1)
	case <-time.After(time.Duration(*seconds) * time.Second):
	}
	_ = cmd.Process.Signal(syscall.SIGTERM)
	select {
	case err := <-exited:
		fmt.Println("FDEXEC kernel stopped after SIGTERM:", err)
	case <-time.After(5 * time.Second):
		_ = cmd.Process.Kill()
		fmt.Println("FDEXEC kernel killed after SIGTERM timeout:", <-exited)
	}
}

func receiveFD(conn *net.UnixConn) (*os.File, error) {
	buf := make([]byte, 16)
	oob := make([]byte, syscall.CmsgSpace(4*4))
	conn.SetReadDeadline(time.Now().Add(30 * time.Second))
	_, oobn, _, _, err := conn.ReadMsgUnix(buf, oob)
	if err != nil {
		return nil, err
	}
	messages, err := syscall.ParseSocketControlMessage(oob[:oobn])
	if err != nil {
		return nil, err
	}
	for _, message := range messages {
		fds, err := syscall.ParseUnixRights(&message)
		if err != nil {
			continue
		}
		for _, extra := range fds[1:] {
			syscall.Close(extra)
		}
		if len(fds) > 0 {
			return os.NewFile(uintptr(fds[0]), "tun"), nil
		}
	}
	return nil, fmt.Errorf("no descriptor in the message")
}

func pipe(r io.Reader) {
	scanner := bufio.NewScanner(r)
	scanner.Buffer(make([]byte, 64*1024), 1024*1024)
	for scanner.Scan() {
		fmt.Println("KERNEL", scanner.Text())
	}
}
