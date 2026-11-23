//! Configuration import and generation for the single-kernel design ("Rust
//! brain", docs/architecture/brain-config.md).
//!
//! A subscription (share links, Base64 link lists, Clash/mihomo YAML or
//! sing-box JSON) is read into one model ([`ImportResult`]: nodes, groups,
//! rules, notices). Each node picks its implementation: a native sing-box
//! outbound when the protocol and every field map without loss, otherwise
//! the `mihomo` plugin (the mihomo proxy map as written) or the `xray`
//! plugin (an Xray outbound). Nothing is dropped silently: a field that has
//! no native equivalent moves the node to its plugin with a notice, and what
//! no implementation can carry is an import error.
//!
//! [`generate::generate`] turns the model into the minimal sing-box
//! configuration: TUN inbound, `route.final` = `fkbrain`, one `fkdns` DNS
//! server, the node outbounds and `direct`/`block`; no route or DNS rules,
//! no selector/urltest (groups and rules are the brain's).
//!
//! Subscription DNS settings (`dns`, `nameserver-policy`, `hosts`) are
//! recognised and not used; the result carries [`IGNORED_DNS`].

pub mod clash;
pub mod generate;
pub mod links;
pub mod native;
pub mod singbox;
pub mod text;
pub mod value;
pub mod xray;

#[cfg(test)]
mod tests;

use std::collections::{BTreeMap, BTreeSet};

use serde_json::{Map, Value};

use crate::rules::engine::import_clash::ProviderLoader;
use crate::rules::engine::import_singbox::{HostDirective, RuleSetLoader};
use crate::rules::engine::{ImportError, RouterIr, Target};

/// The notice every import whose source carried DNS settings gets.
pub const IGNORED_DNS: &str = "已忽略订阅 DNS 配置";

/// Outbound tags the generator owns; a node may not use them.
pub const RESERVED_TAGS: [&str; 5] = [generate::TAG_BRAIN, generate::TAG_DIRECT, generate::TAG_BLOCK, generate::TAG_TUN, generate::TAG_DNS];

/// Names a group or a rule may use besides nodes and groups (mihomo's
/// built-in proxies).
pub const BUILTIN_TARGETS: [&str; 5] = ["DIRECT", "REJECT", "REJECT-DROP", "PASS", "COMPATIBLE"];

/// Which kernel code runs a node.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Implementation {
    /// A sing-box outbound or endpoint.
    Native,
    /// The `mihomo` plugin outbound (mihomo's own proxy map).
    Mihomo,
    /// The `xray` plugin outbound (an Xray outbound object).
    Xray,
}

impl Implementation {
    pub fn as_str(self) -> &'static str {
        match self {
            Implementation::Native => "native",
            Implementation::Mihomo => "mihomo",
            Implementation::Xray => "xray",
        }
    }
}

/// TLS layer of a node, as far as the model needs to know it.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Security {
    None,
    Tls,
    Reality,
}

impl Security {
    pub fn as_str(self) -> &'static str {
        match self {
            Security::None => "none",
            Security::Tls => "tls",
            Security::Reality => "reality",
        }
    }
}

/// What the generator writes for a node (without `tag` and `detour`, which
/// the node holds).
#[derive(Debug, Clone, PartialEq)]
pub enum Spec {
    /// A sing-box outbound object (`type` and its fields).
    Native(Map<String, Value>),
    /// A sing-box endpoint object (WireGuard).
    NativeEndpoint(Map<String, Value>),
    /// A mihomo proxy map (`name`, `type`, …; `dialer-proxy` moved to the node's detour).
    Mihomo(Map<String, Value>),
    /// An Xray outbound object (`protocol`, `settings`, `streamSettings`).
    Xray(Map<String, Value>),
}

/// One proxy node.
#[derive(Debug, Clone, PartialEq)]
pub struct Node {
    /// The outbound tag (the subscription's name for the node).
    pub tag: String,
    /// The protocol as the source named it (`vless`, `ss`, `shadowsocks`, …).
    pub protocol: String,
    /// Server address; empty when the protocol has none in one place (WireGuard peers).
    pub server: String,
    /// Server port; 0 for a port range or none.
    pub port: u16,
    /// Transport (`tcp`, `ws`, `grpc`, `xhttp`, `quic`, …).
    pub transport: String,
    pub security: Security,
    /// Chained proxy: the node this one dials through (mihomo `dialer-proxy`,
    /// sing-box `detour`); always another node.
    pub detour: Option<String>,
    pub spec: Spec,
}

impl Node {
    pub fn implementation(&self) -> Implementation {
        match self.spec {
            Spec::Native(_) | Spec::NativeEndpoint(_) => Implementation::Native,
            Spec::Mihomo(_) => Implementation::Mihomo,
            Spec::Xray(_) => Implementation::Xray,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum GroupKind {
    Select,
    UrlTest,
    Fallback,
    LoadBalance,
}

impl GroupKind {
    pub fn as_str(self) -> &'static str {
        match self {
            GroupKind::Select => "select",
            GroupKind::UrlTest => "url-test",
            GroupKind::Fallback => "fallback",
            GroupKind::LoadBalance => "load-balance",
        }
    }
}

/// A proxy group. Members are resolved at import (explicit members plus
/// `include-all*` filtered as mihomo does); the brain runs the group (stage 5).
#[derive(Debug, Clone, PartialEq, Default)]
pub struct GroupParams {
    /// Health check URL.
    pub url: Option<String>,
    /// Health check interval, seconds.
    pub interval_secs: Option<u64>,
    /// url-test tolerance, milliseconds.
    pub tolerance_ms: Option<u64>,
    /// Health check timeout, milliseconds.
    pub timeout_ms: Option<u64>,
    pub lazy: Option<bool>,
    pub max_failed_times: Option<u64>,
    /// load-balance strategy (`consistent-hashing`, `round-robin`, `sticky-sessions`).
    pub strategy: Option<String>,
    /// mihomo `expected-status` as written.
    pub expected_status: Option<String>,
    /// sing-box selector `default`.
    pub default: Option<String>,
    /// sing-box urltest `idle_timeout`, seconds.
    pub idle_timeout_secs: Option<u64>,
    pub interrupt_exist_connections: Option<bool>,
    pub disable_udp: bool,
    pub hidden: bool,
    pub icon: Option<String>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Group {
    pub name: String,
    pub kind: GroupKind,
    /// Node, group or built-in names, in order.
    pub members: Vec<String>,
    pub params: GroupParams,
}

/// Rules of a subscription: the rule engine's representation (stage 1).
#[derive(Debug, Clone, PartialEq)]
pub struct Rules {
    pub router: RouterIr,
    /// sing-box `sniff` rules; fkbrain sniffs every connection itself.
    pub host: Vec<HostDirective>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum NoticeKind {
    /// Subscription DNS settings were not used ([`IGNORED_DNS`]).
    IgnoredDns,
    /// Settings of the source that the brain's configuration does not take.
    IgnoredSettings,
    /// A node went to its plugin because a field has no native equivalent.
    Downgraded,
    /// A share link that could not be read; the other links were imported.
    SkippedLink,
    /// A group whose members are empty after filtering.
    EmptyGroup,
    /// A host directive (sing-box `sniff`) handled by fkbrain.
    HostDirective,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Notice {
    pub kind: NoticeKind,
    /// What it is about (node, group, key).
    pub at: String,
    pub message: String,
}

impl Notice {
    pub fn new(kind: NoticeKind, at: impl Into<String>, message: impl Into<String>) -> Notice {
        Notice { kind, at: at.into(), message: message.into() }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SourceFormat {
    Links,
    Base64Links,
    Clash,
    SingBox,
}

#[derive(Debug, Clone, PartialEq)]
pub struct ImportResult {
    pub format: SourceFormat,
    pub nodes: Vec<Node>,
    pub groups: Vec<Group>,
    /// `None` when the source has no rules (link lists).
    pub rules: Option<Rules>,
    pub notices: Vec<Notice>,
}

impl ImportResult {
    pub fn node(&self, tag: &str) -> Option<&Node> {
        self.nodes.iter().find(|n| n.tag == tag)
    }

    pub fn group(&self, name: &str) -> Option<&Group> {
        self.groups.iter().find(|g| g.name == name)
    }

    pub fn has_notice(&self, kind: NoticeKind) -> bool {
        self.notices.iter().any(|n| n.kind == kind)
    }
}

/// Where rule providers / rule sets are read from (the engine does no I/O).
pub struct Loaders<'a> {
    pub clash: &'a dyn ProviderLoader,
    pub singbox: &'a dyn RuleSetLoader,
}

fn no_provider(provider: &crate::rules::engine::import_clash::ProviderRef) -> Result<Vec<u8>, String> {
    Err(format!("rule provider {:?} is not available (no loader)", provider.name))
}

fn no_rule_set(set: &crate::rules::engine::import_singbox::RuleSetRef) -> Result<Vec<u8>, String> {
    Err(format!("rule set {:?} is not available (no loader)", set.tag))
}

impl Loaders<'static> {
    /// Loaders that refuse every remote or local file (inline content only).
    pub fn none() -> Loaders<'static> {
        Loaders { clash: &no_provider, singbox: &no_rule_set }
    }
}

/// Detects the format and imports. Order: sing-box JSON (`{`), Clash YAML
/// (a mapping with `proxies`, `proxy-groups`, `proxy-providers` or
/// `rules`), share links, Base64 of share links.
pub fn import(content: &str, loaders: &Loaders) -> Result<ImportResult, ImportError> {
    let trimmed = content.trim_start_matches('\u{feff}').trim();
    if trimmed.is_empty() {
        return Err(ImportError::new("subscription", "empty content"));
    }
    if trimmed.starts_with('{') {
        let config: Value =
            serde_json::from_str(trimmed).map_err(|e| ImportError::new("subscription", format!("invalid JSON: {e}")))?;
        return singbox::import_value(&config, loaders.singbox);
    }
    if let Ok(yaml) = serde_yaml::from_str::<Value>(trimmed) {
        if let Some(map) = yaml.as_object() {
            if ["proxies", "proxy-groups", "proxy-providers", "rules"].iter().any(|k| map.contains_key(*k)) {
                return clash::import_value(&yaml, loaders.clash);
            }
        }
    }
    if trimmed.contains("://") {
        return links::import_list(trimmed, SourceFormat::Links);
    }
    match text::base64_text(trimmed) {
        Some(decoded) if decoded.contains("://") => links::import_list(&decoded, SourceFormat::Base64Links),
        _ => Err(ImportError::new("subscription", "not a sing-box JSON, Clash YAML, share link list or Base64 link list")),
    }
}

/// Checks a finished import: unique tags not reserved, detours naming nodes
/// without cycles, group members and rule targets that exist, no group cycles.
pub fn validate(result: &ImportResult) -> Result<(), ImportError> {
    let mut names: BTreeSet<&str> = BTreeSet::new();
    for node in &result.nodes {
        if node.tag.is_empty() {
            return Err(ImportError::new("node", "empty name"));
        }
        if RESERVED_TAGS.contains(&node.tag.as_str()) {
            return Err(ImportError::new(format!("node {:?}", node.tag), "the name is reserved for the generated configuration"));
        }
        if !names.insert(node.tag.as_str()) {
            return Err(ImportError::new(format!("node {:?}", node.tag), "duplicate name"));
        }
    }
    for group in &result.groups {
        if group.name.is_empty() {
            return Err(ImportError::new("group", "empty name"));
        }
        if RESERVED_TAGS.contains(&group.name.as_str()) {
            return Err(ImportError::new(format!("group {:?}", group.name), "the name is reserved for the generated configuration"));
        }
        if !names.insert(group.name.as_str()) {
            return Err(ImportError::new(format!("group {:?}", group.name), "duplicate name (a node or group has it)"));
        }
    }
    check_detours(result)?;
    let known = |name: &str| names.contains(name) || BUILTIN_TARGETS.contains(&name);
    for group in &result.groups {
        for member in &group.members {
            if !known(member.as_str()) {
                return Err(ImportError::new(format!("group {:?}", group.name), format!("unknown member {member:?}")));
            }
        }
    }
    check_group_cycles(result)?;
    if let Some(rules) = &result.rules {
        let lists = std::iter::once(("rules", &rules.router.rules)).chain(rules.router.sub_rules.iter().map(|(n, r)| (n.as_str(), r)));
        for (list, entries) in lists {
            for rule in entries {
                if let Target::Outbound(name) = &rule.target {
                    if !known(name.as_str()) {
                        return Err(ImportError::new(format!("{list}: {}", rule.source), format!("unknown proxy or group {name:?}")));
                    }
                }
            }
        }
        if let Some(Target::Outbound(name)) = &rules.router.final_target {
            if !known(name.as_str()) {
                return Err(ImportError::new("final", format!("unknown proxy or group {name:?}")));
            }
        }
    }
    Ok(())
}

/// Detours must name nodes (a group cannot be a sing-box `detour` before the
/// brain runs groups, stage 5) and must not form a cycle.
pub fn check_detours(result: &ImportResult) -> Result<(), ImportError> {
    let by_tag: BTreeMap<&str, &Node> = result.nodes.iter().map(|n| (n.tag.as_str(), n)).collect();
    for node in &result.nodes {
        if let Some(detour) = &node.detour {
            if by_tag.contains_key(detour.as_str()) {
                continue;
            }
            let at = format!("node {:?}", node.tag);
            if result.groups.iter().any(|g| &g.name == detour) {
                return Err(ImportError::new(
                    at,
                    format!("dialer-proxy/detour {detour:?} is a group; a chained proxy has to name a node (groups as detours need the brain's groups, stage 5)"),
                ));
            }
            if detour == "DIRECT" {
                return Err(ImportError::new(at, "dialer-proxy DIRECT is not supported; remove it to dial directly"));
            }
            return Err(ImportError::new(at, format!("dialer-proxy/detour {detour:?} names no node")));
        }
    }
    for node in &result.nodes {
        let mut seen = vec![node.tag.as_str()];
        let mut current = node;
        while let Some(next) = current.detour.as_deref() {
            if seen.contains(&next) {
                seen.push(next);
                return Err(ImportError::new(format!("node {:?}", node.tag), format!("chained proxy loop: {}", seen.join(" -> "))));
            }
            seen.push(next);
            current = match by_tag.get(next) {
                Some(n) => *n,
                None => break,
            };
        }
    }
    Ok(())
}

fn check_group_cycles(result: &ImportResult) -> Result<(), ImportError> {
    let by_name: BTreeMap<&str, &Group> = result.groups.iter().map(|g| (g.name.as_str(), g)).collect();
    // 0 = unvisited, 1 = on the stack, 2 = done.
    let mut state: BTreeMap<&str, u8> = BTreeMap::new();
    fn visit<'a>(
        name: &'a str,
        by_name: &BTreeMap<&'a str, &'a Group>,
        state: &mut BTreeMap<&'a str, u8>,
        path: &mut Vec<&'a str>,
    ) -> Result<(), ImportError> {
        match state.get(name).copied().unwrap_or(0) {
            2 => return Ok(()),
            1 => {
                path.push(name);
                return Err(ImportError::new(format!("group {name:?}"), format!("group loop: {}", path.join(" -> "))));
            }
            _ => {}
        }
        state.insert(name, 1);
        path.push(name);
        if let Some(group) = by_name.get(name).copied() {
            for member in &group.members {
                if by_name.contains_key(member.as_str()) {
                    visit(member.as_str(), by_name, state, path)?;
                }
            }
        }
        path.pop();
        state.insert(name, 2);
        Ok(())
    }
    for group in &result.groups {
        let mut path = Vec::new();
        visit(group.name.as_str(), &by_name, &mut state, &mut path)?;
    }
    Ok(())
}
