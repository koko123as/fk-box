//! The tool status lists (phase 4e2): Tailscale, OpenConnect and OpenVPN
//! endpoints and USBIP servers, as the deleted Kotlin
//! `TailscaleStatusViewModel`, `OpenConnectStatusViewModel`,
//! `OpenVPNStatusViewModel` and `USBIPStatusViewModel` kept them.
//!
//! The host opens the libbox status subscriptions while the plan's
//! `toolStatus` target connects (sessions.rs) and forwards each update,
//! converted to plain values, as a "toolStatus" event (`kind`, `event`,
//! `value`). This keeps the lists, makes the UI's "tools" snapshot of them,
//! and checks a command against them before it reaches the host
//! ([`ToolStatus::prepare`]): the peer, exit node or server it names must
//! still exist, and the host gets the peer's details instead of looking
//! them up itself.

use serde_json::{json, Map, Value};

use crate::runtime::Output;
use crate::taildrop::Inbox;

pub const KINDS: [&str; 4] = ["tailscale", "openconnect", "openvpn", "usbip"];

#[derive(Debug, Default)]
pub struct ToolStatus {
    lists: Map<String, Value>,
    /// The Taildrop inbox (taildrop.rs), opened and closed with the others.
    pub inbox: Inbox,
}

fn s<'a>(value: &'a Value, key: &str) -> &'a str {
    value.get(key).and_then(Value::as_str).unwrap_or_default()
}

fn arg(args: &Value, key: &str) -> Result<String, String> {
    args.get(key).and_then(Value::as_str).map(str::to_string).ok_or_else(|| format!("Missing argument: {key}"))
}

/// The short name of a peer: its MagicDNS name's first label, else its host name.
pub fn peer_display_name(peer: &Value) -> String {
    let dns = s(peer, "dnsName");
    let first = dns.split('.').next().unwrap_or_default();
    if first.is_empty() { s(peer, "hostName").to_string() } else { first.to_string() }
}

impl ToolStatus {
    fn list(&self, kind: &str) -> &[Value] {
        self.lists.get(kind).and_then(Value::as_array).map(Vec::as_slice).unwrap_or_default()
    }

    /// The UI's "tools" value: each kind's list.
    pub fn snapshot(&self) -> Value {
        let mut out = Map::new();
        for kind in KINDS {
            out.insert(kind.to_string(), Value::Array(self.list(kind).to_vec()));
        }
        Value::Object(out)
    }

    fn publish(&self) -> Output {
        Output::Event("tools", self.snapshot())
    }

    /// One forwarded event: `update` (the kind's whole list), `error` (the
    /// subscription ended with a message: empty and report it), `failed`
    /// (it could not open: empty quietly) or `reset` (every subscription
    /// closed as the tool status `target` moved: kind "*"). Kind "taildrop"
    /// is the inbox's. `available`: whether the session's service is up.
    pub fn event(&mut self, event: &Value, available: bool) -> Vec<Output> {
        let kind = s(event, "kind");
        if kind == "taildrop" {
            return self.inbox.event(event);
        }
        match s(event, "event") {
            "reset" => {
                let target = &event["target"];
                let mut out = self.inbox.target(
                    target["connect"].as_bool().unwrap_or(false),
                    target["remoteServerId"].as_i64(),
                    available,
                );
                if !self.lists.values().all(|l| l.as_array().is_none_or(Vec::is_empty)) {
                    self.lists.clear();
                    out.push(self.publish());
                }
                out
            }
            _ if !KINDS.contains(&kind) => vec![],
            "update" => {
                let list = event["value"].as_array().cloned().unwrap_or_default();
                self.lists.insert(kind.to_string(), Value::Array(list));
                vec![self.publish()]
            }
            "error" => {
                self.lists.remove(kind);
                let mut out = vec![self.publish()];
                if let Some(message) = event["value"].as_str().filter(|m| !m.is_empty()) {
                    out.push(Output::Error(message.to_string()));
                }
                out
            }
            "failed" => {
                self.lists.remove(kind);
                vec![self.publish()]
            }
            _ => vec![],
        }
    }

    fn endpoint(&self, kind: &str, tag: &str) -> Option<&Value> {
        self.list(kind).iter().find(|e| s(e, "endpointTag") == tag)
    }

    /// The peer `id` of a Tailscale endpoint: its own device or one in a user group.
    pub fn peer(&self, endpoint: &str, id: &str) -> Option<&Value> {
        let endpoint = self.endpoint("tailscale", endpoint)?;
        if endpoint["selfPeer"].is_object() && s(&endpoint["selfPeer"], "id") == id {
            return Some(&endpoint["selfPeer"]);
        }
        endpoint["userGroups"].as_array()?.iter().find_map(|group| {
            group["peers"].as_array()?.iter().find(|peer| s(peer, "id") == id)
        })
    }

    fn peer_for(&self, args: &Value) -> Result<&Value, String> {
        self.peer(&arg(args, "endpoint")?, &arg(args, "peer")?).ok_or_else(|| "Peer no longer exists".to_string())
    }

    /// Checks a host command that names a peer, exit node or server, and
    /// adds what the host needs of it (`peerInfo`). Other commands pass
    /// through unchanged.
    pub fn prepare(&self, method: &str, mut args: Value) -> Result<Value, String> {
        self.inbox.prepare(method, &args)?;
        let peer_info = |peer: &Value| {
            json!({
                "stableID": s(peer, "stableID"),
                "hostName": s(peer, "hostName"),
                "displayName": peer_display_name(peer),
                "address": peer["tailscaleIPs"].as_array().and_then(|a| a.first()).cloned(),
                "sshHostKeys": peer["sshHostKeys"].clone(),
            })
        };
        match method {
            "tools.setTailscaleExitNode" => {
                let endpoint =
                    self.endpoint("tailscale", &arg(&args, "endpoint")?).ok_or("Endpoint no longer exists")?;
                let stable = arg(&args, "peer")?;
                let available = endpoint["userGroups"].as_array().into_iter().flatten().any(|group| {
                    group["peers"].as_array().into_iter().flatten().any(|p| {
                        s(p, "stableID") == stable && p["exitNodeOption"].as_bool().unwrap_or(false)
                    })
                });
                if !stable.is_empty() && !available {
                    return Err("Not an available exit node".into());
                }
            }
            "ssh.options" | "ssh.start" => {
                let peer = self.peer_for(&args)?;
                if peer["sshHostKeys"].as_array().is_none_or(Vec::is_empty) {
                    return Err("Peer has no authenticated SSH host keys".into());
                }
                if method == "ssh.start" && peer["tailscaleIPs"].as_array().is_none_or(Vec::is_empty) {
                    return Err("Peer has no address".into());
                }
                args["peerInfo"] = peer_info(peer);
            }
            "tools.sendShare" => {
                let peer = self.peer_for(&args)?;
                if !(peer["canReceiveFiles"].as_bool().unwrap_or(false) && peer["online"].as_bool().unwrap_or(false)) {
                    return Err("Peer is not available for file transfer".into());
                }
                args["peerInfo"] = peer_info(peer);
            }
            "tools.sendTaildropFile" => {
                let peer = self.peer_for(&args)?;
                if !peer["canReceiveFiles"].as_bool().unwrap_or(false) {
                    return Err("This peer cannot receive files".into());
                }
                args["peerInfo"] = peer_info(peer);
            }
            "tools.attachUsb" => {
                let server = arg(&args, "server")?;
                if !self.list("usbip").iter().any(|s_| s(s_, "serverTag") == server) {
                    return Err("USBIP server no longer exists".into());
                }
            }
            _ => {}
        }
        Ok(args)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn peer(id: &str, stable: &str) -> Value {
        json!({ "id": id, "stableID": stable, "hostName": "host-".to_string() + id, "dnsName": format!("{id}.tail.ts.net."),
            "tailscaleIPs": ["100.64.0.9"], "sshHostKeys": ["ssh-ed25519 AAA"], "online": true,
            "exitNodeOption": true, "canReceiveFiles": true })
    }

    fn with_tailscale() -> ToolStatus {
        let mut t = ToolStatus::default();
        let endpoint = json!({ "endpointTag": "ts", "selfPeer": peer("me", "s0"),
            "userGroups": [{ "id": 1, "peers": [peer("laptop", "s1"),
                { "id": "phone", "stableID": "s2", "hostName": "phone", "dnsName": "", "tailscaleIPs": [], "sshHostKeys": [],
                  "online": false, "exitNodeOption": false, "canReceiveFiles": true }] }] });
        t.event(&json!({ "kind": "tailscale", "event": "update", "value": [endpoint] }), true);
        t
    }

    #[test]
    fn updates_errors_and_reset_shape_the_snapshot() {
        let mut t = with_tailscale();
        assert_eq!(t.snapshot()["tailscale"].as_array().unwrap().len(), 1);
        assert_eq!(t.snapshot()["usbip"], json!([]));
        let out = t.event(&json!({ "kind": "tailscale", "event": "error", "value": "gone" }), true);
        assert!(out.contains(&Output::Error("gone".into())));
        assert_eq!(t.snapshot()["tailscale"], json!([]));
        t.event(&json!({ "kind": "usbip", "event": "update", "value": [{ "serverTag": "u" }] }), true);
        assert_eq!(t.event(&json!({ "kind": "*", "event": "reset" }), true).len(), 1);
        assert!(t.event(&json!({ "kind": "*", "event": "reset" }), true).is_empty(), "nothing to clear");
        assert!(t.event(&json!({ "kind": "bogus", "event": "update", "value": [] }), true).is_empty());
    }

    #[test]
    fn peers_are_found_and_named() {
        let t = with_tailscale();
        assert_eq!(s(t.peer("ts", "me").unwrap(), "stableID"), "s0");
        assert_eq!(peer_display_name(t.peer("ts", "laptop").unwrap()), "laptop");
        assert_eq!(peer_display_name(t.peer("ts", "phone").unwrap()), "phone");
        assert!(t.peer("ts", "nope").is_none() && t.peer("other", "me").is_none());
    }

    #[test]
    fn exit_nodes_must_be_offered() {
        let t = with_tailscale();
        assert!(t.prepare("tools.setTailscaleExitNode", json!({ "endpoint": "ts", "peer": "s1" })).is_ok());
        assert!(t.prepare("tools.setTailscaleExitNode", json!({ "endpoint": "ts", "peer": "" })).is_ok());
        assert_eq!(t.prepare("tools.setTailscaleExitNode", json!({ "endpoint": "ts", "peer": "s2" })).unwrap_err(), "Not an available exit node");
        assert_eq!(t.prepare("tools.setTailscaleExitNode", json!({ "endpoint": "x", "peer": "" })).unwrap_err(), "Endpoint no longer exists");
    }

    #[test]
    fn ssh_and_taildrop_get_peer_details() {
        let t = with_tailscale();
        let args = t.prepare("ssh.start", json!({ "endpoint": "ts", "peer": "laptop", "username": "root" })).unwrap();
        assert_eq!(args["peerInfo"]["address"], "100.64.0.9");
        assert_eq!(args["peerInfo"]["hostName"], "host-laptop");
        assert_eq!(args["username"], "root");
        assert!(t.prepare("ssh.options", json!({ "endpoint": "ts", "peer": "phone" })).unwrap_err().contains("SSH host keys"));
        assert_eq!(t.prepare("ssh.start", json!({ "endpoint": "ts", "peer": "gone" })).unwrap_err(), "Peer no longer exists");
        assert_eq!(t.prepare("tools.sendShare", json!({ "endpoint": "ts", "peer": "phone" })).unwrap_err(), "Peer is not available for file transfer");
        let args = t.prepare("tools.sendTaildropFile", json!({ "endpoint": "ts", "peer": "phone" })).unwrap();
        assert_eq!(args["peerInfo"]["stableID"], "s2");
        assert_eq!(t.prepare("tools.attachUsb", json!({ "server": "u", "device": "1-1" })).unwrap_err(), "USBIP server no longer exists");
        assert_eq!(t.prepare("other", json!({ "a": 1 })).unwrap(), json!({ "a": 1 }));
    }
}
