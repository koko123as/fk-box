package fk

// sing-box runs in the app process through the official libbox, not as an
// fk-core child (docs/kernel-isolation.md §2). fk-core still owns what it does
// for Xray and mihomo around the kernel: the profile gets a local mixed
// inbound on 127.0.0.1 with random credentials, so fk-core's own requests and
// the app's IP probe go through the profile's ordinary rules
// (ServiceLocalProxy, NewUIServiceEgressProbe).

import (
	"errors"
	"sync"
)

const singBoxLocalTag = "fkbox-local"

// singBoxLocal is the running sing-box's local inbound, nil while none runs.
type singBoxLocal struct {
	port     int
	password string
}

var (
	singBoxAccess sync.Mutex
	singBoxActive *singBoxLocal
	singBoxNext   *singBoxLocal
)

// SingBoxServiceContent answers content with the local inbound added. The
// first call after SingBoxServiceStopped picks the port and credentials; the
// next calls (the same start's fallback without the routing rules) reuse them.
func SingBoxServiceContent(content string) (string, error) {
	root, err := decodeOrderedJSON(content)
	if err != nil {
		return "", err
	}
	singBoxAccess.Lock()
	defer singBoxAccess.Unlock()
	if singBoxNext == nil {
		port, err := freeLocalPort()
		if err != nil {
			return "", err
		}
		singBoxNext = &singBoxLocal{port: port, password: randomHex(16)}
	}
	inbounds := anyList(root["inbounds"])
	for _, inbound := range inbounds {
		if anyString(anyMap(inbound)["tag"]) == singBoxLocalTag {
			return "", errors.New("the profile already has an inbound tagged " + singBoxLocalTag)
		}
	}
	root["inbounds"] = append(inbounds, map[string]any{
		"type":        "mixed",
		"tag":         singBoxLocalTag,
		"listen":      "127.0.0.1",
		"listen_port": singBoxNext.port,
		"users":       []any{map[string]any{"username": localReturnUser, "password": singBoxNext.password}},
	})
	return encodeRoutingJSON(root)
}

// SingBoxServiceStarted records that sing-box runs with the content
// SingBoxServiceContent prepared.
func SingBoxServiceStarted() {
	singBoxAccess.Lock()
	singBoxActive, singBoxNext = singBoxNext, nil
	singBoxAccess.Unlock()
}

// SingBoxServiceStopped records that no sing-box runs.
func SingBoxServiceStopped() {
	singBoxAccess.Lock()
	singBoxActive, singBoxNext = nil, nil
	singBoxAccess.Unlock()
}

func singBoxLocalProxy() *singBoxLocal {
	singBoxAccess.Lock()
	defer singBoxAccess.Unlock()
	return singBoxActive
}
