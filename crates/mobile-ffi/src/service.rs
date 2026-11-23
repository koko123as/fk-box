//! What the Kotlin `BoxService` decided while it started or reloaded a
//! profile (phase 4e3b), called through [`crate::api`] (`service.*`): the
//! routing status the Settings › Traffic routing page shows and whether a
//! notice goes up for it, the geo data notice, and which packages the
//! per-app proxy hands to the core. The service keeps the libbox command
//! server, the VPN and the Android notices themselves.

use serde_json::{json, Value};

/// `PER_APP_PROXY_INCLUDE` of the Android settings (1 is exclude).
pub const PER_APP_INCLUDE: i64 = 2;

fn s<'a>(value: &'a Value, key: &str) -> &'a str {
    value.get(key).and_then(Value::as_str).unwrap_or_default()
}

fn take_chars(text: &str, count: usize) -> &str {
    text.char_indices().nth(count).map_or(text, |(i, _)| &text[..i])
}

/// The routing status after a start or reload: `core`, libbox's
/// `CoreRoutingResult` as `result` (null when the core gave none), the
/// reason the saved rules could not be read (`unreadable`), the previous
/// status's `previousError`, `now` (ms) and `zh` (the notice language).
///
/// Answers `status` (`core`, `applied`, `active`, `fallback`, `error`,
/// `warnings`, `time`) and `notice`: the toast text when the rules newly
/// failed (the error differs from the previous one), else null.
pub fn routing_status(args: &Value) -> Value {
    let result = &args["result"];
    let error = match args.get("unreadable").and_then(Value::as_str) {
        Some(reason) => format!("Routing rules unreadable: {reason}"),
        None => s(result, "error").to_string(),
    };
    let warnings: Vec<&str> = s(result, "warnings").lines().filter(|l| !l.trim().is_empty()).collect();
    let status = json!({
        "core": s(args, "core"),
        "applied": result["applied"].as_i64().unwrap_or(0),
        "active": result["active"].as_bool().unwrap_or(false),
        "fallback": result["fallback"].as_bool().unwrap_or(false),
        "error": error,
        "warnings": warnings,
        "time": args["now"].as_i64().unwrap_or(0),
    });
    let notice = (!error.is_empty() && error != s(args, "previousError")).then(|| {
        let title = if args["zh"].as_bool().unwrap_or(false) {
            "分流规则未生效，已按原配置运行"
        } else {
            "Routing rules not applied; the profile runs without them"
        };
        format!("{title}\n{}", take_chars(&error, 160))
    });
    json!({ "status": status, "notice": notice })
}

/// Geo data a profile needs could not be had (`detail`, `zh`): `text` for
/// the start error, `notice` (at most 200 characters) for the toast.
pub fn geo_missing(args: &Value) -> Value {
    let title = if args["zh"].as_bool().unwrap_or(false) {
        "地理数据下载失败，可在 工具 › 资源 中手动下载或更换下载地址"
    } else {
        "Geo data could not be downloaded; download it or change its address in Tools › Resources"
    };
    let text = format!("{title}\n{}", s(args, "detail"));
    let notice = take_chars(&text, 200).to_string();
    json!({ "text": text, "notice": notice })
}

/// The per-app proxy packages for the core: `enabled`, `mode` (2 include,
/// otherwise exclude), the effective `list` and the app's `own` package.
/// Answers `include` (the list with the app, which must stay proxied) or
/// `exclude` (the list without the app), or neither when it is off.
pub fn per_app_packages(args: &Value) -> Value {
    if !args["enabled"].as_bool().unwrap_or(false) {
        return json!({});
    }
    let own = s(args, "own");
    let mut list: Vec<&str> = Vec::new();
    for name in args["list"].as_array().into_iter().flatten().filter_map(Value::as_str) {
        if !list.contains(&name) {
            list.push(name);
        }
    }
    if args["mode"].as_i64() == Some(PER_APP_INCLUDE) {
        if !own.is_empty() && !list.contains(&own) {
            list.push(own);
        }
        json!({ "include": list })
    } else {
        list.retain(|name| *name != own);
        json!({ "exclude": list })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn routing_status_shapes_the_result_and_notices_new_errors() {
        let args = json!({ "core": "xray", "now": 5, "zh": false, "previousError": "",
            "result": { "applied": 3, "active": true, "fallback": false, "error": "", "warnings": "a\n\n b \n" } });
        let out = routing_status(&args);
        assert_eq!(
            out["status"],
            json!({ "core": "xray", "applied": 3, "active": true, "fallback": false,
            "error": "", "warnings": ["a", " b "], "time": 5 })
        );
        assert_eq!(out["notice"], Value::Null);

        let failed = json!({ "core": "mihomo", "now": 6, "zh": true, "previousError": "",
            "result": { "applied": 0, "active": false, "fallback": true, "error": "bad rule" } });
        let out = routing_status(&failed);
        assert_eq!(out["status"]["fallback"], true);
        assert_eq!(out["notice"], "分流规则未生效，已按原配置运行\nbad rule");
        let mut again = failed.clone();
        again["previousError"] = json!("bad rule");
        assert_eq!(routing_status(&again)["notice"], Value::Null, "the same error is not noticed twice");
    }

    #[test]
    fn unreadable_rules_win_and_long_errors_are_cut() {
        let args = json!({ "core": "sing-box", "now": 1, "result": null, "unreadable": "x".repeat(300) });
        let out = routing_status(&args);
        assert!(out["status"]["error"].as_str().unwrap().starts_with("Routing rules unreadable: x"));
        assert_eq!(out["status"]["applied"], 0);
        let notice = out["notice"].as_str().unwrap();
        let body = notice.split_once('\n').unwrap().1;
        assert_eq!(body.chars().count(), 160);
        assert!(notice.starts_with("Routing rules not applied"));
    }

    #[test]
    fn geo_notice_is_capped() {
        let out = geo_missing(&json!({ "detail": "地".repeat(300), "zh": true }));
        assert!(out["text"].as_str().unwrap().starts_with("地理数据下载失败"));
        assert_eq!(out["notice"].as_str().unwrap().chars().count(), 200);
        let out = geo_missing(&json!({ "detail": "geoip.dat" }));
        assert!(out["text"].as_str().unwrap().ends_with("Tools › Resources\ngeoip.dat"));
        assert_eq!(out["text"], out["notice"]);
    }

    #[test]
    fn per_app_lists_keep_the_app_proxied() {
        let base = json!({ "enabled": true, "own": "io.fkbox.app", "list": ["a", "io.fkbox.app", "a", "b"] });
        let mut include = base.clone();
        include["mode"] = json!(2);
        assert_eq!(per_app_packages(&include), json!({ "include": ["a", "io.fkbox.app", "b"] }));
        let mut include_without = include.clone();
        include_without["list"] = json!(["a"]);
        assert_eq!(per_app_packages(&include_without), json!({ "include": ["a", "io.fkbox.app"] }));
        let mut exclude = base.clone();
        exclude["mode"] = json!(1);
        assert_eq!(per_app_packages(&exclude), json!({ "exclude": ["a", "b"] }));
        assert_eq!(per_app_packages(&json!({ "enabled": false, "mode": 2 })), json!({}));
    }
}
