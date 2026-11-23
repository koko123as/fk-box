//! sing-box routing into the unified representation: `route.rules` (default
//! and logical rules, rule actions), `route.rule_set` (inline, local and
//! remote; source and binary) and `route.final`.
//!
//! A default rule follows sing-box's grouping (`route/rule/rule_abstract.go`):
//! destination address items (`domain*`, `ip_cidr`, `ip_is_private`, and the
//! rule's `rule_set`s) are OR'ed, so are source address items, source ports
//! and destination ports; the groups and every other item are AND'ed;
//! `invert` negates. IP items never consult DNS (sing-box matches them only
//! against an address the connection already has, unless a `resolve`
//! action ran, which the brain cannot do synchronously).
//!
//! `sniff` rules are returned as host directives (sniffing happens in the
//! host before decide); every field or action the brain cannot express is
//! an error naming it.

use std::collections::BTreeSet;

use serde_json::{Map, Value};

use super::domain::normalize_value;
use super::import_clash::conds_rule_sets;
use super::ip::IpNet;
use super::ir::{Behavior, Cond, ImportError, IpSide, Network, Ranges, RouterIr, Rule, RuleSet, Target};
use super::ruleset::read_srs;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RuleSetFormat {
    Source,
    Binary,
}

/// A `local`/`remote` rule set whose bytes the caller supplies.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RuleSetRef {
    pub tag: String,
    pub kind: String,
    pub format: RuleSetFormat,
    pub path: Option<String>,
    pub url: Option<String>,
}

pub trait RuleSetLoader {
    fn load(&self, set: &RuleSetRef) -> Result<Vec<u8>, String>;
}

impl<F: Fn(&RuleSetRef) -> Result<Vec<u8>, String>> RuleSetLoader for F {
    fn load(&self, set: &RuleSetRef) -> Result<Vec<u8>, String> {
        self(set)
    }
}

/// A rule the host acts on itself.
#[derive(Debug, Clone, PartialEq)]
pub enum HostDirective {
    /// `action: sniff` at `index` in `route.rules`, as written.
    Sniff { index: usize, rule: Value },
}

#[derive(Debug, Clone, PartialEq)]
pub struct SingBoxImport {
    pub router: RouterIr,
    pub host: Vec<HostDirective>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Kind {
    Route,
    Headless,
}

/// Fields sing-box has that the brain does not express, with why.
const UNSUPPORTED: [(&str, &str); 23] = [
    ("auth_user", "the inbound user is not in the connection metadata"),
    ("client", "the sniffed client is not in the connection metadata"),
    ("clash_mode", "Clash API modes are not part of the brain"),
    ("wifi_ssid", "Wi-Fi state is not in the connection metadata"),
    ("wifi_bssid", "Wi-Fi state is not in the connection metadata"),
    ("network_type", "the network type is not in the connection metadata"),
    ("network_is_expensive", "the network type is not in the connection metadata"),
    ("network_is_constrained", "the network type is not in the connection metadata"),
    ("network_interface_address", "interface addresses are not in the connection metadata"),
    ("default_interface_address", "interface addresses are not in the connection metadata"),
    ("interface_address", "interface addresses are not in the connection metadata"),
    ("process_path", "the process path is not in the connection metadata"),
    ("process_path_regex", "the process path is not in the connection metadata"),
    ("user", "the user name is not in the connection metadata"),
    ("source_mac_address", "the source MAC address is not in the connection metadata"),
    ("source_hostname", "the source hostname is not in the connection metadata"),
    ("preferred_by", "outbound preference rules are not part of the brain"),
    ("rule_set_ip_cidr_accept_empty", "only meaningful for DNS rules"),
    ("adguard_domain", "AdGuard rule syntax is not implemented"),
    ("query_type", "a DNS rule item; it cannot route connections"),
    ("geoip", "removed in sing-box 1.12 (use rule sets)"),
    ("source_geoip", "removed in sing-box 1.12 (use rule sets)"),
    ("geosite", "removed in sing-box 1.12 (use rule sets)"),
];

fn strings(v: &Value) -> Result<Vec<String>, String> {
    match v {
        Value::String(s) => Ok(vec![s.clone()]),
        Value::Array(items) => items.iter().map(|i| i.as_str().map(str::to_string).ok_or_else(|| format!("{i} is not a string"))).collect(),
        other => Err(format!("{other} is not a string or a list of strings")),
    }
}

fn numbers(v: &Value, max: u64) -> Result<Vec<u32>, String> {
    let one = |i: &Value| -> Result<u32, String> {
        i.as_u64().filter(|n| *n <= max).map(|n| n as u32).ok_or_else(|| format!("{i} is not a number up to {max}"))
    };
    match v {
        Value::Array(items) => items.iter().map(one).collect(),
        other => Ok(vec![one(other)?]),
    }
}

fn boolean(v: &Value) -> Result<bool, String> {
    v.as_bool().ok_or_else(|| format!("{v} is not a boolean"))
}

fn any_of(mut conds: Vec<Cond>) -> Cond {
    if conds.len() == 1 {
        conds.pop().unwrap_or(Cond::Any)
    } else {
        Cond::Or(conds)
    }
}

fn field_error(key: &str) -> String {
    match UNSUPPORTED.iter().find(|(k, _)| *k == key) {
        Some((_, why)) => format!("field {key:?} is not supported: {why}"),
        None => format!("unknown field {key:?}"),
    }
}

/// A default rule's condition; `action_keys` are skipped (the caller reads them).
fn default_cond(obj: &Map<String, Value>, kind: Kind, action_keys: &[&str]) -> Result<Cond, String> {
    let route = kind == Kind::Route;
    let mut dst_addr = Vec::new();
    let mut src_addr = Vec::new();
    let mut src_ports = Vec::new();
    let mut dst_ports = Vec::new();
    let mut items = Vec::new();
    let mut rule_sets = Vec::new();
    let mut match_source = false;
    let mut invert = false;
    for (key, value) in obj {
        let at = |e: String| format!("{key}: {e}");
        match key.as_str() {
            "type" if value.as_str() == Some("default") => {}
            "domain" => {
                for s in strings(value).map_err(at)? {
                    dst_addr.push(Cond::Domain(normalize_value(&s).map_err(at)?));
                }
            }
            "domain_suffix" => {
                for s in strings(value).map_err(at)? {
                    dst_addr.push(match s.strip_prefix('.') {
                        Some(rest) => Cond::DomainSubdomain(normalize_value(rest).map_err(at)?),
                        None => Cond::DomainSuffix(normalize_value(&s).map_err(at)?),
                    });
                }
            }
            "domain_keyword" => {
                for s in strings(value).map_err(at)? {
                    dst_addr.push(Cond::DomainKeyword(s.to_lowercase()));
                }
            }
            "domain_regex" => {
                for s in strings(value).map_err(at)? {
                    regex::Regex::new(&s).map_err(|e| at(e.to_string()))?;
                    dst_addr.push(Cond::DomainRegex(s));
                }
            }
            "ip_cidr" => {
                for s in strings(value).map_err(at)? {
                    dst_addr.push(Cond::IpCidr { net: IpNet::parse(&s).map_err(at)?, side: IpSide::DST_NO_RESOLVE });
                }
            }
            "ip_is_private" if route => {
                if boolean(value).map_err(at)? {
                    dst_addr.push(Cond::IpPrivate { side: IpSide::DST_NO_RESOLVE });
                }
            }
            "source_ip_cidr" => {
                for s in strings(value).map_err(at)? {
                    src_addr.push(Cond::IpCidr { net: IpNet::parse(&s).map_err(at)?, side: IpSide::Src });
                }
            }
            "source_ip_is_private" if route => {
                if boolean(value).map_err(at)? {
                    src_addr.push(Cond::IpPrivate { side: IpSide::Src });
                }
            }
            "source_port" => src_ports.extend(numbers(value, 65535).map_err(at)?.into_iter().map(|p| (p, p))),
            "source_port_range" => {
                for s in strings(value).map_err(at)? {
                    src_ports.push(Ranges::parse_colon(&s).map_err(at)?);
                }
            }
            "port" => dst_ports.extend(numbers(value, 65535).map_err(at)?.into_iter().map(|p| (p, p))),
            "port_range" => {
                for s in strings(value).map_err(at)? {
                    dst_ports.push(Ranges::parse_colon(&s).map_err(at)?);
                }
            }
            "network" => {
                let mut nets = Vec::new();
                for s in strings(value).map_err(at)? {
                    nets.push(Cond::Network(Network::parse(&s).ok_or_else(|| at(format!("unknown network {s:?}")))?));
                }
                items.push(any_of(nets));
            }
            "ip_version" if route => {
                let v = value.as_u64().filter(|v| *v == 4 || *v == 6).ok_or_else(|| at("must be 4 or 6".into()))?;
                items.push(Cond::IpVersion(v as u8));
            }
            "protocol" if route => {
                items.push(any_of(strings(value).map_err(at)?.into_iter().map(|p| Cond::Protocol(p.to_ascii_lowercase())).collect()));
            }
            "process_name" => items.push(any_of(strings(value).map_err(at)?.into_iter().map(Cond::ProcessName).collect())),
            "package_name" => items.push(any_of(strings(value).map_err(at)?.into_iter().map(Cond::PackageName).collect())),
            "package_name_regex" => {
                let mut conds = Vec::new();
                for s in strings(value).map_err(at)? {
                    regex::Regex::new(&s).map_err(|e| at(e.to_string()))?;
                    conds.push(Cond::PackageNameRegex(s));
                }
                items.push(any_of(conds));
            }
            "user_id" if route => {
                let ids = numbers(value, u64::from(u32::MAX)).map_err(at)?;
                items.push(Cond::Uid(Ranges(ids.into_iter().map(|u| (u, u)).collect())));
            }
            "inbound" if route => items.push(any_of(strings(value).map_err(at)?.into_iter().map(Cond::InboundTag).collect())),
            "rule_set" if route => rule_sets = strings(value).map_err(at)?,
            "rule_set_ip_cidr_match_source" if route => match_source = boolean(value).map_err(at)?,
            "invert" => invert = boolean(value).map_err(at)?,
            k if action_keys.contains(&k) => {}
            k => return Err(field_error(k)),
        }
    }
    for name in rule_sets {
        dst_addr.push(Cond::RuleSet { name, src_ip: match_source, swap_ports: false, no_resolve: true });
    }
    let mut groups = Vec::new();
    if !src_addr.is_empty() {
        groups.push(any_of(src_addr));
    }
    if !src_ports.is_empty() {
        groups.push(Cond::SrcPort(Ranges(src_ports)));
    }
    if !dst_addr.is_empty() {
        groups.push(any_of(dst_addr));
    }
    if !dst_ports.is_empty() {
        groups.push(Cond::DstPort(Ranges(dst_ports)));
    }
    groups.extend(items);
    let cond = match groups.len() {
        0 => Cond::Any,
        1 => groups.pop().unwrap_or(Cond::Any),
        _ => Cond::And(groups),
    };
    Ok(if invert { Cond::Not(Box::new(cond)) } else { cond })
}

/// A rule's condition (default or logical); nested rules carry no action.
fn rule_cond(value: &Value, kind: Kind, action_keys: &[&str], depth: u32) -> Result<Cond, String> {
    if depth > 32 {
        return Err("logical rules nested too deep".into());
    }
    let obj = value.as_object().ok_or("a rule must be an object")?;
    if obj.get("type").and_then(Value::as_str) != Some("logical") {
        return default_cond(obj, kind, action_keys);
    }
    let mut mode = None;
    let mut children = None;
    let mut invert = false;
    for (key, v) in obj {
        match key.as_str() {
            "type" => {}
            "mode" => mode = v.as_str(),
            "rules" => children = Some(v.as_array().ok_or("rules: not a list")?),
            "invert" => invert = boolean(v).map_err(|e| format!("invert: {e}"))?,
            k if action_keys.contains(&k) => {}
            k => return Err(field_error(k)),
        }
    }
    let children = children.filter(|c| !c.is_empty()).ok_or("logical rule without rules")?;
    let mut conds = Vec::with_capacity(children.len());
    for (i, c) in children.iter().enumerate() {
        conds.push(rule_cond(c, kind, &[], depth + 1).map_err(|e| format!("rules[{i}]: {e}"))?);
    }
    let cond = match mode {
        Some("and") => Cond::And(conds),
        Some("or") => Cond::Or(conds),
        other => return Err(format!("unknown logical mode {other:?}")),
    };
    Ok(if invert { Cond::Not(Box::new(cond)) } else { cond })
}

enum Converted {
    Rule(Rule),
    Sniff,
}

fn route_rule(value: &Value) -> Result<Converted, String> {
    let obj = value.as_object().ok_or("a rule must be an object")?;
    let action = match obj.get("action") {
        None => "route",
        Some(Value::String(a)) if a.is_empty() => "route",
        Some(Value::String(a)) => a.as_str(),
        Some(other) => return Err(format!("action: {other} is not a string")),
    };
    let keys: &[&str] = match action {
        "route" => &["action", "outbound"],
        "reject" => &["action", "method", "no_drop"],
        "hijack-dns" => &["action"],
        "sniff" => &["action", "sniffer", "timeout"],
        "resolve" => {
            return Err("action \"resolve\" needs a synchronous DNS lookup, which decide does not do (stage 4: DNS in the brain)".into())
        }
        "route-options" => return Err("action \"route-options\" (dial options) is not expressible as a routing decision".into()),
        "bypass" => return Err("action \"bypass\" is not supported".into()),
        other => return Err(format!("unknown action {other:?}")),
    };
    // Route options on a route action would be dropped: refuse them.
    let cond = rule_cond(value, Kind::Route, keys, 0)?;
    let source = serde_json::to_string(value).unwrap_or_default();
    let target = match action {
        "route" => {
            let tag = obj.get("outbound").and_then(Value::as_str).filter(|t| !t.is_empty()).ok_or("route rule without an outbound")?;
            Target::Outbound(tag.to_string())
        }
        "reject" => {
            if obj.get("no_drop").and_then(Value::as_bool) == Some(true) {
                return Err("reject no_drop is not expressible".into());
            }
            match obj.get("method").and_then(Value::as_str).unwrap_or("default") {
                "default" | "" => Target::Reject,
                "drop" => Target::RejectDrop,
                other => return Err(format!("unknown reject method {other:?}")),
            }
        }
        "hijack-dns" => Target::HijackDns,
        _ => return Ok(Converted::Sniff),
    };
    Ok(Converted::Rule(Rule { cond, target, source }))
}

/// A rule set in the source format (`{"version": n, "rules": [...]}`).
pub fn source_rule_set(value: &Value) -> Result<RuleSet, String> {
    let obj = value.as_object().ok_or("rule set: not an object")?;
    if let Some(key) = obj.keys().find(|k| !matches!(k.as_str(), "version" | "rules")) {
        return Err(format!("rule set: unknown field {key:?}"));
    }
    let rules = obj.get("rules").and_then(Value::as_array).ok_or("rule set: no rules")?;
    let mut out = Vec::with_capacity(rules.len());
    for (i, r) in rules.iter().enumerate() {
        out.push(rule_cond(r, Kind::Headless, &[], 0).map_err(|e| format!("rules[{i}]: {e}"))?);
    }
    Ok(RuleSet { behavior: Behavior::Classical, rules: out })
}

const RULE_SET_KEYS: [&str; 9] = ["type", "tag", "format", "path", "url", "rules", "download_detour", "update_interval", "http_client"];

fn load_rule_set(spec: &Map<String, Value>, tag: &str, loader: &dyn RuleSetLoader) -> Result<RuleSet, String> {
    if let Some(key) = spec.keys().find(|k| !RULE_SET_KEYS.contains(&k.as_str())) {
        return Err(format!("unknown field {key:?}"));
    }
    let kind = spec.get("type").and_then(Value::as_str).unwrap_or_default();
    if kind == "inline" {
        let rules = spec.get("rules").cloned().unwrap_or(Value::Array(Vec::new()));
        return source_rule_set(&serde_json::json!({ "rules": rules }));
    }
    if kind != "local" && kind != "remote" {
        return Err(format!("unknown rule set type {kind:?}"));
    }
    let path = spec.get("path").and_then(Value::as_str).map(str::to_string);
    let url = spec.get("url").and_then(Value::as_str).map(str::to_string);
    let location = (if kind == "local" { path.clone() } else { url.clone() })
        .ok_or_else(|| format!("{kind} rule set without a {}", if kind == "local" { "path" } else { "url" }))?;
    let format = match spec.get("format").and_then(Value::as_str) {
        Some("source") => RuleSetFormat::Source,
        Some("binary") => RuleSetFormat::Binary,
        Some(other) => return Err(format!("unknown format {other:?}")),
        None if location.ends_with(".srs") => RuleSetFormat::Binary,
        None if location.ends_with(".json") => RuleSetFormat::Source,
        None => return Err("format is missing and cannot be told from the file name".into()),
    };
    let data = loader.load(&RuleSetRef { tag: tag.to_string(), kind: kind.to_string(), format, path, url })?;
    let value = match format {
        RuleSetFormat::Binary => read_srs(&data)?,
        RuleSetFormat::Source => serde_json::from_slice(&data).map_err(|e| format!("invalid JSON: {e}"))?,
    };
    source_rule_set(&value)
}

pub fn import_value(config: &Value, loader: &dyn RuleSetLoader) -> Result<SingBoxImport, ImportError> {
    let empty = Value::Object(Map::new());
    let route = config.get("route").unwrap_or(&empty);
    for legacy in ["geoip", "geosite"] {
        if route.get(legacy).is_some() {
            return Err(ImportError::new(format!("route.{legacy}"), "removed in sing-box 1.12 (use rule sets)"));
        }
    }
    let mut router = RouterIr::default();
    let mut host = Vec::new();
    let rules = match route.get("rules") {
        None | Some(Value::Null) => Vec::new(),
        Some(Value::Array(r)) => r.clone(),
        Some(_) => return Err(ImportError::new("route.rules", "not a list")),
    };
    for (i, value) in rules.iter().enumerate() {
        match route_rule(value).map_err(|e| ImportError::new(format!("route.rules[{i}]"), e))? {
            Converted::Rule(rule) => router.rules.push(rule),
            Converted::Sniff => host.push(HostDirective::Sniff { index: i, rule: value.clone() }),
        }
    }
    let mut wanted = BTreeSet::new();
    for rule in &router.rules {
        conds_rule_sets(&rule.cond, &mut wanted);
    }
    let specs = match route.get("rule_set") {
        None | Some(Value::Null) => Vec::new(),
        Some(Value::Array(s)) => s.clone(),
        Some(_) => return Err(ImportError::new("route.rule_set", "not a list")),
    };
    for name in wanted {
        let spec = specs
            .iter()
            .filter_map(Value::as_object)
            .find(|s| s.get("tag").and_then(Value::as_str) == Some(name))
            .ok_or_else(|| ImportError::new(format!("rule_set {name:?}"), "no such rule set in route.rule_set"))?;
        let set = load_rule_set(spec, name, loader).map_err(|e| ImportError::new(format!("route.rule_set {name:?}"), e))?;
        router.rule_sets.insert(name.to_string(), set);
    }
    let final_tag = route.get("final").and_then(Value::as_str).filter(|t| !t.is_empty()).or_else(|| {
        config.get("outbounds").and_then(Value::as_array).and_then(|o| o.first()).and_then(|o| o.get("tag")).and_then(Value::as_str)
    });
    router.final_target = final_tag.map(|t| Target::Outbound(t.to_string()));
    Ok(SingBoxImport { router, host })
}

pub fn import(json: &str, loader: &dyn RuleSetLoader) -> Result<SingBoxImport, ImportError> {
    let config: Value = serde_json::from_str(json).map_err(|e| ImportError::new("config", format!("invalid JSON: {e}")))?;
    import_value(&config, loader)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::rules::engine::ir::MissPolicy;
    use serde_json::json;

    fn no_loader(_: &RuleSetRef) -> Result<Vec<u8>, String> {
        Err("no files".into())
    }

    fn one(rule: Value) -> Result<Rule, String> {
        match route_rule(&rule)? {
            Converted::Rule(r) => Ok(r),
            Converted::Sniff => Err("sniff".into()),
        }
    }

    #[test]
    fn groups_follow_sing_box() {
        let r = one(json!({
            "domain_suffix": ["example.com", ".sub.test"],
            "ip_cidr": "10.0.0.0/8",
            "port": [443, 8443],
            "port_range": "1000:2000",
            "network": "tcp",
            "outbound": "proxy"
        }))
        .unwrap();
        assert_eq!(r.target, Target::Outbound("proxy".into()));
        assert_eq!(
            r.cond,
            Cond::And(vec![
                Cond::Or(vec![
                    Cond::DomainSuffix("example.com".into()),
                    Cond::DomainSubdomain("sub.test".into()),
                    Cond::IpCidr { net: IpNet::parse("10.0.0.0/8").unwrap(), side: IpSide::DST_NO_RESOLVE },
                ]),
                Cond::DstPort(Ranges(vec![(443, 443), (8443, 8443), (1000, 2000)])),
                Cond::Network(Network::Tcp),
            ])
        );
        let r = one(json!({"action": "reject", "method": "drop", "invert": true, "domain_keyword": "Ads"})).unwrap();
        assert_eq!(r.target, Target::RejectDrop);
        assert_eq!(r.cond, Cond::Not(Box::new(Cond::DomainKeyword("ads".into()))));
        let r = one(json!({"action": "hijack-dns", "protocol": "dns"})).unwrap();
        assert_eq!((r.target, r.cond), (Target::HijackDns, Cond::Protocol("dns".into())));
        let r = one(json!({"rule_set": ["geoip-cn", "geosite-cn"], "rule_set_ip_cidr_match_source": true, "outbound": "direct"})).unwrap();
        assert_eq!(
            r.cond,
            Cond::Or(vec![
                Cond::RuleSet { name: "geoip-cn".into(), src_ip: true, swap_ports: false, no_resolve: true },
                Cond::RuleSet { name: "geosite-cn".into(), src_ip: true, swap_ports: false, no_resolve: true },
            ])
        );
        let r = one(json!({"ip_is_private": true, "source_ip_is_private": true, "ip_version": 6, "user_id": [10001], "package_name": ["a.b"], "inbound": "tun-in", "outbound": "direct"})).unwrap();
        let Cond::And(groups) = r.cond else { panic!() };
        assert_eq!(groups.len(), 6);
        assert_eq!(one(json!({"outbound": "x"})).unwrap().cond, Cond::Any);
        let r =
            one(json!({"type": "logical", "mode": "or", "rules": [{"domain": "a.com"}, {"port": 22, "invert": true}], "outbound": "x"}))
                .unwrap();
        assert_eq!(r.cond, Cond::Or(vec![Cond::Domain("a.com".into()), Cond::Not(Box::new(Cond::DstPort(Ranges::single(22))))]));
        assert!(matches!(route_rule(&json!({"action": "sniff", "sniffer": ["tls"]})).unwrap(), Converted::Sniff));
    }

    #[test]
    fn what_cannot_be_expressed_is_an_error() {
        for (rule, want) in [
            (json!({"wifi_ssid": "home", "outbound": "x"}), "wifi_ssid"),
            (json!({"clash_mode": "direct", "outbound": "x"}), "clash_mode"),
            (json!({"process_path": "/bin/x", "outbound": "x"}), "process_path"),
            (json!({"geosite": "cn", "outbound": "x"}), "removed"),
            (json!({"bogus": 1, "outbound": "x"}), "unknown field"),
            (json!({"action": "resolve"}), "resolve"),
            (json!({"action": "route-options", "udp_connect": true}), "route-options"),
            (json!({"action": "route", "outbound": "x", "override_port": 53}), "override_port"),
            (json!({"action": "reject", "method": "reply"}), "reject method"),
            (json!({"domain": "a.com"}), "without an outbound"),
            (json!({"type": "logical", "mode": "xor", "rules": [{"domain": "a"}], "outbound": "x"}), "logical mode"),
            (
                json!({"type": "logical", "mode": "and", "rules": [{"domain": "a", "outbound": "y"}], "outbound": "x"}),
                "unknown field \"outbound\"",
            ),
            (json!({"ip_cidr": "1.2.3.4/40", "outbound": "x"}), "invalid IP CIDR"),
            (json!({"port": 70000, "outbound": "x"}), "port"),
        ] {
            let err = route_rule(&rule).err().unwrap_or_default();
            assert!(err.contains(want), "{rule}: {err}");
        }
        assert!(source_rule_set(&json!({"rules": [{"inbound": "x"}]})).unwrap_err().contains("inbound"));
    }

    #[test]
    fn imports_route_with_rule_sets_and_final() {
        let config = json!({
            "outbounds": [{"type": "direct", "tag": "direct"}, {"type": "selector", "tag": "proxy"}],
            "route": {
                "rules": [
                    {"action": "sniff"},
                    {"protocol": "dns", "action": "hijack-dns"},
                    {"rule_set": "ads", "action": "reject"},
                    {"rule_set": ["cn"], "outbound": "direct"}
                ],
                "rule_set": [
                    {"type": "inline", "tag": "ads", "rules": [{"domain_suffix": "ads.example"}]},
                    {"type": "local", "tag": "cn", "path": "cn.json"},
                    {"type": "remote", "tag": "unused", "url": "https://example.com/x.srs"}
                ],
                "final": "proxy",
                "auto_detect_interface": true
            }
        });
        let loader = |s: &RuleSetRef| -> Result<Vec<u8>, String> {
            assert_eq!((s.tag.as_str(), s.format), ("cn", RuleSetFormat::Source));
            Ok(br#"{"version": 3, "rules": [{"ip_cidr": ["1.0.1.0/24"]}, {"domain_suffix": "cn"}]}"#.to_vec())
        };
        let out = import_value(&config, &loader).unwrap();
        assert_eq!(out.host, vec![HostDirective::Sniff { index: 0, rule: json!({"action": "sniff"}) }]);
        assert_eq!(out.router.rules.len(), 3);
        assert_eq!(out.router.rule_sets.len(), 2);
        assert_eq!(out.router.rule_sets["cn"].rules.len(), 2);
        assert_eq!(out.router.final_target, Some(Target::Outbound("proxy".into())));
        let mut no_final = config.clone();
        no_final["route"].as_object_mut().unwrap().remove("final");
        assert_eq!(import_value(&no_final, &loader).unwrap().router.final_target, Some(Target::Outbound("direct".into())));
        let missing = json!({"route": {"rules": [{"rule_set": "nope", "outbound": "x"}]}});
        assert!(import_value(&missing, &no_loader).unwrap_err().message.contains("no such rule set"));
        let legacy = json!({"route": {"geosite": {}}});
        assert!(import_value(&legacy, &no_loader).is_err());
        let _ = MissPolicy::Skip;
    }

    #[test]
    fn binary_rule_sets_go_through_the_same_importer() {
        use crate::rules::engine::ruleset::testdata::{srs, Item};
        let file = srs(&[(&[Item::Domains(&[], &["example.org"]), Item::Ips(6, &[("10.0.0.0", "10.0.0.255")])], false)]);
        let config = json!({"route": {"rules": [{"rule_set": "x", "outbound": "p"}], "rule_set": [{"type": "remote", "tag": "x", "url": "https://e.test/x.srs"}]}});
        let loader = move |s: &RuleSetRef| -> Result<Vec<u8>, String> {
            assert_eq!(s.format, RuleSetFormat::Binary);
            Ok(file.clone())
        };
        let out = import_value(&config, &loader).unwrap();
        assert_eq!(
            out.router.rule_sets["x"].rules,
            vec![Cond::Or(vec![
                Cond::DomainSuffix("example.org".into()),
                Cond::IpCidr { net: IpNet::parse("10.0.0.0/24").unwrap(), side: IpSide::DST_NO_RESOLVE },
            ])]
        );
    }
}
