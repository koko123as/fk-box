//! Reading proxy maps the way mihomo's decoder reads them (weakly typed:
//! numbers as strings and back, `_` in a key read as `-`), while recording
//! which keys a mapping consumed, so a key left over is never dropped
//! silently.

use std::collections::BTreeSet;

use serde_json::{Map, Value};

/// True for a value mihomo's decoder leaves at its zero value (absent,
/// `false`, `0`, `""`, empty list, or a mapping of such values).
pub fn is_zero(v: &Value) -> bool {
    match v {
        Value::Null => true,
        Value::Bool(b) => !*b,
        Value::Number(n) => n.as_f64() == Some(0.0),
        Value::String(s) => s.is_empty(),
        Value::Array(a) => a.is_empty(),
        Value::Object(o) => o.values().all(is_zero),
    }
}

/// The mapping inside a `json!` object literal.
pub fn into_map(v: Value) -> Map<String, Value> {
    match v {
        Value::Object(m) => m,
        _ => Map::new(),
    }
}

/// A scalar as text (strings as they are, numbers and booleans printed).
pub fn scalar_text(v: &Value) -> Option<String> {
    match v {
        Value::String(s) => Some(s.clone()),
        Value::Number(n) => Some(n.to_string()),
        Value::Bool(b) => Some(b.to_string()),
        _ => None,
    }
}

/// A port: a number or a numeric string, 1–65535.
pub fn port(v: &Value) -> Result<u16, String> {
    let text = scalar_text(v).ok_or_else(|| format!("invalid port {v}"))?;
    match text.trim().parse::<u16>() {
        Ok(p) if p > 0 => Ok(p),
        _ => Err(format!("invalid port {text:?}")),
    }
}

/// A Go duration (`300ms`, `1m30s`, `1.5h`) in milliseconds; `0` alone is 0.
pub fn go_duration_ms(text: &str) -> Result<u64, String> {
    let s = text.trim();
    if s == "0" {
        return Ok(0);
    }
    if s.is_empty() {
        return Err("empty duration".into());
    }
    let bytes = s.as_bytes();
    let mut i = 0;
    let mut total = 0f64;
    while i < bytes.len() {
        let start = i;
        while i < bytes.len() && (bytes[i].is_ascii_digit() || bytes[i] == b'.') {
            i += 1;
        }
        if start == i {
            return Err(format!("invalid duration {s:?}"));
        }
        let number: f64 = s[start..i].parse().map_err(|_| format!("invalid duration {s:?}"))?;
        let unit_start = i;
        while i < bytes.len() && !(bytes[i].is_ascii_digit() || bytes[i] == b'.') {
            i += 1;
        }
        let factor = match &s[unit_start..i] {
            "ns" => 0.000_001,
            "us" | "µs" => 0.001,
            "ms" => 1.0,
            "s" => 1000.0,
            "m" => 60_000.0,
            "h" => 3_600_000.0,
            _ => return Err(format!("invalid duration {s:?}")),
        };
        total += number * factor;
    }
    Ok(total.round() as u64)
}

/// A mapping being read: every key looked at is recorded, whatever its value.
pub struct Reader<'a> {
    map: &'a Map<String, Value>,
    prefix: String,
    used: BTreeSet<String>,
}

impl<'a> Reader<'a> {
    /// `prefix` names the mapping in leftover keys (`""` for the top level,
    /// `"ws-opts."` for a nested one).
    pub fn new(map: &'a Map<String, Value>, prefix: &str) -> Reader<'a> {
        Reader { map, prefix: prefix.to_string(), used: BTreeSet::new() }
    }

    /// The key as written in the mapping for `name` (exact, or with `_` for `-`).
    fn find(&self, name: &str) -> Option<&'a String> {
        let map: &'a Map<String, Value> = self.map;
        if let Some(k) = map.keys().find(|k| k.as_str() == name) {
            return Some(k);
        }
        map.keys().find(|k| k.replace('_', "-") == name)
    }

    /// Marks `name` read and returns its value (`None` when absent or null).
    pub fn raw(&mut self, name: &str) -> Option<&'a Value> {
        let key = self.find(name)?;
        self.used.insert(key.clone());
        let map: &'a Map<String, Value> = self.map;
        match map.get(key.as_str()) {
            Some(Value::Null) | None => None,
            Some(v) => Some(v),
        }
    }

    /// Marks `name` read without looking at it.
    pub fn skip(&mut self, name: &str) {
        let _ = self.raw(name);
    }

    pub fn has(&self, name: &str) -> bool {
        match self.find(name) {
            Some(k) => self.map.get(k.as_str()).map(|v| !is_zero(v)).unwrap_or(false),
            None => false,
        }
    }

    /// A string (numbers and booleans as text); `None` when absent or empty.
    pub fn string(&mut self, name: &str) -> Result<Option<String>, String> {
        match self.raw(name) {
            None => Ok(None),
            Some(v) => match scalar_text(v) {
                Some(s) if s.is_empty() => Ok(None),
                Some(s) => Ok(Some(s)),
                None => Err(format!("{}{name}: not a string", self.prefix)),
            },
        }
    }

    pub fn boolean(&mut self, name: &str) -> Result<bool, String> {
        let prefix = self.prefix.clone();
        match self.raw(name) {
            None => Ok(false),
            Some(Value::Bool(b)) => Ok(*b),
            Some(Value::Number(n)) => match n.as_i64() {
                Some(0) => Ok(false),
                Some(1) => Ok(true),
                _ => Err(format!("{prefix}{name}: not a boolean")),
            },
            Some(Value::String(s)) => match s.trim().to_ascii_lowercase().as_str() {
                "" | "false" | "0" => Ok(false),
                "true" | "1" => Ok(true),
                _ => Err(format!("{prefix}{name}: not a boolean")),
            },
            Some(_) => Err(format!("{prefix}{name}: not a boolean")),
        }
    }

    /// An unsigned integer (number or numeric string); `None` when absent or empty.
    pub fn uint(&mut self, name: &str) -> Result<Option<u64>, String> {
        let prefix = self.prefix.clone();
        match self.raw(name) {
            None => Ok(None),
            Some(Value::Number(n)) => {
                if let Some(u) = n.as_u64() {
                    Ok(Some(u))
                } else {
                    match n.as_f64() {
                        Some(f) if f >= 0.0 && f.fract() == 0.0 => Ok(Some(f as u64)),
                        _ => Err(format!("{prefix}{name}: not an unsigned integer")),
                    }
                }
            }
            Some(Value::String(s)) if s.trim().is_empty() => Ok(None),
            Some(Value::String(s)) => s.trim().parse::<u64>().map(Some).map_err(|_| format!("{prefix}{name}: not an unsigned integer")),
            Some(_) => Err(format!("{prefix}{name}: not an unsigned integer")),
        }
    }

    /// A list of strings; a single string is a one-element list (mihomo's
    /// weak typing). Empty strings are kept out.
    pub fn strings(&mut self, name: &str) -> Result<Vec<String>, String> {
        let prefix = self.prefix.clone();
        match self.raw(name) {
            None => Ok(Vec::new()),
            Some(Value::Array(items)) => {
                let mut out = Vec::new();
                for item in items {
                    match scalar_text(item) {
                        Some(s) if s.is_empty() => {}
                        Some(s) => out.push(s),
                        None => return Err(format!("{prefix}{name}: not a list of strings")),
                    }
                }
                Ok(out)
            }
            Some(v) => match scalar_text(v) {
                Some(s) if s.is_empty() => Ok(Vec::new()),
                Some(s) => Ok(vec![s]),
                None => Err(format!("{prefix}{name}: not a list of strings")),
            },
        }
    }

    /// A nested mapping; `None` when absent or zero.
    pub fn object(&mut self, name: &str) -> Result<Option<&'a Map<String, Value>>, String> {
        let prefix = self.prefix.clone();
        match self.raw(name) {
            None => Ok(None),
            Some(Value::Object(m)) if m.values().all(is_zero) => Ok(None),
            Some(Value::Object(m)) => Ok(Some(m)),
            Some(v) if is_zero(v) => Ok(None),
            Some(_) => Err(format!("{prefix}{name}: not a mapping")),
        }
    }

    /// Keys present with a non-zero value that nothing read, with the prefix.
    pub fn leftovers(&self) -> Vec<String> {
        self.map
            .iter()
            .filter(|(k, v)| !self.used.contains(k.as_str()) && !is_zero(v))
            .map(|(k, _)| format!("{}{k}", self.prefix))
            .collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn reader_reads_weakly_and_tracks_keys() {
        let map = into_map(json!({
            "name": "a", "port": "443", "udp": "true", "alpn": "h2", "auth_str": "x",
            "skip-cert-verify": false, "ws-opts": {"path": ""}, "extra": 5
        }));
        let mut r = Reader::new(&map, "");
        assert_eq!(r.string("name").unwrap().as_deref(), Some("a"));
        assert_eq!(r.uint("port").unwrap(), Some(443));
        assert!(r.boolean("udp").unwrap());
        assert_eq!(r.strings("alpn").unwrap(), vec!["h2".to_string()]);
        assert_eq!(r.string("auth-str").unwrap().as_deref(), Some("x"));
        assert!(r.object("ws-opts").unwrap().is_none());
        assert_eq!(r.leftovers(), vec!["extra".to_string()]);
        assert!(r.boolean("name").is_err());
    }

    #[test]
    fn durations_and_ports() {
        assert_eq!(go_duration_ms("1m30s").unwrap(), 90_000);
        assert_eq!(go_duration_ms("300ms").unwrap(), 300);
        assert_eq!(go_duration_ms("1.5h").unwrap(), 5_400_000);
        assert_eq!(go_duration_ms("0").unwrap(), 0);
        assert!(go_duration_ms("5").is_err());
        assert!(go_duration_ms("").is_err());
        assert_eq!(port(&json!(443)).unwrap(), 443);
        assert_eq!(port(&json!("8443")).unwrap(), 8443);
        assert!(port(&json!(0)).is_err());
        assert!(port(&json!("x")).is_err());
        assert!(is_zero(&json!({"a": false, "b": ""})));
        assert!(!is_zero(&json!({"a": 1})));
    }
}
