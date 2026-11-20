package libbox

import (
	"encoding/json"
	"fmt"
	"strings"
	"testing"
)

func TestUIHelloMatch(t *testing.T) {
	result, err := UIInvoke("hello", fmt.Sprintf(`{"protocolVersion": %d, "client": "android", "clientVersion": "0.2.0"}`, ProtocolVersion))
	if err != nil {
		t.Fatal(err)
	}
	var reply map[string]any
	if err = json.Unmarshal([]byte(result), &reply); err != nil {
		t.Fatal(err)
	}
	if reply["ok"] != true || reply["protocolVersion"] != float64(ProtocolVersion) || reply["coreVersion"] != Version() {
		t.Fatalf("reply %s", result)
	}
}

func TestUIHelloMismatch(t *testing.T) {
	for _, client := range []int{ProtocolVersion + 1, ProtocolMinClient - 1} {
		_, err := UIInvoke("hello", fmt.Sprintf(`{"protocolVersion": %d, "client": "android", "clientVersion": "0.2.0"}`, client))
		want := fmt.Sprintf("protocol version mismatch: core=%d client=%d", ProtocolVersion, client)
		if err == nil || err.Error() != want {
			t.Fatalf("client %d: %v, want %q", client, err, want)
		}
	}
	// A core that still accepts older clients takes them, and only them.
	if _, err := uiHelloCheck(2, 2, 3); err != nil {
		t.Fatal(err)
	}
	if _, err := uiHelloCheck(1, 2, 3); err == nil || err.Error() != "protocol version mismatch: core=3 client=1" {
		t.Fatalf("below minClient: %v", err)
	}
}

func TestUIHelloMissingField(t *testing.T) {
	for _, args := range []string{"", `{}`, `{"client": "android", "clientVersion": "0.2.0"}`} {
		if _, err := UIInvoke("hello", args); err == nil || !strings.Contains(err.Error(), "missing protocolVersion") {
			t.Fatalf("args %q: %v", args, err)
		}
	}
	if _, err := UIInvoke("hello", `{"protocolVersion": "1"}`); err == nil || !strings.Contains(err.Error(), "invalid method arguments") {
		t.Fatalf("string version: %v", err)
	}
}
