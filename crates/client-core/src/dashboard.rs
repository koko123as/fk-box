//! The dashboard header's state as the UI reads it, derived from what the
//! platform reports about the service (libbox command client: status, clash
//! modes, system proxy) and the remote session.
//!
//! The platform sends `{"status", "selectedProfileId", "startedAt",
//! "remoteStartedAt", "trafficVisible", "clashModeVisible", "modes", "mode",
//! "systemProxyVisible", "systemProxy", "remote", "remoteConnected",
//! "deprecated"}`; [`view`] returns the keys the pages read
//! (`trafficAvailable`, `modes` only while the core offers mode switching,
//! `systemProxyAvailable` only for the local service, `startedAt` from the
//! remote session when there is one). A value already in view form (no
//! `clashModeVisible`) passes through, as the screenshot harness sends it.

use serde_json::{json, Value};

fn flag(raw: &Value, key: &str) -> bool {
    raw.get(key).and_then(Value::as_bool).unwrap_or(false)
}

pub fn view(raw: &Value) -> Value {
    let Some(object) = raw.as_object() else { return json!({}) };
    if !object.contains_key("clashModeVisible") {
        return raw.clone();
    }
    let remote = raw.get("remote").filter(|r| !r.is_null()).cloned().unwrap_or(Value::Null);
    let is_remote = !remote.is_null();
    let started_at = raw
        .get("remoteStartedAt")
        .filter(|v| !v.is_null())
        .or_else(|| raw.get("startedAt"))
        .cloned()
        .unwrap_or(Value::Null);
    let modes = if flag(raw, "clashModeVisible") { raw.get("modes").cloned().unwrap_or(json!([])) } else { json!([]) };
    json!({
        "status": raw.get("status").cloned().unwrap_or(json!("Stopped")),
        "selectedProfileId": raw.get("selectedProfileId").cloned().unwrap_or(Value::Null),
        "startedAt": started_at,
        "trafficAvailable": flag(raw, "trafficVisible"),
        "modes": modes,
        "mode": raw.get("mode").cloned().unwrap_or(json!("")),
        "systemProxyAvailable": flag(raw, "systemProxyVisible") && !is_remote,
        "systemProxy": flag(raw, "systemProxy"),
        "remote": remote,
        "remoteConnected": flag(raw, "remoteConnected"),
        "deprecated": raw.get("deprecated").cloned().unwrap_or(json!([])),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn raw() -> Value {
        json!({
            "status": "Started", "selectedProfileId": 3, "startedAt": 100, "remoteStartedAt": null,
            "trafficVisible": true, "clashModeVisible": true, "modes": ["Rule", "Global"], "mode": "Rule",
            "systemProxyVisible": true, "systemProxy": true, "remote": null, "remoteConnected": false,
            "deprecated": [{"message": "m", "url": null}],
        })
    }

    #[test]
    fn local_service() {
        let v = view(&raw());
        assert_eq!(v["startedAt"], 100);
        assert_eq!(v["modes"], json!(["Rule", "Global"]));
        assert_eq!(v["systemProxyAvailable"], true);
        assert_eq!(v["trafficAvailable"], true);
        assert_eq!(v["deprecated"][0]["message"], "m");
    }

    #[test]
    fn hidden_modes_are_not_listed() {
        let mut r = raw();
        r["clashModeVisible"] = json!(false);
        assert_eq!(view(&r)["modes"], json!([]));
        assert_eq!(view(&r)["mode"], "Rule");
    }

    #[test]
    fn remote_session_uses_its_start_and_hides_system_proxy() {
        let mut r = raw();
        r["remote"] = json!("Home");
        r["remoteConnected"] = json!(true);
        r["remoteStartedAt"] = json!(500);
        let v = view(&r);
        assert_eq!(v["startedAt"], 500);
        assert_eq!(v["systemProxyAvailable"], false);
        assert_eq!(v["remote"], "Home");
        r["remoteStartedAt"] = Value::Null;
        assert_eq!(view(&r)["startedAt"], 100);
    }

    #[test]
    fn view_form_passes_through() {
        let shown = json!({"status": "Started", "modes": ["Rule"], "systemProxyAvailable": false});
        assert_eq!(view(&shown), shown);
        assert_eq!(view(&json!(null)), json!({}));
    }
}
