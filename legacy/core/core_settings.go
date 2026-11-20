package libbox

// Settings the UI host keeps. The values stay in the host's own store (on
// Android: Settings.kt over the Room key/value database); Go reads them
// through UISettingsStore with the same keys and the same defaults, and
// reproduces Settings.kt's derived rules for what Go needs.

import (
	"errors"
	"fmt"
	"strings"
	"sync"
)

// UISettingsStore is the host's settings, read by key. A key that has no
// stored value answers defaultValue. Go may call it from any goroutine; reads
// must not block on the host's UI thread.
type UISettingsStore interface {
	GetBool(key string, defaultValue bool) bool
	GetLong(key string, defaultValue int64) int64
	GetString(key string, defaultValue string) string
}

// Keys and defaults as in the host's SettingsKey.kt / Settings.kt.
const (
	uiSettingCoreSingBoxEnabled = "core_sing_box_enabled"
	uiSettingCoreXrayEnabled    = "core_xray_enabled"
	uiSettingCoreMihomoEnabled  = "core_mihomo_enabled"
)

var (
	uiSettingsAccess sync.RWMutex
	uiSettingsStore  UISettingsStore
)

// SetUISettingsStore installs (or, with nil, removes) the host's settings.
func SetUISettingsStore(store UISettingsStore) {
	uiSettingsAccess.Lock()
	uiSettingsStore = store
	uiSettingsAccess.Unlock()
}

func uiSettings() (UISettingsStore, error) {
	uiSettingsAccess.RLock()
	store := uiSettingsStore
	uiSettingsAccess.RUnlock()
	if store == nil {
		return nil, errors.New("settings are not available")
	}
	return store, nil
}

// uiCoreEnabledIn is Settings.coreEnabled: Xray and mihomo by their own
// switch, anything else by sing-box's (each on by default).
func uiCoreEnabledIn(store UISettingsStore, core string) bool {
	switch core {
	case CoreXray:
		return store.GetBool(uiSettingCoreXrayEnabled, true)
	case CoreMihomo:
		return store.GetBool(uiSettingCoreMihomoEnabled, true)
	default:
		return store.GetBool(uiSettingCoreSingBoxEnabled, true)
	}
}

func uiCoreEnabled(core string) (bool, error) {
	store, err := uiSettings()
	if err != nil {
		return false, err
	}
	return uiCoreEnabledIn(store, core), nil
}

// uiEnabledCores is Settings.enabledCores: the cores left on, comma-separated,
// in the order sing-box, xray, mihomo (what PrepareCoreProfile takes).
func uiEnabledCores() (string, error) {
	store, err := uiSettings()
	if err != nil {
		return "", err
	}
	var enabled []string
	for _, core := range []string{CoreSingBox, CoreXray, CoreMihomo} {
		if uiCoreEnabledIn(store, core) {
			enabled = append(enabled, core)
		}
	}
	return strings.Join(enabled, ","), nil
}

// uiAutoFetchCore is Settings.autoFetchCore: the core an auto profile's
// subscription is fetched for.
func uiAutoFetchCore() (string, error) {
	store, err := uiSettings()
	if err != nil {
		return "", err
	}
	switch {
	case store.GetBool(uiSettingCoreSingBoxEnabled, true):
		return CoreSingBox, nil
	case store.GetBool(uiSettingCoreMihomoEnabled, true):
		return CoreMihomo, nil
	default:
		return CoreXray, nil
	}
}

// uiRequireCoreEnabled is Settings.requireCoreEnabled.
func uiRequireCoreEnabled(core string) error {
	enabled, err := uiCoreEnabled(core)
	if err != nil {
		return err
	}
	if !enabled {
		return coreTurnedOffError(core)
	}
	return nil
}

// coreTurnedOffError is the text Settings.requireCoreEnabled and
// PrepareCoreProfile give for a core that is off.
func coreTurnedOffError(core string) error {
	return fmt.Errorf("The %s core is turned off. Turn it on in Settings › General › Core, or pick another core for this profile.", coreLabel(core))
}
