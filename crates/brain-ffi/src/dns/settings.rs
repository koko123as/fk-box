//! The app-side DNS settings (the subscription's own `dns:` section is
//! ignored, see brain-config.md). They arrive as the `dns` object of the
//! profile options; every key is optional and has the default below.
//! docs/architecture/brain-dns.md is the reference.

use std::net::IpAddr;

use fkbox_client_core::rules::engine::domain::{normalize, wildcard_match};
use serde_json::{Map, Value};

use super::fakeip::{parse_v4, parse_v6};

/// Direct names (the rules route them to `fk-direct`): plain DNS to two
/// public resolvers in mainland China, sent straight from the device
/// through the protected direct dialer.
pub const DEFAULT_DIRECT_UPSTREAMS: [&str; 2] = ["udp://223.5.5.5:53", "udp://119.29.29.29:53"];
/// Proxied names: DNS over TCP through the node the rules picked (a node
/// may not relay UDP; the proxy encrypts the hop anyway).
pub const DEFAULT_PROXY_UPSTREAMS: [&str; 2] = ["tcp://1.1.1.1:53", "tcp://8.8.8.8:53"];
pub const DEFAULT_FAKE_IP_RANGE: &str = "198.18.0.0/15";
pub const DEFAULT_FAKE_IP_RANGE6: &str = "fc00::/18";
/// Names that must see real addresses even with fake-ip on: local names,
/// time and connectivity checks, STUN (peer-to-peer apps compare addresses).
pub const DEFAULT_FAKE_IP_FILTER: [&str; 12] = [
    "localhost",
    "+.lan",
    "+.local",
    "+.localdomain",
    "+.home.arpa",
    "+.in-addr.arpa",
    "+.ip6.arpa",
    "time.*.com",
    "+.ntp.org",
    "+.msftconnecttest.com",
    "+.msftncsi.com",
    "stun.*",
];
pub const DEFAULT_TIMEOUT_MS: u32 = 5000;

/// A name pattern: `+.example.com` (the name and every subdomain),
/// `*.example.com` (subdomains only), a mihomo wildcard with `*` / `?`
/// elsewhere, or an exact name.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Pattern {
    Exact(String),
    SuffixWithSelf(String),
    Subdomains(String),
    Wildcard(String),
}

impl Pattern {
    pub fn parse(text: &str) -> Result<Pattern, String> {
        let text = text.trim().to_ascii_lowercase();
        let bad = || format!("dns: bad name pattern {text:?}");
        if let Some(rest) = text.strip_prefix("+.") {
            return Ok(Pattern::SuffixWithSelf(normalize(rest).ok_or_else(bad)?.into_owned()));
        }
        if let Some(rest) = text.strip_prefix("*.") {
            if !rest.contains(['*', '?']) {
                return Ok(Pattern::Subdomains(normalize(rest).ok_or_else(bad)?.into_owned()));
            }
        }
        if text.contains(['*', '?']) {
            return Ok(Pattern::Wildcard(text.trim_end_matches('.').to_string()));
        }
        Ok(Pattern::Exact(normalize(&text).ok_or_else(bad)?.into_owned()))
    }

    /// `name` is normalized (lowercase, no trailing dot).
    pub fn matches(&self, name: &str) -> bool {
        let below =
            |suffix: &str| name.len() > suffix.len() && name.ends_with(suffix) && name.as_bytes()[name.len() - suffix.len() - 1] == b'.';
        match self {
            Pattern::Exact(n) => name == n,
            Pattern::SuffixWithSelf(s) => name == s || below(s),
            Pattern::Subdomains(s) => below(s),
            Pattern::Wildcard(w) => wildcard_match(w.as_bytes(), name.as_bytes()),
        }
    }
}

/// `nameserver-policy` of the app: names matching `domains` use `upstreams`
/// through `outbound` (or through the outbound the rules pick when unset).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Policy {
    pub domains: Vec<Pattern>,
    pub upstreams: Vec<String>,
    pub outbound: Option<String>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct DnsSettings {
    pub direct_upstreams: Vec<String>,
    pub proxy_upstreams: Vec<String>,
    pub fake_ip: bool,
    pub fake_ip_range: String,
    /// `None`: AAAA queries of fake-ip names get an empty answer.
    pub fake_ip_range6: Option<String>,
    pub fake_ip_filter: Vec<Pattern>,
    /// Static answers, checked before anything else (also with fake-ip on).
    pub hosts: Vec<(String, Vec<IpAddr>)>,
    pub policy: Vec<Policy>,
    /// File the fake-ip table is kept in across restarts; none: memory only.
    pub fake_ip_store: Option<String>,
    /// How long one query may take when the caller gives no timeout.
    pub timeout_ms: u32,
}

impl Default for DnsSettings {
    fn default() -> DnsSettings {
        DnsSettings {
            direct_upstreams: DEFAULT_DIRECT_UPSTREAMS.iter().map(|s| s.to_string()).collect(),
            proxy_upstreams: DEFAULT_PROXY_UPSTREAMS.iter().map(|s| s.to_string()).collect(),
            fake_ip: true,
            fake_ip_range: DEFAULT_FAKE_IP_RANGE.into(),
            fake_ip_range6: Some(DEFAULT_FAKE_IP_RANGE6.into()),
            fake_ip_filter: DEFAULT_FAKE_IP_FILTER.iter().map(|p| Pattern::parse(p).expect("default pattern")).collect(),
            hosts: vec![("localhost".into(), vec![IpAddr::from([127, 0, 0, 1]), IpAddr::from([0u16, 0, 0, 0, 0, 0, 0, 1])])],
            policy: Vec::new(),
            fake_ip_store: None,
            timeout_ms: DEFAULT_TIMEOUT_MS,
        }
    }
}

/// Checks an upstream address: `udp://`, `tcp://`, `tls://` or `https://`
/// with an IP address (a name would need DNS to reach DNS), an optional
/// port, an optional path for https and an optional `?sni=` for the TLS ones.
pub fn check_upstream(text: &str) -> Result<(), String> {
    let bad = |why: &str| format!("dns upstream {text:?}: {why}");
    let (scheme, rest) = text.split_once("://").ok_or_else(|| bad("no scheme"))?;
    if !matches!(scheme, "udp" | "tcp" | "tls" | "https") {
        return Err(bad("the scheme must be udp, tcp, tls or https"));
    }
    let rest = rest.split_once('?').map_or(rest, |(r, q)| if q.starts_with("sni=") && scheme != "udp" && scheme != "tcp" { r } else { "" });
    if rest.is_empty() {
        return Err(bad("bad query (only ?sni= on tls and https)"));
    }
    let (authority, path) = rest.split_once('/').map_or((rest, ""), |(a, p)| (a, p));
    if !path.is_empty() && scheme != "https" {
        return Err(bad("a path only for https"));
    }
    let host = if let Some(v6) = authority.strip_prefix('[') {
        let (host, port) = v6.split_once(']').ok_or_else(|| bad("unclosed ["))?;
        if !port.is_empty() {
            port.strip_prefix(':').and_then(|p| p.parse::<u16>().ok()).ok_or_else(|| bad("bad port"))?;
        }
        host
    } else {
        match authority.rsplit_once(':') {
            Some((host, port)) if !host.contains(':') => {
                port.parse::<u16>().map_err(|_| bad("bad port"))?;
                host
            }
            _ => authority,
        }
    };
    host.parse::<IpAddr>().map_err(|_| bad("the address must be an IP"))?;
    Ok(())
}

fn strings(value: &Value, key: &str) -> Result<Vec<String>, String> {
    let list = value.as_array().ok_or_else(|| format!("dns.{key}: not a list"))?;
    list.iter().map(|v| v.as_str().map(str::to_string).ok_or_else(|| format!("dns.{key}: entry is not a string"))).collect()
}

fn upstreams(value: &Value, key: &str) -> Result<Vec<String>, String> {
    let list = strings(value, key)?;
    if list.is_empty() {
        return Err(format!("dns.{key}: empty"));
    }
    for upstream in &list {
        check_upstream(upstream)?;
    }
    Ok(list)
}

fn patterns(value: &Value, key: &str) -> Result<Vec<Pattern>, String> {
    strings(value, key)?.iter().map(|p| Pattern::parse(p)).collect()
}

fn ips(value: &Value, name: &str) -> Result<Vec<IpAddr>, String> {
    let bad = || format!("dns.hosts.{name}: not an IP address or a list of them");
    match value {
        Value::String(s) => Ok(vec![s.parse().map_err(|_| bad())?]),
        Value::Array(list) => list.iter().map(|v| v.as_str().and_then(|s| s.parse().ok()).ok_or_else(bad)).collect(),
        _ => Err(bad()),
    }
}

impl DnsSettings {
    /// Reads the `dns` object of the profile options (unknown keys refused).
    pub fn from_value(value: &Value) -> Result<DnsSettings, String> {
        let object: &Map<String, Value> = value.as_object().ok_or("dns: not an object")?;
        let mut s = DnsSettings::default();
        for (key, item) in object {
            match key.as_str() {
                "direct_upstreams" => s.direct_upstreams = upstreams(item, key)?,
                "proxy_upstreams" => s.proxy_upstreams = upstreams(item, key)?,
                "fake_ip" => s.fake_ip = item.as_bool().ok_or("dns.fake_ip: not a bool")?,
                "fake_ip_range" => {
                    let range = item.as_str().ok_or("dns.fake_ip_range: not a string")?;
                    parse_v4(range)?;
                    s.fake_ip_range = range.to_string();
                }
                "fake_ip_range6" => {
                    s.fake_ip_range6 = match item {
                        Value::Null => None,
                        Value::String(range) => {
                            parse_v6(range)?;
                            Some(range.clone())
                        }
                        _ => return Err("dns.fake_ip_range6: not a string or null".into()),
                    }
                }
                "fake_ip_filter" => s.fake_ip_filter = patterns(item, key)?,
                "hosts" => {
                    let map = item.as_object().ok_or("dns.hosts: not an object")?;
                    s.hosts = Vec::with_capacity(map.len());
                    for (name, addrs) in map {
                        let normalized = normalize(name).ok_or_else(|| format!("dns.hosts: bad name {name:?}"))?.into_owned();
                        s.hosts.push((normalized, ips(addrs, name)?));
                    }
                }
                "policy" => {
                    let list = item.as_array().ok_or("dns.policy: not a list")?;
                    for entry in list {
                        let entry = entry.as_object().ok_or("dns.policy: entry is not an object")?;
                        let mut policy = Policy { domains: Vec::new(), upstreams: Vec::new(), outbound: None };
                        for (k, v) in entry {
                            match k.as_str() {
                                "domains" => policy.domains = patterns(v, "policy.domains")?,
                                "upstreams" => policy.upstreams = upstreams(v, "policy.upstreams")?,
                                "outbound" => policy.outbound = Some(v.as_str().ok_or("dns.policy.outbound: not a string")?.to_string()),
                                other => return Err(format!("dns.policy: unknown key {other:?}")),
                            }
                        }
                        if policy.domains.is_empty() || policy.upstreams.is_empty() {
                            return Err("dns.policy: an entry needs domains and upstreams".into());
                        }
                        s.policy.push(policy);
                    }
                }
                "fake_ip_store" => {
                    s.fake_ip_store = match item {
                        Value::Null => None,
                        Value::String(path) if !path.is_empty() => Some(path.clone()),
                        _ => return Err("dns.fake_ip_store: not a path".into()),
                    }
                }
                "timeout_ms" => {
                    let ms = item.as_u64().filter(|m| (100..=60_000).contains(m)).ok_or("dns.timeout_ms: 100..60000")?;
                    s.timeout_ms = ms as u32;
                }
                other => return Err(format!("dns: unknown key {other:?}")),
            }
        }
        Ok(s)
    }

    pub fn hosts_for(&self, name: &str) -> Option<&[IpAddr]> {
        self.hosts.iter().find(|(n, _)| n == name).map(|(_, a)| a.as_slice())
    }

    pub fn fake_ip_filtered(&self, name: &str) -> bool {
        self.fake_ip_filter.iter().any(|p| p.matches(name))
    }

    pub fn policy_for(&self, name: &str) -> Option<&Policy> {
        self.policy.iter().find(|p| p.domains.iter().any(|d| d.matches(name)))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn patterns_match() {
        let plus = Pattern::parse("+.Example.com").unwrap();
        assert!(plus.matches("example.com") && plus.matches("a.b.example.com") && !plus.matches("badexample.com"));
        let star = Pattern::parse("*.example.com").unwrap();
        assert!(!star.matches("example.com") && star.matches("a.example.com"));
        let wild = Pattern::parse("time.*.com").unwrap();
        assert!(wild.matches("time.windows.com") && !wild.matches("time.windows.net"));
        assert!(Pattern::parse("Exact.Test.").unwrap().matches("exact.test"));
        let d = DnsSettings::default();
        assert!(d.fake_ip_filtered("printer.lan") && d.fake_ip_filtered("stun.l.google.com") && !d.fake_ip_filtered("www.google.com"));
        assert_eq!(d.hosts_for("localhost").map(|a| a.len()), Some(2));
    }

    #[test]
    fn upstream_addresses() {
        for ok in [
            "udp://223.5.5.5",
            "udp://223.5.5.5:53",
            "tcp://[2606:4700::1111]:53",
            "tls://1.1.1.1?sni=one.one.one.one",
            "https://1.1.1.1/dns-query",
            "https://[::1]:8443/q",
        ] {
            check_upstream(ok).unwrap_or_else(|e| panic!("{ok}: {e}"));
        }
        for bad in [
            "8.8.8.8",
            "quic://1.1.1.1",
            "tls://dns.google",
            "udp://1.1.1.1/path",
            "udp://1.1.1.1?sni=x",
            "tcp://1.1.1.1:99999",
            "https://[::1/q",
        ] {
            assert!(check_upstream(bad).is_err(), "{bad}");
        }
    }

    #[test]
    fn from_json_strict_with_defaults() {
        let s = DnsSettings::from_value(&json!({
            "direct_upstreams": ["udp://127.0.0.1:5353"],
            "fake_ip_range": "198.18.128.0/20",
            "fake_ip_range6": null,
            "hosts": {"router.test": "192.168.1.1", "Multi.Test": ["10.0.0.1", "::2"]},
            "policy": [{"domains": ["+.fixture.test"], "upstreams": ["udp://127.0.0.1:5353"], "outbound": "fk-direct"}],
            "fake_ip_store": "/data/fakeip.txt",
            "timeout_ms": 2000
        }))
        .unwrap();
        assert_eq!(s.direct_upstreams, ["udp://127.0.0.1:5353"]);
        assert_eq!(s.proxy_upstreams, DEFAULT_PROXY_UPSTREAMS);
        assert!(s.fake_ip && s.fake_ip_range6.is_none());
        assert_eq!(s.hosts_for("multi.test").unwrap().len(), 2);
        assert!(s.hosts_for("localhost").is_none(), "hosts replaces the default");
        assert_eq!(s.policy_for("a.fixture.test").unwrap().outbound.as_deref(), Some("fk-direct"));
        assert!(s.policy_for("fixture.test.evil").is_none());
        assert_eq!(s.timeout_ms, 2000);
        assert!(DnsSettings::from_value(&json!({"servers": []})).is_err());
        assert!(DnsSettings::from_value(&json!({"fake_ip_range": "10.0.0.0/30"})).is_err());
        assert!(DnsSettings::from_value(&json!({"proxy_upstreams": []})).is_err());
        assert!(DnsSettings::from_value(&json!({"policy": [{"domains": ["a.test"]}]})).is_err());
        assert_eq!(DnsSettings::from_value(&json!({})).unwrap(), DnsSettings::default());
    }
}
