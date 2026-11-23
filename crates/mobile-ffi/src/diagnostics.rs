//! The one-shot network diagnostics (phase 4e): STUN, network quality and
//! Tailscale ping, as the deleted Kotlin `STUNTestViewModel`,
//! `NetworkQualityViewModel` and `TailscalePingViewModel` ran them.
//!
//! The host keeps the libbox test objects and sessions (gomobile) and
//! forwards their callbacks raw, tagged with the run id this module gave
//! (`{"kind", "id", "event", "value"}`, [`Diagnostics::event`]). A callback
//! of a run that was cancelled or replaced is dropped, as the view models'
//! `SessionSlot` generations did; a run that finished, failed or was
//! cancelled is no longer running.

use serde_json::{json, Value};

use crate::runtime::Output;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Kind {
    Stun,
    Quality,
    Ping,
}

impl Kind {
    pub fn key(self) -> &'static str {
        match self {
            Kind::Stun => "stun",
            Kind::Quality => "quality",
            Kind::Ping => "ping",
        }
    }

    pub fn from_key(key: &str) -> Option<Kind> {
        match key {
            "stun" => Some(Kind::Stun),
            "quality" => Some(Kind::Quality),
            "ping" => Some(Kind::Ping),
            _ => None,
        }
    }
}

/// libbox `STUNPhaseDone` and `NetworkQualityPhaseDone`, which the host
/// reports with each result ("donePhase").
fn done_phase(value: &Value) -> Value {
    value.get("donePhase").cloned().unwrap_or(json!(-1))
}

#[derive(Debug)]
struct Run {
    id: u64,
    running: bool,
}

#[derive(Debug)]
pub struct Diagnostics {
    next_id: u64,
    stun: Run,
    stun_state: Value,
    quality: Run,
    quality_state: Value,
    /// The network quality test waiting for the metered-network confirmation.
    quality_pending: Option<Value>,
    ping: Run,
    ping_state: Value,
}

impl Default for Diagnostics {
    fn default() -> Self {
        Diagnostics {
            next_id: 0,
            stun: Run { id: 0, running: false },
            stun_state: json!({ "phase": -1, "externalAddr": "", "latencyMs": 0, "natTypeSupported": false,
                "isRunning": false, "server": "", "mapping": "", "filtering": "" }),
            quality: Run { id: 0, running: false },
            quality_state: json!({ "phase": -1, "idleLatencyMs": 0, "downloadCapacity": 0, "uploadCapacity": 0,
                "downloadRPM": 0, "uploadRPM": 0, "isRunning": false, "configURL": "", "serial": false,
                "http3": false, "showMeteredWarning": false }),
            quality_pending: None,
            ping: Run { id: 0, running: false },
            ping_state: json!({ "isRunning": false, "latencyMs": 0.0, "isDirect": false, "derpRegionCode": "",
                "endpoint": "", "error": "" }),
        }
    }
}

fn set(state: &mut Value, key: &str, value: Value) {
    state[key] = value;
}

fn copy(state: &mut Value, from: &Value, keys: &[&str]) {
    for key in keys {
        if let Some(v) = from.get(*key) {
            state[*key] = v.clone();
        }
    }
}

const STUN_KEYS: &[&str] = &["externalAddr", "latencyMs", "mapping", "filtering"];
const QUALITY_KEYS: &[&str] = &["idleLatencyMs", "downloadCapacity", "uploadCapacity", "downloadRPM", "uploadRPM"];

impl Diagnostics {
    /// The host's defaults: `stunServer`, `qualityURL`.
    pub fn defaults(&mut self, defaults: &Value) {
        if let Some(server) = defaults["stunServer"].as_str() {
            set(&mut self.stun_state, "server", json!(server));
        }
        if let Some(url) = defaults["qualityURL"].as_str() {
            set(&mut self.quality_state, "configURL", json!(url));
        }
    }

    pub fn state(&self, kind: Kind) -> &Value {
        match kind {
            Kind::Stun => &self.stun_state,
            Kind::Quality => &self.quality_state,
            Kind::Ping => &self.ping_state,
        }
    }

    pub fn running(&self, kind: Kind) -> bool {
        self.run(kind).running
    }

    fn run(&self, kind: Kind) -> &Run {
        match kind {
            Kind::Stun => &self.stun,
            Kind::Quality => &self.quality,
            Kind::Ping => &self.ping,
        }
    }

    fn run_mut(&mut self, kind: Kind) -> &mut Run {
        match kind {
            Kind::Stun => &mut self.stun,
            Kind::Quality => &mut self.quality,
            Kind::Ping => &mut self.ping,
        }
    }

    fn state_mut(&mut self, kind: Kind) -> &mut Value {
        match kind {
            Kind::Stun => &mut self.stun_state,
            Kind::Quality => &mut self.quality_state,
            Kind::Ping => &mut self.ping_state,
        }
    }

    fn publish(&mut self, kind: Kind) -> Output {
        let running = self.run(kind).running;
        set(self.state_mut(kind), "isRunning", json!(running));
        Output::Event(kind.key(), self.state(kind).clone())
    }

    fn begin(&mut self, kind: Kind) -> u64 {
        self.next_id += 1;
        let id = self.next_id;
        *self.run_mut(kind) = Run { id, running: true };
        id
    }

    /// Starts a STUN test against `server`, through `outbound` when the
    /// service runs (`service`).
    pub fn start_stun(&mut self, server: &str, outbound: &str, service: bool) -> Result<Vec<Output>, String> {
        if self.stun.running {
            return Err("A STUN test is already running".into());
        }
        let id = self.begin(Kind::Stun);
        let s = &mut self.stun_state;
        set(s, "server", json!(server));
        set(s, "phase", json!(-1));
        set(s, "externalAddr", json!(""));
        set(s, "latencyMs", json!(0));
        set(s, "mapping", json!(""));
        set(s, "filtering", json!(""));
        set(s, "natTypeSupported", json!(false));
        Ok(vec![
            self.publish(Kind::Stun),
            Output::Host("tools.runDiagnostic", json!({ "kind": "stun", "id": id, "service": service,
                "server": server, "outbound": outbound })),
        ])
    }

    /// Asks to start a network quality test. On a metered network
    /// (`metered`) it waits for [`Diagnostics::confirm_quality`].
    pub fn start_quality(&mut self, args: Value, metered: bool, service: bool) -> Result<Vec<Output>, String> {
        if self.quality.running {
            return Err("A network quality test is already running".into());
        }
        let q = &mut self.quality_state;
        copy(q, &args, &["serial", "http3"]);
        if let Some(url) = args.get("url") {
            set(q, "configURL", url.clone());
        }
        if metered {
            set(q, "showMeteredWarning", json!(true));
            self.quality_pending = Some(args);
            return Ok(vec![self.publish(Kind::Quality)]);
        }
        Ok(self.run_quality(args, service))
    }

    fn run_quality(&mut self, args: Value, service: bool) -> Vec<Output> {
        let id = self.begin(Kind::Quality);
        let q = &mut self.quality_state;
        set(q, "showMeteredWarning", json!(false));
        set(q, "phase", json!(-1));
        for key in QUALITY_KEYS {
            set(q, key, json!(0));
        }
        let seconds = if args["seconds"].as_i64() == Some(60) { 60 } else { 30 };
        vec![
            self.publish(Kind::Quality),
            Output::Host("tools.runDiagnostic", json!({ "kind": "quality", "id": id, "service": service,
                "url": args["url"], "outbound": args["outbound"], "serial": args["serial"].as_bool().unwrap_or(false),
                "http3": args["http3"].as_bool().unwrap_or(false), "seconds": seconds })),
        ]
    }

    /// The metered-network warning was answered.
    pub fn confirm_quality(&mut self, start: bool, service: bool) -> Vec<Output> {
        set(&mut self.quality_state, "showMeteredWarning", json!(false));
        match self.quality_pending.take() {
            Some(args) if start && !self.quality.running => self.run_quality(args, service),
            _ => vec![self.publish(Kind::Quality)],
        }
    }

    /// Pings a Tailscale peer at `address` through `endpoint`; a new ping
    /// replaces the last.
    pub fn start_ping(&mut self, endpoint: &str, address: &str) -> Vec<Output> {
        let mut out = Vec::new();
        if self.ping.running {
            out.push(Output::Host("tools.cancelDiagnostic", json!({ "kind": "ping" })));
        }
        let id = self.begin(Kind::Ping);
        set(&mut self.ping_state, "error", json!(""));
        out.push(self.publish(Kind::Ping));
        out.push(Output::Host("tools.runDiagnostic", json!({ "kind": "ping", "id": id, "endpoint": endpoint, "address": address })));
        out
    }

    /// Stops a run (and closes its host session).
    pub fn cancel(&mut self, kind: Kind) -> Vec<Output> {
        self.next_id += 1;
        let id = self.next_id;
        *self.run_mut(kind) = Run { id, running: false };
        vec![self.publish(kind), Output::Host("tools.cancelDiagnostic", json!({ "kind": kind.key() }))]
    }

    /// The service went away or the session target changed: every run stops.
    pub fn cancel_all(&mut self) -> Vec<Output> {
        let mut out = Vec::new();
        for kind in [Kind::Stun, Kind::Quality, Kind::Ping] {
            if self.run(kind).running {
                out.extend(self.cancel(kind));
            }
        }
        if self.quality_pending.take().is_some() {
            set(&mut self.quality_state, "showMeteredWarning", json!(false));
            out.push(self.publish(Kind::Quality));
        }
        out
    }

    /// One forwarded callback: `progress`, `result`, `error` (the run's own
    /// message) or `failed` (it could not start).
    pub fn event(&mut self, event: &Value) -> Vec<Output> {
        let Some(kind) = event["kind"].as_str().and_then(Kind::from_key) else { return vec![] };
        let run = self.run(kind);
        if event["id"].as_u64() != Some(run.id) || !run.running {
            return vec![];
        }
        let value = &event["value"];
        let mut out = Vec::new();
        match (kind, event["event"].as_str().unwrap_or_default()) {
            (Kind::Stun, "progress") => {
                copy(&mut self.stun_state, value, &["phase"]);
                copy(&mut self.stun_state, value, STUN_KEYS);
            }
            (Kind::Stun, "result") => {
                set(&mut self.stun_state, "phase", done_phase(value));
                copy(&mut self.stun_state, value, STUN_KEYS);
                copy(&mut self.stun_state, value, &["natTypeSupported"]);
                self.stun.running = false;
            }
            (Kind::Quality, "progress") => {
                copy(&mut self.quality_state, value, &["phase"]);
                copy(&mut self.quality_state, value, QUALITY_KEYS);
            }
            (Kind::Quality, "result") => {
                set(&mut self.quality_state, "phase", done_phase(value));
                copy(&mut self.quality_state, value, QUALITY_KEYS);
                self.quality.running = false;
            }
            (Kind::Ping, "result") => {
                let error = value["error"].as_str().unwrap_or_default();
                if error.is_empty() {
                    copy(&mut self.ping_state, value, &["latencyMs", "isDirect", "derpRegionCode", "endpoint"]);
                }
                set(&mut self.ping_state, "error", json!(error));
            }
            (Kind::Ping, "error") | (Kind::Ping, "failed") => {
                // A ping that ends or cannot start stops quietly.
                self.ping.running = false;
            }
            (_, "error") | (_, "failed") => {
                self.run_mut(kind).running = false;
                if let Some(message) = value.as_str().filter(|m| !m.is_empty()) {
                    out.push(Output::Error(message.to_string()));
                }
            }
            _ => return vec![],
        }
        out.insert(0, self.publish(kind));
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn event_of(out: &[Output], name: &str) -> Option<Value> {
        out.iter().rev().find_map(|o| match o {
            Output::Event(n, v) if *n == name => Some(v.clone()),
            _ => None,
        })
    }

    fn host_of(out: &[Output]) -> Option<(&'static str, Value)> {
        out.iter().find_map(|o| match o {
            Output::Host(m, v) => Some((*m, v.clone())),
            _ => None,
        })
    }

    #[test]
    fn stun_runs_once_and_drops_stale_callbacks() {
        let mut d = Diagnostics::default();
        d.defaults(&json!({ "stunServer": "stun.example:3478" }));
        assert_eq!(d.state(Kind::Stun)["server"], "stun.example:3478");
        let out = d.start_stun("stun.l.google.com:19302", "proxy", true).unwrap();
        let (method, args) = host_of(&out).unwrap();
        assert_eq!(method, "tools.runDiagnostic");
        assert_eq!(args["outbound"], "proxy");
        let id = args["id"].as_u64().unwrap();
        assert!(d.start_stun("x", "", true).is_err());
        let progress = json!({ "kind": "stun", "id": id, "event": "progress", "value": { "phase": 1, "externalAddr": "1.2.3.4:5" } });
        assert_eq!(event_of(&d.event(&progress), "stun").unwrap()["externalAddr"], "1.2.3.4:5");
        let out = d.event(&json!({ "kind": "stun", "id": id, "event": "result",
            "value": { "donePhase": 9, "externalAddr": "1.2.3.4:5", "latencyMs": 20, "natTypeSupported": true, "mapping": "EIM" } }));
        let state = event_of(&out, "stun").unwrap();
        assert_eq!(state["phase"], 9);
        assert_eq!(state["isRunning"], false);
        assert_eq!(state["mapping"], "EIM");
        assert!(d.event(&progress).is_empty(), "a finished run takes no more callbacks");
        // A cancelled run's callbacks are dropped too.
        let id = host_of(&d.start_stun("s", "", false).unwrap()).unwrap().1["id"].as_u64().unwrap();
        d.cancel(Kind::Stun);
        assert!(d.event(&json!({ "kind": "stun", "id": id, "event": "progress", "value": { "phase": 2 } })).is_empty());
    }

    #[test]
    fn quality_waits_for_metered_confirmation() {
        let mut d = Diagnostics::default();
        let args = json!({ "url": "https://q.example/config", "outbound": "", "serial": true, "http3": false, "seconds": 60 });
        let out = d.start_quality(args.clone(), true, false).unwrap();
        assert!(host_of(&out).is_none());
        assert_eq!(d.state(Kind::Quality)["showMeteredWarning"], true);
        assert!(!d.running(Kind::Quality));
        let out = d.confirm_quality(false, false);
        assert!(host_of(&out).is_none());
        assert_eq!(d.state(Kind::Quality)["showMeteredWarning"], false);
        d.start_quality(args, true, false).unwrap();
        let out = d.confirm_quality(true, false);
        let (_, run) = host_of(&out).unwrap();
        assert_eq!(run["seconds"], 60);
        assert_eq!(run["serial"], true);
        assert!(d.running(Kind::Quality));
    }

    #[test]
    fn failures_stop_and_report() {
        let mut d = Diagnostics::default();
        let id = host_of(&d.start_quality(json!({ "url": "u" }), false, true).unwrap()).unwrap().1["id"].as_u64().unwrap();
        let out = d.event(&json!({ "kind": "quality", "id": id, "event": "failed", "value": "boom" }));
        assert!(out.contains(&Output::Error("boom".into())));
        assert!(!d.running(Kind::Quality));
    }

    #[test]
    fn ping_replaces_and_keeps_last_good_result_on_error_reply() {
        let mut d = Diagnostics::default();
        let first = host_of(&d.start_ping("ts", "100.64.0.2")).unwrap().1["id"].as_u64().unwrap();
        let out = d.start_ping("ts", "100.64.0.3");
        assert_eq!(host_of(&out).unwrap().0, "tools.cancelDiagnostic");
        assert!(d.event(&json!({ "kind": "ping", "id": first, "event": "result", "value": { "latencyMs": 1.0 } })).is_empty());
        let id = first + 1;
        let ok = json!({ "kind": "ping", "id": id, "event": "result", "value": { "latencyMs": 12.5, "isDirect": true, "error": "" } });
        assert_eq!(event_of(&d.event(&ok), "ping").unwrap()["latencyMs"], 12.5);
        let bad = json!({ "kind": "ping", "id": id, "event": "result", "value": { "latencyMs": 0.0, "error": "timeout" } });
        let state = event_of(&d.event(&bad), "ping").unwrap();
        assert_eq!(state["error"], "timeout");
        assert_eq!(state["latencyMs"], 12.5);
        assert_eq!(state["isRunning"], true);
        let out = d.event(&json!({ "kind": "ping", "id": id, "event": "error", "value": "closed" }));
        assert_eq!(event_of(&out, "ping").unwrap()["isRunning"], false);
        assert!(!out.iter().any(|o| matches!(o, Output::Error(_))));
    }

    #[test]
    fn cancel_all_stops_running_tests() {
        let mut d = Diagnostics::default();
        d.start_stun("s", "", true).unwrap();
        d.start_ping("ts", "a");
        let out = d.cancel_all();
        assert!(!d.running(Kind::Stun) && !d.running(Kind::Ping));
        assert_eq!(out.iter().filter(|o| matches!(o, Output::Host("tools.cancelDiagnostic", _))).count(), 2);
        assert!(d.cancel_all().is_empty());
    }
}
