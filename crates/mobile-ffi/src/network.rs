//! What the Kotlin `DefaultNetworkMonitor` and `DefaultNetworkListener` do
//! when the default network changes (phase 4i3), called through
//! [`crate::api`] (`network.*`): whether the core is told to reconnect, the
//! system DNS line handed to the core, and which `ConnectivityManager`
//! callback tracks the default network on each Android version. Kotlin keeps
//! the callbacks, the link property reads and the libbox calls.

use serde_json::{json, Value};

/// A default network update: `network` (its handle, null when there is
/// none) and `last`, the last non-null network. Answers the new `last` and
/// `reconnect`: true only when the default network switched from one
/// network to another (Wi-Fi to cellular), not for property updates of the
/// same network, a lost network, or the first network seen.
pub fn update(args: &Value) -> Value {
    let network = args.get("network").filter(|v| !v.is_null());
    let last = args.get("last").filter(|v| !v.is_null());
    match network {
        Some(current) => json!({ "last": current, "reconnect": last.is_some_and(|previous| previous != current) }),
        None => json!({ "last": last.cloned().unwrap_or(Value::Null), "reconnect": false }),
    }
}

/// The system DNS line for the core from the network's `servers` (host
/// addresses, IPv6 ones possibly with a `%scope`): the addresses without
/// their scope, comma separated, empty ones left out.
pub fn system_dns(args: &Value) -> Value {
    let servers: Vec<&str> = args
        .get("servers")
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
        .filter_map(Value::as_str)
        .map(|server| server.split('%').next().unwrap_or_default())
        .collect();
    json!({ "dns": servers.join(",") })
}

/// The callback that tracks the default network on Android `sdk`:
/// `bestMatching` (31+), `request` (28 to 30, a REQUEST so only the default
/// network is reported, since the default callback returns the VPN), `defaultHandler`
/// (26, 27) or `default` (24, 25, which take no handler). `relaxed`: the
/// Android 6 OEM workaround that drops the validated and captive portal
/// capabilities from the request.
pub fn callback(args: &Value) -> Value {
    let sdk = args.get("sdk").and_then(Value::as_i64).unwrap_or(0);
    let kind = match sdk {
        31.. => "bestMatching",
        28..=30 => "request",
        26..=27 => "defaultHandler",
        _ => "default",
    };
    json!({ "kind": kind, "relaxed": sdk == 23 })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_a_switch_between_networks_reconnects() {
        assert_eq!(update(&json!({ "network": 100, "last": null })), json!({ "last": 100, "reconnect": false }));
        assert_eq!(update(&json!({ "network": 100, "last": 100 })), json!({ "last": 100, "reconnect": false }));
        assert_eq!(update(&json!({ "network": 200, "last": 100 })), json!({ "last": 200, "reconnect": true }));
        assert_eq!(update(&json!({ "network": null, "last": 100 })), json!({ "last": 100, "reconnect": false }));
        // Airplane mode: lost, then the same network back does not reconnect,
        // another one does.
        let lost = update(&json!({ "network": null, "last": 100 }));
        assert_eq!(update(&json!({ "network": 100, "last": lost["last"] }))["reconnect"], false);
        assert_eq!(update(&json!({ "network": 300, "last": lost["last"] }))["reconnect"], true);
    }

    #[test]
    fn system_dns_drops_scopes() {
        let out = system_dns(&json!({ "servers": ["8.8.8.8", "fe80::1%wlan0", "2001:db8::1"] }));
        assert_eq!(out["dns"], "8.8.8.8,fe80::1,2001:db8::1");
        assert_eq!(system_dns(&json!({ "servers": [] }))["dns"], "");
    }

    #[test]
    fn callbacks_follow_the_sdk() {
        let kind = |sdk: i64| callback(&json!({ "sdk": sdk }))["kind"].as_str().unwrap().to_string();
        assert_eq!(kind(35), "bestMatching");
        assert_eq!(kind(31), "bestMatching");
        assert_eq!(kind(30), "request");
        assert_eq!(kind(28), "request");
        assert_eq!(kind(27), "defaultHandler");
        assert_eq!(kind(26), "defaultHandler");
        assert_eq!(kind(24), "default");
        assert_eq!(callback(&json!({ "sdk": 23 }))["relaxed"], true);
        assert_eq!(callback(&json!({ "sdk": 24 }))["relaxed"], false);
    }
}
