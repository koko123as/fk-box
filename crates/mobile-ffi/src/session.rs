//! The process's client store ([`Store`]) and its one-time imports.
//!
//! The store is a directory of JSON files that Rust owns (`profiles.json`,
//! `settings.json`, as Clash-of-Rust's mobile layer keeps them): loading,
//! atomic saves, schema, defaults, ids and ordering are in
//! `fkbox_client_core::storage`; the platform host only names the directory.
//! The host opens it on first use ([`open`], with the app's paths), which
//! also runs the one-time imports of the earlier storage (the SQLite
//! `client.db` of the phase 2-4e3b builds in the same directory, the Room
//! databases and SharedPreferences of older releases), all read from copies
//! and never written or removed, so a downgrade still finds them. The UI
//! takes the same instance ([`store`]), so the process has one copy of the
//! state. On Android the service layer opens it before the activity
//! exists, so the service can start (boot, tile) without the UI.

use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

use fkbox_client_core::content::Kernel;
use fkbox_client_core::ip_quality;
use fkbox_client_core::legacy::{self, Outcome, Sources};
use fkbox_client_core::storage::{sqlite_import, Store};

use crate::api;

static STORE: Mutex<Option<Arc<Store>>> = Mutex::new(None);

const NOT_OPEN: &str = "The client store is not open";

/// The store the host opened, if it has.
pub fn store() -> Option<Arc<Store>> {
    STORE.lock().unwrap_or_else(|e| e.into_inner()).clone()
}

/// Opens the store in the directory `dir` (once per process; later calls
/// return the summary of the imports already recorded) and imports the
/// earlier storage: the Room databases and interface preferences
/// (`sources`), the WebDAV account from SharedPreferences `bbox_webdav`
/// (`webdav`, its own one-time import, phase 2d) and the dashboard's IP
/// quality cache from `dashboard_ip_quality` beside it (phase 2e).
pub fn open(dir: PathBuf, sources: Sources, webdav: PathBuf) -> Result<String, String> {
    let mut slot = STORE.lock().unwrap_or_else(|e| e.into_inner());
    let store = match slot.as_ref() {
        Some(store) => store.clone(),
        None => {
            let store = Arc::new(Store::open(&dir).map_err(|e| e.0)?);
            *slot = Some(store.clone());
            store
        }
    };
    drop(slot);
    Ok(import(&store, &sources, &webdav))
}

/// Runs the one-time imports into `store` and returns their results.
fn import(store: &Store, sources: &Sources, webdav_path: &Path) -> String {
    // The import of the SQLite store ran in Store::open; its result is kept.
    let sqlite = store.meta(sqlite_import::IMPORT_KEY).ok().flatten().unwrap_or_default();
    // An import failure is reported, not fatal: the store stays empty and
    // unmarked, and the next start tries again.
    let summary = match legacy::migrate(store, sources) {
        Ok(Outcome::AlreadyDone(summary)) => summary,
        Ok(Outcome::Done(summary)) => serde_json::to_string(&summary).unwrap_or_default(),
        Err(error) => serde_json::json!({ "status": "failed", "error": error.0 }).to_string(),
    };
    let webdav = match legacy::migrate_webdav(store, webdav_path, &sources.scratch_dir) {
        Ok(summary) => summary,
        Err(error) => serde_json::json!({ "status": "failed", "error": error.0 }).to_string(),
    };
    // The dashboard's IP quality cache sits next to `bbox_webdav` in
    // shared_prefs (phase 2e, its own one-time import).
    let quality_xml = webdav_path.with_file_name("dashboard_ip_quality.xml");
    let quality = match ip_quality::migrate(store, &quality_xml, &sources.scratch_dir) {
        Ok(summary) => summary,
        Err(error) => serde_json::json!({ "status": "failed", "error": error.0 }).to_string(),
    };
    format!("{summary}; sqlite: {sqlite}; webdav: {webdav}; ipQuality: {quality}")
}

/// One [`api`] call on the open store.
pub fn call(method: &str, args: &str) -> Result<String, String> {
    let store = store().ok_or(NOT_OPEN)?;
    api::call(&store, method, args)
}

/// One [`api`] call that may reach the cores through `kernel`.
pub fn call_with_kernel(kernel: &mut dyn Kernel, method: &str, args: &str) -> Result<String, String> {
    let store = store().ok_or(NOT_OPEN)?;
    api::call_with_kernel(&store, kernel, method, args)
}

#[cfg(test)]
mod tests {
    use super::*;
    use rusqlite::Connection;
    use serde_json::Value;

    fn scratch(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("fkbox-ffi-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    fn sources(app: &Path) -> Sources {
        Sources {
            profiles_db: app.join("databases/profiles.db"),
            settings_db: app.join("databases/settings.db"),
            ui_preferences: app.join("shared_prefs/flclash_ui.xml"),
            scratch_dir: app.join("cache"),
        }
    }

    fn open_at(app: &Path) -> (Store, String) {
        let store = Store::open(&app.join("files/fkbox")).unwrap();
        let summary = import(&store, &sources(app), &app.join("shared_prefs/bbox_webdav.xml"));
        (store, summary)
    }

    /// An install upgraded from a phase 2-4e3b build: `files/fkbox/client.db`
    /// (whose Room import already ran) comes over through the JSON call
    /// surface the Kotlin host uses, and stays on disk.
    #[test]
    fn upgrade_from_the_sqlite_store() {
        let app = scratch("upgrade");
        let db = app.join("files/fkbox/client.db");
        std::fs::create_dir_all(db.parent().unwrap()).unwrap();
        let conn = Connection::open(&db).unwrap();
        conn.execute_batch(
            "CREATE TABLE meta (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL);
             CREATE TABLE settings (key TEXT PRIMARY KEY NOT NULL, kind INTEGER NOT NULL, value TEXT NOT NULL);
             CREATE TABLE profiles (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, user_order INTEGER NOT NULL DEFAULT 0,
                 name TEXT NOT NULL DEFAULT '', icon TEXT DEFAULT NULL, path TEXT NOT NULL DEFAULT '', kind INTEGER NOT NULL DEFAULT 0,
                 remote_url TEXT NOT NULL DEFAULT '', last_updated INTEGER NOT NULL DEFAULT 0, auto_update INTEGER NOT NULL DEFAULT 0,
                 auto_update_interval INTEGER NOT NULL DEFAULT 60, core TEXT NOT NULL DEFAULT 'auto', upload INTEGER NOT NULL DEFAULT 0,
                 download INTEGER NOT NULL DEFAULT 0, total INTEGER NOT NULL DEFAULT 0, expire INTEGER NOT NULL DEFAULT 0,
                 web_page_url TEXT NOT NULL DEFAULT '', interval_user_set INTEGER NOT NULL DEFAULT 0,
                 resolved_core TEXT NOT NULL DEFAULT '', resolved_stamp TEXT NOT NULL DEFAULT '');
             CREATE TABLE remote_servers (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, user_order INTEGER NOT NULL DEFAULT 0,
                 name TEXT NOT NULL DEFAULT '', url TEXT NOT NULL DEFAULT '', secret TEXT NOT NULL DEFAULT '');
             CREATE TABLE preferences (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL);
             PRAGMA user_version = 1;
             INSERT INTO meta VALUES ('legacy_import', '{\"status\":\"imported\",\"profiles\":1}');
             INSERT INTO settings VALUES ('selected_profile', 3, '5');
             INSERT INTO profiles (id, user_order, name, path, core) VALUES (5, 0, 'home', '/data/configs/5.json', 'xray');
             INSERT INTO preferences VALUES ('ui', '{\"theme\":\"dark\"}');",
        )
        .unwrap();
        drop(conn);
        let before = std::fs::read(&db).unwrap();

        let (store, summary) = open_at(&app);
        assert!(summary.starts_with(r#"{"status":"imported","profiles":1}"#), "the Room import is not run again: {summary}");
        let sqlite = summary.split("sqlite: ").nth(1).and_then(|rest| rest.split("; webdav").next()).unwrap();
        let sqlite: Value = serde_json::from_str(sqlite).unwrap();
        assert_eq!((sqlite["status"].as_str(), sqlite["profiles"].as_i64()), (Some("imported"), Some(1)), "{summary}");
        let list: Value = serde_json::from_str(&api::call(&store, "profiles.list", "{}").unwrap()).unwrap();
        assert_eq!(list[0]["name"], "home");
        assert_eq!(list[0]["core"], "xray");
        let settings: Value = serde_json::from_str(&api::call(&store, "settings.all", "{}").unwrap()).unwrap();
        assert_eq!(settings["selected_profile"], serde_json::json!({"t": 3, "v": 5}));
        assert_eq!(std::fs::read(&db).unwrap(), before, "client.db is kept for a downgrade");
        drop(store);

        // The next start reads the JSON files; nothing is imported again.
        let (store, again) = open_at(&app);
        assert_eq!(again, summary);
        assert_eq!(store.profiles().unwrap().len(), 1);
        std::fs::remove_dir_all(app).unwrap();
    }

    /// A fresh install: nothing to import, empty files written once.
    #[test]
    fn fresh_install() {
        let app = scratch("fresh");
        let (store, summary) = open_at(&app);
        assert!(store.is_empty().unwrap());
        assert!(summary.starts_with(r#"{"status":"none""#), "{summary}");
        assert!(app.join("files/fkbox/profiles.json").is_file());
        assert!(app.join("files/fkbox/settings.json").is_file());
        std::fs::remove_dir_all(app).unwrap();
    }
}
