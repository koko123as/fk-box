//! Share links that only Xray runs without loss (VLESS/VMess/Trojan over
//! XHTTP, VLESS encryption, REALITY with ML-DSA-65 verification) into an
//! Xray outbound object for the `xray` plugin. Field names follow Xray-core
//! 26.11.9 `infra/conf` (simplified outbound settings `address`/`port`/`id`,
//! `streamSettings`).
//!
//! Xray 26 refuses `allowInsecure` (`transport_security.go`: removed in
//! favour of `pinnedPeerCertSha256` / `verifyPeerCertByName`), so a link
//! asking for an unverified certificate is an error here.

use serde_json::{json, Map, Value};

use super::text::Url;
use super::Security;

/// Whether the link asks to skip certificate verification.
pub fn insecure(url: &Url) -> bool {
    ["allowInsecure", "insecure", "allow_insecure"].iter().any(|k| matches!(url.get(k).trim().to_ascii_lowercase().as_str(), "1" | "true"))
}

pub struct XrayLink {
    pub outbound: Map<String, Value>,
    pub transport: String,
    pub security: Security,
}

/// `scheme` is `vless`, `vmess` or `trojan` (Xray share link standard,
/// XTLS/Xray-core discussion 716).
pub fn from_link(scheme: &str, url: &Url) -> Result<XrayLink, String> {
    if url.host.is_empty() {
        return Err("missing host".into());
    }
    let port: u16 = url.port.parse().map_err(|_| "missing or invalid port".to_string())?;
    let mut settings = Map::new();
    settings.insert("address".into(), json!(url.host));
    settings.insert("port".into(), json!(port));
    match scheme {
        "vless" => {
            if url.username.is_empty() {
                return Err("missing id".into());
            }
            settings.insert("id".into(), json!(url.username));
            let encryption = url.get("encryption");
            settings.insert("encryption".into(), json!(if encryption.is_empty() { "none" } else { encryption }));
            let flow = url.get("flow");
            if !flow.is_empty() {
                settings.insert("flow".into(), json!(flow.to_ascii_lowercase()));
            }
        }
        "vmess" => {
            if url.username.is_empty() {
                return Err("missing id".into());
            }
            settings.insert("id".into(), json!(url.username));
            let security = url.get("encryption");
            settings.insert("security".into(), json!(if security.is_empty() { "auto" } else { security }));
        }
        "trojan" => {
            if url.username.is_empty() {
                return Err("missing password".into());
            }
            settings.insert("password".into(), json!(url.username));
        }
        other => return Err(format!("{other} has no Xray mapping here")),
    }
    let mut stream = Map::new();
    let mut network = url.get("type").to_ascii_lowercase();
    if network.is_empty() {
        network = "tcp".to_string();
    }
    if network == "splithttp" {
        network = "xhttp".to_string();
    }
    let host = url.get("host");
    let path = url.get("path");
    match network.as_str() {
        "tcp" | "raw" => {
            let header = url.get("headerType").to_ascii_lowercase();
            if !header.is_empty() && header != "none" {
                return Err(format!("raw transport header {header}"));
            }
            network = "raw".to_string();
        }
        "xhttp" => {
            let mut x = Map::new();
            if !path.is_empty() {
                x.insert("path".into(), json!(path));
            }
            if !host.is_empty() {
                x.insert("host".into(), json!(host));
            }
            let mode = url.get("mode");
            if !mode.is_empty() {
                x.insert("mode".into(), json!(mode));
            }
            let extra = url.get("extra");
            if !extra.is_empty() {
                // Kept whole: Xray reads `extra` itself (SplitHTTPConfig.Extra).
                let value: Value = serde_json::from_str(extra).map_err(|e| format!("extra is not JSON: {e}"))?;
                if !value.is_object() {
                    return Err("extra is not a JSON object".into());
                }
                x.insert("extra".into(), value);
            }
            stream.insert("xhttpSettings".into(), Value::Object(x));
        }
        "ws" => {
            let mut x = Map::new();
            if !path.is_empty() {
                x.insert("path".into(), json!(path));
            }
            if !host.is_empty() {
                x.insert("host".into(), json!(host));
            }
            stream.insert("wsSettings".into(), Value::Object(x));
        }
        "httpupgrade" => {
            let mut x = Map::new();
            if !path.is_empty() {
                x.insert("path".into(), json!(path));
            }
            if !host.is_empty() {
                x.insert("host".into(), json!(host));
            }
            stream.insert("httpupgradeSettings".into(), Value::Object(x));
        }
        "grpc" => {
            let mut x = Map::new();
            let service = url.get("serviceName");
            if !service.is_empty() {
                x.insert("serviceName".into(), json!(service));
            }
            let authority = url.get("authority");
            if !authority.is_empty() {
                x.insert("authority".into(), json!(authority));
            }
            if url.get("mode") == "multi" {
                x.insert("multiMode".into(), json!(true));
            }
            stream.insert("grpcSettings".into(), Value::Object(x));
        }
        other => return Err(format!("transport {other}")),
    }
    stream.insert("network".into(), json!(network));
    let mut security_kind = url.get("security").to_ascii_lowercase();
    if scheme == "trojan" && security_kind.is_empty() {
        security_kind = "tls".to_string();
    }
    let sni = url.get("sni");
    let fp = url.get("fp");
    let alpn: Vec<&str> = url.get("alpn").split(',').map(str::trim).filter(|s| !s.is_empty()).collect();
    let security = match security_kind.as_str() {
        "" | "none" => {
            stream.insert("security".into(), json!("none"));
            Security::None
        }
        "tls" => {
            if insecure(url) {
                return Err("the link turns certificate verification off (allowInsecure/insecure), which Xray 26 removed; use pcs (pinnedPeerCertSha256) or vcn (verifyPeerCertByName) instead".into());
            }
            let mut t = Map::new();
            if !sni.is_empty() {
                t.insert("serverName".into(), json!(sni));
            }
            if !alpn.is_empty() {
                t.insert("alpn".into(), json!(alpn));
            }
            if !fp.is_empty() {
                t.insert("fingerprint".into(), json!(fp));
            }
            for (param, field) in [("pcs", "pinnedPeerCertSha256"), ("vcn", "verifyPeerCertByName"), ("ech", "echConfigList")] {
                let v = url.get(param);
                if !v.is_empty() {
                    t.insert(field.into(), json!(v));
                }
            }
            stream.insert("security".into(), json!("tls"));
            stream.insert("tlsSettings".into(), Value::Object(t));
            Security::Tls
        }
        "reality" => {
            if insecure(url) {
                return Err("allowInsecure/insecure with REALITY".into());
            }
            let public_key = url.get("pbk");
            if public_key.is_empty() {
                return Err("REALITY without pbk".into());
            }
            let mut t = Map::new();
            if !sni.is_empty() {
                t.insert("serverName".into(), json!(sni));
            }
            t.insert("fingerprint".into(), json!(if fp.is_empty() { "chrome" } else { fp }));
            t.insert("publicKey".into(), json!(public_key));
            for (param, field) in [("sid", "shortId"), ("spx", "spiderX"), ("pqv", "mldsa65Verify")] {
                let v = url.get(param);
                if !v.is_empty() {
                    t.insert(field.into(), json!(v));
                }
            }
            stream.insert("security".into(), json!("reality"));
            stream.insert("realitySettings".into(), Value::Object(t));
            Security::Reality
        }
        other => return Err(format!("security {other}")),
    };
    let mut outbound = Map::new();
    outbound.insert("protocol".into(), json!(scheme));
    outbound.insert("settings".into(), Value::Object(settings));
    outbound.insert("streamSettings".into(), Value::Object(stream));
    Ok(XrayLink { outbound, transport: if network == "raw" { "tcp".to_string() } else { network }, security })
}
