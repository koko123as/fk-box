//! Geo resources shared by Xray and mihomo (geoip.dat, geosite.dat, the
//! MMDB and ASN.mmdb): the policy behind them.
//!
//! Decided here: which files a core needs for a profile ([`needed`]), whether
//! the copy in the app package replaces an installed file
//! ([`should_extract`]), which installed files an automatic update fetches
//! and how often it runs ([`due`], [`schedule`]), and the download addresses
//! the user set ([`custom_urls`], [`set_url`], [`valid_url`]).
//!
//! The platform keeps the rest: the download and file placement (libbox
//! `core_geo.go`, with the default addresses and file names), the
//! bookkeeping file next to the data (`geo-resources.json`, whose records
//! [`due`] reads), the package's bundled copies, and the scheduler
//! (WorkManager on Android).

use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::storage::{SettingValue, Store};

pub const GEOIP: &str = "geoip";
pub const GEOSITE: &str = "geosite";
pub const MMDB: &str = "mmdb";
pub const ASN: &str = "asn";

/// Display order (FlClash's GeoResource enum).
pub const NAMES: [&str; 4] = [GEOIP, GEOSITE, MMDB, ASN];

pub const SOURCE_BUILTIN: &str = "builtin";
pub const SOURCE_DOWNLOAD: &str = "download";

/// The service settings behind the policy (`SettingsKey.GEO_*`).
pub const AUTO_UPDATE_KEY: &str = "geo_auto_update";
pub const UPDATE_INTERVAL_KEY: &str = "geo_update_interval";
pub const URLS_KEY: &str = "geo_urls";

/// FlClash's geo-update-interval default (hours), and the longest allowed (a year).
pub const DEFAULT_INTERVAL_HOURS: i64 = 24;
pub const MAX_INTERVAL_HOURS: i64 = 8760;

/// What the platform last put in place for one file, as its bookkeeping
/// file records it: `time` is the age of the data, `checked` the last
/// download attempt that succeeded (milliseconds).
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct Installed {
    pub source: String,
    pub sha256: String,
    pub time: i64,
    pub checked: i64,
    pub etag: String,
    pub last_modified: String,
}

/// Whether the bundled copy should replace what is installed. A missing file
/// is always restored; otherwise the bundle only replaces data older than
/// itself, so a newer download (or a file mihomo fetched) is never
/// overwritten, while an app upgrade with a newer bundle replaces an older
/// bundled or downloaded file. Without a record, the file's own modification
/// time stands in for its age.
pub fn should_extract(exists: bool, installed: Option<&Installed>, bundled_sha256: &str, bundle_time: i64, file_modified: i64) -> bool {
    if !exists {
        return true;
    }
    match installed {
        None => file_modified >= 1 && file_modified < bundle_time,
        Some(installed) if installed.sha256 == bundled_sha256 => false,
        Some(installed) => installed.time < bundle_time,
    }
}

/// `geodata-mode["']?\s*:\s*true` somewhere in `lower`.
fn geodata_mode(lower: &str) -> bool {
    const KEY: &str = "geodata-mode";
    lower.match_indices(KEY).any(|(at, _)| {
        let mut rest = &lower[at + KEY.len()..];
        if let Some(stripped) = rest.strip_prefix(['"', '\'']) {
            rest = stripped;
        }
        let rest = rest.trim_start_matches(java_space);
        rest.strip_prefix(':').is_some_and(|r| r.trim_start_matches(java_space).starts_with("true"))
    })
}

fn java_space(c: char) -> bool {
    matches!(c, ' ' | '\t' | '\n' | '\u{0b}' | '\u{0c}' | '\r')
}

/// The resources `core` reads for `text` (its profile content plus the
/// routing rules compiled for it). sing-box uses rule sets, none of these.
/// Xray loads geoip.dat / geosite.dat only for the `geoip:`/`geosite:` (or
/// `ext:geoip.dat:…`) entries it is given. mihomo may read GeoSite and the
/// MMDB lazily (GEOSITE/GEOIP rules, DNS fallback-filter), so both are
/// always prepared; ASN.mmdb only for IP-ASN rules and geoip.dat only in
/// geodata-mode. A reference this misses still has mihomo's own download or
/// the Resources page to fall back on. In display order.
pub fn needed(core: &str, text: &str) -> Vec<&'static str> {
    let lower = text.to_lowercase();
    let wanted: Vec<&'static str> = match core {
        "xray" => {
            let mut out = Vec::new();
            if lower.contains("geosite:") || lower.contains("geosite.dat") {
                out.push(GEOSITE);
            }
            if lower.contains("geoip:") || lower.contains("geoip.dat") {
                out.push(GEOIP);
            }
            out
        }
        "mihomo" => {
            let mut out = vec![GEOSITE, MMDB];
            if lower.contains("ip-asn") {
                out.push(ASN);
            }
            if geodata_mode(&lower) {
                out.push(GEOIP);
            }
            out
        }
        _ => Vec::new(),
    };
    NAMES.into_iter().filter(|n| wanted.contains(n)).collect()
}

/// Whether a file last checked at `last_checked` is due under an interval of
/// `interval_hours` (at least one hour) at `now` (milliseconds).
pub fn is_due(last_checked: i64, interval_hours: i64, now: i64) -> bool {
    last_checked <= 0 || now.saturating_sub(last_checked) >= interval_hours.max(1).saturating_mul(3_600_000)
}

/// The bookkeeping file's records; an unreadable file reads as none.
pub fn decode_state(text: &str) -> BTreeMap<String, Installed> {
    if text.trim().is_empty() {
        return BTreeMap::new();
    }
    serde_json::from_str(text).unwrap_or_default()
}

/// Installed files (`installed`, as the platform found them) whose last
/// check is older than the interval. A file no core has needed yet is left
/// for its first use, so automatic updates never fetch data nothing reads.
pub fn due(state: &str, installed: &[String], interval_hours: i64, now: i64) -> Vec<&'static str> {
    let state = decode_state(state);
    NAMES
        .into_iter()
        .filter(|name| installed.iter().any(|i| i == name))
        .filter(|name| is_due(state.get(*name).map(|s| s.checked).unwrap_or(0), interval_hours, now))
        .collect()
}

/// When the automatic update runs: whether it is on and its period.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Schedule {
    pub enabled: bool,
    pub interval_hours: i64,
}

fn bool_setting(store: &Store, key: &str, default: bool) -> Result<bool, String> {
    Ok(match store.setting(key).map_err(|e| e.0)? {
        Some(SettingValue::Bool(v)) => v,
        _ => default,
    })
}

/// Off and every 24 hours unless the user changed it; the period is at
/// least an hour.
pub fn schedule(store: &Store) -> Result<Schedule, String> {
    let interval = match store.setting(UPDATE_INTERVAL_KEY).map_err(|e| e.0)? {
        Some(SettingValue::Long(v)) => i64::from(v as i32),
        _ => DEFAULT_INTERVAL_HOURS,
    };
    Ok(Schedule { enabled: bool_setting(store, AUTO_UPDATE_KEY, false)?, interval_hours: interval.max(1) })
}

/// Java's `\s` and the characters `java.net.URI` rejects outright.
fn uri_char_ok(c: char) -> bool {
    if c.is_ascii() {
        c.is_ascii_alphanumeric() || "-_.!~*'();/?:@&=+$,%#[]".contains(c)
    } else {
        // Java accepts "other" characters: not control, not a space character.
        !c.is_control() && !c.is_whitespace()
    }
}

fn valid_escapes(text: &str) -> bool {
    let bytes = text.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' {
            if !(bytes.get(i + 1).is_some_and(u8::is_ascii_hexdigit) && bytes.get(i + 2).is_some_and(u8::is_ascii_hexdigit)) {
                return false;
            }
            i += 3;
        } else {
            i += 1;
        }
    }
    true
}

fn ipv4(host: &str) -> bool {
    let parts: Vec<&str> = host.split('.').collect();
    parts.len() == 4 && parts.iter().all(|p| !p.is_empty() && p.len() <= 3 && p.bytes().all(|b| b.is_ascii_digit()) && p.parse::<u16>().is_ok_and(|v| v <= 255))
}

/// A server-based host as `java.net.URI` reads one: a bracketed IPv6
/// literal, a dotted IPv4 address, or a hostname whose labels are letters,
/// digits and inner hyphens and whose last label (when there are several)
/// starts with a letter.
fn hostname(host: &str) -> bool {
    if let Some(inner) = host.strip_prefix('[').and_then(|h| h.strip_suffix(']')) {
        return inner.contains(':') && inner.chars().all(|c| c.is_ascii_hexdigit() || c == ':' || c == '.');
    }
    if host.is_empty() {
        return false;
    }
    if ipv4(host) {
        return true;
    }
    let trimmed = host.strip_suffix('.').unwrap_or(host);
    let labels: Vec<&str> = trimmed.split('.').collect();
    let label_ok = |l: &&str| {
        !l.is_empty()
            && l.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-')
            && !l.starts_with('-')
            && !l.ends_with('-')
    };
    if !labels.iter().all(label_ok) {
        return false;
    }
    labels.len() == 1 || labels.last().is_some_and(|l| l.as_bytes()[0].is_ascii_alphabetic())
}

/// An http or https address with a host, as the Kotlin check accepted it
/// (`java.net.URI`: scheme exactly `http`/`https`, a server-based authority
/// with a host, and no character a URI may not contain).
pub fn valid_url(value: &str) -> bool {
    let rest = if let Some(r) = value.strip_prefix("http://") {
        r
    } else if let Some(r) = value.strip_prefix("https://") {
        r
    } else {
        return false;
    };
    if !value.chars().all(uri_char_ok) || !valid_escapes(value) {
        return false;
    }
    let end = rest.find(['/', '?', '#']).unwrap_or(rest.len());
    let (authority, tail) = rest.split_at(end);
    // A second '#' (inside the fragment) and brackets outside the host are illegal.
    if tail.matches('#').count() > 1 || tail.contains(['[', ']']) {
        return false;
    }
    let host_port = match authority.rfind('@') {
        Some(at) if authority[..at].contains('@') => return false,
        Some(at) => &authority[at + 1..],
        None => authority,
    };
    let host = if host_port.starts_with('[') {
        match host_port.find(']') {
            Some(close) => {
                let (h, after) = host_port.split_at(close + 1);
                if !(after.is_empty() || after.strip_prefix(':').is_some_and(|p| p.bytes().all(|b| b.is_ascii_digit()))) {
                    return false;
                }
                h
            }
            None => return false,
        }
    } else {
        match host_port.split_once(':') {
            Some((h, port)) if port.bytes().all(|b| b.is_ascii_digit()) => h,
            Some(_) => return false,
            None => host_port,
        }
    };
    hostname(host)
}

/// The addresses the user set, by resource; unknown names and invalid
/// addresses are left out, and unreadable text reads as none.
pub fn decode_urls(text: &str) -> BTreeMap<String, String> {
    if text.trim().is_empty() {
        return BTreeMap::new();
    }
    serde_json::from_str::<BTreeMap<String, String>>(text)
        .map(|urls| urls.into_iter().filter(|(name, url)| NAMES.contains(&name.as_str()) && valid_url(url)).collect())
        .unwrap_or_default()
}

/// The stored text (`""` for none), names sorted.
pub fn encode_urls(urls: &BTreeMap<String, String>) -> String {
    if urls.is_empty() {
        String::new()
    } else {
        serde_json::to_string(urls).unwrap_or_default()
    }
}

/// A wire map of addresses (the settings page, backups): known names, valid
/// http(s) strings.
pub fn urls_from_wire(value: &Value) -> Result<BTreeMap<String, String>, String> {
    let object = value.as_object().ok_or("Invalid geo URLs")?;
    let mut out = BTreeMap::new();
    for (name, url) in object {
        match url.as_str() {
            Some(url) if NAMES.contains(&name.as_str()) && valid_url(url) => {
                out.insert(name.clone(), url.to_string());
            }
            _ => return Err(format!("Invalid geo URL: {name}")),
        }
    }
    Ok(out)
}

pub fn custom_urls(store: &Store) -> Result<BTreeMap<String, String>, String> {
    Ok(match store.setting(URLS_KEY).map_err(|e| e.0)? {
        Some(SettingValue::String(text)) => decode_urls(&text),
        _ => BTreeMap::new(),
    })
}

/// The address a resource downloads from: the user's, else `default_url`
/// (the kernel layer's).
pub fn url(store: &Store, name: &str, default_url: &str) -> Result<String, String> {
    Ok(custom_urls(store)?.remove(name).filter(|u| !u.trim().is_empty()).unwrap_or_else(|| default_url.to_string()))
}

/// Sets a resource's address: empty or the default clears it. Returns the
/// stored value of the setting (`None` when it was removed), for the
/// platform's settings cache.
pub fn set_url(store: &Store, name: &str, url: &str, default_url: &str) -> Result<Option<SettingValue>, String> {
    if !NAMES.contains(&name) {
        return Err(format!("Unknown geo resource: {name}"));
    }
    let value = url.trim();
    let mut urls = custom_urls(store)?;
    if value.is_empty() || value == default_url {
        urls.remove(name);
    } else {
        if !valid_url(value) {
            return Err("Not a valid HTTP or HTTPS URL".into());
        }
        urls.insert(name.into(), value.into());
    }
    let text = encode_urls(&urls);
    // The Kotlin store wrote "" rather than removing the key.
    let stored = SettingValue::String(text);
    store.put_setting(URLS_KEY, &stored).map_err(|e| e.0)?;
    Ok(Some(stored))
}

#[cfg(test)]
mod tests {
    use super::*;

    const SHA: &str = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    const BUNDLE: i64 = 1_700_000_000_000;

    fn installed(source: &str, sha: &str, time: i64) -> Installed {
        Installed { source: source.into(), sha256: sha.into(), time, ..Default::default() }
    }

    #[test]
    fn bundle_restores_missing_and_replaces_only_older_data() {
        assert!(should_extract(false, None, SHA, BUNDLE, 0));
        let newer = installed(SOURCE_DOWNLOAD, &"b".repeat(64), BUNDLE + 1);
        assert!(should_extract(false, Some(&newer), SHA, BUNDLE, 0));
        let download = installed(SOURCE_DOWNLOAD, &"b".repeat(64), BUNDLE + 60_000);
        assert!(!should_extract(true, Some(&download), SHA, BUNDLE, BUNDLE + 60_000));
        let same_time = installed(SOURCE_DOWNLOAD, &"b".repeat(64), BUNDLE);
        assert!(!should_extract(true, Some(&same_time), SHA, BUNDLE, BUNDLE));
        assert!(should_extract(true, Some(&installed(SOURCE_BUILTIN, &"c".repeat(64), BUNDLE - 86_400_000)), SHA, BUNDLE, 1));
        assert!(should_extract(true, Some(&installed(SOURCE_DOWNLOAD, &"d".repeat(64), BUNDLE - 1)), SHA, BUNDLE, 1));
        assert!(!should_extract(true, Some(&installed(SOURCE_BUILTIN, SHA, BUNDLE)), SHA, BUNDLE, 1));
        // Without a record (mihomo wrote it), the file's own age decides.
        assert!(!should_extract(true, None, SHA, BUNDLE, BUNDLE + 1));
        assert!(should_extract(true, None, SHA, BUNDLE, BUNDLE - 1));
        assert!(!should_extract(true, None, SHA, BUNDLE, 0));
    }

    #[test]
    fn due_follows_the_interval_and_skips_files_not_in_place() {
        let hour = 3_600_000;
        assert!(is_due(0, 24, 10 * hour));
        assert!(!is_due(10 * hour, 24, 33 * hour));
        assert!(is_due(10 * hour, 24, 34 * hour));
        assert!(is_due(10 * hour, 0, 11 * hour));
        let state = format!(
            r#"{{"geoip":{{"source":"builtin","sha256":"{SHA}","time":5,"checked":{}}},"asn":{{"source":"download","sha256":"{SHA}","time":9,"checked":9,"etag":"e","lastModified":"m"}}}}"#,
            30 * hour
        );
        let present = vec!["geoip".to_string(), "asn".to_string(), "mmdb".to_string()];
        assert_eq!(due(&state, &present, 24, 40 * hour), ["mmdb", "asn"]);
        assert_eq!(due(&state, &present, 24, 60 * hour), ["geoip", "mmdb", "asn"]);
        // Unreadable bookkeeping: every installed file is due.
        assert_eq!(due("{not json", &present, 24, 1), ["geoip", "mmdb", "asn"]);
        assert_eq!(decode_state(&state)["asn"].last_modified, "m");
    }

    #[test]
    fn schedule_comes_from_the_settings() {
        let store = Store::open_in_memory().unwrap();
        assert_eq!(schedule(&store).unwrap(), Schedule { enabled: false, interval_hours: 24 });
        store.put_setting(AUTO_UPDATE_KEY, &SettingValue::Bool(true)).unwrap();
        store.put_setting(UPDATE_INTERVAL_KEY, &SettingValue::Long(0)).unwrap();
        assert_eq!(schedule(&store).unwrap(), Schedule { enabled: true, interval_hours: 1 });
        store.put_setting(UPDATE_INTERVAL_KEY, &SettingValue::Long(72)).unwrap();
        assert_eq!(schedule(&store).unwrap().interval_hours, 72);
        // A value of another kind reads as the default, as the Kotlin store did.
        store.put_setting(UPDATE_INTERVAL_KEY, &SettingValue::String("72".into())).unwrap();
        assert_eq!(schedule(&store).unwrap().interval_hours, 24);
    }

    #[test]
    fn needed_files_by_core() {
        assert!(needed("sing-box", r#"{"route":{"rule_set":[{"tag":"geoip-cn"}]}}"#).is_empty());
        assert!(needed("xray", r#"{"outbounds":[{"protocol":"freedom"}]}"#).is_empty());
        assert_eq!(needed("xray", r#"{"routing":{"rules":[{"domain":["geosite:cn"]},{"ip":["GeoIP:CN"]}]}}"#), [GEOIP, GEOSITE]);
        assert_eq!(needed("xray", r#"{"ip":["ext:geoip.dat:private"]}"#), [GEOIP]);
        assert_eq!(needed("mihomo", "rules:\n  - MATCH,DIRECT\n"), [GEOSITE, MMDB]);
        assert_eq!(needed("mihomo", "rules:\n  - IP-ASN,13335,DIRECT\n"), [GEOSITE, MMDB, ASN]);
        assert_eq!(needed("mihomo", "geodata-mode: true\nrules:\n  - GEOIP,CN,DIRECT\n"), [GEOIP, GEOSITE, MMDB]);
        assert_eq!(needed("mihomo", r#"{"geodata-mode" :  true}"#), [GEOIP, GEOSITE, MMDB]);
        assert!(!needed("mihomo", "geodata-mode: false\n").contains(&GEOIP));
    }

    #[test]
    fn urls_are_checked_like_java_uri() {
        for ok in [
            "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geoip.dat",
            "http://mirror.example/geoip.metadb",
            "https://user:pw@cdn.example.com:8443/a?b=c#d",
            "http://127.0.0.1:8080/x",
            "http://[::1]/x",
            "https://localhost",
            "https://example.com/%E4%B8%AD",
            "https://example.com/中文",
        ] {
            assert!(valid_url(ok), "{ok}");
        }
        for bad in [
            "https://",
            "not a url",
            "file:///sdcard/geoip.dat",
            "ftp://example.com/x",
            "HTTPS://example.com/x",
            "https:///path",
            "https://exa mple.com/",
            "https://example.com/a b",
            "https://example.com/a|b",
            "https://under_score.example/x",
            "https://example.1abc/x",
            "https://-a.example/x",
            "https://example.com:80a/x",
            "https://example.com/%zz",
            "https://example.com/#a#b",
            "https://例子.com/x",
        ] {
            assert!(!valid_url(bad), "{bad}");
        }
    }

    #[test]
    fn urls_round_trip_and_set_url_writes_the_setting() {
        let mut urls = BTreeMap::new();
        urls.insert(GEOSITE.to_string(), "https://example.com/geosite.dat".to_string());
        urls.insert(MMDB.to_string(), "http://mirror.example/geoip.metadb".to_string());
        assert_eq!(decode_urls(&encode_urls(&urls)), urls);
        assert_eq!(encode_urls(&BTreeMap::new()), "");
        // The Kotlin text: sorted keys, compact.
        assert_eq!(encode_urls(&urls), r#"{"geosite":"https://example.com/geosite.dat","mmdb":"http://mirror.example/geoip.metadb"}"#);
        let dirty = r#"{"asn":"https://example.com/asn.mmdb","rules":"https://example.com/x","geoip":"file:///x"}"#;
        assert_eq!(decode_urls(dirty).into_iter().collect::<Vec<_>>(), [("asn".to_string(), "https://example.com/asn.mmdb".to_string())]);
        assert!(decode_urls("{not json").is_empty());

        let store = Store::open_in_memory().unwrap();
        let default = "https://default.example/geoip.dat";
        assert_eq!(url(&store, GEOIP, default).unwrap(), default);
        set_url(&store, GEOIP, " https://mirror.example/geoip.dat ", default).unwrap();
        assert_eq!(url(&store, GEOIP, default).unwrap(), "https://mirror.example/geoip.dat");
        assert_eq!(set_url(&store, GEOIP, "nope", default).unwrap_err(), "Not a valid HTTP or HTTPS URL");
        assert!(set_url(&store, "rules", "https://x.example", default).is_err());
        assert_eq!(set_url(&store, GEOIP, default, default).unwrap(), Some(SettingValue::String(String::new())));
        assert!(custom_urls(&store).unwrap().is_empty());
        assert!(urls_from_wire(&serde_json::json!({"geoip": "file:///sdcard/geoip.dat"})).is_err());
        assert!(urls_from_wire(&serde_json::json!({"rules": "https://example.com/x"})).is_err());
        assert_eq!(urls_from_wire(&serde_json::json!({"asn": "https://e.example/a"})).unwrap().len(), 1);
    }
}
