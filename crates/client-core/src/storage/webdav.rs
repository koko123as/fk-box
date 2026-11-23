//! The WebDAV backup account and its upload/download flow.
//!
//! The account is one JSON object (`uri`, `user`, `password`, `fileName`) in
//! the store's `preferences` table under [`PREF_KEY`], with the password as
//! the platform encrypted it (Android: Keystore AES-GCM, `enc1:` prefix,
//! `utils/SecretBox.kt`); an account imported from before that still holds
//! it in plain text and is encrypted on first read. The Android app kept it
//! in SharedPreferences `bbox_webdav`, imported once
//! ([`crate::legacy::migrate_webdav`]).
//!
//! The platform does the parts that need it through the kernel bridge
//! ([`Kernel`]): `secret.encrypt`/`secret.decrypt` and
//! `webdav.request` (the HTTP request itself, in libbox with the system
//! certificates).

use serde::Deserialize;
use serde_json::{json, Map, Value};

use crate::content::Kernel;
use crate::storage::Store;

/// The preferences-table key of the account.
pub const PREF_KEY: &str = "webdav";
/// The remote directory backups are kept in.
pub const DIRECTORY: &str = "B-box/";
/// The prefix the platform's encrypted values carry.
pub const ENCRYPTED_PREFIX: &str = "enc1:";
pub const MAX_BYTES: usize = 64 * 1024 * 1024;

const KEYS: [&str; 4] = ["uri", "user", "password", "fileName"];

fn utf16_len(value: &str) -> usize {
    value.encode_utf16().count()
}

/// The authority of an http(s) address and the rest after it.
fn split_authority(address: &str) -> Option<(&str, &str)> {
    let rest = address.strip_prefix("https://").or_else(|| address.strip_prefix("http://"))?;
    let end = rest.find(['/', '?', '#']).unwrap_or(rest.len());
    Some(rest.split_at(end))
}

/// Checks an account as the Kotlin `BoxWebDAV.validate` did (`java.net.URI`
/// rules: scheme exactly http/https, a server host, a port in range, no user
/// info, query or fragment; credentials and file name limits).
pub fn validate(value: &Map<String, Value>) -> Result<(), String> {
    if value.len() != KEYS.len() || !KEYS.iter().all(|k| value.contains_key(*k)) {
        return Err("Invalid WebDAV account".into());
    }
    let address = value["uri"].as_str().ok_or("WebDAV address is required")?;
    let invalid = || "Enter a WebDAV HTTP or HTTPS directory address".to_string();
    if utf16_len(address) > 4096 || !crate::geo::valid_url(address) {
        return Err(invalid());
    }
    let (authority, tail) = split_authority(address).ok_or_else(invalid)?;
    if authority.contains('@') || tail.contains(['?', '#']) {
        return Err(invalid());
    }
    let port = if authority.starts_with('[') {
        authority.rsplit_once("]:").map(|(_, p)| p)
    } else {
        authority.split_once(':').map(|(_, p)| p)
    };
    if let Some(port) = port.filter(|p| !p.is_empty()) {
        if !port.parse::<u32>().is_ok_and(|p| (1..=65535).contains(&p)) {
            return Err(invalid());
        }
    }
    let short = |key: &str| value[key].as_str().is_some_and(|v| utf16_len(v) <= 4096);
    if !short("user") || !short("password") {
        return Err("Invalid WebDAV credentials".into());
    }
    let file = value["fileName"].as_str().ok_or("A backup file name is required")?;
    if !(1..=128).contains(&utf16_len(file)) || file == "." || file == ".." || file.chars().any(|c| c == '/' || c == '\\' || (c as u32) < 32) {
        return Err("Invalid backup file name".into());
    }
    Ok(())
}

fn percent_encode(out: &mut String, c: char) {
    let mut buffer = [0u8; 4];
    for b in c.encode_utf8(&mut buffer).bytes() {
        out.push_str(&format!("%{b:02X}"));
    }
}

/// `root.resolve(URI(null, null, path, null, null)).toASCIIString()` for a
/// relative `path` under the account's directory address: the address with
/// one trailing slash, then `path` quoted as a URI path (characters outside
/// the unreserved, sub-delimiter, `:`, `@` and `/` sets, `%` included, as
/// UTF-8 escapes). Non-ASCII characters of the address are escaped too.
pub fn resolve(address: &str, path: &str) -> String {
    let mut out = String::with_capacity(address.len() + path.len() + 8);
    for c in address.trim_end_matches('/').chars() {
        if c.is_ascii() {
            out.push(c);
        } else {
            percent_encode(&mut out, c);
        }
    }
    out.push('/');
    for c in path.chars() {
        if c.is_ascii_alphanumeric() || "-._~!$&'()*+,;=:@/".contains(c) {
            out.push(c);
        } else {
            percent_encode(&mut out, c);
        }
    }
    out
}

fn decode_string(value: Value, method: &str) -> Result<String, String> {
    value.as_str().map(str::to_string).ok_or_else(|| format!("kernel {method}: unexpected result"))
}

fn encrypt(kernel: &mut dyn Kernel, plain: &str) -> Result<String, String> {
    decode_string(kernel.call("secret.encrypt", json!({ "value": plain }))?, "secret.encrypt")
}

fn stored(store: &Store) -> Result<Option<Map<String, Value>>, String> {
    let text = store.preference(PREF_KEY).map_err(|e| e.0)?;
    Ok(text.filter(|t| !t.is_empty()).and_then(|t| serde_json::from_str::<Value>(&t).ok()).and_then(|v| v.as_object().cloned()))
}

fn write(store: &Store, value: Option<&Map<String, Value>>) -> Result<(), String> {
    match value {
        Some(value) => store.set_preference(PREF_KEY, &Value::Object(value.clone()).to_string()),
        None => store.remove_preference(PREF_KEY),
    }
    .map_err(|e| format!("Unable to save WebDAV account: {}", e.0))
}

/// The account with its password in plain text (for requests, backups and
/// the settings page), None when there is none. A password still stored in
/// plain text is encrypted now (a failure there is not fatal).
pub fn account(store: &Store, kernel: &mut dyn Kernel) -> Result<Option<Map<String, Value>>, String> {
    let Some(mut value) = stored(store)? else { return Ok(None) };
    let Some(password) = value.get("password").and_then(Value::as_str).map(str::to_string) else { return Ok(Some(value)) };
    if !password.starts_with(ENCRYPTED_PREFIX) {
        if !store.in_transaction() {
            if let Ok(sealed) = encrypt(kernel, &password) {
                let mut sealed_value = value.clone();
                sealed_value.insert("password".into(), sealed.into());
                let _ = write(store, Some(&sealed_value));
            }
        }
        return Ok(Some(value));
    }
    let plain = decode_string(kernel.call("secret.decrypt", json!({ "value": password }))?, "secret.decrypt")?;
    value.insert("password".into(), plain.into());
    Ok(Some(value))
}

/// The stored form of the account (password encrypted), or None.
pub fn raw(store: &Store) -> Result<Option<String>, String> {
    store.preference(PREF_KEY).map_err(|e| e.0)
}

/// Puts back a stored form read by [`raw`] (a rollback).
pub fn put_raw(store: &Store, raw: Option<&str>) -> Result<(), String> {
    match raw {
        Some(text) => store.set_preference(PREF_KEY, text),
        None => store.remove_preference(PREF_KEY),
    }
    .map_err(|e| e.0)
}

/// Saves the account (validated, password encrypted), or removes it when
/// `value` is None.
pub fn save(store: &Store, kernel: &mut dyn Kernel, value: Option<&Map<String, Value>>) -> Result<(), String> {
    let Some(value) = value else { return write(store, None) };
    validate(value)?;
    let mut sealed = value.clone();
    let password = value["password"].as_str().unwrap_or("");
    sealed.insert("password".into(), encrypt(kernel, password)?.into());
    write(store, Some(&sealed))
}

#[derive(Deserialize)]
struct Response {
    status: i64,
    #[serde(default)]
    content: String,
}

fn request(kernel: &mut dyn Kernel, account: &Map<String, Value>, method: &str, path: &str, content: &str, accept_existing: bool) -> Result<String, String> {
    validate(account)?;
    let url = resolve(account["uri"].as_str().unwrap_or(""), path);
    let args = json!({
        "method": method,
        "url": url,
        "user": account["user"],
        "password": account["password"],
        "content": content,
    });
    let response: Response =
        serde_json::from_value(kernel.call("webdav.request", args)?).map_err(|e| format!("kernel webdav.request: unexpected result: {e}"))?;
    if !((200..=299).contains(&response.status) || accept_existing && response.status == 405) {
        return Err(format!("WebDAV {method} failed: HTTP {}", response.status));
    }
    if method == "GET" && response.status != 200 {
        return Err(format!("WebDAV download failed: HTTP {}", response.status));
    }
    Ok(response.content)
}

fn required(store: &Store, kernel: &mut dyn Kernel) -> Result<Map<String, Value>, String> {
    account(store, kernel)?.ok_or_else(|| "Configure a WebDAV account first".to_string())
}

/// Checks that the server answers for the account.
pub fn check(store: &Store, kernel: &mut dyn Kernel) -> Result<(), String> {
    let account = required(store, kernel)?;
    request(kernel, &account, "OPTIONS", "", "", false).map(|_| ())
}

/// Uploads `content` (a backup) as the account's file.
pub fn upload(store: &Store, kernel: &mut dyn Kernel, content: &str) -> Result<(), String> {
    let account = required(store, kernel)?;
    if content.len() > MAX_BYTES {
        return Err("Backup exceeds 64 MiB".into());
    }
    request(kernel, &account, "MKCOL", DIRECTORY, "", true)?;
    let file = format!("{DIRECTORY}{}", account["fileName"].as_str().unwrap_or(""));
    request(kernel, &account, "PUT", &file, content, false).map(|_| ())
}

/// Downloads the account's file.
pub fn download(store: &Store, kernel: &mut dyn Kernel) -> Result<String, String> {
    let account = required(store, kernel)?;
    let file = format!("{DIRECTORY}{}", account["fileName"].as_str().unwrap_or(""));
    request(kernel, &account, "GET", &file, "", false)
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;

    /// Encrypts by prefixing, answers requests from `responses` (method →
    /// status, content) and records them.
    #[derive(Default)]
    pub(crate) struct Dav {
        pub requests: Vec<Value>,
        pub statuses: std::collections::HashMap<String, i64>,
        pub file: String,
    }

    impl Kernel for Dav {
        fn call(&mut self, method: &str, args: Value) -> Result<Value, String> {
            match method {
                "secret.encrypt" => Ok(json!(format!("enc1:{}", args["value"].as_str().unwrap()))),
                "secret.decrypt" => Ok(json!(args["value"].as_str().unwrap().strip_prefix("enc1:").unwrap())),
                "webdav.request" => {
                    let verb = args["method"].as_str().unwrap().to_string();
                    if verb == "PUT" {
                        self.file = args["content"].as_str().unwrap().to_string();
                    }
                    self.requests.push(args);
                    let status = *self.statuses.get(&verb).unwrap_or(&if verb == "MKCOL" { 201 } else { 200 });
                    Ok(json!({ "status": status, "content": if verb == "GET" { self.file.clone() } else { String::new() } }))
                }
                _ => Err(format!("unexpected {method}")),
            }
        }
    }

    pub(crate) fn account_value() -> Map<String, Value> {
        json!({"uri": "https://dav.example/remote.php/dav/files/me/", "user": "me", "password": "pw", "fileName": "fk box.json"})
            .as_object()
            .unwrap()
            .clone()
    }

    #[test]
    fn validation_follows_the_kotlin_rules() {
        validate(&account_value()).unwrap();
        let with = |key: &str, value: Value| {
            let mut a = account_value();
            a.insert(key.into(), value);
            validate(&a)
        };
        for uri in ["ftp://x", "https://u@h/", "https://h/?q", "https://h/#f", "https://h:70000/", "https:///", "https://h_h/"] {
            assert!(with("uri", json!(uri)).is_err(), "{uri}");
        }
        assert!(with("uri", json!("http://h:/")).is_ok());
        assert!(with("uri", json!("http://[::1]:8080/dav")).is_ok());
        for name in ["", ".", "..", "a/b", "a\\b", "a\nb"] {
            assert_eq!(with("fileName", json!(name)).unwrap_err(), "Invalid backup file name");
        }
        assert_eq!(with("password", json!(1)).unwrap_err(), "Invalid WebDAV credentials");
        let mut extra = account_value();
        extra.insert("x".into(), json!(1));
        assert_eq!(validate(&extra).unwrap_err(), "Invalid WebDAV account");
    }

    #[test]
    fn resolve_matches_java_uri() {
        assert_eq!(resolve("https://h/dav//", "B-box/fk box.json"), "https://h/dav/B-box/fk%20box.json");
        assert_eq!(resolve("https://h", ""), "https://h/");
        assert_eq!(resolve("https://h/é", "B-box/a%#?ü"), "https://h/%C3%A9/B-box/a%25%23%3F%C3%BC");
    }

    #[test]
    fn account_is_stored_encrypted_and_flows_use_it() {
        let store = Store::open_in_memory().unwrap();
        let mut dav = Dav::default();
        assert_eq!(check(&store, &mut dav).unwrap_err(), "Configure a WebDAV account first");
        save(&store, &mut dav, Some(&account_value())).unwrap();
        assert!(raw(&store).unwrap().unwrap().contains("enc1:pw"));
        assert_eq!(account(&store, &mut dav).unwrap().unwrap()["password"], json!("pw"));
        upload(&store, &mut dav, "{\"format\":\"bbox-1\"}").unwrap();
        assert_eq!(dav.requests[0]["method"], json!("MKCOL"));
        assert_eq!(dav.requests[1]["url"], json!("https://dav.example/remote.php/dav/files/me/B-box/fk%20box.json"));
        assert_eq!(download(&store, &mut dav).unwrap(), "{\"format\":\"bbox-1\"}");
        dav.statuses.insert("MKCOL".into(), 405);
        upload(&store, &mut dav, "x").unwrap();
        dav.statuses.insert("GET".into(), 204);
        assert_eq!(download(&store, &mut dav).unwrap_err(), "WebDAV download failed: HTTP 204");
        dav.statuses.insert("OPTIONS".into(), 401);
        assert_eq!(check(&store, &mut dav).unwrap_err(), "WebDAV OPTIONS failed: HTTP 401");
        save(&store, &mut dav, None).unwrap();
        assert_eq!(account(&store, &mut dav).unwrap(), None);
    }

    #[test]
    fn a_plain_password_is_encrypted_on_first_read() {
        let store = Store::open_in_memory().unwrap();
        store.set_preference(PREF_KEY, &Value::Object(account_value()).to_string()).unwrap();
        let mut dav = Dav::default();
        assert_eq!(account(&store, &mut dav).unwrap().unwrap()["password"], json!("pw"));
        assert!(raw(&store).unwrap().unwrap().contains("enc1:pw"));
        assert_eq!(account(&store, &mut dav).unwrap().unwrap()["password"], json!("pw"));
    }
}
