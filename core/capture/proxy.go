// Package capture is fk-box's external capture proxy (docs/kernel-isolation.md
// §7): a SOCKS5 server on 127.0.0.1 that a kernel's route sends the
// connections to capture or rewrite to. Each connection is sniffed, handed to
// the httpdebug engine, and its upstream leaves through the kernel's local
// inbound, so it takes the profile's ordinary rules and outbounds.
package capture

import (
	"bufio"
	"context"
	"crypto/subtle"
	"crypto/x509"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"sync"
	"time"

	"github.com/koko123as/fk-box/core/httpdebug"
)

const handshakeTimeout = 10 * time.Second

// Proxy is one running capture proxy.
type Proxy struct {
	listener net.Listener
	username string
	password string
	// upstream answers the kernel's local inbound ("http://user:pass@host:port").
	upstream func() string
	// packages is the app filter the kernel's route already applied: the
	// connections it sends belong to these apps (sing-box package_name).
	packages func() []string
	roots    *x509.CertPool

	access sync.Mutex
	conns  map[net.Conn]struct{}
	closed bool
	wg     sync.WaitGroup
}

// Options configure a proxy.
type Options struct {
	Username string
	Password string
	Upstream func() string
	Packages func() []string
	Roots    *x509.CertPool
}

// Listen starts a proxy on 127.0.0.1 with a free port.
func Listen(options Options) (*Proxy, error) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return nil, err
	}
	proxy := &Proxy{
		listener: listener, username: options.Username, password: options.Password,
		upstream: options.Upstream, packages: options.Packages, roots: options.Roots,
		conns: map[net.Conn]struct{}{},
	}
	proxy.wg.Add(1)
	go proxy.serve()
	return proxy, nil
}

// Port is the proxy's listening port.
func (p *Proxy) Port() int { return p.listener.Addr().(*net.TCPAddr).Port }

// Close stops the proxy and closes its connections.
func (p *Proxy) Close() error {
	p.access.Lock()
	p.closed = true
	for conn := range p.conns {
		_ = conn.Close()
	}
	p.access.Unlock()
	err := p.listener.Close()
	p.wg.Wait()
	return err
}

func (p *Proxy) track(conn net.Conn, add bool) bool {
	p.access.Lock()
	defer p.access.Unlock()
	if add {
		if p.closed {
			return false
		}
		p.conns[conn] = struct{}{}
		return true
	}
	delete(p.conns, conn)
	return true
}

func (p *Proxy) serve() {
	defer p.wg.Done()
	for {
		conn, err := p.listener.Accept()
		if err != nil {
			return
		}
		if !p.track(conn, true) {
			_ = conn.Close()
			return
		}
		p.wg.Add(1)
		go func() {
			defer p.wg.Done()
			p.handle(conn)
		}()
	}
}

func (p *Proxy) handle(conn net.Conn) {
	closed := make(chan struct{})
	var once sync.Once
	finish := func(error) {
		once.Do(func() {
			p.track(conn, false)
			_ = conn.Close()
			close(closed)
		})
	}
	_ = conn.SetDeadline(time.Now().Add(handshakeTimeout))
	client, destination, err := p.handshake(conn)
	if err != nil {
		finish(err)
		return
	}
	_ = conn.SetDeadline(time.Time{})
	if !p.route(client, destination, finish) {
		// Not captured: relay it unchanged through the kernel.
		p.relay(client, destination, finish)
	}
	<-closed
}

// handshake runs the SOCKS5 server side (username/password, CONNECT) and
// answers the client connection (with any bytes read ahead) and the
// requested destination as host:port.
func (p *Proxy) handshake(conn net.Conn) (net.Conn, string, error) {
	reader := bufio.NewReader(conn)
	header := make([]byte, 2)
	if _, err := io.ReadFull(reader, header); err != nil {
		return nil, "", err
	}
	if header[0] != 5 {
		return nil, "", errors.New("capture: not a SOCKS5 client")
	}
	methods := make([]byte, header[1])
	if _, err := io.ReadFull(reader, methods); err != nil {
		return nil, "", err
	}
	hasAuth := false
	for _, method := range methods {
		hasAuth = hasAuth || method == 2
	}
	if !hasAuth {
		_, _ = conn.Write([]byte{5, 0xff})
		return nil, "", errors.New("capture: client offers no password authentication")
	}
	if _, err := conn.Write([]byte{5, 2}); err != nil {
		return nil, "", err
	}
	if _, err := io.ReadFull(reader, header); err != nil {
		return nil, "", err
	}
	user := make([]byte, header[1])
	if _, err := io.ReadFull(reader, user); err != nil {
		return nil, "", err
	}
	length, err := reader.ReadByte()
	if err != nil {
		return nil, "", err
	}
	pass := make([]byte, length)
	if _, err = io.ReadFull(reader, pass); err != nil {
		return nil, "", err
	}
	if subtle.ConstantTimeCompare(user, []byte(p.username)) != 1 || subtle.ConstantTimeCompare(pass, []byte(p.password)) != 1 {
		_, _ = conn.Write([]byte{1, 1})
		return nil, "", errors.New("capture: wrong credentials")
	}
	if _, err = conn.Write([]byte{1, 0}); err != nil {
		return nil, "", err
	}
	request := make([]byte, 4)
	if _, err = io.ReadFull(reader, request); err != nil {
		return nil, "", err
	}
	if request[0] != 5 || request[1] != 1 {
		_, _ = conn.Write([]byte{5, 7, 0, 1, 0, 0, 0, 0, 0, 0})
		return nil, "", errors.New("capture: only CONNECT is supported")
	}
	var host string
	switch request[3] {
	case 1:
		address := make([]byte, 4)
		if _, err = io.ReadFull(reader, address); err != nil {
			return nil, "", err
		}
		host = net.IP(address).String()
	case 4:
		address := make([]byte, 16)
		if _, err = io.ReadFull(reader, address); err != nil {
			return nil, "", err
		}
		host = net.IP(address).String()
	case 3:
		length, err = reader.ReadByte()
		if err != nil {
			return nil, "", err
		}
		name := make([]byte, length)
		if _, err = io.ReadFull(reader, name); err != nil {
			return nil, "", err
		}
		host = string(name)
	default:
		return nil, "", errors.New("capture: unknown address type")
	}
	portBytes := make([]byte, 2)
	if _, err = io.ReadFull(reader, portBytes); err != nil {
		return nil, "", err
	}
	// The tunnel is acknowledged before the client's first bytes, which the
	// sniffer needs (a TLS client waits for this reply to send its hello).
	if _, err = conn.Write([]byte{5, 0, 0, 1, 0, 0, 0, 0, 0, 0}); err != nil {
		return nil, "", err
	}
	return &bufferedConn{Conn: conn, reader: reader}, net.JoinHostPort(host, strconv.Itoa(int(binary.BigEndian.Uint16(portBytes)))), nil
}

func (p *Proxy) metadata(destination string, protocol, host string) httpdebug.Metadata {
	destHost, portText, _ := net.SplitHostPort(destination)
	port, _ := strconv.Atoi(portText)
	if host == "" {
		host = destHost
	}
	packages := []string{}
	if p.packages != nil {
		packages = append(packages, p.packages()...)
	}
	return httpdebug.Metadata{
		Host: host, Port: uint16(port), Protocol: protocol, Destination: destination,
		Inbound: "fkbox-capture", UID: -1, Packages: packages,
	}
}

// route hands the connection to the engine; false when it does not take it.
// Only a copy of the first bytes is sniffed, for the capture decision; the
// kernel already routed the connection by its own rules.
func (p *Proxy) route(conn net.Conn, destination string, finish func(error)) bool {
	engine := httpdebug.Current()
	if engine == nil || !engine.Candidate(p.metadata(destination, "", "")) {
		return false
	}
	conn, protocol, host := sniff(conn)
	info := p.metadata(destination, protocol, host)
	if protocol == "tls" {
		var selected bool
		conn, info, selected = engine.SelectTLS(context.Background(), conn, info)
		if !selected {
			engine.Observe(info, "TLS")
			p.relay(conn, destination, finish)
			return true
		}
	} else if !engine.Matches(info) {
		p.relay(conn, destination, finish)
		return true
	}
	dial := func(ctx context.Context) (net.Conn, error) { return p.dial(ctx, destination) }
	mapped := func(ctx context.Context, address string) (net.Conn, error) { return p.dial(ctx, address) }
	if !engine.HandleRouted(context.Background(), conn, info, dial, mapped, p.roots, finish) {
		p.relay(conn, destination, finish)
	}
	return true
}

// dial opens a tunnel to address through the kernel's local inbound (HTTP CONNECT).
func (p *Proxy) dial(ctx context.Context, address string) (net.Conn, error) {
	upstream, err := url.Parse(p.upstream())
	if err != nil || upstream.Host == "" {
		return nil, errors.New("capture: no kernel is running")
	}
	var dialer net.Dialer
	conn, err := dialer.DialContext(ctx, "tcp", upstream.Host)
	if err != nil {
		return nil, err
	}
	if deadline, ok := ctx.Deadline(); ok {
		_ = conn.SetDeadline(deadline)
	} else {
		_ = conn.SetDeadline(time.Now().Add(handshakeTimeout))
	}
	request := &http.Request{Method: http.MethodConnect, URL: &url.URL{Opaque: address}, Host: address, Header: http.Header{}}
	if user := upstream.User; user != nil {
		password, _ := user.Password()
		request.Header.Set("Proxy-Authorization", "Basic "+base64.StdEncoding.EncodeToString([]byte(user.Username()+":"+password)))
	}
	if err = request.Write(conn); err != nil {
		conn.Close()
		return nil, err
	}
	reader := bufio.NewReader(conn)
	response, err := http.ReadResponse(reader, request)
	if err != nil {
		conn.Close()
		return nil, err
	}
	_ = response.Body.Close()
	if response.StatusCode != http.StatusOK {
		conn.Close()
		return nil, fmt.Errorf("capture: kernel refused %s: %s", address, response.Status)
	}
	_ = conn.SetDeadline(time.Time{})
	if reader.Buffered() > 0 {
		return &bufferedConn{Conn: conn, reader: reader}, nil
	}
	return conn, nil
}

// relay copies the connection to destination through the kernel unchanged.
func (p *Proxy) relay(conn net.Conn, destination string, finish func(error)) {
	upstream, err := p.dial(context.Background(), destination)
	if err != nil {
		finish(err)
		return
	}
	if !p.track(upstream, true) {
		_ = upstream.Close()
		finish(nil)
		return
	}
	go func() {
		defer p.track(upstream, false)
		done := make(chan struct{}, 2)
		go func() { _, _ = io.Copy(upstream, conn); closeWrite(upstream); done <- struct{}{} }()
		go func() { _, _ = io.Copy(conn, upstream); closeWrite(conn); done <- struct{}{} }()
		<-done
		<-done
		_ = upstream.Close()
		finish(nil)
	}()
}

func closeWrite(conn net.Conn) {
	type writeCloser interface{ CloseWrite() error }
	if closer, ok := conn.(writeCloser); ok {
		_ = closer.CloseWrite()
		return
	}
	if buffered, ok := conn.(*bufferedConn); ok {
		closeWrite(buffered.Conn)
		return
	}
	if sniffed, ok := conn.(*peekedConn); ok {
		closeWrite(sniffed.Conn)
	}
}

type bufferedConn struct {
	net.Conn
	reader *bufio.Reader
}

func (c *bufferedConn) Read(b []byte) (int, error) { return c.reader.Read(b) }
