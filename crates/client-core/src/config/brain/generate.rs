//! The minimal sing-box configuration of the single-kernel design
//! (docs/architecture/brain-config.md): one TUN inbound, `route.final` =
//! `fkbrain` with no route rules, one DNS server (`fkdns`) with no DNS
//! rules, the node outbounds/endpoints, and `direct`/`block`. Groups and
//! rules stay in the brain; no `selector`/`urltest` is written.

use serde_json::{json, Map, Value};

use super::{validate, ImportResult, Spec};

pub const TAG_BRAIN: &str = "fkbrain";
pub const TAG_DIRECT: &str = "fk-direct";
pub const TAG_BLOCK: &str = "fk-block";
pub const TAG_TUN: &str = "fk-tun";
pub const TAG_DNS: &str = "fkdns";

/// The TUN inbound. Defaults are the Android service's today
/// (apps/android/core/core_profile.go, crates/mobile-ffi vpn_routing):
/// 172.19.0.1/30 and fdfe:dcba:9876::1/126, `auto_route`, sing-box's
/// default MTU and stack.
#[derive(Debug, Clone, PartialEq)]
pub struct TunOptions {
    pub address: Vec<String>,
    pub mtu: Option<u32>,
    pub auto_route: bool,
    pub strict_route: bool,
    pub stack: Option<String>,
}

impl Default for TunOptions {
    fn default() -> TunOptions {
        TunOptions {
            address: vec!["172.19.0.1/30".to_string(), "fdfe:dcba:9876::1/126".to_string()],
            mtu: None,
            auto_route: true,
            strict_route: false,
            stack: None,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Default)]
pub struct GenerateOptions {
    pub tun: TunOptions,
    /// sing-box `log.level`; no `log` block when `None`.
    pub log_level: Option<String>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Generated {
    /// The sing-box configuration.
    pub config: Value,
    /// fkbrain's outbound table: `decide` returns an index into it
    /// (the nodes in import order, then `fk-direct`, `fk-block`).
    pub outbounds: Vec<String>,
}

/// The sing-box object of one node (outbound or endpoint) and whether it is an endpoint.
pub fn node_object(tag: &str, detour: Option<&str>, spec: &Spec) -> (Map<String, Value>, bool) {
    let mut object = Map::new();
    let endpoint = matches!(spec, Spec::NativeEndpoint(_));
    match spec {
        Spec::Native(o) | Spec::NativeEndpoint(o) => {
            object.insert("type".into(), o.get("type").cloned().unwrap_or(Value::Null));
            object.insert("tag".into(), json!(tag));
            if let Some(d) = detour {
                object.insert("detour".into(), json!(d));
            }
            for (k, v) in o {
                if k != "type" && k != "tag" && k != "detour" {
                    object.insert(k.clone(), v.clone());
                }
            }
        }
        Spec::Mihomo(proxy) => {
            object.insert("type".into(), json!("mihomo"));
            object.insert("tag".into(), json!(tag));
            if let Some(d) = detour {
                object.insert("detour".into(), json!(d));
            }
            object.insert("proxy".into(), Value::Object(proxy.clone()));
        }
        Spec::Xray(outbound) => {
            object.insert("type".into(), json!("xray"));
            object.insert("tag".into(), json!(tag));
            if let Some(d) = detour {
                object.insert("detour".into(), json!(d));
            }
            object.insert("outbound".into(), Value::Object(outbound.clone()));
        }
    }
    (object, endpoint)
}

pub fn generate(result: &ImportResult, options: &GenerateOptions) -> Result<Generated, String> {
    validate(result).map_err(|e| e.to_string())?;
    let mut table = Vec::new();
    let mut nodes = Vec::new();
    let mut endpoints = Vec::new();
    for node in &result.nodes {
        let (object, endpoint) = node_object(&node.tag, node.detour.as_deref(), &node.spec);
        if object.get("type").and_then(Value::as_str).unwrap_or("").is_empty() {
            return Err(format!("node {:?} has no type", node.tag));
        }
        table.push(node.tag.clone());
        if endpoint {
            endpoints.push(Value::Object(object));
        } else {
            nodes.push(Value::Object(object));
        }
    }
    table.push(TAG_DIRECT.to_string());
    table.push(TAG_BLOCK.to_string());

    let mut outbounds = vec![json!({"type": "fkbrain", "tag": TAG_BRAIN, "outbounds": table})];
    outbounds.extend(nodes);
    outbounds.push(json!({"type": "direct", "tag": TAG_DIRECT}));
    outbounds.push(json!({"type": "block", "tag": TAG_BLOCK}));

    let mut tun = Map::new();
    tun.insert("type".into(), json!("tun"));
    tun.insert("tag".into(), json!(TAG_TUN));
    tun.insert("address".into(), json!(options.tun.address));
    if let Some(mtu) = options.tun.mtu {
        tun.insert("mtu".into(), json!(mtu));
    }
    if options.tun.auto_route {
        tun.insert("auto_route".into(), json!(true));
    }
    if options.tun.strict_route {
        tun.insert("strict_route".into(), json!(true));
    }
    if let Some(stack) = &options.tun.stack {
        tun.insert("stack".into(), json!(stack));
    }

    let mut config = Map::new();
    if let Some(level) = &options.log_level {
        config.insert("log".into(), json!({"level": level}));
    }
    config.insert("dns".into(), json!({"servers": [{"type": "fkdns", "tag": TAG_DNS}], "final": TAG_DNS}));
    config.insert("inbounds".into(), json!([Value::Object(tun)]));
    if !endpoints.is_empty() {
        config.insert("endpoints".into(), Value::Array(endpoints));
    }
    config.insert("outbounds".into(), Value::Array(outbounds));
    config.insert(
        "route".into(),
        json!({
            "final": TAG_BRAIN,
            // VpnService.protect for every dialer goes through the platform's
            // AutoDetectInterfaceControl, which this switches on.
            "auto_detect_interface": true,
            "default_domain_resolver": TAG_DNS
        }),
    );
    let table = {
        let mut t: Vec<String> = result.nodes.iter().map(|n| n.tag.clone()).collect();
        t.push(TAG_DIRECT.to_string());
        t.push(TAG_BLOCK.to_string());
        t
    };
    Ok(Generated { config: Value::Object(config), outbounds: table })
}
