package fk

import (
	"encoding/json"
	"errors"
	"fmt"
	"strings"
)

// RemoveUIRetiredCaptureOptions is a narrow upgrade sanitizer. It drops the
// retired extension without interpreting it; no capture option or schema exists.
// Raw messages preserve unrelated numeric values and fields.
func RemoveUIRetiredCaptureOptions(content string) (string, error) {
	if !strings.Contains(content, "http_debug") {
		return content, nil
	}
	root, err := unmarshalExtendedRaw([]byte(content))
	if err != nil {
		return "", err
	}
	raw, exists := root["experimental"]
	if !exists {
		return content, nil
	}
	var experimental map[string]json.RawMessage
	if err = json.Unmarshal(raw, &experimental); err != nil {
		return "", err
	}
	if _, exists = experimental["http_debug"]; !exists {
		return content, nil
	}
	delete(experimental, "http_debug")
	root["experimental"], err = json.Marshal(experimental)
	if err != nil {
		return "", err
	}
	data, err := json.Marshal(root)
	return string(data), err
}

// unmarshalExtendedRaw is sing's json.UnmarshalExtended for a JSON object: a
// syntax error carries its row and column ("...: row R, column C").
func unmarshalExtendedRaw(content []byte) (map[string]json.RawMessage, error) {
	var root map[string]json.RawMessage
	err := json.Unmarshal(content, &root)
	if err == nil {
		return root, nil
	}
	var syntaxError *json.SyntaxError
	if errors.As(err, &syntaxError) {
		prefix := string(content[:syntaxError.Offset])
		row := strings.Count(prefix, "\n") + 1
		column := len(prefix) - strings.LastIndex(prefix, "\n") - 1
		return nil, fmt.Errorf("%w: row %d, column %d", syntaxError, row, column)
	}
	return nil, err
}
