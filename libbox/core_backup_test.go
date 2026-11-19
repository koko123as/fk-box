package libbox

import (
	"bytes"
	"encoding/json"
	"strings"
	"testing"
)

// Backups exactly as the app writes them: BoxBackups.encode is org.json's
// JSONObject(map).toString() (insertion order, "/" escaped as "\/", U+2028
// escaped, other non-ASCII raw), here for a backup of the current version
// (core and subscription fields, WebDAV account, remote servers) and one
// written before profiles carried a core (no origin, no core/subscription
// keys). The app still encodes backups itself, so a new backup is byte for
// byte what the previous version wrote and read.
const uiBackupCurrentFixture = `{"format":"bbox-1","origin":"6f1c2f3e-8a7b-4c2d-9e10-1a2b3c4d5e6f","createdAt":1763620000000,"selectedProfile":2,"profiles":[{"id":1,"name":"Local","remote":false,"url":"","updatedAt":1763600000000,"autoUpdate":false,"interval":60,"core":"auto","intervalUserSet":false,"resolvedCore":"sing-box","key":"0b5c8f1e-2d3a-4e6f-8a9b-0c1d2e3f4a5b","content":"{}"},{"id":2,"name":"Remote ✈ \u2028","remote":true,"url":"https:\/\/example.com\/sub?token=a&b=<c>","updatedAt":1763610000000,"autoUpdate":true,"interval":720,"core":"sing-box","upload":10,"download":20,"total":10737418240,"expire":1800000000,"webPageUrl":"https:\/\/example.com\/panel","intervalUserSet":true,"resolvedCore":"sing-box","key":"84ff88bc-85db-366f-8193-7f40f2449abf","content":"{\n  \"log\": {\n    \"level\": \"info\"\n  }\n}\n"}],"preferences":{"cardLayout":["VPN","Service status"],"tabMotion":"slide","proxyUnfolded":{"local:2":true}},"settings":{"routingRules":{"version":1},"geoUpdateInterval":24,"geoAutoUpdate":false,"ratio":0.75},"terminal":{"fontSize":14},"webdav":{"uri":"https:\/\/dav.example.com\/remote.php\/dav\/files\/u\/","user":"u","password":"plain-text","fileName":"fk-box.json"},"servers":[{"name":"Home","url":"http:\/\/192.168.1.2:9090","secret":"s3cret"}]}`

const uiBackupLegacyFixture = `{"format":"bbox-1","createdAt":1700000000000,"selectedProfile":-1,"profiles":[{"id":3,"name":"Old remote","remote":true,"url":"https:\/\/example.com\/old","updatedAt":1690000000000,"autoUpdate":true,"interval":60,"key":"9a0b1c2d-3e4f-4a5b-8c6d-7e8f9a0b1c2d","content":"{\"outbounds\":[{\"type\":\"direct\",\"tag\":\"direct\"}]}"}],"preferences":{},"settings":{},"servers":[]}`

// uiBackupRun runs the app's validation order: the document, then per
// profile CheckEntry, (the app's URL check), CheckEntryIdentity, then Finish.
func uiBackupRun(encoded []byte) error {
	check, err := NewUIBackupCheck(encoded)
	if err != nil {
		return err
	}
	for index := int32(0); index < check.EntryCount(); index++ {
		if err = check.CheckEntry(index); err != nil {
			return err
		}
		if err = check.CheckEntryIdentity(index); err != nil {
			return err
		}
	}
	return check.Finish()
}

func uiBackupDocument(t *testing.T, fixture string) map[string]any {
	t.Helper()
	decoder := json.NewDecoder(strings.NewReader(fixture))
	decoder.UseNumber()
	var document map[string]any
	if err := decoder.Decode(&document); err != nil {
		t.Fatal(err)
	}
	return document
}

func uiBackupEncode(t *testing.T, document map[string]any) []byte {
	t.Helper()
	var buffer bytes.Buffer
	encoder := json.NewEncoder(&buffer)
	encoder.SetEscapeHTML(false)
	if err := encoder.Encode(document); err != nil {
		t.Fatal(err)
	}
	return buffer.Bytes()
}

func uiBackupProfile(document map[string]any, index int) map[string]any {
	return document["profiles"].([]any)[index].(map[string]any)
}

func TestUIBackupKotlinFixturesPass(t *testing.T) {
	for name, fixture := range map[string]string{"current": uiBackupCurrentFixture, "legacy": uiBackupLegacyFixture} {
		check, err := NewUIBackupCheck([]byte(fixture))
		if err != nil {
			t.Fatalf("%s: %v", name, err)
		}
		if err = uiBackupRun([]byte(fixture)); err != nil {
			t.Fatalf("%s: %v", name, err)
		}
		if name == "current" {
			if check.EntryCount() != 2 || uiBackupDeclaredCore(check.entries[1]) != CoreSingBox || uiBackupDeclaredCore(check.entries[0]) != CoreAuto {
				t.Fatalf("current: %d entries", check.EntryCount())
			}
			// The escaped forms read back as the app wrote them.
			if check.entries[1]["url"] != "https://example.com/sub?token=a&b=<c>" || check.entries[1]["name"] != "Remote \u2708 \u2028" ||
				!strings.Contains(check.entries[1]["content"].(string), "\"level\": \"info\"") {
				t.Fatalf("current: %v", check.entries[1])
			}
		} else if check.EntryCount() != 1 || uiBackupDeclaredCore(check.entries[0]) != CoreAuto {
			t.Fatalf("legacy: %v", check.entries)
		}
	}
}

func TestUIBackupDocumentFaults(t *testing.T) {
	for _, test := range []struct {
		name   string
		mutate func(document map[string]any)
		want   string
	}{
		{"format", func(d map[string]any) { d["format"] = "bbox-2" }, "Unsupported B-box backup format"},
		{"no format", func(d map[string]any) { delete(d, "format") }, "Unsupported B-box backup format"},
		{"numeric format", func(d map[string]any) { d["format"] = json.Number("1") }, "Unsupported B-box backup format"},
		{"origin case", func(d map[string]any) { d["origin"] = "6F1C2F3E-8A7B-4C2D-9E10-1A2B3C4D5E6F" }, "Invalid backup origin"},
		{"origin short", func(d map[string]any) { d["origin"] = "6f1c2f3e-8a7b-4c2d-9e10-1a2b3c4d5e6" }, "Invalid backup origin"},
		{"null origin", func(d map[string]any) { d["origin"] = nil }, ""},
		{"timestamp text", func(d map[string]any) { d["createdAt"] = "1" }, "Invalid backup timestamp"},
		{"timestamp fraction", func(d map[string]any) { d["createdAt"] = json.Number("1.5") }, "Invalid backup timestamp"},
		{"timestamp negative", func(d map[string]any) { d["createdAt"] = json.Number("-1") }, "Invalid backup timestamp"},
		{"timestamp beyond 2^53", func(d map[string]any) { d["createdAt"] = json.Number("9007199254740992") }, "Invalid backup timestamp"},
		{"timestamp as double", func(d map[string]any) { d["createdAt"] = json.Number("1.0E12") }, ""},
		{"no profiles", func(d map[string]any) { delete(d, "profiles") }, "Backup has no profiles"},
		{"null profiles", func(d map[string]any) { d["profiles"] = nil }, "Backup has no profiles"},
		{"profiles object", func(d map[string]any) { d["profiles"] = map[string]any{} }, "Invalid backup list"},
		{"profile number", func(d map[string]any) { d["profiles"] = []any{json.Number("1")} }, "Invalid backup entry"},
		{"profile null", func(d map[string]any) { d["profiles"] = []any{nil} }, "Invalid backup entry"},
		{"too many", func(d map[string]any) {
			list := make([]any, 1001)
			for i := range list {
				list[i] = map[string]any{}
			}
			d["profiles"] = list
		}, "Backup contains too many profiles"},
		{"no profiles is fine", func(d map[string]any) { d["profiles"] = []any{}; d["selectedProfile"] = json.Number("-1") }, ""},
		{"blank name", func(d map[string]any) { uiBackupProfile(d, 0)["name"] = " \u00a0\u3000\x1c" }, "Profile name is required"},
		{"numeric name", func(d map[string]any) { uiBackupProfile(d, 0)["name"] = json.Number("5") }, "Profile name is required"},
		{"long name", func(d map[string]any) { uiBackupProfile(d, 0)["name"] = strings.Repeat("a", 4097) }, "Profile name is required"},
		{"long name in UTF-16", func(d map[string]any) { uiBackupProfile(d, 0)["name"] = strings.Repeat("\U0001D7CF", 2049) }, "Profile name is required"},
		{"name at limit", func(d map[string]any) { uiBackupProfile(d, 0)["name"] = strings.Repeat("\U0001D7CF", 2048) }, ""},
		{"core", func(d map[string]any) { uiBackupProfile(d, 0)["core"] = "clash" }, "Invalid profile core"},
		{"numeric core", func(d map[string]any) { uiBackupProfile(d, 0)["core"] = json.Number("1") }, "Invalid profile core"},
		{"negative total", func(d map[string]any) { uiBackupProfile(d, 1)["total"] = json.Number("-1") }, "Invalid subscription total"},
		{"text upload", func(d map[string]any) { uiBackupProfile(d, 1)["upload"] = "1" }, "Invalid subscription upload"},
		{"decimal expire", func(d map[string]any) { uiBackupProfile(d, 1)["expire"] = json.Number("1.5") }, ""},
		{"website", func(d map[string]any) { uiBackupProfile(d, 1)["webPageUrl"] = json.Number("1") }, "Invalid subscription website"},
		{"interval option", func(d map[string]any) { uiBackupProfile(d, 1)["intervalUserSet"] = "yes" }, "Invalid update interval option"},
		{"no content", func(d map[string]any) { delete(uiBackupProfile(d, 0), "content") }, "Missing profile content"},
		{"numeric content", func(d map[string]any) { uiBackupProfile(d, 0)["content"] = json.Number("5") }, "Missing profile content"},
		{"array content", func(d map[string]any) { uiBackupProfile(d, 0)["content"] = "[{}, {}]" }, "a JSON array of configs must be split into profiles first"},
		{"url", func(d map[string]any) { uiBackupProfile(d, 0)["url"] = json.Number("5") }, "Invalid profile URL"},
		{"null url", func(d map[string]any) { uiBackupProfile(d, 0)["url"] = nil }, ""},
		{"key", func(d map[string]any) { uiBackupProfile(d, 0)["key"] = "ABC" }, "Invalid profile backup identity"},
		{"id zero", func(d map[string]any) { uiBackupProfile(d, 0)["id"] = json.Number("0") }, "Invalid profile ID"},
		{"id fraction", func(d map[string]any) { uiBackupProfile(d, 0)["id"] = json.Number("1.5") }, "Invalid profile ID"},
		{"interval 14", func(d map[string]any) { uiBackupProfile(d, 0)["interval"] = json.Number("14") }, "Invalid update interval"},
		{"interval 15.0", func(d map[string]any) { uiBackupProfile(d, 0)["interval"] = json.Number("15.0") }, ""},
		{"interval beyond Int", func(d map[string]any) { uiBackupProfile(d, 0)["interval"] = json.Number("4294967311") }, "Invalid update interval"},
		{"interval text", func(d map[string]any) { uiBackupProfile(d, 0)["interval"] = "60" }, "Invalid update interval"},
		{"auto update", func(d map[string]any) { uiBackupProfile(d, 0)["autoUpdate"] = "true" }, "Invalid auto update option"},
		{"updated at", func(d map[string]any) { uiBackupProfile(d, 0)["updatedAt"] = json.Number("-1") }, "Invalid update timestamp"},
		{"duplicate key", func(d map[string]any) { uiBackupProfile(d, 1)["key"] = uiBackupProfile(d, 0)["key"] }, "Backup contains duplicate profiles"},
		{"duplicate id", func(d map[string]any) { uiBackupProfile(d, 1)["id"] = json.Number("1.0") }, "Backup contains duplicate profiles"},
		{"no ids", func(d map[string]any) {
			delete(uiBackupProfile(d, 0), "id")
			delete(uiBackupProfile(d, 1), "id")
			d["selectedProfile"] = json.Number("-1")
		}, ""},
		{"selected unknown", func(d map[string]any) { d["selectedProfile"] = json.Number("3") }, "Invalid selected profile"},
		{"selected below -1", func(d map[string]any) { d["selectedProfile"] = json.Number("-2") }, "Invalid selected profile"},
		{"selected text", func(d map[string]any) { d["selectedProfile"] = "2" }, "Invalid selected profile"},
		{"selected as double", func(d map[string]any) { d["selectedProfile"] = json.Number("2.0") }, ""},
		{"selected none", func(d map[string]any) { d["selectedProfile"] = json.Number("-1") }, ""},
		// The first fault in the app's order wins.
		{"entry 0 before entry 1", func(d map[string]any) {
			uiBackupProfile(d, 0)["interval"] = json.Number("1")
			uiBackupProfile(d, 1)["name"] = ""
		}, "Invalid update interval"},
		{"metadata before identity", func(d map[string]any) {
			uiBackupProfile(d, 0)["key"] = "x"
			uiBackupProfile(d, 0)["core"] = "x"
		}, "Invalid profile core"},
		{"name before url", func(d map[string]any) {
			uiBackupProfile(d, 0)["url"] = true
			uiBackupProfile(d, 0)["name"] = ""
		}, "Profile name is required"},
		{"selection before size", func(d map[string]any) {
			d["settings"] = map[string]any{"padding": strings.Repeat("x", uiBackupLimit)}
			d["selectedProfile"] = json.Number("9")
		}, "Invalid selected profile"},
		{"size", func(d map[string]any) { d["settings"] = map[string]any{"padding": strings.Repeat("x", uiBackupLimit)} }, "Backup exceeds 64 MiB"},
	} {
		document := uiBackupDocument(t, uiBackupCurrentFixture)
		test.mutate(document)
		err := uiBackupRun(uiBackupEncode(t, document))
		if test.want == "" {
			if err != nil {
				t.Fatalf("%s: %v", test.name, err)
			}
			continue
		}
		if err == nil || err.Error() != test.want {
			t.Fatalf("%s: got %v, want %q", test.name, err, test.want)
		}
	}
}

// A profile's content is checked with the core it declares, with the same
// error the core's own check gives.
func TestUIBackupContentUsesTheDeclaredCore(t *testing.T) {
	document := uiBackupDocument(t, uiBackupCurrentFixture)
	uiBackupProfile(document, 1)["content"] = "proxies: []\n"
	err := uiBackupRun(uiBackupEncode(t, document))
	want := CheckCoreConfig(CoreSingBox, "proxies: []\n")
	if err == nil || want == nil || err.Error() != want.Error() {
		t.Fatalf("got %v, want %v", err, want)
	}
	document = uiBackupDocument(t, uiBackupCurrentFixture)
	uiBackupProfile(document, 0)["content"] = strings.Repeat(" ", coreProfileLimit) + "{}"
	if err = uiBackupRun(uiBackupEncode(t, document)); err == nil || err.Error() != "Configuration exceeds 16 MiB" {
		t.Fatalf("got %v", err)
	}
	if _, err = NewUIBackupCheck([]byte("[]")); err == nil || err.Error() != "Value [] of type org.json.JSONArray cannot be converted to JSONObject" {
		t.Fatalf("got %v", err)
	}
	check, _ := NewUIBackupCheck([]byte(uiBackupLegacyFixture))
	if check.CheckEntry(1) == nil || check.CheckEntryIdentity(-1) == nil {
		t.Fatal("out of range entry accepted")
	}
}

func TestUIBackupProfileKey(t *testing.T) {
	const origin = "6f1c2f3e-8a7b-4c2d-9e10-1a2b3c4d5e6f"
	for _, test := range []struct {
		path string
		id   int64
		want string
	}{
		// Files the app creates are named by a UUID: that is the key.
		{"/data/user/0/io.fkbox.app/files/configs/0b5c8f1e-2d3a-4e6f-8a9b-0c1d2e3f4a5b.json", 1, "0b5c8f1e-2d3a-4e6f-8a9b-0c1d2e3f4a5b"},
		{"/data/configs/0b5c8f1e-2d3a-4e6f-8a9b-0c1d2e3f4a5b.yaml", 9, "0b5c8f1e-2d3a-4e6f-8a9b-0c1d2e3f4a5b"},
		{"/data/configs/0b5c8f1e-2d3a-4e6f-8a9b-0c1d2e3f4a5b", 9, "0b5c8f1e-2d3a-4e6f-8a9b-0c1d2e3f4a5b"},
		// Anything else: UUID.nameUUIDFromBytes("<origin>:<id>"), as the fixture's second profile.
		{"/data/configs/7.json", 7, "84ff88bc-85db-366f-8193-7f40f2449abf"},
		{"/data/configs/0B5C8F1E-2D3A-4E6F-8A9B-0C1D2E3F4A5B.json", 7, "84ff88bc-85db-366f-8193-7f40f2449abf"},
		{"/data/configs/0b5c8f1e-2d3a-4e6f-8a9b-0c1d2e3f4a5b.json.bak", 7, "84ff88bc-85db-366f-8193-7f40f2449abf"},
	} {
		if got := UIBackupProfileKey(test.path, origin, test.id); got != test.want {
			t.Fatalf("%s: %s, want %s", test.path, got, test.want)
		}
	}
	// java.util.UUID.nameUUIDFromBytes("abc".getBytes()).
	if got := uiNameUUID([]byte("abc")); got != "90015098-3cd2-3fb0-9696-3f7d28e17f72" {
		t.Fatal(got)
	}
}
