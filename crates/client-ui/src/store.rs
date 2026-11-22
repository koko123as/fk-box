//! The client store ([`fkbox_client_core::storage::Store`]) as the UI uses it:
//! the profile list and the interface preferences are read here, and
//! preferences and the profile order are written here, instead of asking the
//! host. Changes that involve a profile's configuration or the running
//! service still go through the host, which writes the same store and then
//! sends a `profiles` event without a payload (src/app.rs re-reads).
//!
//! The platform shell supplies the store through [`set_store_provider`]; on
//! Android it is the instance the Kotlin layer opened, available once the
//! host has answered `ready`. Without a store (the screenshot harness) the
//! UI keeps using the host's payloads and calls.

use std::sync::{Arc, OnceLock};

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
}
