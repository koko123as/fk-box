package protocol

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net"
	"os"
	"strings"
	"syscall"
	"testing"
	"time"
)

type frame struct {
	ID      json.RawMessage `json:"id"`
	Result  json.RawMessage `json:"result"`
	Error   *string         `json:"error"`
	Event   string          `json:"event"`
	Payload json.RawMessage `json:"payload"`
	Call    int64           `json:"call"`
	Method  string          `json:"method"`
}

func TestServerHelloFirstThenMethods(t *testing.T) {
	var out strings.Builder
	var server *Server
	server = NewServer(func(ctx context.Context, request Request) (json.RawMessage, error) {
		switch request.Method {
		case "hello":
			return json.RawMessage(`{"ok":true}`), nil
		case "checkConfig":
			server.Emit("logs", `{"reset":true}`)
			return json.RawMessage("true"), nil
		}
		return nil, errors.New("unknown method: " + request.Method)
	}, &syncWriter{w: &out})
	server.Emit("logs", `{}`) // before hello: dropped
	input := strings.Join([]string{
		`{"id":1,"method":"checkConfig","args":{}}`,
		`not json`,
		`{"id":2,"method":"hello","args":{"protocolVersion":1}}`,
		`{"id":"three","method":"checkConfig","args":{"content":"{}"}}`,
		`{"id":4,"method":"nope"}`,
	}, "\n")
	if err := server.Serve(context.Background(), strings.NewReader(input)); err != nil {
		t.Fatal(err)
	}
	byID := map[string]frame{}
	events := 0
	for _, line := range strings.Split(strings.TrimSpace(out.String()), "\n") {
		var f frame
		if err := json.Unmarshal([]byte(line), &f); err != nil {
			t.Fatalf("bad frame %q: %v", line, err)
		}
		if f.Event != "" {
			events++
			continue
		}
		byID[string(f.ID)] = f
	}
	if f := byID["1"]; f.Error == nil || *f.Error != ErrHelloFirst.Error() {
		t.Fatalf("request before hello: %+v", f)
	}
	if f := byID["null"]; f.Error == nil {
		t.Fatalf("invalid line: %+v", f)
	}
	if f := byID["2"]; string(f.Result) != `{"ok":true}` {
		t.Fatalf("hello: %+v", f)
	}
	if f := byID[`"three"`]; string(f.Result) != "true" {
		t.Fatalf("method after hello: %+v", f)
	}
	if f := byID["4"]; f.Error == nil {
		t.Fatalf("unknown method: %+v", f)
	}
	if events != 1 {
		t.Fatalf("events = %d, want 1", events)
	}
}

func TestServerReverseCall(t *testing.T) {
	clientSide, coreSide := net.Pipe()
	defer clientSide.Close()
	var server *Server
	server = NewServer(func(ctx context.Context, request Request) (json.RawMessage, error) {
		switch request.Method {
		case "hello":
			return json.RawMessage("{}"), nil
		case "sign":
			result, err := server.Call(ctx, "captureSign", map[string]string{"digest": "AA"})
			if err != nil {
				return nil, err
			}
			return result, nil
		}
		return nil, errors.New("unknown")
	}, coreSide)
	go server.Serve(context.Background(), coreSide)
	reader := bufio.NewReader(clientSide)
	send := func(line string) {
		if _, err := io.WriteString(clientSide, line+"\n"); err != nil {
			t.Fatal(err)
		}
	}
	read := func() frame {
		line, err := reader.ReadString('\n')
		if err != nil {
			t.Fatal(err)
		}
		var f frame
		if err := json.Unmarshal([]byte(line), &f); err != nil {
			t.Fatal(err)
		}
		return f
	}
	send(`{"id":1,"method":"hello","args":{}}`)
	read()
	send(`{"id":2,"method":"sign","args":{}}`)
	callFrame := read()
	if callFrame.Method != "captureSign" || callFrame.Call == 0 {
		t.Fatalf("expected a reverse call, got %+v", callFrame)
	}
	send(`{"reply":` + jsonInt(callFrame.Call) + `,"result":"c2ln"}`)
	answer := read()
	if string(answer.ID) != "2" || string(answer.Result) != `"c2ln"` {
		t.Fatalf("answer: %+v", answer)
	}
}

func TestServerReceivesDescriptor(t *testing.T) {
	fds, err := syscall.Socketpair(syscall.AF_UNIX, syscall.SOCK_STREAM, 0)
	if err != nil {
		t.Fatal(err)
	}
	clientFile := os.NewFile(uintptr(fds[0]), "client")
	coreFile := os.NewFile(uintptr(fds[1]), "core")
	clientConn, _ := net.FileConn(clientFile)
	coreConn, _ := net.FileConn(coreFile)
	clientFile.Close()
	coreFile.Close()
	defer clientConn.Close()
	defer coreConn.Close()

	pipeRead, pipeWrite, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	defer pipeRead.Close()
	received := make(chan *os.File, 1)
	server := NewServer(func(ctx context.Context, request Request) (json.RawMessage, error) {
		if request.Method == "attach" {
			received <- request.File
		}
		return json.RawMessage("true"), nil
	}, coreConn)
	go server.Serve(context.Background(), coreConn)

	unixClient := clientConn.(*net.UnixConn)
	if _, err := unixClient.Write([]byte(`{"id":1,"method":"hello","args":{}}` + "\n")); err != nil {
		t.Fatal(err)
	}
	rights := syscall.UnixRights(int(pipeWrite.Fd()))
	if _, _, err := unixClient.WriteMsgUnix([]byte(`{"id":2,"method":"attach","fd":true}`+"\n"), rights, nil); err != nil {
		t.Fatal(err)
	}
	pipeWrite.Close()
	select {
	case file := <-received:
		if file == nil {
			t.Fatal("no descriptor received")
		}
		if _, err := file.Write([]byte("ok")); err != nil {
			t.Fatal(err)
		}
		file.Close()
	case <-time.After(5 * time.Second):
		t.Fatal("timeout")
	}
	buffer := make([]byte, 2)
	if _, err := io.ReadFull(pipeRead, buffer); err != nil || string(buffer) != "ok" {
		t.Fatalf("read through the received descriptor: %q %v", buffer, err)
	}
}

func jsonInt(v int64) string {
	encoded, _ := json.Marshal(v)
	return string(encoded)
}

type syncWriter struct {
	w io.Writer
}

func (s *syncWriter) Write(p []byte) (int, error) { return s.w.Write(p) }
