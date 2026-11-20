// Package protocol is the framing fk-core speaks with its client (the Android
// app's FkCoreClient, or a desktop client): the UI protocol of
// protocol/methods.json as JSON lines over a byte stream (stdio or a Unix
// socket), in both directions.
//
//	client -> core  {"id": 1, "method": "hello", "args": {...}}
//	core -> client  {"id": 1, "result": ...} or {"id": 1, "error": "text"}
//	core -> client  {"event": "logs", "payload": ...}
//	core -> client  {"call": 7, "method": "captureSign", "args": {...}}
//	client -> core  {"reply": 7, "result": ...} or {"reply": 7, "error": "text"}
//
// A request may carry one file descriptor (the VPN's TUN): the client sends
// the line with "fd": true in a single write that has the descriptor attached
// (SCM_RIGHTS; Android: LocalSocket.setFileDescriptorsForSend). The method
// receives it as Request.File.
//
// The first request must be "hello"; anything before it is answered with
// ErrHelloFirst. Requests run concurrently; responses carry their id.
package protocol

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"strconv"
	"sync"
	"syscall"
)

// ErrHelloFirst answers a request sent before the hello handshake.
var ErrHelloFirst = errors.New("protocol: send hello first")

// ErrClosed answers a reverse call whose connection ended.
var ErrClosed = errors.New("protocol: connection closed")

// MaxLine is the longest line either side reads.
const MaxLine = 16 << 20

// Request is one method call from the client.
type Request struct {
	Method string
	Args   json.RawMessage
	// File is the descriptor the client attached to the request, or nil. The
	// method owns it and must close it.
	File *os.File
}

// Handler answers one request with a JSON result.
type Handler func(ctx context.Context, request Request) (json.RawMessage, error)

type frameIn struct {
	ID     json.RawMessage `json:"id,omitempty"`
	Method string          `json:"method,omitempty"`
	Args   json.RawMessage `json:"args,omitempty"`
	FD     bool            `json:"fd,omitempty"`
	Reply  json.RawMessage `json:"reply,omitempty"`
	Result json.RawMessage `json:"result,omitempty"`
	Error  *string         `json:"error,omitempty"`
}

type response struct {
	ID     json.RawMessage `json:"id"`
	Result json.RawMessage `json:"result,omitempty"`
	Error  *string         `json:"error,omitempty"`
}

type event struct {
	Event   string          `json:"event"`
	Payload json.RawMessage `json:"payload"`
}

type call struct {
	Call   int64           `json:"call"`
	Method string          `json:"method"`
	Args   json.RawMessage `json:"args"`
}

type callResult struct {
	result json.RawMessage
	err    error
}

// Server serves one client connection.
type Server struct {
	handler Handler

	writeAccess sync.Mutex
	out         io.Writer

	helloAccess sync.Mutex
	hello       bool

	callAccess sync.Mutex
	nextCall   int64
	calls      map[int64]chan callResult
	closed     bool
}

// NewServer answers requests with handler and writes to out.
func NewServer(handler Handler, out io.Writer) *Server {
	return &Server{handler: handler, out: out, calls: make(map[int64]chan callResult)}
}

// Ready reports whether the client finished the hello handshake.
func (s *Server) Ready() bool {
	s.helloAccess.Lock()
	defer s.helloAccess.Unlock()
	return s.hello
}

// Emit sends an event; payload is its JSON value (a string that is not valid
// JSON is sent as a JSON string). Events before the handshake are dropped.
func (s *Server) Emit(kind string, payload any) {
	if !s.Ready() {
		return
	}
	s.write(event{Event: kind, Payload: encode(payload)})
}

// Call asks the client to run method (a host method of the protocol) and
// waits for its reply or for ctx to end.
func (s *Server) Call(ctx context.Context, method string, args any) (json.RawMessage, error) {
	if !s.Ready() {
		return nil, ErrHelloFirst
	}
	s.callAccess.Lock()
	if s.closed {
		s.callAccess.Unlock()
		return nil, ErrClosed
	}
	s.nextCall++
	id := s.nextCall
	wait := make(chan callResult, 1)
	s.calls[id] = wait
	s.callAccess.Unlock()
	defer func() {
		s.callAccess.Lock()
		delete(s.calls, id)
		s.callAccess.Unlock()
	}()
	s.write(call{Call: id, Method: method, Args: encode(args)})
	select {
	case result := <-wait:
		return result.result, result.err
	case <-ctx.Done():
		return nil, ctx.Err()
	}
}

// Serve reads from conn until it ends, answers every request and returns
// once every answer was written. io.EOF is not an error. When conn is a
// *net.UnixConn, descriptors attached to requests are received.
func (s *Server) Serve(ctx context.Context, conn io.Reader) error {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	reader := &fdReader{}
	if unixConn, ok := conn.(*net.UnixConn); ok {
		reader.conn = unixConn
	} else {
		reader.plain = conn
	}
	defer reader.closeAll()
	scanner := bufio.NewScanner(reader)
	scanner.Buffer(make([]byte, 64<<10), MaxLine)
	var running sync.WaitGroup
	defer running.Wait()
	defer s.endCalls()
	for scanner.Scan() {
		line := scanner.Bytes()
		if len(line) == 0 {
			continue
		}
		var frame frameIn
		if err := json.Unmarshal(line, &frame); err != nil {
			s.answer(nil, nil, errors.New("protocol: invalid request"))
			continue
		}
		if len(frame.Reply) > 0 {
			s.deliver(frame)
			continue
		}
		var file *os.File
		if frame.FD {
			file = reader.take()
		}
		if frame.Method == "" {
			closeFile(file)
			s.answer(frame.ID, nil, errors.New("protocol: invalid request"))
			continue
		}
		request := Request{Method: frame.Method, Args: frame.Args, File: file}
		if frame.Method == "hello" {
			// The handshake runs before anything else is read.
			result, err := s.handler(ctx, request)
			if err == nil {
				s.helloAccess.Lock()
				s.hello = true
				s.helloAccess.Unlock()
			}
			s.answer(frame.ID, result, err)
			continue
		}
		if !s.Ready() {
			closeFile(file)
			s.answer(frame.ID, nil, ErrHelloFirst)
			continue
		}
		running.Add(1)
		go func(id json.RawMessage, request Request) {
			defer running.Done()
			result, err := s.handler(ctx, request)
			s.answer(id, result, err)
		}(frame.ID, request)
	}
	return scanner.Err()
}

func (s *Server) deliver(frame frameIn) {
	id, err := strconv.ParseInt(string(frame.Reply), 10, 64)
	if err != nil {
		return
	}
	s.callAccess.Lock()
	wait := s.calls[id]
	s.callAccess.Unlock()
	if wait == nil {
		return
	}
	if frame.Error != nil {
		wait <- callResult{err: errors.New(*frame.Error)}
	} else {
		wait <- callResult{result: frame.Result}
	}
}

func (s *Server) endCalls() {
	s.callAccess.Lock()
	defer s.callAccess.Unlock()
	s.closed = true
	for id, wait := range s.calls {
		wait <- callResult{err: ErrClosed}
		delete(s.calls, id)
	}
}

func (s *Server) answer(id json.RawMessage, result json.RawMessage, err error) {
	if len(id) == 0 {
		id = json.RawMessage("null")
	}
	frame := response{ID: id}
	if err != nil {
		message := err.Error()
		frame.Error = &message
	} else {
		if len(result) == 0 || !json.Valid(result) {
			result = json.RawMessage("null")
		}
		frame.Result = result
	}
	s.write(frame)
}

func (s *Server) write(value any) {
	encoded, err := json.Marshal(value)
	if err != nil {
		return
	}
	encoded = append(encoded, '\n')
	s.writeAccess.Lock()
	_, _ = s.out.Write(encoded)
	s.writeAccess.Unlock()
}

func encode(value any) json.RawMessage {
	switch v := value.(type) {
	case nil:
		return json.RawMessage("null")
	case json.RawMessage:
		if json.Valid(v) {
			return v
		}
		quoted, _ := json.Marshal(string(v))
		return quoted
	case string:
		if json.Valid([]byte(v)) {
			return json.RawMessage(v)
		}
		quoted, _ := json.Marshal(v)
		return quoted
	}
	encoded, err := json.Marshal(value)
	if err != nil {
		quoted, _ := json.Marshal(fmt.Sprint(value))
		return quoted
	}
	return encoded
}

func closeFile(file *os.File) {
	if file != nil {
		_ = file.Close()
	}
}

// fdReader reads a stream and keeps the descriptors that arrived with it, in
// order.
type fdReader struct {
	conn  *net.UnixConn
	plain io.Reader

	access sync.Mutex
	files  []*os.File
}

func (r *fdReader) Read(p []byte) (int, error) {
	if r.conn == nil {
		return r.plain.Read(p)
	}
	oob := make([]byte, syscall.CmsgSpace(4*8))
	n, oobn, _, _, err := r.conn.ReadMsgUnix(p, oob)
	if oobn > 0 {
		r.keep(oob[:oobn])
	}
	return n, err
}

func (r *fdReader) keep(oob []byte) {
	messages, err := syscall.ParseSocketControlMessage(oob)
	if err != nil {
		return
	}
	r.access.Lock()
	defer r.access.Unlock()
	for i := range messages {
		fds, err := syscall.ParseUnixRights(&messages[i])
		if err != nil {
			continue
		}
		for _, fd := range fds {
			syscall.CloseOnExec(fd)
			r.files = append(r.files, os.NewFile(uintptr(fd), "fd"))
		}
	}
}

func (r *fdReader) take() *os.File {
	r.access.Lock()
	defer r.access.Unlock()
	if len(r.files) == 0 {
		return nil
	}
	file := r.files[0]
	r.files = r.files[1:]
	return file
}

func (r *fdReader) closeAll() {
	r.access.Lock()
	defer r.access.Unlock()
	for _, file := range r.files {
		_ = file.Close()
	}
	r.files = nil
}
