//! The terminal (Ghostty) settings: their values, validation and writes, and
//! their part of a backup.
//!
//! The values live in the store's `settings` table under the keys and kinds
//! the Android app always used (`SettingsKey.TAILSCALE_SSH_*`), read with the
//! same defaults. What needs the platform is asked through the kernel bridge
//! ([`Kernel`]): the bundled theme names (`terminal.themes`), the imported
//! font files (`terminal.fonts`) and whether a font file loads
//! (`terminal.fontValid`). Picking and importing a font file stays with the
//! platform.

use std::path::{Path, PathBuf};

use serde_json::{json, Map, Value};

use crate::codec;
use crate::content::Kernel;
use crate::service_settings::Written;
use crate::storage::{SettingValue, Store};

pub const LIGHT_THEME_KEY: &str = "tailscale_ssh_light_theme";
pub const DARK_THEME_KEY: &str = "tailscale_ssh_dark_theme";
pub const FONT_FAMILY_KEY: &str = "tailscale_ssh_font_family";
pub const FONT_SIZE_KEY: &str = "tailscale_ssh_font_size";
pub const FONT_PATH_KEY: &str = "tailscale_ssh_custom_font_path";
pub const LIGHT_CONFIG_KEY: &str = "tailscale_ssh_light_config";
pub const DARK_CONFIG_KEY: &str = "tailscale_ssh_dark_config";
pub const FONT_FOLLOW_THEME_KEY: &str = "tailscale_ssh_font_follow_theme";

/// The defaults the Android settings cache used (Settings.kt).
pub const DEFAULT_LIGHT_THEME: &str = "Alabaster";
pub const DEFAULT_DARK_THEME: &str = "Afterglow";
pub const DEFAULT_FONT_SIZE: i32 = 14;

pub const MAX_CONFIG_CHARS: usize = 256 * 1024;
pub const MAX_FONT_BYTES: usize = 16 * 1024 * 1024;
const MAX_FONT_BASE64: usize = 24 * 1024 * 1024;
pub const FONT_EXTENSIONS: [&str; 4] = ["ttf", "otf", "ttc", "otc"];

/// The settings a backup carries, without the font.
const BACKUP_KEYS: [&str; 7] = ["lightTheme", "darkTheme", "lightConfig", "darkConfig", "fontFollowTheme", "fontFamily", "fontSize"];

/// The keys only the store holds: setting them needs nothing from the
/// platform (no theme list, no font file).
pub const STORE_ONLY_KEYS: [&str; 5] = ["lightConfig", "darkConfig", "fontFollowTheme", "fontSize", "fontFamily"];

fn text(store: &Store, key: &str, default: &str) -> Result<String, String> {
    Ok(match store.setting(key).map_err(|e| e.0)? {
        Some(SettingValue::String(v)) => v,
        _ => default.to_string(),
    })
}

/// The settings by wire name (Kotlin `BoxTerminalSettings.snapshot` without
/// the platform's default theme names).
pub fn snapshot(store: &Store) -> Result<Map<String, Value>, String> {
    let follow = match store.setting(FONT_FOLLOW_THEME_KEY).map_err(|e| e.0)? {
        Some(SettingValue::Bool(v)) => v,
        _ => true,
    };
    let size = match store.setting(FONT_SIZE_KEY).map_err(|e| e.0)? {
        Some(SettingValue::Long(v)) => v as i32,
        _ => DEFAULT_FONT_SIZE,
    };
    let mut out = Map::new();
    out.insert("lightTheme".into(), text(store, LIGHT_THEME_KEY, DEFAULT_LIGHT_THEME)?.into());
    out.insert("darkTheme".into(), text(store, DARK_THEME_KEY, DEFAULT_DARK_THEME)?.into());
    out.insert("lightConfig".into(), text(store, LIGHT_CONFIG_KEY, "")?.into());
    out.insert("darkConfig".into(), text(store, DARK_CONFIG_KEY, "")?.into());
    out.insert("fontFollowTheme".into(), follow.into());
    out.insert("fontFamily".into(), text(store, FONT_FAMILY_KEY, "")?.into());
    out.insert("fontPath".into(), text(store, FONT_PATH_KEY, "")?.into());
    out.insert("fontSize".into(), size.into());
    Ok(out)
}

fn string_list(value: Value, method: &str) -> Result<Vec<String>, String> {
    serde_json::from_value(value).map_err(|e| format!("kernel {method}: unexpected result: {e}"))
}

fn theme_known(kernel: &mut dyn Kernel, name: &str, dark: bool) -> Result<bool, String> {
    if name.is_empty() {
        return Ok(true);
    }
    Ok(string_list(kernel.call("terminal.themes", json!({ "dark": dark }))?, "terminal.themes")?.iter().any(|n| n == name))
}

fn font_imported(kernel: &mut dyn Kernel, path: &str) -> Result<bool, String> {
    Ok(string_list(kernel.call("terminal.fonts", json!({}))?, "terminal.fonts")?.iter().any(|p| p == path))
}

fn font_size(value: &Value) -> Option<i32> {
    let v = value.as_f64()?;
    (v.fract() == 0.0 && (8.0..=48.0).contains(&v)).then_some(v as i32)
}

fn s(value: &str) -> SettingValue {
    SettingValue::String(value.to_string())
}

/// The entries one setting (`key` as in a backup) writes, after checking it;
/// `kernel` is needed for a theme or an imported font, nothing else.
fn need<'a>(kernel: Option<&'a mut dyn Kernel>, key: &str) -> Result<&'a mut dyn Kernel, String> {
    kernel.ok_or_else(|| format!("Setting {key} needs the platform"))
}

pub fn entries(key: &str, value: &Value, kernel: Option<&mut dyn Kernel>) -> Result<Vec<(String, SettingValue)>, String> {
    Ok(match key {
        "lightTheme" | "darkTheme" => {
            let name = value.as_str().ok_or("Invalid theme")?;
            let dark = key == "darkTheme";
            if !name.is_empty() && !theme_known(need(kernel, key)?, name, dark)? {
                return Err("Unknown Ghostty theme".into());
            }
            vec![((if dark { DARK_THEME_KEY } else { LIGHT_THEME_KEY }).to_string(), s(name))]
        }
        "lightConfig" | "darkConfig" => {
            let config = value.as_str().ok_or("Invalid configuration")?;
            if config.encode_utf16().count() > MAX_CONFIG_CHARS {
                return Err("Configuration is too large".into());
            }
            vec![((if key == "darkConfig" { DARK_CONFIG_KEY } else { LIGHT_CONFIG_KEY }).to_string(), s(config))]
        }
        "fontFollowTheme" => vec![(FONT_FOLLOW_THEME_KEY.into(), SettingValue::Bool(value.as_bool().ok_or("Invalid font option")?))],
        "fontSize" => {
            if !value.is_number() {
                return Err("Invalid font size".into());
            }
            let size = font_size(value).ok_or("Font size must be 8–48")?;
            vec![(FONT_SIZE_KEY.into(), SettingValue::Long(i64::from(size)))]
        }
        "fontFamily" => {
            let name = value.as_str().filter(|n| n.encode_utf16().count() <= 128).ok_or("Invalid font")?;
            vec![(FONT_FAMILY_KEY.into(), s(name)), (FONT_PATH_KEY.into(), s("")), (FONT_FOLLOW_THEME_KEY.into(), SettingValue::Bool(false))]
        }
        "fontPath" => {
            let path = value.as_str().ok_or("Invalid font")?;
            if !font_imported(need(kernel, key)?, path)? {
                return Err("Unknown imported font".into());
            }
            vec![(FONT_PATH_KEY.into(), s(path)), (FONT_FAMILY_KEY.into(), s("")), (FONT_FOLLOW_THEME_KEY.into(), SettingValue::Bool(false))]
        }
        _ => return Err("Unknown terminal setting".into()),
    })
}

fn writable(store: &Store) -> Result<(), String> {
    if store.in_transaction() {
        Err("Wait for the restore to finish".into())
    } else {
        Ok(())
    }
}

/// Writes one setting and returns what was written (for the platform's
/// settings cache). Rejected while a backup restore holds the store.
pub fn set(store: &Store, key: &str, value: &Value, kernel: Option<&mut dyn Kernel>) -> Result<Vec<Written>, String> {
    writable(store)?;
    let entries = entries(key, value, kernel)?;
    store.put_settings(&entries).map_err(|e| e.0)?;
    Ok(entries.iter().map(|(k, v)| Written::of(k, v)).collect())
}

/// Records a font the platform just imported as the one in use.
pub fn use_imported_font(store: &Store, path: &str) -> Result<Vec<Written>, String> {
    writable(store)?;
    let entries = vec![(FONT_PATH_KEY.to_string(), s(path)), (FONT_FAMILY_KEY.into(), s("")), (FONT_FOLLOW_THEME_KEY.into(), SettingValue::Bool(false))];
    store.put_settings(&entries).map_err(|e| e.0)?;
    Ok(entries.iter().map(|(k, v)| Written::of(k, v)).collect())
}

/// Forgets `path` as the font in use after the platform deleted it.
pub fn font_deleted(store: &Store, path: &str) -> Result<Vec<Written>, String> {
    if text(store, FONT_PATH_KEY, "")? != path {
        return Ok(Vec::new());
    }
    writable(store)?;
    let entries = vec![(FONT_PATH_KEY.to_string(), s(""))];
    store.put_settings(&entries).map_err(|e| e.0)?;
    Ok(entries.iter().map(|(k, v)| Written::of(k, v)).collect())
}

/// The terminal part of a backup: the settings and, when an imported font
/// is in use, the font file itself (Base64).
pub fn backup(store: &Store) -> Result<Map<String, Value>, String> {
    let mut all = snapshot(store)?;
    let path = all.get("fontPath").and_then(Value::as_str).unwrap_or("").to_string();
    all.retain(|k, _| BACKUP_KEYS.contains(&k.as_str()));
    if !path.is_empty() {
        let file = Path::new(&path);
        let size = std::fs::metadata(file).ok().filter(|m| m.is_file()).map(|m| m.len());
        if !size.is_some_and(|s| s <= MAX_FONT_BYTES as u64) {
            return Err("Imported terminal font is unavailable or too large".into());
        }
        let bytes = std::fs::read(file).map_err(|_| "Imported terminal font is unavailable or too large".to_string())?;
        let name = file.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
        all.insert("font".into(), json!({ "name": name, "content": codec::base64_encode(&bytes) }));
    }
    Ok(all)
}

fn extension(name: &str) -> String {
    name.rsplit_once('.').map(|(_, e)| e).unwrap_or(name).to_lowercase()
}

/// The decoded font of a backup's terminal part.
fn font_bytes(font: &Map<String, Value>) -> Result<Vec<u8>, String> {
    let content = font.get("content").and_then(Value::as_str).ok_or("Invalid terminal font content")?;
    if content.len() > MAX_FONT_BASE64 {
        return Err("Terminal font exceeds 16 MiB".into());
    }
    let bytes = codec::base64_decode(content)?;
    if bytes.len() > MAX_FONT_BYTES {
        return Err("Terminal font exceeds 16 MiB".into());
    }
    Ok(bytes)
}

/// Checks a backup's terminal part (Kotlin `validateBackup`).
pub fn validate_backup(value: &Map<String, Value>, kernel: &mut dyn Kernel) -> Result<(), String> {
    let has_all = BACKUP_KEYS.iter().all(|k| value.contains_key(*k));
    let no_other = value.keys().all(|k| BACKUP_KEYS.contains(&k.as_str()) || k == "font");
    if !has_all || !no_other {
        return Err("Invalid terminal backup".into());
    }
    for key in ["lightTheme", "darkTheme"] {
        let name = value[key].as_str().ok_or("Invalid terminal theme")?;
        if !theme_known(kernel, name, key == "darkTheme")? {
            return Err("Unknown Ghostty theme".into());
        }
    }
    for key in ["lightConfig", "darkConfig"] {
        if !value[key].as_str().is_some_and(|c| c.encode_utf16().count() <= MAX_CONFIG_CHARS) {
            return Err("Invalid terminal configuration".into());
        }
    }
    if !value["fontFollowTheme"].is_boolean() || !value["fontFamily"].as_str().is_some_and(|f| f.encode_utf16().count() <= 128) {
        return Err("Invalid terminal font options".into());
    }
    if !value["fontSize"].is_number() {
        return Err("Invalid terminal font size".into());
    }
    font_size(&value["fontSize"]).ok_or("Font size must be 8–48")?;
    if let Some(font) = value.get("font") {
        let font = font.as_object().filter(|f| f.len() == 2 && f.contains_key("name") && f.contains_key("content")).ok_or("Invalid terminal font backup")?;
        let name = font["name"].as_str().ok_or("Invalid terminal font name")?;
        let length = name.encode_utf16().count();
        if !(1..=128).contains(&length)
            || name.chars().any(|c| c == '/' || c == '\\' || (c as u32) < 32)
            || !FONT_EXTENSIONS.contains(&extension(name).as_str())
        {
            return Err("Invalid terminal font name".into());
        }
        font_bytes(font)?;
    }
    Ok(())
}

/// The setting entries of a terminal snapshot (backup or saved state) with
/// `font_path` as the font in use.
pub fn state_entries(value: &Map<String, Value>, font_path: &str) -> Result<Vec<(String, SettingValue)>, String> {
    let str_of = |key: &str| value.get(key).and_then(Value::as_str).map(s).ok_or_else(|| "Invalid terminal backup".to_string());
    Ok(vec![
        (LIGHT_THEME_KEY.into(), str_of("lightTheme")?),
        (DARK_THEME_KEY.into(), str_of("darkTheme")?),
        (LIGHT_CONFIG_KEY.into(), str_of("lightConfig")?),
        (DARK_CONFIG_KEY.into(), str_of("darkConfig")?),
        (FONT_FOLLOW_THEME_KEY.into(), SettingValue::Bool(value.get("fontFollowTheme").and_then(Value::as_bool).ok_or("Invalid terminal backup")?)),
        (FONT_FAMILY_KEY.into(), str_of("fontFamily")?),
        (FONT_PATH_KEY.into(), s(font_path)),
        (FONT_SIZE_KEY.into(), SettingValue::Long(i64::from(value.get("fontSize").and_then(font_size).ok_or("Invalid terminal backup")?))),
    ])
}

/// Writes a backup's font into `fonts_dir` (a new file, checked by the
/// platform) and returns it; None when the backup carries no font.
pub fn restore_font(value: &Map<String, Value>, fonts_dir: &Path, kernel: &mut dyn Kernel) -> Result<Option<PathBuf>, String> {
    let Some(font) = value.get("font").and_then(Value::as_object) else { return Ok(None) };
    let name = font.get("name").and_then(Value::as_str).ok_or("Invalid terminal font name")?;
    let ext = extension(name);
    let stem: String = name.rsplit_once('.').map(|(s, _)| s).unwrap_or(name).chars().take(40).collect();
    std::fs::create_dir_all(fonts_dir).map_err(|e| format!("Unable to write font: {e}"))?;
    let file = fonts_dir.join(format!("{stem}-{}.{ext}", &codec::random_uuid()[..8]));
    let result = (|| {
        std::fs::write(&file, font_bytes(font)?).map_err(|e| format!("Unable to write font: {e}"))?;
        let ok = kernel.call("terminal.fontValid", json!({ "path": file.to_string_lossy() }))?;
        if ok.as_bool() != Some(true) {
            return Err("Invalid font file".to_string());
        }
        Ok(())
    })();
    match result {
        Ok(()) => Ok(Some(file)),
        Err(error) => {
            let _ = std::fs::remove_file(&file);
            Err(error)
        }
    }
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;

    /// Themes "A" and the default (light), "B" and the default (dark);
    /// imported font "/f/x.ttf"; every existing font file loads.
    pub(crate) struct Ghostty;

    impl Kernel for Ghostty {
        fn call(&mut self, method: &str, args: Value) -> Result<Value, String> {
            match method {
                "terminal.themes" => Ok(if args["dark"] == json!(true) { json!(["B", DEFAULT_DARK_THEME]) } else { json!(["A", DEFAULT_LIGHT_THEME]) }),
                "terminal.fonts" => Ok(json!(["/f/x.ttf"])),
                "terminal.fontValid" => Ok(json!(std::fs::metadata(args["path"].as_str().unwrap()).is_ok())),
                _ => Err(format!("unexpected {method}")),
            }
        }
    }

    #[test]
    fn snapshot_has_the_kotlin_defaults() {
        let store = Store::open_in_memory().unwrap();
        let snap = snapshot(&store).unwrap();
        assert_eq!(
            Value::Object(snap),
            json!({"lightTheme": "Alabaster", "darkTheme": "Afterglow", "lightConfig": "", "darkConfig": "", "fontFollowTheme": true, "fontFamily": "", "fontPath": "", "fontSize": 14})
        );
        store.put_setting(FONT_SIZE_KEY, &SettingValue::String("x".into())).unwrap();
        assert_eq!(snapshot(&store).unwrap()["fontSize"], json!(14));
    }

    #[test]
    fn set_checks_and_writes_like_kotlin() {
        let store = Store::open_in_memory().unwrap();
        let mut k = Ghostty;
        assert_eq!(set(&store, "lightTheme", &json!("A"), Some(&mut k)).unwrap()[0].key, LIGHT_THEME_KEY);
        assert_eq!(set(&store, "darkTheme", &json!("A"), Some(&mut k)).unwrap_err(), "Unknown Ghostty theme");
        assert!(set(&store, "darkTheme", &json!("B"), None).is_err());
        assert_eq!(set(&store, "fontSize", &json!(7), None).unwrap_err(), "Font size must be 8–48");
        assert_eq!(set(&store, "fontSize", &json!("9"), None).unwrap_err(), "Invalid font size");
        set(&store, "fontSize", &json!(20.0), None).unwrap();
        set(&store, "fontPath", &json!("/f/x.ttf"), Some(&mut k)).unwrap();
        assert_eq!(snapshot(&store).unwrap()["fontFollowTheme"], json!(false));
        assert_eq!(font_deleted(&store, "/f/x.ttf").unwrap().len(), 1);
        let written = set(&store, "fontFamily", &json!("monospace"), None).unwrap();
        assert_eq!(written.len(), 3);
        let snap = snapshot(&store).unwrap();
        assert_eq!((snap["fontSize"].clone(), snap["fontFamily"].clone(), snap["fontPath"].clone()), (json!(20), json!("monospace"), json!("")));
        assert!(set(&store, "lightConfig", &json!("x".repeat(MAX_CONFIG_CHARS + 1)), None).is_err());
        assert_eq!(set(&store, "nope", &json!(1), None).unwrap_err(), "Unknown terminal setting");
        store.begin().unwrap();
        assert!(set(&store, "fontSize", &json!(10), None).is_err());
        store.rollback().unwrap();
    }

    #[test]
    fn backup_validation_and_font_restore() {
        let dir = crate::legacy::tests::scratch("terminal");
        let store = Store::open_in_memory().unwrap();
        let font = dir.join("Mono.ttf");
        std::fs::write(&font, b"font-bytes").unwrap();
        store.put_setting(FONT_PATH_KEY, &s(&font.to_string_lossy())).unwrap();
        let part = backup(&store).unwrap();
        assert_eq!(part["font"]["name"], json!("Mono.ttf"));
        assert!(!part.contains_key("fontPath"));
        let mut k = Ghostty;
        validate_backup(&part, &mut k).unwrap();
        let restored = restore_font(&part, &dir.join("fonts"), &mut k).unwrap().unwrap();
        assert_eq!(std::fs::read(&restored).unwrap(), b"font-bytes");
        assert!(restored.file_name().unwrap().to_string_lossy().starts_with("Mono-"));
        let mut bad = part.clone();
        bad.insert("lightTheme".into(), json!("Z"));
        assert_eq!(validate_backup(&bad, &mut k).unwrap_err(), "Unknown Ghostty theme");
        let mut bad = part.clone();
        bad.insert("font".into(), json!({"name": "a.exe", "content": ""}));
        assert_eq!(validate_backup(&bad, &mut k).unwrap_err(), "Invalid terminal font name");
        let mut bad = part.clone();
        bad.insert("extra".into(), json!(1));
        assert_eq!(validate_backup(&bad, &mut k).unwrap_err(), "Invalid terminal backup");
        let entries = state_entries(&part, "/p").unwrap();
        assert_eq!(entries.len(), 8);
        std::fs::remove_file(&font).unwrap();
        assert!(backup(&store).is_err());
        let _ = std::fs::remove_dir_all(dir);
    }
}
