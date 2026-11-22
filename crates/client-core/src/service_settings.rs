//! The service settings as the settings pages and backups see them: one
//! snapshot of the values the user edits, their validation, and the write.
//!
//! The values live in the store's `settings` table under the keys and value
//! kinds the Android app always used (`SettingsKey`, `KeyValueEntity` type
//! codes), so nothing here changes the stored data. What a change does
//! outside the store stays with the platform, which this module tells what
//! to do ([`Plan`]): switching the package query mode, scanning the
//! installed apps for the managed per-app list, restarting the service,
//! re-reading its setup options, rescheduling geo updates. The battery
//! optimization exemption is platform state and is not part of the
//! snapshot; the platform adds it to what the UI shows.

use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};

use crate::geo;
use crate::routing;
use crate::storage::{SettingValue, Store};

#[derive(Clone, Copy)]
enum Kind {
    Bool(bool),
    Int(i32),
    Set,
    Text(&'static str),
    GeoUrls,
    Routing,
}

/// One setting: its wire name (the snapshot's key), its store key, its kind
/// and default, and whether the settings pages may write it.
struct Field {
    wire: &'static str,
    key: &'static str,
    kind: Kind,
    writable: bool,
}

const fn field(wire: &'static str, key: &'static str, kind: Kind) -> Field {
    Field { wire, key, kind, writable: true }
}

const FIELDS: [Field; 20] = [
    field("perAppEnabled", "per_app_proxy_enabled", Kind::Bool(false)),
    field("perAppMode", "per_app_proxy_mode", Kind::Int(PER_APP_EXCLUDE)),
    field("perAppList", "per_app_proxy_list", Kind::Set),
    field("perAppManaged", "per_app_proxy_managed_mode", Kind::Bool(false)),
    // Filled by the platform's app scan, never by the pages.
    Field { wire: "perAppManagedList", key: "per_app_proxy_managed_list", kind: Kind::Set, writable: false },
    field("allowBypass", "allow_bypass", Kind::Bool(false)),
    field("autoRedirect", "auto_redirect", Kind::Bool(false)),
    field("dynamicNotification", "dynamic_notification", Kind::Bool(true)),
    field("disableDeprecatedWarnings", "disable_deprecated_warnings", Kind::Bool(false)),
    field("singBoxEnabled", "core_sing_box_enabled", Kind::Bool(true)),
    field("xrayEnabled", "core_xray_enabled", Kind::Bool(true)),
    field("mihomoEnabled", "core_mihomo_enabled", Kind::Bool(true)),
    field("oomKillerEnabled", "oom_killer_enabled", Kind::Bool(false)),
    field("oomKillerDisabled", "oom_killer_disabled", Kind::Bool(true)),
    field("oomMemoryLimitMB", "oom_memory_limit_mb", Kind::Int(50)),
    field("powerReportEnabled", "power_report_enabled", Kind::Bool(false)),
    field("queryMode", QUERY_MODE_KEY, Kind::Text(QUERY_MODE_SHIZUKU)),
    field("geoAutoUpdate", geo::AUTO_UPDATE_KEY, Kind::Bool(false)),
    field("geoUpdateInterval", geo::UPDATE_INTERVAL_KEY, Kind::Int(geo::DEFAULT_INTERVAL_HOURS as i32)),
    field("geoUrls", geo::URLS_KEY, Kind::GeoUrls),
];

/// Routing rules are part of the snapshot too (the last field, kept apart
/// so its kind stays visible).
const ROUTING: Field = field("routingRules", routing::SETTING_KEY, Kind::Routing);

pub const PER_APP_EXCLUDE: i32 = 1;
pub const PER_APP_INCLUDE: i32 = 2;
pub const QUERY_MODE_KEY: &str = "per_app_proxy_package_query_mode";
pub const QUERY_MODE_ROOT: &str = "ROOT";
pub const QUERY_MODE_SHIZUKU: &str = "SHIZUKU";

/// The settings a running service only picks up when it restarts, which
/// the pages may ask the platform to do right away.
pub const RESTART_KEYS: [&str; 3] = ["allowBypass", "autoRedirect", "dynamicNotification"];

/// Keys whose change reschedules the automatic geo update.
pub const GEO_SCHEDULE_KEYS: [&str; 2] = ["geoAutoUpdate", "geoUpdateInterval"];

fn fields() -> impl Iterator<Item = &'static Field> {
    FIELDS.iter().chain(std::iter::once(&ROUTING))
}

fn find(wire: &str) -> Option<&'static Field> {
    fields().find(|f| f.wire == wire)
}

/// A stored value read the way the Kotlin store read it: the default when
/// absent or of another kind; an int is the stored long's low 32 bits.
fn read(stored: Option<&SettingValue>, kind: Kind) -> Value {
    match (kind, stored) {
        (Kind::Bool(_), Some(SettingValue::Bool(v))) => Value::Bool(*v),
        (Kind::Bool(d), _) => Value::Bool(d),
        (Kind::Int(_), Some(SettingValue::Long(v))) => Value::from(*v as i32),
        (Kind::Int(d), _) => Value::from(d),
        (Kind::Set, Some(SettingValue::StringSet(v))) => Value::from(v.clone()),
        (Kind::Set, _) => Value::Array(Vec::new()),
        (Kind::Text(_), Some(SettingValue::String(v))) => Value::String(v.clone()),
        (Kind::Text(d), _) => Value::String(d.into()),
        (Kind::GeoUrls, Some(SettingValue::String(v))) => serde_json::to_value(geo::decode_urls(v)).unwrap_or_default(),
        (Kind::GeoUrls, _) => Value::Object(Map::new()),
        (Kind::Routing, Some(SettingValue::String(v))) => routing::to_wire(&routing::decode(v).unwrap_or_default()),
        (Kind::Routing, _) => routing::to_wire(&routing::Rules::default()),
    }
}

/// Every setting the pages show, by wire name, with defaults filled in.
pub fn snapshot(store: &Store) -> Result<Map<String, Value>, String> {
    let stored = store.settings().map_err(|e| e.0)?;
    let mut out = Map::new();
    for f in fields() {
        let value = stored.iter().find(|(k, _)| k == f.key).map(|(_, v)| v);
        out.insert(f.wire.into(), read(value, f.kind));
    }
    Ok(out)
}

fn current(store: &Store, wire: &str) -> Result<Value, String> {
    let f = find(wire).ok_or_else(|| format!("Unknown setting: {wire}"))?;
    Ok(read(store.setting(f.key).map_err(|e| e.0)?.as_ref(), f.kind))
}

/// A whole number within `range`, in any JSON number form.
fn integer(value: &Value, range: std::ops::RangeInclusive<i64>) -> Option<i64> {
    let number = value.as_f64()?;
    (number.fract() == 0.0 && number >= *range.start() as f64 && number <= *range.end() as f64).then_some(number as i64)
}

/// Checks each value on its own (edits and complete backups alike); the
/// message names the first invalid setting.
pub fn validate(values: &Map<String, Value>) -> Result<(), String> {
    for (key, value) in values {
        let valid = match find(key).filter(|f| f.writable).map(|f| f.kind) {
            Some(Kind::Bool(_)) => value.is_boolean(),
            Some(Kind::Int(_)) => match key.as_str() {
                "perAppMode" => integer(value, 1..=2).is_some(),
                "oomMemoryLimitMB" => integer(value, 10..=4096).is_some(),
                "geoUpdateInterval" => integer(value, 1..=geo::MAX_INTERVAL_HOURS).is_some(),
                _ => false,
            },
            Some(Kind::Set) => value.as_array().is_some_and(|items| {
                items.len() <= 10_000
                    && items.iter().all(|i| i.as_str().is_some_and(|s| !s.trim().is_empty() && s.encode_utf16().count() < 512))
            }),
            Some(Kind::Text(_)) => matches!(value.as_str(), Some(QUERY_MODE_ROOT | QUERY_MODE_SHIZUKU)),
            Some(Kind::GeoUrls) => geo::urls_from_wire(value).is_ok(),
            Some(Kind::Routing) => routing::from_wire(value).is_ok(),
            None => false,
        };
        if !valid {
            return Err(format!("Invalid service setting: {key}"));
        }
    }
    Ok(())
}

/// What the platform does around a write, before it ([`patch`]) and after.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Plan {
    /// Switch the package query mode to this first (Android: root or
    /// Shizuku), and back if anything after it fails.
    pub query_mode: Option<String>,
    /// Scan the installed apps for the managed per-app list and save it
    /// before the write (managed mode is never on without its list).
    pub scan: bool,
    /// The service's setup options (OOM killer, power report) changed.
    pub reload_setup: bool,
    /// The automatic geo update needs rescheduling.
    pub reschedule_geo: bool,
}

impl Plan {
    /// True when the write needs nothing from the platform before it, so a
    /// UI with the store can write it itself and tell the platform after.
    pub fn store_only(&self) -> bool {
        self.query_mode.is_none() && !self.scan
    }
}

fn bool_of(values: &Map<String, Value>, store: &Store, key: &str) -> Result<bool, String> {
    match values.get(key) {
        Some(v) => Ok(v.as_bool().unwrap_or(false)),
        None => Ok(current(store, key)?.as_bool().unwrap_or(false)),
    }
}

/// Validates `values` against each other and the stored settings, and says
/// what the platform does around the write.
pub fn plan(store: &Store, values: &Map<String, Value>) -> Result<Plan, String> {
    validate(values)?;
    let cores = ["singBoxEnabled", "xrayEnabled", "mihomoEnabled"];
    if cores.iter().any(|k| values.contains_key(*k)) {
        let mut any = false;
        for key in cores {
            any |= bool_of(values, store, key)?;
        }
        if !any {
            return Err("Keep at least one core turned on".into());
        }
    }
    let managed = bool_of(values, store, "perAppManaged")?;
    let enabled = bool_of(values, store, "perAppEnabled")?;
    let touches = |keys: &[&str]| keys.iter().any(|k| values.contains_key(*k));
    Ok(Plan {
        query_mode: values.get("queryMode").and_then(Value::as_str).map(str::to_string),
        scan: managed && enabled && touches(&["perAppManaged", "perAppEnabled", "queryMode"]),
        reload_setup: touches(&["oomKillerEnabled", "oomKillerDisabled", "oomMemoryLimitMB", "powerReportEnabled"]),
        reschedule_geo: touches(&GEO_SCHEDULE_KEYS),
    })
}

/// The stored form of one valid wire value.
fn stored(f: &Field, value: &Value) -> Result<SettingValue, String> {
    Ok(match f.kind {
        Kind::Bool(_) => SettingValue::Bool(value.as_bool().unwrap_or(false)),
        Kind::Int(_) => SettingValue::Long(value.as_f64().unwrap_or(0.0) as i64),
        Kind::Set => SettingValue::from_json(SettingValue::STRING_SET, value).map_err(|e| e.0)?,
        Kind::Text(_) => SettingValue::String(value.as_str().unwrap_or_default().into()),
        Kind::GeoUrls => SettingValue::String(geo::encode_urls(&geo::urls_from_wire(value)?)),
        Kind::Routing => SettingValue::String(routing::encode(&routing::from_wire(value)?)?),
    })
}

/// One written entry, as the platform's settings cache takes it.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Written {
    pub key: String,
    pub t: i64,
    pub v: Value,
}

impl Written {
    pub fn of(key: &str, value: &SettingValue) -> Written {
        Written { key: key.into(), t: value.kind(), v: value.to_json() }
    }
}

/// Writes `values` in one step (all or nothing) after [`plan`]'s checks.
/// The query mode is written too: the platform switched it already and the
/// store keeps the same value. Returns what was written.
pub fn patch(store: &Store, values: &Map<String, Value>) -> Result<Vec<Written>, String> {
    plan(store, values)?;
    let mut entries = Vec::with_capacity(values.len());
    for (key, value) in values {
        let f = find(key).ok_or_else(|| format!("Unknown setting: {key}"))?;
        entries.push((f.key.to_string(), stored(f, value)?));
    }
    store.put_settings(&entries).map_err(|e| e.0)?;
    Ok(entries.iter().map(|(k, v)| Written::of(k, v)).collect())
}

/// The snapshot entries a backup carries (what the user edits; not the
/// managed list, which a scan rebuilds).
pub fn backup(store: &Store) -> Result<Map<String, Value>, String> {
    let mut all = snapshot(store)?;
    all.retain(|k, _| find(k).is_some_and(|f| f.writable));
    Ok(all)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn map(value: Value) -> Map<String, Value> {
        value.as_object().unwrap().clone()
    }

    #[test]
    fn snapshot_has_the_kotlin_defaults() {
        let store = Store::open_in_memory().unwrap();
        let all = snapshot(&store).unwrap();
        assert_eq!(all.len(), 21);
        assert_eq!(all["perAppMode"], json!(1));
        assert_eq!(all["dynamicNotification"], json!(true));
        assert_eq!(all["oomKillerDisabled"], json!(true));
        assert_eq!(all["oomMemoryLimitMB"], json!(50));
        assert_eq!(all["queryMode"], json!("SHIZUKU"));
        assert_eq!(all["geoUpdateInterval"], json!(24));
        assert_eq!(all["geoUrls"], json!({}));
        assert_eq!(all["routingRules"], json!({"version": 1, "cores": {}}));
        assert_eq!(all["perAppList"], json!([]));
        assert!(!all.contains_key("batteryExempt"));
        assert!(!backup(&store).unwrap().contains_key("perAppManagedList"));
    }

    #[test]
    fn snapshot_reads_values_as_kotlin_did() {
        let store = Store::open_in_memory().unwrap();
        store.put_setting("per_app_proxy_mode", &SettingValue::Long(2)).unwrap();
        store.put_setting("per_app_proxy_list", &SettingValue::StringSet(vec!["b".into(), "a".into()])).unwrap();
        // Another kind than expected reads as the default.
        store.put_setting("allow_bypass", &SettingValue::String("true".into())).unwrap();
        store.put_setting("geo_urls", &SettingValue::String(r#"{"asn":"https://e.example/a","x":"https://e.example"}"#.into())).unwrap();
        store.put_setting("routing_rules", &SettingValue::String("{broken".into())).unwrap();
        let all = snapshot(&store).unwrap();
        assert_eq!(all["perAppMode"], json!(2));
        assert_eq!(all["perAppList"], json!(["b", "a"]));
        assert_eq!(all["allowBypass"], json!(false));
        assert_eq!(all["geoUrls"], json!({"asn": "https://e.example/a"}));
        assert_eq!(all["routingRules"], json!({"version": 1, "cores": {}}));
    }

    #[test]
    fn validation_matches_the_backup_checks() {
        let ok = map(json!({"perAppMode": 2, "perAppList": ["org.example.app"], "oomMemoryLimitMB": 50}));
        validate(&ok).unwrap();
        for (key, bad) in [
            ("oomMemoryLimitMB", json!(9)),
            ("allowBypass", json!("true")),
            ("serviceMode", json!("VPN")),
            ("perAppManagedList", json!([])),
            ("perAppMode", json!(3)),
            ("perAppList", json!([" "])),
            ("geoUpdateInterval", json!(0)),
            ("geoUpdateInterval", json!(1.5)),
            ("geoUrls", json!({"geoip": "file:///sdcard/geoip.dat"})),
            ("geoUrls", json!({"rules": "https://example.com/x"})),
            ("queryMode", json!("ADB")),
            ("routingRules", json!({"version": 9})),
            ("routingRules", json!("text")),
        ] {
            let mut values = ok.clone();
            values.insert(key.into(), bad);
            assert_eq!(validate(&values).unwrap_err(), format!("Invalid service setting: {key}"));
        }
        validate(&map(json!({"geoAutoUpdate": true, "geoUpdateInterval": 24.0, "geoUrls": {"geoip": "https://example.com/geoip.dat"}})))
            .unwrap();
    }

    #[test]
    fn plan_says_what_the_platform_does() {
        let store = Store::open_in_memory().unwrap();
        assert_eq!(plan(&store, &map(json!({"allowBypass": true}))).unwrap(), Plan::default());
        assert!(plan(&store, &map(json!({"allowBypass": true}))).unwrap().store_only());
        let managed = plan(&store, &map(json!({"perAppManaged": true, "perAppEnabled": true}))).unwrap();
        assert!(managed.scan && !managed.store_only());
        // Managed on but per-app off: nothing to scan yet.
        assert!(!plan(&store, &map(json!({"perAppManaged": true}))).unwrap().scan);
        store.put_setting("per_app_proxy_enabled", &SettingValue::Bool(true)).unwrap();
        assert!(plan(&store, &map(json!({"perAppManaged": true}))).unwrap().scan);
        let query = plan(&store, &map(json!({"queryMode": "ROOT"}))).unwrap();
        assert_eq!(query.query_mode.as_deref(), Some("ROOT"));
        assert!(!query.scan);
        assert!(plan(&store, &map(json!({"oomMemoryLimitMB": 64}))).unwrap().reload_setup);
        assert!(plan(&store, &map(json!({"geoUpdateInterval": 6}))).unwrap().reschedule_geo);
        store.put_setting("core_xray_enabled", &SettingValue::Bool(false)).unwrap();
        store.put_setting("core_mihomo_enabled", &SettingValue::Bool(false)).unwrap();
        assert_eq!(plan(&store, &map(json!({"singBoxEnabled": false}))).unwrap_err(), "Keep at least one core turned on");
        plan(&store, &map(json!({"singBoxEnabled": false, "xrayEnabled": true}))).unwrap();
    }

    #[test]
    fn patch_writes_everything_or_nothing_in_the_stored_form() {
        let store = Store::open_in_memory().unwrap();
        let rules = json!({"cores": {"xray": {"mode": "override", "rules": [{"id": "r", "values": ["geosite:cn"], "target": {"type": "direct"}}]}}});
        let written = patch(
            &store,
            &map(json!({
                "perAppMode": 2, "perAppList": ["a", "b", "a"], "oomMemoryLimitMB": 64.0, "queryMode": "ROOT",
                "geoUrls": {"mmdb": "https://m.example/x", "asn": "https://a.example/y"}, "routingRules": rules,
            })),
        )
        .unwrap();
        assert_eq!(written.len(), 6);
        assert_eq!(store.setting("per_app_proxy_mode").unwrap(), Some(SettingValue::Long(2)));
        assert_eq!(store.setting("per_app_proxy_list").unwrap(), Some(SettingValue::StringSet(vec!["a".into(), "b".into()])));
        assert_eq!(store.setting("oom_memory_limit_mb").unwrap(), Some(SettingValue::Long(64)));
        assert_eq!(store.setting(QUERY_MODE_KEY).unwrap(), Some(SettingValue::String("ROOT".into())));
        assert_eq!(
            store.setting("geo_urls").unwrap(),
            Some(SettingValue::String(r#"{"asn":"https://a.example/y","mmdb":"https://m.example/x"}"#.into()))
        );
        let Some(SettingValue::String(text)) = store.setting("routing_rules").unwrap() else { panic!() };
        assert!(text.starts_with(r#"{"version":1,"cores":{"xray":{"mode":"override","final":{"type":"config","tag":""},"rules":[{"id":"r","enabled":true"#));
        let w = written.iter().find(|w| w.key == "per_app_proxy_list").unwrap();
        assert_eq!((w.t, w.v.clone()), (5, json!(["a", "b"])));

        // One bad value: nothing is written.
        assert!(patch(&store, &map(json!({"perAppMode": 1, "oomMemoryLimitMB": 1}))).is_err());
        assert_eq!(snapshot(&store).unwrap()["perAppMode"], json!(2));
        assert!(patch(&store, &map(json!({"perAppManagedList": ["x"]}))).is_err());

        // Inside a restore's transaction the write joins it.
        store.begin().unwrap();
        patch(&store, &map(json!({"perAppMode": 1}))).unwrap();
        store.rollback().unwrap();
        assert_eq!(snapshot(&store).unwrap()["perAppMode"], json!(2));
    }
}
