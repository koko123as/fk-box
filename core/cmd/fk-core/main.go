// Command fk-core is fk-box's host process (docs/kernel-isolation.md): the
// Android app (bridge/FkCoreClient.kt) or a desktop client starts it, sends
// "hello", then speaks the protocol of package protocol over a Unix socket
// (-socket PATH) or stdio. fk-core never links a kernel: it runs the official
// Xray and mihomo executables as child processes and reads sing-box through
// its official control socket.
//
//	fk-core [-dir DIR] [-socket PATH] [-libdir DIR] [-files DIR] [-working DIR]
//	        [-temp DIR] [-singbox-version VERSION] [-version]
//
// Besides hello and version it answers every function package fk registers
// (fk.Register: the profile store, profile checks and conversion, routing,
// geo data, WebDAV, the UI protocol methods, ...); fk reaches the app back
// through reverse calls and events on the active connection (fk.Host).
//
// With -socket, fk-core exits when its standard input ends, so it never
// outlives the app process that started it (Java's ProcessBuilder keeps the
// pipe open for the life of the parent).
package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net"
	"os"
	"os/signal"
	"path/filepath"
	"runtime"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/koko123as/fk-box/core/fk"
	"github.com/koko123as/fk-box/core/protocol"
	"github.com/koko123as/fk-box/core/rpc"
)

// ProtocolVersion is the version of the fk-core protocol this build speaks;
// MinClient the oldest client it accepts.
const (
	ProtocolVersion = 1
	MinClient       = 1
)

// Version is set at build time (-ldflags "-X main.Version=...").
var Version = "dev"

// SingBoxVersion is the version of the official libbox the app ships (the
// sing-box subscription User-Agent); -singbox-version overrides it.
var SingBoxVersion = ""

// hostCallTimeout bounds one reverse call to the app.
const hostCallTimeout = 60 * time.Second

// registry holds every function of package fk the client calls by name.
var registry = rpc.New()

// activeServer is the connection fk reaches the app through.
var activeServer atomic.Pointer[protocol.Server]

// serverHost is fk.Host over the active connection.
type serverHost struct{}

func (serverHost) Call(method string, args ...any) (json.RawMessage, error) {
	server := activeServer.Load()
	if server == nil {
		return nil, protocol.ErrClosed
	}
	if args == nil {
		args = []any{}
	}
	ctx, cancel := context.WithTimeout(context.Background(), hostCallTimeout)
	defer cancel()
	return server.Call(ctx, method, args)
}

func (serverHost) Emit(kind string, payload string) {
	if server := activeServer.Load(); server != nil {
		server.Emit(kind, payload)
	}
}

// serve answers one client on conn until it ends; its objects are released
// when it is gone.
func serve(ctx context.Context, conn io.Reader, out io.Writer) error {
	server := protocol.NewServer(handle, out)
	activeServer.Store(server)
	defer func() {
		activeServer.CompareAndSwap(server, nil)
		registry.ReleaseAll()
	}()
	return server.Serve(ctx, conn)
}

func main() {
	directory := flag.String("dir", ".", "working directory")
	socket := flag.String("socket", "", "serve a Unix socket at this path instead of stdio")
	libDir := flag.String("libdir", "", "directory of the kernel executables (the app's nativeLibraryDir)")
	filesDir := flag.String("files", "", "the app's files directory")
	workingDir := flag.String("working", "", "the app's working directory (geo data, rule lists)")
	tempDir := flag.String("temp", "", "directory for temporary files")
	singBoxVersion := flag.String("singbox-version", SingBoxVersion, "version of the app's official libbox")
	version := flag.Bool("version", false, "print the version and exit")
	flag.Parse()
	if *version {
		fmt.Printf("fk-core %s (protocol %d, %s)\n", Version, ProtocolVersion, runtime.Version())
		return
	}
	if err := prepare(*directory); err != nil {
		fmt.Fprintln(os.Stderr, "fk-core:", err)
		os.Exit(1)
	}
	fk.SetPaths(*libDir, *filesDir, *workingDir, *tempDir)
	fk.SetVersion(Version, *singBoxVersion)
	fk.Register(registry)
	fk.SetHost(serverHost{})
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	var err error
	if *socket != "" {
		go func() {
			// The parent holds our stdin; it ends when the parent is gone.
			_, _ = io.Copy(io.Discard, os.Stdin)
			cancel()
		}()
		err = serveSocket(ctx, *socket)
	} else {
		err = serve(ctx, os.Stdin, os.Stdout)
	}
	if err != nil && !errors.Is(err, context.Canceled) {
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
	return os.Chdir(absolute)
}

// serveSocket answers one client at a time on a Unix socket only this user
// can open, until ctx ends.
func serveSocket(ctx context.Context, path string) error {
	if err := os.Remove(path); err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	listener, err := net.ListenUnix("unix", &net.UnixAddr{Name: path, Net: "unix"})
	if err != nil {
		return err
	}
	if err = os.Chmod(path, 0o600); err != nil {
		listener.Close()
		return err
	}
	go func() {
		<-ctx.Done()
		listener.Close()
	}()
	defer os.Remove(path)
	for {
		conn, err := listener.AcceptUnix()
		if err != nil {
			if ctx.Err() != nil {
				return ctx.Err()
			}
			return err
		}
		connCtx, connCancel := context.WithCancel(ctx)
		go func() {
			<-connCtx.Done()
			conn.Close()
		}()
		if err = serve(connCtx, conn, conn); err != nil && connCtx.Err() == nil {
			fmt.Fprintln(os.Stderr, "fk-core: client:", err)
		}
		connCancel()
	}
}

type helloReply struct {
	ProtocolVersion int    `json:"protocolVersion"`
	CoreVersion     string `json:"coreVersion"`
	OK              bool   `json:"ok"`
}

func handle(ctx context.Context, request protocol.Request) (json.RawMessage, error) {
	defer func() {
		if request.File != nil {
			request.File.Close()
		}
	}()
	switch request.Method {
	case "hello":
		var args struct {
			ProtocolVersion *int `json:"protocolVersion"`
		}
		if err := decode(request.Args, &args); err != nil {
			return nil, err
		}
		if args.ProtocolVersion == nil {
			return nil, errors.New("hello: missing protocolVersion")
		}
		if *args.ProtocolVersion < MinClient || *args.ProtocolVersion > ProtocolVersion {
			return nil, fmt.Errorf("protocol version mismatch: core=%d client=%d", ProtocolVersion, *args.ProtocolVersion)
		}
		return json.Marshal(helloReply{ProtocolVersion: ProtocolVersion, CoreVersion: Version, OK: true})
	case "version":
		return json.Marshal(map[string]string{"fkCore": Version, "go": runtime.Version()})
	}
	if registry.Has(request.Method) {
		args := request.Args
		if len(args) == 0 {
			args = json.RawMessage("[]")
		}
		return registry.Call(request.Method, args)
	}
	return nil, fmt.Errorf("%s: unknown method", request.Method)
}

func decode(args json.RawMessage, target any) error {
	if len(args) == 0 || string(args) == "null" {
		return nil
	}
	if err := json.Unmarshal(args, target); err != nil {
		return fmt.Errorf("invalid method arguments: %w", err)
	}
	return nil
}
