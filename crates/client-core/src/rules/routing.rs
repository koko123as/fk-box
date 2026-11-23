//! Per-core routing rules (Settings › Traffic routing): one independent rule
//! set for each core, kept in the service settings (`routing_rules`, JSON
//! text) and in backups.
//!
//! This module owns the data model, its validation and its serialization.
//! Compiling a set into a core's own routing syntax stays in the kernel layer
//! (libbox `core_routing*.go`, `CommandServer.startOrReloadCoreServiceWithRouting`,
//! `Libbox.applyCoreRouting`): the platform hands it [`core_json`] for the
//! core that runs. The model follows Lemonochka/keqdroid's TrafficRoute /
//! RoutingProfile (content, target, apps, network, ports, order); the field
//! names are the JSON libbox reads.
//!
//! The stored text is the one the Kotlin `CoreRouting` wrote (every field
//! written, fields in declaration order), so a store written here reads the
//! same in an older release.

use std::collections::hash_map::RandomState;
use std::hash::{BuildHasher, Hasher};
use std::sync::atomic::{AtomicU64, Ordering};

use serde::de::{self, MapAccess, Visitor};
use serde::{Deserialize, Deserializer, Serialize, Serializer};
use serde_json::Value;

use crate::storage::{SettingValue, Store};

/// The service setting the rules are stored under (`SettingsKey.ROUTING_RULES`).
pub const SETTING_KEY: &str = "routing_rules";

pub const VERSION: i64 = 1;
pub const CORES: [&str; 3] = ["sing-box", "xray", "mihomo"];
pub const MODES: [&str; 3] = ["prepend", "override", "off"];
pub const CONTENTS: [&str; 4] = ["all", "match", "preset", "list"];
pub const TARGETS: [&str; 4] = ["proxy", "direct", "block", "node"];
pub const FINALS: [&str; 5] = ["config", "proxy", "direct", "block", "node"];
pub const NETWORKS: [&str; 3] = ["any", "tcp", "udp"];

pub const MAX_RULES: usize = 500;
pub const MAX_VALUES: usize = 5000;
pub const MAX_TEXT: usize = 2048;
pub const MAX_APPS: usize = 1000;
pub const MAX_PORT_RANGES: usize = 28;

/// A preset: what libbox expands it to (`core_routing.go` routingPresets),
/// its title and description (English source strings, translated by the UI).
pub struct Preset {
    pub id: &'static str,
    pub title: &'static str,
    pub description: &'static str,
}

pub const PRESETS: [Preset; 4] = [
    Preset { id: "cn-direct", title: "Bypass mainland China", description: "geosite:cn, geoip:cn and private addresses go direct" },
    Preset {
        id: "gfw-proxy",
        title: "GFW list through proxy, the rest direct",
        description: "geosite:gfw through the proxy; all other traffic direct",
    },
    Preset { id: "ads-block", title: "Block ads", description: "geosite:category-ads-all is blocked" },
    Preset { id: "lan-direct", title: "LAN direct", description: "Private and LAN addresses go direct" },
];

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct Target {
    #[serde(rename = "type")]
    pub kind: String,
    pub tag: String,
}

impl Target {
    pub fn new(kind: &str) -> Target {
        Target { kind: kind.into(), tag: String::new() }
    }
}

impl Default for Target {
    fn default() -> Target {
        Target::new("proxy")
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct Rule {
    pub id: String,
    pub enabled: bool,
    pub name: String,
    pub content: String,
    pub values: Vec<String>,
    pub preset: String,
    pub url: String,
    pub target: Target,
    pub apps: Vec<String>,
    pub network: String,
    pub ports: String,
}

impl Default for Rule {
    /// A new rule, with a fresh id (as the Kotlin model gave one to a rule
    /// read without an id).
    fn default() -> Rule {
        Rule {
            id: new_id(),
            enabled: true,
            name: String::new(),
            content: "match".into(),
            values: Vec::new(),
            preset: String::new(),
            url: String::new(),
            target: Target::default(),
            apps: Vec::new(),
            network: "any".into(),
            ports: String::new(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct CoreSet {
    pub mode: String,
    #[serde(rename = "final")]
    pub fallback: Target,
    pub rules: Vec<Rule>,
}

impl Default for CoreSet {
    fn default() -> CoreSet {
        CoreSet { mode: "prepend".into(), fallback: Target::new("config"), rules: Vec::new() }
    }
}

/// The cores' sets in the order they were added (a JSON object).
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Cores(pub Vec<(String, CoreSet)>);

impl Serialize for Cores {
    fn serialize<S: Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        serializer.collect_map(self.0.iter().map(|(k, v)| (k, v)))
    }
}

impl<'de> Deserialize<'de> for Cores {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Cores, D::Error> {
        struct CoresVisitor;
        impl<'de> Visitor<'de> for CoresVisitor {
            type Value = Cores;
            fn expecting(&self, f: &mut std::fmt::Formatter) -> std::fmt::Result {
                f.write_str("an object of core rule sets")
            }
            fn visit_map<A: MapAccess<'de>>(self, mut map: A) -> Result<Cores, A::Error> {
                let mut out: Vec<(String, CoreSet)> = Vec::new();
                while let Some((key, value)) = map.next_entry::<String, CoreSet>()? {
                    match out.iter_mut().find(|(k, _)| *k == key) {
                        Some(slot) => slot.1 = value,
                        None => out.push((key, value)),
                    }
                }
                Ok(Cores(out))
            }
        }
        deserializer.deserialize_map(CoresVisitor)
    }
}

/// The version accepts an integral number in any JSON form (`1`, `1.0`), as
/// the Kotlin wire reader did for backups read through org.json.
fn version<'de, D: Deserializer<'de>>(deserializer: D) -> Result<i64, D::Error> {
    let value = Value::deserialize(deserializer)?;
    value
        .as_i64()
        .or_else(|| value.as_f64().filter(|v| v.fract() == 0.0 && v.abs() < 9.0e15).map(|v| v as i64))
        .ok_or_else(|| de::Error::custom(format!("invalid version: {value}")))
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct Rules {
    #[serde(deserialize_with = "version")]
    pub version: i64,
    pub cores: Cores,
}

impl Default for Rules {
    fn default() -> Rules {
        Rules { version: VERSION, cores: Cores::default() }
    }
}

impl Rules {
    pub fn core(&self, core: &str) -> CoreSet {
        self.cores.0.iter().find(|(k, _)| k == core).map(|(_, v)| v.clone()).unwrap_or_default()
    }

    pub fn with(mut self, core: &str, set: CoreSet) -> Rules {
        match self.cores.0.iter_mut().find(|(k, _)| k == core) {
            Some(slot) => slot.1 = set,
            None => self.cores.0.push((core.into(), set)),
        }
        self
    }
}

/// A rule id: a random version-4 UUID (process-random hasher keys, the clock
/// and a counter; no RNG crate needed).
pub fn new_id() -> String {
    static COUNTER: AtomicU64 = AtomicU64::new(0);
    let n = COUNTER.fetch_add(1, Ordering::Relaxed);
    let nanos = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_nanos() as u64).unwrap_or(0);
    let mut bytes = [0u8; 16];
    for (half, chunk) in bytes.chunks_mut(8).enumerate() {
        let mut hasher = RandomState::new().build_hasher();
        hasher.write_u64(nanos);
        hasher.write_u64(n);
        hasher.write_usize(half);
        chunk.copy_from_slice(&hasher.finish().to_le_bytes());
    }
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    let h: String = bytes.iter().map(|b| format!("{b:02x}")).collect();
    format!("{}-{}-{}-{}-{}", &h[0..8], &h[8..12], &h[12..16], &h[16..20], &h[20..32])
}

/// The rules a preset adds.
pub fn preset_rules(id: &str) -> Vec<Rule> {
    let rule = |name: &str, content: &str, preset: &str, target: &str| Rule {
        name: name.into(),
        content: content.into(),
        preset: preset.into(),
        target: Target::new(target),
        ..Rule::default()
    };
    match id {
        "cn-direct" => vec![rule("Bypass mainland China", "preset", "cn-direct", "direct")],
        "gfw-proxy" => vec![rule("GFW list", "preset", "gfw-proxy", "proxy"), rule("Everything else", "all", "", "direct")],
        "ads-block" => vec![rule("Block ads", "preset", "ads-block", "block")],
        "lan-direct" => vec![rule("LAN direct", "preset", "lan-direct", "direct")],
        _ => Vec::new(),
    }
}

/// Length in UTF-16 units, the measure the Kotlin limits used.
fn len16(text: &str) -> usize {
    text.encode_utf16().count()
}

fn blank(text: &str) -> bool {
    text.trim().is_empty()
}

fn require(condition: bool, message: impl FnOnce() -> String) -> Result<(), String> {
    if condition {
        Ok(())
    } else {
        Err(message())
    }
}

const FAILED: fn() -> String = || "Failed requirement.".to_string();

/// Java's `\s`: space, tab, newline, vertical tab, form feed, carriage return.
fn java_space(c: char) -> bool {
    matches!(c, ' ' | '\t' | '\n' | '\u{0b}' | '\u{0c}' | '\r')
}

/// The shape check of a ports field: numbers of up to five digits, single
/// ranges `a-b`, separated by a comma or a space (with any whitespace
/// around). The same strings as the Kotlin pattern
/// `^\s*\d{1,5}(\s*-\s*\d{1,5})?(\s*[, ]\s*\d{1,5}(\s*-\s*\d{1,5})?)*\s*$`.
fn ports_shape(text: &str) -> bool {
    // Runs of digits and of everything else.
    let mut runs: Vec<(bool, &str)> = Vec::new();
    let mut start = 0;
    let mut digits: Option<bool> = None;
    for (i, c) in text.char_indices() {
        let d = c.is_ascii_digit();
        if digits.is_some_and(|prev| prev != d) {
            runs.push((digits.unwrap_or(false), &text[start..i]));
            start = i;
        }
        digits = Some(d);
    }
    if let Some(d) = digits {
        runs.push((d, &text[start..]));
    }
    let mut runs = runs.as_slice();
    if runs.first().is_some_and(|r| !r.0) {
        if !runs[0].1.chars().all(java_space) {
            return false;
        }
        runs = &runs[1..];
    }
    if runs.last().is_some_and(|r| !r.0) {
        if !runs[runs.len() - 1].1.chars().all(java_space) {
            return false;
        }
        runs = &runs[..runs.len() - 1];
    }
    if runs.is_empty() {
        return false;
    }
    let mut after_dash = false;
    for (index, (is_digits, run)) in runs.iter().enumerate() {
        if index % 2 == 0 {
            if !is_digits || run.len() > 5 {
                return false;
            }
            continue;
        }
        let commas = run.chars().filter(|&c| c == ',').count();
        let dashes = run.chars().filter(|&c| c == '-').count();
        if !run.chars().all(|c| java_space(c) || c == ',' || c == '-') {
            return false;
        }
        if dashes == 1 && commas == 0 && !after_dash {
            after_dash = true;
        } else if dashes == 0 && (commas == 1 || (commas == 0 && run.contains(' '))) {
            after_dash = false;
        } else {
            return false;
        }
    }
    true
}

/// A ports field: empty, or up to [`MAX_PORT_RANGES`] ports and ranges of
/// 1–65535 separated by commas or spaces.
pub fn ports_valid(text: &str) -> bool {
    if blank(text) {
        return true;
    }
    if !ports_shape(text) {
        return false;
    }
    let ranges: Vec<&str> = text.split([',', ' ']).filter(|p| !blank(p)).collect();
    if ranges.len() > MAX_PORT_RANGES {
        return false;
    }
    ranges.iter().all(|part| {
        let mut bounds = Vec::new();
        for bound in part.split('-') {
            match bound.trim().parse::<i64>() {
                Ok(v) => bounds.push(v),
                Err(_) => return false,
            }
        }
        bounds.iter().all(|v| (1..=65535).contains(v)) && (bounds.len() == 1 || bounds[0] <= bounds[1])
    })
}

/// An Android package name as a rule may list it.
pub fn package_valid(name: &str) -> bool {
    len16(name) < 256
        && !name.is_empty()
        && name.split('.').all(|part| !part.is_empty() && part.chars().all(|c| c.is_ascii_alphanumeric() || c == '_'))
}

/// A rule list address.
pub fn url_valid(url: &str) -> bool {
    len16(url) <= MAX_TEXT && (url.starts_with("https://") || url.starts_with("http://")) && len16(url) > 8
}

/// Apps, a network or ports: what a rule can match by without destination entries.
pub fn has_conditions(rule: &Rule) -> bool {
    rule.apps.iter().any(|a| !blank(a)) || (rule.network != "any" && !blank(&rule.network)) || !blank(&rule.ports)
}

/// Splits what the user typed into entries: one per line, or several on a
/// line separated by commas or spaces. A `regexp:` line is one entry as it
/// is, since a pattern may contain commas and spaces (`a{1,3}`).
pub fn split_values(text: &str) -> Vec<String> {
    text.lines()
        .flat_map(|line| {
            let trimmed = line.trim();
            if trimmed.get(..7).is_some_and(|p| p.eq_ignore_ascii_case("regexp:")) {
                vec![trimmed.to_string()]
            } else {
                trimmed.split([',', ' ', '\t']).map(str::to_string).collect()
            }
        })
        .map(|v| v.trim().to_string())
        .filter(|v| !v.is_empty())
        .collect()
}

/// Checks one rule's limits and choices.
pub fn validate_rule(rule: &Rule) -> Result<(), String> {
    require(!blank(&rule.id) && len16(&rule.id) <= 64, || "Invalid routing rule id".into())?;
    require(len16(&rule.name) <= 256, FAILED)?;
    require(CONTENTS.contains(&rule.content.as_str()), || format!("Unknown rule content: {}", rule.content))?;
    require(TARGETS.contains(&rule.target.kind.as_str()), || format!("Unknown rule target: {}", rule.target.kind))?;
    require(rule.target.kind != "node" || !blank(&rule.target.tag), || "Choose a node or group".into())?;
    require(len16(&rule.target.tag) <= 256, FAILED)?;
    require(rule.values.len() <= MAX_VALUES && rule.values.iter().all(|v| len16(v) <= MAX_TEXT), || {
        "Too many or too long entries".into()
    })?;
    // An empty "match" with apps, a network or ports matches by those alone (an app-only rule).
    require(rule.content != "match" || rule.values.iter().any(|v| !blank(v)) || has_conditions(rule), || {
        "Add a domain, IP or geo entry, or choose apps, a network or ports".into()
    })?;
    require(rule.content != "preset" || PRESETS.iter().any(|p| p.id == rule.preset), || format!("Unknown preset: {}", rule.preset))?;
    require(rule.content != "list" || url_valid(&rule.url), || "Enter an http(s) rule list URL".into())?;
    require(rule.apps.len() <= MAX_APPS && rule.apps.iter().all(|a| package_valid(a)), || "Invalid package name".into())?;
    require(NETWORKS.contains(&rule.network.as_str()), || format!("Unknown network: {}", rule.network))?;
    require(ports_valid(&rule.ports), || "Ports are 1–65535, as 443 or 8000-9000, separated by commas".into())
}

/// What a core cannot express, checked on top of [`validate_rule`] when the
/// UI edits that core's set (Xray has no per-app routing).
pub fn validate_rule_for_core(rule: &Rule, core: &str) -> Result<(), String> {
    validate_rule(rule)?;
    require(core != "xray" || rule.apps.iter().all(|a| blank(a)), || {
        "Xray cannot route by app. Remove the apps, or use the sing-box or mihomo core (or Access control).".into()
    })
}

/// Checks the limits and choices of a whole rule set.
pub fn validate(rules: &Rules) -> Result<(), String> {
    require(rules.version == VERSION, || format!("Unsupported routing rules version {}", rules.version))?;
    for (core, set) in &rules.cores.0 {
        require(CORES.contains(&core.as_str()), || format!("Unknown core in routing rules: {core}"))?;
        require(MODES.contains(&set.mode.as_str()), || format!("Unknown routing mode: {}", set.mode))?;
        require(FINALS.contains(&set.fallback.kind.as_str()), || format!("Unknown fallback: {}", set.fallback.kind))?;
        require(set.fallback.kind != "node" || !blank(&set.fallback.tag), || "Choose the fallback node".into())?;
        require(len16(&set.fallback.tag) <= 256, FAILED)?;
        require(set.rules.len() <= MAX_RULES, || format!("At most {MAX_RULES} rules per core"))?;
        let mut ids: Vec<&str> = set.rules.iter().map(|r| r.id.as_str()).collect();
        ids.sort_unstable();
        ids.dedup();
        require(ids.len() == set.rules.len(), || "Duplicate routing rule id".into())?;
        set.rules.iter().try_for_each(validate_rule)?;
    }
    Ok(())
}

/// The stored text (`""` means no rules) read and checked.
pub fn decode(text: &str) -> Result<Rules, String> {
    if blank(text) {
        return Ok(Rules::default());
    }
    let rules: Rules = serde_json::from_str(text).map_err(|e| format!("Invalid routing rules: {e}"))?;
    validate(&rules)?;
    Ok(rules)
}

/// The stored text of checked rules.
pub fn encode(rules: &Rules) -> Result<String, String> {
    validate(rules)?;
    serde_json::to_string(rules).map_err(|e| e.to_string())
}

/// The wire form (bridge payloads, backups, the UI's draft) read and checked.
pub fn from_wire(value: &Value) -> Result<Rules, String> {
    if !value.is_object() {
        return Err("Invalid routing rules".into());
    }
    let rules: Rules = serde_json::from_value(value.clone()).map_err(|e| format!("Invalid routing rules: {e}"))?;
    validate(&rules)?;
    Ok(rules)
}

/// The wire form: every field written.
pub fn to_wire(rules: &Rules) -> Value {
    serde_json::to_value(rules).unwrap_or(Value::Null)
}

/// The JSON the kernel layer compiles for `core` (`""` when the core has no rules).
pub fn core_json(rules: &Rules, core: &str) -> String {
    match rules.cores.0.iter().find(|(k, _)| k == core) {
        Some((_, set)) => serde_json::to_string(set).unwrap_or_default(),
        None => String::new(),
    }
}

/// The saved rules' text.
pub fn stored_text(store: &Store) -> Result<String, String> {
    Ok(match store.setting(SETTING_KEY).map_err(|e| e.0)? {
        Some(SettingValue::String(text)) => text,
        _ => String::new(),
    })
}

/// The saved rules; unreadable ones read as none (as the settings page showed them).
pub fn load(store: &Store) -> Rules {
    stored_text(store).ok().and_then(|text| decode(&text).ok()).unwrap_or_default()
}

/// The rule set the kernel layer compiles for `core` now; an error when the
/// saved rules cannot be read (the service then starts without them and
/// says why).
pub fn saved_core_json(store: &Store, core: &str) -> Result<String, String> {
    Ok(core_json(&decode(&stored_text(store)?)?, core))
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn rule(id: &str) -> Rule {
        Rule { id: id.into(), values: vec!["example.com".into()], ..Rule::default() }
    }

    fn sample() -> Rules {
        let rules = vec![
            Rule { id: "a".into(), name: "ads".into(), content: "preset".into(), preset: "ads-block".into(), target: Target::new("block"), ..Rule::default() },
            Rule {
                id: "b".into(),
                values: vec!["example.com".into(), "geosite:cn".into()],
                target: Target { kind: "node".into(), tag: "HK".into() },
                network: "tcp".into(),
                ports: "443, 8000-9000".into(),
                apps: vec!["com.example.app".into()],
                ..Rule::default()
            },
            Rule {
                id: "c".into(),
                enabled: false,
                content: "list".into(),
                url: "https://lists.example.test/ads.txt".into(),
                target: Target::new("direct"),
                ..Rule::default()
            },
        ];
        Rules::default().with("sing-box", CoreSet { mode: "prepend".into(), fallback: Target::new("direct"), rules })
    }

    #[test]
    fn encodes_decodes_and_keeps_cores_independent() {
        let rules = sample();
        let decoded = decode(&encode(&rules).unwrap()).unwrap();
        assert_eq!(rules, decoded);
        assert_eq!(CoreSet::default(), decoded.core("xray"));
        assert_eq!("", core_json(&decoded, "mihomo"));
        let set: Value = serde_json::from_str(&core_json(&decoded, "sing-box")).unwrap();
        assert_eq!(set["mode"], json!("prepend"));
        assert_eq!(set["final"]["type"], json!("direct"));
        assert_eq!(set["rules"][1]["target"], json!({"type": "node", "tag": "HK"}));
        assert_eq!(set["rules"][1]["network"], json!("tcp"));
        assert_eq!(set["rules"][1]["enabled"], json!(true));
        assert_eq!(Rules::default(), decode("").unwrap());
        assert_eq!(Rules::default(), decode("  ").unwrap());
    }

    #[test]
    fn stored_text_matches_what_kotlin_wrote() {
        // CoreRouting.encode (kotlinx, encodeDefaults) of a one-rule set.
        let kotlin = r#"{"version":1,"cores":{"mihomo":{"mode":"override","final":{"type":"config","tag":""},"rules":[{"id":"r1","enabled":true,"name":"","content":"all","values":[],"preset":"","url":"","target":{"type":"direct","tag":""},"apps":["com.tencent.mm"],"network":"any","ports":""}]}}}"#;
        let rules = decode(kotlin).unwrap();
        assert_eq!(rules.core("mihomo").rules[0].apps, ["com.tencent.mm"]);
        assert_eq!(encode(&rules).unwrap(), kotlin);
        // Cores keep their order.
        let two = sample().with("xray", CoreSet::default()).with("mihomo", CoreSet::default());
        let text = encode(&two).unwrap();
        assert!(text.find("\"sing-box\"").unwrap() < text.find("\"xray\"").unwrap());
        assert!(text.find("\"xray\"").unwrap() < text.find("\"mihomo\"").unwrap());
    }

    #[test]
    fn wire_form_round_trips_through_backups() {
        let rules = sample();
        let wire = to_wire(&rules);
        assert_eq!(rules, from_wire(&wire).unwrap());
        // A backup read through org.json may carry the version as 1.0.
        let mut restored = wire.clone();
        restored["version"] = json!(1.0);
        assert_eq!(rules, from_wire(&restored).unwrap());
        // Missing fields take their defaults; a rule without an id gets one.
        let sparse = json!({"cores": {"xray": {"rules": [{"values": ["a.com"]}]}}});
        let read = from_wire(&sparse).unwrap();
        let set = read.core("xray");
        assert_eq!((set.mode.as_str(), set.fallback.kind.as_str()), ("prepend", "config"));
        assert_eq!(set.rules[0].id.len(), 36);
        assert!(from_wire(&json!("text")).is_err());
        assert_eq!(from_wire(&json!({"version": 9})).unwrap_err(), "Unsupported routing rules version 9");
        assert!(from_wire(&json!({"version": 1.5})).is_err());
    }

    #[test]
    fn invalid_rules_are_rejected() {
        let valid = rule("x");
        validate_rule(&valid).unwrap();
        let invalid = [
            Rule { id: " ".into(), ..valid.clone() },
            Rule { target: Target { kind: "node".into(), tag: String::new() }, ..valid.clone() },
            Rule { target: Target::new("teleport"), ..valid.clone() },
            Rule { content: "geo".into(), ..valid.clone() },
            Rule { values: vec![], ..valid.clone() },
            Rule { content: "preset".into(), preset: "nope".into(), ..valid.clone() },
            Rule { content: "list".into(), url: "ftp://x.example/list".into(), ..valid.clone() },
            Rule { apps: vec!["not a package!".into()], ..valid.clone() },
            Rule { network: "sctp".into(), ..valid.clone() },
            Rule { ports: "0".into(), ..valid.clone() },
            Rule { ports: "9000-8000".into(), ..valid.clone() },
            Rule { values: vec!["x".repeat(MAX_TEXT + 1)], ..valid.clone() },
        ];
        for r in &invalid {
            assert!(validate_rule(r).is_err(), "{r:?}");
        }
        let merge = Rules::default().with("sing-box", CoreSet { mode: "merge".into(), ..CoreSet::default() });
        assert_eq!(validate(&merge).unwrap_err(), "Unknown routing mode: merge");
        let dup = Rules::default().with("sing-box", CoreSet { rules: vec![valid.clone(), valid.clone()], ..CoreSet::default() });
        assert_eq!(validate(&dup).unwrap_err(), "Duplicate routing rule id");
        let unknown = Rules::default().with("v2ray", CoreSet::default());
        assert_eq!(validate(&unknown).unwrap_err(), "Unknown core in routing rules: v2ray");
        let node = Rules::default().with("xray", CoreSet { fallback: Target::new("node"), ..CoreSet::default() });
        assert_eq!(validate(&node).unwrap_err(), "Choose the fallback node");
    }

    #[test]
    fn ports_follow_the_kotlin_pattern() {
        for ok in ["", "  ", "443", "443, 8000-9000", "80 443", "80,443", " 1 ,3 ", "1\t-\t2", "1 ,\t2", "65535"] {
            assert!(ports_valid(ok), "{ok:?}");
        }
        let many = (1..=29).map(|n| n.to_string()).collect::<Vec<_>>().join(",");
        let max = (1..=28).map(|n| n.to_string()).collect::<Vec<_>>().join(",");
        assert!(ports_valid(&max));
        // "1 - 2" passes the pattern but a space splits the range (as in Kotlin).
        for bad in [many.as_str(), "65536", "123456", "1-2-3", "1;2", "1\t2", "1,,2", "a", "-1", "1-", "1 - 2", "0-5"] {
            assert!(!ports_valid(bad), "{bad:?}");
        }
    }

    #[test]
    fn split_values_keeps_regexp_lines_whole() {
        assert_eq!(split_values(" a.com\nb.com, geosite:cn\t1.2.3.0/24 \n"), ["a.com", "b.com", "geosite:cn", "1.2.3.0/24"]);
        assert_eq!(split_values("a.com\n  regexp:^a{1,3}\\.b, c$ \nd.com"), ["a.com", "regexp:^a{1,3}\\.b, c$", "d.com"]);
        assert_eq!(split_values("REGEXP:x y"), ["REGEXP:x y"]);
    }

    #[test]
    fn presets_are_valid_and_app_only_rules_are_allowed() {
        let gfw = preset_rules("gfw-proxy");
        assert_eq!(gfw.len(), 2);
        assert_eq!((gfw[1].content.as_str(), gfw[1].target.kind.as_str()), ("all", "direct"));
        for preset in &PRESETS {
            preset_rules(preset.id).iter().try_for_each(validate_rule).unwrap();
        }
        let app = Rule { content: "all".into(), apps: vec!["com.tencent.mm".into()], target: Target::new("direct"), ..Rule::default() };
        validate_rule(&app).unwrap();
        validate_rule(&Rule { content: "match".into(), ..app.clone() }).unwrap();
        validate_rule(&Rule { network: "udp".into(), apps: vec![], content: "match".into(), ..app.clone() }).unwrap();
        validate_rule(&Rule { ports: "443".into(), apps: vec![], content: "match".into(), ..app.clone() }).unwrap();
        assert!(validate_rule(&Rule { values: vec![" ".into()], apps: vec![], content: "match".into(), ..app.clone() }).is_err());
        assert!(validate_rule_for_core(&app, "xray").unwrap_err().contains("Xray"));
        validate_rule_for_core(&app, "mihomo").unwrap();
        let ids: std::collections::HashSet<String> = (0..200).map(|_| new_id()).collect();
        assert_eq!(ids.len(), 200);
    }

    #[test]
    fn saved_rules_come_from_the_store() {
        let store = Store::open_in_memory().unwrap();
        assert_eq!(load(&store), Rules::default());
        assert_eq!(saved_core_json(&store, "xray").unwrap(), "");
        store.put_setting(SETTING_KEY, &SettingValue::String(encode(&sample()).unwrap())).unwrap();
        assert_eq!(load(&store), sample());
        assert!(saved_core_json(&store, "sing-box").unwrap().contains("\"HK\""));
        store.put_setting(SETTING_KEY, &SettingValue::String("{\"version\":2}".into())).unwrap();
        assert_eq!(load(&store), Rules::default());
        assert!(saved_core_json(&store, "sing-box").is_err());
    }
}
