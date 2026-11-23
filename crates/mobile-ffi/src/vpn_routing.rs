//! The VPN interface the Kotlin `VPNService.openTun` builds (phase 4i2),
//! called through [`crate::api`] (`vpnRouting.plan`): from libbox's `TunOptions`
//! (converted to JSON by the host, since the gomobile iterators stay in
//! Kotlin) and the Android settings, which addresses, DNS servers, routes,
//! excluded routes, allowed or disallowed apps, MTU and HTTP proxy go into
//! the `VpnService.Builder`. Kotlin keeps the builder calls and `establish`.

use serde_json::{json, Value};

/// Android 10 (Q): `setMetered` and the VPN's HTTP proxy.
const ANDROID_Q: i64 = 29;
/// Android 13 (Tiramisu): routes as `IpPrefix`, and excluded routes.
const ANDROID_T: i64 = 33;

fn list<'a>(args: &'a Value, key: &str) -> &'a [Value] {
    args.get(key).and_then(Value::as_array).map(Vec::as_slice).unwrap_or_default()
}

fn flag(value: &Value, key: &str) -> bool {
    value.get(key).and_then(Value::as_bool).unwrap_or(false)
}

/// The builder plan for `TunOptions`. `args`: `sdk`, the settings
/// `allowBypass` and `systemProxy` (whether the user turned the system proxy
/// on), and the options: `mtu`, `autoRoute`, `dnsDisabled` (DNS mode is
/// disabled), `inet4Address`/`inet6Address` and the route lists
/// (`inet4RouteAddress`, `inet6RouteAddress`, `inet4RouteExclude`,
/// `inet6RouteExclude`, `inet4RouteRange`, `inet6RouteRange`) as
/// `{address, prefix}`, `dnsServers`, the `include`/`exclude` packages and
/// `httpProxy` (`enabled`, `server`, `port`, `bypass`).
///
/// Answers `session`, `mtu`, `metered` (false from Android 10, else null:
/// not set), `allowBypass`, `addresses`, `dns`, `routes`, `excludeRoutes`,
/// `ipPrefix` (whether routes are added as `IpPrefix`), `allowed`,
/// `disallowed`, `httpProxy` (null when none is set) and the service's
/// `systemProxyAvailable` / `systemProxyEnabled`.
pub fn plan(args: &Value) -> Value {
    let sdk = args.get("sdk").and_then(Value::as_i64).unwrap_or(0);
    let ip_prefix = sdk >= ANDROID_T;
    let has4 = !list(args, "inet4Address").is_empty();
    let has6 = !list(args, "inet6Address").is_empty();
    let mut addresses: Vec<Value> = list(args, "inet4Address").to_vec();
    addresses.extend_from_slice(list(args, "inet6Address"));

    let (mut dns, mut routes, mut exclude_routes, mut allowed, mut disallowed) = (vec![], vec![], vec![], vec![], vec![]);
    if flag(args, "autoRoute") {
        if !flag(args, "dnsDisabled") {
            dns = list(args, "dnsServers").to_vec();
        }
        if ip_prefix {
            let default4 = json!({ "address": "0.0.0.0", "prefix": 0 });
            let default6 = json!({ "address": "::", "prefix": 0 });
            for (key, has, default) in [("inet4RouteAddress", has4, default4), ("inet6RouteAddress", has6, default6)] {
                let given = list(args, key);
                if !given.is_empty() {
                    routes.extend_from_slice(given);
                } else if has {
                    routes.push(default);
                }
            }
            exclude_routes.extend_from_slice(list(args, "inet4RouteExclude"));
            exclude_routes.extend_from_slice(list(args, "inet6RouteExclude"));
        } else {
            routes.extend_from_slice(list(args, "inet4RouteRange"));
            routes.extend_from_slice(list(args, "inet6RouteRange"));
        }
        allowed = list(args, "include").to_vec();
        disallowed = list(args, "exclude").to_vec();
    }

    let proxy = &args["httpProxy"];
    let available = flag(proxy, "enabled") && sdk >= ANDROID_Q;
    let enabled = available && flag(args, "systemProxy");
    let http_proxy =
        if enabled { json!({ "server": proxy["server"], "port": proxy["port"], "bypass": list(proxy, "bypass") }) } else { Value::Null };

    json!({
        "session": "sing-box",
        "mtu": args["mtu"],
        "metered": if sdk >= ANDROID_Q { json!(false) } else { Value::Null },
        "allowBypass": flag(args, "allowBypass"),
        "addresses": addresses,
        "dns": dns,
        "routes": routes,
        "excludeRoutes": exclude_routes,
        "ipPrefix": ip_prefix,
        "allowed": allowed,
        "disallowed": disallowed,
        "httpProxy": http_proxy,
        "systemProxyAvailable": available,
        "systemProxyEnabled": enabled,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn p(address: &str, prefix: i64) -> Value {
        json!({ "address": address, "prefix": prefix })
    }

    fn options(sdk: i64) -> Value {
        json!({
            "sdk": sdk, "allowBypass": false, "systemProxy": true, "mtu": 9000, "autoRoute": true, "dnsDisabled": false,
            "inet4Address": [p("172.19.0.1", 30)], "inet6Address": [p("fdfe:dcba:9876::1", 126)],
            "dnsServers": ["172.19.0.2"],
            "inet4RouteAddress": [], "inet6RouteAddress": [p("2000::", 3)],
            "inet4RouteExclude": [p("192.168.0.0", 16)], "inet6RouteExclude": [],
            "inet4RouteRange": [p("1.0.0.0", 8)], "inet6RouteRange": [p("2000::", 3)],
            "include": ["a"], "exclude": ["b"],
            "httpProxy": { "enabled": true, "server": "127.0.0.1", "port": 2080, "bypass": ["localhost"] },
        })
    }

    #[test]
    fn android_13_adds_default_routes_and_exclusions() {
        let out = plan(&options(35));
        assert_eq!(out["session"], "sing-box");
        assert_eq!(out["mtu"], 9000);
        assert_eq!(out["metered"], false);
        assert_eq!(out["ipPrefix"], true);
        assert_eq!(out["addresses"], json!([p("172.19.0.1", 30), p("fdfe:dcba:9876::1", 126)]));
        assert_eq!(out["dns"], json!(["172.19.0.2"]));
        assert_eq!(out["routes"], json!([p("0.0.0.0", 0), p("2000::", 3)]));
        assert_eq!(out["excludeRoutes"], json!([p("192.168.0.0", 16)]));
        assert_eq!(out["allowed"], json!(["a"]));
        assert_eq!(out["disallowed"], json!(["b"]));
        assert_eq!(out["httpProxy"], json!({ "server": "127.0.0.1", "port": 2080, "bypass": ["localhost"] }));
        assert_eq!(out["systemProxyAvailable"], true);
        assert_eq!(out["systemProxyEnabled"], true);
    }

    #[test]
    fn no_default_route_without_an_address_of_that_family() {
        let mut args = options(33);
        args["inet6Address"] = json!([]);
        args["inet6RouteAddress"] = json!([]);
        let out = plan(&args);
        assert_eq!(out["routes"], json!([p("0.0.0.0", 0)]));
        assert_eq!(out["addresses"], json!([p("172.19.0.1", 30)]));
    }

    #[test]
    fn older_android_uses_route_ranges_and_no_exclusions() {
        let out = plan(&options(31));
        assert_eq!(out["ipPrefix"], false);
        assert_eq!(out["routes"], json!([p("1.0.0.0", 8), p("2000::", 3)]));
        assert_eq!(out["excludeRoutes"], json!([]));
        let old = plan(&options(28));
        assert_eq!(old["metered"], Value::Null);
        assert_eq!(old["httpProxy"], Value::Null);
        assert_eq!(old["systemProxyAvailable"], false);
        assert_eq!(old["systemProxyEnabled"], false);
    }

    #[test]
    fn without_auto_route_only_addresses_go_in() {
        let mut args = options(35);
        args["autoRoute"] = json!(false);
        args["allowBypass"] = json!(true);
        let out = plan(&args);
        assert_eq!(out["allowBypass"], true);
        assert_eq!(out["addresses"].as_array().unwrap().len(), 2);
        for key in ["dns", "routes", "excludeRoutes", "allowed", "disallowed"] {
            assert_eq!(out[key], json!([]), "{key}");
        }
    }

    #[test]
    fn dns_and_the_proxy_follow_their_switches() {
        let mut args = options(35);
        args["dnsDisabled"] = json!(true);
        args["systemProxy"] = json!(false);
        let out = plan(&args);
        assert_eq!(out["dns"], json!([]));
        assert_eq!(out["httpProxy"], Value::Null);
        assert_eq!(out["systemProxyAvailable"], true);
        assert_eq!(out["systemProxyEnabled"], false);
        args["httpProxy"]["enabled"] = json!(false);
        assert_eq!(plan(&args)["systemProxyAvailable"], false);
    }
}
