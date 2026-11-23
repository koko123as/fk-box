//! Subscriptions: the provider headers a fetch returns, the metadata they
//! leave on a profile, and the automatic update policy.
//!
//! The HTTP request itself stays in the platform's kernel layer (libbox on
//! Android: its certificate store and per-core User-Agent rules); it hands
//! back the body and the raw `subscription-userinfo`,
//! `profile-update-interval` and `profile-web-page-url` values, parsed here.
//! When updates run is the platform's (WorkManager on Android); which
//! profiles are due and how often to wake up is decided here.


use serde::{Deserialize, Serialize};

use crate::storage::Profile;

/// The shortest periodic interval (Android WorkManager's minimum).
pub const MIN_INTERVAL_MINUTES: i32 = 15;

/// One year; anything longer is a provider error, not an update hint.
pub const MAX_INTERVAL_MINUTES: i32 = 365 * 24 * 60;

/// Traffic and expiry a provider reports in `subscription-userinfo`
/// (`upload=..; download=..; total=..; expire=..`). Bytes and Unix seconds;
/// 0 means not reported, and an expire of 0 means no expiry.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct UserInfo {
    pub upload: i64,
    pub download: i64,
    pub total: i64,
    pub expire: i64,
}

impl UserInfo {
    pub fn used(&self) -> i64 {
        self.upload.saturating_add(self.download)
    }

    pub fn remaining(&self) -> i64 {
        (self.total - self.used()).max(0)
    }

    pub fn is_empty(&self) -> bool {
        self.total <= 0 && self.expire <= 0 && self.upload <= 0 && self.download <= 0
    }
}

/// What the kernel layer's fetch returned, headers unparsed.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct Response {
    pub content: String,
    pub user_info: String,
    pub update_interval: String,
    pub web_page: String,
}

/// One fetch: the body and its provider headers, parsed.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct Fetch {
    pub content: String,
    pub info: Option<UserInfo>,
    pub update_interval_minutes: Option<i32>,
    pub web_page_url: String,
}

impl Fetch {
    pub fn parse(response: Response) -> Fetch {
        Fetch {
            info: parse_user_info(&response.user_info),
            update_interval_minutes: parse_update_interval_minutes(&response.update_interval),
            web_page_url: parse_web_page_url(&response.web_page),
            content: response.content,
        }
    }
}

/// The subscription-derived metadata saved on a profile, written and rolled
/// back as one unit.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct Metadata {
    pub upload: i64,
    pub download: i64,
    pub total: i64,
    pub expire: i64,
    pub web_page_url: String,
    pub auto_update_interval: i32,
}

impl Metadata {
    pub fn of(profile: &Profile) -> Metadata {
        Metadata {
            upload: profile.upload,
            download: profile.download,
            total: profile.total,
            expire: profile.expire,
            web_page_url: profile.web_page_url.clone(),
            auto_update_interval: profile.auto_update_interval,
        }
    }

    /// What a successful `fetch` leaves on a profile whose interval is
    /// `interval` (`user_set` when the user chose it). A fetch without
    /// `subscription-userinfo` clears stale traffic/expiry instead of keeping
    /// it; the provider's update interval applies only while the user has
    /// not chosen one.
    pub fn after(interval: i32, user_set: bool, fetch: &Fetch) -> Metadata {
        let info = fetch.info.unwrap_or_default();
        let interval = match fetch.update_interval_minutes {
            Some(hint) if !user_set => hint.max(MIN_INTERVAL_MINUTES),
            _ => interval,
        };
        Metadata {
            upload: info.upload,
            download: info.download,
            total: info.total,
            expire: info.expire,
            web_page_url: fetch.web_page_url.clone(),
            auto_update_interval: interval,
        }
    }
}

const USER_INFO_KEYS: [&str; 4] = ["upload", "download", "total", "expire"];

/// Parses `subscription-userinfo`. Keys are case-insensitive, spaces are
/// tolerated, missing keys read as 0, decimals are truncated and negative or
/// non-numeric values are ignored. None when nothing usable is present.
pub fn parse_user_info(header: &str) -> Option<UserInfo> {
    let mut values: [Option<i64>; 4] = [None; 4];
    for field in header.split(';') {
        let Some(separator) = field.find('=') else { continue };
        if separator == 0 {
            continue;
        }
        let key = field[..separator].trim().to_ascii_lowercase();
        let Some(slot) = USER_INFO_KEYS.iter().position(|k| *k == key) else { continue };
        if let Some(value) = parse_count(&field[separator + 1..]) {
            values[slot] = Some(value);
        }
    }
    if values.iter().all(Option::is_none) {
        return None;
    }
    let [upload, download, total, expire] = values.map(|v| v.unwrap_or(0));
    Some(UserInfo { upload, download, total, expire })
}

fn parse_count(raw: &str) -> Option<i64> {
    let value = raw.trim();
    if value.is_empty() {
        return None;
    }
    if let Ok(count) = value.parse::<i64>() {
        return (count >= 0).then_some(count);
    }
    let decimal = parse_decimal(value)?;
    if decimal < 0.0 {
        return None;
    }
    Some(if decimal >= i64::MAX as f64 { i64::MAX } else { decimal as i64 })
}

/// A finite decimal number (`1.5`, `1e9`); words such as `inf` or `nan` are not numbers here.
fn parse_decimal(value: &str) -> Option<f64> {
    if !value.bytes().all(|b| b.is_ascii_digit() || matches!(b, b'.' | b'e' | b'E' | b'+' | b'-')) {
        return None;
    }
    value.parse::<f64>().ok().filter(|d| d.is_finite())
}

/// `profile-update-interval` is in hours; returns minutes clamped to
/// [`MIN_INTERVAL_MINUTES`]..=[`MAX_INTERVAL_MINUTES`].
pub fn parse_update_interval_minutes(header: &str) -> Option<i32> {
    let hours = parse_decimal(header.trim())?;
    if hours <= 0.0 {
        return None;
    }
    let minutes = (hours * 60.0).min(MAX_INTERVAL_MINUTES as f64).round() as i64;
    Some(minutes.clamp(MIN_INTERVAL_MINUTES as i64, MAX_INTERVAL_MINUTES as i64) as i32)
}

/// `profile-web-page-url`, accepted only as an absolute http/https address
/// with a host; anything else (another scheme, a malformed address) is "".
pub fn parse_web_page_url(header: &str) -> String {
    let value = header.trim();
    if value.is_empty() || value.len() > 4096 || !is_http_url(value) {
        return String::new();
    }
    value.to_string()
}

/// An absolute `http`/`https` URL with a server host, made only of URI
/// characters (RFC 3986 unreserved, reserved and `%`).
pub fn is_http_url(value: &str) -> bool {
    let Some((scheme, rest)) = value.split_once("://") else { return false };
    if !scheme.eq_ignore_ascii_case("http") && !scheme.eq_ignore_ascii_case("https") {
        return false;
    }
    let uri_char = |c: char| c.is_ascii_alphanumeric() || "-._~:/?#[]@!$&'()*+,;=%".contains(c);
    if !rest.chars().all(uri_char) {
        return false;
    }
    let authority = rest.split(['/', '?', '#']).next().unwrap_or("");
    let host_port = authority.rsplit_once('@').map_or(authority, |(_, host)| host);
    let host = if let Some(literal) = host_port.strip_prefix('[') {
        match literal.split_once(']') {
            Some((inner, rest)) if !inner.is_empty() => {
                if !rest.is_empty() && !rest.strip_prefix(':').is_some_and(valid_port) {
                    return false;
                }
                return inner.chars().all(|c| c.is_ascii_hexdigit() || c == ':' || c == '.');
            }
            _ => return false,
        }
    } else {
        match host_port.split_once(':') {
            Some((host, port)) => {
                if !valid_port(port) {
                    return false;
                }
                host
            }
            None => host_port,
        }
    };
    !host.is_empty()
        && host.split('.').all(|label| {
            !label.is_empty() && !label.starts_with('-') && !label.ends_with('-') && label.chars().all(|c| c.is_ascii_alphanumeric() || c == '-')
        })
}

fn valid_port(port: &str) -> bool {
    // ":" with no digits is allowed (an empty port), as java.net.URI does.
    port.chars().all(|c| c.is_ascii_digit())
}

// -- automatic updates ---------------------------------------------------------

/// How the platform should wake up for automatic updates: every
/// `interval_minutes`, first after `initial_delay_seconds` (0 = now).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Schedule {
    pub interval_minutes: i64,
    pub initial_delay_seconds: i64,
}

fn auto_updates(profile: &Profile) -> bool {
    profile.remote() && profile.auto_update
}

/// The periodic schedule for the profiles that update automatically: the
/// shortest interval (at least [`MIN_INTERVAL_MINUTES`]), first due when the
/// stalest of them is. None when no profile updates automatically (the
/// platform cancels its job).
pub fn schedule(profiles: &[Profile], now_ms: i64) -> Option<Schedule> {
    let auto: Vec<&Profile> = profiles.iter().filter(|p| auto_updates(p)).collect();
    let interval = auto.iter().map(|p| p.auto_update_interval as i64).min()?;
    let now_seconds = now_ms / 1000;
    let initial = auto
        .iter()
        .map(|p| p.auto_update_interval as i64 * 60 - (now_seconds - p.last_updated / 1000))
        .min()
        .unwrap_or(0);
    Some(Schedule { interval_minutes: interval.max(MIN_INTERVAL_MINUTES as i64), initial_delay_seconds: initial.max(0) })
}

/// True when `profile` updates automatically and its interval has passed
/// since its last update.
pub fn is_due(profile: &Profile, now_ms: i64) -> bool {
    auto_updates(profile) && (now_ms - profile.last_updated) / 1000 >= profile.auto_update_interval as i64 * 60
}

/// The automatic updates due now, in list order.
pub fn due(profiles: &[Profile], now_ms: i64) -> Vec<i64> {
    profiles.iter().filter(|p| is_due(p, now_ms)).map(|p| p.id).collect()
}

/// What "update all" updates: every remote profile, in list order.
pub fn update_all(profiles: &[Profile]) -> Vec<i64> {
    profiles.iter().filter(|p| p.remote()).map(|p| p.id).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn info(upload: i64, download: i64, total: i64, expire: i64) -> Option<UserInfo> {
        Some(UserInfo { upload, download, total, expire })
    }

    #[test]
    fn user_info_tolerates_case_spaces_and_missing_keys() {
        assert_eq!(
            parse_user_info(" upload=1024; Download = 2048 ;TOTAL=10737418240; expire=1790000000 "),
            info(1024, 2048, 10_737_418_240, 1_790_000_000)
        );
        assert_eq!(parse_user_info("download=5; total=100"), info(0, 5, 100, 0));
        assert_eq!(parse_user_info("upload=1;;total=3;"), info(1, 0, 3, 0));
    }

    #[test]
    fn user_info_truncates_decimals_and_drops_bad_values() {
        assert_eq!(parse_user_info("upload=12.9; download=-4; total=1e9; expire=never"), info(12, 0, 1_000_000_000, 0));
        assert_eq!(parse_user_info("total=1e30").unwrap().total, i64::MAX);
        assert_eq!(parse_user_info("total=inf; upload=+7"), info(7, 0, 0, 0));
    }

    #[test]
    fn user_info_without_usable_values_is_none() {
        for header in ["", "   ", "foo=1; bar", "upload=abc", "=5"] {
            assert_eq!(parse_user_info(header), None, "{header:?}");
        }
    }

    #[test]
    fn used_and_remaining() {
        let zero = parse_user_info("upload=0; download=0; total=0; expire=0").unwrap();
        assert!(zero.is_empty());
        let quota = parse_user_info("upload=30; download=70; total=200; expire=0").unwrap();
        assert_eq!((quota.used(), quota.remaining()), (100, 100));
        assert_eq!(UserInfo { upload: 300, total: 200, ..Default::default() }.remaining(), 0);
        assert_eq!(UserInfo { upload: i64::MAX, download: 5, ..Default::default() }.used(), i64::MAX);
    }

    #[test]
    fn update_interval_is_hours_clamped_to_minutes() {
        assert_eq!(parse_update_interval_minutes("12"), Some(720));
        assert_eq!(parse_update_interval_minutes(" 1.5 "), Some(90));
        assert_eq!(parse_update_interval_minutes("0.1"), Some(15));
        assert_eq!(parse_update_interval_minutes("1000000"), Some(MAX_INTERVAL_MINUTES));
        for header in ["0", "-3", "daily", "", "NaN", "inf"] {
            assert_eq!(parse_update_interval_minutes(header), None, "{header:?}");
        }
    }

    #[test]
    fn web_page_must_be_http_with_a_host() {
        assert_eq!(parse_web_page_url(" https://example.com/user "), "https://example.com/user");
        assert_eq!(parse_web_page_url("http://panel.example.com"), "http://panel.example.com");
        assert_eq!(parse_web_page_url("HTTPS://u:p@panel.example.com:8443/a?b=c#d"), "HTTPS://u:p@panel.example.com:8443/a?b=c#d");
        assert_eq!(parse_web_page_url("http://[::1]:8080/"), "http://[::1]:8080/");
        for header in
            ["javascript:alert(1)", "intent://example.com", "https://", "not a url", "", "https://exa mple.com", "https://a_b.example", "ftp://x.example", "https://x.example/<script>"]
        {
            assert_eq!(parse_web_page_url(header), "", "{header:?}");
        }
        assert_eq!(parse_web_page_url(&format!("https://x.example/{}", "a".repeat(4096))), "");
    }

    #[test]
    fn provider_interval_applies_only_while_the_user_has_not_chosen_one() {
        let fetch = Fetch::parse(Response {
            content: "{}".into(),
            user_info: "upload=1; download=2; total=3".into(),
            update_interval: "24".into(),
            web_page: "https://example.com".into(),
        });
        let expected =
            Metadata { upload: 1, download: 2, total: 3, expire: 0, web_page_url: "https://example.com".into(), auto_update_interval: 1440 };
        assert_eq!(Metadata::after(60, false, &fetch), expected);
        assert_eq!(Metadata::after(60, true, &fetch).auto_update_interval, 60);
    }

    #[test]
    fn a_fetch_without_headers_clears_stale_info() {
        let fetch = Fetch::parse(Response { content: "{}".into(), ..Default::default() });
        assert_eq!(fetch.info, None);
        assert_eq!(Metadata::after(120, false, &fetch), Metadata { auto_update_interval: 120, ..Default::default() });
    }

    fn remote(id: i64, interval: i32, last_updated: i64, auto_update: bool) -> Profile {
        Profile { id, kind: 1, auto_update, auto_update_interval: interval, last_updated, ..Profile::default() }
    }

    #[test]
    fn schedule_takes_the_shortest_interval_and_the_stalest_profile() {
        let now = 10_000_000_000;
        let profiles = vec![
            remote(1, 120, now - 30 * 60_000, true),
            remote(2, 60, now - 10 * 60_000, true),
            remote(3, 5, now, false),
            Profile { id: 4, auto_update: true, auto_update_interval: 1, ..Profile::default() },
        ];
        // Interval 60; profile 2 is due in 50 minutes, profile 1 in 90.
        assert_eq!(schedule(&profiles, now), Some(Schedule { interval_minutes: 60, initial_delay_seconds: 50 * 60 }));
        assert_eq!(schedule(&[remote(1, 5, now - 3_600_000, true)], now), Some(Schedule { interval_minutes: 15, initial_delay_seconds: 0 }));
        assert_eq!(schedule(&profiles[2..], now), None);
    }

    #[test]
    fn due_profiles_are_automatic_ones_past_their_interval() {
        let now = 10_000_000_000;
        let profiles = vec![
            remote(1, 60, now - 60 * 60_000, true),
            remote(2, 60, now - 59 * 60_000, true),
            remote(3, 60, 0, false),
            Profile { id: 4, auto_update: true, ..Profile::default() },
        ];
        assert_eq!(due(&profiles, now), vec![1]);
        assert_eq!(update_all(&profiles), vec![1, 2, 3]);
    }
}
