package libbox

// Default log level: when a profile does not set one, every core runs at
// warn (sing-box "warn", Xray "warning", mihomo "warning"). A level the
// profile sets explicitly is always kept. Only the content handed to the core
// changes; the profile on disk is untouched.

import (
	"bytes"
	"encoding/json"
	"strings"

	mihomoYAML "github.com/metacubex/mihomo/common/yaml"
	mihomoLog "github.com/metacubex/mihomo/log"
	"github.com/sagernet/sing-box/daemon"
)

const (
	coreDefaultLogLevel     = "warn"    // sing-box
	coreDefaultXrayLogLevel = "warning" // Xray and mihomo
)

// withDefaultSingBoxLogLevel adds log.level "warn" to a sing-box profile
// whose log section has no level. Values are copied byte for byte; content
// that is not a JSON object is returned as is.
func withDefaultSingBoxLogLevel(content string) string {
	trimmed := strings.TrimSpace(strings.TrimPrefix(content, "\ufeff"))
	if !strings.HasPrefix(trimmed, "{") {
		return content
	}
	var root map[string]json.RawMessage
	if json.Unmarshal([]byte(trimmed), &root) != nil || root == nil {
		return content
	}
	logOptions := map[string]json.RawMessage{}
	if raw, ok := root["log"]; ok && !bytes.Equal(bytes.TrimSpace(raw), []byte("null")) {
		if json.Unmarshal(raw, &logOptions) != nil || logOptions == nil {
			return content
		}
		if _, ok := logOptions["level"]; ok {
			return content
		}
	}
	logOptions["level"] = json.RawMessage(`"` + coreDefaultLogLevel + `"`)
	encoded, err := json.Marshal(logOptions)
	if err != nil {
		return content
	}
	root["log"] = encoded
	out, err := json.Marshal(root)
	if err != nil {
		return content
	}
	return string(out)
}

// xrayLogConfigWithDefault returns the log section Xray runs with: the
// profile's own, or loglevel "warning" when it sets none.
func xrayLogConfigWithDefault(root map[string]any) map[string]any {
	logConfig, _ := root["log"].(map[string]any)
	if logConfig == nil {
		logConfig = map[string]any{}
	}
	if level, ok := logConfig["loglevel"].(string); !ok || strings.TrimSpace(level) == "" {
		logConfig["loglevel"] = coreDefaultXrayLogLevel
	}
	return logConfig
}

// xrayApplyAccessLog decides where Xray's access log goes. With a log
// object present Xray builds an empty "access" as console output, which here
// is the synchronous service log, so a line per connection and DNS query
// would be written at every level. The access log is therefore turned off
// ("none") unless the profile asks for it (any non-empty "access", including
// its own "none", which is kept) or runs at loglevel info/debug. A file path
// cannot be written here, so an explicit path still goes to the service log.
func xrayApplyAccessLog(logConfig map[string]any) {
	if access, ok := logConfig["access"].(string); ok && strings.TrimSpace(access) != "" {
		if strings.EqualFold(strings.TrimSpace(access), "none") {
			logConfig["access"] = "none"
		} else {
			delete(logConfig, "access")
		}
		return
	}
	level, _ := logConfig["loglevel"].(string)
	switch strings.ToLower(strings.TrimSpace(level)) {
	case "debug", "info":
		delete(logConfig, "access")
	default:
		logConfig["access"] = "none"
	}
}

// xrayDaemonLogLevel maps Xray's loglevel to the level reported to the app.
func xrayDaemonLogLevel(level string) daemon.LogLevel {
	switch strings.ToLower(strings.TrimSpace(level)) {
	case "debug":
		return daemon.LogLevel_DEBUG
	case "info":
		return daemon.LogLevel_INFO
	case "error":
		return daemon.LogLevel_ERROR
	case "none":
		return daemon.LogLevel_PANIC
	default:
		return daemon.LogLevel_WARN
	}
}

// mihomoSetsLogLevel reports whether a Clash profile sets log-level itself.
// Block-style YAML (every Clash profile in practice) is checked with a line
// scan for a top-level key, so a large subscription is not parsed a second
// time; flow-style or JSON content falls back to a full parse.
func mihomoSetsLogLevel(content string) bool {
	text := normalizeCoreContent(content)
	if strings.HasPrefix(text, "{") {
		return mihomoSetsLogLevelParsed(text)
	}
	for len(text) > 0 {
		line := text
		if i := strings.IndexByte(text, '\n'); i >= 0 {
			line, text = text[:i], text[i+1:]
		} else {
			text = ""
		}
		if line == "" || line[0] == ' ' || line[0] == '\t' || line[0] == '#' {
			continue
		}
		key := strings.TrimRight(line, " \t\r")
		if colon := strings.IndexByte(key, ':'); colon >= 0 {
			key = strings.TrimSpace(key[:colon])
		} else {
			continue
		}
		key = strings.Trim(key, `"'`)
		if key == "log-level" {
			return true
		}
	}
	return false
}

func mihomoSetsLogLevelParsed(content string) bool {
	var root map[string]any
	if mihomoYAML.Unmarshal([]byte(content), &root) != nil {
		return false
	}
	_, ok := root["log-level"]
	return ok
}

func mihomoDaemonLogLevel(level mihomoLog.LogLevel) daemon.LogLevel {
	switch level {
	case mihomoLog.DEBUG:
		return daemon.LogLevel_DEBUG
	case mihomoLog.INFO:
		return daemon.LogLevel_INFO
	case mihomoLog.ERROR:
		return daemon.LogLevel_ERROR
	case mihomoLog.SILENT:
		return daemon.LogLevel_PANIC
	default:
		return daemon.LogLevel_WARN
	}
}
