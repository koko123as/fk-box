package fk

import (
	"encoding/json"
	"reflect"
	"sort"
	"strings"
	"testing"
)

// Go answers exactly the methods protocol/methods.json gives to it.
func TestUIMethodTableMatchesProtocol(t *testing.T) {
	var owned []string
	for name, owner := range uiProtocolMethods {
		if owner == "go" {
			owned = append(owned, name)
		}
	}
	sort.Strings(owned)
	if got := uiMethodNames(); !reflect.DeepEqual(got, owned) {
		t.Fatalf("Go handlers %v, protocol owner go %v", got, owned)
	}
	for _, name := range owned {
		if !UIHandlesMethod(name) {
			t.Fatalf("UIHandlesMethod(%q) = false", name)
		}
	}
	if UIHandlesMethod("startService") {
		t.Fatal("a host method reported as handled by Go")
	}
}

func TestUIMethodUnknownAndInvalid(t *testing.T) {
	if _, err := UIInvoke("noSuchMethod", "{}"); err == nil || !strings.Contains(err.Error(), "unknown method") {
		t.Fatalf("unknown method: %v", err)
	}
	if _, err := UIInvoke("formatConfig", "{"); err == nil {
		t.Fatal("invalid JSON accepted")
	}
	if _, err := UIInvoke("formatConfig", `{"content": 1}`); err == nil {
		t.Fatal("wrong argument type accepted")
	}
}

func TestUIMethodFormatConfig(t *testing.T) {
	args, _ := json.Marshal(map[string]any{"core": nil, "content": `{"log":{"level":"info"}}`})
	result, err := UIInvoke("formatConfig", string(args))
	if err != nil {
		t.Fatal(err)
	}
	var formatted string
	if err = json.Unmarshal([]byte(result), &formatted); err != nil {
		t.Fatalf("result %q is not a JSON string: %v", result, err)
	}
	direct, err := FormatCoreConfig(CoreSingBox, `{"log":{"level":"info"}}`)
	if err != nil {
		t.Fatal(err)
	}
	if formatted != direct.Value {
		t.Fatalf("formatConfig %q, FormatCoreConfig %q", formatted, direct.Value)
	}
	// An absent core is "auto", like the Kotlin host's `core ?: "auto"`.
	if again, err := UIInvoke("formatConfig", `{"content":"{\"log\":{\"level\":\"info\"}}"}`); err != nil || again != result {
		t.Fatalf("absent core: %q, %v", again, err)
	}
	if _, err = UIInvoke("formatConfig", `{"core":"xray","content":"not json"}`); err == nil {
		t.Fatal("invalid xray config formatted")
	}
}

type uiTestSink struct{ kinds, payloads []string }

func (s *uiTestSink) Emit(kind string, payload string) {
	s.kinds = append(s.kinds, kind)
	s.payloads = append(s.payloads, payload)
}

func TestUIMethodEvents(t *testing.T) {
	sink := &uiTestSink{}
	SetUIEventSink(sink)
	defer SetUIEventSink(nil)
	if err := uiEmit("noSuchEvent", 1); err == nil {
		t.Fatal("unknown event raised")
	}
	if err := uiEmit("memory", map[string]int{"heap": 1}); err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(sink.kinds, []string{"memory"}) || sink.payloads[0] != `{"heap":1}` {
		t.Fatalf("events %v %v", sink.kinds, sink.payloads)
	}
	SetUIEventSink(nil)
	if err := uiEmit("memory", 2); err != nil || len(sink.kinds) != 1 {
		t.Fatalf("event without a sink: %v %v", err, sink.kinds)
	}
}
