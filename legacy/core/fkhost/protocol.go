package fkhost

import (
	"bufio"
	"encoding/json"
	"errors"
	"io"
	"sync"
)

// fk-box's own command service for clients outside the core's process (the
// desktop core, cmd/fk-core): the UI protocol of protocol/methods.json as
// JSON lines over any byte stream (stdio, a local socket). Android does not
// use it: its UI calls the core in-process through gomobile and reads
// sing-box through libbox's StartedService (core/README.md).
//
// One JSON object per line, in each direction:
//
//	client -> core  {"id": 1, "method": "hello", "args": {...}}
//	core -> client  {"id": 1, "result": ...} or {"id": 1, "error": "text"}
//	core -> client  {"event": "logs", "payload": ...}
//
// The first request must be "hello"; anything before it is answered with
// ErrHelloFirst. Requests run concurrently; responses carry their id.

// ErrHelloFirst answers a request sent before the hello handshake.
var ErrHelloFirst = errors.New("protocol: send hello first")

// ProtocolInvoke answers one method: JSON arguments in, a JSON result out.
type ProtocolInvoke func(method string, args string) (string, error)

// ProtocolMaxLine is the longest request line the server reads.
const ProtocolMaxLine = 16 << 20

type protocolRequest struct {
	ID     json.RawMessage `json:"id"`
	Method string          `json:"method"`
	Args   json.RawMessage `json:"args"`
}

type protocolResponse struct {
	ID     json.RawMessage `json:"id"`
	Result json.RawMessage `json:"result,omitempty"`
	Error  *string         `json:"error,omitempty"`
}

type protocolEvent struct {
	Event   string          `json:"event"`
	Payload json.RawMessage `json:"payload"`
}

// ProtocolServer serves one client connection.
type ProtocolServer struct {
	invoke ProtocolInvoke

	writeAccess sync.Mutex
	out         io.Writer

	helloAccess sync.Mutex
	hello       bool
}

// NewProtocolServer answers requests with invoke and writes to out.
func NewProtocolServer(invoke ProtocolInvoke, out io.Writer) *ProtocolServer {
	return &ProtocolServer{invoke: invoke, out: out}
}

// Emit sends an event; payload is its JSON value. Events sent before the
// hello handshake are dropped.
func (s *ProtocolServer) Emit(kind string, payload string) {
	s.helloAccess.Lock()
	ready := s.hello
	s.helloAccess.Unlock()
	if !ready {
		return
	}
	raw := json.RawMessage(payload)
	if !json.Valid(raw) {
		encoded, _ := json.Marshal(payload)
		raw = encoded
	}
	s.write(protocolEvent{Event: kind, Payload: raw})
}

// Serve reads requests from in until it ends, answers each, and returns
// once every answer was written. io.EOF is not an error.
func (s *ProtocolServer) Serve(in io.Reader) error {
	scanner := bufio.NewScanner(in)
	scanner.Buffer(make([]byte, 64<<10), ProtocolMaxLine)
	var running sync.WaitGroup
	defer running.Wait()
	for scanner.Scan() {
		line := scanner.Bytes()
		if len(line) == 0 {
			continue
		}
		var request protocolRequest
		if err := json.Unmarshal(line, &request); err != nil || request.Method == "" {
			s.answer(request.ID, "", errors.New("protocol: invalid request"))
			continue
		}
		if request.Method == "hello" {
			// The handshake runs before anything else is read.
			result, err := s.invoke(request.Method, string(request.Args))
			if err == nil {
				s.helloAccess.Lock()
				s.hello = true
				s.helloAccess.Unlock()
			}
			s.answer(request.ID, result, err)
			continue
		}
		s.helloAccess.Lock()
		ready := s.hello
		s.helloAccess.Unlock()
		if !ready {
			s.answer(request.ID, "", ErrHelloFirst)
			continue
		}
		running.Add(1)
		go func(request protocolRequest) {
			defer running.Done()
			result, err := s.invoke(request.Method, string(request.Args))
			s.answer(request.ID, result, err)
		}(request)
	}
	return scanner.Err()
}

func (s *ProtocolServer) answer(id json.RawMessage, result string, err error) {
	if len(id) == 0 {
		id = json.RawMessage("null")
	}
	response := protocolResponse{ID: id}
	if err != nil {
		message := err.Error()
		response.Error = &message
	} else {
		if result == "" || !json.Valid([]byte(result)) {
			result = "null"
		}
		response.Result = json.RawMessage(result)
	}
	s.write(response)
}

func (s *ProtocolServer) write(value any) {
	encoded, err := json.Marshal(value)
	if err != nil {
		return
	}
	encoded = append(encoded, '\n')
	s.writeAccess.Lock()
	_, _ = s.out.Write(encoded)
	s.writeAccess.Unlock()
}
