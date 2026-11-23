//! sing-box JSON: `outbounds` (protocol outbounds kept as written,
//! `selector`/`urltest` into groups), `endpoints` (WireGuard, MASQUE kept),
//! `route` (the stage 1 rule importer). `dns` is recognised and not used.
//! Types are checked against sing-box 1.15.0-alpha.13's registry
//! (`include/registry.go`).

use serde_json::{json, Map, Value};

use super::value::{go_duration_ms, scalar_text};
use super::{
    validate, Group, GroupKind, GroupParams, ImportResult, Node, Notice, NoticeKind, Rules, Security, SourceFormat, Spec, IGNORED_DNS,
};
use crate::rules::engine::import_singbox::{self, HostDirective, RuleSetLoader};
use crate::rules::engine::ImportError;

/// Protocol outbounds kept as native nodes.
pub const NATIVE_OUTBOUNDS: [&str; 17] = [
    "direct",
    "block",
    "socks",
    "http",
    "shadowsocks",
    "snell",
    "vmess",
    "trojan",
    "naive",
    "tor",
    "ssh",
    "shadowtls",
    "vless",
    "anytls",
    "hysteria",
    "hysteria2",
    "tuic",
];

/// Endpoints kept as native nodes.
pub const NATIVE_ENDPOINTS: [&str; 2] = ["wireguard", "masque"];

/// Endpoints refused at import (decided 2026-11-24).
pub const REFUSED_ENDPOINTS: [&str; 3] = ["tailscale", "openvpn", "openconnect"];

/// Top-level keys the import reads.
const READ_KEYS: [&str; 4] = ["outbounds", "endpoints", "route", "dns"];

fn transport_of(kind: &str, object: &Map<String, Value>) -> String {
    if let Some(t) = object.get("transport").and_then(|t| t.get("type")).and_then(Value::as_str) {
        return t.to_string();
    }
    match kind {
        "hysteria" | "hysteria2" | "tuic" => "quic".to_string(),
        "wireguard" | "masque" => "udp".to_string(),
        _ => "tcp".to_string(),
    }
}

fn security_of(kind: &str, object: &Map<String, Value>) -> Security {
    let tls = object.get("tls");
    let enabled = tls.and_then(|t| t.get("enabled")).and_then(Value::as_bool).unwrap_or(false);
    let reality = tls.and_then(|t| t.get("reality")).and_then(|r| r.get("enabled")).and_then(Value::as_bool).unwrap_or(false);
    if enabled && reality {
        Security::Reality
    } else if enabled || matches!(kind, "hysteria" | "hysteria2" | "tuic") {
        Security::Tls
    } else {
        Security::None
    }
}

/// `domain_resolver` names a DNS server of the subscription, which is not
/// used: point it at `fkdns`, keeping its other options.
fn rewrite_domain_resolver(object: &mut Map<String, Value>, tag: &str, notices: &mut Vec<Notice>) {
    let replacement = match object.get("domain_resolver") {
        None => return,
        Some(Value::String(_)) => json!("fkdns"),
        Some(Value::Object(o)) => {
            let mut o = o.clone();
            o.insert("server".into(), json!("fkdns"));
            Value::Object(o)
        }
        Some(_) => return,
    };
    object.insert("domain_resolver".into(), replacement);
    notices.push(Notice::new(
        NoticeKind::IgnoredDns,
        tag.to_string(),
        "domain_resolver now uses fkdns (subscription DNS servers are not used)",
    ));
}

fn node(kind: &str, tag: String, mut object: Map<String, Value>, endpoint: bool, notices: &mut Vec<Notice>) -> Result<Node, ImportError> {
    let detour = match object.get("detour") {
        Some(Value::String(d)) if !d.is_empty() => Some(d.clone()),
        Some(Value::String(_)) | Some(Value::Null) | None => None,
        Some(_) => return Err(ImportError::new(format!("outbound {tag:?}"), "detour: not a string")),
    };
    let mut rest = Map::new();
    for (k, v) in object.iter() {
        if k != "tag" && k != "detour" {
            rest.insert(k.clone(), v.clone());
        }
    }
    object = rest;
    rewrite_domain_resolver(&mut object, &tag, notices);
    let (server, port) = if kind == "wireguard" {
        let peer = object.get("peers").and_then(Value::as_array).and_then(|p| p.first());
        let server = peer.and_then(|p| p.get("address")).and_then(scalar_text).unwrap_or_default();
        let port = peer.and_then(|p| p.get("port")).and_then(Value::as_u64).unwrap_or(0);
        (server, port)
    } else {
        let server = object.get("server").and_then(scalar_text).unwrap_or_default();
        let port = object.get("server_port").and_then(Value::as_u64).unwrap_or(0);
        (server, port)
    };
    let transport = transport_of(kind, &object);
    let security = security_of(kind, &object);
    let spec = if endpoint { Spec::NativeEndpoint(object) } else { Spec::Native(object) };
    Ok(Node { tag, protocol: kind.to_string(), server, port: u16::try_from(port).unwrap_or(0), transport, security, detour, spec })
}

fn strings(value: Option<&Value>, at: &str) -> Result<Vec<String>, ImportError> {
    match value {
        None | Some(Value::Null) => Ok(Vec::new()),
        Some(Value::Array(items)) => items
            .iter()
            .enumerate()
            .map(|(i, v)| v.as_str().map(str::to_string).ok_or_else(|| ImportError::new(format!("{at}[{i}]"), "not a string")))
            .collect(),
        Some(_) => Err(ImportError::new(at, "not a list")),
    }
}

fn duration_secs(value: Option<&Value>, at: &str) -> Result<Option<u64>, ImportError> {
    match value {
        None | Some(Value::Null) => Ok(None),
        Some(Value::String(s)) => go_duration_ms(s).map(|ms| Some(ms / 1000)).map_err(|e| ImportError::new(at, e)),
        Some(_) => Err(ImportError::new(at, "not a duration string")),
    }
}

fn group(kind: &str, tag: String, object: &Map<String, Value>) -> Result<Group, ImportError> {
    let at = format!("outbound {tag:?}");
    let known: &[&str] = if kind == "selector" {
        &["type", "tag", "outbounds", "default", "interrupt_exist_connections"]
    } else {
        &["type", "tag", "outbounds", "url", "interval", "tolerance", "idle_timeout", "interrupt_exist_connections"]
    };
    if let Some(k) = object.keys().find(|k| !known.contains(&k.as_str())) {
        return Err(ImportError::new(at, format!("unknown field {k:?}")));
    }
    let members = strings(object.get("outbounds"), &format!("{at}.outbounds"))?;
    if members.is_empty() {
        return Err(ImportError::new(at, "no outbounds"));
    }
    let mut params = GroupParams {
        interrupt_exist_connections: object.get("interrupt_exist_connections").and_then(Value::as_bool),
        ..GroupParams::default()
    };
    let kind = if kind == "selector" {
        params.default = object.get("default").and_then(Value::as_str).filter(|s| !s.is_empty()).map(str::to_string);
        if let Some(d) = &params.default {
            if !members.contains(d) {
                return Err(ImportError::new(at, format!("default {d:?} is not one of its outbounds")));
            }
        }
        GroupKind::Select
    } else {
        params.url = object.get("url").and_then(Value::as_str).filter(|s| !s.is_empty()).map(str::to_string);
        params.interval_secs = duration_secs(object.get("interval"), &format!("{at}.interval"))?;
        params.tolerance_ms = object.get("tolerance").and_then(Value::as_u64);
        params.idle_timeout_secs = duration_secs(object.get("idle_timeout"), &format!("{at}.idle_timeout"))?;
        GroupKind::UrlTest
    };
    Ok(Group { name: tag, kind, members, params })
}

pub fn import_value(config: &Value, loader: &dyn RuleSetLoader) -> Result<ImportResult, ImportError> {
    let map = config.as_object().ok_or_else(|| ImportError::new("config", "not an object"))?;
    let mut notices = Vec::new();
    if map.contains_key("dns") {
        notices.push(Notice::new(NoticeKind::IgnoredDns, "dns", IGNORED_DNS));
    }
    let others: Vec<&str> = map.keys().map(String::as_str).filter(|k| !READ_KEYS.contains(k) && *k != "$schema").collect();
    if !others.is_empty() {
        notices.push(Notice::new(
            NoticeKind::IgnoredSettings,
            "config",
            format!("settings not used (the generator writes its own): {}", others.join(", ")),
        ));
    }
    let mut nodes = Vec::new();
    let mut groups = Vec::new();
    let list = |key: &str| -> Result<Vec<Value>, ImportError> {
        match map.get(key) {
            None | Some(Value::Null) => Ok(Vec::new()),
            Some(Value::Array(items)) => Ok(items.clone()),
            Some(_) => Err(ImportError::new(key, "not a list")),
        }
    };
    for (i, value) in list("outbounds")?.into_iter().enumerate() {
        let object = match value {
            Value::Object(o) => o,
            _ => return Err(ImportError::new(format!("outbounds[{i}]"), "not an object")),
        };
        let tag = match object.get("tag").and_then(Value::as_str) {
            Some(t) if !t.is_empty() => t.to_string(),
            _ => return Err(ImportError::new(format!("outbounds[{i}]"), "missing tag")),
        };
        let kind = object.get("type").and_then(Value::as_str).unwrap_or("").to_string();
        let at = format!("outbound {tag:?}");
        match kind.as_str() {
            "selector" | "urltest" => groups.push(group(&kind, tag, &object)?),
            "" => return Err(ImportError::new(at, "missing type")),
            "wireguard" => {
                return Err(ImportError::new(at, "the WireGuard outbound was removed in sing-box 1.13; use a WireGuard endpoint"))
            }
            "shadowsocksr" => return Err(ImportError::new(at, "ShadowsocksR was removed from sing-box 1.6")),
            "dns" => return Err(ImportError::new(at, "the dns outbound was removed in sing-box 1.13 (use the hijack-dns rule action)")),
            k if NATIVE_OUTBOUNDS.contains(&k) => nodes.push(node(k, tag, object, false, &mut notices)?),
            other => return Err(ImportError::new(at, format!("unknown outbound type {other:?}"))),
        }
    }
    for (i, value) in list("endpoints")?.into_iter().enumerate() {
        let object = match value {
            Value::Object(o) => o,
            _ => return Err(ImportError::new(format!("endpoints[{i}]"), "not an object")),
        };
        let tag = match object.get("tag").and_then(Value::as_str) {
            Some(t) if !t.is_empty() => t.to_string(),
            _ => return Err(ImportError::new(format!("endpoints[{i}]"), "missing tag")),
        };
        let kind = object.get("type").and_then(Value::as_str).unwrap_or("").to_string();
        let at = format!("endpoint {tag:?}");
        if REFUSED_ENDPOINTS.contains(&kind.as_str()) {
            return Err(ImportError::new(
                at,
                format!("{kind} endpoints are not supported (they run a network stack of their own, not a proxy protocol)"),
            ));
        }
        if !NATIVE_ENDPOINTS.contains(&kind.as_str()) {
            return Err(ImportError::new(at, format!("unknown endpoint type {kind:?}")));
        }
        nodes.push(node(&kind, tag, object, true, &mut notices)?);
    }
    let imported = import_singbox::import_value(config, loader)?;
    for directive in &imported.host {
        let HostDirective::Sniff { index, .. } = directive;
        notices.push(Notice::new(
            NoticeKind::HostDirective,
            format!("route.rules[{index}]"),
            "sniff is done by fkbrain for every connection; the rule is not used",
        ));
    }
    let result = ImportResult {
        format: SourceFormat::SingBox,
        nodes,
        groups,
        rules: Some(Rules { router: imported.router, host: imported.host }),
        notices,
    };
    validate(&result)?;
    Ok(result)
}
