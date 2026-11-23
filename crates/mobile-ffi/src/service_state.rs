//! The start and stop state machine of the Kotlin `BoxService` (phase 4i1),
//! called through [`crate::api`] (`serviceState.*`): what a start request does
//! in each service state, when a stop goes ahead and what follows it, which
//! profile checks end a start, how a failed step is classified for the
//! alert, whether a failed reload falls back to the configuration that last
//! started, and the foreground notification's title, text and traffic line.
//!
//! Kotlin keeps the state itself (its `Status` LiveData feeds the binder),
//! the libbox command server, `startForeground` and the notification
//! posting; each call here takes the state it needs and answers what to do.

use serde_json::{json, Value};

/// `constant/Status.kt`, by ordinal.
pub const STOPPED: i64 = 0;
pub const STARTING: i64 = 1;
pub const STARTED: i64 = 2;
pub const STOPPING: i64 = 3;

/// `constant/Alert.kt`, by ordinal.
pub const ALERT_VPN_PERMISSION: i64 = 0;
pub const ALERT_NOTIFICATION_PERMISSION: i64 = 1;
pub const ALERT_LOCATION_PERMISSION: i64 = 2;
pub const ALERT_EMPTY_CONFIGURATION: i64 = 3;
pub const ALERT_START_COMMAND_SERVER: i64 = 4;
pub const ALERT_CREATE_SERVICE: i64 = 5;
pub const ALERT_START_SERVICE: i64 = 6;

/// Android 10 (Q), from which reading the Wi-Fi state in the background
/// needs the background location permission.
const ANDROID_Q: i64 = 29;

fn s<'a>(value: &'a Value, key: &str) -> &'a str {
    value.get(key).and_then(Value::as_str).unwrap_or_default()
}

fn flag(value: &Value, key: &str) -> bool {
    value.get(key).and_then(Value::as_bool).unwrap_or(false)
}

fn status(value: &Value) -> i64 {
    value.get("status").and_then(Value::as_i64).unwrap_or(STOPPED)
}

/// The foreground notification for `profile` in `phase` (`starting` or
/// `started`): `title` (the profile name, or `sing-box` when it has none)
/// and `text`, the string resource Kotlin shows (`status_starting`,
/// `status_started`).
pub fn notification(args: &Value) -> Value {
    let profile = s(args, "profile");
    let title = if profile.trim().is_empty() { "sing-box" } else { profile };
    let text = if s(args, "phase") == "started" { "status_started" } else { "status_starting" };
    json!({ "title": title, "text": text })
}

/// A start request (`onStartCommand`) in service `status` with the last
/// `profile` name. Answers `action`:
///
/// - `start`: the service was stopped; it becomes `Starting` (`status`),
///   clears the stop request and starts up.
/// - `restartAfterStop`: the last run is still stopping; the stop job starts
///   the service again once it is done.
/// - `none`: it is already starting or started.
///
/// Every answer carries the `notification` to show at once, since a start
/// through `startForegroundService` needs `startForeground` within seconds.
pub fn start_command(args: &Value) -> Value {
    let profile = s(args, "profile");
    let current = status(args);
    let phase = if current == STARTED { "started" } else { "starting" };
    let shown = notification(&json!({ "profile": profile, "phase": phase }));
    match current {
        STOPPED => json!({ "action": "start", "status": STARTING, "notification": shown }),
        STOPPING => json!({ "action": "restartAfterStop", "status": STOPPING, "notification": shown }),
        _ => json!({ "action": "none", "status": current, "notification": shown }),
    }
}

/// A stop request (the notification's Stop, `SERVICE_CLOSE`, a revoked VPN)
/// in service `status`: `stop` when the service is starting or started,
/// which then becomes `Stopping`; otherwise it is ignored.
pub fn stop_request(args: &Value) -> Value {
    let current = status(args);
    if current == STARTED || current == STARTING {
        json!({ "stop": true, "status": STOPPING })
    } else {
        json!({ "stop": false, "status": current })
    }
}

/// The stop job finished: the service is `Stopped`, and `restart` when a
/// start arrived while it was stopping (`restartAfterStop`), else it stops
/// itself.
pub fn stop_finished(args: &Value) -> Value {
    json!({ "status": STOPPED, "restart": flag(args, "restartAfterStop") })
}

/// The core started: the service becomes `Started` unless a stop was
/// requested meanwhile (the stop job then owns the state).
pub fn start_succeeded(args: &Value) -> Value {
    if flag(args, "stopRequested") {
        json!({ "started": false })
    } else {
        json!({ "started": true, "status": STARTED, "notification": notification(&json!({ "profile": s(args, "profile"), "phase": "started" })) })
    }
}

/// The selected profile before a start or reload: `selected` (its id, -1 for
/// none), `found` (whether the store has it) and `blank` (its content is
/// empty). Answers the `alert` ending the start, or null when it may go on.
pub fn profile_check(args: &Value) -> Value {
    let selected = args.get("selected").and_then(Value::as_i64).unwrap_or(-1);
    let empty = selected == -1 || !flag(args, "found") || flag(args, "blank");
    json!({ "alert": if empty { json!(ALERT_EMPTY_CONFIGURATION) } else { Value::Null } })
}

/// A start step failed (`stage`): `commandServer` (libbox setup or the
/// command server), `core` (starting or reloading the core), anything else
/// (the rest of the start). Answers its `alert`, and `send` (false while a
/// stop is requested, since the stop job then owns teardown and nothing is
/// reported).
///
/// `block` (the kill switch, Settings › Service): with `killSwitch` on in
/// VPN mode (`vpn`), a failed `core` or later step keeps the VPN up with a
/// TUN nothing reads, so traffic is dropped instead of leaking until the
/// user stops the service. A command server failure still stops, since the
/// service cannot be controlled without it.
pub fn start_failure(args: &Value) -> Value {
    let stage = s(args, "stage");
    let alert = match stage {
        "commandServer" => ALERT_START_COMMAND_SERVER,
        "core" => ALERT_CREATE_SERVICE,
        _ => ALERT_START_SERVICE,
    };
    let send = !flag(args, "stopRequested");
    let block = send && stage != "commandServer" && kill_switch_on(args);
    json!({ "alert": alert, "send": send, "block": block })
}

fn kill_switch_on(args: &Value) -> bool {
    flag(args, "killSwitch") && flag(args, "vpn")
}

/// Whether a start first opens the blocking TUN (kill switch on in VPN
/// mode), so nothing leaks while the core starts; the core's own TUN
/// replaces it.
pub fn start_guard(args: &Value) -> Value {
    json!({ "block": kill_switch_on(args) })
}

/// The notification while the kill switch blocks traffic after a failure:
/// the profile's `title` and the `kill_switch_blocked` text.
pub fn blocked_notification(args: &Value) -> Value {
    let mut shown = notification(&json!({ "profile": s(args, "profile"), "phase": "started" }));
    shown["text"] = json!("kill_switch_blocked");
    shown
}

/// What a page does with a service alert (`type`, the error `message`):
/// `permission` asks for the location permission and retries the start
/// (no error shown); otherwise `message` is the error shown, or null with
/// `fallback`, the string resource used when the service sent no message.
pub fn alert_text(args: &Value) -> Value {
    let kind = args.get("type").and_then(Value::as_i64).unwrap_or(ALERT_START_SERVICE);
    if kind == ALERT_LOCATION_PERMISSION {
        return json!({ "permission": true });
    }
    let fallback = match kind {
        ALERT_VPN_PERMISSION => "error_missing_vpn_permission",
        ALERT_NOTIFICATION_PERMISSION => "notification_permission_required_description",
        ALERT_EMPTY_CONFIGURATION => "error_empty_configuration",
        ALERT_START_COMMAND_SERVER => "error_start_command_server",
        ALERT_CREATE_SERVICE => "error_create_service",
        _ => "error_start_service",
    };
    let message = args.get("message").filter(|m| m.is_string()).cloned().unwrap_or(Value::Null);
    json!({ "permission": false, "message": message, "fallback": fallback })
}

/// Whether a failed reload falls back to the configuration that last
/// started (`previous`: `profileId`, `content`, `routing`; null when none)
/// instead of stopping: only for the same profile (`selected`), and only
/// when its `content` or its routing rules (`currentRouting`, the rules
/// libbox would compile now, null when unreadable) changed since then.
pub fn reload_fallback(args: &Value) -> Value {
    let previous = &args["previous"];
    if previous.is_null() {
        return json!({ "fallback": false });
    }
    let same_profile = previous["profileId"].as_i64().is_some() && previous["profileId"] == args["selected"];
    let content_changed = previous["content"] != args["content"];
    let routing_changed = previous["routing"] != args["currentRouting"];
    json!({ "fallback": same_profile && (content_changed || routing_changed) })
}

/// The permission the core's Wi-Fi state needs on Android `sdk`.
pub fn wifi_permission(args: &Value) -> Value {
    let sdk = args.get("sdk").and_then(Value::as_i64).unwrap_or(ANDROID_Q);
    let name = if sdk < ANDROID_Q { "android.permission.ACCESS_FINE_LOCATION" } else { "android.permission.ACCESS_BACKGROUND_LOCATION" };
    json!({ "permission": name })
}

/// Whether the notification shows the traffic speed once started: the
/// dynamic notification setting (`enabled`) and the notification permission
/// (`permitted`).
pub fn traffic_shown(args: &Value) -> Value {
    json!({ "shown": flag(args, "enabled") && flag(args, "permitted") })
}

/// The traffic line from libbox's formatted `uplink` and `downlink`
/// (`Libbox.formatBytes`), and `post`: false while it repeats `last`, so an
/// idle line is not re-posted every second.
pub fn traffic_text(args: &Value) -> Value {
    let text = format!("{}/s ↑\t{}/s ↓", s(args, "uplink"), s(args, "downlink"));
    let post = args.get("last").and_then(Value::as_str) != Some(text.as_str());
    json!({ "text": text, "post": post })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn start_requests_follow_the_state() {
        let out = start_command(&json!({ "status": STOPPED, "profile": "" }));
        assert_eq!(out["action"], "start");
        assert_eq!(out["status"], STARTING);
        assert_eq!(out["notification"], json!({ "title": "sing-box", "text": "status_starting" }));

        let out = start_command(&json!({ "status": STOPPING, "profile": "Home" }));
        assert_eq!(out["action"], "restartAfterStop");
        assert_eq!(out["status"], STOPPING);
        assert_eq!(out["notification"], json!({ "title": "Home", "text": "status_starting" }));

        let out = start_command(&json!({ "status": STARTED, "profile": "Home" }));
        assert_eq!(out["action"], "none");
        assert_eq!(out["notification"]["text"], "status_started");
        let out = start_command(&json!({ "status": STARTING, "profile": "  " }));
        assert_eq!(out["action"], "none");
        assert_eq!(out["notification"], json!({ "title": "sing-box", "text": "status_starting" }));
    }

    #[test]
    fn stops_only_leave_a_running_service() {
        for running in [STARTING, STARTED] {
            assert_eq!(stop_request(&json!({ "status": running })), json!({ "stop": true, "status": STOPPING }));
        }
        for idle in [STOPPED, STOPPING] {
            assert_eq!(stop_request(&json!({ "status": idle }))["stop"], false);
        }
        assert_eq!(stop_finished(&json!({ "restartAfterStop": true })), json!({ "status": STOPPED, "restart": true }));
        assert_eq!(stop_finished(&json!({})), json!({ "status": STOPPED, "restart": false }));
    }

    #[test]
    fn a_requested_stop_wins_over_the_start() {
        assert_eq!(start_succeeded(&json!({ "stopRequested": true })), json!({ "started": false }));
        let out = start_succeeded(&json!({ "stopRequested": false, "profile": "Work" }));
        assert_eq!(out["status"], STARTED);
        assert_eq!(out["notification"], json!({ "title": "Work", "text": "status_started" }));
        assert_eq!(start_failure(&json!({ "stage": "core", "stopRequested": true }))["send"], false);
    }

    #[test]
    fn profile_checks_and_failures_pick_the_alert() {
        let ok = json!({ "selected": 3, "found": true, "blank": false });
        assert_eq!(profile_check(&ok)["alert"], Value::Null);
        for bad in [
            json!({ "selected": -1, "found": true, "blank": false }),
            json!({ "selected": 3, "found": false }),
            json!({ "selected": 3, "found": true, "blank": true }),
        ] {
            assert_eq!(profile_check(&bad)["alert"], ALERT_EMPTY_CONFIGURATION);
        }
        assert_eq!(start_failure(&json!({ "stage": "commandServer" })), json!({ "alert": ALERT_START_COMMAND_SERVER, "send": true, "block": false }));
        assert_eq!(start_failure(&json!({ "stage": "core" }))["alert"], ALERT_CREATE_SERVICE);
        assert_eq!(start_failure(&json!({ "stage": "service" }))["alert"], ALERT_START_SERVICE);
    }

    #[test]
    fn the_kill_switch_blocks_failures_in_vpn_mode() {
        let on = |stage: &str, vpn: bool, stop: bool| {
            start_failure(&json!({ "stage": stage, "killSwitch": true, "vpn": vpn, "stopRequested": stop }))["block"].clone()
        };
        assert_eq!(on("core", true, false), true);
        assert_eq!(on("service", true, false), true);
        assert_eq!(on("commandServer", true, false), false);
        assert_eq!(on("core", false, false), false);
        assert_eq!(on("core", true, true), false);
        assert_eq!(start_failure(&json!({ "stage": "core", "vpn": true }))["block"], false);
        assert_eq!(start_guard(&json!({ "killSwitch": true, "vpn": true }))["block"], true);
        assert_eq!(start_guard(&json!({ "killSwitch": true, "vpn": false }))["block"], false);
        let shown = blocked_notification(&json!({ "profile": "Home" }));
        assert_eq!(shown, json!({ "title": "Home", "text": "kill_switch_blocked" }));
    }

    #[test]
    fn alert_text_keeps_the_message_or_names_the_fallback() {
        assert_eq!(alert_text(&json!({ "type": ALERT_LOCATION_PERMISSION, "message": "x" })), json!({ "permission": true }));
        assert_eq!(
            alert_text(&json!({ "type": ALERT_CREATE_SERVICE, "message": "bad outbound" })),
            json!({ "permission": false, "message": "bad outbound", "fallback": "error_create_service" })
        );
        let cases = [
            (ALERT_VPN_PERMISSION, "error_missing_vpn_permission"),
            (ALERT_NOTIFICATION_PERMISSION, "notification_permission_required_description"),
            (ALERT_EMPTY_CONFIGURATION, "error_empty_configuration"),
            (ALERT_START_COMMAND_SERVER, "error_start_command_server"),
            (ALERT_START_SERVICE, "error_start_service"),
        ];
        for (kind, fallback) in cases {
            let out = alert_text(&json!({ "type": kind, "message": null }));
            assert_eq!(out["message"], Value::Null);
            assert_eq!(out["fallback"], fallback);
        }
    }

    #[test]
    fn reloads_fall_back_only_for_a_changed_same_profile() {
        let previous = json!({ "profileId": 7, "content": "{}", "routing": "r1" });
        let args = |selected: i64, content: &str, routing: Value| json!({ "previous": previous, "selected": selected, "content": content, "currentRouting": routing });
        assert_eq!(reload_fallback(&args(7, "{ }", json!("r1")))["fallback"], true, "content changed");
        assert_eq!(reload_fallback(&args(7, "{}", json!("r2")))["fallback"], true, "rules changed");
        assert_eq!(reload_fallback(&args(7, "{}", Value::Null))["fallback"], true, "rules now unreadable");
        assert_eq!(reload_fallback(&args(7, "{}", json!("r1")))["fallback"], false, "nothing changed");
        assert_eq!(reload_fallback(&args(8, "{ }", json!("r1")))["fallback"], false, "another profile");
        assert_eq!(reload_fallback(&json!({ "previous": null, "selected": 7 }))["fallback"], false);
        let unread = json!({ "previous": { "profileId": 7, "content": "{}", "routing": null },
            "selected": 7, "content": "{}", "currentRouting": null });
        assert_eq!(reload_fallback(&unread)["fallback"], false);
    }

    #[test]
    fn wifi_permission_follows_the_sdk() {
        assert_eq!(wifi_permission(&json!({ "sdk": 28 }))["permission"], "android.permission.ACCESS_FINE_LOCATION");
        assert_eq!(wifi_permission(&json!({ "sdk": 29 }))["permission"], "android.permission.ACCESS_BACKGROUND_LOCATION");
        assert_eq!(wifi_permission(&json!({ "sdk": 35 }))["permission"], "android.permission.ACCESS_BACKGROUND_LOCATION");
    }

    #[test]
    fn traffic_line_is_posted_only_when_it_changes() {
        assert_eq!(traffic_shown(&json!({ "enabled": true, "permitted": true }))["shown"], true);
        assert_eq!(traffic_shown(&json!({ "enabled": true, "permitted": false }))["shown"], false);
        assert_eq!(traffic_shown(&json!({ "enabled": false, "permitted": true }))["shown"], false);
        let out = traffic_text(&json!({ "uplink": "1.2 KB", "downlink": "0 B", "last": null }));
        assert_eq!(out, json!({ "text": "1.2 KB/s ↑\t0 B/s ↓", "post": true }));
        let again = traffic_text(&json!({ "uplink": "1.2 KB", "downlink": "0 B", "last": "1.2 KB/s ↑\t0 B/s ↓" }));
        assert_eq!(again["post"], false);
    }
}
