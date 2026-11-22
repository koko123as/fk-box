//! Interface preferences: what the UI may store about its own appearance and
//! layout, and the check every write and backup goes through (ported from
//! the Kotlin host's `BoxPreferences.validate`, same keys and limits).

use serde_json::{Map, Value};

/// Dashboard cards; the retired Connections card stays accepted so older
/// layouts and backups keep their other preferences.
const CARDS: [&str; 12] = [
    "Status",
    "Network speed",
    "Outbound mode",
    "VPN",
    "Intranet IP",
    "Current profile",
    "Service status",
    "Memory",
    "Connection stats",
    "Proxy groups",
    "System HTTP proxy",
    "Connections",
];

fn choices(key: &str) -> Option<&'static [&'static str]> {
    Some(match key {
        "theme" => &["system", "light", "dark"],
        "colorStyle" => &["vivid", "tinted", "soft", "monochrome"],
        "proxySort" => &["Default", "Name", "Latency"],
        "proxyLayout" => &["Tabs", "List", "Compact"],
        "proxyCardSize" => &["Compact", "Standard", "Loose"],
        "proxyDensity" => &["Tight", "Standard", "Loose"],
        "proxyIconStyle" => &["Filled", "Plain", "Hidden"],
        "restoreStrategy" => &["Compatible", "Override"],
        // The requests page's feed: recent and live connections, or captured HTTP.
        "requestFeed" => &["recent", "live", "captured"],
        // How the phone's home destinations change (crates/client-ui home.slint Pages).
        "tabMotion" => &["slide", "fade"],
        _ => return None,
    })
}

/// Service status targets (apps/android/core/ui_service_check.go, FlClash ServiceTarget ids).
const SERVICES: [&str; 13] = [
    "google",
    "github",
    "youtube",
    "chatgpt",
    "claude",
    "gemini",
    "netflix",
    "disney-plus",
    "prime-video",
    "spotify",
    "tiktok",
    "discord",
    "bilibili",
];

/// Retired keys older settings and backups may still carry: the language
/// choice and the appearance options of the earlier decorated UI. They are
/// dropped, not refused.
const RETIRED: [&str; 6] = ["locale", "tabAnimation", "liquidGlass", "appearanceStyle", "floatingNavigation", "sidebarExpanded"];

const BOOLEANS: [&str; 6] =
    ["closeConnectionsOnSelect", "hideTimeoutProxies", "pureBlack", "systemColor", "customFontScale", "railExtended"];

fn whole(value: &Value) -> Option<i64> {
    let f = value.as_f64()?;
    if f.is_finite() && f == (f as i64) as f64 {
        Some(f as i64)
    } else {
        None
    }
}

/// Kotlin's `Number.toLong()` of a JSON number (truncates).
fn long(value: &Value) -> Option<i64> {
    value.as_i64().or_else(|| value.as_f64().filter(|f| f.is_finite()).map(|f| f as i64))
}

fn distinct(items: &[Value]) -> bool {
    items.iter().enumerate().all(|(i, a)| items[..i].iter().all(|b| a != b))
}

fn text_len_in(value: &Value, min: usize, max: usize) -> bool {
    // Kotlin String.length counts UTF-16 units.
    value.as_str().map(|s| (min..=max).contains(&s.encode_utf16().count())).unwrap_or(false)
}

fn valid(key: &str, value: &Value) -> bool {
    if let Some(choices) = choices(key) {
        return value.as_str().map(|v| choices.contains(&v)).unwrap_or(false);
    }
    if BOOLEANS.contains(&key) {
        return value.is_boolean();
    }
    match key {
        "proxyColumns" => whole(value).map(|v| (1..=4).contains(&v)).unwrap_or(false),
        "proxyUnfolded" => value.as_object().is_some_and(|map| {
            map.len() <= 128
                && map.iter().all(|(profile, groups)| {
                    (1..=4096).contains(&profile.encode_utf16().count())
                        && groups.as_array().is_some_and(|groups| {
                            groups.len() <= 4096 && distinct(groups) && groups.iter().all(|g| text_len_in(g, 1, 4096))
                        })
                })
        }),
        // A record page id to its grouping.
        "recordGroupings" => value.as_object().is_some_and(|map| {
            map.len() <= 16
                && map.iter().all(|(page, grouping)| {
                    (1..=64).contains(&page.encode_utf16().count())
                        && grouping.as_str().is_some_and(|g| ["app", "host", "rule", "exit"].contains(&g))
                })
        }),
        // The service status order and the services turned off.
        "serviceOrder" | "disabledServices" => value.as_array().is_some_and(|items| {
            items.iter().all(|s| s.as_str().is_some_and(|s| SERVICES.contains(&s)))
                && distinct(items)
                && (key == "serviceOrder" || items.len() < SERVICES.len())
        }),
        "fontScale" => value.as_f64().is_some_and(|v| v.is_finite() && (0.8..=1.4).contains(&v)),
        "fontFamily" => value.is_null() || text_len_in(value, 1, 128),
        "accentColors" => value.as_array().is_some_and(|items| {
            items.len() <= 64
                && distinct(items)
                && items.iter().all(|c| c.is_number() && long(c).is_some_and(|c| (0..=0xffff_ffff).contains(&c)))
        }),
        "color" => value.is_number() && long(value).is_some_and(|c| (0..=0xffff_ffff).contains(&c)),
        "dashboardCards" | "hiddenCards" => value.as_array().is_some_and(|items| {
            let status = items.iter().any(|c| c.as_str() == Some("Status"));
            items.iter().all(|c| c.as_str().is_some_and(|c| CARDS.contains(&c)))
                && distinct(items)
                && if key == "dashboardCards" { status } else { !status }
        }),
        _ => false,
    }
}

/// Drops the retired keys and checks the rest; the first invalid key fails
/// the whole set with the message the Kotlin host gave.
pub fn validate(values: &Map<String, Value>) -> Result<Map<String, Value>, String> {
    let mut out = Map::new();
    for (key, value) in values {
        if RETIRED.contains(&key.as_str()) {
            continue;
        }
        if !valid(key, value) {
            return Err(format!("Invalid interface preference: {key}"));
        }
        out.insert(key.clone(), value.clone());
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn check(value: Value) -> Result<Map<String, Value>, String> {
        validate(value.as_object().unwrap())
    }

    #[test]
    fn accepts_every_known_key() {
        let all = json!({
            "theme": "dark", "colorStyle": "soft", "proxySort": "Latency", "proxyLayout": "List",
            "proxyCardSize": "Loose", "proxyDensity": "Tight", "proxyIconStyle": "Hidden",
            "restoreStrategy": "Override", "requestFeed": "captured", "tabMotion": "fade",
            "closeConnectionsOnSelect": true, "hideTimeoutProxies": false, "pureBlack": true,
            "systemColor": false, "customFontScale": true, "railExtended": true,
            "proxyColumns": 3, "proxyUnfolded": {"local:1": ["Proxy", "Auto"]},
            "recordGroupings": {"connections": "host"}, "serviceOrder": ["google", "github"],
            "disabledServices": ["tiktok"], "fontScale": 1.1, "fontFamily": null,
            "accentColors": [4278190335u32, 0], "color": 4294967295u32,
            "dashboardCards": ["Status", "Memory"], "hiddenCards": ["Connections"],
        });
        assert_eq!(Value::Object(check(all.clone()).unwrap()), all);
    }

    #[test]
    fn drops_retired_keys() {
        assert_eq!(
            Value::Object(check(json!({"locale": "zh", "liquidGlass": true, "theme": "light"})).unwrap()),
            json!({"theme": "light"})
        );
    }

    #[test]
    fn refuses_invalid_values() {
        for bad in [
            json!({"theme": "neon"}),
            json!({"pureBlack": 1}),
            json!({"proxyColumns": 5}),
            json!({"proxyColumns": 2.5}),
            json!({"fontScale": 2.0}),
            json!({"fontFamily": ""}),
            json!({"color": -1}),
            json!({"accentColors": [1, 1]}),
            json!({"dashboardCards": ["Memory"]}),
            json!({"hiddenCards": ["Status"]}),
            json!({"serviceOrder": ["google", "google"]}),
            json!({"disabledServices": SERVICES}),
            json!({"recordGroupings": {"x": "country"}}),
            json!({"proxyUnfolded": {"": ["a"]}}),
            json!({"unknown": true}),
        ] {
            let message = check(bad.clone()).unwrap_err();
            assert!(message.starts_with("Invalid interface preference: "), "{bad}: {message}");
        }
    }

    #[test]
    fn integral_floats_count_as_whole_numbers() {
        assert!(check(json!({"proxyColumns": 2.0})).is_ok());
        assert!(check(json!({"color": 255.0})).is_ok());
    }
}
