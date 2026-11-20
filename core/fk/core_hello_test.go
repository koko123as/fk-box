package fk

import (
	"encoding/json"
	"fmt"
	"strings"
	"testing"
)

func TestUIHelloMatch(t *testing.T) {
	defer uiSetClient(nil)
	result, err := UIInvoke("hello", fmt.Sprintf(`{"protocolVersion": %d, "client": "android", "clientVersion": "0.2.0"}`, ProtocolVersion))
	if err != nil {
		t.Fatal(err)
	}
	var reply map[string]any
	if err = json.Unmarshal([]byte(result), &reply); err != nil {
		t.Fatal(err)
	}
	if reply["ok"] != true || reply["protocolVersion"] != float64(ProtocolVersion) || reply["coreVersion"] != singBoxLibboxVersion() {
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

func TestUIHelloCapabilities(t *testing.T) {
	defer uiSetClient(nil)
	result, err := UIInvoke("hello", fmt.Sprintf(`{"protocolVersion": %d, "client": "android", "clientVersion": "0.2.0",
		"capabilities": ["usbip", "vpn_service", "teleport", "usbip"]}`, ProtocolVersion))
	if err != nil {
		t.Fatal(err)
	}
	var reply struct {
		Capabilities []string `json:"capabilities"`
	}
	if err = json.Unmarshal([]byte(result), &reply); err != nil {
		t.Fatal(err)
	}
	if strings.Join(reply.Capabilities, ",") != "usbip,vpn_service" {
		t.Fatalf("effective capabilities %v, want known names deduplicated and sorted", reply.Capabilities)
	}
	if !UIClientHasCapability("usbip") || !UIClientHasCapability("vpn_service") || UIClientHasCapability("teleport") || UIClientHasCapability("taildrop") {
		t.Fatal("stored capabilities disagree with the answer")
	}
	// A client that declares nothing gets an empty list (not null) and clears the old set.
	result, err = UIInvoke("hello", fmt.Sprintf(`{"protocolVersion": %d, "client": "desktop"}`, ProtocolVersion))
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(result, `"capabilities":[]`) || UIClientHasCapability("usbip") {
		t.Fatalf("no capabilities: %s", result)
	}
	// A rejected handshake leaves the stored set alone.
	uiSetClient([]string{"taildrop"})
	if _, err = UIInvoke("hello", `{"protocolVersion": 0, "capabilities": []}`); err == nil || !UIClientHasCapability("taildrop") {
		t.Fatalf("mismatch changed capabilities: %v", err)
	}
}

// Every capability the protocol lists is known to the core.
func TestUIHelloKnowsProtocolCapabilities(t *testing.T) {
	defer uiSetClient(nil)
	var all []string
	for name := range uiProtocolCapabilities {
		all = append(all, name)
	}
	if len(all) == 0 {
		t.Fatal("protocol lists no capabilities")
	}
	if got := uiSetClient(all); len(got) != len(all) {
		t.Fatalf("effective %v, declared %v", got, all)
	}
}
