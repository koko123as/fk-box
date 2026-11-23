//! The Taildrop inbox (phase 4e3a), as the deleted Kotlin
//! `TaildropViewModel` and the inbox part of `BoxTools` kept it: which
//! endpoint's inbox the open page shows, when the host's libbox inbox
//! subscription opens and closes, the received and receiving files, and the
//! file checks of the inbox commands.
//!
//! The UI's `tools.subscribeTaildrop` / `tools.unsubscribeTaildrop` stay
//! here ([`Inbox::command`]). The host opens the subscription on
//! `tools.openTaildropInbox` (`endpoint`, `run`) and forwards its updates as
//! "toolStatus" events of kind "taildrop" carrying the run; a run that is no
//! longer current is dropped. The tool status target ([`Inbox::target`])
//! decides whether it may be open: the endpoint is forgotten when the
//! session changes or its service goes away, and kept while the app is only
//! in the background.

use serde_json::{json, Value};

use crate::runtime::Output;

#[derive(Debug, Default)]
pub struct Inbox {
    /// The endpoint the inbox page shows.
    wanted: Option<String>,
    /// The run of the subscription the host has open, if any.
    open: Option<u64>,
    run: u64,
    connected: bool,
    session: Option<i64>,
    files: Vec<Value>,
    receiving: Vec<Value>,
}

fn s<'a>(value: &'a Value, key: &str) -> &'a str {
    value.get(key).and_then(Value::as_str).unwrap_or_default()
}

impl Inbox {
    pub fn snapshot(&self) -> Value {
        json!({ "files": self.files, "receiving": self.receiving })
    }

    fn publish(&self) -> Output {
        Output::Event("inbox", self.snapshot())
    }

    fn open(&mut self, out: &mut Vec<Output>) {
        if let (true, Some(endpoint)) = (self.connected, self.wanted.clone()) {
            self.run += 1;
            self.open = Some(self.run);
            out.push(Output::Host("tools.openTaildropInbox", json!({ "endpoint": endpoint, "run": self.run })));
        }
    }

    /// Closes the open subscription and empties the lists.
    fn close(&mut self, out: &mut Vec<Output>) {
        if self.open.take().is_some() {
            out.push(Output::Host("tools.closeTaildropInbox", json!({})));
        }
        if !self.files.is_empty() || !self.receiving.is_empty() {
            self.files.clear();
            self.receiving.clear();
            out.push(self.publish());
        }
    }

    /// The UI's inbox commands, handled here; None for any other.
    pub fn command(&mut self, method: &str, args: &Value) -> Option<Result<Vec<Output>, String>> {
        let mut out = vec![];
        match method {
            "tools.subscribeTaildrop" => {
                let Some(endpoint) = args.get("endpoint").and_then(Value::as_str) else {
                    return Some(Err("Missing argument: endpoint".into()));
                };
                if self.wanted.as_deref() != Some(endpoint) || self.open.is_none() {
                    self.close(&mut out);
                    self.wanted = Some(endpoint.to_string());
                    self.open(&mut out);
                }
                out.push(Output::Host("tools.markTaildropRead", json!({ "endpoint": endpoint })));
            }
            "tools.unsubscribeTaildrop" => {
                self.wanted = None;
                self.close(&mut out);
            }
            _ => return None,
        }
        Some(Ok(out))
    }

    /// The tool status target moved: every host subscription was closed.
    /// `available` is whether the session's service is reachable at all.
    pub fn target(&mut self, connect: bool, remote: Option<i64>, available: bool) -> Vec<Output> {
        let mut out = vec![];
        // The host closed its subscription with the others.
        self.open = None;
        if self.session != remote || !available {
            self.wanted = None;
        }
        self.session = remote;
        self.connected = connect;
        self.close(&mut out);
        self.open(&mut out);
        out
    }

    /// A forwarded inbox event: `update` (`files`, `receiving`), or `error` /
    /// `failed` (the subscription ended: forget the endpoint, report it).
    pub fn event(&mut self, event: &Value) -> Vec<Output> {
        if event["run"].as_u64().is_none() || event["run"].as_u64() != self.open {
            return vec![];
        }
        let mut out = vec![];
        match s(event, "event") {
            "update" => {
                self.files = event["value"]["files"].as_array().cloned().unwrap_or_default();
                self.receiving = event["value"]["receiving"].as_array().cloned().unwrap_or_default();
                out.push(self.publish());
            }
            "error" | "failed" => {
                self.open = None;
                self.wanted = None;
                self.files.clear();
                self.receiving.clear();
                out.push(self.publish());
                if let Some(message) = event["value"].as_str().filter(|m| !m.is_empty()) {
                    out.push(Output::Error(message.to_string()));
                }
            }
            _ => {}
        }
        out
    }

    /// Checks an inbox file command: the file must still be in the inbox.
    pub fn prepare(&self, method: &str, args: &Value) -> Result<(), String> {
        if matches!(method, "tools.deleteTaildropFile" | "tools.saveTaildropFile") {
            let name = args.get("name").and_then(Value::as_str).ok_or("Missing argument: name")?;
            if !self.files.iter().any(|f| s(f, "name") == name) {
                return Err("File no longer exists".into());
            }
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn hosts(out: &[Output]) -> Vec<(&'static str, Value)> {
        out.iter().filter_map(|o| if let Output::Host(m, a) = o { Some((*m, a.clone())) } else { None }).collect()
    }

    fn update(run: u64) -> Value {
        json!({ "kind": "taildrop", "event": "update", "run": run,
            "value": { "files": [{ "name": "a.txt", "size": 3, "senderName": "laptop" }], "receiving": [] } })
    }

    #[test]
    fn subscribing_waits_for_the_target_and_marks_read() {
        let mut inbox = Inbox::default();
        let out = inbox.command("tools.subscribeTaildrop", &json!({ "endpoint": "ts" })).unwrap().unwrap();
        assert_eq!(hosts(&out), vec![("tools.markTaildropRead", json!({ "endpoint": "ts" }))]);
        let out = inbox.target(true, None, true);
        assert_eq!(hosts(&out), vec![("tools.openTaildropInbox", json!({ "endpoint": "ts", "run": 1 }))]);
        assert!(inbox.command("tools.other", &json!({})).is_none());
    }

    #[test]
    fn updates_of_the_current_run_fill_the_lists() {
        let mut inbox = Inbox::default();
        inbox.target(true, None, true);
        inbox.command("tools.subscribeTaildrop", &json!({ "endpoint": "ts" }));
        assert!(inbox.event(&update(9)).is_empty(), "stale run");
        assert_eq!(inbox.event(&update(1)).len(), 1);
        assert!(inbox.prepare("tools.saveTaildropFile", &json!({ "name": "a.txt" })).is_ok());
        assert_eq!(inbox.prepare("tools.deleteTaildropFile", &json!({ "name": "b" })).unwrap_err(), "File no longer exists");
        let out = inbox.command("tools.unsubscribeTaildrop", &json!({})).unwrap().unwrap();
        assert_eq!(hosts(&out)[0].0, "tools.closeTaildropInbox");
        assert_eq!(inbox.snapshot()["files"], json!([]));
        assert!(inbox.event(&update(1)).is_empty(), "closed");
    }

    #[test]
    fn background_keeps_the_endpoint_and_session_changes_forget_it() {
        let mut inbox = Inbox::default();
        inbox.target(true, None, true);
        inbox.command("tools.subscribeTaildrop", &json!({ "endpoint": "ts" }));
        assert!(hosts(&inbox.target(false, None, true)).is_empty(), "background: nothing open to close host-side");
        assert_eq!(hosts(&inbox.target(true, None, true))[0].0, "tools.openTaildropInbox");
        inbox.target(false, Some(4), false);
        assert!(hosts(&inbox.target(true, Some(4), true)).is_empty(), "another session forgot the endpoint");
    }

    #[test]
    fn errors_forget_the_endpoint_and_report() {
        let mut inbox = Inbox::default();
        inbox.target(true, None, true);
        inbox.command("tools.subscribeTaildrop", &json!({ "endpoint": "ts" }));
        let out = inbox.event(&json!({ "kind": "taildrop", "event": "error", "run": 1, "value": "gone" }));
        assert!(out.contains(&Output::Error("gone".into())));
        assert!(hosts(&inbox.target(true, None, true)).is_empty());
    }
}
