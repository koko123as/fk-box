//! The connections page's state: an id → row map updated from the core's
//! NEW/UPDATE/CLOSED deltas (libbox `Connections.ApplyEvents`, sing-box
//! experimental/libbox/command_types.go), the rows the page lists and the
//! counts the dashboard shows.
//!
//! The platform forwards the core's event batches unchanged in meaning
//! (`{"reset": bool, "events": [{"type", "id", "connection"?, "uplinkDelta",
//! "downlinkDelta", "closedAt"}]}`); a NEW event, and a CLOSED one for a
//! connection the batch carries again, carry the whole row. Rows keep the
//! order they were first seen in.

use serde_json::{Map, Value};

/// libbox ConnectionEventNew/Update/Closed (iota).
pub const EVENT_NEW: i64 = 0;
pub const EVENT_UPDATE: i64 = 1;
pub const EVENT_CLOSED: i64 = 2;
/// libbox closedConnectionMaxAge: closed rows are kept this long.
pub const CLOSED_MAX_AGE_MS: i64 = 5 * 60 * 1000;

fn long(value: Option<&Value>) -> i64 {
    match value {
        Some(Value::Number(n)) => n.as_i64().or_else(|| n.as_f64().map(|f| f as i64)).unwrap_or(0),
        _ => 0,
    }
}

fn text<'a>(row: &'a Value, key: &str) -> &'a str {
    row.get(key).and_then(Value::as_str).unwrap_or("")
}

fn closed_at(row: &Value) -> Option<i64> {
    row.get("closedAt").and_then(Value::as_i64).filter(|at| *at > 0)
}

/// The rows of one session (a local service or a remote control server).
#[derive(Debug, Default)]
pub struct Connections {
    rows: Map<String, Value>,
}

impl Connections {
    pub fn clear(&mut self) {
        self.rows.clear();
    }

    /// Applies one batch in order, then drops rows closed more than
    /// [`CLOSED_MAX_AGE_MS`] before `now_ms`.
    pub fn apply_batch(&mut self, batch: &Value, now_ms: i64) {
        if batch.get("reset").and_then(Value::as_bool) == Some(true) {
            self.rows.clear();
        }
        for event in batch.get("events").and_then(Value::as_array).into_iter().flatten() {
            let id = text(event, "id");
            let connection = event.get("connection").filter(|c| c.is_object());
            self.apply(
                long(event.get("type")),
                id,
                connection,
                long(event.get("uplinkDelta")),
                long(event.get("downlinkDelta")),
                long(event.get("closedAt")),
            );
        }
        self.evict_closed(now_ms);
    }

    /// One event; false when it names a connection the store does not have
    /// (and carries none) or is of an unknown type.
    pub fn apply(&mut self, kind: i64, id: &str, connection: Option<&Value>, uplink: i64, downlink: i64, closed_at: i64) -> bool {
        match kind {
            EVENT_NEW => {
                let Some(connection) = connection else { return false };
                self.rows.insert(id.to_string(), connection.clone());
            }
            EVENT_UPDATE => {
                let Some(Value::Object(row)) = self.rows.get_mut(id) else { return false };
                let upload_total = long(row.get("uploadTotal")).saturating_add(uplink);
                let download_total = long(row.get("downloadTotal")).saturating_add(downlink);
                row.insert("upload".into(), uplink.into());
                row.insert("download".into(), downlink.into());
                row.insert("uploadTotal".into(), upload_total.into());
                row.insert("downloadTotal".into(), download_total.into());
            }
            EVENT_CLOSED => {
                let mut row = match connection.or_else(|| self.rows.get(id)) {
                    Some(Value::Object(row)) => row.clone(),
                    _ => return false,
                };
                row.insert("closedAt".into(), if closed_at > 0 { closed_at.into() } else { Value::Null });
                row.insert("upload".into(), 0.into());
                row.insert("download".into(), 0.into());
                self.rows.insert(id.to_string(), Value::Object(row));
            }
            _ => return false,
        }
        true
    }

    pub fn evict_closed(&mut self, now_ms: i64) {
        self.rows.retain(|_, row| closed_at(row).is_none_or(|at| now_ms - at <= CLOSED_MAX_AGE_MS));
    }

    /// The rows the page lists: every connection but those of a DNS outbound.
    pub fn rows(&self) -> Vec<Value> {
        self.rows.values().filter(|row| text(row, "outboundType") != "dns").cloned().collect()
    }

    /// The DNS lookups among the connections: rows of a DNS outbound,
    /// sniffed as DNS, or to port 53. Queries a core answers inside its own
    /// resolver (sing-box hijack-dns, mihomo dns-hijack) never become
    /// connections, so they are not counted here.
    pub fn dns_count(&self) -> usize {
        self.rows
            .values()
            .filter(|row| {
                text(row, "outboundType") == "dns"
                    || text(row, "protocol").eq_ignore_ascii_case("dns")
                    || text(row, "destination").ends_with(":53")
            })
            .count()
    }

    /// The dashboard's counts: live and recently closed listed rows, and DNS
    /// lookups (`dns_queries` when the core counts them itself, as a local
    /// service does; a remote session only has its connection list).
    pub fn stats(&self, dns_queries: Option<i64>) -> Value {
        let (mut active, mut recent) = (0, 0);
        for row in self.rows.values().filter(|row| text(row, "outboundType") != "dns") {
            if closed_at(row).is_none() {
                active += 1;
            } else {
                recent += 1;
            }
        }
        let dns = dns_queries.unwrap_or(self.dns_count() as i64);
        serde_json::json!({ "active": active, "recent": recent, "dns": dns })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn connection(id: &str, outbound_type: &str) -> Value {
        json!({
            "id": id, "network": "tcp", "destination": "1.1.1.1:443", "protocol": "tls",
            "closedAt": null, "upload": 5, "download": 7, "uploadTotal": 100, "downloadTotal": 200,
            "outbound": "proxy", "outboundType": outbound_type,
        })
    }

    #[test]
    fn updates_apply_deltas_and_keep_order() {
        let mut store = Connections::default();
        store.apply(EVENT_NEW, "a", Some(&connection("a", "direct")), 0, 0, 0);
        store.apply(EVENT_NEW, "b", Some(&connection("b", "direct")), 0, 0, 0);
        assert!(store.apply(EVENT_UPDATE, "a", None, 10, 20, 0));
        let rows = store.rows();
        assert_eq!(rows.iter().map(|r| r["id"].as_str().unwrap()).collect::<Vec<_>>(), ["a", "b"]);
        assert_eq!((rows[0]["upload"].as_i64(), rows[0]["download"].as_i64()), (Some(10), Some(20)));
        assert_eq!((rows[0]["uploadTotal"].as_i64(), rows[0]["downloadTotal"].as_i64()), (Some(110), Some(220)));
        assert_eq!(rows[1], connection("b", "direct"));
    }

    #[test]
    fn closed_marks_the_row_and_zeroes_rates() {
        let mut store = Connections::default();
        store.apply(EVENT_NEW, "a", Some(&connection("a", "direct")), 0, 0, 0);
        store.apply(EVENT_CLOSED, "a", None, 0, 0, 1_000);
        let a = &store.rows()[0];
        assert_eq!(a["closedAt"], 1_000);
        assert_eq!((a["upload"].as_i64(), a["download"].as_i64(), a["uploadTotal"].as_i64()), (Some(0), Some(0), Some(100)));
        // A closed snapshot for an unknown id is stored as is.
        assert!(store.apply(EVENT_CLOSED, "c", Some(&connection("c", "direct")), 0, 0, 2_000));
        assert_eq!(store.rows()[1]["closedAt"], 2_000);
        assert_eq!(store.stats(None), json!({ "active": 0, "recent": 2, "dns": 0 }));
    }

    #[test]
    fn dns_lookups_are_counted_not_listed() {
        let mut store = Connections::default();
        store.apply(EVENT_NEW, "a", Some(&connection("a", "direct")), 0, 0, 0);
        store.apply(EVENT_NEW, "d", Some(&connection("d", "dns")), 0, 0, 0);
        let mut p = connection("p", "direct");
        p["destination"] = "8.8.8.8:53".into();
        p["protocol"] = "DNS".into();
        store.apply(EVENT_NEW, "p", Some(&p), 0, 0, 0);
        assert_eq!(store.dns_count(), 2);
        assert_eq!(store.rows().iter().map(|r| r["id"].as_str().unwrap()).collect::<Vec<_>>(), ["a", "p"]);
        assert_eq!(store.stats(None), json!({ "active": 2, "recent": 0, "dns": 2 }));
        assert_eq!(store.stats(Some(9))["dns"], 9);
    }

    #[test]
    fn events_for_unknown_ids_are_ignored() {
        let mut store = Connections::default();
        assert!(!store.apply(EVENT_UPDATE, "x", None, 1, 1, 0));
        assert!(!store.apply(EVENT_CLOSED, "x", None, 0, 0, 1));
        assert!(!store.apply(EVENT_NEW, "x", None, 0, 0, 0));
        assert!(!store.apply(7, "x", Some(&connection("x", "direct")), 0, 0, 0));
        assert!(store.rows().is_empty());
    }

    #[test]
    fn batches_reset_and_evict_old_closed_rows() {
        let mut store = Connections::default();
        store.apply_batch(
            &json!({ "reset": true, "events": [
                { "type": EVENT_NEW, "id": "a", "connection": connection("a", "direct") },
                { "type": EVENT_NEW, "id": "b", "connection": connection("b", "direct") },
                { "type": EVENT_CLOSED, "id": "a", "closedAt": 1_000 },
            ] }),
            1_000,
        );
        assert_eq!(store.rows().len(), 2);
        store.apply_batch(&json!({ "reset": false, "events": [] }), 1_000 + CLOSED_MAX_AGE_MS);
        assert_eq!(store.rows().len(), 2);
        store.apply_batch(&json!({ "events": [] }), 1_001 + CLOSED_MAX_AGE_MS);
        assert_eq!(store.rows().iter().map(|r| r["id"].as_str().unwrap()).collect::<Vec<_>>(), ["b"]);
        store.apply_batch(&json!({ "reset": true, "events": [{ "type": EVENT_UPDATE, "id": "b", "uplinkDelta": 1 }] }), 0);
        assert!(store.rows().is_empty());
    }
}
