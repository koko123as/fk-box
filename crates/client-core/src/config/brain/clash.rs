//! Clash/mihomo YAML: `proxies` (each through [`super::native`]),
//! `proxy-groups` (members resolved as mihomo 1.19.34 resolves them without
//! proxy providers), `rules`/`sub-rules`/`rule-providers` (the stage 1 rule
//! importer). `dns`/`hosts` are recognised and not used; `proxy-providers`
//! and a group's `use` are refused (decided 2026-11-24).

use regex::Regex;
use serde_json::{Map, Value};

use super::value::{is_zero, scalar_text, Reader};
use super::{native, validate, Group, GroupKind, GroupParams, ImportResult, Node, Notice, NoticeKind, Rules, SourceFormat, IGNORED_DNS};
use crate::rules::engine::import_clash::{self, ClashOptions, ProviderLoader};
use crate::rules::engine::ImportError;

/// Top-level keys carrying DNS settings.
pub const DNS_KEYS: [&str; 2] = ["dns", "hosts"];

/// Top-level keys the import reads.
const READ_KEYS: [&str; 6] = ["proxies", "proxy-groups", "rules", "sub-rules", "rule-providers", "proxy-providers"];

/// mihomo's adapter type names (`constant/adapters.go`), for `exclude-type`.
pub fn mihomo_type_name(protocol: &str) -> &'static str {
    match protocol {
        "ss" => "Shadowsocks",
        "ssr" => "ShadowsocksR",
        "snell" => "Snell",
        "socks5" => "Socks5",
        "http" => "Http",
        "vmess" => "Vmess",
        "vless" => "Vless",
        "trojan" => "Trojan",
        "hysteria" => "Hysteria",
        "hysteria2" => "Hysteria2",
        "wireguard" => "WireGuard",
        "tuic" => "Tuic",
        "ssh" => "Ssh",
        "mieru" => "Mieru",
        "anytls" => "AnyTLS",
        "sudoku" => "Sudoku",
        "masque" => "Masque",
        "trusttunnel" => "TrustTunnel",
        "shadowquic" => "ShadowQuic",
        "gost-relay" => "GostRelay",
        "direct" => "Direct",
        "reject" => "Reject",
        _ => "Unknown",
    }
}

fn builtin_type_name(name: &str) -> Option<&'static str> {
    match name {
        "DIRECT" => Some("Direct"),
        "REJECT" => Some("Reject"),
        "REJECT-DROP" => Some("RejectDrop"),
        "PASS" => Some("Pass"),
        "COMPATIBLE" => Some("Compatible"),
        _ => None,
    }
}

fn group_type_name(kind: &str) -> &'static str {
    match kind {
        "select" => "Selector",
        "url-test" => "URLTest",
        "fallback" => "Fallback",
        "load-balance" => "LoadBalance",
        _ => "Unknown",
    }
}

/// mihomo splits filters on a backquote; each is a regexp2 (.NET) pattern.
fn filters(text: &str, key: &str) -> Result<Vec<Regex>, String> {
    text.split('`')
        .map(|f| {
            Regex::new(f).map_err(|e| format!("{key} {f:?} does not compile here (mihomo uses .NET-style regexp2; lookaround and backreferences are not supported): {e}"))
        })
        .collect()
}

pub fn import_value(config: &Value, loader: &dyn ProviderLoader) -> Result<ImportResult, ImportError> {
    let map = config.as_object().ok_or_else(|| ImportError::new("config", "not a mapping"))?;
    let mut notices = Vec::new();
    if let Some(p) = map.get("proxy-providers") {
        if !is_zero(p) {
            return Err(ImportError::new("proxy-providers", "proxy providers are not supported; put the nodes in proxies"));
        }
    }
    if DNS_KEYS.iter().any(|k| map.contains_key(*k)) {
        notices.push(Notice::new(NoticeKind::IgnoredDns, "dns", IGNORED_DNS));
    }
    let others: Vec<&str> = map.keys().map(String::as_str).filter(|k| !READ_KEYS.contains(k) && !DNS_KEYS.contains(k)).collect();
    if !others.is_empty() {
        notices.push(Notice::new(NoticeKind::IgnoredSettings, "config", format!("client settings not used: {}", others.join(", "))));
    }
    let mut nodes: Vec<Node> = Vec::new();
    match map.get("proxies") {
        None | Some(Value::Null) => {}
        Some(Value::Array(items)) => {
            for (i, item) in items.iter().enumerate() {
                let proxy = item.as_object().ok_or_else(|| ImportError::new(format!("proxies[{i}]"), "not a mapping"))?;
                nodes.push(native::from_mihomo(proxy.clone(), &mut notices)?);
            }
        }
        Some(_) => return Err(ImportError::new("proxies", "not a list")),
    }
    let raw_groups: Vec<&Map<String, Value>> = match map.get("proxy-groups") {
        None | Some(Value::Null) => Vec::new(),
        Some(Value::Array(items)) => {
            let mut out = Vec::new();
            for (i, item) in items.iter().enumerate() {
                out.push(item.as_object().ok_or_else(|| ImportError::new(format!("proxy-groups[{i}]"), "not a mapping"))?);
            }
            out
        }
        Some(_) => return Err(ImportError::new("proxy-groups", "not a list")),
    };
    // mihomo sorts the proxy names include-all adds.
    let mut all_proxies: Vec<String> = nodes.iter().map(|n| n.tag.clone()).collect();
    all_proxies.sort();
    let group_types: Vec<(String, String)> = raw_groups
        .iter()
        .map(|g| {
            let name = g.get("name").and_then(scalar_text).unwrap_or_default();
            let kind = g.get("type").and_then(scalar_text).unwrap_or_default();
            (name, kind)
        })
        .collect();
    let type_of = |member: &str| -> &'static str {
        if let Some(t) = builtin_type_name(member) {
            return t;
        }
        if let Some(node) = nodes.iter().find(|n| n.tag == member) {
            return mihomo_type_name(&node.protocol);
        }
        match group_types.iter().find(|(n, _)| n == member) {
            Some((_, kind)) => group_type_name(kind),
            None => "Unknown",
        }
    };
    let mut groups = Vec::new();
    for (i, raw) in raw_groups.iter().enumerate() {
        groups.push(group(i, raw, &all_proxies, &type_of, &mut notices)?);
    }
    let router = import_clash::import_value(config, loader, &ClashOptions::default())?;
    let result = ImportResult { format: SourceFormat::Clash, nodes, groups, rules: Some(Rules { router, host: Vec::new() }), notices };
    validate(&result)?;
    Ok(result)
}

fn group(
    index: usize,
    m: &Map<String, Value>,
    all_proxies: &[String],
    type_of: &dyn Fn(&str) -> &'static str,
    notices: &mut Vec<Notice>,
) -> Result<Group, ImportError> {
    let name = match m.get("name").and_then(scalar_text) {
        Some(n) if !n.is_empty() => n,
        _ => return Err(ImportError::new(format!("proxy-groups[{index}]"), "missing name")),
    };
    let at = format!("proxy group {name:?}");
    let err = |e: String| ImportError::new(at.clone(), e);
    let mut r = Reader::new(m, "");
    r.skip("name");
    let kind = match r.string("type").map_err(err)?.as_deref() {
        Some("select") => GroupKind::Select,
        Some("url-test") => GroupKind::UrlTest,
        Some("fallback") => GroupKind::Fallback,
        Some("load-balance") => GroupKind::LoadBalance,
        Some("relay") => return Err(err("relay groups were removed from mihomo; use dialer-proxy on the node".into())),
        Some(other) => return Err(err(format!("unsupported group type {other:?}"))),
        None => return Err(err("missing type".into())),
    };
    if !r.strings("use").map_err(err)?.is_empty() || r.boolean("include-all-providers").map_err(err)? {
        return Err(err("use / include-all-providers (proxy providers) are not supported".into()));
    }
    for key in ["interface-name", "routing-mark", "dialer-proxy"] {
        if r.has(key) {
            notices.push(Notice::new(
                NoticeKind::IgnoredSettings,
                name.clone(),
                format!("{key} on a group is ignored (mihomo ignores it too and logs an error)"),
            ));
        }
        r.skip(key);
    }
    let mut members = r.strings("proxies").map_err(err)?;
    let include_all = r.boolean("include-all").map_err(err)?;
    let include_all_proxies = r.boolean("include-all-proxies").map_err(err)? || include_all;
    let filter = r.string("filter").map_err(err)?;
    let exclude_filter = r.string("exclude-filter").map_err(err)?;
    let exclude_type = r.string("exclude-type").map_err(err)?;
    if include_all_proxies {
        match &filter {
            Some(f) => {
                let regs = filters(f, "filter").map_err(err)?;
                for proxy in all_proxies {
                    for reg in &regs {
                        if reg.is_match(proxy) && !members.contains(proxy) {
                            members.push(proxy.clone());
                        }
                    }
                }
            }
            None => {
                for proxy in all_proxies {
                    if !members.contains(proxy) {
                        members.push(proxy.clone());
                    }
                }
            }
        }
    } else if members.is_empty() {
        return Err(err("`use` or `proxies` missing".into()));
    }
    if let Some(f) = &exclude_filter {
        let regs = filters(f, "exclude-filter").map_err(err)?;
        members.retain(|member| !regs.iter().any(|reg| reg.is_match(member)));
    }
    if let Some(t) = &exclude_type {
        let types: Vec<&str> = t.split('|').map(str::trim).filter(|s| !s.is_empty()).collect();
        members.retain(|member| !types.iter().any(|ty| ty.eq_ignore_ascii_case(type_of(member.as_str()))));
    }
    if members.is_empty() {
        notices.push(Notice::new(
            NoticeKind::EmptyGroup,
            name.clone(),
            "no member is left after filtering; mihomo would use COMPATIBLE (reject)",
        ));
    }
    let mut params = GroupParams { url: r.string("url").map_err(err)?, ..GroupParams::default() };
    params.interval_secs = r.uint("interval").map_err(err)?;
    params.tolerance_ms = r.uint("tolerance").map_err(err)?;
    params.timeout_ms = r.uint("timeout").map_err(err)?;
    if r.raw("lazy").is_some() {
        // raw() marked it; read it again for the value.
        let mut again = Reader::new(m, "");
        params.lazy = Some(again.boolean("lazy").map_err(err)?);
    }
    params.max_failed_times = r.uint("max-failed-times").map_err(err)?;
    params.strategy = r.string("strategy").map_err(err)?;
    if let Some(s) = &params.strategy {
        if kind != GroupKind::LoadBalance {
            return Err(err(format!("strategy {s:?} on a {} group", kind.as_str())));
        }
        if !matches!(s.as_str(), "consistent-hashing" | "round-robin" | "sticky-sessions") {
            return Err(err(format!("unknown load-balance strategy {s:?}")));
        }
    }
    params.expected_status = r.string("expected-status").map_err(err)?;
    params.disable_udp = r.boolean("disable-udp").map_err(err)?;
    params.hidden = r.boolean("hidden").map_err(err)?;
    params.icon = r.string("icon").map_err(err)?;
    if let Some(fallback) = r.string("empty-fallback").map_err(err)? {
        notices.push(Notice::new(
            NoticeKind::IgnoredSettings,
            name.clone(),
            format!("empty-fallback {fallback:?} is not used yet (stage 5)"),
        ));
    }
    let left = r.leftovers();
    if !left.is_empty() {
        notices.push(Notice::new(NoticeKind::IgnoredSettings, name.clone(), format!("unknown group fields not used: {}", left.join(", "))));
    }
    Ok(Group { name, kind, members, params })
}
