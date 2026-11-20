package libbox

// The one entry point the UI host forwards methods to (protocol/methods.json,
// owner "go"). Every platform calls the same table: on Android the Kotlin host
// forwards through gomobile; another client can serve the same calls over IPC.
//
// Arguments and results are JSON. A method answers a JSON value, or an error
// whose text the UI shows as is.

import (
	"encoding/json"
	"errors"
	"fmt"
	"sort"
	"sync"
)

type uiMethodHandler func(args json.RawMessage) (any, error)

var uiMethods = map[string]uiMethodHandler{
	"checkConfig":       uiCheckConfig,
	"checkUpdates":      uiCheckUpdates,
	"decodeProfileText": uiDecodeProfileText,
	"formatConfig":      uiFormatConfig,
	"hello":             uiHello,
}

// UIEventSink receives events Go raises for the UI: kind is the event name in
// protocol/methods.json, payload its JSON value.
type UIEventSink interface {
	Emit(kind string, payload string)
}

var (
	uiEventAccess sync.RWMutex
	uiEventSink   UIEventSink
)

// SetUIEventSink installs (or, with nil, removes) the receiver of Go-raised UI events.
func SetUIEventSink(sink UIEventSink) {
	uiEventAccess.Lock()
	uiEventSink = sink
	uiEventAccess.Unlock()
}

// uiEmit raises an event; it is dropped while no sink is installed.
func uiEmit(kind string, value any) error {
	if _, known := uiProtocolEvents[kind]; !known {
		return fmt.Errorf("unknown UI event: %s", kind)
	}
	payload, err := json.Marshal(value)
	if err != nil {
		return err
	}
	uiEventAccess.RLock()
	sink := uiEventSink
	uiEventAccess.RUnlock()
	if sink != nil {
		sink.Emit(kind, string(payload))
	}
	return nil
}

// uiEmitRaw raises an event whose payload is already JSON.
func uiEmitRaw(kind string, payload string) error {
	if _, known := uiProtocolEvents[kind]; !known {
		return fmt.Errorf("unknown UI event: %s", kind)
	}
	uiEventAccess.RLock()
	sink := uiEventSink
	uiEventAccess.RUnlock()
	if sink != nil {
		sink.Emit(kind, payload)
	}
	return nil
}

// UIHandlesMethod reports whether Go answers method (owner "go").
func UIHandlesMethod(method string) bool {
	_, ok := uiMethods[method]
	return ok
}

// UIInvoke runs method with its JSON arguments and answers the JSON result.
// Empty args are read as {}.
func UIInvoke(method string, args string) (string, error) {
	handler, ok := uiMethods[method]
	if !ok {
		return "", fmt.Errorf("unknown method: %s", method)
	}
	if args == "" {
		args = "{}"
	}
	if !json.Valid([]byte(args)) {
		return "", errors.New("invalid method arguments")
	}
	result, err := handler(json.RawMessage(args))
	if err != nil {
		return "", err
	}
	encoded, err := json.Marshal(result)
	if err != nil {
		return "", err
	}
	return string(encoded), nil
}

// uiMethodNames lists the methods Go answers, sorted (tests compare it with the protocol).
func uiMethodNames() []string {
	names := make([]string, 0, len(uiMethods))
	for name := range uiMethods {
		names = append(names, name)
	}
	sort.Strings(names)
	return names
}

func uiDecodeArgs(args json.RawMessage, target any) error {
	if err := json.Unmarshal(args, target); err != nil {
		return fmt.Errorf("invalid method arguments: %w", err)
	}
	return nil
}

// formatConfig {core?, content}: the content pretty-printed for its core
// ("auto" or absent detects it). Was BoxBridge.formatConfig.
func uiFormatConfig(args json.RawMessage) (any, error) {
	var request struct {
		Core    *string `json:"core"`
		Content string  `json:"content"`
	}
	if err := uiDecodeArgs(args, &request); err != nil {
		return nil, err
	}
	declared := CoreAuto
	if request.Core != nil {
		declared = *request.Core
	}
	core, err := ResolveProfileCore(declared, request.Content)
	if err != nil {
		return nil, err
	}
	formatted, err := FormatCoreConfig(core, request.Content)
	if err != nil {
		return nil, err
	}
	return formatted.Value, nil
}

// checkConfig {core?, content}: true when the content prepares as a profile
// for its core ("auto" or absent detects it) with the cores left on in
// Settings; the error says why not. Was BoxBridge.checkConfig.
func uiCheckConfig(args json.RawMessage) (any, error) {
	var request struct {
		Core    *string `json:"core"`
		Content string  `json:"content"`
	}
	if err := uiDecodeArgs(args, &request); err != nil {
		return nil, err
	}
	declared := CoreAuto
	if request.Core != nil {
		declared = *request.Core
	}
	enabledCores, err := uiEnabledCores()
	if err != nil {
		return nil, err
	}
	if _, err = PrepareCoreProfile(declared, enabledCores, request.Content); err != nil {
		return nil, err
	}
	return true, nil
}

// uiDecodedProfileArray is decodeProfileText's answer for a JSON array of
// configs: one entry per element for saveProfiles.
type uiDecodedProfileArray struct {
	Name    string                `json:"name"`
	Core    string                `json:"core"`
	Entries []uiProfileArrayEntry `json:"entries"`
}

// uiDecodedProfile is decodeProfileText's answer for one profile.
type uiDecodedProfile struct {
	Name         string `json:"name"`
	Content      string `json:"content"`
	Core         string `json:"core"`
	DetectedCore string `json:"detectedCore"`
}

// decodeProfileText {content, name?}: pasted or scanned text. A JSON array of
// configs is split into prepared entries named after name ("Imported
// profile" when absent); anything else is prepared as one auto profile.
// Was BoxBridge.decodeProfileText (BoxProfiles.decodeText).
func uiDecodeProfileText(args json.RawMessage) (any, error) {
	var request struct {
		Content string  `json:"content"`
		Name    *string `json:"name"`
	}
	if err := uiDecodeArgs(args, &request); err != nil {
		return nil, err
	}
	name := "Imported profile"
	if request.Name != nil {
		name = *request.Name
	}
	enabledCores, err := uiEnabledCores()
	if err != nil {
		return nil, err
	}
	entries, err := prepareCoreProfileArray(CoreAuto, enabledCores, name, request.Content)
	if err != nil {
		return nil, err
	}
	if entries != nil {
		return uiDecodedProfileArray{Name: name, Core: CoreAuto, Entries: entries}, nil
	}
	prepared, err := PrepareCoreProfile(CoreAuto, enabledCores, request.Content)
	if err != nil {
		return nil, err
	}
	return uiDecodedProfile{Name: name, Content: prepared.Content, Core: CoreAuto, DetectedCore: prepared.Core}, nil
}
