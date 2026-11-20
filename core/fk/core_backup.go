package fk

// The `bbox-1` backup document's portable checks (was BoxBackups.validate in
// the app) and the stable profile identity it carries. The app still builds
// and encodes backups (org.json, unchanged bytes), stages and restores them
// (files, the profile store transaction, settings, the Keystore-sealed WebDAV
// account) and checks what needs Android or java.net.URI: subscription
// addresses, preferences, settings, remote servers, terminal settings and
// the WebDAV account. It runs these checks step by step in the order the
// Kotlin code used, so a backup with several faults reports the same first one.
//
// Known issue, unchanged here: a backup carries the WebDAV password and
// remote server secrets in plain text.

import (
	"crypto/md5"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"regexp"
	"strings"
)

const (
	uiBackupFormat      = "bbox-1"
	uiBackupMaxProfiles = 1000
	uiBackupMaxName     = 4096
)

// uiCanonicalUUID is what java.util.UUID.fromString(s).toString() == s accepts.
var uiCanonicalUUID = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)

func uiBackupValidUUID(value any) bool {
	text, ok := value.(string)
	return ok && uiCanonicalUUID.MatchString(text)
}

// UIBackupProfileKey is a profile's identity across backups: the file name
// when it is a UUID (profiles the app creates are named so), else the
// name-based UUID of "<origin>:<id>" (java.util.UUID.nameUUIDFromBytes).
func UIBackupProfileKey(path string, origin string, id int64) string {
	name := strings.TrimRight(path, "/")
	if slash := strings.LastIndex(name, "/"); slash >= 0 {
		name = name[slash+1:]
	}
	if dot := strings.LastIndex(name, "."); dot >= 0 {
		name = name[:dot]
	}
	if uiCanonicalUUID.MatchString(name) {
		return name
	}
	return uiNameUUID([]byte(fmt.Sprintf("%s:%d", origin, id)))
}

// uiNameUUID is java.util.UUID.nameUUIDFromBytes: an MD5 (version 3) UUID.
func uiNameUUID(name []byte) string {
	sum := md5.Sum(name)
	sum[6] = sum[6]&0x0f | 0x30
	sum[8] = sum[8]&0x3f | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", sum[0:4], sum[4:6], sum[6:8], sum[8:10], sum[10:16])
}

// uiBackupInteger is BoxBackups.integer: a whole number, at least minimum,
// within JSON's exact integer range.
func uiBackupInteger(value any, minimum int64) bool {
	literal, ok := value.(json.Number)
	if !ok {
		return false
	}
	number := newJVMNumber(literal)
	double := number.toDouble()
	return !math.IsNaN(double) && !math.IsInf(double, 0) && double == float64(number.toLong()) &&
		number.toLong() >= minimum && double <= 9007199254740991.0
}

// UIBackupCheck checks one backup document. The app creates it from the
// backup as org.json encodes it, then calls CheckEntry, its own address
// check, and CheckEntryIdentity for each profile, then Finish.
type UIBackupCheck struct {
	size    int
	backup  map[string]any
	entries []map[string]any
}

// NewUIBackupCheck reads the document and checks its format, origin,
// creation time and profile list.
func NewUIBackupCheck(encoded []byte) (*UIBackupCheck, error) {
	value, err := decodeJVMJSON(encoded)
	if err != nil {
		return nil, err
	}
	backup, ok := value.(map[string]any)
	if !ok {
		return nil, orgJSONTypeMismatch(value, "JSONObject")
	}
	if format, _ := backup["format"].(string); format != uiBackupFormat {
		return nil, errors.New("Unsupported B-box backup format")
	}
	if origin := backup["origin"]; origin != nil && !uiBackupValidUUID(origin) {
		return nil, errors.New("Invalid backup origin")
	}
	if createdAt := backup["createdAt"]; createdAt != nil && !uiBackupInteger(createdAt, 0) {
		return nil, errors.New("Invalid backup timestamp")
	}
	profiles := backup["profiles"]
	if profiles == nil {
		return nil, errors.New("Backup has no profiles")
	}
	list, ok := profiles.([]any)
	if !ok {
		return nil, errors.New("Invalid backup list")
	}
	entries := make([]map[string]any, 0, len(list))
	for _, item := range list {
		entry, ok := item.(map[string]any)
		if !ok {
			return nil, errors.New("Invalid backup entry")
		}
		entries = append(entries, entry)
	}
	if len(entries) > uiBackupMaxProfiles {
		return nil, errors.New("Backup contains too many profiles")
	}
	return &UIBackupCheck{size: len(encoded), backup: backup, entries: entries}, nil
}

// EntryCount is the number of profiles in the backup.
func (c *UIBackupCheck) EntryCount() int32 {
	return int32(len(c.entries))
}

func (c *UIBackupCheck) entry(index int32) (map[string]any, error) {
	if index < 0 || int(index) >= len(c.entries) {
		return nil, errors.New("backup entry out of range")
	}
	return c.entries[index], nil
}

// CheckEntry checks a profile's name, metadata (ProfileWireFields.validate),
// content with the core it declares (old backups declare none: auto) and the
// type of its subscription address. The address itself is the app's.
func (c *UIBackupCheck) CheckEntry(index int32) error {
	entry, err := c.entry(index)
	if err != nil {
		return err
	}
	name, ok := entry["name"].(string)
	if !ok || kotlinIsBlank(name) || utf16Length(name) > uiBackupMaxName {
		return errors.New("Profile name is required")
	}
	if core := entry["core"]; core != nil {
		if text, ok := core.(string); !ok || !uiBackupKnownCore(text) {
			return errors.New("Invalid profile core")
		}
	}
	for _, key := range []string{"upload", "download", "total", "expire"} {
		value := entry[key]
		if value == nil {
			continue
		}
		literal, ok := value.(json.Number)
		if !ok {
			return fmt.Errorf("Invalid subscription %s", key)
		}
		double := newJVMNumber(literal).toDouble()
		if math.IsNaN(double) || math.IsInf(double, 0) || double < 0 {
			return fmt.Errorf("Invalid subscription %s", key)
		}
	}
	if website := entry["webPageUrl"]; website != nil {
		if _, ok := website.(string); !ok {
			return errors.New("Invalid subscription website")
		}
	}
	if userSet := entry["intervalUserSet"]; userSet != nil {
		if _, ok := userSet.(bool); !ok {
			return errors.New("Invalid update interval option")
		}
	}
	content, ok := entry["content"].(string)
	if !ok {
		return errors.New("Missing profile content")
	}
	core, err := ResolveProfileCore(uiBackupDeclaredCore(entry), content)
	if err != nil {
		return err
	}
	// ProfileContent.validate.
	if len(content) > coreProfileLimit {
		return errors.New("Configuration exceeds 16 MiB")
	}
	if err = CheckCoreConfig(core, content); err != nil {
		return err
	}
	if url := entry["url"]; url != nil {
		if _, ok := url.(string); !ok {
			return errors.New("Invalid profile URL")
		}
	}
	return nil
}

// CheckEntryIdentity checks a profile's backup key, ID and update options.
func (c *UIBackupCheck) CheckEntryIdentity(index int32) error {
	entry, err := c.entry(index)
	if err != nil {
		return err
	}
	if key := entry["key"]; key != nil && !uiBackupValidUUID(key) {
		return errors.New("Invalid profile backup identity")
	}
	if id := entry["id"]; id != nil && !uiBackupInteger(id, 1) {
		return errors.New("Invalid profile ID")
	}
	if interval := entry["interval"]; interval != nil {
		literal, ok := interval.(json.Number)
		if !ok {
			return errors.New("Invalid update interval")
		}
		number := newJVMNumber(literal)
		if number.toDouble() != float64(number.toInt()) || number.toInt() < uiSubscriptionMinInterval {
			return errors.New("Invalid update interval")
		}
	}
	if autoUpdate := entry["autoUpdate"]; autoUpdate != nil {
		if _, ok := autoUpdate.(bool); !ok {
			return errors.New("Invalid auto update option")
		}
	}
	if updatedAt := entry["updatedAt"]; updatedAt != nil && !uiBackupInteger(updatedAt, 0) {
		return errors.New("Invalid update timestamp")
	}
	return nil
}

// Finish checks that profiles are not duplicated, that the selected profile
// is one of them (or -1), and the encoded size.
func (c *UIBackupCheck) Finish() error {
	keys := make(map[string]struct{})
	ids := make(map[int64]struct{})
	keyCount, idCount := 0, 0
	for _, entry := range c.entries {
		if key, ok := entry["key"].(string); ok {
			keys[key] = struct{}{}
			keyCount++
		}
		if id, ok := entry["id"].(json.Number); ok {
			ids[newJVMNumber(id).toLong()] = struct{}{}
			idCount++
		}
	}
	if len(keys) != keyCount || len(ids) != idCount {
		return errors.New("Backup contains duplicate profiles")
	}
	if selected := c.backup["selectedProfile"]; selected != nil {
		valid := uiBackupInteger(selected, -1)
		if valid {
			id := newJVMNumber(selected.(json.Number)).toLong()
			_, known := ids[id]
			valid = id == -1 || known
		}
		if !valid {
			return errors.New("Invalid selected profile")
		}
	}
	if c.size > uiBackupLimit {
		return errors.New("Backup exceeds 64 MiB")
	}
	return nil
}

func uiBackupKnownCore(core string) bool {
	switch core {
	case CoreAuto, CoreSingBox, CoreXray, CoreMihomo:
		return true
	}
	return false
}

// uiBackupDeclaredCore is ProfileWireFields.declaredCore.
func uiBackupDeclaredCore(entry map[string]any) string {
	if core, ok := entry["core"].(string); ok && uiBackupKnownCore(core) {
		return core
	}
	return CoreAuto
}
