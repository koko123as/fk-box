//! The client store: one SQLite file holding the service settings, the
//! profile list (metadata only; each profile's configuration stays in its own
//! file at [`Profile::path`]), the remote control servers and the interface
//! preferences.
//!
//! One [`Store`] per process: it owns a single connection behind a mutex, so
//! the UI and the platform host (on Android the Kotlin service layer, through
//! [`crate::api`]) share it instead of opening the file twice. The location
//! comes from the platform shell ([`Store::open`]); nothing here knows a
//! platform path.
//!
//! Schema versions are kept in `PRAGMA user_version` ([`SCHEMA_VERSION`]);
//! [`Store::open`] creates or upgrades the schema in one transaction.

use std::path::Path;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Mutex, MutexGuard};

use rusqlite::{params, Connection, OptionalExtension, Row};
use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};

use crate::subscription::{Fetch, Metadata};

/// The schema this build writes. 1: settings, profiles, remote_servers,
/// preferences and meta (phase 2a).
pub const SCHEMA_VERSION: i64 = 1;

/// The core an `auto` profile resolves to when its content has not been
/// resolved yet, as the Kotlin host defaulted (`Profile.resolvedCore`).
pub const CORE_AUTO: &str = "auto";
pub const CORE_SING_BOX: &str = "sing-box";
pub const CORES: [&str; 4] = [CORE_AUTO, CORE_SING_BOX, "xray", "mihomo"];

/// The update interval (minutes) a profile starts with (TypedProfile.DEFAULT_INTERVAL).
pub const DEFAULT_INTERVAL: i32 = 60;

#[derive(Debug)]
pub struct Error(pub String);

impl std::fmt::Display for Error {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for Error {}

impl From<rusqlite::Error> for Error {
    fn from(error: rusqlite::Error) -> Self {
        Error(format!("store: {error}"))
    }
}

impl From<serde_json::Error> for Error {
    fn from(error: serde_json::Error) -> Self {
        Error(format!("store: {error}"))
    }
}

pub type Result<T> = std::result::Result<T, Error>;

/// A typed setting value. The kinds and their codes are the ones the Android
/// app's settings store used (KeyValueEntity.TYPE_*), so a setting keeps its
/// type through the import; integers are kept as [`SettingValue::Long`] like
/// there (`putInt` stored a long).
#[derive(Debug, Clone, PartialEq)]
pub enum SettingValue {
    Bool(bool),
    Float(f32),
    Long(i64),
    String(String),
    StringSet(Vec<String>),
}

impl SettingValue {
    pub const BOOLEAN: i64 = 1;
    pub const FLOAT: i64 = 2;
    pub const LONG: i64 = 3;
    pub const STRING: i64 = 4;
    pub const STRING_SET: i64 = 5;

    pub fn kind(&self) -> i64 {
        match self {
            SettingValue::Bool(_) => Self::BOOLEAN,
            SettingValue::Float(_) => Self::FLOAT,
            SettingValue::Long(_) => Self::LONG,
            SettingValue::String(_) => Self::STRING,
            SettingValue::StringSet(_) => Self::STRING_SET,
        }
    }

    pub fn to_json(&self) -> Value {
        match self {
            SettingValue::Bool(v) => Value::Bool(*v),
            SettingValue::Float(v) => serde_json::Number::from_f64(f64::from(*v)).map(Value::Number).unwrap_or(Value::Null),
            SettingValue::Long(v) => Value::from(*v),
            SettingValue::String(v) => Value::String(v.clone()),
            SettingValue::StringSet(v) => Value::Array(v.iter().cloned().map(Value::String).collect()),
        }
    }

    pub fn from_json(kind: i64, value: &Value) -> Result<Self> {
        let invalid = || Error(format!("invalid setting value of kind {kind}: {value}"));
        Ok(match kind {
            Self::BOOLEAN => SettingValue::Bool(value.as_bool().ok_or_else(invalid)?),
            Self::FLOAT => SettingValue::Float(value.as_f64().ok_or_else(invalid)? as f32),
            Self::LONG => SettingValue::Long(value.as_i64().or_else(|| value.as_f64().map(|v| v as i64)).ok_or_else(invalid)?),
            Self::STRING => SettingValue::String(value.as_str().ok_or_else(invalid)?.to_string()),
            Self::STRING_SET => {
                let items = value.as_array().ok_or_else(invalid)?;
                let mut set = Vec::with_capacity(items.len());
                for item in items {
                    let item = item.as_str().ok_or_else(invalid)?.to_string();
                    if !set.contains(&item) {
                        set.push(item);
                    }
                }
                SettingValue::StringSet(set)
            }
            _ => return Err(invalid()),
        })
    }
}

/// One profile's metadata: the Android app's `profiles` row with its
/// `TypedProfile` fields as columns. The JSON form (camelCase) is the one the
/// platform host exchanges through [`crate::api`].
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct Profile {
    pub id: i64,
    pub user_order: i64,
    pub name: String,
    pub icon: Option<String>,
    /// The configuration file.
    pub path: String,
    /// 0 local, 1 remote (TypedProfile.Type ordinals).
    #[serde(rename = "type")]
    pub kind: i64,
    #[serde(rename = "remoteURL")]
    pub remote_url: String,
    /// Unix milliseconds.
    pub last_updated: i64,
    pub auto_update: bool,
    /// Minutes.
    pub auto_update_interval: i32,
    /// "auto", "sing-box", "xray" or "mihomo".
    pub core: String,
    /// What the provider's subscription-userinfo reported at the last
    /// successful fetch (bytes, Unix seconds; 0 = not reported / no expiry).
    pub upload: i64,
    pub download: i64,
    pub total: i64,
    pub expire: i64,
    pub web_page_url: String,
    /// The user chose the interval; provider hints no longer apply.
    pub interval_user_set: bool,
    /// The core the content resolves to, as the platform host last resolved
    /// it (the cores' own detection lives in libbox), and the file stamp it
    /// was resolved for. Empty until resolved.
    pub resolved_core: String,
    pub resolved_stamp: String,
}

impl Default for Profile {
    fn default() -> Self {
        Profile {
            id: 0,
            user_order: 0,
            name: String::new(),
            icon: None,
            path: String::new(),
            kind: 0,
            remote_url: String::new(),
            last_updated: 0,
            auto_update: false,
            auto_update_interval: DEFAULT_INTERVAL,
            core: CORE_AUTO.into(),
            upload: 0,
            download: 0,
            total: 0,
            expire: 0,
            web_page_url: String::new(),
            interval_user_set: false,
            resolved_core: String::new(),
            resolved_stamp: String::new(),
        }
    }
}

impl Profile {
    pub fn remote(&self) -> bool {
        self.kind == 1
    }

    /// The core shown for the profile: the resolved one, otherwise the
    /// declared one, and sing-box for an unresolved `auto` profile (the
    /// Kotlin host's fallback when the content could not be read).
    pub fn display_core(&self) -> &str {
        if !self.resolved_core.is_empty() {
            &self.resolved_core
        } else if self.core != CORE_AUTO && CORES.contains(&self.core.as_str()) {
            &self.core
        } else {
            CORE_SING_BOX
        }
    }

    /// The UI's profile row (BoxProfiles `Profile.toWire` with
    /// `ProfileWireFields.export`): traffic and expiry are left out when the
    /// provider reported none, the website when there is none.
    pub fn to_wire(&self) -> Value {
        let mut row = Map::new();
        row.insert("id".into(), self.id.into());
        row.insert("name".into(), self.name.clone().into());
        row.insert("remote".into(), self.remote().into());
        row.insert("url".into(), self.remote_url.clone().into());
        row.insert("updatedAt".into(), self.last_updated.into());
        row.insert("autoUpdate".into(), self.auto_update.into());
        row.insert("interval".into(), self.auto_update_interval.into());
        row.insert("core".into(), self.core.clone().into());
        let reported = self.total > 0 || self.expire > 0 || self.upload > 0 || self.download > 0;
        if reported {
            row.insert("upload".into(), self.upload.into());
            row.insert("download".into(), self.download.into());
            row.insert("total".into(), self.total.into());
            row.insert("expire".into(), self.expire.into());
        }
        if !self.web_page_url.is_empty() {
            row.insert("webPageUrl".into(), self.web_page_url.clone().into());
        }
        row.insert("intervalUserSet".into(), self.interval_user_set.into());
        row.insert("resolvedCore".into(), self.display_core().into());
        Value::Object(row)
    }
}

/// A remote control server (the Android app's `remote_servers` row).
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct RemoteServer {
    pub id: i64,
    pub user_order: i64,
    pub name: String,
    pub url: String,
    pub secret: String,
}

/// The preferences-table key of the interface preferences (one JSON object).
pub const PREF_UI: &str = "ui";
/// The preferences-table key of the backup identity (BoxBackups.origin).
pub const PREF_BACKUP_ORIGIN: &str = "backupOrigin";

pub struct Store {
    conn: Mutex<Connection>,
    in_transaction: AtomicBool,
    /// Content saves waiting for the platform to apply them to the running
    /// service, kept so a failed application can be rolled back
    /// ([`crate::content`]).
    pub(crate) pending: crate::content::Pending,
}

/// The columns a save of a profile's content writes: when it was last
/// updated and the subscription metadata. Nothing else (order, name, URL,
/// core, auto-update switch) is ever written by a content save or an
/// automatic update, so a reorder or an edit made meanwhile is kept.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct ContentState {
    pub last_updated: i64,
    pub metadata: Metadata,
}

const PROFILE_COLUMNS: &str = "id, user_order, name, icon, path, kind, remote_url, last_updated, auto_update, auto_update_interval, core, \
     upload, download, total, expire, web_page_url, interval_user_set, resolved_core, resolved_stamp";

impl Store {
    /// Opens (creating when absent) the store at `path` and brings its schema
    /// to [`SCHEMA_VERSION`]. The parent directory is created.
    pub fn open(path: &Path) -> Result<Store> {
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent).map_err(|e| Error(format!("store: create {}: {e}", parent.display())))?;
        }
        let conn = Connection::open(path)?;
        // WAL: the UI thread reads while the service layer writes, and a
        // crash mid-write leaves the last committed state.
        conn.pragma_update(None, "journal_mode", "WAL")?;
        conn.pragma_update(None, "synchronous", "NORMAL")?;
        conn.busy_timeout(std::time::Duration::from_secs(5))?;
        Self::with_connection(conn)
    }

    /// A store that lives in memory only (tests, the screenshot harness).
    pub fn open_in_memory() -> Result<Store> {
        Self::with_connection(Connection::open_in_memory()?)
    }

    fn with_connection(mut conn: Connection) -> Result<Store> {
        conn.pragma_update(None, "foreign_keys", "ON")?;
        migrate_schema(&mut conn)?;
        Ok(Store { conn: Mutex::new(conn), in_transaction: AtomicBool::new(false), pending: Default::default() })
    }

    fn conn(&self) -> MutexGuard<'_, Connection> {
        self.conn.lock().unwrap_or_else(|e| e.into_inner())
    }

    pub fn schema_version(&self) -> Result<i64> {
        Ok(self.conn().pragma_query_value(None, "user_version", |row| row.get(0))?)
    }

    // -- transactions ------------------------------------------------------

    /// Starts a transaction that spans several calls (a backup restore puts
    /// many profiles back as one change). Only one at a time.
    pub fn begin(&self) -> Result<()> {
        if self.in_transaction.swap(true, Ordering::SeqCst) {
            return Err(Error("store: a transaction is already open".into()));
        }
        if let Err(error) = self.conn().execute_batch("BEGIN IMMEDIATE") {
            self.in_transaction.store(false, Ordering::SeqCst);
            return Err(error.into());
        }
        Ok(())
    }

    pub fn commit(&self) -> Result<()> {
        self.end("COMMIT")
    }

    pub fn rollback(&self) -> Result<()> {
        self.end("ROLLBACK")
    }

    fn end(&self, statement: &str) -> Result<()> {
        if !self.in_transaction.load(Ordering::SeqCst) {
            return Err(Error("store: no transaction is open".into()));
        }
        let result = self.conn().execute_batch(statement);
        if result.is_ok() || statement == "ROLLBACK" {
            self.in_transaction.store(false, Ordering::SeqCst);
        }
        Ok(result?)
    }

    /// True while a [`Store::begin`] transaction is open; writes from
    /// elsewhere (the UI) wait for it to end instead of joining it.
    pub fn in_transaction(&self) -> bool {
        self.in_transaction.load(Ordering::SeqCst)
    }

    // -- meta ----------------------------------------------------------------

    pub fn meta(&self, key: &str) -> Result<Option<String>> {
        Ok(self.conn().query_row("SELECT value FROM meta WHERE key = ?1", [key], |r| r.get(0)).optional()?)
    }

    pub fn set_meta(&self, key: &str, value: &str) -> Result<()> {
        self.conn().execute("INSERT OR REPLACE INTO meta (key, value) VALUES (?1, ?2)", params![key, value])?;
        Ok(())
    }

    /// True when the store holds none of the user's data yet (only `meta`).
    pub fn is_empty(&self) -> Result<bool> {
        let conn = self.conn();
        let count: i64 = conn.query_row(
            "SELECT (SELECT COUNT(*) FROM settings) + (SELECT COUNT(*) FROM profiles) + \
             (SELECT COUNT(*) FROM remote_servers) + (SELECT COUNT(*) FROM preferences)",
            [],
            |r| r.get(0),
        )?;
        Ok(count == 0)
    }

    /// Runs `f` on the connection inside one transaction (the legacy import).
    pub(crate) fn transaction<T>(&self, f: impl FnOnce(&rusqlite::Transaction<'_>) -> Result<T>) -> Result<T> {
        if self.in_transaction() {
            return Err(Error("store: a transaction is already open".into()));
        }
        let mut conn = self.conn();
        let tx = conn.transaction()?;
        let value = f(&tx)?;
        tx.commit()?;
        Ok(value)
    }

    // -- settings ------------------------------------------------------------

    pub fn settings(&self) -> Result<Vec<(String, SettingValue)>> {
        let conn = self.conn();
        let mut statement = conn.prepare("SELECT key, kind, value FROM settings ORDER BY key")?;
        let rows = statement.query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, i64>(1)?, r.get::<_, String>(2)?)))?;
        let mut out = Vec::new();
        for row in rows {
            let (key, kind, text) = row?;
            let value = SettingValue::from_json(kind, &serde_json::from_str(&text)?)?;
            out.push((key, value));
        }
        Ok(out)
    }

    pub fn setting(&self, key: &str) -> Result<Option<SettingValue>> {
        let row: Option<(i64, String)> =
            self.conn().query_row("SELECT kind, value FROM settings WHERE key = ?1", [key], |r| Ok((r.get(0)?, r.get(1)?))).optional()?;
        match row {
            Some((kind, text)) => Ok(Some(SettingValue::from_json(kind, &serde_json::from_str(&text)?)?)),
            None => Ok(None),
        }
    }

    pub fn put_setting(&self, key: &str, value: &SettingValue) -> Result<()> {
        put_setting(&self.conn(), key, value)
    }

    /// Writes several settings as one change. Inside a [`Store::begin`]
    /// transaction (a backup restore) they join it; otherwise they get their
    /// own, so either all are written or none.
    pub fn put_settings(&self, entries: &[(String, SettingValue)]) -> Result<()> {
        let mut conn = self.conn();
        if self.in_transaction() {
            for (key, value) in entries {
                put_setting(&conn, key, value)?;
            }
            return Ok(());
        }
        let tx = conn.transaction()?;
        for (key, value) in entries {
            put_setting(&tx, key, value)?;
        }
        tx.commit()?;
        Ok(())
    }

    pub fn remove_setting(&self, key: &str) -> Result<()> {
        self.conn().execute("DELETE FROM settings WHERE key = ?1", [key])?;
        Ok(())
    }

    // -- profiles ------------------------------------------------------------

    /// Every profile in the user's order.
    pub fn profiles(&self) -> Result<Vec<Profile>> {
        let conn = self.conn();
        let mut statement = conn.prepare(&format!("SELECT {PROFILE_COLUMNS} FROM profiles ORDER BY user_order ASC, id ASC"))?;
        let rows = statement.query_map([], profile_from_row)?;
        Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
    }

    pub fn profile(&self, id: i64) -> Result<Option<Profile>> {
        let conn = self.conn();
        Ok(conn.query_row(&format!("SELECT {PROFILE_COLUMNS} FROM profiles WHERE id = ?1"), [id], profile_from_row).optional()?)
    }

    /// Inserts `profile` and returns its id: a new one when `profile.id` is 0,
    /// otherwise that id (a restore or rollback putting a profile back).
    pub fn insert_profile(&self, profile: &Profile) -> Result<i64> {
        insert_profile(&self.conn(), profile)
    }

    /// Writes every field of `profile` except its order, which only changes
    /// when `write_order` is set (a reorder or a restore), so an update from
    /// a copy read before a reorder does not undo it. Returns the rows changed.
    pub fn update_profile(&self, profile: &Profile, write_order: bool) -> Result<usize> {
        validate_profile(profile)?;
        let conn = self.conn();
        let changed = conn.execute(
            "UPDATE profiles SET name = ?2, icon = ?3, path = ?4, kind = ?5, remote_url = ?6, last_updated = ?7, \
             auto_update = ?8, auto_update_interval = ?9, core = ?10, upload = ?11, download = ?12, total = ?13, \
             expire = ?14, web_page_url = ?15, interval_user_set = ?16, resolved_core = ?17, resolved_stamp = ?18, \
             user_order = CASE WHEN ?19 THEN ?20 ELSE user_order END WHERE id = ?1",
            params![
                profile.id,
                profile.name,
                profile.icon,
                profile.path,
                profile.kind,
                profile.remote_url,
                profile.last_updated,
                profile.auto_update,
                profile.auto_update_interval,
                profile.core,
                profile.upload,
                profile.download,
                profile.total,
                profile.expire,
                profile.web_page_url,
                profile.interval_user_set,
                profile.resolved_core,
                profile.resolved_stamp,
                write_order,
                profile.user_order,
            ],
        )?;
        Ok(changed)
    }

    pub fn delete_profiles(&self, ids: &[i64]) -> Result<usize> {
        let conn = self.conn();
        let mut changed = 0;
        for id in ids {
            changed += conn.execute("DELETE FROM profiles WHERE id = ?1", [id])?;
        }
        Ok(changed)
    }

    /// The order a new profile takes: after the last one (0 for the first).
    pub fn next_profile_order(&self) -> Result<i64> {
        Ok(self.conn().query_row("SELECT COALESCE(MAX(user_order) + 1, 0) FROM profiles", [], |r| r.get(0))?)
    }

    /// Puts the profiles in the order of `ids`, which must name every profile
    /// exactly once (the list may have changed since the caller read it).
    pub fn reorder_profiles(&self, ids: &[i64]) -> Result<()> {
        let mut conn = self.conn();
        let tx = conn.transaction()?;
        let mut current: Vec<i64> = {
            let mut statement = tx.prepare("SELECT id FROM profiles")?;
            let rows = statement.query_map([], |r| r.get(0))?;
            rows.collect::<rusqlite::Result<Vec<i64>>>()?
        };
        let mut wanted = ids.to_vec();
        current.sort_unstable();
        wanted.sort_unstable();
        wanted.dedup();
        if wanted.len() != ids.len() || wanted != current {
            return Err(Error("Profile list changed; refresh and retry".into()));
        }
        for (index, id) in ids.iter().enumerate() {
            tx.execute("UPDATE profiles SET user_order = ?2 WHERE id = ?1", params![id, index as i64])?;
        }
        tx.commit()?;
        Ok(())
    }

    /// The [`ContentState`] of a profile, None when it does not exist.
    pub fn content_state(&self, id: i64) -> Result<Option<ContentState>> {
        Ok(content_state(&self.conn(), id)?)
    }

    /// Records a save of a profile's content in its row: `last_updated` when
    /// given, and the subscription metadata of `fetch` when given, its update
    /// interval decided against the row's own `interval_user_set` as read in
    /// the same locked step. Only the [`ContentState`] columns are written.
    /// Returns the state before and after, or None when the profile no
    /// longer exists.
    pub fn record_content_save(
        &self,
        id: i64,
        last_updated: Option<i64>,
        fetch: Option<&Fetch>,
    ) -> Result<Option<(ContentState, ContentState)>> {
        let conn = self.conn();
        let Some(before) = content_state(&conn, id)? else { return Ok(None) };
        let user_set: bool = conn.query_row("SELECT interval_user_set FROM profiles WHERE id = ?1", [id], |r| r.get(0))?;
        let after = ContentState {
            last_updated: last_updated.unwrap_or(before.last_updated),
            metadata: match fetch {
                Some(fetch) => Metadata::after(before.metadata.auto_update_interval, user_set, fetch),
                None => before.metadata.clone(),
            },
        };
        if after != before {
            write_content_state(&conn, id, &after)?;
        }
        Ok(Some((before, after)))
    }

    /// Puts a [`ContentState`] back (a failed save rolled back). False when
    /// the profile no longer exists.
    pub fn restore_content_state(&self, id: i64, state: &ContentState) -> Result<bool> {
        Ok(write_content_state(&self.conn(), id, state)? == 1)
    }

    /// Records the core a profile's content resolved to for the file `stamp`.
    pub fn set_resolved_core(&self, id: i64, core: &str, stamp: &str) -> Result<()> {
        self.conn().execute("UPDATE profiles SET resolved_core = ?2, resolved_stamp = ?3 WHERE id = ?1", params![id, core, stamp])?;
        Ok(())
    }

    // -- remote servers ------------------------------------------------------

    pub fn remote_servers(&self) -> Result<Vec<RemoteServer>> {
        let conn = self.conn();
        let mut statement = conn.prepare("SELECT id, user_order, name, url, secret FROM remote_servers ORDER BY user_order ASC, id ASC")?;
        let rows = statement.query_map([], server_from_row)?;
        Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
    }

    pub fn remote_server(&self, id: i64) -> Result<Option<RemoteServer>> {
        Ok(self
            .conn()
            .query_row("SELECT id, user_order, name, url, secret FROM remote_servers WHERE id = ?1", [id], server_from_row)
            .optional()?)
    }

    pub fn insert_remote_server(&self, server: &RemoteServer) -> Result<i64> {
        insert_remote_server(&self.conn(), server)
    }

    pub fn update_remote_server(&self, server: &RemoteServer) -> Result<usize> {
        Ok(self.conn().execute(
            "UPDATE remote_servers SET user_order = ?2, name = ?3, url = ?4, secret = ?5 WHERE id = ?1",
            params![server.id, server.user_order, server.name, server.url, server.secret],
        )?)
    }

    pub fn delete_remote_server(&self, id: i64) -> Result<usize> {
        Ok(self.conn().execute("DELETE FROM remote_servers WHERE id = ?1", [id])?)
    }

    pub fn next_remote_server_order(&self) -> Result<i64> {
        Ok(self.conn().query_row("SELECT COALESCE(MAX(user_order) + 1, 0) FROM remote_servers", [], |r| r.get(0))?)
    }

    // -- preferences ---------------------------------------------------------

    /// A raw preferences value (`None` when unset).
    pub fn preference(&self, key: &str) -> Result<Option<String>> {
        Ok(self.conn().query_row("SELECT value FROM preferences WHERE key = ?1", [key], |r| r.get(0)).optional()?)
    }

    pub fn set_preference(&self, key: &str, value: &str) -> Result<()> {
        self.conn().execute("INSERT OR REPLACE INTO preferences (key, value) VALUES (?1, ?2)", params![key, value])?;
        Ok(())
    }

    /// The interface preferences as stored (unvalidated; an unreadable value
    /// reads as empty).
    pub fn ui_preferences_raw(&self) -> Result<Map<String, Value>> {
        Ok(self
            .preference(PREF_UI)?
            .and_then(|text| serde_json::from_str::<Value>(&text).ok())
            .and_then(|v| v.as_object().cloned())
            .unwrap_or_default())
    }

    /// The interface preferences the UI reads: the stored object checked by
    /// [`crate::preferences::validate`]; any invalid entry makes it read as
    /// empty, as the Kotlin host read it (`BoxBridge.readPreferences`).
    pub fn ui_preferences(&self) -> Result<Map<String, Value>> {
        Ok(crate::preferences::validate(&self.ui_preferences_raw()?).unwrap_or_default())
    }

    /// Replaces the interface preferences with `values` after validating them.
    pub fn replace_ui_preferences(&self, values: &Map<String, Value>) -> Result<Map<String, Value>> {
        let valid = crate::preferences::validate(values).map_err(Error)?;
        self.set_preference(PREF_UI, &Value::Object(valid.clone()).to_string())?;
        Ok(valid)
    }

    /// Merges `patch` into the stored interface preferences (BoxBridge
    /// `setPreferences`: the patch is validated, the retired language key is
    /// dropped from what was stored) and returns what the UI reads after.
    pub fn update_ui_preferences(&self, patch: &Map<String, Value>) -> Result<Map<String, Value>> {
        let patch = crate::preferences::validate(patch).map_err(Error)?;
        let mut stored = self.ui_preferences_raw()?;
        stored.remove("locale");
        for (key, value) in patch {
            stored.insert(key, value);
        }
        self.set_preference(PREF_UI, &Value::Object(stored).to_string())?;
        self.ui_preferences()
    }

    /// The backup identity, created with `generate` the first time.
    pub fn backup_origin(&self, generate: impl FnOnce() -> String) -> Result<String> {
        if let Some(origin) = self.preference(PREF_BACKUP_ORIGIN)?.filter(|s| !s.is_empty()) {
            return Ok(origin);
        }
        let origin = generate();
        self.set_preference(PREF_BACKUP_ORIGIN, &origin)?;
        Ok(origin)
    }
}

fn migrate_schema(conn: &mut Connection) -> Result<()> {
    let version: i64 = conn.pragma_query_value(None, "user_version", |row| row.get(0))?;
    if version > SCHEMA_VERSION {
        return Err(Error(format!("store: schema {version} is newer than this build ({SCHEMA_VERSION})")));
    }
    if version == SCHEMA_VERSION {
        return Ok(());
    }
    let tx = conn.transaction()?;
    if version < 1 {
        tx.execute_batch(
            "CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL);
             CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY NOT NULL, kind INTEGER NOT NULL, value TEXT NOT NULL);
             CREATE TABLE IF NOT EXISTS profiles (
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
             CREATE TABLE IF NOT EXISTS remote_servers (
                 id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                 user_order INTEGER NOT NULL DEFAULT 0,
                 name TEXT NOT NULL DEFAULT '',
                 url TEXT NOT NULL DEFAULT '',
                 secret TEXT NOT NULL DEFAULT '');
             CREATE TABLE IF NOT EXISTS preferences (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL);",
        )?;
    }
    tx.pragma_update(None, "user_version", SCHEMA_VERSION)?;
    tx.commit()?;
    Ok(())
}

fn content_state(conn: &Connection, id: i64) -> rusqlite::Result<Option<ContentState>> {
    conn.query_row(
        "SELECT last_updated, upload, download, total, expire, web_page_url, auto_update_interval FROM profiles WHERE id = ?1",
        [id],
        |r| {
            Ok(ContentState {
                last_updated: r.get(0)?,
                metadata: Metadata {
                    upload: r.get(1)?,
                    download: r.get(2)?,
                    total: r.get(3)?,
                    expire: r.get(4)?,
                    web_page_url: r.get(5)?,
                    auto_update_interval: r.get(6)?,
                },
            })
        },
    )
    .optional()
}

fn write_content_state(conn: &Connection, id: i64, state: &ContentState) -> rusqlite::Result<usize> {
    let m = &state.metadata;
    conn.execute(
        "UPDATE profiles SET last_updated = ?2, upload = ?3, download = ?4, total = ?5, expire = ?6, web_page_url = ?7, \
         auto_update_interval = ?8 WHERE id = ?1",
        params![id, state.last_updated, m.upload, m.download, m.total, m.expire, m.web_page_url, m.auto_update_interval],
    )
}

fn validate_profile(profile: &Profile) -> Result<()> {
    if !CORES.contains(&profile.core.as_str()) {
        return Err(Error(format!("Unknown core: {}", profile.core)));
    }
    if profile.kind != 0 && profile.kind != 1 {
        return Err(Error(format!("Invalid profile type: {}", profile.kind)));
    }
    Ok(())
}

pub(crate) fn put_setting(conn: &Connection, key: &str, value: &SettingValue) -> Result<()> {
    conn.execute(
        "INSERT OR REPLACE INTO settings (key, kind, value) VALUES (?1, ?2, ?3)",
        params![key, value.kind(), value.to_json().to_string()],
    )?;
    Ok(())
}

pub(crate) fn insert_profile(conn: &Connection, profile: &Profile) -> Result<i64> {
    validate_profile(profile)?;
    conn.execute(
        "INSERT INTO profiles (id, user_order, name, icon, path, kind, remote_url, last_updated, auto_update, \
         auto_update_interval, core, upload, download, total, expire, web_page_url, interval_user_set, resolved_core, resolved_stamp) \
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15, ?16, ?17, ?18, ?19)",
        params![
            if profile.id > 0 { Some(profile.id) } else { None },
            profile.user_order,
            profile.name,
            profile.icon,
            profile.path,
            profile.kind,
            profile.remote_url,
            profile.last_updated,
            profile.auto_update,
            profile.auto_update_interval,
            profile.core,
            profile.upload,
            profile.download,
            profile.total,
            profile.expire,
            profile.web_page_url,
            profile.interval_user_set,
            profile.resolved_core,
            profile.resolved_stamp,
        ],
    )?;
    Ok(conn.last_insert_rowid())
}

pub(crate) fn insert_remote_server(conn: &Connection, server: &RemoteServer) -> Result<i64> {
    conn.execute(
        "INSERT INTO remote_servers (id, user_order, name, url, secret) VALUES (?1, ?2, ?3, ?4, ?5)",
        params![if server.id > 0 { Some(server.id) } else { None }, server.user_order, server.name, server.url, server.secret],
    )?;
    Ok(conn.last_insert_rowid())
}

fn profile_from_row(r: &Row<'_>) -> rusqlite::Result<Profile> {
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
}

fn server_from_row(r: &Row<'_>) -> rusqlite::Result<RemoteServer> {
    Ok(RemoteServer { id: r.get(0)?, user_order: r.get(1)?, name: r.get(2)?, url: r.get(3)?, secret: r.get(4)? })
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn profile(name: &str) -> Profile {
        Profile { name: name.into(), path: format!("/data/configs/{name}.json"), ..Default::default() }
    }

    #[test]
    fn new_store_has_current_schema_and_is_empty() {
        let store = Store::open_in_memory().unwrap();
        assert_eq!(store.schema_version().unwrap(), SCHEMA_VERSION);
        assert!(store.is_empty().unwrap());
    }

    #[test]
    fn reopening_a_file_keeps_data_and_schema() {
        let dir = crate::legacy::tests::scratch("reopen");
        let path = dir.join("fkbox/client.db");
        {
            let store = Store::open(&path).unwrap();
            store.put_setting("selected_profile", &SettingValue::Long(7)).unwrap();
        }
        let store = Store::open(&path).unwrap();
        assert_eq!(store.schema_version().unwrap(), SCHEMA_VERSION);
        assert_eq!(store.setting("selected_profile").unwrap(), Some(SettingValue::Long(7)));
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn a_newer_schema_is_refused() {
        let dir = crate::legacy::tests::scratch("newer");
        let path = dir.join("client.db");
        {
            let conn = Connection::open(&path).unwrap();
            conn.pragma_update(None, "user_version", SCHEMA_VERSION + 1).unwrap();
        }
        assert!(Store::open(&path).is_err());
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn settings_keep_their_types() {
        let store = Store::open_in_memory().unwrap();
        let values = [
            ("b", SettingValue::Bool(true)),
            ("f", SettingValue::Float(1.5)),
            ("l", SettingValue::Long(-3)),
            ("s", SettingValue::String("ä ok".into())),
            ("set", SettingValue::StringSet(vec!["a".into(), "b".into()])),
        ];
        for (key, value) in &values {
            store.put_setting(key, value).unwrap();
        }
        let read: Vec<_> = store.settings().unwrap();
        assert_eq!(read.len(), values.len());
        for (key, value) in &values {
            assert_eq!(store.setting(key).unwrap().as_ref(), Some(value));
        }
        store.remove_setting("b").unwrap();
        assert_eq!(store.setting("b").unwrap(), None);
    }

    #[test]
    fn setting_json_is_checked_against_its_kind() {
        assert!(SettingValue::from_json(SettingValue::BOOLEAN, &json!("yes")).is_err());
        assert!(SettingValue::from_json(9, &json!(1)).is_err());
        assert_eq!(
            SettingValue::from_json(SettingValue::STRING_SET, &json!(["a", "a", "b"])).unwrap(),
            SettingValue::StringSet(vec!["a".into(), "b".into()])
        );
    }

    #[test]
    fn profiles_insert_update_order_and_delete() {
        let store = Store::open_in_memory().unwrap();
        let mut a = profile("a");
        a.user_order = store.next_profile_order().unwrap();
        a.id = store.insert_profile(&a).unwrap();
        let mut b = profile("b");
        b.user_order = store.next_profile_order().unwrap();
        b.id = store.insert_profile(&b).unwrap();
        assert_eq!((a.user_order, b.user_order), (0, 1));
        assert_eq!(store.profiles().unwrap().iter().map(|p| p.name.as_str()).collect::<Vec<_>>(), ["a", "b"]);

        store.reorder_profiles(&[b.id, a.id]).unwrap();
        assert_eq!(store.profiles().unwrap().iter().map(|p| p.name.as_str()).collect::<Vec<_>>(), ["b", "a"]);

        // An update from a copy read before the reorder keeps the new order.
        a.name = "a2".into();
        assert_eq!(store.update_profile(&a, false).unwrap(), 1);
        let stored = store.profile(a.id).unwrap().unwrap();
        assert_eq!((stored.name.as_str(), stored.user_order), ("a2", 1));
        // A restore writes its order.
        a.user_order = 5;
        store.update_profile(&a, true).unwrap();
        assert_eq!(store.profile(a.id).unwrap().unwrap().user_order, 5);

        assert!(store.reorder_profiles(&[a.id]).is_err());
        assert!(store.reorder_profiles(&[a.id, a.id]).is_err());

        assert_eq!(store.delete_profiles(&[a.id]).unwrap(), 1);
        assert_eq!(store.profiles().unwrap().len(), 1);
        // Ids are never reused (AUTOINCREMENT, as Room's table).
        let c = store.insert_profile(&profile("c")).unwrap();
        assert!(c > a.id);
    }

    #[test]
    fn explicit_ids_are_kept() {
        let store = Store::open_in_memory().unwrap();
        let mut p = profile("restored");
        p.id = 42;
        assert_eq!(store.insert_profile(&p).unwrap(), 42);
        assert!(store.insert_profile(&p).is_err());
    }

    #[test]
    fn unknown_core_is_refused() {
        let store = Store::open_in_memory().unwrap();
        let mut p = profile("x");
        p.core = "v2ray".into();
        assert!(store.insert_profile(&p).is_err());
    }

    #[test]
    fn profile_json_round_trips_with_kotlin_names() {
        let p = Profile { id: 3, kind: 1, remote_url: "https://e/s".into(), core: "xray".into(), ..profile("p") };
        let value = serde_json::to_value(&p).unwrap();
        assert_eq!(value["type"], json!(1));
        assert_eq!(value["remoteURL"], json!("https://e/s"));
        assert_eq!(value["autoUpdateInterval"], json!(60));
        let back: Profile = serde_json::from_value(value).unwrap();
        assert_eq!(back, p);
        // Missing keys take the defaults.
        let partial: Profile = serde_json::from_value(json!({"name": "n"})).unwrap();
        assert_eq!((partial.core.as_str(), partial.auto_update_interval), ("auto", 60));
    }

    #[test]
    fn wire_matches_the_kotlin_profile_row() {
        let mut p = profile("p");
        p.id = 1;
        let wire = p.to_wire();
        assert_eq!(wire["resolvedCore"], json!("sing-box"));
        assert!(wire.get("upload").is_none());
        assert!(wire.get("webPageUrl").is_none());
        p.total = 10;
        p.web_page_url = "https://w".into();
        p.resolved_core = "mihomo".into();
        let wire = p.to_wire();
        assert_eq!(wire["total"], json!(10));
        assert_eq!(wire["upload"], json!(0));
        assert_eq!(wire["webPageUrl"], json!("https://w"));
        assert_eq!(wire["resolvedCore"], json!("mihomo"));
        p.resolved_core.clear();
        p.core = "xray".into();
        assert_eq!(p.to_wire()["resolvedCore"], json!("xray"));
    }

    #[test]
    fn remote_servers_crud() {
        let store = Store::open_in_memory().unwrap();
        let mut s = RemoteServer { name: "home".into(), url: "10.0.0.1:9090".into(), secret: "x".into(), ..Default::default() };
        s.user_order = store.next_remote_server_order().unwrap();
        s.id = store.insert_remote_server(&s).unwrap();
        s.secret = "y".into();
        assert_eq!(store.update_remote_server(&s).unwrap(), 1);
        assert_eq!(store.remote_server(s.id).unwrap().unwrap().secret, "y");
        assert_eq!(store.remote_servers().unwrap().len(), 1);
        assert_eq!(store.next_remote_server_order().unwrap(), 1);
        assert_eq!(store.delete_remote_server(s.id).unwrap(), 1);
        assert!(store.remote_servers().unwrap().is_empty());
    }

    #[test]
    fn ui_preferences_merge_validate_and_drop_locale() {
        let store = Store::open_in_memory().unwrap();
        store.set_preference(PREF_UI, &json!({"locale": "zh", "theme": "dark"}).to_string()).unwrap();
        let read = store.update_ui_preferences(json!({"pureBlack": true}).as_object().unwrap()).unwrap();
        assert_eq!(Value::Object(read), json!({"theme": "dark", "pureBlack": true}));
        assert!(store.update_ui_preferences(json!({"theme": "neon"}).as_object().unwrap()).is_err());
        // An invalid stored value reads as empty.
        store.set_preference(PREF_UI, &json!({"theme": "neon"}).to_string()).unwrap();
        assert!(store.ui_preferences().unwrap().is_empty());
        store.set_preference(PREF_UI, "not json").unwrap();
        assert!(store.ui_preferences().unwrap().is_empty());
    }

    #[test]
    fn backup_origin_is_created_once() {
        let store = Store::open_in_memory().unwrap();
        assert_eq!(store.backup_origin(|| "one".into()).unwrap(), "one");
        assert_eq!(store.backup_origin(|| "two".into()).unwrap(), "one");
    }

    #[test]
    fn explicit_transactions_commit_and_roll_back() {
        let store = Store::open_in_memory().unwrap();
        store.begin().unwrap();
        assert!(store.in_transaction());
        assert!(store.begin().is_err());
        store.insert_profile(&profile("gone")).unwrap();
        store.rollback().unwrap();
        assert!(store.profiles().unwrap().is_empty());
        store.begin().unwrap();
        store.insert_profile(&profile("kept")).unwrap();
        store.commit().unwrap();
        assert_eq!(store.profiles().unwrap().len(), 1);
        assert!(store.commit().is_err());
    }
}
