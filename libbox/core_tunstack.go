package libbox

import (
	"bytes"
	"encoding/json"

	tun "github.com/sagernet/sing-tun"
)

// dropUnbuiltTunStack gives each tun inbound the kernel ("system") stack
// unless its profile picked one this build runs. sing-box 1.15 otherwise
// defaults to sing-tun's userspace "go" stack, whose mmap'd buffers sit
// outside the Go heap and grow to a gigabyte on fast devices; "gvisor" and
// "mixed" also fall back here when this build has no gVisor, as many
// subscription generators still write "mixed". An explicit "system" or "go"
// is kept. The profile on disk is untouched; content that is not a JSON
// profile with inbounds is returned as is.
func dropUnbuiltTunStack(content string) string {
	if !bytes.Contains([]byte(content), []byte(`"tun"`)) {
		return content
	}
	var root map[string]json.RawMessage
	if json.Unmarshal([]byte(content), &root) != nil {
		return content
	}
	var inbounds []map[string]json.RawMessage
	if json.Unmarshal(root["inbounds"], &inbounds) != nil {
		return content
	}
	changed := false
	for _, inbound := range inbounds {
		var kind, stack string
		_ = json.Unmarshal(inbound["type"], &kind)
		_ = json.Unmarshal(inbound["stack"], &stack)
		if kind != "tun" || stack == "system" || stack == "go" || (tun.WithGVisor && (stack == "gvisor" || stack == "mixed")) {
			continue
		}
		inbound["stack"] = json.RawMessage(`"system"`)
		changed = true
	}
	if !changed {
		return content
	}
	encoded, err := json.Marshal(inbounds)
	if err != nil {
		return content
	}
	root["inbounds"] = encoded
	out, err := json.Marshal(root)
	if err != nil {
		return content
	}
	return string(out)
}
