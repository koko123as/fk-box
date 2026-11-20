package libbox

import (
	"encoding/json"
	"fmt"
	"testing"
)

type uiRecordingSink struct{ kinds []string }

func (s *uiRecordingSink) Emit(kind string, payload string) { s.kinds = append(s.kinds, kind) }

func TestUICoreInProcess(t *testing.T) {
	defer uiSetClient(nil)
	var core Core = NewInProcessCore(nil)

	result, err := core.Hello(fmt.Sprintf(`{"protocolVersion": %d, "client": "android", "clientVersion": "0.2.0", "capabilities": ["vpn_service"]}`, ProtocolVersion))
	if err != nil {
		t.Fatal(err)
	}
	var reply struct {
		OK           bool     `json:"ok"`
		Capabilities []string `json:"capabilities"`
	}
	if err = json.Unmarshal([]byte(result), &reply); err != nil || !reply.OK || len(reply.Capabilities) != 1 {
		t.Fatalf("hello: %s %v", result, err)
	}
	if _, err = core.Hello(`{"protocolVersion": 999}`); err == nil {
		t.Fatal("mismatched hello accepted")
	}

	// Without a CommandServer the lifecycle calls fail instead of panicking.
	if err = core.Start(CoreAuto, "{}", nil); err != errInProcessCoreNoServer {
		t.Fatalf("Start: %v", err)
	}
	if err = core.Reload(CoreAuto, "{}", nil); err != errInProcessCoreNoServer {
		t.Fatalf("Reload: %v", err)
	}
	if err = core.Stop(); err != errInProcessCoreNoServer {
		t.Fatalf("Stop: %v", err)
	}
	if err = core.SelectOutbound("proxy", "a"); err != errInProcessCoreNoServer {
		t.Fatalf("SelectOutbound: %v", err)
	}

	// Events reach the sink the Core installs.
	sink := &uiRecordingSink{}
	core.SetEventSink(sink)
	defer core.SetEventSink(nil)
	var event string
	for name := range uiProtocolEvents {
		event = name
		break
	}
	if err = uiEmitRaw(event, "null"); err != nil {
		t.Fatal(err)
	}
	if len(sink.kinds) != 1 || sink.kinds[0] != event {
		t.Fatalf("sink got %v, want [%s]", sink.kinds, event)
	}
}
