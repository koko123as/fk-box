//! The dashboard's IP quality cache: the last quality lookup per egress IP,
//! so reopening the dashboard does not query the providers again.
//!
//! Kept in the `preferences` table under [`PREF_KEY`] as one JSON object
//! (IP → `{"quality": {…, "ip": …}, "checkedAt": ms}`). It used to be
//! SharedPreferences `dashboard_ip_quality` (one string per IP), imported
//! once by [`migrate`] the way 2a imported the other preferences: the file
//! is copied first and read from the copy, the original is never written.
//! The cache is not part of backups.

use std::path::{Path, PathBuf};

use serde_json::{Map, Value};

use crate::legacy::shared_preference_strings;
use crate::storage::{Error, Result as StoreResult, Store};

/// The `preferences` key holding the cache.
pub const PREF_KEY: &str = "ip_quality";
/// The meta key recording the one-time import.
pub const MIGRATION_KEY: &str = "legacy_ip_quality_import";
/// Records older than this are dropped when another one is written.
pub const MAX_AGE_MS: i64 = 3 * 24 * 60 * 60 * 1000;
/// The largest record accepted.
pub const MAX_RECORD_BYTES: usize = 16 * 1024;

fn checked_at(record: &Value) -> i64 {
    match record.get("checkedAt") {
        Some(Value::Number(n)) => n.as_i64().or_else(|| n.as_f64().map(|f| f as i64)).unwrap_or(-1),
        _ => -1,
    }
}

fn all(store: &Store) -> Result<Map<String, Value>, String> {
    Ok(match store.preference(PREF_KEY).map_err(|e| e.0)? {
        Some(text) => match serde_json::from_str(&text) {
            Ok(Value::Object(map)) => map,
            _ => Map::new(),
        },
        None => Map::new(),
    })
}

/// The cached record for `ip`, None when there is none.
pub fn read(store: &Store, ip: &str) -> Result<Option<Value>, String> {
    Ok(all(store)?.remove(ip).filter(Value::is_object))
}

/// Caches `record` for `ip` (it must name that IP and a check time no later
/// than `now`), and drops the other IPs' records older than three days or
/// dated in the future. Refused while a backup restore holds the store.
pub fn write(store: &Store, ip: &str, record: &Value, now: i64) -> Result<(), String> {
    if store.in_transaction() {
        return Err("Wait for the restore to finish".into());
    }
    let at = checked_at(record);
    let valid = (1..=128).contains(&ip.chars().count())
        && record.to_string().len() <= MAX_RECORD_BYTES
        && record.pointer("/quality/ip").and_then(Value::as_str) == Some(ip)
        && (0..=now).contains(&at);
    if !valid {
        return Err("Invalid IP quality record".into());
    }
    let mut cache = all(store)?;
    cache.insert(ip.to_string(), record.clone());
    cache.retain(|key, value| key == ip || (now - MAX_AGE_MS..=now).contains(&checked_at(value)));
    store.set_preference(PREF_KEY, &Value::Object(cache).to_string()).map_err(|e| e.0)
}

/// Imports SharedPreferences `dashboard_ip_quality` once (`.bak` first, as
/// Android reads it), never over a cache the store already has. Entries
/// that are not JSON objects are skipped. Returns the result recorded under
/// [`MIGRATION_KEY`].
pub fn migrate(store: &Store, xml: &Path, scratch_dir: &Path) -> StoreResult<String> {
    if let Some(done) = store.meta(MIGRATION_KEY)? {
        return Ok(done);
    }
    let mut backup = xml.as_os_str().to_owned();
    backup.push(".bak");
    let backup = PathBuf::from(backup);
    let source = if backup.is_file() { backup } else { xml.to_path_buf() };
    let mut imported = Map::new();
    if source.is_file() {
        let scratch = scratch_dir.join(format!("legacy-ip-quality-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&scratch);
        std::fs::create_dir_all(&scratch).map_err(|e| Error(format!("legacy import: {e}")))?;
        let copy = scratch.join("dashboard_ip_quality.xml");
        let read = std::fs::copy(&source, &copy).and_then(|_| std::fs::read_to_string(&copy));
        let _ = std::fs::remove_dir_all(&scratch);
        let text = read.map_err(|e| Error(format!("legacy import: read {}: {e}", source.display())))?;
        for (ip, value) in shared_preference_strings(&text) {
            if let Ok(record @ Value::Object(_)) = serde_json::from_str::<Value>(&value) {
                imported.insert(ip, record);
            }
        }
    }
    let status = if imported.is_empty() {
        "none".to_string()
    } else if store.preference(PREF_KEY)?.is_some() {
        "skipped: the store has a cache".to_string()
    } else {
        format!("imported {}", imported.len())
    };
    let summary = serde_json::json!({ "status": status }).to_string();
    store.transaction(|tx| {
        if status.starts_with("imported") {
            tx.execute(
                "INSERT OR REPLACE INTO preferences (key, value) VALUES (?1, ?2)",
                rusqlite::params![PREF_KEY, Value::Object(imported.clone()).to_string()],
            )?;
        }
        tx.execute("INSERT OR REPLACE INTO meta (key, value) VALUES (?1, ?2)", rusqlite::params![MIGRATION_KEY, summary])?;
        Ok(())
    })?;
    Ok(summary)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::content::tests::tempdir;
    use serde_json::json;

    fn record(ip: &str, at: i64) -> Value {
        json!({ "quality": { "ip": ip, "score": 1 }, "checkedAt": at })
    }

    #[test]
    fn records_are_checked_and_old_ones_dropped() {
        let store = Store::open_in_memory().unwrap();
        let now = 10 * MAX_AGE_MS;
        // The record being written is kept even when it is already old.
        write(&store, "1.1.1.1", &record("1.1.1.1", now - MAX_AGE_MS - 1), now).unwrap();
        assert_eq!(read(&store, "1.1.1.1").unwrap(), Some(record("1.1.1.1", now - MAX_AGE_MS - 1)));
        // Writing another IP drops the stale one.
        write(&store, "2.2.2.2", &record("2.2.2.2", now - 5), now).unwrap();
        assert_eq!(read(&store, "1.1.1.1").unwrap(), None);
        // And keeps the fresh ones.
        write(&store, "3.3.3.3", &record("3.3.3.3", now), now).unwrap();
        assert!(read(&store, "2.2.2.2").unwrap().is_some());
        for (ip, bad) in [
            ("4.4.4.4", record("5.5.5.5", now)),
            ("4.4.4.4", record("4.4.4.4", now + 1)),
            ("4.4.4.4", json!({ "quality": { "ip": "4.4.4.4" } })),
            ("", record("", now)),
            ("4.4.4.4", json!({ "quality": { "ip": "4.4.4.4", "pad": "x".repeat(MAX_RECORD_BYTES) }, "checkedAt": now })),
        ] {
            assert_eq!(write(&store, ip, &bad, now).unwrap_err(), "Invalid IP quality record");
        }
        store.begin().unwrap();
        assert!(write(&store, "2.2.2.2", &record("2.2.2.2", now), now).is_err());
        store.rollback().unwrap();
    }

    #[test]
    fn the_shared_preferences_are_imported_once_from_a_copy() {
        let dir = tempdir::Dir::new();
        let xml = dir.0.join("dashboard_ip_quality.xml");
        let original = format!(
            "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n    <string name=\"1.1.1.1\">{}</string>\n    <string name=\"bad\">nope</string>\n</map>\n",
            record("1.1.1.1", 5).to_string().replace('"', "&quot;")
        );
        std::fs::write(&xml, &original).unwrap();
        let store = Store::open_in_memory().unwrap();
        assert_eq!(migrate(&store, &xml, &dir.0).unwrap(), r#"{"status":"imported 1"}"#);
        assert_eq!(read(&store, "1.1.1.1").unwrap(), Some(record("1.1.1.1", 5)));
        assert_eq!(read(&store, "bad").unwrap(), None);
        assert_eq!(std::fs::read_to_string(&xml).unwrap(), original);
        // Recorded: a later change to the file is not imported again.
        std::fs::write(&xml, "<map/>").unwrap();
        assert_eq!(migrate(&store, &xml, &dir.0).unwrap(), r#"{"status":"imported 1"}"#);
        assert!(read(&store, "1.1.1.1").unwrap().is_some());
        // A store that already has a cache keeps it.
        let other = Store::open_in_memory().unwrap();
        write(&other, "9.9.9.9", &record("9.9.9.9", 1), 2).unwrap();
        std::fs::write(&xml, &original).unwrap();
        assert_eq!(migrate(&other, &xml, &dir.0).unwrap(), r#"{"status":"skipped: the store has a cache"}"#);
        assert_eq!(read(&other, "1.1.1.1").unwrap(), None);
        // No file at all.
        let empty = Store::open_in_memory().unwrap();
        assert_eq!(migrate(&empty, &dir.0.join("missing.xml"), &dir.0).unwrap(), r#"{"status":"none"}"#);
    }
}
