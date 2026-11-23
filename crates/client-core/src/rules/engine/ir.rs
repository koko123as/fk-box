//! The unified rule representation every importer produces and the compiler
//! ([`super::compile`]) reads: one rule list per program (the main rules and
//! each named sub-rule list), named rule sets, and the final target.
//!
//! Values are kept as the importer read them (domains normalized, CIDRs
//! parsed, ports as ranges); nothing here depends on a core's own syntax.

use std::collections::BTreeMap;

use super::ip::IpNet;

/// Transport of a connection.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Network {
    Tcp,
    Udp,
}

impl Network {
    pub fn parse(text: &str) -> Option<Network> {
        match text.trim().to_ascii_lowercase().as_str() {
            "tcp" => Some(Network::Tcp),
            "udp" => Some(Network::Udp),
            _ => None,
        }
    }
}

/// What an IP condition does for a connection that has a domain but no
/// destination IP (see docs/architecture/brain-dns-rules.md).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum MissPolicy {
    /// A cache miss leaves the condition unknown: the top-level rule that
    /// holds it is skipped whatever the logic around it (`NOT` included).
    Skip,
    /// A cache miss makes the condition false, as mihomo's `no-resolve`
    /// does (so `NOT(IP-CIDR …)` matches).
    NoResolve,
}

/// Which address an IP condition reads.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum IpSide {
    /// The source address.
    Src,
    /// The destination address; with `resolve`, a domain-only connection
    /// reads the brain's DNS cache and a miss follows the policy.
    Dst { resolve: Option<MissPolicy> },
}

impl IpSide {
    pub const DST_NO_RESOLVE: IpSide = IpSide::Dst { resolve: None };

    /// The side read when a rule set is referenced from the source (mihomo
    /// `RULE-SET,…,src`, sing-box `rule_set_ip_cidr_match_source`).
    pub fn swapped(self) -> IpSide {
        match self {
            IpSide::Src => IpSide::DST_NO_RESOLVE,
            IpSide::Dst { .. } => IpSide::Src,
        }
    }
}

/// Inclusive integer ranges (ports, UIDs).
#[derive(Debug, Clone, PartialEq, Eq, Hash, Default)]
pub struct Ranges(pub Vec<(u32, u32)>);

impl Ranges {
    pub fn single(v: u32) -> Ranges {
        Ranges(vec![(v, v)])
    }

    pub fn contains(&self, v: u32) -> bool {
        self.0.iter().any(|&(a, b)| a <= v && v <= b)
    }

    /// mihomo's form: `80`, `80/443`, `1000-2000`, `80,443,8000-9000`
    /// (`utils.NewUnsignedRanges`, at most 28 ranges).
    pub fn parse_mihomo(text: &str, max: u32) -> Result<Ranges, String> {
        let text = text.trim();
        if text.is_empty() || text == "*" {
            return Err(format!("empty range: {text:?}"));
        }
        let parts: Vec<&str> = text.split(['/', ',']).collect();
        if parts.len() > 28 {
            return Err("too many ranges (at most 28)".into());
        }
        let mut out = Vec::new();
        for part in parts.iter().map(|p| p.trim()).filter(|p| !p.is_empty()) {
            let (a, b) = match part.split_once('-') {
                Some((a, b)) => (parse_u32(a, max)?, parse_u32(b, max)?),
                None => {
                    let v = parse_u32(part, max)?;
                    (v, v)
                }
            };
            out.push((a.min(b), a.max(b)));
        }
        if out.is_empty() {
            return Err(format!("empty range: {text:?}"));
        }
        Ok(Ranges(out))
    }

    /// sing-box's `port_range` form: `1000:2000`, `:3000`, `4000:`.
    pub fn parse_colon(text: &str) -> Result<(u32, u32), String> {
        let (a, b) = text.split_once(':').ok_or_else(|| format!("invalid port range {text:?}"))?;
        let a = if a.trim().is_empty() { 0 } else { parse_u32(a, 65535)? };
        let b = if b.trim().is_empty() { 65535 } else { parse_u32(b, 65535)? };
        if a > b {
            return Err(format!("invalid port range {text:?}"));
        }
        Ok((a, b))
    }
}

fn parse_u32(text: &str, max: u32) -> Result<u32, String> {
    let v: u32 = text.trim().parse().map_err(|_| format!("invalid number {text:?}"))?;
    if v > max {
        return Err(format!("{v} is out of range (max {max})"));
    }
    Ok(v)
}

/// One condition. Domain values are normalized ([`super::domain::normalize`]);
/// regex and keyword values are matched against the normalized domain.
#[derive(Debug, Clone, PartialEq)]
pub enum Cond {
    /// The domain itself.
    Domain(String),
    /// The domain and every subdomain (`DOMAIN-SUFFIX`, `+.x`, sing-box `domain_suffix`).
    DomainSuffix(String),
    /// Subdomains only (`.x` in mihomo domain sets and sing-box `domain_suffix`).
    DomainSubdomain(String),
    DomainKeyword(String),
    DomainRegex(String),
    /// mihomo `DOMAIN-WILDCARD`: `*` matches any run, `?` one character.
    DomainWildcard(String),
    /// A geosite category, with optional `@attr` filters (`cn`, `category-ads-all`, `cn@ads`).
    GeoSite(String),
    IpCidr {
        net: IpNet,
        side: IpSide,
    },
    /// A geoip code (`cn`; `private`/`lan` fall back to the built-in private ranges).
    GeoIp {
        code: String,
        side: IpSide,
    },
    /// Private, loopback, link-local and other non-global ranges.
    IpPrivate {
        side: IpSide,
    },
    /// 4 or 6 (of the destination; the resolved address for a domain-only connection is not consulted).
    IpVersion(u8),
    DstPort(Ranges),
    SrcPort(Ranges),
    Network(Network),
    /// The sniffed protocol (`tls`, `http`, `quic`, `dns`, `bittorrent`, …), lowercase.
    Protocol(String),
    /// The process name; on Android (mihomo semantics) also the package name.
    ProcessName(String),
    PackageName(String),
    PackageNameRegex(String),
    Uid(Ranges),
    InboundTag(String),
    /// A named rule set. `src_ip`: its IP conditions read the other side
    /// (mihomo `src`, sing-box `rule_set_ip_cidr_match_source`);
    /// `swap_ports`: its port conditions read the other side too (mihomo
    /// `src` swaps the whole address); `no_resolve`: its destination IP
    /// conditions never consult DNS.
    RuleSet {
        name: String,
        src_ip: bool,
        swap_ports: bool,
        no_resolve: bool,
    },
    And(Vec<Cond>),
    Or(Vec<Cond>),
    Not(Box<Cond>),
    /// Always true (`MATCH`, an empty sing-box rule).
    Any,
}

/// Where a matching rule sends the connection.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Target {
    /// An outbound (or group) by tag; the host's outbound table maps it to an index.
    Outbound(String),
    Reject,
    /// Reject without a reply (mihomo `REJECT-DROP`, sing-box `reject` `method: drop`).
    RejectDrop,
    /// Hand the connection to the DNS handler (sing-box `hijack-dns`).
    HijackDns,
    /// Continue with the next rule (mihomo `PASS`).
    Pass,
    /// Evaluate the named sub-rule list; no match there continues after this rule.
    SubRule(String),
}

#[derive(Debug, Clone, PartialEq)]
pub struct Rule {
    pub cond: Cond,
    pub target: Target,
    /// The rule as written (for diagnostics and the decision's rule index).
    pub source: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Behavior {
    Domain,
    IpCidr,
    Classical,
}

/// A rule set: it matches when any of its conditions does.
#[derive(Debug, Clone, PartialEq)]
pub struct RuleSet {
    pub behavior: Behavior,
    pub rules: Vec<Cond>,
}

#[derive(Debug, Clone, PartialEq, Default)]
pub struct RouterIr {
    pub rules: Vec<Rule>,
    pub sub_rules: BTreeMap<String, Vec<Rule>>,
    pub rule_sets: BTreeMap<String, RuleSet>,
    /// Where a connection no rule matched goes; `None` takes the compile
    /// environment's default.
    pub final_target: Option<Target>,
}

/// An import that could not be expressed: the rule (or field) and why. The
/// importers never drop what they cannot express.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ImportError {
    pub at: String,
    pub message: String,
}

impl ImportError {
    pub fn new(at: impl Into<String>, message: impl Into<String>) -> ImportError {
        ImportError { at: at.into(), message: message.into() }
    }
}

impl std::fmt::Display for ImportError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}: {}", self.at, self.message)
    }
}

impl std::error::Error for ImportError {}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ranges_parse_both_forms() {
        assert_eq!(Ranges::parse_mihomo("80/443/1000-2000", 65535).unwrap().0, vec![(80, 80), (443, 443), (1000, 2000)]);
        assert_eq!(Ranges::parse_mihomo("80,443", 65535).unwrap().0, vec![(80, 80), (443, 443)]);
        assert_eq!(Ranges::parse_mihomo("2000-1000", 65535).unwrap().0, vec![(1000, 2000)]);
        assert!(Ranges::parse_mihomo("70000", 65535).is_err());
        assert!(Ranges::parse_mihomo("", 65535).is_err());
        assert!(Ranges::parse_mihomo("a", 65535).is_err());
        let many = (1..=29).map(|n| n.to_string()).collect::<Vec<_>>().join("/");
        assert!(Ranges::parse_mihomo(&many, 65535).is_err());
        assert_eq!(Ranges::parse_colon("1000:2000").unwrap(), (1000, 2000));
        assert_eq!(Ranges::parse_colon(":3000").unwrap(), (0, 3000));
        assert_eq!(Ranges::parse_colon("4000:").unwrap(), (4000, 65535));
        assert!(Ranges::parse_colon("5:4").is_err());
        assert!(Ranges::parse_colon("5").is_err());
        let r = Ranges::parse_mihomo("10-20", 65535).unwrap();
        assert!(r.contains(10) && r.contains(20) && !r.contains(21) && !r.contains(9));
    }

    #[test]
    fn side_swaps() {
        assert_eq!(IpSide::Src.swapped(), IpSide::DST_NO_RESOLVE);
        assert_eq!(IpSide::Dst { resolve: Some(MissPolicy::Skip) }.swapped(), IpSide::Src);
        assert_eq!(Network::parse(" TCP "), Some(Network::Tcp));
        assert_eq!(Network::parse("sctp"), None);
    }
}
