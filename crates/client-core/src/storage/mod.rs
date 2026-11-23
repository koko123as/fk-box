//! The client store: the service settings, the profile list (metadata only;
//! each profile's configuration stays in its own file at [`Profile::path`]),
//! the remote control servers and the interface preferences, kept as two
//! JSON files in one directory the platform shell names, as Clash-of-Rust's
//! mobile layer keeps `profiles.json` and `settings.json`:
//!
//! - `profiles.json`: [`ProfilesFile`], the profiles and the last id handed out;
//! - `settings.json`: [`SettingsFile`], typed settings, raw preferences
//!   (interface preferences, backup identity, WebDAV account, caches), the
//!   remote servers and the `meta` markers of the one-time imports.
//!
//! One [`Store`] per process: it keeps both files in memory behind a mutex,
//! so the UI and the platform host (on Android the Kotlin service layer,
//! through [`crate::api`]) share it. Every change is applied to a copy, the
//! copy is written (temporary file, flush, rename) and only then replaces
//! the state, so a failed write leaves memory and disk as they were. Inside
//! a [`Store::begin`] transaction changes stay in memory until
//! [`Store::commit`] writes them; [`Store::rollback`] puts the snapshot back.
//!
//! Upgrades: a store directory without the JSON files but with the SQLite
//! `client.db` of the phase 2-4e3b builds is imported once from a copy of it
//! ([`sqlite_import`]); the file is left as it was for a downgrade. The Room
//! databases of older releases are imported by [`legacy`].

pub mod legacy;
pub mod backup;
pub mod webdav;
pub mod sqlite_import;
pub(crate) mod parcel;
pub(crate) mod codec;

use std::collections::BTreeMap;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Mutex, MutexGuard};

use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};

use crate::subscription::{Fetch, Metadata};

/// The JSON schema this build writes (`version` in both files). 1 was the
/// SQLite `client.db` (`PRAGMA user_version`); 2 is the JSON files.
pub const SCHEMA_VERSION: i64 = 2;

/// The profile list file in the store directory.
pub const PROFILES_FILE: &str = "profiles.json";
/// The settings file in the store directory.
pub const SETTINGS_FILE: &str = "settings.json";
/// The SQLite store of earlier builds, imported once ([`sqlite_import`]).
pub const SQLITE_FILE: &str = "client.db";

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

/// One stored setting: its kind code ([`SettingValue::kind`]) and JSON value.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct StoredSetting {
    #[serde(rename = "t")]
    pub kind: i64,
    #[serde(rename = "v")]
    pub value: Value,
}

/// `profiles.json`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct ProfilesFile {
    pub version: i64,
    /// The highest id ever handed out: ids are never reused (as Room's and
    /// SQLite's AUTOINCREMENT), since interface preferences key folds by id.
    pub last_id: i64,
    pub profiles: Vec<Profile>,
}

impl Default for ProfilesFile {
    fn default() -> Self {
        ProfilesFile { version: SCHEMA_VERSION, last_id: 0, profiles: Vec::new() }
    }
}

/// `settings.json`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct SettingsFile {
    pub version: i64,
    pub settings: BTreeMap<String, StoredSetting>,
    /// Raw values: the interface preferences ([`PREF_UI`]), the backup
    /// identity, the WebDAV account, the IP quality cache.
    pub preferences: BTreeMap<String, String>,
    pub remote_servers: Vec<RemoteServer>,
    pub last_remote_server_id: i64,
    /// Markers and results of the one-time imports.
    pub meta: BTreeMap<String, String>,
}

impl Default for SettingsFile {
    fn default() -> Self {
        SettingsFile {
            version: SCHEMA_VERSION,
            settings: BTreeMap::new(),
            preferences: BTreeMap::new(),
            remote_servers: Vec::new(),
            last_remote_server_id: 0,
            meta: BTreeMap::new(),
        }
    }
}

/// Everything the store holds; changed through [`Store`] (and, for the
/// one-time imports, [`Store::transaction`]).
#[derive(Debug, Clone, Default, PartialEq)]
pub struct Data {
    pub profiles: ProfilesFile,
    pub settings: SettingsFile,
}

impl Data {
    fn is_empty(&self) -> bool {
        self.profiles.profiles.is_empty()
            && self.settings.settings.is_empty()
            && self.settings.remote_servers.is_empty()
            && self.settings.preferences.is_empty()
    }

    fn profile_mut(&mut self, id: i64) -> Option<&mut Profile> {
        self.profiles.profiles.iter_mut().find(|p| p.id == id)
    }

    fn profile(&self, id: i64) -> Option<&Profile> {
        self.profiles.profiles.iter().find(|p| p.id == id)
    }

    pub fn set_meta(&mut self, key: &str, value: &str) {
        self.settings.meta.insert(key.to_string(), value.to_string());
    }

    pub fn set_preference(&mut self, key: &str, value: &str) {
        self.settings.preferences.insert(key.to_string(), value.to_string());
    }

    pub fn put_setting(&mut self, key: &str, value: &SettingValue) {
        self.settings.settings.insert(key.to_string(), StoredSetting { kind: value.kind(), value: value.to_json() });
    }

    /// Inserts `profile` under a new id when its id is 0, otherwise under
    /// that id, which must be free; returns the id.
    pub fn insert_profile(&mut self, profile: &Profile) -> Result<i64> {
        validate_profile(profile)?;
        let id = if profile.id > 0 {
            if self.profile(profile.id).is_some() {
                return Err(Error(format!("store: profile {} already exists", profile.id)));
            }
            profile.id
        } else {
            self.profiles.profiles.iter().map(|p| p.id).max().unwrap_or(0).max(self.profiles.last_id) + 1
        };
        self.profiles.last_id = self.profiles.last_id.max(id);
        self.profiles.profiles.push(Profile { id, ..profile.clone() });
        Ok(id)
    }

    /// Raises the profile id counter to at least `seq` (an import keeping
    /// the ids its source already handed out).
    pub fn reserve_profile_ids(&mut self, seq: i64) {
        self.profiles.last_id = self.profiles.last_id.max(seq);
    }

    pub fn insert_remote_server(&mut self, server: &RemoteServer) -> Result<i64> {
        let servers = &mut self.settings;
        let id = if server.id > 0 {
            if servers.remote_servers.iter().any(|s| s.id == server.id) {
                return Err(Error(format!("store: remote server {} already exists", server.id)));
            }
            server.id
        } else {
            servers.remote_servers.iter().map(|s| s.id).max().unwrap_or(0).max(servers.last_remote_server_id) + 1
        };
        servers.last_remote_server_id = servers.last_remote_server_id.max(id);
        servers.remote_servers.push(RemoteServer { id, ..server.clone() });
        Ok(id)
    }

    pub fn reserve_remote_server_ids(&mut self, seq: i64) {
        self.settings.last_remote_server_id = self.settings.last_remote_server_id.max(seq);
    }

    fn content_state(&self, id: i64) -> Option<ContentState> {
        self.profile(id).map(|p| ContentState {
            last_updated: p.last_updated,
            metadata: Metadata {
                upload: p.upload,
                download: p.download,
                total: p.total,
                expire: p.expire,
                web_page_url: p.web_page_url.clone(),
                auto_update_interval: p.auto_update_interval,
            },
        })
    }

    fn write_content_state(&mut self, id: i64, state: &ContentState) -> bool {
        let Some(p) = self.profile_mut(id) else { return false };
        let m = &state.metadata;
        p.last_updated = state.last_updated;
        p.upload = m.upload;
        p.download = m.download;
        p.total = m.total;
        p.expire = m.expire;
        p.web_page_url = m.web_page_url.clone();
        p.auto_update_interval = m.auto_update_interval;
        true
    }
}

struct State {
    data: Data,
    /// The state at [`Store::begin`], put back by [`Store::rollback`].
    snapshot: Option<Data>,
}

pub struct Store {
    /// The store directory; None for a store in memory only.
    dir: Option<PathBuf>,
    state: Mutex<State>,
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

/// The columns a profile form edits: name, core, type, subscription URL,
/// automatic update switch and interval, and whether the user chose the
/// interval; with `metadata`, also the subscription traffic, expiry and
/// website (an edit that drops or changes the URL clears them). Never the
/// order, so an edit cannot undo a reorder made meanwhile.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct FormColumns {
    pub name: String,
    pub core: String,
    pub kind: i64,
    pub remote_url: String,
    pub auto_update: bool,
    pub auto_update_interval: i32,
    pub interval_user_set: bool,
    /// The interval inside is ignored (`auto_update_interval` is written).
    pub metadata: Option<Metadata>,
}

impl Store {
    /// Opens (creating when absent) the store in the directory `dir`. When
    /// the directory has no JSON files yet but holds the SQLite store of an
    /// earlier build, that is imported first ([`sqlite_import`]).
    pub fn open(dir: &Path) -> Result<Store> {
        std::fs::create_dir_all(dir).map_err(|e| Error(format!("store: create {}: {e}", dir.display())))?;
        let profiles_path = dir.join(PROFILES_FILE);
        let settings_path = dir.join(SETTINGS_FILE);
        let data = if profiles_path.exists() || settings_path.exists() {
            Data { profiles: read_file(&profiles_path)?, settings: read_file(&settings_path)? }
        } else {
            let data = sqlite_import::import(dir)?;
            // Written right away (even when empty), so the import runs once.
            write_files(dir, &data, None)?;
            data
        };
        for version in [data.profiles.version, data.settings.version] {
            if version > SCHEMA_VERSION {
                return Err(Error(format!("store: schema {version} is newer than this build ({SCHEMA_VERSION})")));
            }
        }
        Ok(Self::with_data(Some(dir.to_path_buf()), data))
    }

    /// A store that lives in memory only (tests, the screenshot harness).
    pub fn open_in_memory() -> Result<Store> {
        Ok(Self::with_data(None, Data::default()))
    }

    fn with_data(dir: Option<PathBuf>, mut data: Data) -> Store {
        data.profiles.version = SCHEMA_VERSION;
        data.settings.version = SCHEMA_VERSION;
        Store { dir, state: Mutex::new(State { data, snapshot: None }), in_transaction: AtomicBool::new(false), pending: Default::default() }
    }

    fn state(&self) -> MutexGuard<'_, State> {
        self.state.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// The store directory (None in memory).
    pub fn dir(&self) -> Option<&Path> {
        self.dir.as_deref()
    }

    pub fn schema_version(&self) -> Result<i64> {
        Ok(self.state().data.profiles.version)
    }

    fn read<T>(&self, f: impl FnOnce(&Data) -> T) -> T {
        f(&self.state().data)
    }

    /// Applies `f` to a copy of the state; on success the copy is written
    /// (unless a transaction is open, which writes at commit) and replaces
    /// the state. An error leaves both as they were.
    fn write<T>(&self, f: impl FnOnce(&mut Data) -> Result<T>) -> Result<T> {
        let mut state = self.state();
        let mut next = state.data.clone();
        let value = f(&mut next)?;
        if next != state.data {
            if state.snapshot.is_none() {
                if let Some(dir) = &self.dir {
                    write_files(dir, &next, Some(&state.data))?;
                }
            }
            state.data = next;
        }
        Ok(value)
    }

    // -- transactions ------------------------------------------------------

    /// Starts a transaction that spans several calls (a backup restore puts
    /// many profiles back as one change). Only one at a time.
    pub fn begin(&self) -> Result<()> {
        let mut state = self.state();
        if self.in_transaction.swap(true, Ordering::SeqCst) {
            return Err(Error("store: a transaction is already open".into()));
        }
        state.snapshot = Some(state.data.clone());
        Ok(())
    }

    /// Writes what changed since [`Store::begin`]. A failed write keeps the
    /// transaction open (the caller rolls it back).
    pub fn commit(&self) -> Result<()> {
        let mut state = self.state();
        if !self.in_transaction.load(Ordering::SeqCst) {
            return Err(Error("store: no transaction is open".into()));
        }
        if let (Some(dir), Some(snapshot)) = (&self.dir, &state.snapshot) {
            if *snapshot != state.data {
                write_files(dir, &state.data, Some(snapshot))?;
            }
        }
        state.snapshot = None;
        self.in_transaction.store(false, Ordering::SeqCst);
        Ok(())
    }

    pub fn rollback(&self) -> Result<()> {
        let mut state = self.state();
        if !self.in_transaction.load(Ordering::SeqCst) {
            return Err(Error("store: no transaction is open".into()));
        }
        if let Some(snapshot) = state.snapshot.take() {
            state.data = snapshot;
        }
        self.in_transaction.store(false, Ordering::SeqCst);
        Ok(())
    }

    /// True while a [`Store::begin`] transaction is open; writes from
    /// elsewhere (the UI) wait for it to end instead of joining it.
    pub fn in_transaction(&self) -> bool {
        self.in_transaction.load(Ordering::SeqCst)
    }

    /// Runs `f` on a copy of the state and writes it as one change (the
    /// one-time imports). Refused while a [`Store::begin`] transaction is open.
    pub(crate) fn transaction<T>(&self, f: impl FnOnce(&mut Data) -> Result<T>) -> Result<T> {
        if self.in_transaction() {
            return Err(Error("store: a transaction is already open".into()));
        }
        self.write(f)
    }

    // -- meta ----------------------------------------------------------------

    pub fn meta(&self, key: &str) -> Result<Option<String>> {
        Ok(self.read(|d| d.settings.meta.get(key).cloned()))
    }

    pub fn set_meta(&self, key: &str, value: &str) -> Result<()> {
        self.write(|d| {
            d.set_meta(key, value);
            Ok(())
        })
    }

    /// True when the store holds none of the user's data yet (only `meta`).
    pub fn is_empty(&self) -> Result<bool> {
        Ok(self.read(Data::is_empty))
    }

    // -- settings ------------------------------------------------------------

    pub fn settings(&self) -> Result<Vec<(String, SettingValue)>> {
        let stored = self.read(|d| d.settings.settings.clone());
        stored.into_iter().map(|(key, s)| Ok((key, SettingValue::from_json(s.kind, &s.value)?))).collect()
    }

    pub fn setting(&self, key: &str) -> Result<Option<SettingValue>> {
        match self.read(|d| d.settings.settings.get(key).cloned()) {
            Some(s) => Ok(Some(SettingValue::from_json(s.kind, &s.value)?)),
            None => Ok(None),
        }
    }

    pub fn put_setting(&self, key: &str, value: &SettingValue) -> Result<()> {
        self.write(|d| {
            d.put_setting(key, value);
            Ok(())
        })
    }

    /// Writes several settings as one change (inside a [`Store::begin`]
    /// transaction, as part of it).
    pub fn put_settings(&self, entries: &[(String, SettingValue)]) -> Result<()> {
        self.write(|d| {
            for (key, value) in entries {
                d.put_setting(key, value);
            }
            Ok(())
        })
    }

    pub fn remove_setting(&self, key: &str) -> Result<()> {
        self.write(|d| {
            d.settings.settings.remove(key);
            Ok(())
        })
    }

    // -- profiles ------------------------------------------------------------

    /// Every profile in the user's order.
    pub fn profiles(&self) -> Result<Vec<Profile>> {
        let mut profiles = self.read(|d| d.profiles.profiles.clone());
        profiles.sort_by_key(|p| (p.user_order, p.id));
        Ok(profiles)
    }

    pub fn profile(&self, id: i64) -> Result<Option<Profile>> {
        Ok(self.read(|d| d.profile(id).cloned()))
    }

    /// Inserts `profile` and returns its id: a new one when `profile.id` is 0,
    /// otherwise that id (a restore or rollback putting a profile back).
    pub fn insert_profile(&self, profile: &Profile) -> Result<i64> {
        self.write(|d| d.insert_profile(profile))
    }

    /// Writes every field of `profile` except its order, which only changes
    /// when `write_order` is set (a reorder or a restore), so an update from
    /// a copy read before a reorder does not undo it. Returns the rows changed.
    pub fn update_profile(&self, profile: &Profile, write_order: bool) -> Result<usize> {
        validate_profile(profile)?;
        self.write(|d| {
            let Some(stored) = d.profile_mut(profile.id) else { return Ok(0) };
            let order = if write_order { profile.user_order } else { stored.user_order };
            *stored = Profile { user_order: order, ..profile.clone() };
            Ok(1)
        })
    }

    pub fn delete_profiles(&self, ids: &[i64]) -> Result<usize> {
        self.write(|d| {
            let before = d.profiles.profiles.len();
            d.profiles.profiles.retain(|p| !ids.contains(&p.id));
            Ok(before - d.profiles.profiles.len())
        })
    }

    /// The order a new profile takes: after the last one (0 for the first).
    pub fn next_profile_order(&self) -> Result<i64> {
        Ok(self.read(|d| d.profiles.profiles.iter().map(|p| p.user_order + 1).max().unwrap_or(0)))
    }

    /// Puts the profiles in the order of `ids`, which must name every profile
    /// exactly once (the list may have changed since the caller read it).
    pub fn reorder_profiles(&self, ids: &[i64]) -> Result<()> {
        self.write(|d| {
            let mut current: Vec<i64> = d.profiles.profiles.iter().map(|p| p.id).collect();
            let mut wanted = ids.to_vec();
            current.sort_unstable();
            wanted.sort_unstable();
            wanted.dedup();
            if wanted.len() != ids.len() || wanted != current {
                return Err(Error("Profile list changed; refresh and retry".into()));
            }
            for (index, id) in ids.iter().enumerate() {
                if let Some(p) = d.profile_mut(*id) {
                    p.user_order = index as i64;
                }
            }
            Ok(())
        })
    }

    /// The [`ContentState`] of a profile, None when it does not exist.
    pub fn content_state(&self, id: i64) -> Result<Option<ContentState>> {
        Ok(self.read(|d| d.content_state(id)))
    }

    /// Records a save of a profile's content: `last_updated` when given, and
    /// the subscription metadata of `fetch` when given, its update interval
    /// decided against the profile's own `interval_user_set` as read in the
    /// same locked step. Only the [`ContentState`] fields are written.
    /// Returns the state before and after, or None when the profile no
    /// longer exists.
    pub fn record_content_save(
        &self,
        id: i64,
        last_updated: Option<i64>,
        fetch: Option<&Fetch>,
    ) -> Result<Option<(ContentState, ContentState)>> {
        self.write(|d| {
            let Some(before) = d.content_state(id) else { return Ok(None) };
            let user_set = d.profile(id).map(|p| p.interval_user_set).unwrap_or(false);
            let after = ContentState {
                last_updated: last_updated.unwrap_or(before.last_updated),
                metadata: match fetch {
                    Some(fetch) => Metadata::after(before.metadata.auto_update_interval, user_set, fetch),
                    None => before.metadata.clone(),
                },
            };
            if after != before {
                d.write_content_state(id, &after);
            }
            Ok(Some((before, after)))
        })
    }

    /// Puts a [`ContentState`] back (a failed save rolled back). False when
    /// the profile no longer exists.
    pub fn restore_content_state(&self, id: i64, state: &ContentState) -> Result<bool> {
        self.write(|d| Ok(d.write_content_state(id, state)))
    }

    /// The fields a profile form edits ([`FormColumns`]), None when the
    /// profile does not exist.
    pub fn form_columns(&self, id: i64) -> Result<Option<FormColumns>> {
        Ok(self.read(|d| {
            d.profile(id).map(|p| FormColumns {
                name: p.name.clone(),
                core: p.core.clone(),
                kind: p.kind,
                remote_url: p.remote_url.clone(),
                auto_update: p.auto_update,
                auto_update_interval: p.auto_update_interval,
                interval_user_set: p.interval_user_set,
                metadata: Some(Metadata {
                    upload: p.upload,
                    download: p.download,
                    total: p.total,
                    expire: p.expire,
                    web_page_url: p.web_page_url.clone(),
                    auto_update_interval: p.auto_update_interval,
                }),
            })
        }))
    }

    /// Writes the fields a profile form edits, and nothing else: the order,
    /// the file path, the update time and the resolved core stay as they
    /// are. The subscription metadata is written only when `columns`
    /// carries it. False when the profile no longer exists.
    pub fn write_form_columns(&self, id: i64, columns: &FormColumns) -> Result<bool> {
        if !CORES.contains(&columns.core.as_str()) {
            return Err(Error(format!("Unknown core: {}", columns.core)));
        }
        if columns.kind != 0 && columns.kind != 1 {
            return Err(Error(format!("Invalid profile type: {}", columns.kind)));
        }
        self.write(|d| {
            let Some(p) = d.profile_mut(id) else { return Ok(false) };
            p.name = columns.name.clone();
            p.core = columns.core.clone();
            p.kind = columns.kind;
            p.remote_url = columns.remote_url.clone();
            p.auto_update = columns.auto_update;
            p.auto_update_interval = columns.auto_update_interval;
            p.interval_user_set = columns.interval_user_set;
            if let Some(m) = &columns.metadata {
                p.upload = m.upload;
                p.download = m.download;
                p.total = m.total;
                p.expire = m.expire;
                p.web_page_url = m.web_page_url.clone();
            }
            Ok(true)
        })
    }

    /// Records the core a profile's content resolved to for the file `stamp`.
    pub fn set_resolved_core(&self, id: i64, core: &str, stamp: &str) -> Result<()> {
        self.write(|d| {
            if let Some(p) = d.profile_mut(id) {
                p.resolved_core = core.to_string();
                p.resolved_stamp = stamp.to_string();
            }
            Ok(())
        })
    }

    // -- remote servers ------------------------------------------------------

    pub fn remote_servers(&self) -> Result<Vec<RemoteServer>> {
        let mut servers = self.read(|d| d.settings.remote_servers.clone());
        servers.sort_by_key(|s| (s.user_order, s.id));
        Ok(servers)
    }

    pub fn remote_server(&self, id: i64) -> Result<Option<RemoteServer>> {
        Ok(self.read(|d| d.settings.remote_servers.iter().find(|s| s.id == id).cloned()))
    }

    pub fn insert_remote_server(&self, server: &RemoteServer) -> Result<i64> {
        self.write(|d| d.insert_remote_server(server))
    }

    pub fn update_remote_server(&self, server: &RemoteServer) -> Result<usize> {
        self.write(|d| match d.settings.remote_servers.iter_mut().find(|s| s.id == server.id) {
            Some(stored) => {
                *stored = server.clone();
                Ok(1)
            }
            None => Ok(0),
        })
    }

    pub fn delete_remote_server(&self, id: i64) -> Result<usize> {
        self.write(|d| {
            let before = d.settings.remote_servers.len();
            d.settings.remote_servers.retain(|s| s.id != id);
            Ok(before - d.settings.remote_servers.len())
        })
    }

    pub fn next_remote_server_order(&self) -> Result<i64> {
        Ok(self.read(|d| d.settings.remote_servers.iter().map(|s| s.user_order + 1).max().unwrap_or(0)))
    }

    // -- preferences ---------------------------------------------------------

    /// A raw preferences value (`None` when unset).
    pub fn preference(&self, key: &str) -> Result<Option<String>> {
        Ok(self.read(|d| d.settings.preferences.get(key).cloned()))
    }

    pub fn set_preference(&self, key: &str, value: &str) -> Result<()> {
        self.write(|d| {
            d.set_preference(key, value);
            Ok(())
        })
    }

    pub fn remove_preference(&self, key: &str) -> Result<()> {
        self.write(|d| {
            d.settings.preferences.remove(key);
            Ok(())
        })
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

fn validate_profile(profile: &Profile) -> Result<()> {
    if !CORES.contains(&profile.core.as_str()) {
        return Err(Error(format!("Unknown core: {}", profile.core)));
    }
    if profile.kind != 0 && profile.kind != 1 {
        return Err(Error(format!("Invalid profile type: {}", profile.kind)));
    }
    Ok(())
}


fn read_file<T: Default + serde::de::DeserializeOwned>(path: &Path) -> Result<T> {
    match std::fs::read(path) {
        Ok(bytes) => serde_json::from_slice(&bytes).map_err(|e| Error(format!("store: {}: {e}", path.display()))),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(T::default()),
        Err(e) => Err(Error(format!("store: read {}: {e}", path.display()))),
    }
}

/// Writes the files of `data` that differ from `before` (all when None):
/// each to a temporary file that is flushed to disk, then renamed over the
/// old one. Both temporaries are complete before either rename, so a crash
/// leaves each file whole (old or new).
fn write_files(dir: &Path, data: &Data, before: Option<&Data>) -> Result<()> {
    let mut staged: Vec<(PathBuf, PathBuf)> = Vec::new();
    let mut stage = |name: &str, bytes: Vec<u8>| -> Result<()> {
        let path = dir.join(name);
        let temp = dir.join(format!(".{name}.tmp"));
        let written = std::fs::File::create(&temp).and_then(|mut file| {
            file.write_all(&bytes)?;
            file.sync_all()
        });
        if let Err(e) = written {
            let _ = std::fs::remove_file(&temp);
            return Err(Error(format!("store: write {}: {e}", temp.display())));
        }
        staged.push((temp, path));
        Ok(())
    };
    let result = (|| {
        if before.map_or(true, |b| b.settings != data.settings) {
            stage(SETTINGS_FILE, serde_json::to_vec_pretty(&data.settings)?)?;
        }
        if before.map_or(true, |b| b.profiles != data.profiles) {
            stage(PROFILES_FILE, serde_json::to_vec_pretty(&data.profiles)?)?;
        }
        Ok(())
    })();
    if let Err(error) = result {
        for (temp, _) in &staged {
            let _ = std::fs::remove_file(temp);
        }
        return Err(error);
    }
    for (temp, path) in &staged {
        std::fs::rename(temp, path).map_err(|e| Error(format!("store: rename {}: {e}", path.display())))?;
    }
    // The renames themselves reach the disk with the directory.
    if let Ok(dir) = std::fs::File::open(dir) {
        let _ = dir.sync_all();
    }
    Ok(())
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
    fn reopening_the_directory_keeps_data_and_schema() {
        let dir = crate::legacy::tests::scratch("reopen");
        let path = dir.join("fkbox");
        {
            let store = Store::open(&path).unwrap();
            store.put_setting("selected_profile", &SettingValue::Long(7)).unwrap();
            store.insert_profile(&profile("a")).unwrap();
        }
        let store = Store::open(&path).unwrap();
        assert_eq!(store.schema_version().unwrap(), SCHEMA_VERSION);
        assert_eq!(store.setting("selected_profile").unwrap(), Some(SettingValue::Long(7)));
        assert_eq!(store.profiles().unwrap()[0].name, "a");
        // Plain, readable JSON like Clash-of-Rust's files.
        let settings: Value = serde_json::from_slice(&std::fs::read(path.join(SETTINGS_FILE)).unwrap()).unwrap();
        assert_eq!(settings["settings"]["selected_profile"], json!({"t": 3, "v": 7}));
        let profiles: Value = serde_json::from_slice(&std::fs::read(path.join(PROFILES_FILE)).unwrap()).unwrap();
        assert_eq!((profiles["version"].as_i64(), profiles["lastId"].as_i64()), (Some(SCHEMA_VERSION), Some(1)));
        // No temporary files are left behind.
        let names: Vec<String> = std::fs::read_dir(&path).unwrap().map(|e| e.unwrap().file_name().to_string_lossy().into_owned()).collect();
        assert!(names.iter().all(|n| !n.ends_with(".tmp")), "{names:?}");
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn a_newer_schema_is_refused() {
        let dir = crate::legacy::tests::scratch("newer");
        std::fs::write(dir.join(PROFILES_FILE), json!({"version": SCHEMA_VERSION + 1}).to_string()).unwrap();
        assert!(Store::open(&dir).is_err());
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn an_unreadable_file_is_an_error_and_is_kept() {
        let dir = crate::legacy::tests::scratch("unreadable");
        std::fs::write(dir.join(SETTINGS_FILE), "{ not json").unwrap();
        assert!(Store::open(&dir).is_err());
        assert_eq!(std::fs::read_to_string(dir.join(SETTINGS_FILE)).unwrap(), "{ not json");
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn a_failed_write_leaves_memory_and_disk_unchanged() {
        let dir = crate::legacy::tests::scratch("failed-write");
        let store = Store::open(&dir).unwrap();
        store.put_setting("kept", &SettingValue::Bool(true)).unwrap();
        // A directory where the temporary file goes makes the write fail.
        std::fs::create_dir(dir.join(format!(".{SETTINGS_FILE}.tmp"))).unwrap();
        assert!(store.put_setting("lost", &SettingValue::Bool(true)).is_err());
        assert_eq!(store.setting("lost").unwrap(), None);
        std::fs::remove_dir(dir.join(format!(".{SETTINGS_FILE}.tmp"))).unwrap();
        let reopened = Store::open(&dir).unwrap();
        assert_eq!(reopened.setting("kept").unwrap(), Some(SettingValue::Bool(true)));
        assert_eq!(reopened.setting("lost").unwrap(), None);
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn a_transaction_writes_at_commit_only() {
        let dir = crate::legacy::tests::scratch("tx-files");
        let store = Store::open(&dir).unwrap();
        store.begin().unwrap();
        store.insert_profile(&profile("pending")).unwrap();
        assert_eq!(Store::open(&dir).unwrap().profiles().unwrap().len(), 0, "not written before commit");
        store.commit().unwrap();
        assert_eq!(Store::open(&dir).unwrap().profiles().unwrap().len(), 1);
        store.begin().unwrap();
        store.delete_profiles(&[1]).unwrap();
        store.rollback().unwrap();
        assert_eq!(store.profiles().unwrap().len(), 1);
        assert_eq!(Store::open(&dir).unwrap().profiles().unwrap().len(), 1);
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
