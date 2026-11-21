package fk

import (
	"encoding/json"
	"errors"
	"fmt"
	"sort"
	"sync"
)

// uiHelloReply is hello's answer.
type uiHelloReply struct {
	ProtocolVersion int      `json:"protocolVersion"`
	CoreVersion     string   `json:"coreVersion"`
	OK              bool     `json:"ok"`
	Capabilities    []string `json:"capabilities"`
}

var (
	uiClientAccess       sync.RWMutex
	uiClientCapabilities = map[string]struct{}{}
)

// hello (coreVersion is the shipped sing-box version, as before) {protocolVersion, client, clientVersion, capabilities?}: the start-up
// handshake. The client's protocol version must lie between ProtocolMinClient
// and ProtocolVersion; otherwise the call fails with
// "protocol version mismatch: core=X client=Y" and the UI stops there. The
// declared capabilities (protocol/methods.json "capabilities") the core knows
// are kept for the session and answered, sorted, as the effective set;
// unknown names are dropped so an older core ignores newer features.
func uiHello(args json.RawMessage) (any, error) {
	var request struct {
		ProtocolVersion *int     `json:"protocolVersion"`
		Client          string   `json:"client"`
		ClientVersion   string   `json:"clientVersion"`
		Capabilities    []string `json:"capabilities"`
	}
	if err := uiDecodeArgs(args, &request); err != nil {
		return nil, err
	}
	if request.ProtocolVersion == nil {
		return nil, errors.New("hello: missing protocolVersion")
	}
	reply, err := uiHelloCheck(*request.ProtocolVersion, ProtocolMinClient, ProtocolVersion)
	if err != nil {
		return nil, err
	}
	reply.Capabilities = uiSetClient(request.Capabilities)
	return reply, nil
}

func uiHelloCheck(client, minClient, core int) (*uiHelloReply, error) {
	if client < minClient || client > core {
		return nil, fmt.Errorf("protocol version mismatch: core=%d client=%d", core, client)
	}
	return &uiHelloReply{ProtocolVersion: core, CoreVersion: singBoxLibboxVersion(), OK: true}, nil
}

// uiSetClient records the declared capabilities the protocol
// lists, and answers those sorted (never nil, so the JSON is a list).
func uiSetClient(declared []string) []string {
	effective := map[string]struct{}{}
	for _, name := range declared {
		if _, known := uiProtocolCapabilities[name]; known {
			effective[name] = struct{}{}
		}
	}
	names := make([]string, 0, len(effective))
	for name := range effective {
		names = append(names, name)
	}
	sort.Strings(names)
	uiClientAccess.Lock()
	uiClientCapabilities = effective
	uiClientAccess.Unlock()
	return names
}

