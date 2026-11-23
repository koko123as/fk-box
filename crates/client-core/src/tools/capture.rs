//! The capture (MITM) settings file: `<filesDir>/capture/settings.json`,
//! private to the app and independent of profiles and their revisions.
//!
//! This used to be the Android host's `CaptureSettings`. The file keeps the
//! same name, format and recovery rules (Android `AtomicFile`: a legacy
//! `.bak` wins over the base file, a stale `.new` is dropped), so a
//! downgraded app reads it unchanged. Validation and normalisation are the
//! capture engine's (Go `ValidateUICaptureConfig`, reached through the
//! platform's [`Kernel`] as `capture.validate`). The MITM certificate and its
//! Keystore key stay with the platform.

use std::fs::{self, File};
use std::io::Read;
use std::path::{Path, PathBuf};

use serde_json::{json, Map, Value};

use crate::content::{self, Kernel};

/// The settings file inside the capture directory.
pub const FILE: &str = "settings.json";
/// The retired certificate record, deleted on first use.
const RETIRED_CA: &str = "ca.json";
/// How large a file the one-time upgrade of an old format reads.
pub const MIGRATION_LIMIT: u64 = 4 * 1024 * 1024;
/// How large the settings may be.
pub const LIMIT: u64 = 64 * 1024;
/// Options of format 1 that format 2 no longer has.
const RETIRED_KEYS: [&str; 13] = [
    "blockQUIC",
    "rewriteEnabled",
    "headerRewrite",
    "urlRewrite",
    "bodyRewrite",
    "mapLocal",
    "scripts",
    "captureNetwork",
    "captureTlsKeys",
    "includeHosts",
    "excludeHosts",
    "tlsLibraries",
    "tlsOffsets",
];

fn sibling(base: &Path, suffix: &str) -> PathBuf {
    let mut name = base.as_os_str().to_owned();
    name.push(suffix);
    PathBuf::from(name)
}

fn io(path: &Path) -> impl Fn(std::io::Error) -> String + '_ {
    move |e| format!("{}: {e}", path.display())
}

/// Recovers an interrupted `AtomicFile` write the way Android does: a
/// legacy backup replaces the base file, a new file next to an intact base
/// is dropped.
fn recover(base: &Path) -> Result<(), String> {
    let legacy = sibling(base, ".bak");
    if legacy.exists() {
        fs::rename(&legacy, base).map_err(io(base))?;
    }
    let new = sibling(base, ".new");
    if new.exists() && base.exists() {
        let _ = fs::remove_file(&new);
    }
    Ok(())
}

/// The file as text, None when there is none. The size is checked on the
/// recovered file, so a small base cannot hide a large backup.
fn read(base: &Path, limit: u64) -> Result<Option<String>, String> {
    recover(base)?;
    let mut file = match File::open(base) {
        Ok(file) => file,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(e) => return Err(io(base)(e)),
    };
    if file.metadata().map_err(io(base))?.len() > limit {
        return Err(format!("Capture settings exceed {} KiB", limit / 1024));
    }
    let mut bytes = Vec::new();
    file.read_to_end(&mut bytes).map_err(io(base))?;
    Ok(Some(String::from_utf8_lossy(&bytes).into_owned()))
}

fn validate(kernel: &mut dyn Kernel, content: &str) -> Result<String, String> {
    kernel
        .call("capture.validate", json!({ "content": content }))?
        .as_str()
        .map(str::to_owned)
        .ok_or_else(|| "Invalid capture settings".to_string())
}

fn version(value: &Map<String, Value>) -> i64 {
    match value.get("version") {
        Some(Value::Number(n)) => n.as_i64().or_else(|| n.as_f64().map(|f| f as i64)).unwrap_or(0),
        Some(Value::String(s)) => s.trim().parse::<f64>().map(|f| f as i64).unwrap_or(0),
        _ => 0,
    }
}

/// The stored text ("" when there is none), after the one-time upgrade: the
/// retired certificate record is deleted, and an old format loses its
/// retired options and has HTTPS interception turned off (passive capture
/// consent never enables it).
fn raw(dir: &Path, kernel: &mut dyn Kernel) -> Result<String, String> {
    let retired = dir.join(RETIRED_CA);
    for path in [sibling(&retired, ".bak"), sibling(&retired, ".new"), retired] {
        content::remove(&path)?;
    }
    let base = dir.join(FILE);
    if let Some(previous) = read(&base, MIGRATION_LIMIT)? {
        let mut value = match serde_json::from_str::<Value>(&previous) {
            Ok(Value::Object(map)) => map,
            Ok(_) => return Err("Capture settings are not a JSON object".into()),
            Err(e) => return Err(format!("Capture settings: {e}")),
        };
        if version(&value) != 2 {
            for key in RETIRED_KEYS {
                value.remove(key);
            }
            value.insert("version".into(), json!(2));
            value.insert("https".into(), json!(false));
            let upgraded = validate(kernel, &Value::Object(value).to_string())?;
            content::write_atomic(&base, &upgraded)?;
        }
    }
    Ok(read(&base, LIMIT)?.unwrap_or_default())
}

/// The settings as the engine normalises them (`{}` when unset).
pub fn config(dir: &Path, kernel: &mut dyn Kernel) -> Result<String, String> {
    let raw = raw(dir, kernel)?;
    validate(kernel, if raw.is_empty() { "{}" } else { &raw })
}

/// `{"revision": …, "config": {…}}`: the revision of the stored text (an
/// edit sends it back) and the normalised settings.
pub fn snapshot(dir: &Path, kernel: &mut dyn Kernel) -> Result<Value, String> {
    let raw = raw(dir, kernel)?;
    let config = validate(kernel, if raw.is_empty() { "{}" } else { &raw })?;
    let config: Value = serde_json::from_str(&config).map_err(|e| format!("Capture settings: {e}"))?;
    Ok(json!({ "revision": content::revision(&raw), "config": config }))
}

/// Saves `content` when the stored text is still at `expected_revision`;
/// the engine validates it first, and a refused save leaves the file as it
/// was. Returns the new [`snapshot`].
pub fn save(dir: &Path, kernel: &mut dyn Kernel, expected_revision: &str, content: &str) -> Result<Value, String> {
    if content::revision(&raw(dir, kernel)?) != expected_revision {
        return Err("Capture settings changed; reload them before saving".into());
    }
    let validated = validate(kernel, content)?;
    content::write_atomic(&dir.join(FILE), &validated)?;
    snapshot(dir, kernel)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::content::tests::tempdir;

    /// Stands in for the engine: refuses retired options and anything that
    /// is not an object, and writes the object back compactly.
    #[derive(Default)]
    struct Engine {
        calls: usize,
    }

    impl Kernel for Engine {
        fn call(&mut self, method: &str, args: Value) -> Result<Value, String> {
            assert_eq!(method, "capture.validate");
            self.calls += 1;
            let text = args["content"].as_str().unwrap();
            let Ok(Value::Object(map)) = serde_json::from_str::<Value>(text) else { return Err("invalid capture settings".into()) };
            if let Some(key) = RETIRED_KEYS.iter().find(|k| map.contains_key(**k) && **k != "blockQUIC") {
                return Err(format!("retired option {key}"));
            }
            Ok(Value::String(Value::Object(map).to_string()))
        }
    }

    #[test]
    fn an_old_format_is_upgraded_once_and_https_turned_off() {
        let dir = tempdir::Dir::new();
        fs::write(dir.0.join(FILE), r#"{"https":true,"scripts":[],"captureNetwork":true,"includeHosts":["a"],"ports":[8443]}"#).unwrap();
        fs::write(dir.0.join(RETIRED_CA), "retired").unwrap();
        let mut engine = Engine::default();
        let snap = snapshot(&dir.0, &mut engine).unwrap();
        assert_eq!(snap["config"], json!({"https": false, "ports": [8443], "version": 2}));
        assert!(!dir.0.join(RETIRED_CA).exists());
        let stored = fs::read_to_string(dir.0.join(FILE)).unwrap();
        assert!(!stored.contains("captureNetwork") && !stored.contains("scripts"));
        assert_eq!(snap["revision"], json!(content::revision(&stored)));
        // Already format 2: read as it is.
        assert_eq!(snapshot(&dir.0, &mut engine).unwrap(), snap);
        assert_eq!(fs::read_to_string(dir.0.join(FILE)).unwrap(), stored);
    }

    #[test]
    fn saves_check_the_revision_and_keep_the_file_on_a_refusal() {
        let dir = tempdir::Dir::new();
        let mut engine = Engine::default();
        let initial = snapshot(&dir.0, &mut engine).unwrap();
        assert_eq!(initial["config"], json!({}));
        assert_eq!(initial["revision"], json!(content::revision("")));
        let revision = initial["revision"].as_str().unwrap().to_string();
        let saved = save(&dir.0, &mut engine, &revision, r#"{"version":2,"https":true}"#).unwrap();
        assert_ne!(saved["revision"], initial["revision"]);
        assert_eq!(save(&dir.0, &mut engine, &revision, "{}").unwrap_err(), "Capture settings changed; reload them before saving");
        let before = fs::read_to_string(dir.0.join(FILE)).unwrap();
        let current = saved["revision"].as_str().unwrap();
        assert!(save(&dir.0, &mut engine, current, r#"{"scripts":[]}"#).is_err());
        assert_eq!(fs::read_to_string(dir.0.join(FILE)).unwrap(), before);
        assert_eq!(config(&dir.0, &mut engine).unwrap(), before);
    }

    #[test]
    fn an_interrupted_write_is_recovered_and_still_size_checked() {
        let dir = tempdir::Dir::new();
        let base = dir.0.join(FILE);
        let backup = sibling(&base, ".bak");
        fs::write(&backup, r#"{"version":2,"ports":[1]}"#).unwrap();
        let mut engine = Engine::default();
        assert_eq!(snapshot(&dir.0, &mut engine).unwrap()["config"], json!({"version": 2, "ports": [1]}));
        assert!(!backup.exists() && base.exists());
        // A stale new file next to an intact base is dropped.
        fs::write(sibling(&base, ".new"), "{").unwrap();
        snapshot(&dir.0, &mut engine).unwrap();
        assert!(!sibling(&base, ".new").exists());
        // A small base must not bypass the limit on the recovered backup.
        fs::write(&base, "{}").unwrap();
        fs::write(&backup, " ".repeat(MIGRATION_LIMIT as usize + 1)).unwrap();
        assert!(snapshot(&dir.0, &mut engine).unwrap_err().contains("exceed"));
        // Format 2 but over the settings limit.
        fs::write(&base, format!(r#"{{"version":2,"pad":"{}"}}"#, "x".repeat(LIMIT as usize))).unwrap();
        assert_eq!(snapshot(&dir.0, &mut engine).unwrap_err(), "Capture settings exceed 64 KiB");
    }
}
