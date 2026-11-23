//! What the Kotlin `BoxBridge` decided before it ran a command (phase 4e3b):
//! the checks of its arguments against the service and remote session
//! state, the defaults of arguments the UI may leave out, which pages keep
//! the libbox command streams open, and the shaping of a few answers.
//!
//! The host keeps only the Android calls. [`Bridge::step`] runs before a
//! command reaches it and says what to do with the command ([`Step`]):
//! forward it (with its arguments completed), answer it here, or reject it
//! with the message the host's `require` used to throw. [`finish`] shapes
//! the host's raw answer of a forwarded command.
//!
//! The state the checks read is the host's own `hostState` as the stream
//! runtime last saw it ([`HostView`]).

use serde_json::{json, Map, Value};

use crate::runtime::Output;

/// The host state the checks read (runtime.rs keeps it from `hostState`).
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct HostView<'a> {
    /// The local service: Stopped / Starting / Started / Stopping.
    pub status: &'a str,
    /// A remote session is chosen (connected or not).
    pub remote: bool,
    /// The chosen remote server's id.
    pub remote_id: Option<i64>,
    /// The selected local profile (-1 or none when there is none).
    pub selected_profile: Option<i64>,
}

impl HostView<'_> {
    fn started(&self) -> bool {
        self.status == "Started"
    }

    fn profile_selected(&self) -> bool {
        self.selected_profile.is_some_and(|id| id != -1)
    }
}

/// What to do with a command.
#[derive(Debug, Clone, PartialEq)]
pub enum Step {
    /// Send it to the host with these arguments.
    Forward(Value),
    /// Answer it here, then apply the outputs.
    Answer(Value, Vec<Output>),
    /// Fail it with this message.
    Reject(String),
}

/// The pages that keep libbox command streams open, as the host's
/// `setTrafficVisible` / `setConnectionsVisible` /
/// `setConnectionStatsVisible` / `setLogsVisible` tracked them.
#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub struct Bridge {
    traffic: bool,
    connections: bool,
    connection_stats: bool,
    logs: bool,
}

/// Commands that change the local profile set: unavailable in a remote session.
const LOCAL_PROFILE_COMMANDS: &[&str] = &[
    "setLanProxy",
    "writeProfile",
    "selectProfile",
    "saveProfile",
    "saveProfiles",
    "updateProfile",
    "deleteProfile",
    "restoreBackup",
    "configureCapture",
];

/// Settings whose change restarts a running local service.
const RESTART_SETTINGS: &[&str] = &["allowBypass", "autoRedirect", "dynamicNotification"];

/// The arguments each command needs (the host's `required(...)`).
fn required_args(method: &str) -> &'static [&'static str] {
    match method {
        "readProfile"
        | "profileRevision"
        | "profileConfig"
        | "profileStats"
        | "selectProfile"
        | "updateProfile"
        | "deleteProfile"
        | "deleteRemoteServer"
        | "connectRemoteServer"
        | "closeConnection" => &["id"],
        "writeProfile" => &["id", "content", "revision"],
        "setLanProxy" => &["profileId", "revision"],
        "selectOutbound" => &["group", "tag"],
        "urlTest" => &["tag"],
        "openUrl" => &["url"],
        "exportBackup" => &["name"],
        "restoreBackup" => &["token"],
        _ => &[],
    }
}

/// Defaults of arguments the UI may leave out.
fn defaults(method: &str) -> &'static [(&'static str, &'static str)] {
    match method {
        "readBackup" | "readWebdavBackup" => &[("scope", "all")],
        "restoreBackup" => &[("scope", "all"), ("strategy", "Compatible")],
        "checkConfig" | "formatConfig" => &[("core", "auto")],
        _ => &[],
    }
}

fn present(args: &Value, key: &str) -> bool {
    args.get(key).is_some_and(|v| !v.is_null())
}

fn str_arg<'a>(args: &'a Value, key: &str) -> &'a str {
    args.get(key).and_then(Value::as_str).unwrap_or_default()
}

fn flag(args: &Value, key: &str) -> bool {
    args.get(key).and_then(Value::as_bool).unwrap_or(false)
}

/// The scheme and host of a URI as `android.net.Uri` reads them for the
/// hierarchical URIs the UI passes (`scheme://[user@]host[:port][/...]`).
pub fn uri_parts(value: &str) -> (Option<&str>, Option<&str>) {
    let Some(colon) = value.find(':') else { return (None, None) };
    let scheme = &value[..colon];
    if scheme.is_empty() || !scheme.chars().all(|c| c.is_ascii_alphanumeric() || "+-.".contains(c)) {
        return (None, None);
    }
    let Some(rest) = value[colon + 1..].strip_prefix("//") else { return (Some(scheme), None) };
    let authority = &rest[..rest.find(['/', '?', '#']).unwrap_or(rest.len())];
    let host = &authority[authority.rfind('@').map_or(0, |i| i + 1)..];
    let host = if let Some(bracketed) = host.strip_prefix('[') {
        bracketed.split(']').next().unwrap_or_default()
    } else {
        host.split(':').next().unwrap_or_default()
    };
    (Some(scheme), Some(host).filter(|h| !h.is_empty()))
}

impl Bridge {
    /// A new host bridge attached (the UI's `attach`): it starts with no
    /// stream pages of its own.
    pub fn reset(&mut self) {
        *self = Bridge::default();
    }

    /// What the host's page visibility is now (`pages.visible`): the traffic
    /// stream, how many pages want the connection stream (the list and the
    /// dashboard's counts), the log stream.
    pub fn visibility(&self) -> Value {
        json!({
            "traffic": self.traffic,
            "connections": u8::from(self.connections) + u8::from(self.connection_stats),
            "logs": self.logs,
        })
    }

    fn set_visible(&mut self, method: &str, visible: bool) -> Option<Vec<Output>> {
        let slot = match method {
            "setTrafficVisible" => &mut self.traffic,
            "setConnectionsVisible" => &mut self.connections,
            "setConnectionStatsVisible" => &mut self.connection_stats,
            "setLogsVisible" => &mut self.logs,
            _ => return None,
        };
        if *slot == visible {
            return Some(vec![]);
        }
        *slot = visible;
        Some(vec![Output::Host("pages.visible", self.visibility())])
    }

    /// Checks one command against `view` before it reaches the host.
    pub fn step(&mut self, view: &HostView, method: &str, mut args: Value) -> Step {
        if method == "attach" {
            self.reset();
        }
        if let Some(out) = self.set_visible(method, flag(&args, "visible")) {
            return Step::Answer(Value::Null, out);
        }
        if !args.is_object() {
            args = Value::Object(Map::new());
        }
        for key in required_args(method) {
            if !present(&args, key) {
                return Step::Reject(format!("Missing argument: {key}"));
            }
        }
        for (key, value) in defaults(method) {
            if !present(&args, key) {
                args[*key] = json!(value);
            }
        }
        if view.remote && LOCAL_PROFILE_COMMANDS.contains(&method) {
            return Step::Reject("Local profiles cannot modify a remote sing-box session".into());
        }
        match method {
            "startService" if view.remote => Step::Reject("Exit remote control before starting the local service".into()),
            "stopService" if view.remote => Step::Reject("Exit remote control before stopping the local service".into()),
            "setSystemProxy" if view.remote => Step::Reject("System HTTP proxy is unavailable".into()),
            "setLanProxy" => match args.get("port").filter(|v| !v.is_null()) {
                Some(port) if !port.as_i64().is_some_and(|p| (1..=65535).contains(&p)) => Step::Reject("Invalid proxy port".into()),
                _ => Step::Forward(args),
            },
            "openUrl" => match uri_parts(str_arg(&args, "url")).0 {
                Some("http" | "https") => Step::Forward(args),
                _ => Step::Reject("Unsupported URL".into()),
            },
            "deleteRemoteServer" if view.remote && args["id"].as_i64() == view.remote_id => {
                Step::Reject("Disconnect from this server first".into())
            }
            "routingPreview" if view.remote => Step::Reject("Routing rules apply to the local service".into()),
            "routingPreview" if !view.profile_selected() => Step::Reject("No profile selected".into()),
            "routingTargets" if view.remote || !view.profile_selected() => Step::Answer(json!([]), vec![]),
            "setSetting" => {
                let restart = flag(&args, "restart");
                if restart && !RESTART_SETTINGS.contains(&str_arg(&args, "key")) {
                    return Step::Reject("Invalid automatic service restart setting".into());
                }
                if restart && !view.remote && !matches!(view.status, "Started" | "Stopped") {
                    return Step::Reject("Wait for the service to finish starting or stopping".into());
                }
                // Only a running local service restarts; otherwise the value is saved.
                args["restartService"] = json!(restart && !view.remote && view.started());
                Step::Forward(args)
            }
            "saveRouting" => {
                args["restartService"] = json!(!view.remote && view.started());
                Step::Forward(args)
            }
            "serviceSettingsWritten" => {
                let geo = [fkbox_client_core::geo::AUTO_UPDATE_KEY, fkbox_client_core::geo::UPDATE_INTERVAL_KEY];
                let reschedule = args["written"].as_array().is_some_and(|written| written.iter().any(|w| geo.contains(&str_arg(w, "key"))));
                args["rescheduleGeo"] = json!(reschedule);
                Step::Forward(args)
            }
            "appLabels" => {
                if let Some(packages) = args["packages"].as_array_mut() {
                    packages.retain(Value::is_string);
                    packages.truncate(1000);
                }
                Step::Forward(args)
            }
            "decodeProfileIntent" => {
                let value = str_arg(&args, "value").to_string();
                match uri_parts(&value) {
                    (Some("sing-box"), Some("import-remote-profile")) => {
                        args["kind"] = json!("remoteLink");
                        Step::Forward(args)
                    }
                    (Some("http" | "https"), host) => match host {
                        Some(host) => Step::Answer(json!({ "name": host, "url": value }), vec![]),
                        None => Step::Reject("Enter an HTTP or HTTPS subscription URL".into()),
                    },
                    _ => {
                        args["kind"] = json!("document");
                        Step::Forward(args)
                    }
                }
            }
            _ => Step::Forward(args),
        }
    }
}

fn lines(value: &Value) -> Value {
    match value {
        Value::String(text) => text.lines().filter(|l| !l.trim().is_empty()).map(|l| json!(l)).collect(),
        Value::Array(_) => value.clone(),
        _ => json!([]),
    }
}

/// Shapes the host's raw answer of a forwarded command: libbox's
/// line-separated lists become arrays.
pub fn finish(method: &str, mut value: Value) -> Value {
    match method {
        "routingTargets" => lines(&value),
        "routingPreview" => {
            if let Some(object) = value.as_object_mut() {
                let warnings = lines(object.get("warnings").unwrap_or(&Value::Null));
                object.insert("warnings".into(), warnings);
            }
            value
        }
        _ => value,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const LOCAL: HostView = HostView { status: "Started", remote: false, remote_id: None, selected_profile: Some(3) };
    const STOPPED: HostView = HostView { status: "Stopped", remote: false, remote_id: None, selected_profile: Some(-1) };
    const REMOTE: HostView = HostView { status: "Stopped", remote: true, remote_id: Some(7), selected_profile: Some(3) };

    fn forward(step: Step) -> Value {
        match step {
            Step::Forward(args) => args,
            other => panic!("not forwarded: {other:?}"),
        }
    }

    fn reject(step: Step) -> String {
        match step {
            Step::Reject(message) => message,
            other => panic!("not rejected: {other:?}"),
        }
    }

    #[test]
    fn missing_arguments_are_rejected_and_defaults_filled() {
        let mut b = Bridge::default();
        assert_eq!(reject(b.step(&LOCAL, "readProfile", json!({}))), "Missing argument: id");
        assert_eq!(reject(b.step(&LOCAL, "writeProfile", json!({ "id": 1, "content": "{}" }))), "Missing argument: revision");
        assert_eq!(reject(b.step(&LOCAL, "selectOutbound", json!({ "group": null, "tag": "a" }))), "Missing argument: group");
        let args = forward(b.step(&LOCAL, "restoreBackup", json!({ "token": "t" })));
        assert_eq!((args["scope"].as_str(), args["strategy"].as_str()), (Some("all"), Some("Compatible")));
        assert_eq!(forward(b.step(&LOCAL, "checkConfig", json!({ "content": "{}" })))["core"], "auto");
        assert_eq!(forward(b.step(&LOCAL, "formatConfig", json!({ "core": "xray", "content": "{}" })))["core"], "xray");
        assert_eq!(forward(b.step(&LOCAL, "readBackup", Value::Null))["scope"], "all");
    }

    #[test]
    fn a_remote_session_blocks_local_commands() {
        let mut b = Bridge::default();
        for method in LOCAL_PROFILE_COMMANDS {
            let args = json!({ "id": 1, "content": "", "revision": "r", "profileId": 1, "token": "t" });
            assert_eq!(reject(b.step(&REMOTE, method, args)), "Local profiles cannot modify a remote sing-box session");
        }
        assert!(reject(b.step(&REMOTE, "startService", json!({}))).starts_with("Exit remote control"));
        assert!(reject(b.step(&REMOTE, "stopService", json!({}))).starts_with("Exit remote control"));
        assert_eq!(reject(b.step(&REMOTE, "setSystemProxy", json!({ "enabled": true }))), "System HTTP proxy is unavailable");
        assert_eq!(reject(b.step(&REMOTE, "deleteRemoteServer", json!({ "id": 7 }))), "Disconnect from this server first");
        forward(b.step(&REMOTE, "deleteRemoteServer", json!({ "id": 8 })));
        forward(b.step(&LOCAL, "deleteRemoteServer", json!({ "id": 7 })));
        forward(b.step(&LOCAL, "startService", json!({})));
    }

    #[test]
    fn ports_and_urls_are_checked() {
        let mut b = Bridge::default();
        let lan = |port: Value| json!({ "profileId": 1, "revision": "r", "enabled": true, "port": port });
        assert_eq!(reject(b.step(&LOCAL, "setLanProxy", lan(json!(0)))), "Invalid proxy port");
        assert_eq!(reject(b.step(&LOCAL, "setLanProxy", lan(json!(65536)))), "Invalid proxy port");
        forward(b.step(&LOCAL, "setLanProxy", lan(json!(7890))));
        forward(b.step(&LOCAL, "setLanProxy", lan(Value::Null)));
        forward(b.step(&LOCAL, "openUrl", json!({ "url": "https://example.com/x" })));
        assert_eq!(reject(b.step(&LOCAL, "openUrl", json!({ "url": "intent://x" }))), "Unsupported URL");
        assert_eq!(reject(b.step(&LOCAL, "openUrl", json!({ "url": "HTTP://x" }))), "Unsupported URL");
    }

    #[test]
    fn routing_needs_a_local_selected_profile() {
        let mut b = Bridge::default();
        assert_eq!(reject(b.step(&REMOTE, "routingPreview", json!({}))), "Routing rules apply to the local service");
        assert_eq!(reject(b.step(&STOPPED, "routingPreview", json!({}))), "No profile selected");
        forward(b.step(&LOCAL, "routingPreview", json!({ "draft": {} })));
        assert_eq!(b.step(&REMOTE, "routingTargets", json!({ "core": "xray" })), Step::Answer(json!([]), vec![]));
        assert_eq!(b.step(&STOPPED, "routingTargets", json!({ "core": "xray" })), Step::Answer(json!([]), vec![]));
        forward(b.step(&LOCAL, "routingTargets", json!({ "core": "xray" })));
        assert_eq!(finish("routingTargets", json!("a\n\n b\nc\n")), json!(["a", " b", "c"]));
        let preview = finish("routingPreview", json!({ "core": "xray", "warnings": "w1\n  \nw2" }));
        assert_eq!(preview["warnings"], json!(["w1", "w2"]));
        assert_eq!(finish("routingPreview", json!({ "warnings": "" }))["warnings"], json!([]));
        assert_eq!(finish("other", json!("a\nb")), json!("a\nb"));
    }

    #[test]
    fn settings_restart_only_a_running_local_service() {
        let mut b = Bridge::default();
        let set = |key: &str, restart: bool| json!({ "key": key, "value": true, "restart": restart });
        assert_eq!(forward(b.step(&LOCAL, "setSetting", set("autoRedirect", true)))["restartService"], true);
        assert_eq!(forward(b.step(&STOPPED, "setSetting", set("autoRedirect", true)))["restartService"], false);
        assert_eq!(forward(b.step(&REMOTE, "setSetting", set("autoRedirect", true)))["restartService"], false);
        assert_eq!(forward(b.step(&LOCAL, "setSetting", set("anything", false)))["restartService"], false);
        assert_eq!(reject(b.step(&LOCAL, "setSetting", set("anything", true))), "Invalid automatic service restart setting");
        let starting = HostView { status: "Starting", ..LOCAL };
        assert_eq!(
            reject(b.step(&starting, "setSetting", set("allowBypass", true))),
            "Wait for the service to finish starting or stopping"
        );
        let remote_starting = HostView { status: "Starting", ..REMOTE };
        forward(b.step(&remote_starting, "setSetting", set("allowBypass", true)));
        assert_eq!(forward(b.step(&LOCAL, "saveRouting", json!({ "value": {} })))["restartService"], true);
        assert_eq!(forward(b.step(&REMOTE, "saveRouting", json!({ "value": {} })))["restartService"], false);
        assert_eq!(forward(b.step(&STOPPED, "saveRouting", json!({ "value": {} })))["restartService"], false);
    }

    #[test]
    fn written_geo_schedule_keys_reschedule() {
        let mut b = Bridge::default();
        let written = |key: &str| json!({ "written": [{ "key": "x", "t": 1, "v": 1 }, { "key": key, "t": 1, "v": true }] });
        assert_eq!(forward(b.step(&LOCAL, "serviceSettingsWritten", written("geo_auto_update")))["rescheduleGeo"], true);
        assert_eq!(forward(b.step(&LOCAL, "serviceSettingsWritten", written("geo_update_interval")))["rescheduleGeo"], true);
        assert_eq!(forward(b.step(&LOCAL, "serviceSettingsWritten", written("other")))["rescheduleGeo"], false);
    }

    #[test]
    fn app_labels_are_capped() {
        let mut b = Bridge::default();
        let packages: Vec<Value> = (0..1200).map(|i| json!(format!("p{i}"))).chain([json!(1)]).collect();
        let args = forward(b.step(&LOCAL, "appLabels", json!({ "packages": packages })));
        assert_eq!(args["packages"].as_array().map(Vec::len), Some(1000));
    }

    #[test]
    fn profile_intents_are_classified() {
        let mut b = Bridge::default();
        let step = |b: &mut Bridge, v: &str| b.step(&LOCAL, "decodeProfileIntent", json!({ "value": v }));
        assert_eq!(
            step(&mut b, "https://user@sub.example.com:8443/path?q"),
            Step::Answer(json!({ "name": "sub.example.com", "url": "https://user@sub.example.com:8443/path?q" }), vec![])
        );
        assert_eq!(reject(step(&mut b, "http:///nohost")), "Enter an HTTP or HTTPS subscription URL");
        assert_eq!(forward(step(&mut b, "sing-box://import-remote-profile?url=x"))["kind"], "remoteLink");
        assert_eq!(forward(step(&mut b, "sing-box://other"))["kind"], "document");
        assert_eq!(forward(step(&mut b, "content://media/1"))["kind"], "document");
        assert_eq!(forward(step(&mut b, "file:///sdcard/a.json"))["kind"], "document");
    }

    #[test]
    fn uri_parts_follow_android_uri() {
        assert_eq!(uri_parts("https://[::1]:80/x"), (Some("https"), Some("::1")));
        assert_eq!(uri_parts("https://a.b"), (Some("https"), Some("a.b")));
        assert_eq!(uri_parts("https://a.b#f"), (Some("https"), Some("a.b")));
        assert_eq!(uri_parts("mailto:x@y"), (Some("mailto"), None));
        assert_eq!(uri_parts("/plain/path"), (None, None));
    }

    #[test]
    fn stream_pages_are_combined_and_sent_on_change() {
        let mut b = Bridge::default();
        let visible = |b: &mut Bridge, m: &str, v: bool| match b.step(&LOCAL, m, json!({ "visible": v })) {
            Step::Answer(Value::Null, out) => out,
            other => panic!("{other:?}"),
        };
        assert_eq!(
            visible(&mut b, "setConnectionsVisible", true),
            vec![Output::Host("pages.visible", json!({ "traffic": false, "connections": 1, "logs": false }))]
        );
        assert!(visible(&mut b, "setConnectionsVisible", true).is_empty(), "unchanged");
        assert_eq!(
            visible(&mut b, "setConnectionStatsVisible", true),
            vec![Output::Host("pages.visible", json!({ "traffic": false, "connections": 2, "logs": false }))]
        );
        visible(&mut b, "setConnectionsVisible", false);
        assert_eq!(b.visibility()["connections"], 1);
        visible(&mut b, "setLogsVisible", true);
        visible(&mut b, "setTrafficVisible", true);
        assert_eq!(b.visibility(), json!({ "traffic": true, "connections": 1, "logs": true }));
        // A new host bridge starts with nothing open; the attach goes on to the host.
        assert_eq!(forward(b.step(&LOCAL, "attach", json!({}))), json!({}));
        assert_eq!(b, Bridge::default());
        assert_eq!(visible(&mut b, "setLogsVisible", true).len(), 1);
    }
}
