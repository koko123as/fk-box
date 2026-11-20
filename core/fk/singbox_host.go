package fk

// sing-box profiles go to the official libbox in the app (host.go). The
// official parser rejects fields it does not know, so the options fk-box kept
// in experimental and no longer hands to sing-box are taken out first: the
// retired experimental.http_debug (RemoveUIRetiredCaptureOptions) and
// experimental.http_rewrite, which the capture proxy outside the kernel reads
// (docs/kernel-isolation.md section 7.3).

import (
	"bytes"
	"encoding/json"
	"fmt"
	"strings"
)

// singBoxHostContent is content as the official libbox may read it.
func singBoxHostContent(content string) (string, error) {
	cleaned, err := RemoveUIRetiredCaptureOptions(content)
	if err != nil {
		return "", err
	}
	text := strings.TrimSpace(strings.TrimPrefix(cleaned, "\ufeff"))
	if !strings.HasPrefix(text, "{") || !strings.Contains(strings.ToLower(text), "http_rewrite") {
		return cleaned, nil
	}
	stripped, changed, err := stripProfileHTTPRewrite([]byte(text))
	if err != nil || !changed {
		// Not an object libbox could read either; its own error says why.
		return cleaned, nil
	}
	return string(stripped), nil
}

// profileHTTPRewrite returns the value of experimental.http_rewrite in a
// sing-box profile (keys matched case-insensitively, the first match), or nil.
func profileHTTPRewrite(content string) json.RawMessage {
	text := strings.TrimSpace(strings.TrimPrefix(content, "\ufeff"))
	if !strings.HasPrefix(text, "{") || !strings.Contains(strings.ToLower(text), "http_rewrite") {
		return nil
	}
	var root map[string]json.RawMessage
	if json.Unmarshal([]byte(text), &root) != nil {
		return nil
	}
	for name, value := range root {
		if !strings.EqualFold(name, "experimental") {
			continue
		}
		var experimental map[string]json.RawMessage
		if json.Unmarshal(value, &experimental) != nil {
			continue
		}
		for key, rule := range experimental {
			if strings.EqualFold(key, "http_rewrite") {
				return rule
			}
		}
	}
	return nil
}

// checkSingBoxConfig checks a sing-box profile with the official libbox.
func checkSingBoxConfig(content string) error {
	cleaned, err := singBoxHostContent(content)
	if err != nil {
		return err
	}
	return CheckConfig(cleaned)
}

// formatSingBoxConfig formats a sing-box profile with the official libbox.
// experimental.http_rewrite is not a field libbox knows; it is taken out for
// libbox and put back, as the last member of experimental, into the
// formatted result, so formatting never loses the user's rewrite rules.
func formatSingBoxConfig(content string) (*StringBox, error) {
	rewrite := profileHTTPRewrite(content)
	cleaned, err := singBoxHostContent(content)
	if err != nil {
		return nil, err
	}
	formatted, err := FormatConfig(cleaned)
	if err != nil || rewrite == nil {
		return formatted, err
	}
	restored, err := restoreHTTPRewrite(formatted.Value, rewrite)
	if err != nil {
		return nil, err
	}
	return wrapString(restored), nil
}

func restoreHTTPRewrite(formatted string, rewrite json.RawMessage) (string, error) {
	var compact bytes.Buffer
	if err := json.Compact(&compact, []byte(formatted)); err != nil {
		return "", fmt.Errorf("formatted sing-box profile: %w", err)
	}
	var rewriteCompact bytes.Buffer
	if err := json.Compact(&rewriteCompact, rewrite); err != nil {
		return "", err
	}
	member := `"http_rewrite":` + rewriteCompact.String()
	found := false
	result, err := rewriteJSONObject(compact.Bytes(), func(name string, value json.RawMessage) (json.RawMessage, bool, error) {
		if found || name != "experimental" {
			return value, true, nil
		}
		trimmed := bytes.TrimSpace(value)
		if len(trimmed) < 2 || trimmed[0] != '{' {
			return value, true, nil
		}
		found = true
		return appendJSONMember(trimmed, member), true, nil
	})
	if err != nil {
		return "", err
	}
	if !found {
		result = appendJSONMember(result, `"experimental":{`+member+`}`)
	}
	var out bytes.Buffer
	if err = json.Indent(&out, result, "", "  "); err != nil {
		return "", err
	}
	if strings.HasSuffix(formatted, "\n") {
		out.WriteByte('\n')
	}
	return out.String(), nil
}

// appendJSONMember adds a "name":value member at the end of a compact JSON object.
func appendJSONMember(object []byte, member string) []byte {
	body := bytes.TrimSuffix(bytes.TrimSpace(object), []byte("}"))
	var out bytes.Buffer
	out.Write(body)
	if len(bytes.TrimSpace(body)) > 1 {
		out.WriteByte(',')
	}
	out.WriteString(member)
	out.WriteByte('}')
	return out.Bytes()
}
