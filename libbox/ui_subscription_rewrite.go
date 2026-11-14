package libbox

import (
	"bytes"
	"encoding/json"
	"fmt"
	"strings"
)

// A profile's experimental.http_rewrite runs with the user's own MITM CA: its
// map_remote, header and script rules can read and forward decrypted cookies
// and authorization headers. A subscription provider must not be able to
// install such rules, so they are removed from every fetched subscription
// (a sing-box profile, or each element of a config array) before the app
// saves it. Rules the user writes into a local profile are unaffected.
//
// Keys are matched case-insensitively, as the profile decoder matches them,
// and the content is decoded instead of searched so an escaped key name
// cannot slip through.
func stripSubscriptionHTTPRewrite(content string) (string, bool) {
	// Self-contained (no core_*.go helpers): the ui_*.go bindings are
	// installed before the multi-core files.
	text := strings.TrimSpace(strings.TrimPrefix(content, "\ufeff"))
	switch {
	case strings.HasPrefix(text, "{"):
		stripped, changed, err := stripProfileHTTPRewrite([]byte(text))
		if err != nil || !changed {
			return content, false
		}
		return string(stripped), true
	case strings.HasPrefix(text, "["):
		var elements []json.RawMessage
		if json.Unmarshal([]byte(text), &elements) != nil {
			return content, false
		}
		stripped := false
		for index, element := range elements {
			trimmed := bytes.TrimSpace(element)
			if len(trimmed) == 0 || trimmed[0] != '{' {
				continue
			}
			result, changed, err := stripProfileHTTPRewrite(trimmed)
			if err != nil {
				return content, false
			}
			if changed {
				elements[index], stripped = result, true
			}
		}
		if !stripped {
			return content, false
		}
		var out bytes.Buffer
		out.WriteByte('[')
		for index, element := range elements {
			if index > 0 {
				out.WriteByte(',')
			}
			out.Write(bytes.TrimSpace(element))
		}
		out.WriteByte(']')
		return out.String(), true
	}
	return content, false
}

// stripProfileHTTPRewrite removes http_rewrite from every top-level
// "experimental" object of one JSON object profile, keeping the order and
// bytes of everything else.
func stripProfileHTTPRewrite(raw []byte) ([]byte, bool, error) {
	changed := false
	result, err := rewriteJSONObject(raw, func(name string, value json.RawMessage) (json.RawMessage, bool, error) {
		if !strings.EqualFold(name, "experimental") {
			return value, true, nil
		}
		trimmed := bytes.TrimSpace(value)
		if len(trimmed) == 0 || trimmed[0] != '{' {
			return value, true, nil
		}
		stripped, err := rewriteJSONObject(trimmed, func(name string, value json.RawMessage) (json.RawMessage, bool, error) {
			if strings.EqualFold(name, "http_rewrite") {
				changed = true
				return nil, false, nil
			}
			return value, true, nil
		})
		return stripped, true, err
	})
	if err != nil || !changed {
		return raw, false, err
	}
	return result, true, nil
}

// rewriteJSONObject re-emits a JSON object, passing each member to edit,
// which returns the new value and whether to keep the member. Untouched
// values are copied byte for byte.
func rewriteJSONObject(raw []byte, edit func(name string, value json.RawMessage) (json.RawMessage, bool, error)) ([]byte, error) {
	decoder := json.NewDecoder(bytes.NewReader(raw))
	token, err := decoder.Token()
	if err != nil {
		return nil, err
	}
	if delim, ok := token.(json.Delim); !ok || delim != '{' {
		return nil, fmt.Errorf("not a JSON object")
	}
	var out bytes.Buffer
	out.WriteByte('{')
	first := true
	for decoder.More() {
		token, err := decoder.Token()
		if err != nil {
			return nil, err
		}
		name, ok := token.(string)
		if !ok {
			return nil, fmt.Errorf("invalid object key")
		}
		var value json.RawMessage
		if err := decoder.Decode(&value); err != nil {
			return nil, err
		}
		value, keep, err := edit(name, value)
		if err != nil {
			return nil, err
		}
		if !keep {
			continue
		}
		if !first {
			out.WriteByte(',')
		}
		first = false
		encodedName, _ := json.Marshal(name)
		out.Write(encodedName)
		out.WriteByte(':')
		out.Write(value)
	}
	if _, err := decoder.Token(); err != nil {
		return nil, err
	}
	if decoder.More() {
		return nil, fmt.Errorf("data after the JSON object")
	}
	out.WriteByte('}')
	return out.Bytes(), nil
}
