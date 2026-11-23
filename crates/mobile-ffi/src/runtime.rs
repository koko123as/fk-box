//! The kernel stream runtime (phase 4d): what the Kotlin view models did with
//! the libbox command streams, now in Rust.
//!
//! The host keeps only the libbox command clients (they are Go objects behind
//! gomobile) and forwards every callback raw: [`Runtime::stream`] takes one
//! (`{"stream", "kind", "value"}`), [`Runtime::host_state`] the service and
//! remote session state, [`Runtime::probe`] the answer to a dashboard probe.
//! Each returns what the UI applies ([`Output`]): the same events the host
//! used to compose itself ("dashboard", "groups", "rates", "connectionEvents",
//! "logEvents"), plus the probes to ask the host for.
//!
//! The rules are the old view models':
//! - dashboard (`DashboardViewModel`): a local service that starts is probed
//!   for its start time, system proxy and deprecation notes (a remote one for
//!   the notes only); a local stop clears the start time, the mode bar, the
//!   system proxy and the traffic flag; traffic is only ever turned on by a
//!   status message; reconnecting the mode stream to a started local service
//!   probes again;
//! - groups (`GroupsViewModel`): a local stop clears them; a lost stream keeps
//!   the last groups only in the background for a started local service;
//! - connections (`ConnectionsViewModel`): a local stop or a lost stream sends
//!   a reset; event batches pass through in order;
//! - logs (`LogViewModel`): lines wait in a buffer of at most [`LOG_LIMIT`]
//!   until the UI drains it ([`Runtime::drain_logs`]); a clear empties it and
//!   makes the next batch a reset; the default level goes once per change.

use std::collections::VecDeque;

use serde_json::{json, Map, Value};

use crate::diagnostics::Diagnostics;

/// The most log lines waiting for the UI (the UI keeps no more).
pub const LOG_LIMIT: usize = 3000;

/// What to probe a started service for.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Probe {
    /// Start time and system proxy status (local service only).
    pub local: bool,
    /// Deprecation notes.
    pub notes: bool,
}

#[derive(Debug, Clone, PartialEq)]
pub enum Output {
    /// An event for the UI, as the host used to send it.
    Event(&'static str, Value),
    /// Ask the host for a dashboard probe (`dashboardProbe`), then pass its
    /// answer to [`Runtime::probe`].
    Probe(Probe),
    /// Log lines wait: drain them with [`Runtime::drain_logs`] (the UI
    /// throttles how often).
    LogsPending,
    /// A host command to send, its failure reported (`tools.runDiagnostic`,
    /// `tools.cancelDiagnostic`).
    Host(&'static str, Value),
    /// An error to show.
    Error(String),
}

#[derive(Debug, Default)]
struct Dashboard {
    status: String,
    selected_profile: Value,
    started_at: Value,
    remote_started_at: Value,
    traffic: bool,
    mode_visible: bool,
    modes: Vec<String>,
    mode: String,
    proxy_visible: bool,
    proxy: bool,
    proxy_switching: bool,
    remote: Value,
    remote_connected: bool,
    deprecated: Value,
}

#[derive(Debug, Default)]
struct Logs {
    reset: bool,
    level: Option<i64>,
    level_changed: bool,
    lines: VecDeque<Value>,
}

#[derive(Debug, Default)]
pub struct Runtime {
    /// The STUN, network quality and Tailscale ping runs (diagnostics.rs).
    pub diagnostics: Diagnostics,
    /// Whether the service (local, or the remote session) is reachable, and
    /// the remote server's id (0 locally), as last reported.
    target: Option<(bool, i64)>,
    dashboard: Dashboard,
    groups: Vec<Value>,
    foreground: bool,
    logs: Logs,
}

fn text(value: &Value) -> String {
    value.as_str().unwrap_or_default().to_string()
}

impl Runtime {
    pub fn new() -> Self {
        Runtime { dashboard: Dashboard { status: "Stopped".into(), ..Default::default() }, foreground: true, ..Default::default() }
    }

    fn remote(&self) -> bool {
        !self.dashboard.remote.is_null()
    }

    fn started(&self) -> bool {
        self.dashboard.status == "Started"
    }

    /// Whether the service the tools act on is reachable: the remote session
    /// when one is chosen, otherwise the local service.
    pub fn service_available(&self) -> bool {
        if self.remote() { self.dashboard.remote_connected } else { self.started() }
    }

    /// The dashboard as the host's `DashboardUiState.toWire` sent it (the
    /// UI's `dashboard::view` reads it).
    pub fn dashboard(&self) -> Value {
        let d = &self.dashboard;
        json!({
            "status": d.status,
            "selectedProfileId": d.selected_profile,
            "startedAt": d.started_at,
            "remoteStartedAt": d.remote_started_at,
            "trafficVisible": d.traffic,
            "clashModeVisible": d.mode_visible,
            "modes": d.modes,
            "mode": d.mode,
            "systemProxyVisible": d.proxy_visible,
            "systemProxy": d.proxy,
            "remote": d.remote,
            "remoteConnected": d.remote_connected,
            "deprecated": d.deprecated.as_array().cloned().unwrap_or_default(),
        })
    }

    fn dashboard_event(&self) -> Output {
        Output::Event("dashboard", self.dashboard())
    }

    /// Whether the system proxy can be switched now (local, available, not
    /// already switching).
    pub fn system_proxy_switchable(&self) -> bool {
        !self.remote() && self.dashboard.proxy_visible && !self.dashboard.proxy_switching
    }

    /// A system proxy switch was sent to the host.
    pub fn system_proxy_switching(&mut self) {
        self.dashboard.proxy_switching = true;
    }

    /// The host answered a system proxy switch.
    pub fn system_proxy_switched(&mut self, enabled: Option<bool>) -> Vec<Output> {
        self.dashboard.proxy_switching = false;
        match enabled {
            Some(enabled) => {
                self.dashboard.proxy = enabled;
                vec![self.dashboard_event()]
            }
            None => vec![],
        }
    }

    /// The app went to the background (false) or came back (true).
    pub fn set_foreground(&mut self, foreground: bool) {
        self.foreground = foreground;
    }

    /// The host's service and remote session state: `status`
    /// (Stopped/Starting/Started/Stopping), `selectedProfileId`, `remote`
    /// (the remote server's name or null), `remoteConnected`,
    /// `remoteStartedAt`.
    pub fn host_state(&mut self, state: &Value) -> Vec<Output> {
        let mut out = Vec::new();
        let d = &mut self.dashboard;
        d.selected_profile = state["selectedProfileId"].clone();
        d.remote = state["remote"].clone();
        d.remote_connected = state["remoteConnected"].as_bool().unwrap_or(false);
        d.remote_started_at = state["remoteStartedAt"].clone();
        let status = state["status"].as_str().map(str::to_string);
        let changed = match status {
            Some(status) if status != d.status => {
                d.status = status;
                true
            }
            _ => false,
        };
        let remote = self.remote();
        // Losing the service or switching to another target stops the
        // diagnostics (the old BoxTools collector).
        let target = (self.service_available(), state["remoteId"].as_i64().unwrap_or(0));
        if let Some(previous) = self.target.replace(target) {
            if previous != target && (previous.1 != target.1 || !target.0) {
                out.extend(self.diagnostics.cancel_all());
            }
        }
        if changed {
            match self.dashboard.status.as_str() {
                "Started" => out.push(Output::Probe(Probe { local: !remote, notes: true })),
                "Stopped" => {
                    if !remote {
                        let d = &mut self.dashboard;
                        d.started_at = Value::Null;
                        d.mode_visible = false;
                        d.proxy_visible = false;
                        d.traffic = false;
                    }
                    out.push(Output::Event("rates", json!({ "upload": 0, "download": 0 })));
                }
                _ => {}
            }
            if !remote && !self.started() {
                self.groups.clear();
                out.push(Output::Event("groups", json!([])));
                out.push(Output::Event("connectionEvents", json!({ "reset": true, "events": [] })));
            }
        }
        out.push(self.dashboard_event());
        out
    }

    /// The host's answer to a dashboard probe: `startedAt`, `systemProxy`
    /// (`available`, `enabled`) and `deprecated` (`message`, `url`), each
    /// present only when probed and read.
    pub fn probe(&mut self, result: &Value) -> Vec<Output> {
        let d = &mut self.dashboard;
        if let Some(started_at) = result.get("startedAt").filter(|v| !v.is_null()) {
            d.started_at = started_at.clone();
        }
        if let Some(proxy) = result.get("systemProxy").filter(|v| v.is_object()) {
            d.proxy_visible = proxy["available"].as_bool().unwrap_or(false);
            d.proxy = proxy["enabled"].as_bool().unwrap_or(false);
        }
        if let Some(notes) = result.get("deprecated").filter(|v| v.as_array().is_some_and(|a| !a.is_empty())) {
            d.deprecated = notes.clone();
        }
        vec![self.dashboard_event()]
    }

    /// One forwarded libbox callback.
    pub fn stream(&mut self, event: &Value) -> Vec<Output> {
        let stream = event["stream"].as_str().unwrap_or_default();
        let kind = event["kind"].as_str().unwrap_or_default();
        let value = &event["value"];
        match (stream, kind) {
            ("status", "status") => self.status(value),
            ("clashMode", "connected") if !self.remote() && self.started() => {
                vec![Output::Probe(Probe { local: true, notes: false })]
            }
            ("clashMode", "init") => {
                let modes: Vec<String> = value["modes"].as_array().map(|m| m.iter().map(text).collect()).unwrap_or_default();
                let d = &mut self.dashboard;
                d.mode_visible = modes.len() > 1;
                d.modes = modes;
                d.mode = text(&value["mode"]);
                vec![self.dashboard_event()]
            }
            ("clashMode", "mode") => {
                self.dashboard.mode = text(value);
                vec![self.dashboard_event()]
            }
            ("groups", "groups") => {
                let groups = value.as_array().cloned().unwrap_or_default();
                if groups == self.groups {
                    return vec![];
                }
                self.groups = groups;
                vec![Output::Event("groups", Value::Array(self.groups.clone()))]
            }
            ("groups", "disconnected") => {
                // Closing the subscription in the background does not change
                // the local route: keep the snapshot until fresh groups come.
                let keep = !self.foreground && !self.remote() && self.started();
                if keep || self.groups.is_empty() {
                    return vec![];
                }
                self.groups.clear();
                vec![Output::Event("groups", json!([]))]
            }
            ("connections", "events") => {
                let mut batch = Map::new();
                batch.insert("reset".into(), json!(value["reset"].as_bool().unwrap_or(false)));
                batch.insert("events".into(), value["events"].as_array().map(|e| Value::Array(e.clone())).unwrap_or(json!([])));
                batch.insert("dnsQueries".into(), value["dnsQueries"].clone());
                vec![Output::Event("connectionEvents", Value::Object(batch))]
            }
            ("connections", "disconnected") => {
                vec![Output::Event("connectionEvents", json!({ "reset": true, "events": [] }))]
            }
            ("log", "level") => {
                if let Some(level) = value.as_i64() {
                    if self.logs.level != Some(level) || self.logs.level_changed {
                        self.logs.level = Some(level);
                        self.logs.level_changed = true;
                    }
                }
                vec![Output::LogsPending]
            }
            ("log", "clear") => {
                self.clear_logs();
                vec![Output::LogsPending]
            }
            ("log", "lines") => {
                let lines = value.as_array().map(Vec::as_slice).unwrap_or_default();
                let skip = lines.len().saturating_sub(LOG_LIMIT);
                for line in &lines[skip..] {
                    if self.logs.lines.len() == LOG_LIMIT {
                        self.logs.lines.pop_front();
                    }
                    self.logs.lines.push_back(line.clone());
                }
                vec![Output::LogsPending]
            }
            _ => vec![],
        }
    }

    fn status(&mut self, status: &Value) -> Vec<Output> {
        let mut out = vec![Output::Event(
            "rates",
            json!({
                "upload": status["uplink"], "download": status["downlink"], "memoryBytes": status["memory"],
                "uploadTotal": status["uplinkTotalText"], "downloadTotal": status["downlinkTotalText"],
                "connections": status["connectionsIn"],
            }),
        )];
        // Traffic only ever turns on from a status message.
        if status["trafficAvailable"].as_bool() == Some(true) && !self.dashboard.traffic {
            self.dashboard.traffic = true;
            out.push(self.dashboard_event());
        }
        out
    }

    /// Clears the waiting log lines (a clear the host could not broadcast,
    /// such as with the service stopped).
    pub fn clear_logs(&mut self) {
        self.logs.reset = true;
        self.logs.lines.clear();
    }

    /// The log lines that arrived since the last drain, as one batch for
    /// client-core `Logs::apply_batch`; None when nothing waits.
    pub fn drain_logs(&mut self) -> Option<Value> {
        let logs = &mut self.logs;
        if !logs.reset && !logs.level_changed && logs.lines.is_empty() {
            return None;
        }
        let mut batch = Map::new();
        batch.insert("reset".into(), json!(logs.reset));
        if logs.level_changed {
            batch.insert("defaultLogLevel".into(), json!(logs.level));
        }
        batch.insert("entries".into(), Value::Array(logs.lines.drain(..).collect()));
        logs.reset = false;
        logs.level_changed = false;
        Some(Value::Object(batch))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn event(out: &[Output], name: &str) -> Option<Value> {
        out.iter().rev().find_map(|o| match o {
            Output::Event(n, v) if *n == name => Some(v.clone()),
            _ => None,
        })
    }

    fn state(status: &str, remote: Value) -> Value {
        json!({ "status": status, "selectedProfileId": 3, "remote": remote, "remoteConnected": false })
    }

    #[test]
    fn local_start_probes_and_stop_clears() {
        let mut rt = Runtime::new();
        let out = rt.host_state(&state("Started", Value::Null));
        assert!(out.contains(&Output::Probe(Probe { local: true, notes: true })));
        rt.probe(&json!({ "startedAt": 100, "systemProxy": { "available": true, "enabled": true } }));
        rt.stream(&json!({ "stream": "clashMode", "kind": "init", "value": { "modes": ["Rule", "Global"], "mode": "Rule" } }));
        rt.stream(&json!({ "stream": "status", "kind": "status", "value": { "trafficAvailable": true } }));
        let d = rt.dashboard();
        assert_eq!(d["startedAt"], 100);
        assert_eq!(d["clashModeVisible"], true);
        assert_eq!(d["trafficVisible"], true);
        assert!(rt.system_proxy_switchable());
        let out = rt.host_state(&state("Stopped", Value::Null));
        let d = event(&out, "dashboard").unwrap();
        assert_eq!(d["startedAt"], Value::Null);
        assert_eq!(d["clashModeVisible"], false);
        assert_eq!(d["systemProxyVisible"], false);
        assert_eq!(d["trafficVisible"], false);
        assert_eq!(event(&out, "groups"), Some(json!([])));
        assert_eq!(event(&out, "connectionEvents").unwrap()["reset"], true);
        assert_eq!(event(&out, "rates").unwrap()["upload"], 0);
    }

    #[test]
    fn remote_start_probes_notes_only_and_stop_keeps_state() {
        let mut rt = Runtime::new();
        let out = rt.host_state(&state("Started", json!("Server")));
        assert!(out.contains(&Output::Probe(Probe { local: false, notes: true })));
        rt.stream(&json!({ "stream": "groups", "kind": "groups", "value": [{ "tag": "a" }] }));
        let out = rt.host_state(&state("Stopped", json!("Server")));
        assert_eq!(event(&out, "groups"), None);
        assert!(!rt.system_proxy_switchable());
    }

    #[test]
    fn mode_stream_reconnect_probes_started_local_service() {
        let mut rt = Runtime::new();
        let connected = json!({ "stream": "clashMode", "kind": "connected" });
        assert!(rt.stream(&connected).is_empty());
        rt.host_state(&state("Started", Value::Null));
        assert_eq!(rt.stream(&connected), vec![Output::Probe(Probe { local: true, notes: false })]);
    }

    #[test]
    fn traffic_never_turns_off_from_status() {
        let mut rt = Runtime::new();
        rt.stream(&json!({ "stream": "status", "kind": "status", "value": { "trafficAvailable": true, "uplink": 5 } }));
        let out = rt.stream(&json!({ "stream": "status", "kind": "status", "value": { "trafficAvailable": false } }));
        assert_eq!(out.len(), 1);
        assert_eq!(rt.dashboard()["trafficVisible"], true);
    }

    #[test]
    fn groups_snapshot_kept_only_in_background_for_started_local_service() {
        let mut rt = Runtime::new();
        rt.host_state(&state("Started", Value::Null));
        let groups = json!({ "stream": "groups", "kind": "groups", "value": [{ "tag": "a" }] });
        assert_eq!(event(&rt.stream(&groups), "groups"), Some(json!([{ "tag": "a" }])));
        assert!(rt.stream(&groups).is_empty(), "unchanged groups are not resent");
        let lost = json!({ "stream": "groups", "kind": "disconnected" });
        rt.set_foreground(false);
        assert!(rt.stream(&lost).is_empty());
        rt.set_foreground(true);
        assert_eq!(event(&rt.stream(&lost), "groups"), Some(json!([])));
    }

    #[test]
    fn connection_batches_pass_and_loss_resets() {
        let mut rt = Runtime::new();
        let out = rt.stream(&json!({ "stream": "connections", "kind": "events",
            "value": { "reset": false, "events": [{ "id": "1" }], "dnsQueries": 4 } }));
        assert_eq!(event(&out, "connectionEvents"), Some(json!({ "reset": false, "events": [{ "id": "1" }], "dnsQueries": 4 })));
        let out = rt.stream(&json!({ "stream": "connections", "kind": "disconnected" }));
        assert_eq!(event(&out, "connectionEvents").unwrap()["reset"], true);
    }

    #[test]
    fn log_buffer_bounds_clears_and_sends_level_once() {
        let mut rt = Runtime::new();
        assert_eq!(rt.drain_logs(), None);
        rt.stream(&json!({ "stream": "log", "kind": "level", "value": 4 }));
        let lines: Vec<Value> = (0..LOG_LIMIT + 5).map(|i| json!({ "level": 4, "message": i.to_string() })).collect();
        rt.stream(&json!({ "stream": "log", "kind": "lines", "value": lines }));
        let batch = rt.drain_logs().unwrap();
        assert_eq!(batch["defaultLogLevel"], 4);
        assert_eq!(batch["reset"], false);
        let entries = batch["entries"].as_array().unwrap();
        assert_eq!(entries.len(), LOG_LIMIT);
        assert_eq!(entries[0]["message"], "5");
        rt.stream(&json!({ "stream": "log", "kind": "level", "value": 4 }));
        assert_eq!(rt.drain_logs(), None, "an unchanged level is not resent");
        rt.stream(&json!({ "stream": "log", "kind": "lines", "value": [{ "message": "x" }] }));
        rt.stream(&json!({ "stream": "log", "kind": "clear" }));
        let batch = rt.drain_logs().unwrap();
        assert_eq!(batch["reset"], true);
        assert_eq!(batch["entries"], json!([]));
    }

    #[test]
    fn losing_the_service_or_switching_target_stops_diagnostics() {
        let mut rt = Runtime::new();
        rt.host_state(&state("Started", Value::Null));
        rt.diagnostics.start_stun("s", "", true).unwrap();
        let out = rt.host_state(&state("Stopped", Value::Null));
        assert!(out.iter().any(|o| matches!(o, Output::Host("tools.cancelDiagnostic", _))));
        rt.host_state(&state("Started", Value::Null));
        rt.diagnostics.start_ping("ts", "a");
        // Selecting a remote server (connected) switches the target.
        let mut remote = state("Started", json!("Server"));
        remote["remoteId"] = json!(7);
        remote["remoteConnected"] = json!(true);
        let out = rt.host_state(&remote);
        assert!(out.iter().any(|o| matches!(o, Output::Host("tools.cancelDiagnostic", _))));
        assert!(rt.service_available());
    }

    #[test]
    fn system_proxy_switch_round_trip() {
        let mut rt = Runtime::new();
        rt.host_state(&state("Started", Value::Null));
        rt.probe(&json!({ "systemProxy": { "available": true, "enabled": false } }));
        assert!(rt.system_proxy_switchable());
        rt.system_proxy_switching();
        assert!(!rt.system_proxy_switchable());
        let out = rt.system_proxy_switched(Some(true));
        assert_eq!(event(&out, "dashboard").unwrap()["systemProxy"], true);
        assert!(rt.system_proxy_switchable());
    }
}
