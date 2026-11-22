//! The client store ([`fkbox_client_core::storage::Store`]) as the UI uses it:
//! the profile list, the interface preferences, the service settings and the
//! routing rules are read here, and preferences, the profile order, the
//! service settings and terminal settings that need nothing from the
//! platform first are written here, instead of asking the host. Changes that involve a profile's configuration or the running
//! service still go through the host, which writes the same store and then
//! sends a `profiles` event without a payload (src/app.rs re-reads).
//!
//! The platform shell supplies the store through [`set_store_provider`]; on
//! Android it is the instance the Kotlin layer opened, available once the
//! host has answered `ready`. Without a store (the screenshot harness) the
//! UI keeps using the host's payloads and calls.

use std::sync::{Arc, OnceLock};

use fkbox_client_core::service_settings::{self, Plan, Written};
use fkbox_client_core::terminal;
use fkbox_client_core::storage::Store;
use serde_json::{Map, Value};

type Provider = Box<dyn Fn() -> Option<Arc<Store>> + Send + Sync>;

static PROVIDER: OnceLock<Provider> = OnceLock::new();

/// Installs where the UI finds the store; the first one stays.
pub fn set_store_provider(provider: impl Fn() -> Option<Arc<Store>> + Send + Sync + 'static) {
    let _ = PROVIDER.set(Box::new(provider));
}

pub fn get() -> Option<Arc<Store>> {
    PROVIDER.get().and_then(|provider| provider())
}

/// The profile rows the pages read (the host's former `profiles` payload).
pub fn profiles(store: &Store) -> Result<Vec<Value>, String> {
    Ok(store.profiles().map_err(|e| e.0)?.iter().map(|p| p.to_wire()).collect())
}

/// What "update all" updates (client-core `subscription::update_all`): every
/// remote profile, in list order.
pub fn update_all(store: &Store) -> Result<Vec<i64>, String> {
    Ok(fkbox_client_core::subscription::update_all(&store.profiles().map_err(|e| e.0)?))
}

pub fn preferences(store: &Store) -> Result<Map<String, Value>, String> {
    store.ui_preferences().map_err(|e| e.0)
}

/// A backup restore holds the store in one transaction; the UI's own writes
/// wait for it instead of joining it.
fn writable(store: &Store) -> Result<(), String> {
    if store.in_transaction() {
        Err("Wait for the restore to finish".into())
    } else {
        Ok(())
    }
}

pub fn update_preferences(store: &Store, values: &Value) -> Result<Map<String, Value>, String> {
    writable(store)?;
    let values = values.as_object().ok_or("Invalid interface preferences")?;
    store.update_ui_preferences(values).map_err(|e| e.0)
}

pub fn reorder_profiles(store: &Store, ids: &[i64]) -> Result<(), String> {
    writable(store)?;
    store.reorder_profiles(ids).map_err(|e| e.0)
}

/// The service settings snapshot (without the platform's own state).
pub fn service_settings(store: &Store) -> Result<Map<String, Value>, String> {
    service_settings::snapshot(store)
}

/// What the platform must do around a write of `values`, after checking them.
pub fn plan_service_settings(store: &Store, values: &Map<String, Value>) -> Result<Plan, String> {
    service_settings::plan(store, values)
}

/// Writes service settings that need nothing from the platform first; the
/// caller tells the platform what was written.
pub fn patch_service_settings(store: &Store, values: &Map<String, Value>) -> Result<Vec<Written>, String> {
    writable(store)?;
    let plan = service_settings::plan(store, values)?;
    if !plan.store_only() {
        return Err("This setting is saved by the app".into());
    }
    service_settings::patch(store, values)
}

/// A terminal setting the client store holds alone (no theme list or font
/// file to check on the platform).
pub fn terminal_store_only(key: &str) -> bool {
    terminal::STORE_ONLY_KEYS.contains(&key)
}

/// Writes such a terminal setting; returns what was written (for the
/// platform's settings cache) and the terminal settings after.
pub fn set_terminal_setting(store: &Store, key: &str, value: &Value) -> Result<(Vec<Written>, Map<String, Value>), String> {
    if !terminal_store_only(key) {
        return Err("This setting is saved by the app".into());
    }
    let written = terminal::set(store, key, value, None)?;
    Ok((written, terminal::snapshot(store)?))
}

/// The dashboard's cached IP quality record for `ip` (client-core
/// `ip_quality`).
pub fn read_ip_quality(store: &Store, ip: &str) -> Result<Option<Value>, String> {
    fkbox_client_core::ip_quality::read(store, ip)
}

/// Caches an IP quality record (old records of other IPs are dropped).
pub fn write_ip_quality(store: &Store, ip: &str, record: &Value) -> Result<(), String> {
    fkbox_client_core::ip_quality::write(store, ip, record, crate::format::now_ms())
}

#[cfg(test)]
mod tests {
    use super::*;
    use fkbox_client_core::storage::Profile;
    use serde_json::json;

    #[test]
    fn rows_preferences_and_order_come_from_the_store() {
        let store = Store::open_in_memory().unwrap();
        let a = store.insert_profile(&Profile { name: "a".into(), user_order: 0, ..Default::default() }).unwrap();
        let b = store.insert_profile(&Profile { name: "b".into(), user_order: 1, ..Default::default() }).unwrap();
        reorder_profiles(&store, &[b, a]).unwrap();
        let rows = profiles(&store).unwrap();
        assert_eq!(rows.iter().map(|r| r["name"].clone()).collect::<Vec<_>>(), [json!("b"), json!("a")]);
        assert_eq!(rows[0]["resolvedCore"], json!("sing-box"));

        assert_eq!(Value::Object(update_preferences(&store, &json!({"theme": "dark"})).unwrap()), json!({"theme": "dark"}));
        assert_eq!(Value::Object(preferences(&store).unwrap()), json!({"theme": "dark"}));
        assert!(update_preferences(&store, &json!({"theme": 3})).is_err());

        store.begin().unwrap();
        assert!(reorder_profiles(&store, &[a, b]).is_err());
        assert!(update_preferences(&store, &json!({"theme": "light"})).is_err());
        store.rollback().unwrap();
    }

    #[test]
    fn service_settings_are_read_and_written_here_unless_the_platform_must_act() {
        let store = Store::open_in_memory().unwrap();
        assert_eq!(service_settings(&store).unwrap()["oomMemoryLimitMB"], json!(50));
        let values = json!({"oomMemoryLimitMB": 64, "disableDeprecatedWarnings": true});
        let plan = plan_service_settings(&store, values.as_object().unwrap()).unwrap();
        assert!(plan.store_only() && plan.reload_setup);
        let written = patch_service_settings(&store, values.as_object().unwrap()).unwrap();
        assert_eq!(written.len(), 2);
        assert_eq!(service_settings(&store).unwrap()["oomMemoryLimitMB"], json!(64));
        // Query mode and managed scans go through the platform.
        assert!(patch_service_settings(&store, json!({"queryMode": "ROOT"}).as_object().unwrap()).is_err());
        assert!(patch_service_settings(&store, json!({"oomMemoryLimitMB": 1}).as_object().unwrap()).is_err());
        store.begin().unwrap();
        assert!(patch_service_settings(&store, json!({"allowBypass": true}).as_object().unwrap()).is_err());
        store.rollback().unwrap();
    }

    #[test]
    fn terminal_settings_without_platform_checks_are_written_here() {
        let store = Store::open_in_memory().unwrap();
        let (written, snapshot) = set_terminal_setting(&store, "fontSize", &json!(18)).unwrap();
        assert_eq!((written.len(), snapshot["fontSize"].clone()), (1, json!(18)));
        assert!(set_terminal_setting(&store, "darkTheme", &json!("x")).is_err());
        store.begin().unwrap();
        assert!(set_terminal_setting(&store, "fontSize", &json!(12)).is_err());
        store.rollback().unwrap();
    }
}

/// The remote control servers in list order, as the host's former
/// `remoteServers` rows (id, name, url, secret).
pub fn remote_servers(store: &Store) -> Result<Vec<Value>, String> {
    Ok(store
        .remote_servers()
        .map_err(|e| e.0)?
        .into_iter()
        .map(|s| serde_json::json!({ "id": s.id, "name": s.name, "url": s.url, "secret": s.secret }))
        .collect())
}

/// The server form's save (client-core `remote_servers::save`); `id` 0 adds.
pub fn save_remote_server(store: &Store, id: i64, name: &str, url: &str, secret: &str) -> Result<i64, String> {
    writable(store)?;
    fkbox_client_core::remote_servers::save(store, id, name, url, secret)
}
