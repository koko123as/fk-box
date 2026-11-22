//! Backups in the `bbox-1` format: packing, validation and restore.
//!
//! A backup is one JSON object: `format`, `origin` (this install's backup
//! identity), `createdAt`, `selectedProfile`, `profiles` (each profile's
//! wire fields with its `key` and `content`), `preferences`, `settings`,
//! `terminal`, `webdav` (absent when there is no account) and `servers`.
//! The text written is what the Kotlin host wrote (org.json escapes `/`,
//! serde does not; both read either).
//!
//! A restore writes the profiles (files and rows), the remote servers, the
//! selection, the terminal settings, the WebDAV account and the interface
//! preferences in one store transaction (the UI's own writes are rejected
//! meanwhile), keeping a copy of every file it replaces. The service
//! settings and applying the selected profile to the running service are
//! the platform's next steps; it then calls [`finish`], or [`rollback`],
//! which puts the store and the files back as they were.
//!
//! What only the platform can do goes through the kernel bridge
//! ([`Kernel`]): `validate` (a profile's content checked by the core it
//! resolves to), the terminal theme/font checks ([`crate::terminal`]) and
//! the WebDAV password encryption ([`crate::webdav`]).

use std::collections::{HashMap, VecDeque};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

use serde::Serialize;
use serde_json::{json, Map, Value};

use crate::codec;
use crate::content::{self, Kernel};
use crate::service_settings::{self, Written};
use crate::storage::{Profile, RemoteServer, SettingValue, Store, CORES, CORE_AUTO, DEFAULT_INTERVAL, PREF_UI};
use crate::subscription;
use crate::{preferences, terminal, webdav};

pub const FORMAT: &str = "bbox-1";
pub const MAX_BYTES: usize = 64 * 1024 * 1024;
pub const SELECTED_PROFILE_KEY: &str = "selected_profile";
const MAX_SAFE_INTEGER: f64 = 9_007_199_254_740_991.0;
const TOO_LARGE: &str = "Backup exceeds 64 MiB";

/// The setting keys a restore writes itself (the rest of the service
/// settings go through the platform).
const OWN_SETTING_KEYS: [&str; 9] = [
    SELECTED_PROFILE_KEY,
    terminal::LIGHT_THEME_KEY,
    terminal::DARK_THEME_KEY,
    terminal::FONT_FAMILY_KEY,
    terminal::FONT_SIZE_KEY,
    terminal::FONT_PATH_KEY,
    terminal::LIGHT_CONFIG_KEY,
    terminal::DARK_CONFIG_KEY,
    terminal::FONT_FOLLOW_THEME_KEY,
];

/// Where a restore puts files: profile configurations go to
/// `<files>/configs`, imported fonts to `<files>/fonts`, the copies of
/// replaced files to `<cache>/restore-*`.
#[derive(Debug, Clone)]
pub struct Dirs {
    pub files: PathBuf,
    pub cache: PathBuf,
}

// -- helpers ---------------------------------------------------------------------

fn opt<'a>(map: &'a Map<String, Value>, key: &str) -> Option<&'a Value> {
    map.get(key).filter(|v| !v.is_null())
}

fn integer(value: &Value, minimum: i64) -> bool {
    value.as_f64().is_some_and(|f| f.is_finite() && f.fract() == 0.0 && f >= minimum as f64 && f <= MAX_SAFE_INTEGER)
}

fn as_long(value: &Value) -> Option<i64> {
    value.as_i64().or_else(|| value.as_f64().map(|f| f as i64))
}

/// A list of objects (absent reads as empty).
fn rows(value: Option<&Value>) -> Result<Vec<&Map<String, Value>>, String> {
    match value {
        None => Ok(Vec::new()),
        Some(value) => value
            .as_array()
            .ok_or("Invalid backup list")?
            .iter()
            .map(|v| v.as_object().ok_or_else(|| "Invalid backup entry".to_string()))
            .collect(),
    }
}

fn object<'a>(backup: &'a Map<String, Value>, key: &str) -> Result<Option<&'a Map<String, Value>>, String> {
    opt(backup, key).map(|v| v.as_object().ok_or_else(|| format!("Invalid backup {key}"))).transpose()
}

fn utf16_len(value: &str) -> usize {
    value.encode_utf16().count()
}

/// The text of a backup, refused above 64 MiB.
pub fn encode(backup: &Value) -> Result<String, String> {
    let text = backup.to_string();
    if text.len() > MAX_BYTES {
        return Err(TOO_LARGE.into());
    }
    Ok(text)
}

/// A remote control server address as the Kotlin `RemoteServer.validateURL`
/// took it: an http(s) address (http assumed without a scheme) with a host,
/// normalized (trimmed, no trailing slash, no `http://`). None when invalid.
pub fn remote_server_url(value: &str) -> Option<String> {
    let trimmed = value.trim().trim_end_matches('/');
    if trimmed.is_empty() {
        return None;
    }
    let lower = trimmed.to_ascii_lowercase();
    let connect = if lower.starts_with("http://") || lower.starts_with("https://") {
        // java.net.URI compares the scheme case-insensitively here.
        let (scheme, rest) = trimmed.split_once("://")?;
        format!("{}://{rest}", scheme.to_ascii_lowercase())
    } else {
        format!("http://{trimmed}")
    };
    if !crate::geo::valid_url(&connect) {
        return None;
    }
    Some(normalize_server_url(value))
}

fn normalize_server_url(value: &str) -> String {
    let trimmed = value.trim().trim_end_matches('/');
    if trimmed.len() >= 7 && trimmed[..7].eq_ignore_ascii_case("http://") {
        trimmed[7..].to_string()
    } else {
        trimmed.to_string()
    }
}

/// A profile's backup identity: its file name when that is a UUID (files
/// are named so), otherwise a UUID derived from the origin and the id.
pub fn profile_key(profile: &Profile, origin: &str) -> String {
    let path = Path::new(&profile.path);
    let name = path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
    let stem = name.rsplit_once('.').map(|(s, _)| s.to_string()).unwrap_or(name);
    if codec::is_canonical_uuid(&stem) {
        stem
    } else {
        codec::name_uuid(format!("{origin}:{}", profile.id).as_bytes())
    }
}

/// Checks `content` with the core `declared` resolves to; returns that core.
fn validate_content(kernel: &mut dyn Kernel, declared: &str, content: &str) -> Result<String, String> {
    let core = kernel.call("validate", json!({ "core": declared, "content": content }))?;
    Ok(core.as_str().unwrap_or(declared).to_string())
}

fn now_ms() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}

// -- packing ---------------------------------------------------------------------

/// Packs a backup of everything (`generated` becomes the backup identity
/// when this install has none yet).
pub fn create(store: &Store, kernel: &mut dyn Kernel, generated: &str) -> Result<Value, String> {
    let e = |error: crate::storage::Error| error.0;
    let origin = store.backup_origin(|| generated.to_string()).map_err(e)?;
    let mut size = 0u64;
    let mut profiles = Vec::new();
    for profile in store.profiles().map_err(e)? {
        let path = Path::new(&profile.path);
        size += std::fs::metadata(path).map(|m| m.len()).unwrap_or(0);
        if size > MAX_BYTES as u64 {
            return Err(TOO_LARGE.into());
        }
        let content = content::read(path)?.ok_or_else(|| format!("{} (No such file or directory)", profile.path))?;
        validate_content(kernel, &profile.core, &content)?;
        let mut entry = match profile.to_wire() {
            Value::Object(map) => map,
            _ => Map::new(),
        };
        entry.insert("key".into(), profile_key(&profile, &origin).into());
        entry.insert("content".into(), content.into());
        profiles.push(Value::Object(entry));
    }
    let selected = match store.setting(SELECTED_PROFILE_KEY).map_err(e)? {
        Some(SettingValue::Long(v)) => v,
        _ => -1,
    };
    let mut backup = Map::new();
    backup.insert("format".into(), FORMAT.into());
    backup.insert("origin".into(), origin.into());
    backup.insert("createdAt".into(), now_ms().into());
    backup.insert("selectedProfile".into(), selected.into());
    backup.insert("profiles".into(), Value::Array(profiles));
    backup.insert("preferences".into(), Value::Object(store.ui_preferences().map_err(e)?));
    backup.insert("settings".into(), Value::Object(service_settings::backup(store)?));
    backup.insert("terminal".into(), Value::Object(terminal::backup(store)?));
    // No account: no key (org.json left null values out), so a restore
    // keeps the account the device has.
    if let Some(account) = webdav::account(store, kernel)? {
        backup.insert("webdav".into(), Value::Object(account));
    }
    let servers: Vec<Value> =
        store.remote_servers().map_err(e)?.into_iter().map(|s| json!({ "name": s.name, "url": s.url, "secret": s.secret })).collect();
    backup.insert("servers".into(), Value::Array(servers));
    let backup = Value::Object(backup);
    encode(&backup)?;
    Ok(backup)
}

// -- validation ------------------------------------------------------------------

/// The profile wire fields of an entry (every key optional).
fn validate_wire_fields(entry: &Map<String, Value>) -> Result<(), String> {
    if let Some(core) = opt(entry, "core") {
        if !core.as_str().is_some_and(|c| CORES.contains(&c)) {
            return Err("Invalid profile core".into());
        }
    }
    for key in ["upload", "download", "total", "expire"] {
        if let Some(v) = opt(entry, key) {
            if !v.as_f64().is_some_and(|f| f.is_finite() && f >= 0.0) {
                return Err(format!("Invalid subscription {key}"));
            }
        }
    }
    if opt(entry, "webPageUrl").is_some_and(|v| !v.is_string()) {
        return Err("Invalid subscription website".into());
    }
    if opt(entry, "intervalUserSet").is_some_and(|v| !v.is_boolean()) {
        return Err("Invalid update interval option".into());
    }
    Ok(())
}

fn declared_core(entry: &Map<String, Value>) -> &str {
    opt(entry, "core").and_then(Value::as_str).filter(|c| CORES.contains(c)).unwrap_or(CORE_AUTO)
}

/// Checks a backup for a restore of `scope` ("all" or "profiles") and
/// returns its profile entries.
pub fn validate<'a>(backup: &'a Value, scope: &str, kernel: &mut dyn Kernel) -> Result<Vec<&'a Map<String, Value>>, String> {
    if scope != "all" && scope != "profiles" {
        return Err("Invalid restore scope".into());
    }
    let backup = backup.as_object().ok_or("Unsupported B-box backup format")?;
    if backup.get("format").and_then(Value::as_str) != Some(FORMAT) {
        return Err("Unsupported B-box backup format".into());
    }
    if opt(backup, "origin").is_some_and(|v| !v.as_str().is_some_and(codec::is_canonical_uuid)) {
        return Err("Invalid backup origin".into());
    }
    if opt(backup, "createdAt").is_some_and(|v| !integer(v, 0)) {
        return Err("Invalid backup timestamp".into());
    }
    let entries = rows(Some(opt(backup, "profiles").ok_or("Backup has no profiles")?))?;
    if entries.len() > 1000 {
        return Err("Backup contains too many profiles".into());
    }
    for entry in &entries {
        if !opt(entry, "name").and_then(Value::as_str).is_some_and(|n| !n.trim().is_empty() && utf16_len(n) <= 4096) {
            return Err("Profile name is required".into());
        }
        validate_wire_fields(entry)?;
        let content = opt(entry, "content").and_then(Value::as_str).ok_or("Missing profile content")?;
        validate_content(kernel, declared_core(entry), content)?;
        let url = match opt(entry, "url") {
            None => "",
            Some(v) => v.as_str().ok_or("Invalid profile URL")?,
        };
        if !url.is_empty() && !crate::geo::valid_url(url) {
            return Err("Enter an HTTP or HTTPS subscription URL".into());
        }
        if opt(entry, "key").is_some_and(|v| !v.as_str().is_some_and(codec::is_canonical_uuid)) {
            return Err("Invalid profile backup identity".into());
        }
        if opt(entry, "id").is_some_and(|v| !integer(v, 1)) {
            return Err("Invalid profile ID".into());
        }
        if opt(entry, "interval").is_some_and(|v| !v.as_f64().is_some_and(|f| f.fract() == 0.0 && f >= 15.0 && f <= f64::from(i32::MAX))) {
            return Err("Invalid update interval".into());
        }
        if opt(entry, "autoUpdate").is_some_and(|v| !v.is_boolean()) {
            return Err("Invalid auto update option".into());
        }
        if opt(entry, "updatedAt").is_some_and(|v| !integer(v, 0)) {
            return Err("Invalid update timestamp".into());
        }
    }
    let keys: Vec<&str> = entries.iter().filter_map(|e| opt(e, "key").and_then(Value::as_str)).collect();
    let ids: Vec<i64> = entries.iter().filter_map(|e| opt(e, "id").and_then(as_long)).collect();
    let distinct = |n: usize, mut v: Vec<String>| {
        v.sort();
        v.dedup();
        v.len() == n
    };
    if !distinct(keys.len(), keys.iter().map(|k| k.to_string()).collect()) || !distinct(ids.len(), ids.iter().map(|i| i.to_string()).collect()) {
        return Err("Backup contains duplicate profiles".into());
    }
    if let Some(selected) = opt(backup, "selectedProfile") {
        if !(integer(selected, -1) && as_long(selected).is_some_and(|s| s == -1 || ids.contains(&s))) {
            return Err("Invalid selected profile".into());
        }
    }
    if scope == "all" {
        preferences::validate(object(backup, "preferences")?.unwrap_or(&Map::new()))?;
        service_settings::validate(object(backup, "settings")?.unwrap_or(&Map::new()))?;
        let servers = rows(opt(backup, "servers"))?;
        if servers.len() > 1000 {
            return Err("Too many remote servers".into());
        }
        let mut identities = Vec::new();
        for server in &servers {
            let name = opt(server, "name").and_then(Value::as_str);
            let url = opt(server, "url").and_then(Value::as_str).and_then(remote_server_url);
            let (Some(name), Some(url)) = (name, url) else { return Err("Invalid remote server".into()) };
            if opt(server, "secret").is_some_and(|v| !v.is_string()) {
                return Err("Invalid remote secret".into());
            }
            identities.push(format!("{url}\n{name}"));
        }
        if !distinct(identities.len(), identities) {
            return Err("Backup contains duplicate remote servers".into());
        }
        if let Some(part) = object(backup, "terminal")? {
            terminal::validate_backup(part, kernel)?;
        }
        if let Some(account) = object(backup, "webdav")? {
            webdav::validate(account)?;
        }
    }
    Ok(entries)
}

// -- staging ---------------------------------------------------------------------

/// Backups read and checked, waiting for the user to choose how to restore
/// them; the platform carries only the token. The last two are kept.
static STAGED: Mutex<VecDeque<(String, Arc<Value>)>> = Mutex::new(VecDeque::new());
const MAX_STAGED: usize = 2;

/// Reads and checks a backup's text and keeps it; returns its token and
/// how many profiles it holds.
pub fn read(text: &str, scope: &str, kernel: &mut dyn Kernel) -> Result<Value, String> {
    if text.len() > MAX_BYTES {
        return Err(TOO_LARGE.into());
    }
    let backup: Value = serde_json::from_str(text).map_err(|e| format!("Invalid backup: {e}"))?;
    let count = validate(&backup, scope, kernel)?.len();
    encode(&backup)?;
    let token = codec::random_uuid();
    let mut staged = STAGED.lock().unwrap_or_else(|e| e.into_inner());
    staged.push_back((token.clone(), Arc::new(backup)));
    while staged.len() > MAX_STAGED {
        staged.pop_front();
    }
    Ok(json!({ "token": token, "profileCount": count }))
}

pub fn staged(token: &str) -> Option<Arc<Value>> {
    STAGED.lock().unwrap_or_else(|e| e.into_inner()).iter().find(|(t, _)| t == token).map(|(_, b)| b.clone())
}

fn unstage(token: &str) {
    STAGED.lock().unwrap_or_else(|e| e.into_inner()).retain(|(t, _)| t != token);
}

// -- restore ---------------------------------------------------------------------

/// What the store held before a restore, for [`rollback`].
struct Previous {
    profiles: Vec<Profile>,
    servers: Vec<RemoteServer>,
    settings: Vec<(String, Option<SettingValue>)>,
    webdav: Option<String>,
    ui: Option<String>,
}

/// A restore the platform has not finished or rolled back yet.
struct Pending {
    previous: Previous,
    all: bool,
    /// Each file the restore wrote, with a copy of what it held (None: it
    /// did not exist).
    originals: Vec<(PathBuf, Option<PathBuf>)>,
    work_dir: PathBuf,
    new_font: Option<PathBuf>,
    /// Files of profiles an Override restore removed, deleted on finish.
    stale: Vec<PathBuf>,
    staged: String,
}

static PENDING: Mutex<Vec<(u64, Pending)>> = Mutex::new(Vec::new());
static NEXT_TOKEN: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(1);
const MAX_PENDING: usize = 4;

/// The result of [`restore`]: the platform applies `settings` (the backup's
/// service settings, scope "all"), puts `written` in its settings cache and
/// applies the selected profile, then calls [`finish`] with `token`, or
/// [`rollback`] and applies `previousSettings` again.
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Restored {
    pub token: u64,
    pub count: usize,
    pub written: Vec<Written>,
    pub settings: Option<Map<String, Value>>,
    pub previous_settings: Option<Map<String, Value>>,
}

fn written(entries: &[(String, Option<SettingValue>)]) -> Vec<Written> {
    entries
        .iter()
        .map(|(k, v)| match v {
            Some(v) => Written::of(k, v),
            None => Written { key: k.clone(), t: 0, v: Value::Null },
        })
        .collect()
}

/// The metadata of a backup entry on `profile` (path, name and order are
/// the caller's). Older backups lack core and subscription keys: auto, none.
pub fn restore_wire_fields(profile: &mut Profile, entry: &Map<String, Value>) {
    profile.remote_url = opt(entry, "url").and_then(Value::as_str).unwrap_or("").to_string();
    let remote = !profile.remote_url.is_empty();
    profile.kind = i64::from(remote);
    profile.auto_update = remote && opt(entry, "autoUpdate").and_then(Value::as_bool) == Some(true);
    profile.auto_update_interval = opt(entry, "interval").and_then(Value::as_f64).map(|f| f as i32).unwrap_or(DEFAULT_INTERVAL);
    profile.last_updated = opt(entry, "updatedAt").and_then(as_long).unwrap_or(0);
    profile.core = declared_core(entry).to_string();
    profile.interval_user_set =
        opt(entry, "intervalUserSet").and_then(Value::as_bool).unwrap_or(profile.auto_update_interval != DEFAULT_INTERVAL);
    let count = |key: &str| -> i64 {
        if !remote {
            return 0;
        }
        match opt(entry, key).and_then(Value::as_f64).filter(|f| f.is_finite() && *f >= 0.0) {
            Some(f) if f >= i64::MAX as f64 => i64::MAX,
            Some(f) => f as i64,
            None => 0,
        }
    };
    profile.upload = count("upload");
    profile.download = count("download");
    profile.total = count("total");
    profile.expire = count("expire");
    // Only an absolute http(s) address is kept (as for fetched headers).
    profile.web_page_url =
        if remote { subscription::parse_web_page_url(opt(entry, "webPageUrl").and_then(Value::as_str).unwrap_or("")) } else { String::new() };
}

fn keep_original(originals: &mut Vec<(PathBuf, Option<PathBuf>)>, work_dir: &Path, file: &Path) -> Result<(), String> {
    if originals.iter().any(|(f, _)| f == file) {
        return Ok(());
    }
    let copy = if file.exists() {
        let copy = work_dir.join(originals.len().to_string());
        std::fs::copy(file, &copy).map_err(|e| format!("Unable to stage restore: {e}"))?;
        Some(copy)
    } else {
        None
    };
    originals.push((file.to_path_buf(), copy));
    Ok(())
}

fn put_back_files(originals: &[(PathBuf, Option<PathBuf>)]) -> Vec<String> {
    let mut errors = Vec::new();
    for (file, original) in originals {
        let result = match original {
            None if file.exists() => std::fs::remove_file(file).map_err(|e| e.to_string()),
            None => Ok(()),
            Some(copy) => std::fs::read(copy).map_err(|e| e.to_string()).and_then(|bytes| content::write_atomic_bytes(file, &bytes)),
        };
        if let Err(error) = result {
            errors.push(format!("{}: {error}", file.display()));
        }
    }
    errors
}

/// Restores the staged backup `token` ("Compatible" keeps the profiles and
/// servers it does not name, "Override" removes them).
pub fn restore_staged(store: &Store, kernel: &mut dyn Kernel, token: &str, scope: &str, strategy: &str, dirs: &Dirs) -> Result<Restored, String> {
    let backup = staged(token).ok_or("The backup is no longer available; open it again")?;
    restore(store, kernel, &backup, scope, strategy, dirs, token)
}

pub fn restore(store: &Store, kernel: &mut dyn Kernel, backup: &Value, scope: &str, strategy: &str, dirs: &Dirs, staged: &str) -> Result<Restored, String> {
    if strategy != "Compatible" && strategy != "Override" {
        return Err("Invalid restore strategy".into());
    }
    let entries = validate(backup, scope, kernel)?;
    let doc = backup.as_object().ok_or("Unsupported B-box backup format")?;
    let all = scope == "all";
    let e = |error: crate::storage::Error| error.0;
    let mut previous_settings = Vec::new();
    for key in OWN_SETTING_KEYS {
        previous_settings.push((key.to_string(), store.setting(key).map_err(e)?));
    }
    let previous = Previous {
        profiles: store.profiles().map_err(e)?,
        servers: store.remote_servers().map_err(e)?,
        settings: previous_settings,
        webdav: webdav::raw(store)?,
        ui: store.preference(PREF_UI).map_err(e)?,
    };
    let old_service_settings = if all { Some(service_settings::backup(store)?) } else { None };
    let origin = store.backup_origin(codec::random_uuid).map_err(e)?;
    let work_dir = dirs.cache.join(format!("restore-{}", codec::random_uuid()));
    std::fs::create_dir_all(&work_dir).map_err(|_| "Unable to stage restore".to_string())?;
    let mut originals = Vec::new();
    let mut new_font = None;
    let mut restored_ids = Vec::new();

    store.begin().map_err(e)?;
    let result = (|| -> Result<Vec<(String, Option<SettingValue>)>, String> {
        let mut mapping: HashMap<i64, i64> = HashMap::new();
        let by_key: HashMap<String, &Profile> = previous.profiles.iter().map(|p| (profile_key(p, &origin), p)).collect();
        let same_origin = opt(doc, "origin").and_then(Value::as_str) == Some(origin.as_str());
        let mut next_order = previous.profiles.iter().map(|p| p.user_order).max().unwrap_or(-1) + 1;
        let configs = dirs.files.join("configs");
        for (index, entry) in entries.iter().enumerate() {
            let key = opt(entry, "key").and_then(Value::as_str);
            let entry_id = opt(entry, "id").and_then(as_long);
            let existing = key.and_then(|k| by_key.get(k).copied()).or_else(|| {
                if same_origin {
                    previous.profiles.iter().find(|p| Some(p.id) == entry_id)
                } else {
                    None
                }
            });
            let mut profile = existing.cloned().unwrap_or_default();
            let file = match existing {
                Some(p) => PathBuf::from(&p.path),
                None => configs.join(format!("{}.json", key.map(str::to_string).unwrap_or_else(codec::random_uuid))),
            };
            keep_original(&mut originals, &work_dir, &file)?;
            content::write_atomic(&file, opt(entry, "content").and_then(Value::as_str).unwrap_or(""))?;
            profile.name = opt(entry, "name").and_then(Value::as_str).unwrap_or("").trim().to_string();
            profile.path = file.to_string_lossy().into_owned();
            restore_wire_fields(&mut profile, entry);
            profile.user_order = if strategy == "Override" {
                index as i64
            } else if let Some(p) = existing {
                p.user_order
            } else {
                next_order += 1;
                next_order - 1
            };
            if existing.is_none() {
                profile.id = 0;
                profile.id = store.insert_profile(&profile).map_err(e)?;
            } else {
                store.update_profile(&profile, true).map_err(e)?;
            }
            restored_ids.push(profile.id);
            if let Some(id) = entry_id {
                mapping.insert(id, profile.id);
            }
        }
        if strategy == "Override" {
            let gone: Vec<i64> = previous.profiles.iter().map(|p| p.id).filter(|id| !restored_ids.contains(id)).collect();
            store.delete_profiles(&gone).map_err(e)?;
        }
        if all {
            let mut kept = Vec::new();
            for (index, entry) in rows(opt(doc, "servers"))?.iter().enumerate() {
                let name = opt(entry, "name").and_then(Value::as_str).unwrap_or("");
                let url = opt(entry, "url").and_then(Value::as_str).and_then(remote_server_url).ok_or("Invalid remote server")?;
                let existing = previous.servers.iter().find(|s| normalize_server_url(&s.url) == url && s.name == name);
                let mut server = RemoteServer {
                    id: existing.map_or(0, |s| s.id),
                    user_order: existing.map_or(0, |s| s.user_order),
                    name: name.to_string(),
                    url: url.clone(),
                    secret: opt(entry, "secret").and_then(Value::as_str).unwrap_or("").to_string(),
                };
                if strategy == "Override" {
                    server.user_order = index as i64;
                } else if existing.is_none() {
                    server.user_order = store.next_remote_server_order().map_err(e)?;
                }
                if existing.is_none() {
                    server.id = store.insert_remote_server(&server).map_err(e)?;
                } else {
                    store.update_remote_server(&server).map_err(e)?;
                }
                kept.push(server.id);
            }
            if strategy == "Override" {
                for server in previous.servers.iter().filter(|s| !kept.contains(&s.id)) {
                    store.delete_remote_server(server.id).map_err(e)?;
                }
            }
        }
        // The selection: the backup's (scope all), else the one before when
        // it still exists, else the first restored profile.
        let current: Vec<i64> = store.profiles().map_err(e)?.iter().map(|p| p.id).collect();
        let backup_selection = opt(doc, "selectedProfile").and_then(as_long);
        let old_selection = match previous.settings.first().and_then(|(_, v)| v.clone()) {
            Some(SettingValue::Long(v)) => v,
            _ => -1,
        };
        let selection = if all && backup_selection == Some(-1) {
            -1
        } else {
            (if all { backup_selection.and_then(|s| mapping.get(&s).copied()) } else { None })
                .or_else(|| current.contains(&old_selection).then_some(old_selection))
                .or_else(|| restored_ids.first().copied())
                .unwrap_or(-1)
        };
        let mut entries = vec![(SELECTED_PROFILE_KEY.to_string(), SettingValue::Long(selection))];
        if all {
            if let Some(part) = object(doc, "terminal")? {
                new_font = terminal::restore_font(part, &dirs.files.join("fonts"), kernel)?;
                let path = new_font.as_ref().map(|p| p.to_string_lossy().into_owned()).unwrap_or_default();
                entries.extend(terminal::state_entries(part, &path)?);
            }
            if doc.contains_key("webdav") {
                webdav::save(store, kernel, object(doc, "webdav")?)?;
            }
            let mut restored = preferences::validate(object(doc, "preferences")?.unwrap_or(&Map::new()))?;
            if let Some(Value::Object(folds)) = restored.get("proxyUnfolded").cloned() {
                let remapped: Map<String, Value> = folds
                    .into_iter()
                    .map(|(key, value)| {
                        let id = key.strip_prefix("local:").and_then(|i| i.parse::<i64>().ok());
                        match id.and_then(|i| mapping.get(&i)) {
                            Some(new_id) => (format!("local:{new_id}"), value),
                            None => (key, value),
                        }
                    })
                    .collect();
                restored.insert("proxyUnfolded".into(), Value::Object(remapped));
            }
            store.replace_ui_preferences(&restored).map_err(e)?;
        }
        store.put_settings(&entries).map_err(e)?;
        Ok(entries.into_iter().map(|(k, v)| (k, Some(v))).collect())
    })();

    let entries = match result.and_then(|entries| store.commit().map_err(e).map(|_| entries)) {
        Ok(entries) => entries,
        Err(error) => {
            let _ = store.rollback();
            let mut errors = put_back_files(&originals);
            if let Some(font) = &new_font {
                if let Err(e) = std::fs::remove_file(font) {
                    errors.push(e.to_string());
                }
            }
            if errors.is_empty() {
                let _ = std::fs::remove_dir_all(&work_dir);
                return Err(error);
            }
            return Err(format!("{error} (rollback incomplete: {})", errors.join("; ")));
        }
    };

    let kept: Vec<String> = store.profiles().map_err(e)?.into_iter().map(|p| p.path).collect();
    let stale = if strategy == "Override" {
        previous.profiles.iter().filter(|p| !kept.contains(&p.path)).map(|p| PathBuf::from(&p.path)).collect()
    } else {
        Vec::new()
    };
    let token = NEXT_TOKEN.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
    let mut pending = PENDING.lock().unwrap_or_else(|e| e.into_inner());
    pending.push((token, Pending { previous, all, originals, work_dir, new_font, stale, staged: staged.to_string() }));
    // A platform that never answered: the oldest is dropped (its copies stay
    // in the cache directory).
    while pending.len() > MAX_PENDING {
        pending.remove(0);
    }
    Ok(Restored {
        token,
        count: restored_ids.len(),
        written: written(&entries),
        settings: if all { Some(object(doc, "settings")?.cloned().unwrap_or_default()) } else { None },
        previous_settings: old_service_settings,
    })
}

fn take(token: u64) -> Option<Pending> {
    let mut pending = PENDING.lock().unwrap_or_else(|e| e.into_inner());
    let index = pending.iter().position(|(t, _)| *t == token)?;
    Some(pending.remove(index).1)
}

/// The platform applied the restore: removes the files of profiles it
/// replaced and the copies, and forgets the staged backup.
pub fn finish(token: u64) {
    let Some(done) = take(token) else { return };
    for file in &done.stale {
        let _ = std::fs::remove_file(file);
    }
    let _ = std::fs::remove_dir_all(&done.work_dir);
    unstage(&done.staged);
}

/// Puts the store and the files back as they were before the restore
/// `token`; returns the setting entries written (for the platform's cache).
pub fn rollback(store: &Store, token: u64) -> Result<Vec<Written>, String> {
    let done = take(token).ok_or("The restore can no longer be rolled back")?;
    let e = |error: crate::storage::Error| error.0;
    let previous = &done.previous;
    store.begin().map_err(e)?;
    let result = (|| -> Result<(), String> {
        let old_ids: Vec<i64> = previous.profiles.iter().map(|p| p.id).collect();
        let now: Vec<i64> = store.profiles().map_err(e)?.iter().map(|p| p.id).filter(|id| !old_ids.contains(id)).collect();
        store.delete_profiles(&now).map_err(e)?;
        for profile in &previous.profiles {
            if store.profile(profile.id).map_err(e)?.is_none() {
                store.insert_profile(profile).map_err(e)?;
            } else {
                store.update_profile(profile, true).map_err(e)?;
            }
        }
        if done.all {
            let old_ids: Vec<i64> = previous.servers.iter().map(|s| s.id).collect();
            for server in store.remote_servers().map_err(e)?.iter().filter(|s| !old_ids.contains(&s.id)) {
                store.delete_remote_server(server.id).map_err(e)?;
            }
            for server in &previous.servers {
                if store.remote_server(server.id).map_err(e)?.is_none() {
                    store.insert_remote_server(server).map_err(e)?;
                } else {
                    store.update_remote_server(server).map_err(e)?;
                }
            }
            webdav::put_raw(store, previous.webdav.as_deref())?;
            match &previous.ui {
                Some(text) => store.set_preference(PREF_UI, text).map_err(e)?,
                None => store.remove_preference(PREF_UI).map_err(e)?,
            }
        }
        for (key, value) in &previous.settings {
            match value {
                Some(value) => store.put_settings(&[(key.clone(), value.clone())]).map_err(e)?,
                None => store.remove_setting(key).map_err(e)?,
            }
        }
        Ok(())
    })();
    let mut errors = Vec::new();
    match result.and_then(|_| store.commit().map_err(e)) {
        Ok(()) => {}
        Err(error) => {
            let _ = store.rollback();
            errors.push(error);
        }
    }
    errors.extend(put_back_files(&done.originals));
    if let Some(font) = &done.new_font {
        let _ = std::fs::remove_file(font);
    }
    if !errors.is_empty() {
        return Err(format!("Restore rollback incomplete: {}", errors.join("; ")));
    }
    let _ = std::fs::remove_dir_all(&done.work_dir);
    let settings: Vec<(String, Option<SettingValue>)> =
        if done.all { previous.settings.clone() } else { previous.settings.iter().take(1).cloned().collect() };
    Ok(written(&settings))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::legacy::tests::scratch;

    /// Every content is valid except "bad"; terminal and WebDAV as in their tests.
    struct Platform {
        dav: webdav::tests::Dav,
    }

    impl Kernel for Platform {
        fn call(&mut self, method: &str, args: Value) -> Result<Value, String> {
            match method {
                "validate" if args["content"] == json!("bad") => Err("invalid configuration".into()),
                "validate" => Ok(json!(if args["core"] == json!("auto") { "sing-box" } else { args["core"].as_str().unwrap() })),
                m if m.starts_with("terminal.") => terminal::tests::Ghostty.call(method, args),
                _ => self.dav.call(method, args),
            }
        }
    }

    fn platform() -> Platform {
        Platform { dav: Default::default() }
    }

    struct Fixture {
        store: Store,
        dirs: Dirs,
        root: PathBuf,
    }

    impl Drop for Fixture {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.root);
        }
    }

    fn fixture() -> Fixture {
        let root = scratch("backup");
        let dirs = Dirs { files: root.join("files"), cache: root.join("cache") };
        let store = Store::open_in_memory().unwrap();
        let configs = dirs.files.join("configs");
        std::fs::create_dir_all(&configs).unwrap();
        for (i, name) in ["a", "b"].iter().enumerate() {
            let path = configs.join(format!("{}.json", codec::name_uuid(name.as_bytes())));
            std::fs::write(&path, format!("{{\"{name}\":1}}")).unwrap();
            let profile = Profile { name: name.to_string(), path: path.to_string_lossy().into(), user_order: i as i64, ..Default::default() };
            store.insert_profile(&profile).unwrap();
        }
        store.put_setting(SELECTED_PROFILE_KEY, &SettingValue::Long(2)).unwrap();
        store.insert_remote_server(&RemoteServer { name: "s".into(), url: "10.0.0.1:9090".into(), ..Default::default() }).unwrap();
        Fixture { store, dirs, root }
    }

    fn origin() -> String {
        codec::name_uuid(b"origin")
    }

    #[test]
    fn helpers_follow_the_kotlin_rules() {
        assert_eq!(remote_server_url(" HTTP://h:9090/ ").as_deref(), Some("h:9090"));
        assert_eq!(remote_server_url("https://h").as_deref(), Some("https://h"));
        assert_eq!(remote_server_url("10.0.0.1:9090").as_deref(), Some("10.0.0.1:9090"));
        assert_eq!(remote_server_url(""), None);
        // As java.net.URI: "ftp:" reads as host "ftp" with an empty port.
        assert_eq!(remote_server_url("ftp://").as_deref(), Some("ftp:"));
        assert_eq!(remote_server_url("a b"), None);
        let named = Profile { id: 7, path: "/x/90015098-3cd2-3fb0-9696-3f7d28e17f72.json".into(), ..Default::default() };
        assert_eq!(profile_key(&named, "o"), "90015098-3cd2-3fb0-9696-3f7d28e17f72");
        let other = Profile { id: 7, path: "/x/7.json".into(), ..Default::default() };
        assert_eq!(profile_key(&other, "o"), codec::name_uuid(b"o:7"));
        let mut p = Profile::default();
        restore_wire_fields(&mut p, json!({"url": "https://e.example/s", "autoUpdate": true, "interval": 240, "total": 1e30, "webPageUrl": " javascript:x "}).as_object().unwrap());
        assert_eq!((p.kind, p.auto_update, p.interval_user_set, p.total, p.web_page_url.as_str()), (1, true, true, i64::MAX, ""));
        let mut local = Profile { core: "xray".into(), ..Default::default() };
        restore_wire_fields(&mut local, json!({"total": 5, "webPageUrl": "https://x.example"}).as_object().unwrap());
        assert_eq!((local.kind, local.total, local.core.as_str(), local.web_page_url.as_str()), (0, 0, "auto", ""));
    }

    #[test]
    fn create_packs_everything_and_validates() {
        let f = fixture();
        let mut k = platform();
        webdav::save(&f.store, &mut k, Some(&webdav::tests::account_value())).unwrap();
        let backup = create(&f.store, &mut k, &origin()).unwrap();
        assert_eq!(backup["format"], json!("bbox-1"));
        assert_eq!(backup["origin"], json!(origin()));
        assert_eq!(backup["profiles"].as_array().unwrap().len(), 2);
        assert_eq!(backup["profiles"][0]["key"], json!(codec::name_uuid(b"a")));
        assert_eq!(backup["profiles"][0]["content"], json!("{\"a\":1}"));
        assert_eq!(backup["webdav"]["password"], json!("pw"));
        assert_eq!(backup["servers"][0]["url"], json!("10.0.0.1:9090"));
        assert!(backup["terminal"].get("fontSize").is_some());
        assert_eq!(validate(&backup, "all", &mut k).unwrap().len(), 2);
        let staged = read(&encode(&backup).unwrap(), "all", &mut k).unwrap();
        assert_eq!(staged["profileCount"], json!(2));
        assert!(super::staged(staged["token"].as_str().unwrap()).is_some());
    }

    #[test]
    fn validation_rejects_what_kotlin_rejected() {
        let f = fixture();
        let mut k = platform();
        let good = create(&f.store, &mut k, &origin()).unwrap();
        let with = |patch: &dyn Fn(&mut Value)| {
            let mut b = good.clone();
            patch(&mut b);
            validate(&b, "all", &mut platform()).map(|_| ()).unwrap_err()
        };
        assert_eq!(validate(&good, "some", &mut k).unwrap_err(), "Invalid restore scope");
        assert_eq!(with(&|b| b["format"] = json!("bbox-2")), "Unsupported B-box backup format");
        assert_eq!(with(&|b| b["origin"] = json!("X")), "Invalid backup origin");
        assert_eq!(with(&|b| b["createdAt"] = json!(-1)), "Invalid backup timestamp");
        assert_eq!(with(&|b| { b.as_object_mut().unwrap().remove("profiles"); }), "Backup has no profiles");
        assert_eq!(with(&|b| b["profiles"][0]["name"] = json!(" ")), "Profile name is required");
        assert_eq!(with(&|b| b["profiles"][0]["core"] = json!("clash")), "Invalid profile core");
        assert_eq!(with(&|b| b["profiles"][0]["total"] = json!(-1)), "Invalid subscription total");
        assert_eq!(with(&|b| b["profiles"][0]["content"] = json!("bad")), "invalid configuration");
        assert_eq!(with(&|b| b["profiles"][0]["url"] = json!("file:///x")), "Enter an HTTP or HTTPS subscription URL");
        assert_eq!(with(&|b| b["profiles"][0]["interval"] = json!(14)), "Invalid update interval");
        assert_eq!(with(&|b| b["profiles"][1]["key"] = b["profiles"][0]["key"].clone()), "Backup contains duplicate profiles");
        assert_eq!(with(&|b| b["selectedProfile"] = json!(99)), "Invalid selected profile");
        assert!(with(&|b| b["preferences"] = json!({"theme": 1})).contains("Invalid interface preference"));
        assert!(!with(&|b| b["settings"] = json!({"oomMemoryLimitMB": 1})).is_empty());
        assert_eq!(with(&|b| b["servers"] = json!([{"name": "n", "url": ""}])), "Invalid remote server");
        assert_eq!(with(&|b| b["servers"] = json!([{"name": "n", "url": "h"}, {"name": "n", "url": "http://h/"}])), "Backup contains duplicate remote servers");
        assert_eq!(with(&|b| b["webdav"] = json!({"uri": "x"})), "Invalid WebDAV account");
        assert_eq!(with(&|b| b["terminal"]["fontSize"] = json!(3)), "Font size must be 8–48");
        // Scope "profiles" ignores the other parts.
        let mut b = good.clone();
        b["webdav"] = json!({"uri": "x"});
        assert!(validate(&b, "profiles", &mut k).is_ok());
    }

    #[test]
    fn override_restore_finishes_and_compatible_restore_rolls_back() {
        let f = fixture();
        let mut k = platform();
        let mut backup = create(&f.store, &mut k, &origin()).unwrap();
        let first = f.store.profiles().unwrap()[0].clone();
        // The backup: profile "a" renamed with new content, "b" dropped, a new one added.
        backup["profiles"][0]["name"] = json!(" A2 ");
        backup["profiles"][0]["content"] = json!("{\"a\":2}");
        let new_key = codec::name_uuid(b"c");
        backup["profiles"][1] = json!({"key": new_key, "id": 50, "name": "c", "content": "{}", "url": "https://e.example/s", "total": 9});
        backup["selectedProfile"] = json!(50);
        backup["preferences"] = json!({"proxyUnfolded": {"local:50": ["g"]}});
        backup["servers"] = json!([]);
        f.store.update_ui_preferences(json!({"theme": "dark"}).as_object().unwrap()).unwrap();
        let old_b = f.store.profiles().unwrap()[1].clone();

        let done = restore(&f.store, &mut k, &backup, "all", "Override", &f.dirs, "t").unwrap();
        assert_eq!(done.count, 2);
        let profiles = f.store.profiles().unwrap();
        assert_eq!(profiles.iter().map(|p| p.name.as_str()).collect::<Vec<_>>(), ["A2", "c"]);
        assert_eq!(profiles[0].id, first.id);
        assert_eq!(std::fs::read_to_string(&first.path).unwrap(), "{\"a\":2}");
        let c = &profiles[1];
        assert!(c.path.ends_with(&format!("{new_key}.json")));
        assert_eq!((c.kind, c.total), (1, 9));
        assert_eq!(f.store.setting(SELECTED_PROFILE_KEY).unwrap(), Some(SettingValue::Long(c.id)));
        assert_eq!(f.store.ui_preferences().unwrap()["proxyUnfolded"], json!({ format!("local:{}", c.id): ["g"] }));
        assert!(f.store.remote_servers().unwrap().is_empty());
        assert_eq!(done.written[0].key, SELECTED_PROFILE_KEY);
        assert!(done.settings.is_some() && done.previous_settings.is_some());
        assert!(Path::new(&old_b.path).exists(), "kept until finish");
        finish(done.token);
        assert!(!Path::new(&old_b.path).exists());

        // A compatible restore of the original backup, then a rollback.
        let before_profiles = f.store.profiles().unwrap();
        let before_ui = f.store.ui_preferences_raw().unwrap();
        let original = create(&f.store, &mut k, &origin()).unwrap();
        let mut next = original.clone();
        next["profiles"][0]["content"] = json!("{\"a\":3}");
        next["profiles"][1]["key"] = json!(codec::name_uuid(b"d"));
        next["profiles"][1]["id"] = json!(77);
        next["selectedProfile"] = json!(77);
        next["preferences"] = json!({"theme": "light"});
        let done = restore(&f.store, &mut k, &next, "all", "Compatible", &f.dirs, "t2").unwrap();
        assert_eq!(f.store.profiles().unwrap().len(), 3);
        let written = rollback(&f.store, done.token).unwrap();
        assert_eq!(written.len(), OWN_SETTING_KEYS.len());
        assert_eq!(f.store.profiles().unwrap(), before_profiles);
        assert_eq!(f.store.ui_preferences_raw().unwrap(), before_ui);
        assert_eq!(std::fs::read_to_string(&first.path).unwrap(), "{\"a\":2}");
        assert!(!f.dirs.files.join("configs").join(format!("{}.json", codec::name_uuid(b"d"))).exists());
        assert!(rollback(&f.store, done.token).is_err());
    }

    #[test]
    fn a_failure_midway_leaves_store_and_files_as_they_were() {
        let f = fixture();
        let mut k = platform();
        let mut backup = create(&f.store, &mut k, &origin()).unwrap();
        let first = f.store.profiles().unwrap()[0].clone();
        backup["profiles"][0]["content"] = json!("{\"changed\":1}");
        // The font fails to decode only after the profiles were written.
        backup["terminal"]["font"] = json!({"name": "x.ttf", "content": "AAAA"});
        let before = f.store.profiles().unwrap();
        struct BadFont(Platform);
        impl Kernel for BadFont {
            fn call(&mut self, method: &str, args: Value) -> Result<Value, String> {
                if method == "terminal.fontValid" {
                    return Ok(json!(false));
                }
                self.0.call(method, args)
            }
        }
        let error = restore(&f.store, &mut BadFont(platform()), &backup, "all", "Override", &f.dirs, "t").unwrap_err();
        assert_eq!(error, "Invalid font file");
        assert!(!f.store.in_transaction());
        assert_eq!(f.store.profiles().unwrap(), before);
        assert_eq!(std::fs::read_to_string(&first.path).unwrap(), "{\"a\":1}");
        assert!(std::fs::read_dir(f.dirs.files.join("fonts")).unwrap().next().is_none());
        assert!(std::fs::read_dir(&f.dirs.cache).unwrap().next().is_none(), "copies removed");
    }

    #[test]
    fn ui_writes_are_rejected_while_a_restore_holds_the_store() {
        let f = fixture();
        f.store.begin().unwrap();
        assert!(terminal::set(&f.store, "fontSize", &json!(12), None).is_err());
        f.store.rollback().unwrap();
    }
}
