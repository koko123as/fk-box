//! A mihomo proxy map (from Clash YAML or a share link) into a node: a native
//! sing-box outbound (fields checked against sing-box 1.15.0-alpha.13's
//! `option` package) when every key of the map has a sing-box equivalent,
//! otherwise the `mihomo` plugin with the map as written and a notice naming
//! the keys or values that moved it there.
//!
//! mihomo's decoder is weakly typed and reads `_` in keys as `-`
//! ([`super::value::Reader`]); keys it would leave at their zero value do
//! not count.

use serde_json::{json, Map, Value};

use super::value::{is_zero, port, scalar_text, Reader};
use super::{Node, Notice, NoticeKind, Security, Spec};
use crate::rules::engine::ImportError;

/// mihomo node types refused at import (decided 2026-11-24): they run a
/// whole network stack rather than a proxy protocol.
pub const REFUSED_TYPES: [&str; 4] = ["tailscale", "openvpn", "zerotier", "easytier"];

/// mihomo node types without a sing-box protocol: always the mihomo plugin.
pub const PLUGIN_TYPES: [&str; 8] = ["ssr", "snell", "mieru", "sudoku", "shadowquic", "gost-relay", "masque", "trusttunnel"];

/// mihomo node types with a native mapping.
pub const NATIVE_TYPES: [&str; 14] =
    ["ss", "vmess", "vless", "trojan", "hysteria", "hysteria2", "tuic", "wireguard", "anytls", "socks5", "http", "ssh", "direct", "reject"];

/// sing-box shadowsocks `method` values (option/shadowsocks.go).
pub const SS_METHODS: [&str; 18] = [
    "none",
    "aes-128-gcm",
    "aes-192-gcm",
    "aes-256-gcm",
    "chacha20-ietf-poly1305",
    "xchacha20-ietf-poly1305",
    "2022-blake3-aes-128-gcm",
    "2022-blake3-aes-256-gcm",
    "2022-blake3-chacha20-poly1305",
    "aes-128-ctr",
    "aes-192-ctr",
    "aes-256-ctr",
    "aes-128-cfb",
    "aes-192-cfb",
    "aes-256-cfb",
    "rc4-md5",
    "chacha20-ietf",
    "xchacha20",
];

/// sing-box uTLS fingerprints (option/tls.go `OutboundUTLSOptions`).
pub const UTLS_FINGERPRINTS: [&str; 15] = [
    "chrome_psk",
    "chrome_psk_shuffle",
    "chrome_padding_psk_shuffle",
    "chrome_pq",
    "chrome_pq_psk",
    "chrome",
    "firefox",
    "edge",
    "safari",
    "360",
    "qq",
    "ios",
    "android",
    "random",
    "randomized",
];

/// sing-box VMess `security` values (option/vmess.go).
const VMESS_SECURITY: [&str; 6] = ["auto", "none", "zero", "aes-128-cfb", "aes-128-gcm", "chacha20-poly1305"];

/// One mihomo proxy map into a node. A map that moves to the plugin adds a
/// [`NoticeKind::Downgraded`] notice.
pub fn from_mihomo(map: Map<String, Value>, notices: &mut Vec<Notice>) -> Result<Node, ImportError> {
    let name = match map.get("name").and_then(scalar_text) {
        Some(s) if !s.is_empty() => s,
        _ => return Err(ImportError::new("proxy", "missing name")),
    };
    let at = format!("proxy {name:?}");
    let kind = map.get("type").and_then(Value::as_str).map(|s| s.trim().to_ascii_lowercase()).unwrap_or_default();
    if kind.is_empty() {
        return Err(ImportError::new(at, "missing type"));
    }
    if REFUSED_TYPES.contains(&kind.as_str()) {
        return Err(ImportError::new(
            at,
            format!("{kind} nodes are not supported (they run a network stack of their own, not a proxy protocol)"),
        ));
    }
    match kind.as_str() {
        "dns" => return Err(ImportError::new(at, "type dns hands connections to mihomo's DNS server; not supported")),
        "rematch" => return Err(ImportError::new(at, "type rematch re-runs mihomo's rules; not supported")),
        "relay" => return Err(ImportError::new(at, "type relay was removed from mihomo; use dialer-proxy")),
        _ => {}
    }
    if !NATIVE_TYPES.contains(&kind.as_str()) && !PLUGIN_TYPES.contains(&kind.as_str()) {
        return Err(ImportError::new(at, format!("unknown proxy type {kind:?}")));
    }
    // `dialer-proxy` becomes the node's detour (sing-box `detour` on the
    // native outbound or on the plugin outbound).
    let mut detour = None;
    let mut rest = Map::new();
    for (key, value) in map {
        if key.replace('_', "-") == "dialer-proxy" {
            match value {
                Value::String(s) if !s.is_empty() => detour = Some(s),
                Value::String(_) | Value::Null => {}
                _ => return Err(ImportError::new(at, "dialer-proxy: not a string")),
            }
        } else {
            rest.insert(key, value);
        }
    }
    let map = rest;
    let server = map.get("server").and_then(scalar_text).unwrap_or_default();
    let port_number = match map.get("port") {
        Some(v) if !is_zero(v) => port(v).map_err(|e| ImportError::new(at.clone(), e))?,
        _ => 0,
    };
    if server.is_empty() && !matches!(kind.as_str(), "wireguard" | "direct" | "reject") {
        return Err(ImportError::new(at, "missing server"));
    }
    let (transport, security) = summary(&kind, &map);
    let spec = if PLUGIN_TYPES.contains(&kind.as_str()) {
        Spec::Mihomo(map)
    } else {
        match native(&kind, &map) {
            Ok(Native::Outbound(o)) => Spec::Native(o),
            Ok(Native::Endpoint(e)) => Spec::NativeEndpoint(e),
            Err(reason) => {
                notices.push(Notice::new(NoticeKind::Downgraded, name.clone(), format!("{kind} runs in the mihomo plugin: {reason}")));
                Spec::Mihomo(map)
            }
        }
    };
    Ok(Node { tag: name, protocol: kind, server, port: port_number, transport, security, detour, spec })
}

/// Transport and TLS layer of a mihomo map, for the model.
fn summary(kind: &str, map: &Map<String, Value>) -> (String, Security) {
    let text = |key: &str| map.get(key).and_then(scalar_text).map(|s| s.to_ascii_lowercase()).unwrap_or_default();
    let flag = |key: &str| match map.get(key) {
        Some(Value::Bool(b)) => *b,
        Some(Value::String(s)) => s == "true" || s == "1",
        _ => false,
    };
    let mut transport = match kind {
        "hysteria" | "hysteria2" | "tuic" | "shadowquic" => "quic".to_string(),
        "wireguard" => "udp".to_string(),
        "mieru" => {
            let t = text("transport");
            if t.is_empty() {
                "tcp".to_string()
            } else {
                t
            }
        }
        _ => {
            let n = text("network");
            if n.is_empty() {
                "tcp".to_string()
            } else {
                n
            }
        }
    };
    if transport == "ws" {
        let upgrade = map.get("ws-opts").and_then(Value::as_object).and_then(|o| o.get("v2ray-http-upgrade")).and_then(Value::as_bool);
        if upgrade == Some(true) {
            transport = "httpupgrade".to_string();
        }
    }
    let reality = map.get("reality-opts").map(|v| !is_zero(v)).unwrap_or(false);
    let security = if reality && (flag("tls") || kind == "trojan") {
        Security::Reality
    } else if flag("tls") || matches!(kind, "trojan" | "hysteria" | "hysteria2" | "tuic" | "anytls") {
        Security::Tls
    } else {
        Security::None
    };
    (transport, security)
}

enum Native {
    Outbound(Map<String, Value>),
    Endpoint(Map<String, Value>),
}

fn native(kind: &str, map: &Map<String, Value>) -> Result<Native, String> {
    let mut r = Reader::new(map, "");
    r.skip("name");
    r.skip("type");
    let mut out = Map::new();
    let endpoint = match kind {
        "ss" => shadowsocks(&mut r, &mut out).map(|_| false),
        "vmess" => vmess(&mut r, &mut out).map(|_| false),
        "vless" => vless(&mut r, &mut out).map(|_| false),
        "trojan" => trojan(&mut r, &mut out).map(|_| false),
        "hysteria" => hysteria(&mut r, &mut out).map(|_| false),
        "hysteria2" => hysteria2(&mut r, &mut out).map(|_| false),
        "tuic" => tuic(&mut r, &mut out).map(|_| false),
        "wireguard" => wireguard(&mut r, &mut out).map(|_| true),
        "anytls" => anytls(&mut r, &mut out).map(|_| false),
        "socks5" => socks5(&mut r, &mut out).map(|_| false),
        "http" => http(&mut r, &mut out).map(|_| false),
        "ssh" => ssh(&mut r, &mut out).map(|_| false),
        "direct" => direct(&mut r, &mut out).map(|_| false),
        "reject" => {
            out.insert("type".into(), json!("block"));
            Ok(false)
        }
        other => Err(format!("no native mapping for {other}")),
    }?;
    done(&r)?;
    Ok(if endpoint { Native::Endpoint(out) } else { Native::Outbound(out) })
}

/// Fails with the keys nothing read.
fn done(r: &Reader) -> Result<(), String> {
    let left = r.leftovers();
    if left.is_empty() {
        Ok(())
    } else {
        Err(format!("no sing-box field for {}", left.join(", ")))
    }
}

fn put(out: &mut Map<String, Value>, key: &str, value: Value) {
    out.insert(key.to_string(), value);
}

fn server(r: &mut Reader, out: &mut Map<String, Value>, need_port: bool) -> Result<(), String> {
    let server = r.string("server")?.ok_or("missing server")?;
    put(out, "server", json!(server));
    match r.raw("port") {
        Some(v) if !is_zero(v) => put(out, "server_port", json!(port(v)?)),
        _ if need_port => return Err("missing port".into()),
        _ => {}
    }
    Ok(())
}

/// mihomo `BasicOption` fields with a sing-box dial field.
fn dial(r: &mut Reader, out: &mut Map<String, Value>) -> Result<(), String> {
    if r.boolean("tfo")? {
        put(out, "tcp_fast_open", json!(true));
    }
    if r.boolean("mptcp")? {
        put(out, "tcp_multi_path", json!(true));
    }
    if let Some(name) = r.string("interface-name")? {
        put(out, "bind_interface", json!(name));
    }
    if let Some(mark) = r.uint("routing-mark")? {
        if mark > 0 {
            put(out, "routing_mark", json!(mark));
        }
    }
    match r.string("ip-version")? {
        None => {}
        Some(v) if v == "dual" => {}
        Some(v) => return Err(format!("ip-version {v} (address family preference belongs to the brain's resolver)")),
    }
    Ok(())
}

/// mihomo `udp: false` (its default) is TCP only.
fn udp_flag(r: &mut Reader, out: &mut Map<String, Value>) -> Result<(), String> {
    if !r.boolean("udp")? {
        put(out, "network", json!("tcp"));
    }
    Ok(())
}

/// The TLS block. Keys mihomo has and sing-box does not (`fingerprint`
/// pinning, `certificate`/`private-key`, `name-cert-verify`, `ech-opts`,
/// `shadow-tls-opts`, `restls-opts`, `jls-opts`, …) are left unread, so they
/// downgrade the node.
fn tls(r: &mut Reader, enabled: bool, sni_keys: &[&str], utls: bool, reality: bool) -> Result<Option<Map<String, Value>>, String> {
    let mut sni = None;
    for key in sni_keys {
        if let Some(s) = r.string(key)? {
            if sni.is_none() {
                sni = Some(s);
            }
        }
    }
    let insecure = r.boolean("skip-cert-verify")?;
    let alpn = r.strings("alpn")?;
    let mut fingerprint = if utls { r.string("client-fingerprint")? } else { None };
    let reality_opts = if reality { r.object("reality-opts")? } else { None };
    if !enabled {
        return Ok(None);
    }
    let mut t = Map::new();
    put(&mut t, "enabled", json!(true));
    if let Some(s) = sni {
        put(&mut t, "server_name", json!(s));
    }
    if insecure {
        put(&mut t, "insecure", json!(true));
    }
    if !alpn.is_empty() {
        put(&mut t, "alpn", json!(alpn));
    }
    if fingerprint.as_deref() == Some("none") {
        fingerprint = None;
    }
    if let Some(opts) = reality_opts {
        let mut rr = Reader::new(opts, "reality-opts.");
        let public_key = rr.string("public-key")?.ok_or("reality-opts.public-key is missing")?;
        let short_id = rr.string("short-id")?;
        done(&rr)?;
        let mut m = Map::new();
        put(&mut m, "enabled", json!(true));
        put(&mut m, "public_key", json!(public_key));
        if let Some(sid) = short_id {
            put(&mut m, "short_id", json!(sid));
        }
        put(&mut t, "reality", Value::Object(m));
        // sing-box's REALITY client needs uTLS; mihomo uses chrome when unset.
        if fingerprint.is_none() {
            fingerprint = Some("chrome".to_string());
        }
    }
    if let Some(fp) = fingerprint {
        if !UTLS_FINGERPRINTS.contains(&fp.as_str()) {
            return Err(format!("client-fingerprint {fp:?} has no sing-box uTLS fingerprint"));
        }
        put(&mut t, "utls", json!({"enabled": true, "fingerprint": fp}));
    }
    Ok(Some(t))
}

fn headers(map: &Map<String, Value>, at: &str) -> Result<Map<String, Value>, String> {
    let mut out = Map::new();
    for (k, v) in map {
        match v {
            Value::Array(items) => {
                let mut list = Vec::new();
                for item in items {
                    list.push(scalar_text(item).ok_or_else(|| format!("{at}.{k}: not a string"))?);
                }
                if !list.is_empty() {
                    out.insert(k.clone(), json!(list));
                }
            }
            other => {
                let s = scalar_text(other).ok_or_else(|| format!("{at}.{k}: not a string"))?;
                if !s.is_empty() {
                    out.insert(k.clone(), json!(s));
                }
            }
        }
    }
    Ok(out)
}

/// The V2Ray transport of vmess/vless/trojan. Option blocks of other
/// networks are read and not used (mihomo ignores them too).
fn transport(r: &mut Reader, out: &mut Map<String, Value>, tls_on: bool) -> Result<(), String> {
    let network = r.string("network")?.map(|n| n.to_ascii_lowercase()).unwrap_or_else(|| "tcp".to_string());
    match network.as_str() {
        "tcp" | "" => {}
        "ws" => {
            let mut t = Map::new();
            let mut upgrade = false;
            let mut host = None;
            let mut hdrs = Map::new();
            let mut path = None;
            let mut early = None;
            let mut early_name = None;
            if let Some(opts) = r.object("ws-opts")? {
                let mut o = Reader::new(opts, "ws-opts.");
                path = o.string("path")?;
                if let Some(h) = o.object("headers")? {
                    hdrs = headers(h, "ws-opts.headers")?;
                }
                early = o.uint("max-early-data")?;
                early_name = o.string("early-data-header-name")?;
                upgrade = o.boolean("v2ray-http-upgrade")?;
                // v2ray-http-upgrade-fast-open has no sing-box equivalent: left unread.
                done(&o)?;
            }
            if upgrade {
                if early.unwrap_or(0) > 0 || early_name.is_some() {
                    return Err("ws-opts early data with v2ray-http-upgrade".into());
                }
                let host_key = hdrs.keys().find(|k| k.eq_ignore_ascii_case("host")).cloned();
                if let Some(k) = host_key {
                    if let Some(Value::String(h)) = hdrs.remove(&k) {
                        host = Some(h);
                    }
                }
                put(&mut t, "type", json!("httpupgrade"));
                if let Some(h) = host {
                    put(&mut t, "host", json!(h));
                }
                if let Some(p) = path {
                    put(&mut t, "path", json!(p));
                }
                if !hdrs.is_empty() {
                    put(&mut t, "headers", Value::Object(hdrs));
                }
            } else {
                put(&mut t, "type", json!("ws"));
                if let Some(p) = path {
                    put(&mut t, "path", json!(p));
                }
                if !hdrs.is_empty() {
                    put(&mut t, "headers", Value::Object(hdrs));
                }
                if let Some(e) = early {
                    if e > 0 {
                        put(&mut t, "max_early_data", json!(e));
                    }
                }
                if let Some(n) = early_name {
                    put(&mut t, "early_data_header_name", json!(n));
                }
            }
            put(out, "transport", Value::Object(t));
        }
        "grpc" => {
            let mut t = Map::new();
            put(&mut t, "type", json!("grpc"));
            if let Some(opts) = r.object("grpc-opts")? {
                let mut o = Reader::new(opts, "grpc-opts.");
                if let Some(name) = o.string("grpc-service-name")? {
                    put(&mut t, "service_name", json!(name));
                }
                // grpc-user-agent, ping-interval and the connection pool keys
                // have no sing-box equivalent: left unread.
                done(&o)?;
            }
            put(out, "transport", Value::Object(t));
        }
        "h2" => {
            if !tls_on {
                return Err("network h2 without tls".into());
            }
            let mut t = Map::new();
            put(&mut t, "type", json!("http"));
            if let Some(opts) = r.object("h2-opts")? {
                let mut o = Reader::new(opts, "h2-opts.");
                let host = o.strings("host")?;
                if !host.is_empty() {
                    put(&mut t, "host", json!(host));
                }
                if let Some(p) = o.string("path")? {
                    put(&mut t, "path", json!(p));
                }
                done(&o)?;
            }
            put(out, "transport", Value::Object(t));
        }
        other => return Err(format!("network {other} has no sing-box transport")),
    }
    for key in ["ws-opts", "grpc-opts", "h2-opts", "http-opts", "xhttp-opts"] {
        r.skip(key);
    }
    Ok(())
}

/// mihomo `smux` into sing-box `multiplex`.
fn smux(r: &mut Reader, out: &mut Map<String, Value>) -> Result<(), String> {
    let opts = match r.object("smux")? {
        Some(o) => o,
        None => return Ok(()),
    };
    let mut s = Reader::new(opts, "smux.");
    if !s.boolean("enabled")? {
        // Disabled: mihomo reads nothing else of it.
        return Ok(());
    }
    let mut m = Map::new();
    put(&mut m, "enabled", json!(true));
    if let Some(p) = s.string("protocol")? {
        match p.as_str() {
            "h2mux" | "smux" | "yamux" => put(&mut m, "protocol", json!(p)),
            other => return Err(format!("smux.protocol {other}")),
        }
    }
    for (from, to) in [("max-connections", "max_connections"), ("min-streams", "min_streams"), ("max-streams", "max_streams")] {
        if let Some(v) = s.uint(from)? {
            if v > 0 {
                put(&mut m, to, json!(v));
            }
        }
    }
    if s.boolean("padding")? {
        put(&mut m, "padding", json!(true));
    }
    // statistic only shows mux connections in mihomo's dashboard.
    s.skip("statistic");
    // only-tcp and brutal-opts have no equivalent here: left unread.
    done(&s)?;
    put(out, "multiplex", Value::Object(m));
    Ok(())
}

fn shadowsocks(r: &mut Reader, out: &mut Map<String, Value>) -> Result<(), String> {
    put(out, "type", json!("shadowsocks"));
    server(r, out, true)?;
    dial(r, out)?;
    let cipher = r.string("cipher")?.ok_or("missing cipher")?;
    let method = if cipher == "dummy" { "none".to_string() } else { cipher.to_ascii_lowercase() };
    if !SS_METHODS.contains(&method.as_str()) {
        return Err(format!("cipher {cipher}"));
    }
    put(out, "method", json!(method));
    if let Some(p) = r.string("password")? {
        put(out, "password", json!(p));
    }
    udp_flag(r, out)?;
    if r.boolean("udp-over-tcp")? {
        // mihomo's default is the legacy version 1; sing-box's is 2.
        let version = r.uint("udp-over-tcp-version")?.unwrap_or(1);
        if version != 1 && version != 2 {
            return Err(format!("udp-over-tcp-version {version}"));
        }
        put(out, "udp_over_tcp", json!({"enabled": true, "version": version}));
    } else {
        r.skip("udp-over-tcp-version");
    }
    match r.string("plugin")? {
        None => r.skip("plugin-opts"),
        Some(p) if p == "obfs" => {
            let mut mode = None;
            let mut host = None;
            if let Some(opts) = r.object("plugin-opts")? {
                let mut o = Reader::new(opts, "plugin-opts.");
                mode = o.string("mode")?;
                host = o.string("host")?;
                done(&o)?;
            }
            let mode = mode.ok_or("plugin-opts.mode is missing")?;
            if mode != "http" && mode != "tls" {
                return Err(format!("obfs mode {mode}"));
            }
            // sing-box's obfs-local reads `obfs` and `obfs-host`; mihomo's default host is bing.com.
            let host = host.unwrap_or_else(|| "bing.com".to_string());
            put(out, "plugin", json!("obfs-local"));
            put(out, "plugin_opts", json!(format!("obfs={mode};obfs-host={host}")));
        }
        Some(p) => return Err(format!("plugin {p} (sing-box semantics differ or it has none)")),
    }
    // Only the shadow-tls/restls plugins read client-fingerprint.
    r.skip("client-fingerprint");
    smux(r, out)
}

/// VMess packet encoding as mihomo decides it (adapter/outbound/vmess.go).
fn vmess_packet_encoding(r: &mut Reader) -> Result<&'static str, String> {
    let declared = r.string("packet-encoding")?;
    let xudp = r.boolean("xudp")?;
    let packet_addr = r.boolean("packet-addr")?;
    Ok(match declared.as_deref() {
        Some("packetaddr") | Some("packet") => "packetaddr",
        Some("xudp") => "xudp",
        _ if xudp => "xudp",
        _ if packet_addr => "packetaddr",
        _ => "",
    })
}

fn vmess(r: &mut Reader, out: &mut Map<String, Value>) -> Result<(), String> {
    put(out, "type", json!("vmess"));
    server(r, out, true)?;
    dial(r, out)?;
    put(out, "uuid", json!(r.string("uuid")?.ok_or("missing uuid")?));
    let security = r.string("cipher")?.unwrap_or_else(|| "auto".to_string());
    if !VMESS_SECURITY.contains(&security.as_str()) {
        return Err(format!("cipher {security}"));
    }
    put(out, "security", json!(security));
    if let Some(aid) = r.uint("alterId")? {
        if aid > 0 {
            put(out, "alter_id", json!(aid));
        }
    }
    if r.boolean("global-padding")? {
        put(out, "global_padding", json!(true));
    }
    if r.boolean("authenticated-length")? {
        put(out, "authenticated_length", json!(true));
    }
    udp_flag(r, out)?;
    let encoding = vmess_packet_encoding(r)?;
    if !encoding.is_empty() {
        put(out, "packet_encoding", json!(encoding));
    }
    let tls_on = r.boolean("tls")?;
    if let Some(t) = tls(r, tls_on, &["servername"], true, true)? {
        put(out, "tls", Value::Object(t));
    }
    transport(r, out, tls_on)?;
    smux(r, out)
}

fn vless(r: &mut Reader, out: &mut Map<String, Value>) -> Result<(), String> {
    put(out, "type", json!("vless"));
    server(r, out, true)?;
    dial(r, out)?;
    put(out, "uuid", json!(r.string("uuid")?.ok_or("missing uuid")?));
    if let Some(flow) = r.string("flow")? {
        if flow != "xtls-rprx-vision" {
            return Err(format!("flow {flow}"));
        }
        put(out, "flow", json!(flow));
    }
    match r.string("encryption")? {
        None => {}
        Some(e) if e == "none" => {}
        Some(_) => return Err("VLESS encryption".into()),
    }
    udp_flag(r, out)?;
    // mihomo's VLESS default is XUDP (adapter/outbound/vless.go).
    let declared = r.string("packet-encoding")?;
    let xudp = r.boolean("xudp")?;
    let packet_addr = r.boolean("packet-addr")?;
    let encoding = match declared.as_deref() {
        Some("packetaddr") | Some("packet") => "packetaddr",
        _ if xudp => "xudp",
        _ if packet_addr => "packetaddr",
        _ => "xudp",
    };
    put(out, "packet_encoding", json!(encoding));
    let tls_on = r.boolean("tls")?;
    if let Some(t) = tls(r, tls_on, &["servername"], true, true)? {
        put(out, "tls", Value::Object(t));
    }
    transport(r, out, tls_on)?;
    smux(r, out)
}

fn trojan(r: &mut Reader, out: &mut Map<String, Value>) -> Result<(), String> {
    put(out, "type", json!("trojan"));
    server(r, out, true)?;
    dial(r, out)?;
    put(out, "password", json!(r.string("password")?.ok_or("missing password")?));
    udp_flag(r, out)?;
    if let Some(t) = tls(r, true, &["sni"], true, true)? {
        put(out, "tls", Value::Object(t));
    }
    transport(r, out, true)?;
    // ss-opts (Trojan-Go's shadowsocks layer) is left unread when enabled.
    if let Some(opts) = r.object("ss-opts")? {
        let mut o = Reader::new(opts, "ss-opts.");
        if o.boolean("enabled")? {
            return Err("ss-opts".into());
        }
    }
    smux(r, out)
}

/// mihomo bandwidth (`100`, `100 Mbps`, `1 Gbps`) in whole Mbps.
fn mbps(text: &str) -> Result<u64, String> {
    let t = text.trim();
    if let Ok(n) = t.parse::<u64>() {
        return Ok(n);
    }
    let lower = t.to_ascii_lowercase();
    let (number, factor) = if let Some(n) = lower.strip_suffix("mbps") {
        (n, 1)
    } else if let Some(n) = lower.strip_suffix("gbps") {
        (n, 1000)
    } else {
        return Err(format!("bandwidth {text:?} (only whole Mbps/Gbps values map to sing-box)"));
    };
    number.trim().parse::<u64>().map(|n| n * factor).map_err(|_| format!("bandwidth {text:?}"))
}

/// mihomo `ports` (`443,1000-2000`) into sing-box `server_ports` (`443:443`, `1000:2000`).
fn server_ports(text: &str) -> Result<Vec<String>, String> {
    let mut out = Vec::new();
    for part in text.split([',', '/']) {
        let part = part.trim();
        if part.is_empty() {
            continue;
        }
        let (a, b) = match part.split_once('-') {
            Some((a, b)) => (a.trim(), b.trim()),
            None => (part, part),
        };
        let a: u16 = a.parse().map_err(|_| format!("ports {text:?}"))?;
        let b: u16 = b.parse().map_err(|_| format!("ports {text:?}"))?;
        out.push(format!("{}:{}", a.min(b), a.max(b)));
    }
    if out.is_empty() {
        return Err(format!("ports {text:?}"));
    }
    Ok(out)
}

fn hysteria_ports(r: &mut Reader, out: &mut Map<String, Value>) -> Result<(), String> {
    put(out, "server", json!(r.string("server")?.ok_or("missing server")?));
    let ports = r.string("ports")?;
    match r.raw("port") {
        Some(v) if !is_zero(v) => put(out, "server_port", json!(port(v)?)),
        _ if ports.is_none() => return Err("missing port".into()),
        _ => {}
    }
    if let Some(p) = ports {
        put(out, "server_ports", json!(server_ports(&p)?));
    }
    Ok(())
}

fn hysteria2(r: &mut Reader, out: &mut Map<String, Value>) -> Result<(), String> {
    put(out, "type", json!("hysteria2"));
    hysteria_ports(r, out)?;
    dial(r, out)?;
    if let Some(hop) = r.string("hop-interval")? {
        // mihomo: seconds or a range of seconds, at least 5.
        let (a, b) = match hop.split_once('-') {
            Some((a, b)) => (a.trim().to_string(), Some(b.trim().to_string())),
            None => (hop.trim().to_string(), None),
        };
        let a: u64 = a.parse().map_err(|_| format!("hop-interval {hop:?}"))?;
        put(out, "hop_interval", json!(format!("{}s", a.max(5))));
        if let Some(b) = b {
            let b: u64 = b.parse().map_err(|_| format!("hop-interval {hop:?}"))?;
            put(out, "hop_interval_max", json!(format!("{}s", b.max(a.max(5)))));
        }
    }
    if let Some(up) = r.string("up")? {
        put(out, "up_mbps", json!(mbps(&up)?));
    }
    if let Some(down) = r.string("down")? {
        put(out, "down_mbps", json!(mbps(&down)?));
    }
    if let Some(p) = r.string("password")? {
        put(out, "password", json!(p));
    }
    match r.string("obfs")? {
        None => r.skip("obfs-password"),
        Some(o) if o == "salamander" => {
            let password = r.string("obfs-password")?.unwrap_or_default();
            put(out, "obfs", json!({"type": "salamander", "password": password}));
        }
        Some(o) => return Err(format!("obfs {o}")),
    }
    if let Some(t) = tls(r, true, &["sni"], false, false)? {
        put(out, "tls", Value::Object(t));
    }
    if let Some(profile) = r.string("bbr-profile")? {
        match profile.as_str() {
            "standard" | "conservative" | "aggressive" => put(out, "bbr_profile", json!(profile)),
            other => return Err(format!("bbr-profile {other}")),
        }
    }
    // UDP is always on in mihomo's hysteria2.
    r.skip("udp");
    Ok(())
}

fn hysteria(r: &mut Reader, out: &mut Map<String, Value>) -> Result<(), String> {
    put(out, "type", json!("hysteria"));
    hysteria_ports(r, out)?;
    dial(r, out)?;
    if let Some(hop) = r.uint("hop-interval")? {
        if hop > 0 {
            put(out, "hop_interval", json!(format!("{hop}s")));
        }
    }
    match r.string("protocol")? {
        None => {}
        Some(p) if p == "udp" => {}
        Some(p) => return Err(format!("protocol {p}")),
    }
    let up = r.string("up")?.ok_or("missing up")?;
    let down = r.string("down")?.ok_or("missing down")?;
    put(out, "up_mbps", json!(mbps(&up)?));
    put(out, "down_mbps", json!(mbps(&down)?));
    if let Some(a) = r.string("auth-str")? {
        put(out, "auth_str", json!(a));
    }
    if let Some(a) = r.string("auth")? {
        // Base64 in both (sing-box `auth` is bytes).
        put(out, "auth", json!(a));
    }
    if let Some(o) = r.string("obfs")? {
        put(out, "obfs", json!(o));
    }
    if r.boolean("disable-mtu-discovery")? {
        put(out, "disable_path_mtu_discovery", json!(true));
    }
    if let Some(t) = tls(r, true, &["sni"], false, false)? {
        put(out, "tls", Value::Object(t));
    }
    r.skip("udp");
    Ok(())
}

fn tuic(r: &mut Reader, out: &mut Map<String, Value>) -> Result<(), String> {
    if r.has("token") {
        return Err("TUIC v4 (token); sing-box has TUIC v5 only".into());
    }
    put(out, "type", json!("tuic"));
    server(r, out, true)?;
    dial(r, out)?;
    put(out, "uuid", json!(r.string("uuid")?.ok_or("missing uuid")?));
    if let Some(p) = r.string("password")? {
        put(out, "password", json!(p));
    }
    if let Some(cc) = r.string("congestion-controller")? {
        match cc.as_str() {
            "cubic" | "new_reno" | "bbr" => put(out, "congestion_control", json!(cc)),
            other => return Err(format!("congestion-controller {other}")),
        }
    }
    if let Some(mode) = r.string("udp-relay-mode")? {
        match mode.as_str() {
            "native" | "quic" => put(out, "udp_relay_mode", json!(mode)),
            other => return Err(format!("udp-relay-mode {other}")),
        }
    }
    if r.boolean("reduce-rtt")? {
        put(out, "zero_rtt_handshake", json!(true));
    }
    if let Some(ms) = r.uint("heartbeat-interval")? {
        if ms > 0 {
            put(out, "heartbeat", json!(format!("{ms}ms")));
        }
    }
    if r.boolean("udp-over-stream")? {
        put(out, "udp_over_stream", json!(true));
    }
    let disable_sni = r.boolean("disable-sni")?;
    if let Some(mut t) = tls(r, true, &["sni"], false, false)? {
        if disable_sni {
            put(&mut t, "disable_sni", json!(true));
        }
        put(out, "tls", Value::Object(t));
    }
    r.skip("udp");
    Ok(())
}

fn with_prefix(address: &str, bits: u8) -> String {
    if address.contains('/') {
        address.to_string()
    } else {
        format!("{address}/{bits}")
    }
}

fn wireguard(r: &mut Reader, out: &mut Map<String, Value>) -> Result<(), String> {
    if r.has("peers") {
        return Err("peers (several WireGuard peers)".into());
    }
    put(out, "type", json!("wireguard"));
    let mut address = Vec::new();
    if let Some(ip) = r.string("ip")? {
        address.push(with_prefix(&ip, 32));
    }
    if let Some(ip) = r.string("ipv6")? {
        address.push(with_prefix(&ip, 128));
    }
    if address.is_empty() {
        return Err("missing ip".into());
    }
    put(out, "address", json!(address));
    put(out, "private_key", json!(r.string("private-key")?.ok_or("missing private-key")?));
    if let Some(mtu) = r.uint("mtu")? {
        if mtu > 0 {
            put(out, "mtu", json!(mtu));
        }
    }
    if let Some(workers) = r.uint("workers")? {
        if workers > 0 {
            put(out, "workers", json!(workers));
        }
    }
    dial(r, out)?;
    let mut peer = Map::new();
    put(&mut peer, "address", json!(r.string("server")?.ok_or("missing server")?));
    let peer_port = match r.raw("port") {
        Some(v) => port(v)?,
        None => return Err("missing port".into()),
    };
    put(&mut peer, "port", json!(peer_port));
    put(&mut peer, "public_key", json!(r.string("public-key")?.ok_or("missing public-key")?));
    if let Some(psk) = r.string("pre-shared-key")? {
        put(&mut peer, "pre_shared_key", json!(psk));
    }
    let allowed = r.strings("allowed-ips")?;
    let allowed = if allowed.is_empty() { vec!["0.0.0.0/0".to_string(), "::/0".to_string()] } else { allowed };
    put(&mut peer, "allowed_ips", json!(allowed));
    if let Some(k) = r.uint("persistent-keepalive")? {
        if k > 0 {
            put(&mut peer, "persistent_keepalive_interval", json!(k));
        }
    }
    match r.raw("reserved") {
        None => {}
        Some(Value::Array(items)) => {
            let mut bytes = Vec::new();
            for item in items {
                match item.as_u64() {
                    Some(b) if b <= 255 => bytes.push(b),
                    _ => return Err("reserved: not a list of bytes".into()),
                }
            }
            if !bytes.is_empty() {
                put(&mut peer, "reserved", json!(bytes));
            }
        }
        Some(v) if is_zero(v) => {}
        Some(_) => return Err("reserved in string form".into()),
    }
    put(out, "peers", json!([Value::Object(peer)]));
    r.skip("udp");
    Ok(())
}

fn anytls(r: &mut Reader, out: &mut Map<String, Value>) -> Result<(), String> {
    put(out, "type", json!("anytls"));
    server(r, out, true)?;
    dial(r, out)?;
    if let Some(p) = r.string("password")? {
        put(out, "password", json!(p));
    }
    if let Some(t) = tls(r, true, &["sni"], true, false)? {
        put(out, "tls", Value::Object(t));
    }
    for (from, to) in [("idle-session-check-interval", "idle_session_check_interval"), ("idle-session-timeout", "idle_session_timeout")] {
        if let Some(secs) = r.uint(from)? {
            if secs > 0 {
                put(out, to, json!(format!("{secs}s")));
            }
        }
    }
    if let Some(n) = r.uint("min-idle-session")? {
        if n > 0 {
            put(out, "min_idle_session", json!(n));
        }
    }
    if let Some(m) = r.string("client-metadata")? {
        put(out, "client_metadata", json!(m));
    }
    // sing-box's AnyTLS always offers UDP over the session; mihomo's `udp`
    // only switches its UDP on.
    r.skip("udp");
    Ok(())
}

fn socks5(r: &mut Reader, out: &mut Map<String, Value>) -> Result<(), String> {
    put(out, "type", json!("socks"));
    server(r, out, true)?;
    dial(r, out)?;
    put(out, "version", json!("5"));
    if let Some(u) = r.string("username")? {
        put(out, "username", json!(u));
    }
    if let Some(p) = r.string("password")? {
        put(out, "password", json!(p));
    }
    if r.boolean("tls")? {
        return Err("SOCKS5 over TLS".into());
    }
    // Without TLS, mihomo does not read skip-cert-verify.
    r.skip("skip-cert-verify");
    udp_flag(r, out)
}

fn http(r: &mut Reader, out: &mut Map<String, Value>) -> Result<(), String> {
    put(out, "type", json!("http"));
    server(r, out, true)?;
    dial(r, out)?;
    if let Some(u) = r.string("username")? {
        put(out, "username", json!(u));
    }
    if let Some(p) = r.string("password")? {
        put(out, "password", json!(p));
    }
    let tls_on = r.boolean("tls")?;
    if let Some(t) = tls(r, tls_on, &["sni"], false, false)? {
        put(out, "tls", Value::Object(t));
    }
    if let Some(h) = r.object("headers")? {
        let h = headers(h, "headers")?;
        if !h.is_empty() {
            put(out, "headers", Value::Object(h));
        }
    }
    // HTTP proxies carry no UDP in either kernel.
    r.skip("udp");
    Ok(())
}

fn ssh(r: &mut Reader, out: &mut Map<String, Value>) -> Result<(), String> {
    put(out, "type", json!("ssh"));
    server(r, out, true)?;
    dial(r, out)?;
    if let Some(u) = r.string("username")? {
        put(out, "user", json!(u));
    }
    if let Some(p) = r.string("password")? {
        put(out, "password", json!(p));
    }
    if let Some(key) = r.string("private-key")? {
        if !key.contains("-----BEGIN") {
            return Err("private-key as a file path (mihomo resolves it against its own directory)".into());
        }
        put(out, "private_key", json!(key));
    }
    if let Some(p) = r.string("private-key-passphrase")? {
        put(out, "private_key_passphrase", json!(p));
    }
    let host_key = r.strings("host-key")?;
    if !host_key.is_empty() {
        put(out, "host_key", json!(host_key));
    }
    let algorithms = r.strings("host-key-algorithms")?;
    if !algorithms.is_empty() {
        put(out, "host_key_algorithms", json!(algorithms));
    }
    r.skip("udp");
    Ok(())
}

fn direct(r: &mut Reader, out: &mut Map<String, Value>) -> Result<(), String> {
    put(out, "type", json!("direct"));
    r.skip("server");
    r.skip("port");
    dial(r, out)?;
    r.skip("udp");
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn helpers() {
        assert_eq!(mbps("100").unwrap(), 100);
        assert_eq!(mbps("50 Mbps").unwrap(), 50);
        assert_eq!(mbps("1 Gbps").unwrap(), 1000);
        assert!(mbps("10 MB/s").is_err());
        assert_eq!(server_ports("443,1000-2000").unwrap(), vec!["443:443".to_string(), "1000:2000".to_string()]);
        assert!(server_ports("a-b").is_err());
        assert_eq!(with_prefix("10.0.0.2", 32), "10.0.0.2/32");
        assert_eq!(with_prefix("fd00::2/128", 128), "fd00::2/128");
    }
}
