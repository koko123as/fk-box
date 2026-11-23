//! Clash/mihomo rules into the unified representation: `rules`, `sub-rules`
//! and the `rule-providers` they reference (inline payloads, or files the
//! caller's loader returns). Rule lines are split the way mihomo's
//! `ParseRulePayload` splits them, and logic payloads (`AND`/`OR`/`NOT`/
//! `SUB-RULE`) as its `parsePayload` does. A rule type, parameter or field
//! that cannot be expressed is an error naming it, never dropped.

use std::collections::{BTreeMap, BTreeSet};

use serde_json::Value;

use super::domain::{label_wildcard_regex, normalize_value};
use super::ip::IpNet;
use super::ir::{Behavior, Cond, ImportError, IpSide, MissPolicy, Network, Ranges, RouterIr, Rule, RuleSet, Target};
use super::ruleset::{read_mrs, text_lines, yaml_lines, MrsContent};

#[derive(Debug, Clone, Copy)]
pub struct ClashOptions {
    /// What a DNS cache miss does for IP rules without `no-resolve`.
    pub miss: MissPolicy,
}

impl Default for ClashOptions {
    fn default() -> ClashOptions {
        ClashOptions { miss: MissPolicy::Skip }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ProviderFormat {
    Yaml,
    Text,
    Mrs,
}

/// A rule provider whose content the caller supplies (`type: http|file`).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProviderRef {
    pub name: String,
    pub kind: String,
    pub behavior: Behavior,
    pub format: ProviderFormat,
    pub path: Option<String>,
    pub url: Option<String>,
}

/// Reads the bytes of a provider: the downloaded or local file. The engine
/// does no I/O of its own.
pub trait ProviderLoader {
    fn load(&self, provider: &ProviderRef) -> Result<Vec<u8>, String>;
}

impl<F: Fn(&ProviderRef) -> Result<Vec<u8>, String>> ProviderLoader for F {
    fn load(&self, provider: &ProviderRef) -> Result<Vec<u8>, String> {
        self(provider)
    }
}

/// Rule types mihomo has and this engine does not express (yet), with why.
const UNSUPPORTED: [(&str, &str); 16] = [
    ("IP-ASN", "ASN data (ASN.mmdb) is not read by the engine"),
    ("SRC-IP-ASN", "ASN data (ASN.mmdb) is not read by the engine"),
    ("IP-SUFFIX", "IP suffix matching is not implemented"),
    ("SRC-IP-SUFFIX", "IP suffix matching is not implemented"),
    ("IN-PORT", "the inbound port is not in the connection metadata"),
    ("IN-TYPE", "the inbound type is not in the connection metadata"),
    ("IN-USER", "the inbound user is not in the connection metadata"),
    ("DSCP", "DSCP is not in the connection metadata"),
    ("PROCESS-PATH", "the process path is not in the connection metadata"),
    ("PROCESS-PATH-REGEX", "the process path is not in the connection metadata"),
    ("PROCESS-PATH-WILDCARD", "the process path is not in the connection metadata"),
    ("PROCESS-NAME-REGEX", "process name regexes are not implemented"),
    ("PROCESS-NAME-WILDCARD", "process name wildcards are not implemented"),
    ("REMATCH-NAME", "re-matching after a proxy is not part of the decision"),
    ("NETWORK-TYPE", "the network type is not in the connection metadata"),
    ("SCRIPT", "script rules are not supported"),
];

struct Parts {
    tp: String,
    payload: String,
    target: String,
    params: Vec<String>,
}

/// mihomo's `ParseRulePayload`.
fn split(raw: &str, need_target: bool) -> Parts {
    let mut item: Vec<String> = raw.split(',').map(|s| s.trim().to_string()).collect();
    let tp = item[0].to_ascii_uppercase();
    let mut parts = Parts { tp, payload: String::new(), target: String::new(), params: Vec::new() };
    if item.len() > 1 {
        match parts.tp.as_str() {
            "MATCH" => parts.target = item[1].clone(),
            "NOT" | "OR" | "AND" | "SUB-RULE" | "DOMAIN-REGEX" | "PROCESS-NAME-REGEX" | "PROCESS-PATH-REGEX" => {
                if need_target {
                    parts.target = item.pop().unwrap_or_default();
                }
                parts.payload = item[1..].join(",");
            }
            _ => {
                parts.payload = item[1].clone();
                if item.len() > 2 {
                    if need_target {
                        parts.target = item[2].clone();
                        parts.params = item[3..].to_vec();
                    } else {
                        parts.params = item[2..].to_vec();
                    }
                }
            }
        }
    }
    parts
}

/// The top-level parenthesized groups of a logic payload `(…)`.
fn logic_children(payload: &str) -> Result<Vec<&str>, String> {
    let p = payload.trim();
    if !p.starts_with('(') || !p.ends_with(')') || p.len() < 2 {
        return Err("logic payload must be written as (…)".into());
    }
    let inner = &p[1..p.len() - 1];
    let mut out = Vec::new();
    let (mut depth, mut start) = (0usize, 0usize);
    for (i, c) in inner.char_indices() {
        match c {
            '(' => {
                if depth == 0 {
                    start = i + 1;
                }
                depth += 1;
            }
            ')' => {
                if depth == 0 {
                    return Err("missing '('".into());
                }
                depth -= 1;
                if depth == 0 {
                    out.push(&inner[start..i]);
                }
            }
            _ => {}
        }
    }
    if depth != 0 {
        return Err("missing ')'".into());
    }
    Ok(out)
}

fn ip_side(params: &[String], opts: &ClashOptions) -> IpSide {
    if params.iter().any(|p| p == "src") {
        IpSide::Src
    } else if params.iter().any(|p| p == "no-resolve") {
        IpSide::DST_NO_RESOLVE
    } else {
        IpSide::Dst { resolve: Some(opts.miss) }
    }
}

fn check_params(params: &[String]) -> Result<(), String> {
    match params.iter().find(|p| !matches!(p.as_str(), "no-resolve" | "src" | "")) {
        Some(p) => Err(format!("unknown rule parameter {p:?}")),
        None => Ok(()),
    }
}

/// One condition (`TYPE,payload(,params)`), as in a classical provider or a logic payload.
pub fn parse_cond(raw: &str, opts: &ClashOptions) -> Result<Cond, String> {
    let parts = split(raw, false);
    match parts.tp.as_str() {
        "MATCH" | "SUB-RULE" => Err(format!("{} cannot be used inside a logic rule or a rule provider", parts.tp)),
        "" => Err(format!("{raw:?} has no rule type")),
        _ => cond_of(&parts, opts),
    }
}

fn cond_of(parts: &Parts, opts: &ClashOptions) -> Result<Cond, String> {
    let payload = parts.payload.as_str();
    if parts.tp != "MATCH" && payload.is_empty() {
        return Err(format!("{}: missing payload", parts.tp));
    }
    check_params(&parts.params)?;
    let side = || ip_side(&parts.params, opts);
    Ok(match parts.tp.as_str() {
        "DOMAIN" => Cond::Domain(normalize_value(payload)?),
        "DOMAIN-SUFFIX" => match payload.strip_prefix('.') {
            Some(rest) => Cond::DomainSubdomain(normalize_value(rest)?),
            None => Cond::DomainSuffix(normalize_value(payload)?),
        },
        "DOMAIN-KEYWORD" => Cond::DomainKeyword(payload.to_lowercase()),
        "DOMAIN-REGEX" => {
            regex::Regex::new(payload).map_err(|e| format!("DOMAIN-REGEX: {e}"))?;
            Cond::DomainRegex(payload.to_string())
        }
        "DOMAIN-WILDCARD" => Cond::DomainWildcard(payload.to_ascii_lowercase()),
        "GEOSITE" => Cond::GeoSite(payload.to_ascii_lowercase()),
        "GEOIP" => Cond::GeoIp { code: payload.to_ascii_lowercase(), side: side() },
        "SRC-GEOIP" => Cond::GeoIp { code: payload.to_ascii_lowercase(), side: IpSide::Src },
        "IP-CIDR" | "IP-CIDR6" => Cond::IpCidr { net: IpNet::parse(payload)?, side: side() },
        "SRC-IP-CIDR" => Cond::IpCidr { net: IpNet::parse(payload)?, side: IpSide::Src },
        "SRC-PORT" => Cond::SrcPort(Ranges::parse_mihomo(payload, 65535)?),
        "DST-PORT" => Cond::DstPort(Ranges::parse_mihomo(payload, 65535)?),
        "NETWORK" => Cond::Network(Network::parse(payload).ok_or_else(|| format!("NETWORK: unknown network {payload:?}"))?),
        "PROCESS-NAME" => Cond::ProcessName(payload.to_string()),
        "UID" => Cond::Uid(Ranges::parse_mihomo(payload, u32::MAX)?),
        "IN-NAME" => Cond::InboundTag(payload.to_string()),
        "RULE-SET" => {
            let src = parts.params.iter().any(|p| p == "src");
            Cond::RuleSet {
                name: payload.to_string(),
                src_ip: src,
                swap_ports: src,
                no_resolve: src || parts.params.iter().any(|p| p == "no-resolve"),
            }
        }
        "AND" | "OR" => {
            let children = logic_children(payload)?;
            if children.is_empty() {
                return Err(format!("{}: no rules inside", parts.tp));
            }
            let conds = children.iter().map(|c| parse_cond(c, opts)).collect::<Result<Vec<_>, _>>()?;
            if parts.tp == "AND" {
                Cond::And(conds)
            } else {
                Cond::Or(conds)
            }
        }
        "NOT" => {
            let children = logic_children(payload)?;
            if children.len() != 1 {
                return Err("NOT must contain exactly one rule".into());
            }
            Cond::Not(Box::new(parse_cond(children[0], opts)?))
        }
        "MATCH" => Cond::Any,
        tp => {
            return Err(match UNSUPPORTED.iter().find(|(t, _)| *t == tp) {
                Some((_, why)) => format!("{tp} is not supported: {why}"),
                None => format!("unknown rule type {tp}"),
            })
        }
    })
}

fn target_of(name: &str) -> Result<Target, String> {
    Ok(match name {
        "" => return Err("missing target".into()),
        "REJECT" => Target::Reject,
        "REJECT-DROP" => Target::RejectDrop,
        "PASS" => Target::Pass,
        other => Target::Outbound(other.to_string()),
    })
}

/// One rule line (`TYPE,payload,target(,params)`).
pub fn parse_rule(raw: &str, opts: &ClashOptions) -> Result<Rule, String> {
    let parts = split(raw, true);
    let (cond, target) = match parts.tp.as_str() {
        "" => return Err("empty rule".into()),
        "SUB-RULE" => {
            let children = logic_children(&format!("({})", parts.payload))?;
            if children.len() != 1 {
                return Err("SUB-RULE must contain exactly one rule".into());
            }
            if parts.target.is_empty() {
                return Err("SUB-RULE: missing sub-rule name".into());
            }
            (parse_cond(children[0], opts)?, Target::SubRule(parts.target.clone()))
        }
        _ => (cond_of(&parts, opts)?, target_of(&parts.target)?),
    };
    Ok(Rule { cond, target, source: raw.trim().to_string() })
}

/// A domain-behavior entry: `example.com`, `+.example.com` (with
/// subdomains), `.example.com` (subdomains only), `*` labels.
pub fn domain_entry(entry: &str) -> Result<Cond, String> {
    let entry = entry.trim();
    if let Some(rest) = entry.strip_prefix("+.") {
        return Ok(Cond::DomainSuffix(normalize_value(rest)?));
    }
    if let Some(rest) = entry.strip_prefix('.') {
        return Ok(Cond::DomainSubdomain(normalize_value(rest)?));
    }
    if entry.contains('*') {
        let lower = entry.to_ascii_lowercase();
        if lower.split('.').any(|l| l.contains('*') && l != "*") {
            return Err(format!("domain entry {entry:?}: '*' must be a whole label"));
        }
        return Ok(Cond::DomainRegex(label_wildcard_regex(&lower)));
    }
    if entry.contains(',') {
        return Err(format!("domain entry {entry:?} looks like a classical rule"));
    }
    Ok(Cond::Domain(normalize_value(entry)?))
}

/// The entries of a provider (its lines already split) as a rule set.
pub fn provider_set(behavior: Behavior, lines: &[String], opts: &ClashOptions) -> Result<RuleSet, String> {
    let mut rules = Vec::with_capacity(lines.len());
    for line in lines {
        let cond = match behavior {
            Behavior::Domain => domain_entry(line),
            Behavior::IpCidr => IpNet::parse(line).map(|net| Cond::IpCidr { net, side: IpSide::Dst { resolve: Some(opts.miss) } }),
            Behavior::Classical => {
                let cond = parse_cond(line, opts)?;
                if contains_rule_set(&cond) {
                    return Err(format!("{line:?}: a rule provider cannot reference another rule set"));
                }
                Ok(cond)
            }
        }
        .map_err(|e| format!("{line:?}: {e}"))?;
        rules.push(cond);
    }
    Ok(RuleSet { behavior, rules })
}

fn contains_rule_set(cond: &Cond) -> bool {
    match cond {
        Cond::RuleSet { .. } => true,
        Cond::And(c) | Cond::Or(c) => c.iter().any(contains_rule_set),
        Cond::Not(c) => contains_rule_set(c),
        _ => false,
    }
}

/// A provider file's content as a rule set.
pub fn provider_content(behavior: Behavior, format: ProviderFormat, data: &[u8], opts: &ClashOptions) -> Result<RuleSet, String> {
    let lines = match format {
        ProviderFormat::Yaml => yaml_lines(data)?,
        ProviderFormat::Text => text_lines(data)?,
        ProviderFormat::Mrs => match (read_mrs(data)?, behavior) {
            (MrsContent::Domain(keys), Behavior::Domain) => {
                // MRS keys: "+.x" is subdomains only (the plain "x" is its own key).
                let mut rules = Vec::with_capacity(keys.len());
                for key in keys {
                    let cond = match key.strip_prefix("+.") {
                        Some(rest) => Cond::DomainSubdomain(normalize_value(rest)?),
                        None => domain_entry(&key)?,
                    };
                    rules.push(cond);
                }
                return Ok(RuleSet { behavior, rules });
            }
            (MrsContent::IpCidr(nets), Behavior::IpCidr) => nets,
            _ => return Err("the MRS file's behavior does not match the provider's".into()),
        },
    };
    provider_set(behavior, &lines, opts)
}

/// The rule sets a condition references.
pub(crate) fn conds_rule_sets<'c>(cond: &'c Cond, out: &mut BTreeSet<&'c str>) {
    match cond {
        Cond::RuleSet { name, .. } => {
            out.insert(name.as_str());
        }
        Cond::And(c) | Cond::Or(c) => c.iter().for_each(|c| conds_rule_sets(c, out)),
        Cond::Not(c) => conds_rule_sets(c, out),
        _ => {}
    }
}

const PROVIDER_KEYS: [&str; 11] =
    ["type", "behavior", "path", "url", "proxy", "format", "interval", "size-limit", "payload", "header", "path-in-bundle"];

fn str_field<'v>(map: &'v serde_json::Map<String, Value>, key: &str) -> Option<&'v str> {
    map.get(key).and_then(Value::as_str)
}

fn load_provider(name: &str, spec: &Value, loader: &dyn ProviderLoader, opts: &ClashOptions) -> Result<RuleSet, String> {
    let map = spec.as_object().ok_or("not a mapping")?;
    if let Some(key) = map.keys().find(|k| !PROVIDER_KEYS.contains(&k.as_str())) {
        return Err(format!("unknown field {key:?}"));
    }
    let behavior = match str_field(map, "behavior").unwrap_or_default() {
        "domain" => Behavior::Domain,
        "ipcidr" => Behavior::IpCidr,
        "classical" => Behavior::Classical,
        other => return Err(format!("unknown behavior {other:?}")),
    };
    let format = match str_field(map, "format").unwrap_or_default() {
        "" | "yaml" => ProviderFormat::Yaml,
        "text" => ProviderFormat::Text,
        "mrs" => ProviderFormat::Mrs,
        other => return Err(format!("unknown format {other:?}")),
    };
    let kind = str_field(map, "type").unwrap_or_default();
    match kind {
        "inline" => {
            let payload = map.get("payload").and_then(Value::as_array).ok_or("inline provider without a payload list")?;
            let lines = payload
                .iter()
                .map(|v| v.as_str().map(|s| s.trim().to_string()).ok_or_else(|| format!("payload entry {v} is not a string")))
                .collect::<Result<Vec<_>, _>>()?;
            provider_set(behavior, &lines, opts)
        }
        "http" | "file" => {
            let provider = ProviderRef {
                name: name.to_string(),
                kind: kind.to_string(),
                behavior,
                format,
                path: str_field(map, "path").map(str::to_string),
                url: str_field(map, "url").map(str::to_string),
            };
            let data = loader.load(&provider)?;
            provider_content(behavior, format, &data, opts)
        }
        other => Err(format!("unsupported provider type {other:?}")),
    }
}

/// A whole configuration (already parsed from YAML).
pub fn import_value(config: &Value, loader: &dyn ProviderLoader, opts: &ClashOptions) -> Result<RouterIr, ImportError> {
    let mut ir = RouterIr::default();
    let list = |v: Option<&Value>, at: &str| -> Result<Vec<String>, ImportError> {
        match v {
            None | Some(Value::Null) => Ok(Vec::new()),
            Some(Value::Array(items)) => items
                .iter()
                .enumerate()
                .map(|(i, v)| v.as_str().map(str::to_string).ok_or_else(|| ImportError::new(format!("{at}[{i}]"), "not a string")))
                .collect(),
            Some(_) => Err(ImportError::new(at, "not a list")),
        }
    };
    for (i, line) in list(config.get("rules"), "rules")?.iter().enumerate() {
        ir.rules.push(parse_rule(line, opts).map_err(|e| ImportError::new(format!("rules[{i}] {line:?}"), e))?);
    }
    match config.get("sub-rules") {
        None | Some(Value::Null) => {}
        Some(Value::Object(subs)) => {
            for (name, lines) in subs {
                let at = format!("sub-rules.{name}");
                let mut rules = Vec::new();
                for (i, line) in list(Some(lines), &at)?.iter().enumerate() {
                    rules.push(parse_rule(line, opts).map_err(|e| ImportError::new(format!("{at}[{i}] {line:?}"), e))?);
                }
                ir.sub_rules.insert(name.clone(), rules);
            }
        }
        Some(_) => return Err(ImportError::new("sub-rules", "not a mapping")),
    }
    for (name, rules) in std::iter::once(("rules", &ir.rules)).chain(ir.sub_rules.iter().map(|(n, r)| (n.as_str(), r))) {
        for rule in rules.iter() {
            if let Target::SubRule(sub) = &rule.target {
                if !ir.sub_rules.contains_key(sub) {
                    return Err(ImportError::new(format!("{name}: {}", rule.source), format!("unknown sub-rule {sub:?}")));
                }
            }
        }
    }
    let mut wanted = BTreeSet::new();
    for rule in ir.rules.iter().chain(ir.sub_rules.values().flatten()) {
        conds_rule_sets(&rule.cond, &mut wanted);
    }
    let providers = match config.get("rule-providers") {
        None | Some(Value::Null) => serde_json::Map::new(),
        Some(Value::Object(p)) => p.clone(),
        Some(_) => return Err(ImportError::new("rule-providers", "not a mapping")),
    };
    let mut sets = BTreeMap::new();
    for name in wanted {
        let spec = providers.get(name).ok_or_else(|| ImportError::new(format!("RULE-SET,{name}"), "no such rule provider"))?;
        let set = load_provider(name, spec, loader, opts).map_err(|e| ImportError::new(format!("rule-providers.{name}"), e))?;
        sets.insert(name.to_string(), set);
    }
    ir.rule_sets = sets;
    // mihomo sends what no rule matched DIRECT.
    ir.final_target = Some(Target::Outbound("DIRECT".into()));
    Ok(ir)
}

/// A whole configuration as YAML text.
pub fn import(yaml: &str, loader: &dyn ProviderLoader, opts: &ClashOptions) -> Result<RouterIr, ImportError> {
    let config: Value = serde_yaml::from_str(yaml).map_err(|e| ImportError::new("config", format!("invalid YAML: {e}")))?;
    import_value(&config, loader, opts)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn opts() -> ClashOptions {
        ClashOptions::default()
    }

    fn cond(line: &str) -> Cond {
        parse_rule(line, &opts()).unwrap().cond
    }

    #[test]
    fn splits_like_mihomo() {
        let p = split("DOMAIN-SUFFIX, example.com ,Proxy,no-resolve", true);
        assert_eq!((p.tp.as_str(), p.payload.as_str(), p.target.as_str()), ("DOMAIN-SUFFIX", "example.com", "Proxy"));
        assert_eq!(p.params, ["no-resolve"]);
        let p = split("AND,((DOMAIN,a.com),(NETWORK,UDP)),REJECT", true);
        assert_eq!((p.payload.as_str(), p.target.as_str()), ("((DOMAIN,a.com),(NETWORK,UDP))", "REJECT"));
        let p = split("DOMAIN-REGEX,^a{1,3}\\.com$,DIRECT", true);
        assert_eq!(p.payload, "^a{1,3}\\.com$");
        let p = split("match,Proxy", true);
        assert_eq!((p.tp.as_str(), p.target.as_str()), ("MATCH", "Proxy"));
        let p = split("IP-CIDR,1.0.0.0/8,no-resolve", false);
        assert_eq!(p.params, ["no-resolve"]);
        assert_eq!(logic_children("((A,1),(B,(2)))").unwrap(), ["A,1", "B,(2)"]);
        assert!(logic_children("(A,1").is_err());
        assert!(logic_children("((A,1)").is_err());
        assert!(logic_children("(A))(").is_err());
    }

    #[test]
    fn every_supported_rule_type() {
        assert_eq!(cond("DOMAIN,WWW.Example.com,P"), Cond::Domain("www.example.com".into()));
        assert_eq!(cond("DOMAIN-SUFFIX,example.com,P"), Cond::DomainSuffix("example.com".into()));
        assert_eq!(cond("DOMAIN-SUFFIX,.example.com,P"), Cond::DomainSubdomain("example.com".into()));
        assert_eq!(cond("DOMAIN-KEYWORD,GooGle,P"), Cond::DomainKeyword("google".into()));
        assert_eq!(cond("DOMAIN-REGEX,^ad[0-9]+\\.,P"), Cond::DomainRegex("^ad[0-9]+\\.".into()));
        assert_eq!(cond("DOMAIN-WILDCARD,*.Example.?om,P"), Cond::DomainWildcard("*.example.?om".into()));
        assert_eq!(cond("GEOSITE,CN,P"), Cond::GeoSite("cn".into()));
        assert_eq!(cond("GEOIP,CN,P"), Cond::GeoIp { code: "cn".into(), side: IpSide::Dst { resolve: Some(MissPolicy::Skip) } });
        assert_eq!(cond("GEOIP,CN,P,no-resolve"), Cond::GeoIp { code: "cn".into(), side: IpSide::DST_NO_RESOLVE });
        assert_eq!(cond("GEOIP,CN,P,src"), Cond::GeoIp { code: "cn".into(), side: IpSide::Src });
        assert_eq!(cond("SRC-GEOIP,cn,P"), Cond::GeoIp { code: "cn".into(), side: IpSide::Src });
        let net = IpNet::parse("10.0.0.0/8").unwrap();
        assert_eq!(cond("IP-CIDR,10.0.0.0/8,P,no-resolve"), Cond::IpCidr { net, side: IpSide::DST_NO_RESOLVE });
        assert_eq!(
            cond("IP-CIDR6,2001:db8::/32,P").clone(),
            Cond::IpCidr { net: IpNet::parse("2001:db8::/32").unwrap(), side: IpSide::Dst { resolve: Some(MissPolicy::Skip) } }
        );
        assert_eq!(cond("SRC-IP-CIDR,10.0.0.0/8,P"), Cond::IpCidr { net, side: IpSide::Src });
        assert_eq!(cond("DST-PORT,80/443,P"), Cond::DstPort(Ranges(vec![(80, 80), (443, 443)])));
        assert_eq!(cond("SRC-PORT,1000-2000,P"), Cond::SrcPort(Ranges(vec![(1000, 2000)])));
        assert_eq!(cond("NETWORK,udp,P"), Cond::Network(Network::Udp));
        assert_eq!(cond("PROCESS-NAME,com.tencent.mm,P"), Cond::ProcessName("com.tencent.mm".into()));
        assert_eq!(cond("UID,10000-10100,P"), Cond::Uid(Ranges(vec![(10000, 10100)])));
        assert_eq!(cond("IN-NAME,tun-in,P"), Cond::InboundTag("tun-in".into()));
        assert_eq!(cond("RULE-SET,ads,P"), Cond::RuleSet { name: "ads".into(), src_ip: false, swap_ports: false, no_resolve: false });
        assert_eq!(
            cond("RULE-SET,cnip,P,no-resolve"),
            Cond::RuleSet { name: "cnip".into(), src_ip: false, swap_ports: false, no_resolve: true }
        );
        assert_eq!(cond("RULE-SET,lan,P,src"), Cond::RuleSet { name: "lan".into(), src_ip: true, swap_ports: true, no_resolve: true });
        assert_eq!(cond("MATCH,P"), Cond::Any);
        assert_eq!(
            cond("AND,((DOMAIN,a.com),(NOT,((NETWORK,UDP)))),P"),
            Cond::And(vec![Cond::Domain("a.com".into()), Cond::Not(Box::new(Cond::Network(Network::Udp)))])
        );
        assert_eq!(
            cond("OR,((DST-PORT,22),(DOMAIN-KEYWORD,ssh)),P"),
            Cond::Or(vec![Cond::DstPort(Ranges::single(22)), Cond::DomainKeyword("ssh".into())])
        );
        let rule = parse_rule("SUB-RULE,(AND,((NETWORK,TCP),(DST-PORT,443))),web", &opts()).unwrap();
        assert_eq!(rule.target, Target::SubRule("web".into()));
        assert!(matches!(rule.cond, Cond::And(_)));
        assert_eq!(parse_rule("DOMAIN,a.com,REJECT-DROP", &opts()).unwrap().target, Target::RejectDrop);
        assert_eq!(parse_rule("DOMAIN,a.com,REJECT", &opts()).unwrap().target, Target::Reject);
        assert_eq!(parse_rule("DOMAIN,a.com,PASS", &opts()).unwrap().target, Target::Pass);
        assert_eq!(parse_rule("DOMAIN,例子.测试,P", &opts()).unwrap().cond, Cond::Domain("xn--fsqu00a.xn--0zwm56d".into()));
        let miss = ClashOptions { miss: MissPolicy::NoResolve };
        assert_eq!(
            parse_rule("IP-CIDR,10.0.0.0/8,P", &miss).unwrap().cond,
            Cond::IpCidr { net, side: IpSide::Dst { resolve: Some(MissPolicy::NoResolve) } }
        );
    }

    #[test]
    fn what_cannot_be_expressed_is_an_error() {
        for (line, want) in [
            ("IP-ASN,13335,P", "IP-ASN is not supported"),
            ("PROCESS-PATH,/usr/bin/curl,P", "PROCESS-PATH is not supported"),
            ("IN-PORT,7890,P", "IN-PORT is not supported"),
            ("FOO,bar,P", "unknown rule type FOO"),
            ("DOMAIN,a.com,P,weird", "unknown rule parameter"),
            ("DOMAIN,a.com", "missing target"),
            ("DOMAIN-REGEX,(,P", "DOMAIN-REGEX"),
            ("IP-CIDR,300.0.0.0/8,P", "invalid IP CIDR"),
            ("NETWORK,sctp,P", "unknown network"),
            ("NOT,((DOMAIN,a),(DOMAIN,b)),P", "exactly one"),
            ("AND,((MATCH,x)),P", "cannot be used inside"),
            // As in mihomo, the wrapped payload holds one malformed rule.
            ("SUB-RULE,((DOMAIN,a),(DOMAIN,b)),x", "unknown rule type"),
            ("DST-PORT,,P", "missing payload"),
        ] {
            let err = parse_rule(line, &opts()).unwrap_err();
            assert!(err.contains(want), "{line}: {err}");
        }
    }

    #[test]
    fn domain_entries() {
        assert_eq!(domain_entry("+.Example.com").unwrap(), Cond::DomainSuffix("example.com".into()));
        assert_eq!(domain_entry(".example.com").unwrap(), Cond::DomainSubdomain("example.com".into()));
        assert_eq!(domain_entry("example.com").unwrap(), Cond::Domain("example.com".into()));
        assert_eq!(domain_entry("*.example.com").unwrap(), Cond::DomainRegex(r"^[^.]+\.example\.com$".into()));
        assert!(domain_entry("a*.example.com").is_err());
        assert!(domain_entry("DOMAIN,a.com").is_err());
    }

    #[test]
    fn imports_a_config_with_providers_and_sub_rules() {
        let yaml = r#"
proxies: []
rules:
  - RULE-SET,ads,REJECT
  - RULE-SET,cn-ip,DIRECT,no-resolve
  - RULE-SET,apps,Proxy
  - SUB-RULE,(NETWORK,TCP),web
  - GEOIP,CN,DIRECT
  - MATCH,Proxy
sub-rules:
  web:
    - DST-PORT,443,Proxy
    - MATCH,DIRECT
rule-providers:
  ads:
    type: inline
    behavior: domain
    payload: ["+.ads.example", "tracker.example"]
  cn-ip:
    type: http
    behavior: ipcidr
    format: text
    url: https://example.com/cn.txt
    path: ./cn.txt
    interval: 86400
  apps:
    type: file
    behavior: classical
    path: ./apps.yaml
  unused:
    type: file
    behavior: domain
    path: ./missing.yaml
"#;
        let loader = |p: &ProviderRef| -> Result<Vec<u8>, String> {
            match p.name.as_str() {
                "cn-ip" => {
                    assert_eq!((p.format, p.url.as_deref()), (ProviderFormat::Text, Some("https://example.com/cn.txt")));
                    Ok(b"1.0.1.0/24\n# comment\n2400:3200::/32\n".to_vec())
                }
                "apps" => Ok(b"payload:\n  - PROCESS-NAME,com.example\n  - IP-CIDR,8.8.8.8/32,no-resolve\n".to_vec()),
                other => Err(format!("unexpected {other}")),
            }
        };
        let ir = import(yaml, &loader, &opts()).unwrap();
        assert_eq!(ir.rules.len(), 6);
        assert_eq!(ir.sub_rules["web"].len(), 2);
        assert_eq!(ir.rule_sets.len(), 3);
        assert_eq!(ir.rule_sets["ads"].rules, vec![Cond::DomainSuffix("ads.example".into()), Cond::Domain("tracker.example".into())]);
        assert_eq!(ir.rule_sets["cn-ip"].rules.len(), 2);
        assert_eq!(ir.rule_sets["apps"].behavior, Behavior::Classical);
        assert_eq!(ir.final_target, Some(Target::Outbound("DIRECT".into())));

        let missing = "rules:\n  - RULE-SET,nope,DIRECT\n";
        assert!(import(missing, &loader, &opts()).unwrap_err().message.contains("no such rule provider"));
        let bad_sub = "rules:\n  - SUB-RULE,(NETWORK,TCP),nope\n";
        assert!(import(bad_sub, &loader, &opts()).unwrap_err().message.contains("unknown sub-rule"));
        let bad_field =
            "rules:\n  - RULE-SET,x,DIRECT\nrule-providers:\n  x: {type: inline, behavior: domain, payload: [a.com], weird: 1}\n";
        assert!(import(bad_field, &loader, &opts()).unwrap_err().message.contains("unknown field"));
        let nested = "rules:\n  - RULE-SET,x,DIRECT\nrule-providers:\n  x: {type: inline, behavior: classical, payload: ['RULE-SET,y']}\n";
        assert!(import(nested, &loader, &opts()).unwrap_err().message.contains("cannot reference"));
        let err = import("rules:\n  - IP-ASN,1,DIRECT\n", &loader, &opts()).unwrap_err();
        assert!(err.at.starts_with("rules[0]") && err.message.contains("IP-ASN"), "{err}");
    }
}
