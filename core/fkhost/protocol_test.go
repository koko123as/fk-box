package fkhost

import (
	"bytes"
	"encoding/json"
	"errors"
	"strings"
	"testing"
)

func TestProtocolServerHelloFirstThenMethods(t *testing.T) {
	var out bytes.Buffer
	var server *ProtocolServer
	server = NewProtocolServer(func(method, args string) (string, error) {
		switch method {
		case "hello":
			return `{"ok":true}`, nil
		case "checkConfig":
			server.Emit("logs", `{"reset":true}`)
			return "true", nil
		}
		return "", errors.New("unknown method: " + method)
	}, &out)
	server.Emit("logs", `{}`) // before hello: dropped
	input := strings.Join([]string{
		`{"id":1,"method":"checkConfig","args":{}}`,
		`not json`,
		`{"id":2,"method":"hello","args":{"protocolVersion":1}}`,
		`{"id":"three","method":"checkConfig","args":{"content":"{}"}}`,
		`{"id":4,"method":"nope"}`,
	}, "\n")
	if err := server.Serve(strings.NewReader(input)); err != nil {
		t.Fatal(err)
	}
	type frame struct {
		ID      json.RawMessage `json:"id"`
		Result  json.RawMessage `json:"result"`
		Error   *string         `json:"error"`
		Event   string          `json:"event"`
		Payload json.RawMessage `json:"payload"`
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
		t.Fatalf("method: %+v", f)
	}
	if f := byID["4"]; f.Error == nil || !strings.Contains(*f.Error, "unknown method") {
		t.Fatalf("unknown: %+v", f)
	}
	if events != 1 {
		t.Fatalf("events: %d, want 1", events)
	}
}
