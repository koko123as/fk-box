//! One-time import of the Android app's earlier storage into the [`Store`]:
//!
//! - Room `profiles.db` (`profiles`, and `remote_servers` from schema 3;
//!   apps/android/app/schemas/io.nekohasekai.sfa.database.ProfileDatabase),
//! - Room `settings.db` (`KeyValueEntity`;
//!   apps/android/app/schemas/io.nekohasekai.sfa.database.preference.KeyValueDatabase),
//! - SharedPreferences `flclash_ui` (the interface preferences JSON under
//!   `value`, the backup identity under `backupOrigin`).
//!
//! The old files are never written: each database is copied (with its `-wal`
//! and `-shm`) to a scratch directory and read from the copy, so they stay as
//! they were for a downgrade or a retry. The import runs only while the store
//! is empty, in one transaction that also records the result under the meta
//! key [`MIGRATION_KEY`]; running it again does nothing.

use std::path::{Path, PathBuf};

use rusqlite::{Connection, OpenFlags, OptionalExtension};
use serde::Serialize;
use serde_json::Value;

use crate::parcel;
use crate::storage::{self, Error, Profile, RemoteServer, Result, SettingValue, Store, PREF_BACKUP_ORIGIN, PREF_UI};

/// The meta key holding the import's result ([`Summary`] as JSON).
pub const MIGRATION_KEY: &str = "legacy_import";

/// Where the earlier storage lives; every file may be absent (a fresh
/// install has none). The platform shell supplies the paths.
#[derive(Debug, Clone)]
pub struct Sources {
    /// Room `profiles.db` (Context.getDatabasePath("profiles.db")).
    pub profiles_db: PathBuf,
    /// Room `settings.db`.
    pub settings_db: PathBuf,
    /// SharedPreferences XML `shared_prefs/flclash_ui.xml`.
    pub ui_preferences: PathBuf,
    /// A directory the copies are read from; removed afterwards.
    pub scratch_dir: PathBuf,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Summary {
    /// "imported", "none" (nothing to import) or "skipped" (the store
    /// already held data, nothing was imported).
    pub status: String,
    pub profiles: usize,
    pub remote_servers: usize,
    pub settings: usize,
    pub preferences: bool,
    pub backup_origin: bool,
    /// Rows that could not be read and were left out, with the reason.
    pub skipped: Vec<String>,
    /// Room schema version of profiles.db (0 when absent).
    pub profiles_schema: i64,
}

impl Summary {
    fn new(status: &str) -> Self {
        Summary {
            status: status.into(),
            profiles: 0,
            remote_servers: 0,
            settings: 0,
            preferences: false,
            backup_origin: false,
            skipped: Vec::new(),
            profiles_schema: 0,
        }
    }
}

/// What [`migrate`] did.
#[derive(Debug, Clone, PartialEq)]
pub enum Outcome {
    /// An earlier run already recorded its result (returned as stored).
    AlreadyDone(String),
    /// This run imported, found nothing, or skipped; the summary is recorded.
    Done(Summary),
}

/// Imports the earlier storage into `store` once. An error leaves the store
/// unchanged and unmarked, so the next start tries again while the store is
/// still empty.
pub fn migrate(store: &Store, sources: &Sources) -> Result<Outcome> {
    if let Some(done) = store.meta(MIGRATION_KEY)? {
        return Ok(Outcome::AlreadyDone(done));
    }
    if !store.is_empty()? {
        let summary = Summary::new("skipped");
        store.set_meta(MIGRATION_KEY, &serde_json::to_string(&summary)?)?;
        return Ok(Outcome::Done(summary));
    }
    let scratch = sources.scratch_dir.join(format!("legacy-import-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&scratch);
    let result = read_sources(sources, &scratch);
    let _ = std::fs::remove_dir_all(&scratch);
    let legacy = result?;

    let mut summary = Summary::new(if legacy.is_empty() { "none" } else { "imported" });
    summary.profiles_schema = legacy.profiles_schema;
    summary.skipped = legacy.skipped.clone();
    store.transaction(|tx| {
        for profile in &legacy.profiles {
            storage::insert_profile(tx, profile)?;
        }
        for server in &legacy.servers {
            storage::insert_remote_server(tx, server)?;
        }
        // Room's AUTOINCREMENT counters, so an id deleted before the upgrade
        // is not handed out again (interface preferences key folds by id).
        for (table, seq) in [("profiles", legacy.profiles_seq), ("remote_servers", legacy.servers_seq)] {
            if seq > 0 {
                let current: Option<i64> =
                    tx.query_row("SELECT seq FROM sqlite_sequence WHERE name = ?1", [table], |r| r.get(0)).optional()?;
                match current {
                    Some(current) if current >= seq => {}
                    Some(_) => {
                        tx.execute("UPDATE sqlite_sequence SET seq = ?2 WHERE name = ?1", rusqlite::params![table, seq])?;
                    }
                    None => {
                        tx.execute("INSERT INTO sqlite_sequence (name, seq) VALUES (?1, ?2)", rusqlite::params![table, seq])?;
                    }
                }
            }
        }
        for (key, value) in &legacy.settings {
            storage::put_setting(tx, key, value)?;
        }
        if let Some(value) = &legacy.ui_preferences {
            tx.execute("INSERT OR REPLACE INTO preferences (key, value) VALUES (?1, ?2)", rusqlite::params![PREF_UI, value])?;
        }
        if let Some(origin) = &legacy.backup_origin {
            tx.execute("INSERT OR REPLACE INTO preferences (key, value) VALUES (?1, ?2)", rusqlite::params![PREF_BACKUP_ORIGIN, origin])?;
        }
        summary.profiles = legacy.profiles.len();
        summary.remote_servers = legacy.servers.len();
        summary.settings = legacy.settings.len();
        summary.preferences = legacy.ui_preferences.is_some();
        summary.backup_origin = legacy.backup_origin.is_some();
        tx.execute(
            "INSERT OR REPLACE INTO meta (key, value) VALUES (?1, ?2)",
            rusqlite::params![MIGRATION_KEY, serde_json::to_string(&summary)?],
        )?;
        Ok(())
    })?;
    Ok(Outcome::Done(summary))
}

#[derive(Default)]
struct Legacy {
    profiles: Vec<Profile>,
    profiles_seq: i64,
    profiles_schema: i64,
    servers: Vec<RemoteServer>,
    servers_seq: i64,
    settings: Vec<(String, SettingValue)>,
    ui_preferences: Option<String>,
    backup_origin: Option<String>,
    skipped: Vec<String>,
}

impl Legacy {
    fn is_empty(&self) -> bool {
        self.profiles.is_empty()
            && self.servers.is_empty()
            && self.settings.is_empty()
            && self.ui_preferences.is_none()
            && self.backup_origin.is_none()
    }
}

fn read_sources(sources: &Sources, scratch: &Path) -> Result<Legacy> {
    let mut legacy = Legacy::default();
    if let Some(conn) = open_copy(&sources.profiles_db, &scratch.join("profiles"))? {
        read_profiles_db(&conn, &mut legacy)?;
    }
    if let Some(conn) = open_copy(&sources.settings_db, &scratch.join("settings"))? {
        read_settings_db(&conn, &mut legacy)?;
    }
    read_ui_preferences(&sources.ui_preferences, &mut legacy)?;
    Ok(legacy)
}

/// Copies `db` and its `-wal`/`-shm` (uncheckpointed writes live in the WAL)
/// into `dir` and opens the copy; `None` when there is no database.
fn open_copy(db: &Path, dir: &Path) -> Result<Option<Connection>> {
    if !db.is_file() {
        return Ok(None);
    }
    std::fs::create_dir_all(dir).map_err(|e| Error(format!("legacy import: create {}: {e}", dir.display())))?;
    let name = db.file_name().ok_or_else(|| Error("legacy import: database has no file name".into()))?;
    let copy = dir.join(name);
    for suffix in ["", "-wal", "-shm"] {
        let mut from = db.as_os_str().to_owned();
        from.push(suffix);
        let from = PathBuf::from(from);
        if from.is_file() {
            let mut to = copy.as_os_str().to_owned();
            to.push(suffix);
            std::fs::copy(&from, PathBuf::from(to)).map_err(|e| Error(format!("legacy import: copy {}: {e}", from.display())))?;
        }
    }
    let conn = Connection::open_with_flags(&copy, OpenFlags::SQLITE_OPEN_READ_WRITE | OpenFlags::SQLITE_OPEN_NO_MUTEX)?;
    Ok(Some(conn))
}

fn table_exists(conn: &Connection, table: &str) -> Result<bool> {
    Ok(conn.query_row("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?1", [table], |_| Ok(())).optional()?.is_some())
}

fn column_exists(conn: &Connection, table: &str, column: &str) -> Result<bool> {
    let mut statement = conn.prepare(&format!("PRAGMA table_info(`{table}`)"))?;
    let names = statement.query_map([], |r| r.get::<_, String>(1))?.collect::<rusqlite::Result<Vec<_>>>()?;
    Ok(names.iter().any(|n| n == column))
}

fn sequence(conn: &Connection, table: &str) -> Result<i64> {
    if !table_exists(conn, "sqlite_sequence")? {
        return Ok(0);
    }
    Ok(conn.query_row("SELECT seq FROM sqlite_sequence WHERE name = ?1", [table], |r| r.get(0)).optional()?.unwrap_or(0))
}

fn read_profiles_db(conn: &Connection, legacy: &mut Legacy) -> Result<()> {
    legacy.profiles_schema = conn.pragma_query_value(None, "user_version", |r| r.get(0))?;
    if table_exists(conn, "profiles")? {
        // Schema 1 has no icon column (MIGRATION_1_2 added it).
        let icon = if column_exists(conn, "profiles", "icon")? { "icon" } else { "NULL" };
        let mut statement =
            conn.prepare(&format!("SELECT id, userOrder, name, {icon}, typed FROM profiles ORDER BY userOrder ASC, id ASC"))?;
        let rows = statement.query_map([], |r| {
            Ok((r.get::<_, i64>(0)?, r.get::<_, i64>(1)?, r.get::<_, String>(2)?, r.get::<_, Option<String>>(3)?, r.get::<_, Vec<u8>>(4)?))
        })?;
        for row in rows {
            let (id, user_order, name, icon, typed) = row?;
            let mut profile = Profile { id, user_order, name, icon, ..Default::default() };
            match parcel::read_typed_profile(&typed, &mut profile) {
                Ok(()) => legacy.profiles.push(profile),
                Err(error) => legacy.skipped.push(format!("profile {id}: {error}")),
            }
        }
        legacy.profiles_seq = sequence(conn, "profiles")?;
    }
    if table_exists(conn, "remote_servers")? {
        let mut statement = conn.prepare("SELECT id, userOrder, name, url, secret FROM remote_servers ORDER BY userOrder ASC, id ASC")?;
        let rows = statement.query_map([], |r| {
            Ok(RemoteServer { id: r.get(0)?, user_order: r.get(1)?, name: r.get(2)?, url: r.get(3)?, secret: r.get(4)? })
        })?;
        legacy.servers = rows.collect::<rusqlite::Result<Vec<_>>>()?;
        legacy.servers_seq = sequence(conn, "remote_servers")?;
    }
    Ok(())
}

fn read_settings_db(conn: &Connection, legacy: &mut Legacy) -> Result<()> {
    if !table_exists(conn, "KeyValueEntity")? {
        return Ok(());
    }
    let mut statement = conn.prepare("SELECT `key`, valueType, value FROM KeyValueEntity ORDER BY `key`")?;
    let rows = statement.query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, i64>(1)?, r.get::<_, Vec<u8>>(2)?)))?;
    for row in rows {
        let (key, kind, value) = row?;
        match decode_key_value(kind, &value) {
            Ok(value) => legacy.settings.push((key, value)),
            Err(error) => legacy.skipped.push(format!("setting {key}: {error}")),
        }
    }
    Ok(())
}

/// Decodes a `KeyValueEntity.value` (java.nio.ByteBuffer, big-endian).
pub fn decode_key_value(kind: i64, value: &[u8]) -> std::result::Result<SettingValue, String> {
    let fixed =
        |n: usize| -> std::result::Result<&[u8], String> { value.get(..n).ok_or_else(|| format!("{} bytes, expected {n}", value.len())) };
    Ok(match kind {
        SettingValue::BOOLEAN => SettingValue::Bool(fixed(1)?[0] != 0),
        SettingValue::FLOAT => SettingValue::Float(f32::from_be_bytes(fixed(4)?.try_into().unwrap())),
        SettingValue::LONG => SettingValue::Long(i64::from_be_bytes(fixed(8)?.try_into().unwrap())),
        SettingValue::STRING => SettingValue::String(String::from_utf8(value.to_vec()).map_err(|_| "invalid UTF-8")?),
        SettingValue::STRING_SET => SettingValue::StringSet(decode_string_set(value)?),
        other => return Err(format!("unknown value type {other}")),
    })
}

/// `KeyValueEntity.put(Set<String>)` wrote each entry as a 4-byte length
/// followed by the entry's UTF-8 bytes, but the length was `String.length`
/// (UTF-16 units), not the byte count. For ASCII entries (package names,
/// peer ids) the two agree; for others the entry's bytes are taken until
/// they decode to that many UTF-16 units, which recovers what was written.
fn decode_string_set(value: &[u8]) -> std::result::Result<Vec<String>, String> {
    let mut out: Vec<String> = Vec::new();
    let mut pos = 0;
    while pos < value.len() {
        let len = value.get(pos..pos + 4).ok_or("truncated string set")?;
        let units = i32::from_be_bytes(len.try_into().unwrap());
        let units = usize::try_from(units).map_err(|_| "invalid string set entry length")?;
        pos += 4;
        let rest = &value[pos..];
        let mut end = 0;
        let mut counted = 0;
        while counted < units {
            let width = match rest.get(end) {
                Some(b) if *b < 0x80 => 1,
                Some(b) if *b >= 0xf0 => 4,
                Some(b) if *b >= 0xe0 => 3,
                Some(_) => 2,
                None => return Err("truncated string set entry".into()),
            };
            end += width;
            counted += if width == 4 { 2 } else { 1 };
        }
        let entry = std::str::from_utf8(rest.get(..end).ok_or("truncated string set entry")?).map_err(|_| "invalid UTF-8 in string set")?;
        if !out.iter().any(|e| e == entry) {
            out.push(entry.to_string());
        }
        pos += end;
    }
    Ok(out)
}

/// Android keeps `<name>.xml.bak` while a write is in flight and restores it
/// on the next read, so it wins when present.
fn read_ui_preferences(path: &Path, legacy: &mut Legacy) -> Result<()> {
    let mut backup = path.as_os_str().to_owned();
    backup.push(".bak");
    let backup = PathBuf::from(backup);
    let file = if backup.is_file() { backup } else { path.to_path_buf() };
    if !file.is_file() {
        return Ok(());
    }
    let text = std::fs::read_to_string(&file).map_err(|e| Error(format!("legacy import: read {}: {e}", file.display())))?;
    let strings = shared_preference_strings(&text);
    if let Some(value) = strings.iter().find(|(k, _)| k == "value").map(|(_, v)| v) {
        // Kept as stored (validated when read, as before); only a JSON object counts.
        match serde_json::from_str::<Value>(value) {
            Ok(Value::Object(_)) => legacy.ui_preferences = Some(value.clone()),
            _ => legacy.skipped.push("interface preferences: not a JSON object".into()),
        }
    }
    legacy.backup_origin = strings.iter().find(|(k, v)| k == "backupOrigin" && !v.is_empty()).map(|(_, v)| v.clone());
    Ok(())
}

/// The `<string name="…">…</string>` entries of a SharedPreferences XML file
/// (other value types are not used by `flclash_ui`).
pub fn shared_preference_strings(xml: &str) -> Vec<(String, String)> {
    let mut out = Vec::new();
    let mut rest = xml;
    while let Some(start) = rest.find("<string") {
        rest = &rest[start + "<string".len()..];
        let Some(tag_end) = rest.find('>') else { break };
        let attributes = &rest[..tag_end];
        let Some(name) = attribute(attributes, "name") else {
            rest = &rest[tag_end + 1..];
            continue;
        };
        if attributes.trim_end().ends_with('/') {
            out.push((name, String::new()));
            rest = &rest[tag_end + 1..];
            continue;
        }
        rest = &rest[tag_end + 1..];
        let Some(close) = rest.find("</string>") else { break };
        out.push((name, unescape(&rest[..close])));
        rest = &rest[close + "</string>".len()..];
    }
    out
}

fn attribute(attributes: &str, name: &str) -> Option<String> {
    let mut search = attributes;
    loop {
        let at = search.find(name)?;
        let after = search[at + name.len()..].trim_start();
        let preceded = at == 0 || search[..at].ends_with(char::is_whitespace);
        if preceded && after.starts_with('=') {
            let after = after[1..].trim_start();
            let quote = after.chars().next()?;
            if quote != '"' && quote != '\'' {
                return None;
            }
            let value = &after[1..];
            let end = value.find(quote)?;
            return Some(unescape(&value[..end]));
        }
        search = &search[at + name.len()..];
    }
}

fn unescape(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    let mut rest = text;
    while let Some(amp) = rest.find('&') {
        out.push_str(&rest[..amp]);
        rest = &rest[amp..];
        let Some(semi) = rest.find(';') else { break };
        let entity = &rest[1..semi];
        let decoded = match entity {
            "amp" => Some('&'),
            "lt" => Some('<'),
            "gt" => Some('>'),
            "quot" => Some('"'),
            "apos" => Some('\''),
            _ if entity.starts_with("#x") || entity.starts_with("#X") => {
                u32::from_str_radix(&entity[2..], 16).ok().and_then(char::from_u32)
            }
            _ if entity.starts_with('#') => entity[1..].parse().ok().and_then(char::from_u32),
            _ => None,
        };
        match decoded {
            Some(c) => {
                out.push(c);
                rest = &rest[semi + 1..];
            }
            None => {
                out.push('&');
                rest = &rest[1..];
            }
        }
    }
    out.push_str(rest);
    out
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use serde_json::json;
    use std::sync::atomic::{AtomicUsize, Ordering};

    /// A fresh scratch directory under the system temp dir.
    pub(crate) fn scratch(name: &str) -> PathBuf {
        static NEXT: AtomicUsize = AtomicUsize::new(0);
        let dir = std::env::temp_dir().join(format!("fkbox-core-{}-{}-{}", name, std::process::id(), NEXT.fetch_add(1, Ordering::SeqCst)));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    /// The Room schema JSON exported by the app (apps/android/app/schemas).
    fn room_schema(database: &str, version: u32) -> Value {
        let path = Path::new(env!("CARGO_MANIFEST_DIR"))
            .join(format!("../../apps/android/app/schemas/io.nekohasekai.sfa.database.{database}/{version}.json"));
        serde_json::from_str(&std::fs::read_to_string(&path).unwrap_or_else(|e| panic!("{}: {e}", path.display()))).unwrap()
    }

    /// Creates a database at `path` the way Room would from its exported
    /// schema: every entity's createSql, the identity table and user_version.
    fn create_room_db(path: &Path, database: &str, version: u32) -> Connection {
        let schema = room_schema(database, version);
        let db = &schema["database"];
        let conn = Connection::open(path).unwrap();
        conn.pragma_update(None, "journal_mode", "WAL").unwrap();
        for entity in db["entities"].as_array().unwrap() {
            let sql = entity["createSql"].as_str().unwrap().replace("${TABLE_NAME}", entity["tableName"].as_str().unwrap());
            conn.execute_batch(&sql).unwrap();
        }
        for query in db["setupQueries"].as_array().unwrap() {
            conn.execute_batch(query.as_str().unwrap()).unwrap();
        }
        conn.pragma_update(None, "user_version", db["version"].as_i64().unwrap()).unwrap();
        conn
    }

    fn kv_bytes(value: &SettingValue) -> Vec<u8> {
        match value {
            SettingValue::Bool(v) => vec![u8::from(*v)],
            SettingValue::Float(v) => v.to_be_bytes().to_vec(),
            SettingValue::Long(v) => v.to_be_bytes().to_vec(),
            SettingValue::String(v) => v.as_bytes().to_vec(),
            // As KeyValueEntity.put(Set) wrote it: UTF-16 length, UTF-8 bytes.
            SettingValue::StringSet(v) => v
                .iter()
                .flat_map(|s| {
                    let mut out = (s.encode_utf16().count() as i32).to_be_bytes().to_vec();
                    out.extend_from_slice(s.as_bytes());
                    out
                })
                .collect(),
        }
    }

    fn remote_profile(id: i64, order: i64, name: &str) -> Profile {
        Profile {
            id,
            user_order: order,
            name: name.into(),
            path: format!("/data/user/0/io.fkbox.app/files/configs/{name}.yaml"),
            kind: 1,
            remote_url: "https://sub.example/s?t=1".into(),
            last_updated: 1_732_000_000_000,
            auto_update: true,
            auto_update_interval: 180,
            core: "mihomo".into(),
            upload: 10,
            download: 20,
            total: 1 << 40,
            expire: 1_800_000_000,
            web_page_url: "https://sub.example".into(),
            interval_user_set: true,
            ..Default::default()
        }
    }

    fn local_profile(id: i64, order: i64, name: &str) -> Profile {
        Profile {
            id,
            user_order: order,
            name: name.into(),
            path: format!("/data/user/0/io.fkbox.app/files/configs/{name}.json"),
            ..Default::default()
        }
    }

    const UI_PREFS: &str = r#"{"theme":"dark","locale":"zh","proxyUnfolded":{"local:5":["Proxy"]},"dashboardCards":["Status","Memory"]}"#;

    fn ui_prefs_xml(prefs: &str) -> String {
        let escaped = prefs.replace('&', "&amp;").replace('"', "&quot;").replace('<', "&lt;").replace('>', "&gt;");
        format!(
            "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n    <string name=\"value\">{escaped}</string>\n    <string name=\"backupOrigin\">3f2b1c9e-0000-4000-8000-000000000001</string>\n</map>\n"
        )
    }

    struct Fixture {
        dir: PathBuf,
        sources: Sources,
    }

    impl Drop for Fixture {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.dir);
        }
    }

    /// The storage an upgrading user has: profiles.db at Room `version`
    /// (two profiles, one deleted id; schema 3 adds a remote server),
    /// settings.db with one value of every kind, and flclash_ui.xml.
    fn fixture(name: &str, version: u32) -> Fixture {
        let dir = scratch(name);
        let databases = dir.join("databases");
        std::fs::create_dir_all(&databases).unwrap();
        let shared = dir.join("shared_prefs");
        std::fs::create_dir_all(&shared).unwrap();
        let sources = Sources {
            profiles_db: databases.join("profiles.db"),
            settings_db: databases.join("settings.db"),
            ui_preferences: shared.join("flclash_ui.xml"),
            scratch_dir: dir.join("cache"),
        };

        let conn = create_room_db(&sources.profiles_db, "ProfileDatabase", version);
        let parcel_version = if version >= 3 { 3 } else { 2 };
        let icon = version >= 2;
        for profile in [remote_profile(5, 1, "sub"), local_profile(2, 0, "local"), local_profile(9, 2, "gone")] {
            let typed = parcel::write_typed_profile(&profile, parcel_version);
            if icon {
                conn.execute(
                    "INSERT INTO profiles (id, userOrder, name, icon, typed) VALUES (?1, ?2, ?3, ?4, ?5)",
                    rusqlite::params![
                        profile.id,
                        profile.user_order,
                        profile.name,
                        if profile.id == 5 { Some("star") } else { None },
                        typed
                    ],
                )
                .unwrap();
            } else {
                conn.execute(
                    "INSERT INTO profiles (id, userOrder, name, typed) VALUES (?1, ?2, ?3, ?4)",
                    rusqlite::params![profile.id, profile.user_order, profile.name, typed],
                )
                .unwrap();
            }
        }
        // Deleted after creation: Room's counter stays at 9.
        conn.execute("DELETE FROM profiles WHERE id = 9", []).unwrap();
        if version >= 3 {
            conn.execute(
                "INSERT INTO remote_servers (id, userOrder, name, url, secret) VALUES (4, 0, 'nas', '192.168.1.2:9090', 's3cret')",
                [],
            )
            .unwrap();
        }
        // Leave the rows in the WAL (Room's normal state while the app ran).
        std::mem::forget(conn);

        let conn = create_room_db(&sources.settings_db, "preference.KeyValueDatabase", 1);
        for (key, value) in settings_fixture() {
            conn.execute(
                "INSERT INTO KeyValueEntity (`key`, valueType, value) VALUES (?1, ?2, ?3)",
                rusqlite::params![key, value.kind(), kv_bytes(&value)],
            )
            .unwrap();
        }
        drop(conn);

        std::fs::write(&sources.ui_preferences, ui_prefs_xml(UI_PREFS)).unwrap();
        Fixture { dir, sources }
    }

    fn settings_fixture() -> Vec<(&'static str, SettingValue)> {
        vec![
            ("selected_profile", SettingValue::Long(5)),
            ("service_mode", SettingValue::String("vpn".into())),
            ("per_app_proxy_enabled", SettingValue::Bool(true)),
            ("per_app_proxy_mode", SettingValue::Long(2)),
            ("per_app_proxy_list", SettingValue::StringSet(vec!["com.android.chrome".into(), "org.telegram.messenger".into()])),
            ("tailscale_ssh_quick_connect_peers", SettingValue::StringSet(vec!["笔记本".into(), "nas".into(), "emoji😀".into()])),
            ("routing_rules", SettingValue::String(r#"{"cores":{"sing-box":{"rules":[]}}}"#.into())),
            ("oom_memory_limit_mb", SettingValue::Long(80)),
            ("some_float", SettingValue::Float(0.25)),
        ]
    }

    fn file_bytes(path: &Path) -> Vec<(String, Vec<u8>)> {
        let mut out = Vec::new();
        for suffix in ["", "-wal", "-shm"] {
            let mut p = path.as_os_str().to_owned();
            p.push(suffix);
            if let Ok(bytes) = std::fs::read(PathBuf::from(&p)) {
                out.push((suffix.to_string(), bytes));
            }
        }
        out
    }

    fn check_imported(store: &Store, version: u32) {
        let profiles = store.profiles().unwrap();
        assert_eq!(profiles.iter().map(|p| (p.id, p.name.as_str())).collect::<Vec<_>>(), [(2, "local"), (5, "sub")]);
        let mut expected = remote_profile(5, 1, "sub");
        if version < 3 {
            // Parcel v2 had no subscription fields.
            expected.upload = 0;
            expected.download = 0;
            expected.total = 0;
            expected.expire = 0;
            expected.web_page_url.clear();
        }
        expected.icon = (version >= 2).then(|| "star".to_string());
        assert_eq!(profiles[1], expected);
        assert_eq!(profiles[0], local_profile(2, 0, "local"));

        // A new profile never takes a deleted id.
        assert_eq!(store.insert_profile(&local_profile(0, 3, "new")).unwrap(), 10);

        let servers = store.remote_servers().unwrap();
        if version >= 3 {
            assert_eq!(
                servers,
                [RemoteServer { id: 4, user_order: 0, name: "nas".into(), url: "192.168.1.2:9090".into(), secret: "s3cret".into() }]
            );
        } else {
            assert!(servers.is_empty());
        }

        for (key, value) in settings_fixture() {
            assert_eq!(store.setting(key).unwrap(), Some(value), "{key}");
        }
        // Read back as before: retired locale dropped, the rest validated.
        assert_eq!(
            Value::Object(store.ui_preferences().unwrap()),
            json!({"theme": "dark", "proxyUnfolded": {"local:5": ["Proxy"]}, "dashboardCards": ["Status", "Memory"]})
        );
        assert_eq!(store.backup_origin(|| unreachable!()).unwrap(), "3f2b1c9e-0000-4000-8000-000000000001");
    }

    #[test]
    fn imports_every_room_schema_version() {
        for version in 1..=3 {
            let fixture = fixture(&format!("v{version}"), version);
            let before_profiles = file_bytes(&fixture.sources.profiles_db);
            let before_settings = file_bytes(&fixture.sources.settings_db);
            let store = Store::open_in_memory().unwrap();
            let Outcome::Done(summary) = migrate(&store, &fixture.sources).unwrap() else { panic!("not imported") };
            assert_eq!(summary.status, "imported");
            assert_eq!(summary.profiles, 2);
            assert_eq!(summary.remote_servers, usize::from(version >= 3));
            assert_eq!(summary.settings, settings_fixture().len());
            assert!(summary.preferences && summary.backup_origin);
            assert!(summary.skipped.is_empty(), "{:?}", summary.skipped);
            assert_eq!(summary.profiles_schema, i64::from(version));
            check_imported(&store, version);
            // The old files are untouched and the scratch copies are gone.
            assert_eq!(file_bytes(&fixture.sources.profiles_db), before_profiles);
            assert_eq!(file_bytes(&fixture.sources.settings_db), before_settings);
            assert!(std::fs::read_dir(&fixture.sources.scratch_dir).map(|d| d.count()).unwrap_or(0) == 0);
        }
    }

    #[test]
    fn running_again_changes_nothing() {
        let fixture = fixture("again", 3);
        let store = Store::open_in_memory().unwrap();
        migrate(&store, &fixture.sources).unwrap();
        store.put_setting("selected_profile", &SettingValue::Long(2)).unwrap();
        let outcome = migrate(&store, &fixture.sources).unwrap();
        assert!(matches!(outcome, Outcome::AlreadyDone(ref s) if s.contains("\"imported\"")));
        assert_eq!(store.setting("selected_profile").unwrap(), Some(SettingValue::Long(2)));
        assert_eq!(store.profiles().unwrap().len(), 2);
    }

    #[test]
    fn survives_reopening_the_store_file() {
        let fixture = fixture("reopen", 3);
        let path = fixture.dir.join("files/fkbox/client.db");
        {
            let store = Store::open(&path).unwrap();
            migrate(&store, &fixture.sources).unwrap();
        }
        let store = Store::open(&path).unwrap();
        assert!(matches!(migrate(&store, &fixture.sources).unwrap(), Outcome::AlreadyDone(_)));
        check_imported(&store, 3);
    }

    #[test]
    fn fresh_install_records_none() {
        let dir = scratch("fresh");
        let sources = Sources {
            profiles_db: dir.join("databases/profiles.db"),
            settings_db: dir.join("databases/settings.db"),
            ui_preferences: dir.join("shared_prefs/flclash_ui.xml"),
            scratch_dir: dir.join("cache"),
        };
        let store = Store::open_in_memory().unwrap();
        let Outcome::Done(summary) = migrate(&store, &sources).unwrap() else { panic!() };
        assert_eq!(summary.status, "none");
        assert!(store.is_empty().unwrap());
        assert!(matches!(migrate(&store, &sources).unwrap(), Outcome::AlreadyDone(_)));
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn a_store_with_data_is_not_overwritten() {
        let fixture = fixture("nonempty", 3);
        let store = Store::open_in_memory().unwrap();
        store.insert_profile(&local_profile(0, 0, "mine")).unwrap();
        let Outcome::Done(summary) = migrate(&store, &fixture.sources).unwrap() else { panic!() };
        assert_eq!(summary.status, "skipped");
        assert_eq!(store.profiles().unwrap().len(), 1);
    }

    #[test]
    fn a_failed_import_leaves_the_store_empty_and_retries() {
        let fixture = fixture("corrupt", 3);
        // A file that is not a database.
        std::fs::write(&fixture.sources.settings_db, b"definitely not sqlite, but long enough to look like a header....").unwrap();
        for suffix in ["-wal", "-shm"] {
            let mut p = fixture.sources.settings_db.as_os_str().to_owned();
            p.push(suffix);
            let _ = std::fs::remove_file(PathBuf::from(p));
        }
        let store = Store::open_in_memory().unwrap();
        assert!(migrate(&store, &fixture.sources).is_err());
        assert!(store.is_empty().unwrap());
        assert_eq!(store.meta(MIGRATION_KEY).unwrap(), None);
        // Once the file is gone the next start imports the rest.
        std::fs::remove_file(&fixture.sources.settings_db).unwrap();
        let Outcome::Done(summary) = migrate(&store, &fixture.sources).unwrap() else { panic!() };
        assert_eq!((summary.status.as_str(), summary.profiles, summary.settings), ("imported", 2, 0));
    }

    #[test]
    fn an_unreadable_profile_row_is_skipped() {
        let fixture = fixture("badrow", 3);
        let conn = Connection::open(&fixture.sources.profiles_db).unwrap();
        conn.execute("INSERT INTO profiles (id, userOrder, name, icon, typed) VALUES (11, 5, 'broken', NULL, x'0300')", []).unwrap();
        drop(conn);
        let store = Store::open_in_memory().unwrap();
        let Outcome::Done(summary) = migrate(&store, &fixture.sources).unwrap() else { panic!() };
        assert_eq!(summary.profiles, 2);
        assert_eq!(summary.skipped.len(), 1);
        assert!(summary.skipped[0].starts_with("profile 11:"));
    }

    #[test]
    fn shared_preferences_backup_file_wins() {
        let fixture = fixture("bak", 3);
        let mut bak = fixture.sources.ui_preferences.as_os_str().to_owned();
        bak.push(".bak");
        std::fs::write(PathBuf::from(bak), ui_prefs_xml(r#"{"theme":"light"}"#)).unwrap();
        let store = Store::open_in_memory().unwrap();
        migrate(&store, &fixture.sources).unwrap();
        assert_eq!(Value::Object(store.ui_preferences().unwrap()), json!({"theme": "light"}));
    }

    #[test]
    fn xml_strings_and_entities() {
        let xml = "<map><string name=\"a\">x &amp; &lt;y&gt; &quot;z&quot; &#39;&#x4e2d;</string><string name='b' /><boolean name=\"c\" value=\"true\" /><string name=\"d\"></string></map>";
        assert_eq!(
            shared_preference_strings(xml),
            [("a".to_string(), "x & <y> \"z\" '中".to_string()), ("b".to_string(), String::new()), ("d".to_string(), String::new())]
        );
    }

    #[test]
    fn key_values_decode_like_the_kotlin_store() {
        assert_eq!(decode_key_value(1, &[1]).unwrap(), SettingValue::Bool(true));
        assert_eq!(decode_key_value(3, &(-1i64).to_be_bytes()).unwrap(), SettingValue::Long(-1));
        assert_eq!(decode_key_value(2, &1.5f32.to_be_bytes()).unwrap(), SettingValue::Float(1.5));
        assert_eq!(decode_key_value(5, &[]).unwrap(), SettingValue::StringSet(vec![]));
        assert!(decode_key_value(3, &[0; 4]).is_err());
        assert!(decode_key_value(0, &[]).is_err());
        assert!(decode_key_value(5, &[0, 0, 0, 9, b'a']).is_err());
    }
}
