//! The compiled router: what a decision runs.
//!
//! Each program (the main rules, each sub-rule list) keeps first-match order
//! but does not walk its rules one by one. Rules whose condition is a plain
//! union of domain and IP entries (`DOMAIN*`, `IP-CIDR*`, `GEOSITE`, `GEOIP`
//! from `geoip.dat`, domain/ipcidr rule sets, `OR` of those) are folded into
//! the program's indexes, each entry carrying its rule index; a lookup gives
//! the earliest such rule that matches (the candidate). The other rules (the
//! generic ones: ports, networks, apps, logic, mmdb `GEOIP`, `PASS`,
//! `SUB-RULE` targets) are evaluated in order, and only those before the
//! candidate. `MATCH` sets the candidate outright.
//!
//! Conditions evaluate to true, false or unknown (an IP condition whose DNS
//! cache lookup missed under [`MissPolicy::Skip`]); logic is Kleene's, and a
//! top-level unknown skips the rule.

use std::collections::{BTreeMap, HashMap};
use std::net::{IpAddr, Ipv4Addr};
use std::sync::Arc;

use regex::Regex;

use super::conn::{Conn, DnsView, MAX_CACHED_ADDRS};
use super::domain::{self, DomainIndex, DomainIndexBuilder};
use super::geo::{GeoIpDat, GeoSiteDb, SiteDomain};
use super::ip::{self, IpIndex, IpNet};
use super::ir::{Cond, IpSide, MissPolicy, Network, Ranges, RouterIr, Rule, Target};
use super::mmdb::Mmdb;

/// What a router is compiled against: the host's outbound and inbound tables
/// (their order is the index the decision carries) and the geo data.
pub struct Env<'a> {
    pub outbounds: &'a [String],
    pub inbounds: &'a [String],
    pub geosite: Option<&'a GeoSiteDb>,
    pub geoip: Option<&'a GeoIpDat>,
    pub mmdb: Option<Arc<Mmdb>>,
    /// The final target when the rules name none.
    pub default_final: Option<Target>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Action {
    /// Index into the outbound table.
    Outbound(u32),
    Reject,
    RejectDrop,
    HijackDns,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Verdict {
    pub action: Action,
    /// The top-level rule that matched (0-based), `None` for the final target.
    pub rule: Option<u32>,
    /// A rule needed the DNS cache and it missed.
    pub dns_miss: bool,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct RouterStats {
    pub rules: usize,
    pub indexed: usize,
    pub generic: usize,
    pub sub_programs: usize,
    pub domain_entries: usize,
    pub ip_nodes: usize,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Tri {
    T,
    F,
    U,
}

fn tri(b: bool) -> Tri {
    if b {
        Tri::T
    } else {
        Tri::F
    }
}

enum Entry {
    Full(String),
    Suffix(String),
    Sub(String),
    Keyword(String),
    Regex(String),
    Wild(String),
    Ip(IpNet, IpSide),
}

/// How a referenced rule set's conditions are read.
#[derive(Debug, Clone, Copy, Default)]
struct Xf {
    src_ip: bool,
    swap_ports: bool,
    no_resolve: bool,
}

impl Xf {
    fn side(self, side: IpSide) -> IpSide {
        let side = if self.src_ip { side.swapped() } else { side };
        match side {
            IpSide::Dst { .. } if self.no_resolve => IpSide::DST_NO_RESOLVE,
            other => other,
        }
    }
}

/// IP index slots: source; destination without DNS; destination through the
/// cache with each miss policy.
fn slot(side: IpSide) -> usize {
    match side {
        IpSide::Src => 0,
        IpSide::Dst { resolve: None } => 1,
        IpSide::Dst { resolve: Some(MissPolicy::Skip) } => 2,
        IpSide::Dst { resolve: Some(MissPolicy::NoResolve) } => 3,
    }
}

fn slot_side(slot: usize) -> IpSide {
    match slot {
        0 => IpSide::Src,
        1 => IpSide::DST_NO_RESOLVE,
        2 => IpSide::Dst { resolve: Some(MissPolicy::Skip) },
        _ => IpSide::Dst { resolve: Some(MissPolicy::NoResolve) },
    }
}

struct SetNode {
    domains: Option<DomainIndex>,
    ips: [Option<IpIndex>; 4],
}

enum Node {
    Set(Box<SetNode>),
    MmdbGeoIp { code: Box<str>, side: IpSide },
    DstPort(Ranges),
    SrcPort(Ranges),
    Network(Network),
    IpVersion(u8),
    Protocol(Box<str>),
    ProcessName(Box<str>),
    PackageName(Box<str>),
    PackageRegex(Regex),
    Uid(Ranges),
    Inbound(u32),
    And(Vec<Node>),
    Or(Vec<Node>),
    Not(Box<Node>),
    Any,
}

#[derive(Debug, Clone, Copy)]
enum CTarget {
    Action(Action),
    Pass,
    Sub(usize),
}

struct CRule {
    node: Option<Node>,
    target: CTarget,
}

struct Program {
    rules: Vec<CRule>,
    domains: DomainIndex,
    src_ips: IpIndex,
    dst_ips: IpIndex,
    dst_resolve: IpIndex,
    /// The earliest rule in `dst_resolve` (DNS is consulted only before it).
    resolve_min: u32,
    /// The earliest `MATCH`.
    always: u32,
    /// Rules evaluated one by one, ascending.
    generic: Vec<u32>,
}

pub struct Router {
    main: Program,
    subs: Vec<Program>,
    final_action: Action,
    mmdb: Option<Arc<Mmdb>>,
    stats: RouterStats,
}

const MAX_SUB_DEPTH: u32 = 16;

fn is_private_code(code: &str) -> bool {
    code.eq_ignore_ascii_case("private") || code.eq_ignore_ascii_case("lan")
}

struct Compiler<'a> {
    ir: &'a RouterIr,
    env: &'a Env<'a>,
    subs: HashMap<String, usize>,
    stats: RouterStats,
}

impl<'a> Compiler<'a> {
    fn outbound(&self, tag: &str) -> Result<u32, String> {
        self.env.outbounds.iter().position(|o| o == tag).map(|p| p as u32).ok_or_else(|| format!("unknown outbound {tag:?}"))
    }

    fn target(&self, target: &Target) -> Result<CTarget, String> {
        Ok(match target {
            Target::Outbound(tag) => CTarget::Action(Action::Outbound(self.outbound(tag)?)),
            Target::Reject => CTarget::Action(Action::Reject),
            Target::RejectDrop => CTarget::Action(Action::RejectDrop),
            Target::HijackDns => CTarget::Action(Action::HijackDns),
            Target::Pass => CTarget::Pass,
            Target::SubRule(name) => CTarget::Sub(*self.subs.get(name).ok_or_else(|| format!("unknown sub-rule {name:?}"))?),
        })
    }

    /// Pushes the entries of `cond` when it is a plain union of domain and IP
    /// entries; `false` (entries pushed so far to be discarded) otherwise.
    fn flatten(&self, cond: &Cond, xf: Xf, out: &mut Vec<Entry>, in_set: bool) -> Result<bool, String> {
        match cond {
            Cond::Domain(d) => out.push(Entry::Full(d.clone())),
            Cond::DomainSuffix(d) => out.push(Entry::Suffix(d.clone())),
            Cond::DomainSubdomain(d) => out.push(Entry::Sub(d.clone())),
            Cond::DomainKeyword(k) => out.push(Entry::Keyword(k.to_ascii_lowercase())),
            Cond::DomainRegex(r) => out.push(Entry::Regex(r.clone())),
            Cond::DomainWildcard(w) => out.push(Entry::Wild(w.clone())),
            Cond::GeoSite(name) => {
                let db = self.env.geosite.ok_or_else(|| format!("GEOSITE {name}: no geosite data is loaded"))?;
                for d in db.domains(name)? {
                    out.push(match d {
                        SiteDomain::Keyword(k) => Entry::Keyword(k.to_ascii_lowercase()),
                        SiteDomain::Regex(r) => Entry::Regex(r),
                        SiteDomain::Suffix(s) => Entry::Suffix(domain::normalize_value(&s)?),
                        SiteDomain::Full(f) => Entry::Full(domain::normalize_value(&f)?),
                    });
                }
            }
            Cond::IpCidr { net, side } => out.push(Entry::Ip(*net, xf.side(*side))),
            Cond::IpPrivate { side } => {
                let side = xf.side(*side);
                for net in ip::PRIVATE_NETS {
                    out.push(Entry::Ip(IpNet::parse(net)?, side));
                }
            }
            Cond::GeoIp { code, side } => {
                let side = xf.side(*side);
                // mihomo's `LAN` is the private ranges whatever the data; `private`
                // is a code like any other when the data has it.
                let builtin = code.eq_ignore_ascii_case("lan");
                if let Some(dat) = self.env.geoip.filter(|d| !builtin && d.has(code)) {
                    for net in dat.nets(code)? {
                        out.push(Entry::Ip(net, side));
                    }
                } else if !builtin && self.env.mmdb.is_some() {
                    return Ok(false);
                } else if is_private_code(code) {
                    for net in ip::PRIVATE_NETS {
                        out.push(Entry::Ip(IpNet::parse(net)?, side));
                    }
                } else {
                    return Err(format!("GEOIP {code}: no geoip data has this code"));
                }
            }
            Cond::RuleSet { name, src_ip, swap_ports, no_resolve } => {
                if in_set {
                    return Err(format!("rule set {name:?} is referenced from inside a rule set"));
                }
                let set = self.ir.rule_sets.get(name).ok_or_else(|| format!("unknown rule set {name:?}"))?;
                let inner = Xf { src_ip: *src_ip, swap_ports: *swap_ports, no_resolve: *no_resolve };
                for c in &set.rules {
                    if !self.flatten(c, inner, out, true)? {
                        return Ok(false);
                    }
                }
            }
            Cond::Or(children) => {
                for c in children {
                    if !self.flatten(c, xf, out, in_set)? {
                        return Ok(false);
                    }
                }
            }
            _ => return Ok(false),
        }
        Ok(true)
    }

    fn set_node(&mut self, entries: Vec<Entry>) -> Result<Node, String> {
        let mut domains = DomainIndexBuilder::default();
        let mut ips: [Option<IpIndex>; 4] = Default::default();
        for e in entries {
            match e {
                Entry::Ip(net, side) => ips[slot(side)].get_or_insert_with(IpIndex::new).insert(net, 0),
                other => add_domain(&mut domains, other, 0),
            }
        }
        self.stats.domain_entries += domains.len();
        let domains = if domains.is_empty() { None } else { Some(domains.build()?) };
        for index in ips.iter_mut().flatten() {
            index.shrink();
            self.stats.ip_nodes += index.node_count();
        }
        Ok(Node::Set(Box::new(SetNode { domains, ips })))
    }

    fn compile_or(&mut self, children: &[Cond], xf: Xf, in_set: bool) -> Result<Node, String> {
        let mut entries = Vec::new();
        let mut nodes = Vec::new();
        for c in children {
            let before = entries.len();
            if self.flatten(c, xf, &mut entries, in_set)? {
                continue;
            }
            entries.truncate(before);
            nodes.push(self.compile_node(c, xf, in_set)?);
        }
        if !entries.is_empty() {
            nodes.insert(0, self.set_node(entries)?);
        }
        Ok(if nodes.len() == 1 { nodes.pop().unwrap_or(Node::Any) } else { Node::Or(nodes) })
    }

    fn compile_node(&mut self, cond: &Cond, xf: Xf, in_set: bool) -> Result<Node, String> {
        let mut entries = Vec::new();
        if self.flatten(cond, xf, &mut entries, in_set)? {
            return self.set_node(entries);
        }
        Ok(match cond {
            // Reached only when the geoip.dat has no such code and an mmdb is loaded.
            Cond::GeoIp { code, side } => Node::MmdbGeoIp { code: code.to_ascii_lowercase().into(), side: xf.side(*side) },
            Cond::IpVersion(v) => Node::IpVersion(*v),
            Cond::DstPort(r) if xf.swap_ports => Node::SrcPort(r.clone()),
            Cond::DstPort(r) => Node::DstPort(r.clone()),
            Cond::SrcPort(r) if xf.swap_ports => Node::DstPort(r.clone()),
            Cond::SrcPort(r) => Node::SrcPort(r.clone()),
            Cond::Network(n) => Node::Network(*n),
            Cond::Protocol(p) => Node::Protocol(p.to_ascii_lowercase().into()),
            Cond::ProcessName(p) => Node::ProcessName(p.as_str().into()),
            Cond::PackageName(p) => Node::PackageName(p.as_str().into()),
            Cond::PackageNameRegex(p) => Node::PackageRegex(Regex::new(p).map_err(|e| format!("package name regex: {e}"))?),
            Cond::Uid(r) => Node::Uid(r.clone()),
            Cond::InboundTag(tag) => Node::Inbound(
                self.env.inbounds.iter().position(|t| t == tag).map(|p| p as u32).ok_or_else(|| format!("unknown inbound {tag:?}"))?,
            ),
            Cond::RuleSet { name, src_ip, swap_ports, no_resolve } => {
                if in_set {
                    return Err(format!("rule set {name:?} is referenced from inside a rule set"));
                }
                let ir = self.ir;
                let set = ir.rule_sets.get(name).ok_or_else(|| format!("unknown rule set {name:?}"))?;
                let inner = Xf { src_ip: *src_ip, swap_ports: *swap_ports, no_resolve: *no_resolve };
                self.compile_or(&set.rules, inner, true)?
            }
            Cond::And(children) => {
                let mut nodes = Vec::with_capacity(children.len());
                for c in children {
                    nodes.push(self.compile_node(c, xf, in_set)?);
                }
                Node::And(nodes)
            }
            Cond::Or(children) => self.compile_or(children, xf, in_set)?,
            Cond::Not(c) => Node::Not(Box::new(self.compile_node(c, xf, in_set)?)),
            Cond::Any => Node::Any,
            other => return Err(format!("internal: condition {other:?} did not compile")),
        })
    }

    fn compile_program(&mut self, rules: &[Rule]) -> Result<Program, String> {
        if rules.len() >= u32::MAX as usize {
            return Err("too many rules".into());
        }
        let mut domains = DomainIndexBuilder::default();
        let mut src_ips = IpIndex::new();
        let mut dst_ips = IpIndex::new();
        let mut dst_resolve = IpIndex::new();
        let mut resolve_min = u32::MAX;
        let mut always = u32::MAX;
        let mut generic = Vec::new();
        let mut compiled = Vec::with_capacity(rules.len());
        for (i, rule) in rules.iter().enumerate() {
            let at = |e: String| format!("rule {} ({}): {e}", i + 1, rule.source);
            let index = i as u32;
            let target = self.target(&rule.target).map_err(at)?;
            if let CTarget::Action(_) = target {
                if matches!(rule.cond, Cond::Any) {
                    always = always.min(index);
                    compiled.push(CRule { node: None, target });
                    self.stats.indexed += 1;
                    continue;
                }
                let mut entries = Vec::new();
                if self.flatten(&rule.cond, Xf::default(), &mut entries, false).map_err(at)? {
                    for e in entries {
                        match e {
                            Entry::Ip(net, side) => match slot(side) {
                                0 => src_ips.insert(net, index),
                                1 => dst_ips.insert(net, index),
                                _ => {
                                    dst_resolve.insert(net, index);
                                    resolve_min = resolve_min.min(index);
                                }
                            },
                            other => {
                                add_domain(&mut domains, other, index);
                                self.stats.domain_entries += 1;
                            }
                        }
                    }
                    compiled.push(CRule { node: None, target });
                    self.stats.indexed += 1;
                    continue;
                }
            }
            let node = self.compile_node(&rule.cond, Xf::default(), false).map_err(at)?;
            generic.push(index);
            compiled.push(CRule { node: Some(node), target });
            self.stats.generic += 1;
        }
        for index in [&mut src_ips, &mut dst_ips, &mut dst_resolve] {
            index.shrink();
            self.stats.ip_nodes += index.node_count();
        }
        Ok(Program { rules: compiled, domains: domains.build()?, src_ips, dst_ips, dst_resolve, resolve_min, always, generic })
    }
}

fn add_domain(b: &mut DomainIndexBuilder, e: Entry, value: u32) {
    match e {
        Entry::Full(d) => b.full(&d, value),
        Entry::Suffix(d) => b.suffix(&d, value),
        Entry::Sub(d) => b.subdomain(&d, value),
        Entry::Keyword(k) => b.keyword(&k, value),
        Entry::Regex(r) => b.regex(&r, value),
        Entry::Wild(w) => b.wildcard(&w, value),
        Entry::Ip(..) => {}
    }
}

/// Sub-rule lists may nest but not loop.
fn check_sub_rule_cycles(ir: &RouterIr) -> Result<(), String> {
    fn refs(rules: &[Rule]) -> impl Iterator<Item = &str> {
        rules.iter().filter_map(|r| match &r.target {
            Target::SubRule(name) => Some(name.as_str()),
            _ => None,
        })
    }
    fn visit<'r>(name: &'r str, ir: &'r RouterIr, state: &mut BTreeMap<&'r str, bool>) -> Result<(), String> {
        match state.get(name) {
            Some(true) => return Ok(()),
            Some(false) => return Err(format!("sub-rule {name:?} refers back to itself")),
            None => {}
        }
        state.insert(name, false);
        if let Some(rules) = ir.sub_rules.get(name) {
            for next in refs(rules) {
                visit(next, ir, state)?;
            }
        }
        state.insert(name, true);
        Ok(())
    }
    let mut state = BTreeMap::new();
    for name in ir.sub_rules.keys() {
        visit(name, ir, &mut state)?;
    }
    Ok(())
}

pub fn compile(ir: &RouterIr, env: &Env) -> Result<Router, String> {
    check_sub_rule_cycles(ir)?;
    let subs: HashMap<String, usize> = ir.sub_rules.keys().enumerate().map(|(i, k)| (k.clone(), i)).collect();
    let mut c = Compiler { ir, env, subs, stats: RouterStats::default() };
    let main = c.compile_program(&ir.rules)?;
    let mut sub_programs = Vec::with_capacity(ir.sub_rules.len());
    for (name, rules) in &ir.sub_rules {
        sub_programs.push(c.compile_program(rules).map_err(|e| format!("sub-rule {name}: {e}"))?);
    }
    let fallback = ir.final_target.clone().or_else(|| env.default_final.clone()).ok_or("no final target")?;
    let final_action = match c.target(&fallback).map_err(|e| format!("final: {e}"))? {
        CTarget::Action(a) => a,
        _ => return Err("the final target must be an outbound or a reject".into()),
    };
    let mut stats = c.stats;
    stats.rules = ir.rules.len() + ir.sub_rules.values().map(Vec::len).sum::<usize>();
    stats.sub_programs = sub_programs.len();
    Ok(Router { main, subs: sub_programs, final_action, mmdb: env.mmdb.clone(), stats })
}

enum Resolved {
    NotYet,
    Hit(usize, [IpAddr; MAX_CACHED_ADDRS]),
    Miss,
}

struct Ctx<'c> {
    conn: &'c Conn<'c>,
    domain: Option<&'c str>,
    src: Option<IpAddr>,
    dst: Option<IpAddr>,
    dns: &'c dyn DnsView,
    mmdb: Option<&'c Mmdb>,
    resolved: Resolved,
    dns_miss: bool,
}

impl Ctx<'_> {
    /// The cached addresses of the domain (looked up once per decision).
    fn resolve(&mut self) -> Option<&[IpAddr]> {
        if let Resolved::NotYet = self.resolved {
            let domain = self.domain?;
            let mut buf = [IpAddr::V4(Ipv4Addr::UNSPECIFIED); MAX_CACHED_ADDRS];
            self.resolved = match self.dns.lookup(domain, &mut buf) {
                Some(n) => {
                    let n = n.min(MAX_CACHED_ADDRS);
                    for a in buf.iter_mut().take(n) {
                        *a = ip::unmap(*a);
                    }
                    Resolved::Hit(n, buf)
                }
                None => {
                    self.dns.prefetch(domain);
                    Resolved::Miss
                }
            };
        }
        match &self.resolved {
            Resolved::Hit(n, buf) => Some(&buf[..*n]),
            _ => {
                self.dns_miss = true;
                None
            }
        }
    }
}

fn addr_tri(ctx: &mut Ctx, side: IpSide, pred: impl Fn(IpAddr) -> bool) -> Tri {
    match side {
        IpSide::Src => tri(ctx.src.is_some_and(&pred)),
        IpSide::Dst { resolve } => {
            if let Some(ip) = ctx.dst {
                return tri(pred(ip));
            }
            let Some(policy) = resolve else { return Tri::F };
            if ctx.domain.is_none() {
                return Tri::F;
            }
            match ctx.resolve() {
                Some(ips) => tri(ips.iter().any(|ip| pred(*ip))),
                None => match policy {
                    MissPolicy::Skip => Tri::U,
                    MissPolicy::NoResolve => Tri::F,
                },
            }
        }
    }
}

fn eval(node: &Node, ctx: &mut Ctx) -> Tri {
    let conn = ctx.conn;
    match node {
        Node::Any => Tri::T,
        Node::Set(set) => {
            if let (Some(d), Some(index)) = (ctx.domain, &set.domains) {
                if index.contains(d) {
                    return Tri::T;
                }
            }
            let mut unknown = false;
            for (slot, index) in set.ips.iter().enumerate() {
                let Some(index) = index else { continue };
                match addr_tri(ctx, slot_side(slot), |ip| index.contains(ip)) {
                    Tri::T => return Tri::T,
                    Tri::U => unknown = true,
                    Tri::F => {}
                }
            }
            if unknown {
                Tri::U
            } else {
                Tri::F
            }
        }
        Node::MmdbGeoIp { code, side } => {
            let mmdb = ctx.mmdb;
            match mmdb {
                Some(db) => addr_tri(ctx, *side, |ip| db.has_code(ip, code)),
                None => Tri::F,
            }
        }
        Node::DstPort(r) => tri(r.contains(u32::from(conn.dst_port))),
        Node::SrcPort(r) => tri(r.contains(u32::from(conn.src_port))),
        Node::Network(n) => tri(conn.network == Some(*n)),
        Node::IpVersion(v) => tri(ctx.dst.map(|ip| if ip.is_ipv4() { 4 } else { 6 }) == Some(*v)),
        Node::Protocol(p) => tri(conn.protocol.is_some_and(|x| x.eq_ignore_ascii_case(p))),
        Node::ProcessName(p) => tri(conn.process.is_some_and(|x| x.eq_ignore_ascii_case(p)) || conn.package.is_some_and(|x| x == &**p)),
        Node::PackageName(p) => tri(conn.package.is_some_and(|x| x == &**p)),
        Node::PackageRegex(re) => tri(conn.package.is_some_and(|x| re.is_match(x))),
        Node::Uid(r) => tri(conn.uid.is_some_and(|u| r.contains(u))),
        Node::Inbound(i) => tri(conn.inbound == Some(*i)),
        Node::And(nodes) => {
            let mut unknown = false;
            for n in nodes {
                match eval(n, ctx) {
                    Tri::F => return Tri::F,
                    Tri::U => unknown = true,
                    Tri::T => {}
                }
            }
            if unknown {
                Tri::U
            } else {
                Tri::T
            }
        }
        Node::Or(nodes) => {
            let mut unknown = false;
            for n in nodes {
                match eval(n, ctx) {
                    Tri::T => return Tri::T,
                    Tri::U => unknown = true,
                    Tri::F => {}
                }
            }
            if unknown {
                Tri::U
            } else {
                Tri::F
            }
        }
        Node::Not(n) => match eval(n, ctx) {
            Tri::T => Tri::F,
            Tri::F => Tri::T,
            Tri::U => Tri::U,
        },
    }
}

impl Router {
    pub fn stats(&self) -> RouterStats {
        self.stats
    }

    pub fn final_action(&self) -> Action {
        self.final_action
    }

    /// The decision for one connection: pure, synchronous, no I/O (the DNS
    /// view is a cache).
    pub fn decide(&self, conn: &Conn, dns: &dyn DnsView) -> Verdict {
        let normalized = conn.domain.and_then(domain::normalize);
        let mut ctx = Ctx {
            conn,
            domain: normalized.as_deref(),
            src: conn.src.map(ip::unmap),
            dst: conn.dst.map(ip::unmap),
            dns,
            mmdb: self.mmdb.as_deref(),
            resolved: Resolved::NotYet,
            dns_miss: false,
        };
        match self.run(&self.main, &mut ctx, 0) {
            Some((rule, action)) => Verdict { action, rule: Some(rule), dns_miss: ctx.dns_miss },
            None => Verdict { action: self.final_action, rule: None, dns_miss: ctx.dns_miss },
        }
    }

    fn run(&self, prog: &Program, ctx: &mut Ctx, depth: u32) -> Option<(u32, Action)> {
        let mut cand = prog.always;
        if let Some(d) = ctx.domain {
            if let Some(v) = prog.domains.lookup(d) {
                cand = cand.min(v);
            }
        }
        if let Some(ip) = ctx.src {
            if let Some(v) = prog.src_ips.lookup(ip) {
                cand = cand.min(v);
            }
        }
        if let Some(ip) = ctx.dst {
            if let Some(v) = prog.dst_ips.lookup(ip) {
                cand = cand.min(v);
            }
            if let Some(v) = prog.dst_resolve.lookup(ip) {
                cand = cand.min(v);
            }
        } else if ctx.domain.is_some() && prog.resolve_min < cand {
            if let Some(ips) = ctx.resolve() {
                for ip in ips {
                    if let Some(v) = prog.dst_resolve.lookup(*ip) {
                        cand = cand.min(v);
                    }
                }
            }
        }
        for &gi in &prog.generic {
            if gi >= cand {
                break;
            }
            let rule = &prog.rules[gi as usize];
            let Some(node) = &rule.node else { continue };
            if eval(node, ctx) != Tri::T {
                continue;
            }
            match rule.target {
                CTarget::Action(a) => return Some((gi, a)),
                CTarget::Pass => continue,
                CTarget::Sub(s) => {
                    if depth < MAX_SUB_DEPTH {
                        if let Some((_, a)) = self.run(&self.subs[s], ctx, depth + 1) {
                            return Some((gi, a));
                        }
                    }
                }
            }
        }
        if cand != u32::MAX {
            if let CTarget::Action(a) = prog.rules[cand as usize].target {
                return Some((cand, a));
            }
        }
        None
    }
}
