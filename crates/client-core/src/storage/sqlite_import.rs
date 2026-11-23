//! One-time import of the SQLite client store (`client.db`, schema 1) that
//! the phase 2 to 4e3b builds kept in the store directory, into the JSON
//! files ([`super::Store::open`] calls it when neither JSON file exists).
//!
//! The database (with its `-wal` and `-shm`, where uncheckpointed writes
//! live) is copied to a scratch directory beside it and read from the copy;
//! `client.db` itself is never written or removed, so an older build still
//! finds it after a downgrade. Everything it held comes over as it was:
//! settings with their kinds, profiles and remote servers with their ids and
//! the AUTOINCREMENT counters (ids are never reused), the preferences, and
//! the `meta` markers, so the Room, WebDAV and IP quality imports that
//! already ran do not run again. The result is recorded under the meta key
//! [`IMPORT_KEY`].
//!
//! A copy that cannot be made is an error (nothing is written, the next
//! start tries again); a database that cannot be read is recorded as
//! `failed` and the store starts empty (the Room import then runs from the
//! older files, and `client.db` stays for a downgrade).

use std::path::{Path, PathBuf};

use rusqlite::{Connection, OpenFlags, OptionalExtension};
use serde_json::json;

use super::{Data, Error, Profile, RemoteServer, Result, SettingValue, SQLITE_FILE};

/// The meta key holding this import's result.
pub const IMPORT_KEY: &str = "sqlite_import";

/// The schema of `client.db` this build reads (its `PRAGMA user_version`).
pub const SQLITE_SCHEMA: i64 = 1;

/// Reads `dir/client.db` into a new [`Data`] (empty with status `none` when
/// there is no database).
pub fn import(dir: &Path) -> Result<Data> {
    let mut data = Data::default();
    let db = dir.join(SQLITE_FILE);
    if !db.is_file() {
        data.set_meta(IMPORT_KEY, &json!({ "status": "none" }).to_string());
        return Ok(data);
    }
    let scratch = dir.join(format!(".sqlite-import-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&scratch);
    let copied = copy_database(&db, &scratch);
    let result = copied.and_then(|copy| {
        let read = read_database(&copy, &mut data);
        Ok(read)
    });
    let _ = std::fs::remove_dir_all(&scratch);
    let summary = match result? {
        Ok(counts) => counts,
        Err(error) => {
            data = Data::default();
            json!({ "status": "failed", "error": error.0 })
        }
    };
    data.set_meta(IMPORT_KEY, &summary.to_string());
    Ok(data)
}

fn copy_database(db: &Path, scratch: &Path) -> Result<PathBuf> {
    std::fs::create_dir_all(scratch).map_err(|e| Error(format!("sqlite import: create {}: {e}", scratch.display())))?;
    let copy = scratch.join(SQLITE_FILE);
    for suffix in ["", "-wal", "-shm"] {
        let mut from = db.as_os_str().to_owned();
        from.push(suffix);
        let from = PathBuf::from(from);
        if from.is_file() {
            let mut to = copy.as_os_str().to_owned();
            to.push(suffix);
            std::fs::copy(&from, PathBuf::from(to)).map_err(|e| Error(format!("sqlite import: copy {}: {e}", from.display())))?;
        }
    }
    Ok(copy)
}

fn table_exists(conn: &Connection, table: &str) -> Result<bool> {
    Ok(conn.query_row("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?1", [table], |_| Ok(())).optional()?.is_some())
}

fn sequence(conn: &Connection, table: &str) -> Result<i64> {
    if !table_exists(conn, "sqlite_sequence")? {
        return Ok(0);
    }
    Ok(conn.query_row("SELECT seq FROM sqlite_sequence WHERE name = ?1", [table], |r| r.get(0)).optional()?.unwrap_or(0))
}

fn read_database(copy: &Path, data: &mut Data) -> Result<serde_json::Value> {
    let conn = Connection::open_with_flags(copy, OpenFlags::SQLITE_OPEN_READ_WRITE | OpenFlags::SQLITE_OPEN_NO_MUTEX)?;
    let version: i64 = conn.pragma_query_value(None, "user_version", |r| r.get(0))?;
    if version > SQLITE_SCHEMA {
        return Err(Error(format!("client.db schema {version} is newer than this build reads ({SQLITE_SCHEMA})")));
    }
    let mut skipped = Vec::new();
    if table_exists(&conn, "meta")? {
        let mut statement = conn.prepare("SELECT key, value FROM meta")?;
        let rows = statement.query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?)))?;
        for row in rows {
            let (key, value) = row?;
            data.set_meta(&key, &value);
        }
    }
    let mut settings = 0;
    if table_exists(&conn, "settings")? {
        let mut statement = conn.prepare("SELECT key, kind, value FROM settings ORDER BY key")?;
        let rows = statement.query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, i64>(1)?, r.get::<_, String>(2)?)))?;
        for row in rows {
            let (key, kind, text) = row?;
            match serde_json::from_str(&text).map_err(Error::from).and_then(|v| SettingValue::from_json(kind, &v)) {
                Ok(value) => {
                    data.put_setting(&key, &value);
                    settings += 1;
                }
                Err(error) => skipped.push(format!("setting {key}: {error}")),
            }
        }
    }
    let mut profiles = 0;
    if table_exists(&conn, "profiles")? {
        let mut statement = conn.prepare(
            "SELECT id, user_order, name, icon, path, kind, remote_url, last_updated, auto_update, auto_update_interval, core, \
             upload, download, total, expire, web_page_url, interval_user_set, resolved_core, resolved_stamp \
             FROM profiles ORDER BY user_order ASC, id ASC",
        )?;
        let rows = statement.query_map([], |r| {
            Ok(Profile {
                id: r.get(0)?,
                user_order: r.get(1)?,
                name: r.get(2)?,
                icon: r.get(3)?,
                path: r.get(4)?,
                kind: r.get(5)?,
                remote_url: r.get(6)?,
                last_updated: r.get(7)?,
                auto_update: r.get(8)?,
                auto_update_interval: r.get(9)?,
                core: r.get(10)?,
                upload: r.get(11)?,
                download: r.get(12)?,
                total: r.get(13)?,
                expire: r.get(14)?,
                web_page_url: r.get(15)?,
                interval_user_set: r.get(16)?,
                resolved_core: r.get(17)?,
                resolved_stamp: r.get(18)?,
            })
        })?;
        for row in rows {
            let profile = row?;
            let id = profile.id;
            match data.insert_profile(&profile) {
                Ok(_) => profiles += 1,
                Err(error) => skipped.push(format!("profile {id}: {error}")),
            }
        }
        data.reserve_profile_ids(sequence(&conn, "profiles")?);
    }
    let mut servers = 0;
    if table_exists(&conn, "remote_servers")? {
        let mut statement = conn.prepare("SELECT id, user_order, name, url, secret FROM remote_servers ORDER BY user_order ASC, id ASC")?;
        let rows = statement.query_map([], |r| {
            Ok(RemoteServer { id: r.get(0)?, user_order: r.get(1)?, name: r.get(2)?, url: r.get(3)?, secret: r.get(4)? })
        })?;
        for row in rows {
            let server = row?;
            let id = server.id;
            match data.insert_remote_server(&server) {
                Ok(_) => servers += 1,
                Err(error) => skipped.push(format!("remote server {id}: {error}")),
            }
        }
        data.reserve_remote_server_ids(sequence(&conn, "remote_servers")?);
    }
    let mut preferences = 0;
    if table_exists(&conn, "preferences")? {
        let mut statement = conn.prepare("SELECT key, value FROM preferences")?;
        let rows = statement.query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?)))?;
        for row in rows {
            let (key, value) = row?;
            data.set_preference(&key, &value);
            preferences += 1;
        }
    }
    Ok(json!({
        "status": "imported",
        "profiles": profiles,
        "remoteServers": servers,
        "settings": settings,
        "preferences": preferences,
        "skipped": skipped,
    }))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::storage::{Store, PROFILES_FILE, SETTINGS_FILE};
    use rusqlite::params;
    use serde_json::Value;

    /// A `client.db` as the phase 2-4e3b builds created it (schema 1).
    fn write_v1(path: &Path) {
        let conn = Connection::open(path).unwrap();
        conn.execute_batch(
            "PRAGMA journal_mode = WAL;
             CREATE TABLE meta (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL);
             CREATE TABLE settings (key TEXT PRIMARY KEY NOT NULL, kind INTEGER NOT NULL, value TEXT NOT NULL);
             CREATE TABLE profiles (
                 id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                 user_order INTEGER NOT NULL DEFAULT 0,
                 name TEXT NOT NULL DEFAULT '',
                 icon TEXT DEFAULT NULL,
                 path TEXT NOT NULL DEFAULT '',
                 kind INTEGER NOT NULL DEFAULT 0,
                 remote_url TEXT NOT NULL DEFAULT '',
                 last_updated INTEGER NOT NULL DEFAULT 0,
                 auto_update INTEGER NOT NULL DEFAULT 0,
                 auto_update_interval INTEGER NOT NULL DEFAULT 60,
                 core TEXT NOT NULL DEFAULT 'auto',
                 upload INTEGER NOT NULL DEFAULT 0,
                 download INTEGER NOT NULL DEFAULT 0,
                 total INTEGER NOT NULL DEFAULT 0,
                 expire INTEGER NOT NULL DEFAULT 0,
                 web_page_url TEXT NOT NULL DEFAULT '',
                 interval_user_set INTEGER NOT NULL DEFAULT 0,
                 resolved_core TEXT NOT NULL DEFAULT '',
                 resolved_stamp TEXT NOT NULL DEFAULT '');
             CREATE TABLE remote_servers (
                 id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                 user_order INTEGER NOT NULL DEFAULT 0,
                 name TEXT NOT NULL DEFAULT '',
                 url TEXT NOT NULL DEFAULT '',
                 secret TEXT NOT NULL DEFAULT '');
             CREATE TABLE preferences (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL);
             PRAGMA user_version = 1;",
        )
        .unwrap();
        conn.execute("INSERT INTO meta VALUES ('legacy_import', '{\"status\":\"imported\"}')", []).unwrap();
        conn.execute("INSERT INTO meta VALUES ('legacy_webdav_import', '{\"status\":\"none\"}')", []).unwrap();
        for (key, kind, value) in [
            ("selected_profile", 3, "7"),
            ("dynamic_notification", 1, "false"),
            ("service_mode", 4, "\"vpn\""),
            ("per_app_proxy_list", 5, "[\"a.b\",\"c.d\"]"),
            ("float", 2, "1.5"),
            ("broken", 1, "\"yes\""),
        ] {
            conn.execute("INSERT INTO settings VALUES (?1, ?2, ?3)", params![key, kind, value]).unwrap();
        }
        conn.execute(
            "INSERT INTO profiles (id, user_order, name, path, kind, remote_url, last_updated, auto_update, auto_update_interval, core, \
             total, expire, web_page_url, interval_user_set, resolved_core, resolved_stamp) \
             VALUES (7, 1, 'sub', '/data/configs/7.json', 1, 'https://e/s', 1700000000000, 1, 120, 'mihomo', 100, 1800000000, 'https://w', 1, 'mihomo', '10:20')",
            [],
        )
        .unwrap();
        conn.execute("INSERT INTO profiles (id, user_order, name, path) VALUES (3, 0, 'local', '/data/configs/3.json')", []).unwrap();
        // Profile 9 was deleted before the upgrade: its id stays used.
        conn.execute("INSERT INTO profiles (id, name, path) VALUES (9, 'gone', '/x')", []).unwrap();
        conn.execute("DELETE FROM profiles WHERE id = 9", []).unwrap();
        conn.execute("INSERT INTO remote_servers (id, user_order, name, url, secret) VALUES (2, 0, 'nas', '10.0.0.2:9090', 's')", [])
            .unwrap();
        conn.execute("INSERT INTO preferences VALUES ('ui', '{\"theme\":\"dark\"}')", []).unwrap();
        conn.execute("INSERT INTO preferences VALUES ('backupOrigin', 'origin-1')", []).unwrap();
        // Left in the WAL (no checkpoint), as a running app leaves it.
        std::mem::forget(conn);
    }

    fn snapshot(dir: &Path) -> Vec<(String, Vec<u8>)> {
        let mut files: Vec<_> = std::fs::read_dir(dir)
            .unwrap()
            .map(|e| e.unwrap().path())
            .filter(|p| p.file_name().unwrap().to_string_lossy().starts_with("client.db"))
            .map(|p| (p.file_name().unwrap().to_string_lossy().into_owned(), std::fs::read(&p).unwrap()))
            .collect();
        files.sort();
        files
    }

    #[test]
    fn imports_client_db_once_and_leaves_it_untouched() {
        let dir = crate::legacy::tests::scratch("sqlite-import");
        let store_dir = dir.join("fkbox");
        std::fs::create_dir_all(&store_dir).unwrap();
        write_v1(&store_dir.join(SQLITE_FILE));
        let before = snapshot(&store_dir);
        {
            let store = Store::open(&store_dir).unwrap();
            let profiles = store.profiles().unwrap();
            assert_eq!(profiles.iter().map(|p| (p.id, p.name.as_str())).collect::<Vec<_>>(), [(3, "local"), (7, "sub")]);
            let sub = store.profile(7).unwrap().unwrap();
            assert_eq!((sub.kind, sub.core.as_str(), sub.auto_update_interval, sub.total), (1, "mihomo", 120, 100));
            assert!(sub.interval_user_set && sub.auto_update);
            assert_eq!((sub.resolved_core.as_str(), sub.resolved_stamp.as_str()), ("mihomo", "10:20"));
            assert_eq!(store.setting("selected_profile").unwrap(), Some(SettingValue::Long(7)));
            assert_eq!(store.setting("dynamic_notification").unwrap(), Some(SettingValue::Bool(false)));
            assert_eq!(store.setting("float").unwrap(), Some(SettingValue::Float(1.5)));
            assert_eq!(
                store.setting("per_app_proxy_list").unwrap(),
                Some(SettingValue::StringSet(vec!["a.b".into(), "c.d".into()]))
            );
            assert_eq!(store.setting("broken").unwrap(), None);
            assert_eq!(store.remote_servers().unwrap()[0].secret, "s");
            assert_eq!(Value::Object(store.ui_preferences().unwrap()), json!({"theme": "dark"}));
            assert_eq!(store.backup_origin(|| unreachable!()).unwrap(), "origin-1");
            // The earlier imports stay recorded, so they do not run again.
            assert!(store.meta("legacy_import").unwrap().unwrap().contains("imported"));
            assert!(store.meta("legacy_webdav_import").unwrap().is_some());
            let summary: Value = serde_json::from_str(&store.meta(IMPORT_KEY).unwrap().unwrap()).unwrap();
            assert_eq!(summary["status"], "imported");
            assert_eq!((summary["profiles"].as_i64(), summary["settings"].as_i64()), (Some(2), Some(5)));
            assert_eq!(summary["skipped"].as_array().unwrap().len(), 1);
            // Ids are not reused: 9 was handed out before the upgrade.
            assert_eq!(store.insert_profile(&Profile { name: "new".into(), ..Default::default() }).unwrap(), 10);
            assert_eq!(store.insert_remote_server(&RemoteServer::default()).unwrap(), 3);
        }
        assert!(store_dir.join(PROFILES_FILE).is_file() && store_dir.join(SETTINGS_FILE).is_file());
        assert_eq!(snapshot(&store_dir), before, "client.db is left as it was");
        assert!(!std::fs::read_dir(&store_dir).unwrap().any(|e| e.unwrap().file_name().to_string_lossy().starts_with(".sqlite-import")));

        // Reopened: read from the JSON files, the database is not imported again.
        std::fs::remove_file(store_dir.join(SQLITE_FILE)).unwrap();
        let store = Store::open(&store_dir).unwrap();
        assert_eq!(store.profiles().unwrap().len(), 3);
        assert_eq!(store.setting("service_mode").unwrap(), Some(SettingValue::String("vpn".into())));
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn no_database_records_none() {
        let dir = crate::legacy::tests::scratch("sqlite-none");
        let store = Store::open(&dir).unwrap();
        assert!(store.is_empty().unwrap());
        assert_eq!(store.meta(IMPORT_KEY).unwrap().as_deref(), Some(r#"{"status":"none"}"#));
        assert!(dir.join(SETTINGS_FILE).is_file());
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn an_unreadable_database_is_recorded_and_the_store_starts_empty() {
        let dir = crate::legacy::tests::scratch("sqlite-corrupt");
        std::fs::write(dir.join(SQLITE_FILE), b"definitely not sqlite, but long enough to look like a header....").unwrap();
        let store = Store::open(&dir).unwrap();
        assert!(store.is_empty().unwrap());
        let summary: Value = serde_json::from_str(&store.meta(IMPORT_KEY).unwrap().unwrap()).unwrap();
        assert_eq!(summary["status"], "failed");
        // The Room import still runs on an empty store.
        assert_eq!(store.meta(crate::legacy::MIGRATION_KEY).unwrap(), None);
        assert!(dir.join(SQLITE_FILE).is_file());
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn a_newer_database_schema_is_not_imported() {
        let dir = crate::legacy::tests::scratch("sqlite-newer");
        {
            let conn = Connection::open(dir.join(SQLITE_FILE)).unwrap();
            conn.pragma_update(None, "user_version", SQLITE_SCHEMA + 1).unwrap();
        }
        let store = Store::open(&dir).unwrap();
        assert!(store.meta(IMPORT_KEY).unwrap().unwrap().contains("failed"));
        std::fs::remove_dir_all(dir).unwrap();
    }
}
