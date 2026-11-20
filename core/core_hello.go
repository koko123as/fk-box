package libbox

import (
	"encoding/json"
	"errors"
	"fmt"
)

// uiHelloReply is hello's answer.
type uiHelloReply struct {
	ProtocolVersion int    `json:"protocolVersion"`
	CoreVersion     string `json:"coreVersion"`
	OK              bool   `json:"ok"`
}

// hello {protocolVersion, client, clientVersion}: the start-up handshake. The
// client's protocol version must lie between ProtocolMinClient and
// ProtocolVersion; otherwise the call fails with
// "protocol version mismatch: core=X client=Y" and the UI stops there.
func uiHello(args json.RawMessage) (any, error) {
	var request struct {
		ProtocolVersion *int   `json:"protocolVersion"`
		Client          string `json:"client"`
		ClientVersion   string `json:"clientVersion"`
	}
	if err := uiDecodeArgs(args, &request); err != nil {
		return nil, err
	}
	if request.ProtocolVersion == nil {
		return nil, errors.New("hello: missing protocolVersion")
	}
	return uiHelloCheck(*request.ProtocolVersion, ProtocolMinClient, ProtocolVersion)
}

func uiHelloCheck(client, minClient, core int) (*uiHelloReply, error) {
	if client < minClient || client > core {
		return nil, fmt.Errorf("protocol version mismatch: core=%d client=%d", core, client)
	}
	return &uiHelloReply{ProtocolVersion: core, CoreVersion: Version(), OK: true}, nil
}
