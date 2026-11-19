package libbox

import (
	"encoding/json"
	"reflect"
	"strings"
	"testing"
)

// uiTestSettings is a host settings store: values holds what was written,
// every other key answers its default, as Settings.kt's dataStore does.
type uiTestSettings struct {
	values map[string]any
	reads  []string
}

func (s *uiTestSettings) GetBool(key string, defaultValue bool) bool {
	s.reads = append(s.reads, key)
	if value, ok := s.values[key].(bool); ok {
		return value
	}
	return defaultValue
}

func (s *uiTestSettings) GetLong(key string, defaultValue int64) int64 {
	s.reads = append(s.reads, key)
	if value, ok := s.values[key].(int64); ok {
		return value
	}
	return defaultValue
}

func (s *uiTestSettings) GetString(key string, defaultValue string) string {
	s.reads = append(s.reads, key)
	if value, ok := s.values[key].(string); ok {
		return value
	}
	return defaultValue
}

func uiUseTestSettings(t *testing.T, values map[string]any) *uiTestSettings {
	t.Helper()
	store := &uiTestSettings{values: values}
	SetUISettingsStore(store)
	t.Cleanup(func() { SetUISettingsStore(nil) })
	return store
}

const testTurnedOff = " core is turned off. Turn it on in Settings › General › Core, or pick another core for this profile."

func TestUISettingsUnavailableWithoutStore(t *testing.T) {
	SetUISettingsStore(nil)
	if _, err := uiEnabledCores(); err == nil || err.Error() != "settings are not available" {
		t.Fatalf("enabledCores without a store: %v", err)
	}
	if _, err := UIInvoke("checkConfig", `{"content":"{}"}`); err == nil || err.Error() != "settings are not available" {
		t.Fatalf("checkConfig without a store: %v", err)
	}
}

// Settings.kt: each core is on until switched off; an unknown core follows sing-box.
func TestUISettingsCoreSwitchesAndDefaults(t *testing.T) {
	store := uiUseTestSettings(t, nil)
	if cores, err := uiEnabledCores(); err != nil || cores != "sing-box,xray,mihomo" {
		t.Fatalf("defaults: %q %v", cores, err)
	}
	wantReads := []string{"core_sing_box_enabled", "core_xray_enabled", "core_mihomo_enabled"}
	if !reflect.DeepEqual(store.reads, wantReads) {
		t.Fatalf("keys read %q", store.reads)
	}
	cases := []struct {
		values   map[string]any
		enabled  string
		autoCore string
	}{
		{map[string]any{"core_sing_box_enabled": false}, "xray,mihomo", CoreMihomo},
		{map[string]any{"core_xray_enabled": false}, "sing-box,mihomo", CoreSingBox},
		{map[string]any{"core_sing_box_enabled": false, "core_mihomo_enabled": false}, "xray", CoreXray},
		// Every core off still fetches for Xray, as Settings.autoFetchCore.
		{map[string]any{"core_sing_box_enabled": false, "core_xray_enabled": false, "core_mihomo_enabled": false}, "", CoreXray},
		{map[string]any{"core_sing_box_enabled": true, "core_xray_enabled": true, "core_mihomo_enabled": true}, "sing-box,xray,mihomo", CoreSingBox},
	}
	for _, c := range cases {
		uiUseTestSettings(t, c.values)
		if cores, err := uiEnabledCores(); err != nil || cores != c.enabled {
			t.Errorf("%v: enabledCores %q %v, want %q", c.values, cores, err, c.enabled)
		}
		if core, err := uiAutoFetchCore(); err != nil || core != c.autoCore {
			t.Errorf("%v: autoFetchCore %q %v, want %q", c.values, core, err, c.autoCore)
		}
	}
	uiUseTestSettings(t, map[string]any{"core_sing_box_enabled": false})
	for core, want := range map[string]bool{CoreSingBox: false, CoreXray: true, CoreMihomo: true, "unknown": false, CoreAuto: false} {
		if got, err := uiCoreEnabled(core); err != nil || got != want {
			t.Errorf("coreEnabled(%q) = %v %v, want %v", core, got, err, want)
		}
	}
}

func TestUISettingsRequireCoreEnabled(t *testing.T) {
	uiUseTestSettings(t, map[string]any{"core_xray_enabled": false, "core_mihomo_enabled": false})
	if err := uiRequireCoreEnabled(CoreSingBox); err != nil {
		t.Fatal(err)
	}
	for core, label := range map[string]string{CoreXray: "Xray", CoreMihomo: "mihomo"} {
		if err := uiRequireCoreEnabled(core); err == nil || err.Error() != "The "+label+testTurnedOff {
			t.Errorf("%s off: %v", core, err)
		}
	}
	uiUseTestSettings(t, map[string]any{"core_sing_box_enabled": false})
	if err := uiRequireCoreEnabled("unknown"); err == nil || err.Error() != "The sing-box"+testTurnedOff {
		t.Fatalf("unknown core off: %v", err)
	}
}

func TestUIMethodCheckConfig(t *testing.T) {
	uiUseTestSettings(t, nil)
	singBox := `{"outbounds":[{"type":"direct","tag":"direct"}]}`
	xray := `{"outbounds":[{"protocol":"freedom","tag":"direct"}]}`
	args, _ := json.Marshal(map[string]any{"core": nil, "content": singBox})
	// The Kotlin host answered true.
	if result, err := UIInvoke("checkConfig", string(args)); err != nil || result != "true" {
		t.Fatalf("valid config: %q %v", result, err)
	}
	if result, err := UIInvoke("checkConfig", `{"content":`+jsonString(xray)+`}`); err != nil || result != "true" {
		t.Fatalf("absent core: %q %v", result, err)
	}
	if result, err := UIInvoke("checkConfig", `{"core":"xray","content":`+jsonString(xray)+`}`); err != nil || result != "true" {
		t.Fatalf("xray: %q %v", result, err)
	}
	// Errors are PrepareCoreProfile's own text.
	_, want := PrepareCoreProfile(CoreXray, testAllCores, `{"outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"a","port":"x"}]}}]}`)
	_, err := UIInvoke("checkConfig", `{"core":"xray","content":"{\"outbounds\":[{\"protocol\":\"vless\",\"settings\":{\"vnext\":[{\"address\":\"a\",\"port\":\"x\"}]}}]}"}`)
	if want == nil || err == nil || err.Error() != want.Error() {
		t.Fatalf("bad config: %v, want %v", err, want)
	}
	if _, err = UIInvoke("checkConfig", `{"core":"other","content":"{}"}`); err == nil || err.Error() != "unknown core: other" {
		t.Fatalf("unknown core: %v", err)
	}
	// The switches are read from the host's settings on every call.
	uiUseTestSettings(t, map[string]any{"core_xray_enabled": false})
	if _, err = UIInvoke("checkConfig", `{"content":`+jsonString(xray)+`}`); err == nil || err.Error() != "The Xray"+testTurnedOff {
		t.Fatalf("xray off: %v", err)
	}
	if _, err = UIInvoke("checkConfig", `{"content":`+jsonString(testShadowsocks)+`}`); err != nil {
		t.Fatalf("links with xray off: %v", err)
	}
}

func jsonString(value string) string {
	encoded, _ := json.Marshal(value)
	return string(encoded)
}

const testProfileArray = `[{"remarks":" A ","outbounds":[{"type":"direct","tag":"d"}]},{"outbounds":[{"protocol":"freedom","tag":"f"}]}]`

func TestUIPrepareCoreProfileArray(t *testing.T) {
	entries, err := PrepareCoreProfileArray(CoreAuto, testAllCores, "src", testProfileArray)
	if err != nil {
		t.Fatal(err)
	}
	split, err := SplitCoreConfigArray(testProfileArray)
	if err != nil {
		t.Fatal(err)
	}
	if entries.Len() != 2 || split.Len() != 2 {
		t.Fatalf("entries %d, split %d", entries.Len(), split.Len())
	}
	for index, name := range []string{"A", "src #2"} {
		i := int32(index)
		prepared, err := PrepareCoreProfile(CoreAuto, testAllCores, split.Content(i))
		if err != nil {
			t.Fatal(err)
		}
		if entries.Name(i) != name || entries.Content(i) != prepared.Content || entries.Core(i) != prepared.Core {
			t.Errorf("entry %d = %q %q %q, want %q %q %q", index, entries.Name(i), entries.Core(i), entries.Content(i), name, prepared.Core, prepared.Content)
		}
	}
	if entries.Core(0) != CoreSingBox || entries.Core(1) != CoreXray {
		t.Fatalf("cores %s %s", entries.Core(0), entries.Core(1))
	}
	// Not an array: nil, no error.
	if entries, err = PrepareCoreProfileArray(CoreAuto, testAllCores, "src", `{"outbounds":[]}`); entries != nil || err != nil {
		t.Fatalf("single config: %v %v", entries, err)
	}
	// The first failing element is named, and nothing is returned.
	_, err = PrepareCoreProfileArray(CoreAuto, "sing-box", "src", testProfileArray)
	if err == nil || err.Error() != "Configuration 2 (src #2): The Xray"+testTurnedOff {
		t.Fatalf("xray element off: %v", err)
	}
	_, err = PrepareCoreProfileArray("other", testAllCores, "src", testProfileArray)
	if err == nil || err.Error() != "Configuration 1 (A): unknown core: other" {
		t.Fatalf("unknown core: %v", err)
	}
}

func TestUIMethodDecodeProfileText(t *testing.T) {
	uiUseTestSettings(t, nil)
	result, err := UIInvoke("decodeProfileText", `{"content":`+jsonString(testProfileArray)+`,"name":"Imported profile"}`)
	if err != nil {
		t.Fatal(err)
	}
	entries, _ := PrepareCoreProfileArray(CoreAuto, testAllCores, "Imported profile", testProfileArray)
	want := map[string]any{"name": "Imported profile", "core": "auto", "entries": []any{
		map[string]any{"name": "A", "content": entries.Content(0), "core": "sing-box"},
		map[string]any{"name": "Imported profile #2", "content": entries.Content(1), "core": "xray"},
	}}
	var got map[string]any
	if err = json.Unmarshal([]byte(result), &got); err != nil || !reflect.DeepEqual(got, want) {
		t.Fatalf("array: %s %v", result, err)
	}
	// Keys in the order the Kotlin host's map had them.
	if !strings.HasPrefix(result, `{"name":"Imported profile","core":"auto","entries":[{"name":"A","content":`) {
		t.Fatalf("array key order: %s", result)
	}

	// One profile: prepared content with its detected core; the name defaults.
	xray := `{"outbounds":[{"protocol":"freedom","tag":"direct"}]}`
	result, err = UIInvoke("decodeProfileText", `{"content":`+jsonString(xray)+`}`)
	if err != nil {
		t.Fatal(err)
	}
	if want := `{"name":"Imported profile","content":` + jsonString(xray) + `,"core":"auto","detectedCore":"xray"}`; result != want {
		t.Fatalf("single: %s, want %s", result, want)
	}
	result, err = UIInvoke("decodeProfileText", `{"content":`+jsonString(testShadowsocks)+`,"name":"Pasted"}`)
	if err != nil {
		t.Fatal(err)
	}
	var single map[string]any
	if err = json.Unmarshal([]byte(result), &single); err != nil || single["name"] != "Pasted" || single["core"] != "auto" || single["detectedCore"] != CoreSingBox {
		t.Fatalf("links: %s %v", result, err)
	}

	// The core switches come from the host's settings.
	uiUseTestSettings(t, map[string]any{"core_xray_enabled": false})
	if _, err = UIInvoke("decodeProfileText", `{"content":`+jsonString(testProfileArray)+`,"name":"list"}`); err == nil || err.Error() != "Configuration 2 (list #2): The Xray"+testTurnedOff {
		t.Fatalf("array with xray off: %v", err)
	}
	if _, err = UIInvoke("decodeProfileText", `{"content":`+jsonString(xray)+`}`); err == nil || err.Error() != "The Xray"+testTurnedOff {
		t.Fatalf("single with xray off: %v", err)
	}
}
