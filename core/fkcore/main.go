// Command fk-core is fk-box's core as a child process for desktop clients,
// built from the same packages as the Android core (libbox's fk-box files
// and experimental/fkhost). The client starts it, sends "hello", then speaks
// the UI protocol (protocol/methods.json) as JSON lines over stdio or a
// local socket; see fkhost.ProtocolServer for the framing.
//
//	fk-core [-dir DIR] [-socket PATH]
//
// It answers the methods Go owns (hello, checkConfig, formatConfig, ...),
// startService/reloadService {content, core?} and stopService (the desktop
// client passes the profile; Android's host reads it from its own store),
// setLogsVisible {visible} and clearLogs, and raises the core's events. The
// other methods belong to the client's host and are refused by name.
// Opening the TUN needs the system's privileges (Linux: CAP_NET_ADMIN).
package main

import (
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"

	"github.com/sagernet/sing-box/experimental/fkhost"
	"github.com/sagernet/sing-box/experimental/libbox"
)

func main() {
	directory := flag.String("dir", ".", "working directory (profiles' assets, geo data, kernel homes)")
	socket := flag.String("socket", "", "serve a Unix socket at this path instead of stdio")
	version := flag.Bool("version", false, "print the versions and exit")
	flag.Parse()
	if *version {
		fmt.Printf("fk-core: sing-box %s, protocol %d (min client %d)\n", libbox.Version(), libbox.ProtocolVersion, libbox.ProtocolMinClient)
		return
	}
	if err := prepare(*directory); err != nil {
		fmt.Fprintln(os.Stderr, "fk-core:", err)
		os.Exit(1)
	}
	stopLogs := fkhost.Log.Subscribe(func(entry fkhost.Entry) {
		libbox.UILogStreamAppend(entry.Level.Priority(), entry.Message)
		libbox.UILogStreamCommit()
	})
	signals := make(chan os.Signal, 1)
	signal.Notify(signals, os.Interrupt, syscall.SIGTERM)
	go func() {
		<-signals
		shutdown(stopLogs)
		os.Exit(0)
	}()
	var err error
	if *socket != "" {
		err = serveSocket(*socket)
	} else {
		err = serve(os.Stdin, os.Stdout)
	}
	shutdown(stopLogs)
	if err != nil {
		fmt.Fprintln(os.Stderr, "fk-core:", err)
		os.Exit(1)
	}
}

func prepare(directory string) error {
	absolute, err := filepath.Abs(directory)
	if err != nil {
		return err
	}
	if err = os.MkdirAll(absolute, 0o700); err != nil {
		return err
	}
	// libbox's own paths stay empty here; relative ones resolve below it.
	if err = os.Chdir(absolute); err != nil {
		return err
	}
	fkhost.SetWorkingDirectory(absolute)
	return nil
}

func shutdown(stopLogs func()) {
	libbox.SetUIEventSink(nil)
	if err := libbox.StandaloneCoreStop(); err != nil {
		fkhost.Log.Diagnostic("stop: " + err.Error())
	}
	stopLogs()
}

// serve answers one client until its input ends.
func serve(in io.Reader, out io.Writer) error {
	server := fkhost.NewProtocolServer(invoke, out)
	libbox.SetUIEventSink(server)
	defer libbox.SetUIEventSink(nil)
	return server.Serve(in)
}

// serveSocket answers one client at a time on a Unix socket only this user
// can open.
func serveSocket(path string) error {
	if err := os.Remove(path); err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	listener, err := net.Listen("unix", path)
	if err != nil {
		return err
	}
	defer listener.Close()
	if err = os.Chmod(path, 0o600); err != nil {
		return err
	}
	for {
		conn, err := listener.Accept()
		if err != nil {
			return err
		}
		if err = serve(conn, conn); err != nil {
			fkhost.Log.Diagnostic("client: " + err.Error())
		}
		_ = conn.Close()
	}
}

type serviceArgs struct {
	Core    string `json:"core"`
	Content string `json:"content"`
}

func invoke(method string, args string) (string, error) {
	if libbox.UIHandlesMethod(method) {
		return libbox.UIInvoke(method, args)
	}
	switch method {
	case "startService", "reloadService":
		var request serviceArgs
		if err := decode(args, &request); err != nil {
			return "", err
		}
		if request.Content == "" {
			return "", fmt.Errorf("%s: missing content", method)
		}
		if err := libbox.StandaloneCoreStart(request.Core, request.Content); err != nil {
			return "", err
		}
		return "true", nil
	case "stopService":
		if err := libbox.StandaloneCoreStop(); err != nil {
			return "", err
		}
		return "true", nil
	case "setLogsVisible":
		var request struct {
			Visible bool `json:"visible"`
		}
		if err := decode(args, &request); err != nil {
			return "", err
		}
		libbox.UILogStreamSetVisible(request.Visible)
		return "true", nil
	case "clearLogs":
		libbox.UILogStreamClear()
		return "true", nil
	}
	return "", fmt.Errorf("%s: answered by the client's host, not by fk-core", method)
}

func decode(args string, target any) error {
	if args == "" {
		return nil
	}
	if err := json.Unmarshal([]byte(args), target); err != nil {
		return fmt.Errorf("invalid method arguments: %w", err)
	}
	return nil
}
