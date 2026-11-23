//! The logs page's records: a bounded ring of the core's log lines (libbox
//! `CommandClientHandler.WriteLogs`/`ClearLogs`), ANSI colour codes removed,
//! each with a monotonic id and the time it arrived.
//!
//! The platform forwards what the core sent since its last batch:
//! `{"reset": bool, "defaultLogLevel": int|null, "entries": [{"level": int,
//! "message": str}]}`. `reset` means the core cleared its logs (or replays
//! them on a new subscription) before `entries`; a missing
//! `defaultLogLevel` keeps the level already known. Filtering and pause
//! snapshots belong to the page.

use std::collections::VecDeque;

use serde_json::{json, Value};

/// Records kept, oldest dropped first.
pub const LIMIT: usize = 3000;

/// libbox log level priority → the label the page shows and filters on.
pub fn level_label(priority: i64) -> &'static str {
    match priority {
        0 => "Panic",
        1 => "Fatal",
        2 => "Error",
        3 => "Warn",
        4 => "Info",
        5 => "Debug",
        6 => "Trace",
        _ => "Default",
    }
}

/// Removes SGR sequences (`ESC [ digits/; m`), as the core colours its lines.
pub fn strip_ansi(message: &str) -> String {
    if !message.contains('\u{1b}') {
        return message.to_string();
    }
    let bytes = message.as_bytes();
    let mut out = String::with_capacity(message.len());
    let mut start = 0;
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == 0x1b && bytes.get(i + 1) == Some(&b'[') {
            let mut j = i + 2;
            while j < bytes.len() && (bytes[j].is_ascii_digit() || bytes[j] == b';') {
                j += 1;
            }
            if bytes.get(j) == Some(&b'm') {
                out.push_str(&message[start..i]);
                i = j + 1;
                start = i;
                continue;
            }
        }
        i += 1;
    }
    out.push_str(&message[start..]);
    out
}

#[derive(Debug)]
pub struct Logs {
    limit: usize,
    records: VecDeque<Value>,
    next_id: i64,
    default_level: Option<String>,
}

impl Default for Logs {
    fn default() -> Self {
        Self::with_limit(LIMIT)
    }
}

impl Logs {
    pub fn with_limit(limit: usize) -> Self {
        Self { limit: limit.max(1), records: VecDeque::new(), next_id: 0, default_level: None }
    }

    /// Ids keep counting across a clear, so a page's pause snapshot never
    /// mistakes new lines for old ones.
    pub fn clear(&mut self) {
        self.records.clear();
    }

    pub fn append(&mut self, level: i64, message: &str, now_ms: i64) {
        if self.records.len() == self.limit {
            self.records.pop_front();
        }
        self.next_id += 1;
        self.records.push_back(json!({
            "id": self.next_id,
            "level": level_label(level),
            "message": strip_ansi(message),
            "receivedAt": now_ms,
        }));
    }

    pub fn apply_batch(&mut self, batch: &Value, now_ms: i64) {
        if batch.get("reset").and_then(Value::as_bool) == Some(true) {
            self.clear();
        }
        if let Some(level) = batch.get("defaultLogLevel") {
            self.default_level = level.as_i64().map(|l| level_label(l).to_string());
        }
        let entries = batch.get("entries").and_then(Value::as_array).map(Vec::as_slice).unwrap_or(&[]);
        // Only the newest `limit` lines of an oversized batch can survive.
        for entry in &entries[entries.len().saturating_sub(self.limit)..] {
            let level = entry.get("level").and_then(Value::as_i64).unwrap_or(-1);
            self.append(level, entry.get("message").and_then(Value::as_str).unwrap_or(""), now_ms);
        }
    }

    /// `{"id", "level", "message", "receivedAt"}`, oldest first.
    pub fn rows(&self) -> Vec<Value> {
        self.records.iter().cloned().collect()
    }

    pub fn default_level(&self) -> Option<&str> {
        self.default_level.as_deref()
    }

    pub fn len(&self) -> usize {
        self.records.len()
    }

    pub fn is_empty(&self) -> bool {
        self.records.is_empty()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn messages(logs: &Logs) -> Vec<String> {
        logs.rows().iter().map(|r| r["message"].as_str().unwrap().to_string()).collect()
    }

    #[test]
    fn keeps_trace_and_debug_and_strips_ansi() {
        let mut logs = Logs::default();
        logs.apply_batch(
            &json!({"entries": [{"level": 5, "message": "\u{1b}[32mdebug\u{1b}[0m"}, {"level": 6, "message": "trace"}]}),
            123,
        );
        assert_eq!(messages(&logs), ["debug", "trace"]);
        let rows = logs.rows();
        assert_eq!(rows[0]["level"], "Debug");
        assert_eq!(rows[1]["level"], "Trace");
        assert!(rows.iter().all(|r| r["receivedAt"] == 123));
    }

    #[test]
    fn strip_ansi_leaves_other_escapes() {
        assert_eq!(strip_ansi("a\u{1b}[1;31mb\u{1b}[0mc"), "abc");
        assert_eq!(strip_ansi("\u{1b}[2Jx"), "\u{1b}[2Jx");
        assert_eq!(strip_ansi("plain é"), "plain é");
        assert_eq!(strip_ansi("tail\u{1b}["), "tail\u{1b}[");
    }

    #[test]
    fn oversized_batches_stay_bounded_and_ids_survive_clear() {
        let mut logs = Logs::with_limit(3);
        logs.apply_batch(&json!({"entries": [{"level": 4, "message": "old"}]}), 0);
        let batch: Vec<Value> = (1..=10).map(|i| json!({"level": 4, "message": i.to_string()})).collect();
        logs.apply_batch(&json!({"entries": batch}), 0);
        assert_eq!(messages(&logs), ["8", "9", "10"]);
        let last = logs.rows().last().unwrap()["id"].as_i64().unwrap();
        logs.apply_batch(&json!({"reset": true}), 0);
        assert!(logs.is_empty());
        logs.apply_batch(&json!({"entries": [{"level": 2, "message": "new"}]}), 0);
        assert!(logs.rows()[0]["id"].as_i64().unwrap() > last);
    }

    #[test]
    fn reset_applies_before_the_batch_entries() {
        let mut logs = Logs::default();
        logs.apply_batch(&json!({"entries": [{"level": 4, "message": "a"}]}), 0);
        logs.apply_batch(&json!({"reset": true, "entries": [{"level": 4, "message": "b"}]}), 0);
        assert_eq!(messages(&logs), ["b"]);
    }

    #[test]
    fn default_level_is_kept_until_sent() {
        let mut logs = Logs::default();
        assert_eq!(logs.default_level(), None);
        logs.apply_batch(&json!({"defaultLogLevel": 4}), 0);
        assert_eq!(logs.default_level(), Some("Info"));
        logs.apply_batch(&json!({"entries": []}), 0);
        assert_eq!(logs.default_level(), Some("Info"));
        logs.apply_batch(&json!({"defaultLogLevel": 99}), 0);
        assert_eq!(logs.default_level(), Some("Default"));
        logs.apply_batch(&json!({"defaultLogLevel": null}), 0);
        assert_eq!(logs.default_level(), None);
    }
}
