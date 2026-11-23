//! Share links into nodes. Each link becomes the mihomo proxy map mihomo
//! 1.19.34's `ConvertsV2Ray` (common/convert) builds from it, which
//! [`super::native`] then maps to sing-box or keeps for the mihomo plugin;
//! links only Xray carries without loss go to [`super::xray`].
//!
//! Differences from `ConvertsV2Ray`, all on purpose: a link that cannot be
//! read is reported (a notice naming the line) instead of skipped quietly;
//! `wireguard://` and a plain `mieru` refusal are added; an empty name falls
//! back to `server:port`; no random `User-Agent` header is invented;
//! `allowInsecure`/`insecure` is honoured for VLESS/VMess/Trojan;
//! `https://` proxy links keep certificate verification on; an unknown
//! Shadowsocks plugin is an error rather than dropped.

use std::collections::BTreeMap;

use serde_json::{json, Map, Value};

use super::text::{base64_text, parse_url, percent_decode, Url};
use super::{native, validate, xray, ImportResult, Node, Notice, NoticeKind, SourceFormat, Spec};
use crate::rules::engine::ImportError;

/// What one line gave.
#[derive(Debug, Clone, PartialEq)]
pub enum Link {
    /// A mihomo proxy map.
    Mihomo(Map<String, Value>),
    /// A finished node (the Xray plugin).
    Node(Node),
}

#[derive(Debug, Clone, PartialEq, Default)]
pub struct Parsed {
    pub links: Vec<Link>,
    /// Link parameters with no effect in any implementation chosen.
    pub warnings: Vec<String>,
}

/// mihomo's `uniqueName`, also registering the suffixed name.
fn unique(names: &mut BTreeMap<String, u32>, name: &str) -> String {
    if !names.contains_key(name) {
        names.insert(name.to_string(), 0);
        return name.to_string();
    }
    loop {
        let next = {
            let counter = names.get_mut(name).expect("present");
            *counter += 1;
            format!("{name}-{:02}", *counter)
        };
        if !names.contains_key(&next) {
            names.insert(next.clone(), 0);
            return next;
        }
    }
}

fn base_name(url: &Url) -> String {
    if url.fragment.trim().is_empty() {
        format!("{}:{}", url.host, url.port)
    } else {
        url.fragment.clone()
    }
}

fn put(m: &mut Map<String, Value>, key: &str, value: Value) {
    m.insert(key.to_string(), value);
}

fn put_str(m: &mut Map<String, Value>, key: &str, value: &str) {
    if !value.is_empty() {
        m.insert(key.to_string(), json!(value));
    }
}

fn port_number(url: &Url) -> Result<u16, String> {
    match url.port.parse::<u16>() {
        Ok(p) if p > 0 => Ok(p),
        _ => Err("missing or invalid port".into()),
    }
}

fn split_list(text: &str) -> Vec<String> {
    text.split(',').map(str::trim).filter(|s| !s.is_empty()).map(str::to_string).collect()
}

fn parse_bool(text: &str) -> bool {
    matches!(text.trim().to_ascii_lowercase().as_str(), "1" | "true")
}

/// One line of a link list.
pub fn parse_link(line: &str, names: &mut BTreeMap<String, u32>) -> Result<Parsed, String> {
    let (scheme, body) = line.split_once("://").ok_or("not a share link")?;
    let scheme = scheme.trim().to_ascii_lowercase();
    match scheme.as_str() {
        "ss" => shadowsocks(line, names),
        "ssr" => shadowsocksr(body, names),
        "vmess" => vmess(line, body, names),
        "vless" => vless(line, names),
        "trojan" => trojan(line, names),
        "hysteria" => hysteria(line, names),
        "hysteria2" | "hy2" | "hysteria2+realm" | "hy2+realm" => hysteria2(line, &scheme, names),
        "tuic" => tuic(line, names),
        "wireguard" | "wg" => wireguard(line, names),
        "anytls" => anytls(line, names),
        "socks" | "socks5" | "socks5h" | "http" | "https" => socks_http(line, &scheme, names),
        "mierus" => mieru(line, names),
        "mieru" => Err("mieru:// is mieru's binary client profile; use a mierus:// link".into()),
        other => Err(format!("unsupported link scheme {other}://")),
    }
}

fn one(map: Map<String, Value>) -> Parsed {
    Parsed { links: vec![Link::Mihomo(map)], warnings: Vec::new() }
}

/// A list of links (one per line). Unreadable lines become notices; no
/// readable link at all is an error.
pub fn import_list(text: &str, format: SourceFormat) -> Result<ImportResult, ImportError> {
    let mut names = BTreeMap::new();
    let mut nodes = Vec::new();
    let mut notices = Vec::new();
    let mut first_error: Option<ImportError> = None;
    for (index, raw) in text.lines().enumerate() {
        let line = raw.trim();
        if line.is_empty() || !line.contains("://") {
            continue;
        }
        let at = format!("line {}", index + 1);
        let parsed = match parse_link(line, &mut names) {
            Ok(p) => p,
            Err(e) => {
                notices.push(Notice::new(NoticeKind::SkippedLink, at.clone(), e.clone()));
                first_error.get_or_insert(ImportError::new(at, e));
                continue;
            }
        };
        for warning in parsed.warnings {
            notices.push(Notice::new(NoticeKind::IgnoredSettings, at.clone(), warning));
        }
        for link in parsed.links {
            match link {
                Link::Node(node) => nodes.push(node),
                Link::Mihomo(map) => match native::from_mihomo(map, &mut notices) {
                    Ok(node) => nodes.push(node),
                    Err(e) => {
                        notices.push(Notice::new(NoticeKind::SkippedLink, at.clone(), e.to_string()));
                        first_error.get_or_insert(ImportError::new(at.clone(), e.to_string()));
                    }
                },
            }
        }
    }
    if nodes.is_empty() {
        return Err(first_error.unwrap_or_else(|| ImportError::new("subscription", "no share links")));
    }
    let result = ImportResult { format, nodes, groups: Vec::new(), rules: None, notices };
    validate(&result)?;
    Ok(result)
}

/// TLS, REALITY, packet encoding and transport of VLESS/VMess/Trojan links
/// (mihomo's `handleVShareLink`). `sni_key` is `servername` (VLESS/VMess)
/// or `sni` (Trojan).
fn v_share(
    url: &Url,
    m: &mut Map<String, Value>,
    sni_key: &str,
    always_tls: bool,
    packet: bool,
    warnings: &mut Vec<String>,
) -> Result<(), String> {
    if url.host.is_empty() {
        return Err("missing host".into());
    }
    put(m, "server", json!(url.host));
    put(m, "port", json!(port_number(url)?));
    put(m, "udp", json!(true));
    let security = url.get("security").to_ascii_lowercase();
    let tls = always_tls || security.ends_with("tls") || security == "reality";
    if tls {
        if !always_tls {
            put(m, "tls", json!(true));
        }
        let fp = url.get("fp");
        put(m, "client-fingerprint", json!(if fp.is_empty() { "chrome" } else { fp }));
        let alpn = split_list(url.get("alpn"));
        if !alpn.is_empty() {
            put(m, "alpn", json!(alpn));
        }
        put_str(m, "fingerprint", url.get("pcs"));
        put_str(m, "name-cert-verify", url.get("vcn"));
        let ech = url.get("ech");
        if !ech.is_empty() {
            put(m, "ech-opts", json!({"enable": true, "config": ech}));
        }
        if xray::insecure(url) {
            put(m, "skip-cert-verify", json!(true));
        }
    }
    put_str(m, sni_key, url.get("sni"));
    let pbk = url.get("pbk");
    if !pbk.is_empty() {
        let mut reality = Map::new();
        put(&mut reality, "public-key", json!(pbk));
        put_str(&mut reality, "short-id", url.get("sid"));
        let pq = url.get("support-x25519mlkem768");
        if !pq.is_empty() {
            put(&mut reality, "support-x25519mlkem768", json!(parse_bool(pq)));
        }
        put(m, "reality-opts", Value::Object(reality));
        if !url.get("spx").is_empty() {
            warnings.push("spx (REALITY spiderX) is only used by Xray and was not kept".to_string());
        }
    }
    if packet {
        match url.get("packetEncoding") {
            "none" => {}
            "packet" => put(m, "packet-addr", json!(true)),
            _ => put(m, "xudp", json!(true)),
        }
    }
    let mut network = url.get("type").to_ascii_lowercase();
    if network.is_empty() {
        network = "tcp".to_string();
    }
    let fake = url.get("headerType").to_ascii_lowercase();
    if network == "tcp" && fake == "http" {
        network = "http".to_string();
    } else if network == "http" {
        network = "h2".to_string();
    }
    let host = url.get("host");
    let path = url.get("path");
    let mut final_network = network.clone();
    match network.as_str() {
        "tcp" => {}
        "http" => {
            let mut opts = Map::new();
            put(&mut opts, "path", json!([if path.is_empty() { "/" } else { path }]));
            if !host.is_empty() {
                put(&mut opts, "headers", json!({"Host": [host]}));
            }
            put_str(&mut opts, "method", url.get("method"));
            put(m, "http-opts", Value::Object(opts));
        }
        "h2" => {
            let mut opts = Map::new();
            put(&mut opts, "path", json!(if path.is_empty() { "/" } else { path }));
            if !host.is_empty() {
                put(&mut opts, "host", json!([host]));
            }
            put(m, "h2-opts", Value::Object(opts));
        }
        "ws" | "httpupgrade" => {
            let mut opts = Map::new();
            put_str(&mut opts, "path", path);
            if !host.is_empty() {
                put(&mut opts, "headers", json!({"Host": host}));
            }
            if network == "httpupgrade" {
                put(&mut opts, "v2ray-http-upgrade", json!(true));
            }
            let ed = url.get("ed");
            if !ed.is_empty() {
                let early: u64 = ed.parse().map_err(|_| format!("bad WebSocket max early data size {ed:?}"))?;
                if network == "ws" {
                    put(&mut opts, "max-early-data", json!(early));
                    put(&mut opts, "early-data-header-name", json!("Sec-WebSocket-Protocol"));
                } else {
                    put(&mut opts, "v2ray-http-upgrade-fast-open", json!(true));
                }
            }
            put_str(&mut opts, "early-data-header-name", url.get("eh"));
            final_network = "ws".to_string();
            put(m, "ws-opts", Value::Object(opts));
        }
        "grpc" => {
            put(m, "grpc-opts", json!({"grpc-service-name": url.get("serviceName")}));
            if url.get("mode") == "multi" {
                warnings.push("grpc mode=multi is only used by Xray; the node uses gun (single) mode".to_string());
            }
            if !url.get("authority").is_empty() {
                warnings.push("grpc authority is only used by Xray and was not kept".to_string());
            }
        }
        other => return Err(format!("transport {other} is not supported in share links")),
    }
    put(m, "network", json!(final_network));
    Ok(())
}

/// Links whose transport or encryption only Xray carries.
fn needs_xray(url: &Url, scheme: &str) -> bool {
    let network = url.get("type").to_ascii_lowercase();
    let encryption = url.get("encryption");
    network == "xhttp"
        || network == "splithttp"
        || (scheme == "vless" && !encryption.is_empty() && encryption != "none")
        || (url.get("security").eq_ignore_ascii_case("reality") && !url.get("pqv").is_empty())
}

fn xray_node(scheme: &str, url: &Url, names: &mut BTreeMap<String, u32>) -> Result<Parsed, String> {
    let link = xray::from_link(scheme, url)?;
    let port: u16 = url.port.parse().unwrap_or(0);
    let tag = unique(names, &base_name(url));
    let node = Node {
        tag,
        protocol: scheme.to_string(),
        server: url.host.clone(),
        port,
        transport: link.transport,
        security: link.security,
        detour: None,
        spec: Spec::Xray(link.outbound),
    };
    Ok(Parsed { links: vec![Link::Node(node)], warnings: Vec::new() })
}

fn vless(line: &str, names: &mut BTreeMap<String, u32>) -> Result<Parsed, String> {
    let url = parse_url(line)?;
    if needs_xray(&url, "vless") {
        return xray_node("vless", &url, names);
    }
    let mut m = Map::new();
    put(&mut m, "type", json!("vless"));
    if url.username.is_empty() {
        return Err("missing uuid".into());
    }
    put(&mut m, "uuid", json!(url.username));
    let mut warnings = Vec::new();
    v_share(&url, &mut m, "servername", false, true, &mut warnings)?;
    let flow = url.get("flow");
    if !flow.is_empty() {
        put(&mut m, "flow", json!(flow.to_ascii_lowercase()));
    }
    put_str(&mut m, "encryption", url.get("encryption"));
    put(&mut m, "name", json!(unique(names, &base_name(&url))));
    Ok(Parsed { links: vec![Link::Mihomo(m)], warnings })
}

fn trojan(line: &str, names: &mut BTreeMap<String, u32>) -> Result<Parsed, String> {
    let url = parse_url(line)?;
    if needs_xray(&url, "trojan") {
        return xray_node("trojan", &url, names);
    }
    let mut m = Map::new();
    put(&mut m, "type", json!("trojan"));
    if url.username.is_empty() {
        return Err("missing password".into());
    }
    put(&mut m, "password", json!(url.username));
    let mut warnings = Vec::new();
    v_share(&url, &mut m, "sni", true, false, &mut warnings)?;
    put(&mut m, "name", json!(unique(names, &base_name(&url))));
    Ok(Parsed { links: vec![Link::Mihomo(m)], warnings })
}

fn vmess(line: &str, body: &str, names: &mut BTreeMap<String, u32>) -> Result<Parsed, String> {
    let decoded = base64_text(body.trim()).and_then(|text| serde_json::from_str::<Value>(&text).ok());
    let values = match decoded {
        Some(Value::Object(v)) => v,
        _ => {
            // Xray's VMessAEAD URL form.
            let url = parse_url(line)?;
            if needs_xray(&url, "vmess") {
                return xray_node("vmess", &url, names);
            }
            let mut m = Map::new();
            put(&mut m, "type", json!("vmess"));
            if url.username.is_empty() {
                return Err("missing uuid".into());
            }
            put(&mut m, "uuid", json!(url.username));
            let mut warnings = Vec::new();
            v_share(&url, &mut m, "servername", false, true, &mut warnings)?;
            put(&mut m, "alterId", json!(0));
            let cipher = url.get("encryption");
            put(&mut m, "cipher", json!(if cipher.is_empty() { "auto" } else { cipher }));
            put(&mut m, "name", json!(unique(names, &base_name(&url))));
            return Ok(Parsed { links: vec![Link::Mihomo(m)], warnings });
        }
    };
    // v2rayN's Base64 JSON form.
    let text = |key: &str| -> String {
        match values.get(key) {
            Some(Value::String(s)) => s.clone(),
            Some(Value::Number(n)) => n.to_string(),
            _ => String::new(),
        }
    };
    let server = text("add");
    if server.is_empty() {
        return Err("missing add (server)".into());
    }
    let port: u16 = match text("port").trim().parse() {
        Ok(p) if p > 0 => p,
        _ => return Err("missing or invalid port".into()),
    };
    let id = text("id");
    if id.is_empty() {
        return Err("missing id".into());
    }
    let mut m = Map::new();
    put(&mut m, "type", json!("vmess"));
    put(&mut m, "server", json!(server));
    put(&mut m, "port", json!(port));
    put(&mut m, "uuid", json!(id));
    let aid: u64 = text("aid").trim().parse().unwrap_or(0);
    put(&mut m, "alterId", json!(aid));
    put(&mut m, "udp", json!(true));
    put(&mut m, "xudp", json!(true));
    let cipher = text("scy");
    put(&mut m, "cipher", json!(if cipher.is_empty() { "auto".to_string() } else { cipher }));
    put_str(&mut m, "servername", &text("sni"));
    let mut network = text("net").to_ascii_lowercase();
    if network.is_empty() {
        network = "tcp".to_string();
    }
    if text("type") == "http" {
        network = "http".to_string();
    } else if network == "http" {
        network = "h2".to_string();
    }
    if network == "xhttp" || network == "splithttp" {
        return Err("VMess over XHTTP in the Base64 JSON form is not supported; use the URL form".into());
    }
    let tls = text("tls").to_ascii_lowercase();
    if tls.ends_with("tls") {
        put(&mut m, "tls", json!(true));
        let alpn = split_list(&text("alpn"));
        if !alpn.is_empty() {
            put(&mut m, "alpn", json!(alpn));
        }
        put_str(&mut m, "client-fingerprint", &text("fp"));
        if parse_bool(&text("allowInsecure")) || parse_bool(&text("insecure")) {
            put(&mut m, "skip-cert-verify", json!(true));
        }
    }
    let host = text("host");
    let path = text("path");
    let mut final_network = network.clone();
    match network.as_str() {
        "tcp" => {}
        "http" => {
            let mut opts = Map::new();
            put(&mut opts, "path", json!([if path.is_empty() { "/".to_string() } else { path.clone() }]));
            if !host.is_empty() {
                put(&mut opts, "headers", json!({"Host": [host]}));
            }
            put(&mut m, "http-opts", Value::Object(opts));
        }
        "h2" => {
            let mut opts = Map::new();
            put(&mut opts, "path", json!(if path.is_empty() { "/".to_string() } else { path.clone() }));
            if !host.is_empty() {
                put(&mut opts, "host", json!([host]));
            }
            put(&mut m, "h2-opts", Value::Object(opts));
        }
        "ws" | "httpupgrade" => {
            let mut opts = Map::new();
            let mut ws_path = if path.is_empty() { "/".to_string() } else { path.clone() };
            // `?ed=N` alone in the path is the early data size (v2rayN).
            if let Some((p, q)) = path.split_once('?') {
                if let Some(ed) = q.strip_prefix("ed=") {
                    if let Ok(early) = ed.parse::<u64>() {
                        if network == "ws" {
                            put(&mut opts, "max-early-data", json!(early));
                            put(&mut opts, "early-data-header-name", json!("Sec-WebSocket-Protocol"));
                        } else {
                            put(&mut opts, "v2ray-http-upgrade-fast-open", json!(true));
                        }
                        ws_path = p.to_string();
                    }
                }
            }
            put(&mut opts, "path", json!(ws_path));
            if !host.is_empty() {
                put(&mut opts, "headers", json!({"Host": host}));
            }
            if network == "httpupgrade" {
                put(&mut opts, "v2ray-http-upgrade", json!(true));
            }
            final_network = "ws".to_string();
            put(&mut m, "ws-opts", Value::Object(opts));
        }
        "grpc" => {
            put(&mut m, "grpc-opts", json!({"grpc-service-name": path}));
        }
        other => return Err(format!("transport {other} is not supported in share links")),
    }
    put(&mut m, "network", json!(final_network));
    let ps = text("ps");
    let name = if ps.trim().is_empty() { format!("{}:{}", m["server"].as_str().unwrap_or(""), port) } else { ps };
    put(&mut m, "name", json!(unique(names, &name)));
    Ok(one(m))
}

/// SIP002 (`method:password` in Base64 or in the clear) and the legacy form
/// (the whole `method:password@host:port` in Base64).
fn shadowsocks(line: &str, names: &mut BTreeMap<String, u32>) -> Result<Parsed, String> {
    let mut url = parse_url(line)?;
    let fragment = url.fragment.clone();
    if url.port.is_empty() {
        let decoded = base64_text(&url.host).ok_or("neither host:port nor a Base64 legacy link")?;
        let query = url.query.clone();
        url = parse_url(&format!("ss://{decoded}"))?;
        url.query = query;
        url.fragment = fragment;
    }
    let (cipher, password) = match &url.password {
        Some(p) => (url.username.clone(), p.clone()),
        None => {
            let decoded = base64_text(&url.username).ok_or("user info is neither method:password nor its Base64")?;
            match decoded.split_once(':') {
                Some((c, p)) => (c.to_string(), p.to_string()),
                None => return Err("user info has no method:password".into()),
            }
        }
    };
    if cipher.is_empty() {
        return Err("missing method".into());
    }
    if url.host.is_empty() {
        return Err("missing host".into());
    }
    let mut m = Map::new();
    put(&mut m, "type", json!("ss"));
    put(&mut m, "server", json!(url.host));
    put(&mut m, "port", json!(port_number(&url)?));
    put(&mut m, "cipher", json!(cipher));
    put(&mut m, "password", json!(password));
    put(&mut m, "udp", json!(true));
    if url.get("udp-over-tcp") == "true" || url.get("uot") == "1" {
        put(&mut m, "udp-over-tcp", json!(true));
    }
    let plugin = url.get("plugin");
    if !plugin.is_empty() {
        let mut parts = plugin.split(';');
        let plugin_name = parts.next().unwrap_or("").trim().to_string();
        let mut opts: BTreeMap<String, String> = BTreeMap::new();
        for part in parts {
            let part = part.trim();
            if part.is_empty() {
                continue;
            }
            match part.split_once('=') {
                Some((k, v)) => opts.insert(k.to_string(), v.to_string()),
                None => opts.insert(part.to_string(), String::new()),
            };
        }
        let opt = |k: &str| opts.get(k).cloned().unwrap_or_default();
        if plugin_name.contains("obfs") {
            put(&mut m, "plugin", json!("obfs"));
            put(&mut m, "plugin-opts", json!({"mode": opt("obfs"), "host": opt("obfs-host")}));
        } else if plugin_name.contains("v2ray-plugin") {
            let mode = if opts.contains_key("mode") { opt("mode") } else { opt("obfs") };
            let host = if opts.contains_key("host") { opt("host") } else { opt("obfs-host") };
            put(&mut m, "plugin", json!("v2ray-plugin"));
            put(&mut m, "plugin-opts", json!({"mode": mode, "host": host, "path": opt("path"), "tls": plugin.contains("tls")}));
        } else {
            return Err(format!("Shadowsocks plugin {plugin_name:?} is not supported in share links"));
        }
    }
    put(&mut m, "name", json!(unique(names, &base_name(&url))));
    Ok(one(m))
}

/// `ssr://` + Base64 of `host:port:protocol:method:obfs:base64pass/?params`.
fn shadowsocksr(body: &str, names: &mut BTreeMap<String, u32>) -> Result<Parsed, String> {
    let decoded = base64_text(body.trim()).ok_or("ssr link is not Base64")?;
    let (before, after) = decoded.split_once("/?").ok_or("ssr link has no /? part")?;
    let fields: Vec<&str> = before.split(':').collect();
    if fields.len() != 6 {
        return Err("ssr link does not have six fields".into());
    }
    let b64 = |s: &str| base64_text(s).unwrap_or_default();
    let mut params: BTreeMap<String, String> = BTreeMap::new();
    for pair in after.split('&') {
        if let Some((k, v)) = pair.split_once('=') {
            let v = percent_decode(v, false).unwrap_or_else(|_| v.to_string());
            params.insert(k.to_string(), b64(&v));
        }
    }
    let param = |k: &str| params.get(k).cloned().unwrap_or_default();
    let port: u16 = fields[1].parse().map_err(|_| "invalid port".to_string())?;
    let mut m = Map::new();
    put(&mut m, "type", json!("ssr"));
    put(&mut m, "server", json!(fields[0]));
    put(&mut m, "port", json!(port));
    put(&mut m, "protocol", json!(fields[2]));
    put(&mut m, "cipher", json!(fields[3]));
    put(&mut m, "obfs", json!(fields[4]));
    put(&mut m, "password", json!(b64(fields[5])));
    put(&mut m, "udp", json!(true));
    put_str(&mut m, "obfs-param", &param("obfsparam"));
    put_str(&mut m, "protocol-param", &param("protoparam"));
    let remarks = param("remarks");
    let name = if remarks.is_empty() { format!("{}:{}", fields[0], port) } else { remarks };
    put(&mut m, "name", json!(unique(names, &name)));
    Ok(one(m))
}

fn hysteria(line: &str, names: &mut BTreeMap<String, u32>) -> Result<Parsed, String> {
    let url = parse_url(line)?;
    if url.host.is_empty() {
        return Err("missing host".into());
    }
    let mut m = Map::new();
    put(&mut m, "type", json!("hysteria"));
    put(&mut m, "server", json!(url.host));
    put(&mut m, "port", json!(port_number(&url)?));
    put_str(&mut m, "sni", url.get("peer"));
    put_str(&mut m, "obfs", url.get("obfs"));
    let alpn = split_list(url.get("alpn"));
    if !alpn.is_empty() {
        put(&mut m, "alpn", json!(alpn));
    }
    put_str(&mut m, "auth-str", url.get("auth"));
    put_str(&mut m, "protocol", url.get("protocol"));
    let up = if url.has("up") { url.get("up") } else { url.get("upmbps") };
    let down = if url.has("down") { url.get("down") } else { url.get("downmbps") };
    put_str(&mut m, "up", up);
    put_str(&mut m, "down", down);
    if parse_bool(url.get("insecure")) {
        put(&mut m, "skip-cert-verify", json!(true));
    }
    put(&mut m, "name", json!(unique(names, &base_name(&url))));
    Ok(one(m))
}

/// mihomo's `splitHysteria2Ports`: `host:443,1000-2000` → the first port for
/// the URL and the whole list as `ports`.
fn split_hysteria2_ports(line: &str) -> (String, String) {
    let i = match line.find("://") {
        Some(i) => i,
        None => return (line.to_string(), String::new()),
    };
    let (head, rest) = (&line[..i + 3], &line[i + 3..]);
    let (auth, tail) = match rest.find(['/', '?', '#']) {
        Some(j) => (&rest[..j], &rest[j..]),
        None => (rest, ""),
    };
    let (userinfo, hostport) = match auth.rfind('@') {
        Some(at) => (&auth[..at + 1], &auth[at + 1..]),
        None => ("", auth),
    };
    if hostport.contains(']') {
        return (line.to_string(), String::new());
    }
    let colon = match hostport.rfind(':') {
        Some(c) => c,
        None => return (line.to_string(), String::new()),
    };
    let (host, ports) = (&hostport[..colon], &hostport[colon + 1..]);
    if !ports.contains([',', '-']) {
        return (line.to_string(), String::new());
    }
    let first = match ports.find([',', '-']) {
        Some(k) => &ports[..k],
        None => ports,
    };
    if first.is_empty() {
        return (line.to_string(), String::new());
    }
    (format!("{head}{userinfo}{host}:{first}{tail}"), ports.to_string())
}

fn hysteria2(line: &str, scheme: &str, names: &mut BTreeMap<String, u32>) -> Result<Parsed, String> {
    let realm = scheme.ends_with("+realm");
    let (hop_line, ports) = split_hysteria2_ports(line);
    let url = parse_url(&hop_line)?;
    if url.host.is_empty() {
        return Err("missing host".into());
    }
    let mut m = Map::new();
    put(&mut m, "type", json!("hysteria2"));
    put(&mut m, "server", json!(url.host));
    let port: u16 = if url.port.is_empty() { 443 } else { port_number(&url)? };
    put(&mut m, "port", json!(port));
    put_str(&mut m, "ports", &ports);
    put_str(&mut m, "obfs", url.get("obfs"));
    put_str(&mut m, "obfs-password", url.get("obfs-password"));
    put_str(&mut m, "sni", url.get("sni"));
    if parse_bool(url.get("insecure")) {
        put(&mut m, "skip-cert-verify", json!(true));
    }
    let alpn = split_list(url.get("alpn"));
    if !alpn.is_empty() {
        put(&mut m, "alpn", json!(alpn));
    }
    put_str(&mut m, "fingerprint", url.get("pinSHA256"));
    put_str(&mut m, "down", url.get("down"));
    put_str(&mut m, "up", url.get("up"));
    let user = match &url.password {
        Some(p) => format!("{}:{}", url.username, p),
        None => url.username.clone(),
    };
    if realm {
        let mut opts = Map::new();
        put(&mut opts, "enable", json!(true));
        put(&mut opts, "server-url", json!(format!("https://{}:{}", url.host, port)));
        put_str(&mut opts, "token", &user);
        put_str(&mut opts, "realm-id", url.path.trim_start_matches('/'));
        let stun: Vec<&str> = url.all("stun");
        if !stun.is_empty() {
            put(&mut opts, "stun-servers", json!(stun));
        }
        put(&mut m, "realm-opts", Value::Object(opts));
        put_str(&mut m, "password", url.get("auth"));
    } else {
        put_str(&mut m, "password", &user);
    }
    put(&mut m, "name", json!(unique(names, &base_name(&url))));
    Ok(one(m))
}

fn tuic(line: &str, names: &mut BTreeMap<String, u32>) -> Result<Parsed, String> {
    let url = parse_url(line)?;
    if url.host.is_empty() {
        return Err("missing host".into());
    }
    let mut m = Map::new();
    put(&mut m, "type", json!("tuic"));
    put(&mut m, "server", json!(url.host));
    put(&mut m, "port", json!(port_number(&url)?));
    put(&mut m, "udp", json!(true));
    match &url.password {
        Some(p) => {
            put(&mut m, "uuid", json!(url.username));
            put(&mut m, "password", json!(p));
        }
        None => put(&mut m, "token", json!(url.username)),
    }
    put_str(&mut m, "congestion-controller", url.get("congestion_control"));
    let alpn = split_list(url.get("alpn"));
    if !alpn.is_empty() {
        put(&mut m, "alpn", json!(alpn));
    }
    put_str(&mut m, "sni", url.get("sni"));
    if url.get("disable_sni") == "1" {
        put(&mut m, "disable-sni", json!(true));
    }
    put_str(&mut m, "udp-relay-mode", url.get("udp_relay_mode"));
    if parse_bool(url.get("allow_insecure")) || parse_bool(url.get("insecure")) {
        put(&mut m, "skip-cert-verify", json!(true));
    }
    put(&mut m, "name", json!(unique(names, &base_name(&url))));
    Ok(one(m))
}

/// `wireguard://<private key>@host:port?publickey=…&address=…&mtu=…&reserved=…`
/// (v2rayN / Xray form).
fn wireguard(line: &str, names: &mut BTreeMap<String, u32>) -> Result<Parsed, String> {
    let url = parse_url(line)?;
    if url.host.is_empty() {
        return Err("missing host".into());
    }
    if url.username.is_empty() {
        return Err("missing private key".into());
    }
    let public_key = if url.has("publickey") { url.get("publickey") } else { url.get("publicKey") };
    if public_key.is_empty() {
        return Err("missing publickey".into());
    }
    let mut m = Map::new();
    put(&mut m, "type", json!("wireguard"));
    put(&mut m, "server", json!(url.host));
    put(&mut m, "port", json!(port_number(&url)?));
    put(&mut m, "private-key", json!(url.username));
    put(&mut m, "public-key", json!(public_key));
    put(&mut m, "udp", json!(true));
    let address = if url.has("address") { url.get("address") } else { url.get("ip") };
    for addr in split_list(address) {
        if addr.contains(':') {
            if !m.contains_key("ipv6") {
                put(&mut m, "ipv6", json!(addr));
            } else {
                return Err("more than one IPv6 address".into());
            }
        } else if !m.contains_key("ip") {
            put(&mut m, "ip", json!(addr));
        } else {
            return Err("more than one IPv4 address".into());
        }
    }
    if !m.contains_key("ip") && !m.contains_key("ipv6") {
        return Err("missing address".into());
    }
    let mtu = url.get("mtu");
    if !mtu.is_empty() {
        put(&mut m, "mtu", json!(mtu.parse::<u64>().map_err(|_| format!("invalid mtu {mtu:?}"))?));
    }
    let psk = if url.has("presharedkey") { url.get("presharedkey") } else { url.get("preSharedKey") };
    put_str(&mut m, "pre-shared-key", psk);
    let reserved = url.get("reserved");
    if !reserved.is_empty() {
        let mut bytes = Vec::new();
        for part in split_list(reserved) {
            bytes.push(part.parse::<u8>().map_err(|_| format!("invalid reserved {reserved:?}"))?);
        }
        put(&mut m, "reserved", json!(bytes));
    }
    let allowed = split_list(if url.has("allowedips") { url.get("allowedips") } else { url.get("allowedIPs") });
    if !allowed.is_empty() {
        put(&mut m, "allowed-ips", json!(allowed));
    }
    let keepalive = url.get("keepalive");
    if !keepalive.is_empty() {
        put(&mut m, "persistent-keepalive", json!(keepalive.parse::<u64>().map_err(|_| format!("invalid keepalive {keepalive:?}"))?));
    }
    put(&mut m, "name", json!(unique(names, &base_name(&url))));
    Ok(one(m))
}

fn anytls(line: &str, names: &mut BTreeMap<String, u32>) -> Result<Parsed, String> {
    let url = parse_url(line)?;
    if url.host.is_empty() {
        return Err("missing host".into());
    }
    let password = url.password.clone().unwrap_or_else(|| url.username.clone());
    let mut m = Map::new();
    put(&mut m, "type", json!("anytls"));
    put(&mut m, "server", json!(url.host));
    put(&mut m, "port", json!(port_number(&url)?));
    put(&mut m, "password", json!(password));
    put_str(&mut m, "sni", url.get("sni"));
    put_str(&mut m, "fingerprint", url.get("hpkp"));
    if url.get("insecure") == "1" {
        put(&mut m, "skip-cert-verify", json!(true));
    }
    put(&mut m, "udp", json!(true));
    put(&mut m, "name", json!(unique(names, &base_name(&url))));
    Ok(one(m))
}

fn socks_http(line: &str, scheme: &str, names: &mut BTreeMap<String, u32>) -> Result<Parsed, String> {
    let url = parse_url(line)?;
    if url.host.is_empty() {
        return Err("missing host".into());
    }
    let port = port_number(&url)?;
    // v2rayN writes Base64(user:pass); plain user:pass is read as written.
    let (username, password) = match url.userinfo_raw.as_deref().and_then(base64_text) {
        Some(decoded) if url.password.is_none() => match decoded.split_once(':') {
            Some((u, p)) => (u.to_string(), p.to_string()),
            None => (decoded, String::new()),
        },
        _ => (url.username.clone(), url.password.clone().unwrap_or_default()),
    };
    let mut m = Map::new();
    let kind = if scheme.starts_with("socks") { "socks5" } else { "http" };
    put(&mut m, "type", json!(kind));
    put(&mut m, "server", json!(url.host));
    put(&mut m, "port", json!(port));
    put_str(&mut m, "username", &username);
    put_str(&mut m, "password", &password);
    if kind == "socks5" {
        put(&mut m, "udp", json!(true));
    }
    if scheme == "https" {
        put(&mut m, "tls", json!(true));
    }
    put(&mut m, "name", json!(unique(names, &base_name(&url))));
    Ok(one(m))
}

/// `mierus://user:pass@host?profile=…&port=…&protocol=…` (one node per
/// port/protocol pair, as mihomo).
fn mieru(line: &str, names: &mut BTreeMap<String, u32>) -> Result<Parsed, String> {
    let url = parse_url(line)?;
    if url.host.is_empty() {
        return Err("missing host".into());
    }
    let ports = url.all("port");
    let protocols = url.all("protocol");
    if ports.is_empty() || ports.len() != protocols.len() {
        return Err("port and protocol parameters do not pair up".into());
    }
    let mut base = url.fragment.clone();
    if base.is_empty() {
        base = url.get("profile").to_string();
    }
    if base.is_empty() {
        base = url.host.clone();
    }
    let mut parsed = Parsed::default();
    for (port, protocol) in ports.iter().zip(protocols.iter()) {
        let mut m = Map::new();
        put(&mut m, "type", json!("mieru"));
        put(&mut m, "server", json!(url.host));
        put(&mut m, "transport", json!(protocol));
        put(&mut m, "udp", json!(true));
        put(&mut m, "username", json!(url.username));
        put(&mut m, "password", json!(url.password.clone().unwrap_or_default()));
        if port.contains('-') {
            put(&mut m, "port-range", json!(port));
        } else {
            let p: u16 = port.parse().map_err(|_| format!("invalid port {port:?}"))?;
            put(&mut m, "port", json!(p));
        }
        put_str(&mut m, "multiplexing", url.get("multiplexing"));
        put_str(&mut m, "handshake-mode", url.get("handshake-mode"));
        put_str(&mut m, "traffic-pattern", url.get("traffic-pattern"));
        put(&mut m, "name", json!(unique(names, &format!("{base}:{port}/{protocol}"))));
        parsed.links.push(Link::Mihomo(m));
    }
    Ok(parsed)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn unique_names_follow_mihomo() {
        let mut names = BTreeMap::new();
        assert_eq!(unique(&mut names, "a"), "a");
        assert_eq!(unique(&mut names, "a"), "a-01");
        assert_eq!(unique(&mut names, "a"), "a-02");
        assert_eq!(unique(&mut names, "b"), "b");
    }

    #[test]
    fn hysteria2_port_hopping_split() {
        let (line, ports) = split_hysteria2_ports("hy2://pw@example.com:443,1000-2000/?sni=a#n");
        assert_eq!(line, "hy2://pw@example.com:443/?sni=a#n");
        assert_eq!(ports, "443,1000-2000");
        let (line, ports) = split_hysteria2_ports("hy2://pw@example.com:443?sni=a");
        assert_eq!(line, "hy2://pw@example.com:443?sni=a");
        assert_eq!(ports, "");
    }
}
