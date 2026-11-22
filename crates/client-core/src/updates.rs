//! The update check: fk-box's own release metadata (prereleases included),
//! compared by version code with the running build.
//!
//! Two sources are read and the higher version code wins:
//! - the latest release's metadata asset through
//!   `github.com/…/releases/latest/download`, a plain redirect to the asset
//!   origin that is not subject to the anonymous REST API limit (60 requests
//!   an hour per IP, so a shared proxy exit answers 403 quickly);
//! - the REST release list, which also sees prereleases and release notes.
//!
//! A check fails only when both sources fail; a dropped connection is tried
//! once more, a server error (5xx) too. Release assets are named `fk-box-*`;
//! releases before the rename use `FK-box-*`. Both prefixes are accepted, the
//! current one first, and the APK offered is the one the release lists.
//!
//! The requests go through the platform ([`Kernel`] `http.get`, libbox on
//! Android); the platform gives its application id, version code and ABIs.

use serde::Deserialize;
use serde_json::{json, Value};

use crate::content::Kernel;

pub const REPO: &str = "koko123as/fk-box";
pub const ASSET_PREFIX: &str = "fk-box";
pub const LEGACY_ASSET_PREFIX: &str = "FK-box";
const ASSET_PREFIXES: [&str; 2] = [ASSET_PREFIX, LEGACY_ASSET_PREFIX];

fn releases_page() -> String {
    format!("https://github.com/{REPO}/releases")
}

/// A release the check found.
#[derive(Debug, Clone, PartialEq)]
pub struct Candidate {
    pub version: String,
    pub code: i64,
    pub url: String,
    pub notes: String,
    /// The release's asset names (None for the latest/download source).
    pub asset_names: Option<Vec<String>>,
    /// The asset prefix of the metadata this candidate was read from.
    pub asset_prefix: String,
}

/// What the running build is.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Build {
    pub application_id: String,
    pub version_code: i64,
    /// Supported ABIs, preferred first.
    pub abis: Vec<String>,
}

#[derive(Debug)]
enum Failure {
    /// An HTTP answer other than 200.
    Status(i64, String),
    Other(String),
}

impl Failure {
    fn message(self) -> String {
        match self {
            Failure::Status(_, m) | Failure::Other(m) => m,
        }
    }
}

pub fn status_message(url: &str, status: i64) -> String {
    if status == 403 && url.contains("api.github.com") {
        "Release request failed: GitHub API rate limit (HTTP 403)".into()
    } else {
        format!("Release request failed: HTTP {status}")
    }
}

#[derive(Deserialize)]
struct Response {
    status: i64,
    #[serde(default)]
    content: String,
}

fn get(kernel: &mut dyn Kernel, url: &str) -> Result<String, Failure> {
    let mut failure = None;
    for _ in 0..2 {
        let response = match kernel.call("http.get", json!({ "url": url })) {
            Ok(value) => serde_json::from_value::<Response>(value).map_err(|e| Failure::Other(format!("Release request failed: {e}"))),
            Err(error) => {
                // EOF, reset or timeout: try once more.
                failure = Some(Failure::Other(error));
                continue;
            }
        };
        let response = response?;
        if response.status == 200 {
            return Ok(response.content);
        }
        let status = response.status;
        failure = Some(Failure::Status(status, status_message(url, status)));
        // 403/404 would answer the same again; only a server error is repeated.
        if status < 500 {
            break;
        }
    }
    Err(failure.unwrap_or_else(|| Failure::Other("Release request failed".into())))
}

/// A metadata asset's candidate, None when it is for another app or malformed.
pub fn candidate(metadata: &Value, application_id: &str, url: Option<String>, notes: String, asset_names: Option<Vec<String>>, prefix: &str) -> Option<Candidate> {
    let id = metadata.get("application_id").and_then(Value::as_str).unwrap_or("");
    let code = metadata.get("version_code").and_then(|v| v.as_i64().or_else(|| v.as_f64().map(|f| f as i64))).unwrap_or(-1);
    let version = metadata.get("version_name").and_then(Value::as_str).unwrap_or("");
    if id != application_id || code <= 0 || version.trim().is_empty() {
        return None;
    }
    Some(Candidate {
        version: version.to_string(),
        code,
        url: url.unwrap_or_else(|| format!("{}/tag/{version}", releases_page())),
        notes,
        asset_names,
        asset_prefix: prefix.to_string(),
    })
}

/// The newest candidate; on a tie the REST one (with notes and the asset
/// list) wins, then the earlier one (Kotlin `maxWithOrNull`).
pub fn newest(candidates: Vec<Candidate>) -> Option<Candidate> {
    candidates.into_iter().fold(None, |best: Option<Candidate>, c| match best {
        Some(b) if (b.code, b.asset_names.is_some()) >= (c.code, c.asset_names.is_some()) => Some(b),
        _ => Some(c),
    })
}

pub fn metadata_name(prefix: &str) -> String {
    format!("{prefix}-version-metadata.json")
}

pub fn apk_name(version: &str, abi: &str, prefix: &str) -> String {
    format!("{prefix}-{version}-{abi}.apk")
}

/// The release APK for `abi`: with an asset list, the listed one under
/// either prefix (current first); without one, the ARM64 APK under the
/// metadata's prefix.
pub fn apk_asset(candidate: &Candidate, abi: &str) -> Option<String> {
    match &candidate.asset_names {
        None => (abi == "arm64-v8a").then(|| apk_name(&candidate.version, abi, &candidate.asset_prefix)),
        Some(names) => ASSET_PREFIXES.iter().map(|p| apk_name(&candidate.version, abi, p)).find(|n| names.contains(n)),
    }
}

/// The metadata asset among a release's asset names (current prefix first),
/// with its prefix.
pub fn metadata_asset(names: &[String]) -> Option<(String, &'static str)> {
    ASSET_PREFIXES.iter().find_map(|p| {
        let name = metadata_name(p);
        names.contains(&name).then_some((name, *p))
    })
}

fn parse(text: &str) -> Result<Value, Failure> {
    serde_json::from_str(text).map_err(|e| Failure::Other(format!("Invalid release metadata: {e}")))
}

fn from_latest(kernel: &mut dyn Kernel, build: &Build) -> Result<Option<Candidate>, Failure> {
    let mut not_found = None;
    for prefix in ASSET_PREFIXES {
        let url = format!("{}/latest/download/{}", releases_page(), metadata_name(prefix));
        let metadata = match get(kernel, &url) {
            Ok(text) => text,
            // Only a missing asset tries the other name.
            Err(Failure::Status(404, m)) => {
                not_found.get_or_insert(Failure::Status(404, m));
                continue;
            }
            Err(other) => return Err(other),
        };
        return Ok(candidate(&parse(&metadata)?, &build.application_id, None, String::new(), None, prefix));
    }
    Err(not_found.unwrap_or_else(|| Failure::Other("Release request failed".into())))
}

fn from_api(kernel: &mut dyn Kernel, build: &Build) -> Result<Option<Candidate>, Failure> {
    let list = parse(&get(kernel, &format!("https://api.github.com/repos/{REPO}/releases?per_page=20"))?)?;
    let releases = list.as_array().ok_or_else(|| Failure::Other("Invalid release list".into()))?;
    let mut found = Vec::new();
    for release in releases {
        if release.get("draft").and_then(Value::as_bool) == Some(true) {
            continue;
        }
        let Some(assets) = release.get("assets").and_then(Value::as_array) else { continue };
        let names: Vec<String> = assets.iter().map(|a| a.get("name").and_then(Value::as_str).unwrap_or("").to_string()).collect();
        let Some((file, prefix)) = metadata_asset(&names) else { continue };
        let Some(download) = assets
            .iter()
            .find(|a| a.get("name").and_then(Value::as_str) == Some(file.as_str()))
            .and_then(|a| a.get("browser_download_url"))
            .and_then(Value::as_str)
        else {
            continue;
        };
        let Some(metadata) = get(kernel, download).ok().and_then(|t| serde_json::from_str::<Value>(&t).ok()) else { continue };
        let Some(page) = release.get("html_url").and_then(Value::as_str) else { continue };
        let notes = release.get("body").and_then(Value::as_str).unwrap_or("").to_string();
        if let Some(c) = candidate(&metadata, &build.application_id, Some(page.to_string()), notes, Some(names), prefix) {
            found.push(c);
        }
    }
    Ok(newest(found))
}

/// Runs the check: `{available, hasMetadata, url}` plus, when a release was
/// found, `version`, `versionCode`, `notes` and `downloadUrl` (null when no
/// APK fits the ABIs).
pub fn check(kernel: &mut dyn Kernel, build: &Build) -> Result<Value, String> {
    let mut errors = Vec::new();
    let mut candidates = Vec::new();
    for source in [from_latest as fn(&mut dyn Kernel, &Build) -> Result<Option<Candidate>, Failure>, from_api] {
        match source(kernel, build) {
            Ok(Some(c)) => candidates.push(c),
            Ok(None) => {}
            Err(e) => errors.push(e),
        }
    }
    // Both sources failed: report the first failure rather than "no update".
    if candidates.is_empty() && errors.len() == 2 {
        return Err(errors.remove(0).message());
    }
    let Some(latest) = newest(candidates) else {
        return Ok(json!({ "available": false, "hasMetadata": false, "url": releases_page() }));
    };
    let apk = build.abis.iter().find_map(|abi| apk_asset(&latest, abi));
    Ok(json!({
        "available": latest.code > build.version_code,
        "hasMetadata": true,
        "version": latest.version,
        "versionCode": latest.code,
        "url": latest.url,
        "notes": latest.notes,
        "downloadUrl": apk.map(|a| format!("{}/download/{}/{a}", releases_page(), latest.version)),
    }))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    /// Answers by URL; a missing URL is a dropped connection.
    #[derive(Default)]
    struct Web {
        pages: HashMap<String, (i64, String)>,
        calls: Vec<String>,
    }

    impl Kernel for Web {
        fn call(&mut self, method: &str, args: Value) -> Result<Value, String> {
            assert_eq!(method, "http.get");
            let url = args["url"].as_str().unwrap().to_string();
            self.calls.push(url.clone());
            match self.pages.get(&url) {
                Some((status, content)) => Ok(json!({ "status": status, "content": content })),
                None => Err("EOF".into()),
            }
        }
    }

    fn build(code: i64) -> Build {
        Build { application_id: "io.fkbox.app".into(), version_code: code, abis: vec!["arm64-v8a".into(), "armeabi-v7a".into()] }
    }

    fn metadata(code: i64, version: &str) -> String {
        json!({"application_id": "io.fkbox.app", "version_code": code, "version_name": version}).to_string()
    }

    const LATEST: &str = "https://github.com/koko123as/fk-box/releases/latest/download/fk-box-version-metadata.json";
    const LATEST_OLD: &str = "https://github.com/koko123as/fk-box/releases/latest/download/FK-box-version-metadata.json";
    const API: &str = "https://api.github.com/repos/koko123as/fk-box/releases?per_page=20";

    #[test]
    fn candidates_and_assets_follow_the_kotlin_rules() {
        let m = json!({"application_id": "io.fkbox.app", "version_code": 750, "version_name": "1.15.0"});
        let c = candidate(&m, "io.fkbox.app", None, String::new(), None, ASSET_PREFIX).unwrap();
        assert_eq!(c.url, "https://github.com/koko123as/fk-box/releases/tag/1.15.0");
        assert!(candidate(&m, "other.app", None, String::new(), None, ASSET_PREFIX).is_none());
        assert!(candidate(&json!({"application_id": "io.fkbox.app", "version_code": 0, "version_name": "1"}), "io.fkbox.app", None, String::new(), None, ASSET_PREFIX).is_none());
        let api = Candidate { url: "b".into(), notes: "notes".into(), asset_names: Some(vec!["FK-box-1.15.0-arm64-v8a.apk".into()]), ..c.clone() };
        assert_eq!(newest(vec![c.clone(), api.clone()]), Some(api.clone()));
        assert_eq!(newest(vec![api.clone(), c.clone()]), Some(api.clone()));
        assert_eq!(apk_asset(&api, "arm64-v8a").as_deref(), Some("FK-box-1.15.0-arm64-v8a.apk"));
        assert_eq!(apk_asset(&api, "x86_64"), None);
        assert_eq!(apk_asset(&c, "arm64-v8a").as_deref(), Some("fk-box-1.15.0-arm64-v8a.apk"));
        assert_eq!(apk_asset(&c, "armeabi-v7a"), None);
        let names = vec!["FK-box-version-metadata.json".to_string(), "fk-box-version-metadata.json".to_string()];
        assert_eq!(metadata_asset(&names), Some(("fk-box-version-metadata.json".into(), ASSET_PREFIX)));
        assert_eq!(status_message(API, 403), "Release request failed: GitHub API rate limit (HTTP 403)");
        assert_eq!(status_message(LATEST, 403), "Release request failed: HTTP 403");
    }

    #[test]
    fn the_rest_list_wins_a_tie_and_sees_prereleases() {
        let mut web = Web::default();
        web.pages.insert(LATEST.into(), (200, metadata(750, "1.15.0")));
        let releases = json!([
            {"draft": true, "assets": []},
            {"html_url": "https://github.com/koko123as/fk-box/releases/tag/1.15.1-alpha.1", "body": "pre", "assets": [
                {"name": "fk-box-version-metadata.json", "browser_download_url": "https://dl/pre.json"},
                {"name": "fk-box-1.15.1-alpha.1-arm64-v8a.apk"}]},
        ]);
        web.pages.insert(API.into(), (200, releases.to_string()));
        web.pages.insert("https://dl/pre.json".into(), (200, metadata(751, "1.15.1-alpha.1")));
        let result = check(&mut web, &build(750)).unwrap();
        assert_eq!(result["available"], json!(true));
        assert_eq!(result["notes"], json!("pre"));
        assert_eq!(
            result["downloadUrl"],
            json!("https://github.com/koko123as/fk-box/releases/download/1.15.1-alpha.1/fk-box-1.15.1-alpha.1-arm64-v8a.apk")
        );
        let current = check(&mut web, &build(751)).unwrap();
        assert_eq!(current["available"], json!(false));
    }

    #[test]
    fn failures_retry_fall_back_and_report() {
        let mut web = Web::default();
        // Old asset name only; API rate limited.
        web.pages.insert(LATEST.into(), (404, String::new()));
        web.pages.insert(LATEST_OLD.into(), (200, metadata(700, "1.14.0")));
        web.pages.insert(API.into(), (403, String::new()));
        let result = check(&mut web, &build(700)).unwrap();
        assert_eq!((result["available"].clone(), result["version"].clone()), (json!(false), json!("1.14.0")));
        assert_eq!(web.calls.iter().filter(|u| *u == API).count(), 1);
        // Both sources fail: the first failure is reported; a dropped connection is tried twice.
        let mut down = Web::default();
        down.pages.insert(API.into(), (503, String::new()));
        assert_eq!(check(&mut down, &build(1)).unwrap_err(), "EOF");
        assert_eq!(down.calls.iter().filter(|u| *u == LATEST).count(), 2);
        assert_eq!(down.calls.iter().filter(|u| *u == API).count(), 2);
        // No release has metadata.
        let mut empty = Web::default();
        empty.pages.insert(LATEST.into(), (404, String::new()));
        empty.pages.insert(LATEST_OLD.into(), (404, String::new()));
        empty.pages.insert(API.into(), (200, "[]".into()));
        assert_eq!(check(&mut empty, &build(1)).unwrap()["hasMetadata"], json!(false));
    }
}
