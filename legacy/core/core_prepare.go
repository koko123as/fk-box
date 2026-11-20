package libbox

// Preparing a profile before it is saved: what a user imported or a
// subscription returned becomes content for the core that runs it. Moved
// from the Android app's ProfileContent.prepare; the host passes the cores
// the user left on, since the settings stay with the host.

import (
	"errors"
	"fmt"
	"strings"
)

// coreProfileLimit is the largest profile the app saves (16 MiB of UTF-8).
const coreProfileLimit = 16 * 1024 * 1024

// PrepareCoreProfile turns content into a profile for declared ("auto"
// detects): share links / Base64 node lists are converted for the enabled
// cores (comma-separated, as the host's Settings.enabledCores), sing-box JSON
// drops the retired capture options, the resulting core must be turned on,
// and the result is checked by that core's own parser.
func PrepareCoreProfile(declared string, enabledCores string, content string) (*UICoreProfile, error) {
	if len(content) > coreProfileLimit {
		return nil, errors.New("Configuration exceeds 16 MiB")
	}
	var prepared *UICoreProfile
	if DetectCoreConfig(content) == CoreLinks {
		converted, err := ConvertCoreShareLinksEnabled(declared, enabledCores, content)
		if err != nil {
			return nil, err
		}
		prepared = &UICoreProfile{Core: converted.Core, Content: converted.Content}
	} else {
		core, err := ResolveProfileCore(declared, content)
		if err != nil {
			return nil, err
		}
		prepared = &UICoreProfile{Core: core, Content: content}
		if core == CoreSingBox {
			prepared.Content, err = RemoveUIRetiredCaptureOptions(content)
			if err != nil {
				return nil, err
			}
		}
	}
	if !coreEnabledIn(enabledCores, prepared.Core) {
		return nil, coreTurnedOffError(prepared.Core)
	}
	if err := validateCoreProfile(prepared.Core, prepared.Content); err != nil {
		return nil, err
	}
	return prepared, nil
}

// validateCoreProfile is the host's ProfileContent.validate(core, content).
func validateCoreProfile(core string, content string) error {
	if len(content) > coreProfileLimit {
		return errors.New("Configuration exceeds 16 MiB")
	}
	return CheckCoreConfig(core, content)
}

// coreEnabledIn mirrors the host's Settings.coreEnabled: Xray and mihomo by
// their own switch, anything else by sing-box's.
func coreEnabledIn(enabledCores string, core string) bool {
	switch core {
	case CoreXray, CoreMihomo:
	default:
		core = CoreSingBox
	}
	for _, item := range strings.Split(enabledCores, ",") {
		if strings.TrimSpace(item) == core {
			return true
		}
	}
	return false
}

// coreLabel mirrors the host's Settings.coreLabel.
func coreLabel(core string) string {
	switch core {
	case CoreXray:
		return "Xray"
	case CoreMihomo:
		return "mihomo"
	default:
		return "sing-box"
	}
}

// UIProfileArrayEntries is the result of PrepareCoreProfileArray: the
// profiles to create, read by index (gomobile cannot return slices).
type UIProfileArrayEntries struct {
	entries []uiProfileArrayEntry
}

type uiProfileArrayEntry struct {
	Name    string `json:"name"`
	Content string `json:"content"`
	Core    string `json:"core"`
}

func (e *UIProfileArrayEntries) Len() int32 { return int32(len(e.entries)) }

// Name is the profile's name (ProfileArrayNames).
func (e *UIProfileArrayEntries) Name(index int32) string { return e.entries[index].Name }

// Content is the element prepared by PrepareCoreProfile.
func (e *UIProfileArrayEntries) Content(index int32) string { return e.entries[index].Content }

// Core is the core that runs the prepared element.
func (e *UIProfileArrayEntries) Core(index int32) string { return e.entries[index].Core }

// PrepareCoreProfileArray splits a JSON array of configs into one profile
// per element, named by ProfileArrayNames from source and each prepared with
// PrepareCoreProfile for declared ("auto" detects every element separately).
// It fails on the first element that does not pass, naming it, so nothing is
// saved half-way; nil when content is not such an array. Moved from the
// Android app's ProfileContent.splitArray + prepareArray.
func PrepareCoreProfileArray(declared string, enabledCores string, source string, content string) (*UIProfileArrayEntries, error) {
	entries, err := prepareCoreProfileArray(declared, enabledCores, source, content)
	if err != nil || entries == nil {
		return nil, err
	}
	return &UIProfileArrayEntries{entries: entries}, nil
}

func prepareCoreProfileArray(declared string, enabledCores string, source string, content string) ([]uiProfileArrayEntry, error) {
	if DetectCoreConfig(content) != CoreArray {
		return nil, nil
	}
	split, err := SplitCoreConfigArray(content)
	if err != nil {
		return nil, err
	}
	remarks := make([]string, len(split.entries))
	for index, element := range split.entries {
		remarks[index] = element.remarks
	}
	names := profileArrayNames(source, remarks)
	entries := make([]uiProfileArrayEntry, len(split.entries))
	for index, element := range split.entries {
		prepared, err := PrepareCoreProfile(declared, enabledCores, element.content)
		if err != nil {
			return nil, fmt.Errorf("Configuration %d (%s): %s", index+1, names[index], err.Error())
		}
		entries[index] = uiProfileArrayEntry{Name: names[index], Content: prepared.Content, Core: prepared.Core}
	}
	return entries, nil
}
