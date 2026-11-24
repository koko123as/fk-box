//! Proxy groups run by the brain (stage 5): `select`, `url-test`,
//! `fallback` and `load-balance`, with health checks through the host's
//! `probe(outbound_tag, url)`. Contract: docs/architecture/brain-groups.md.
//!
//! The rules are compiled against fkbrain's outbound table followed by the
//! group names, so a rule naming a group yields a virtual index past the
//! real outbounds. Every snapshot carries a [`GroupTable`] (the current
//! choice of each group, immutable) that decide follows from a virtual
//! index to a real one: one more branch per decision, no lock, no IO. A
//! change (a manual selection, a health check result) builds a new table
//! and swaps it into a new snapshot that shares the router, so decide and
//! the DNS route see either the old choices or the new ones, never a mix,
//! and nothing restarts.
//!
//! The control state ([`Groups`]) lives behind a mutex: the current member
//! of each group, the probe samples, the persisted selections and the
//! last test time. Only the control path and the test scheduler take it.

pub mod ffi;

#[cfg(test)]
mod tests;

use std::collections::{BTreeMap, HashMap};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::{Duration, Instant};

use fkbox_client_core::config::brain::{Group, GroupKind};
use fkbox_client_core::rules::engine::{Action, Conn};
use serde_json::{json, Value};

use crate::abi::*;
use crate::host::{HostApi, HostError};

/// mihomo's defaults for a group without `url` / `interval` / `timeout`.
pub const DEFAULT_URL: &str = "https://www.gstatic.com/generate_204";
pub const DEFAULT_INTERVAL_SECS: u64 = 300;
pub const DEFAULT_TIMEOUT_MS: u32 = 5000;
/// url-test tolerance when the source sets none (sing-box's default; mihomo
/// has none, which switches on every 1 ms difference).
pub const DEFAULT_TOLERANCE_MS: u32 = 50;
/// Probes in flight at once, over all groups.
pub const PROBE_CONCURRENCY: usize = 8;
/// Tries of consistent hashing before it takes the first alive member (mihomo's `maxRetry`).
const HASH_RETRIES: u64 = 5;

/// One member of a group, resolved against fkbrain's table at plan time.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Member {
    /// A real outbound (a node, or `fk-direct` for DIRECT / COMPATIBLE / PASS).
    Out(u32),
    /// Another group (index into [`GroupSet::specs`]).
    Group(u32),
    Reject,
    RejectDrop,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Strategy {
    /// The destination (its last two labels, else its address) picks the member (jump hash).
    ConsistentHashing,
    RoundRobin,
    /// Source address and destination pick the member: one client keeps one
    /// member per site. mihomo adds an LRU on top; without it the choice is
    /// stable as long as the alive set is.
    StickySessions,
}

impl Strategy {
    fn parse(value: Option<&str>) -> Result<Strategy, String> {
        match value.unwrap_or("consistent-hashing") {
            "consistent-hashing" => Ok(Strategy::ConsistentHashing),
            "round-robin" => Ok(Strategy::RoundRobin),
            "sticky-sessions" => Ok(Strategy::StickySessions),
            other => Err(format!("unknown load-balance strategy {other:?}")),
        }
    }

    pub fn as_str(self) -> &'static str {
        match self {
            Strategy::ConsistentHashing => "consistent-hashing",
            Strategy::RoundRobin => "round-robin",
            Strategy::StickySessions => "sticky-sessions",
        }
    }
}

/// mihomo `expected-status` (`*`, `204`, `200/204`, `200-299`); empty: any status.
fn parse_expected(value: Option<&str>) -> Result<Vec<(u16, u16)>, String> {
    let Some(text) = value.map(str::trim).filter(|t| !t.is_empty() && *t != "*") else { return Ok(Vec::new()) };
    let mut ranges = Vec::new();
    for part in text.split('/') {
        let bad = || format!("expected-status {text:?}");
        let (low, high) = match part.trim().split_once('-') {
            Some((a, b)) => (a.trim().parse::<u16>().map_err(|_| bad())?, b.trim().parse::<u16>().map_err(|_| bad())?),
            None => {
                let code = part.trim().parse::<u16>().map_err(|_| bad())?;
                (code, code)
            }
        };
        if low > high {
            return Err(bad());
        }
        ranges.push((low, high));
    }
    Ok(ranges)
}

/// A group as the brain runs it.
#[derive(Debug, Clone)]
pub struct GroupSpec {
    pub name: String,
    pub kind: GroupKind,
    pub members: Vec<Member>,
    /// The members as the source names them (the UI's names).
    pub member_names: Vec<String>,
    pub url: String,
    /// Zero: no periodic test.
    pub interval: Duration,
    pub tolerance_ms: u32,
    pub timeout_ms: u32,
    /// Periodic tests only while the group is in use.
    pub lazy: bool,
    pub strategy: Strategy,
    pub expected: Vec<(u16, u16)>,
    /// The member a select group starts on (sing-box `default`, else the first).
    pub default: usize,
    pub hidden: bool,
    pub icon: Option<String>,
}

impl GroupSpec {
    /// url-test, fallback and load-balance choose by themselves; AGENTS.md:
    /// automatic groups cannot be selected by hand.
    pub fn automatic(&self) -> bool {
        self.kind != GroupKind::Select
    }

    fn expects(&self, status: i32) -> bool {
        status > 0 && (self.expected.is_empty() || self.expected.iter().any(|(l, h)| (*l as i32..=*h as i32).contains(&status)))
    }
}

/// The groups of one configuration, resolved against fkbrain's table.
#[derive(Debug, Clone)]
pub struct GroupSet {
    /// fkbrain's outbound table; virtual indices start at its length.
    pub outbounds: Vec<String>,
    pub specs: Vec<GroupSpec>,
    /// Members before the groups that contain them (evaluation order).
    order: Vec<u32>,
    index: HashMap<String, u32>,
    base: u32,
}

impl Default for GroupSet {
    /// No groups: nothing is a virtual index.
    fn default() -> GroupSet {
        GroupSet { outbounds: Vec::new(), specs: Vec::new(), order: Vec::new(), index: HashMap::new(), base: u32::MAX }
    }
}

impl GroupSet {
    /// Resolves the imported groups. Fails on an unknown member, a group
    /// named like an outbound, a group loop or an unknown strategy.
    pub fn build(groups: &[Group], outbounds: &[String], direct_tag: &str) -> Result<GroupSet, String> {
        let direct = outbounds.iter().position(|t| t == direct_tag).ok_or("no direct outbound in the table")? as u32;
        let mut index = HashMap::new();
        for (i, group) in groups.iter().enumerate() {
            if outbounds.contains(&group.name) {
                return Err(format!("group {:?} has the name of an outbound", group.name));
            }
            if index.insert(group.name.clone(), i as u32).is_some() {
                return Err(format!("group {:?} is defined twice", group.name));
            }
        }
        let mut specs = Vec::with_capacity(groups.len());
        for group in groups {
            let mut names = group.members.clone();
            if names.is_empty() {
                // mihomo: a group left empty by its filters holds COMPATIBLE (direct).
                names.push("COMPATIBLE".into());
            }
            let mut members = Vec::with_capacity(names.len());
            for name in &names {
                let member = match name.as_str() {
                    "DIRECT" | "COMPATIBLE" | "PASS" => Member::Out(direct),
                    "REJECT" => Member::Reject,
                    "REJECT-DROP" => Member::RejectDrop,
                    _ => match (outbounds.iter().position(|t| t == name), index.get(name)) {
                        (Some(i), _) => Member::Out(i as u32),
                        (None, Some(g)) => Member::Group(*g),
                        (None, None) => return Err(format!("group {:?}: unknown member {name:?}", group.name)),
                    },
                };
                members.push(member);
            }
            let p = &group.params;
            let default = p.default.as_ref().and_then(|d| names.iter().position(|n| n == d)).unwrap_or(0);
            specs.push(GroupSpec {
                name: group.name.clone(),
                kind: group.kind,
                members,
                member_names: names,
                url: p.url.clone().filter(|u| !u.is_empty()).unwrap_or_else(|| DEFAULT_URL.to_string()),
                interval: Duration::from_secs(p.interval_secs.unwrap_or(DEFAULT_INTERVAL_SECS)),
                tolerance_ms: p.tolerance_ms.map_or(DEFAULT_TOLERANCE_MS, |t| t.min(u32::MAX as u64) as u32),
                timeout_ms: p.timeout_ms.filter(|t| *t > 0).map_or(DEFAULT_TIMEOUT_MS, |t| t.min(60_000) as u32),
                lazy: p.lazy.unwrap_or(true),
                strategy: if group.kind == GroupKind::LoadBalance {
                    Strategy::parse(p.strategy.as_deref())?
                } else {
                    Strategy::ConsistentHashing
                },
                expected: parse_expected(p.expected_status.as_deref()).map_err(|e| format!("group {:?}: {e}", group.name))?,
                default,
                hidden: p.hidden,
                icon: p.icon.clone(),
            });
        }
        let order = topo_order(&specs)?;
        Ok(GroupSet { outbounds: outbounds.to_vec(), specs, order, index, base: outbounds.len() as u32 })
    }

    /// The names the rules compile against: the outbounds, then the groups.
    pub fn target_names(&self) -> Vec<String> {
        self.outbounds.iter().cloned().chain(self.specs.iter().map(|s| s.name.clone())).collect()
    }

    pub fn group(&self, name: &str) -> Option<u32> {
        self.index.get(name).copied()
    }

    pub fn is_empty(&self) -> bool {
        self.specs.is_empty()
    }
}

/// Members first; a group reachable from itself is an error.
fn topo_order(specs: &[GroupSpec]) -> Result<Vec<u32>, String> {
    // 0 unvisited, 1 on the path, 2 done.
    let mut state = vec![0u8; specs.len()];
    let mut order = Vec::with_capacity(specs.len());
    fn visit(g: usize, specs: &[GroupSpec], state: &mut [u8], order: &mut Vec<u32>) -> Result<(), String> {
        match state[g] {
            2 => return Ok(()),
            1 => return Err(format!("group loop at {:?}", specs[g].name)),
            _ => {}
        }
        state[g] = 1;
        for member in &specs[g].members {
            if let Member::Group(h) = member {
                visit(*h as usize, specs, state, order)?;
            }
        }
        state[g] = 2;
        order.push(g as u32);
        Ok(())
    }
    for g in 0..specs.len() {
        visit(g, specs, &mut state, &mut order)?;
    }
    Ok(order)
}

/// What decide follows for one group.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Entry {
    To(Member),
    Balance { strategy: Strategy, members: Vec<Member>, alive: Vec<bool> },
}

/// The groups' current choices, immutable once published; decide reads it
/// from the snapshot it loaded.
#[derive(Debug)]
pub struct GroupTable {
    base: u32,
    pub entries: Vec<Entry>,
    /// Set by decide when it goes through a group (lazy health checks).
    touched: Arc<Vec<AtomicBool>>,
    /// Round-robin counters, shared by the tables of one configuration.
    rr: Arc<Vec<AtomicUsize>>,
}

impl Default for GroupTable {
    fn default() -> GroupTable {
        GroupTable { base: u32::MAX, entries: Vec::new(), touched: Arc::default(), rr: Arc::default() }
    }
}

const FNV_OFFSET: u64 = 0xcbf2_9ce4_8422_2325;
const FNV_PRIME: u64 = 0x0000_0100_0000_01b3;

fn fnv(mut hash: u64, bytes: &[u8]) -> u64 {
    for b in bytes {
        hash ^= *b as u64;
        hash = hash.wrapping_mul(FNV_PRIME);
    }
    hash
}

/// Lamping and Veach's jump consistent hash (what mihomo's load-balance uses).
pub fn jump_hash(mut key: u64, buckets: usize) -> usize {
    let (mut b, mut j) = (-1i64, 0i64);
    while j < buckets as i64 {
        b = j;
        key = key.wrapping_mul(2_862_933_555_777_941_757).wrapping_add(1);
        j = ((b + 1) as f64 * ((1u64 << 31) as f64 / ((key >> 33) + 1) as f64)) as i64;
    }
    b.max(0) as usize
}

/// The destination's key: the last two labels of the name (mihomo takes the
/// registrable domain from the public suffix list; two labels is the cheap
/// approximation), else the address.
fn destination_hash(conn: &Conn) -> u64 {
    if let Some(domain) = conn.domain.filter(|d| !d.is_empty()) {
        let domain = domain.trim_end_matches('.');
        let cut = domain.rmatch_indices('.').nth(1).map_or(0, |(i, _)| i + 1);
        return fnv(FNV_OFFSET, domain[cut..].as_bytes());
    }
    match conn.dst {
        Some(std::net::IpAddr::V4(v4)) => fnv(FNV_OFFSET, &v4.octets()),
        Some(std::net::IpAddr::V6(v6)) => fnv(FNV_OFFSET, &v6.octets()),
        None => FNV_OFFSET,
    }
}

impl GroupTable {
    /// Follows a rule's action through the groups to a real outbound (or a
    /// reject member). Lock-free; never blocks.
    pub fn resolve(&self, action: Action, conn: &Conn) -> Action {
        let Action::Outbound(index) = action else { return action };
        if index < self.base {
            return action;
        }
        let mut group = (index - self.base) as usize;
        // The groups form no loop (checked at plan time); the bound is a guard.
        for _ in 0..=self.entries.len() {
            let Some(entry) = self.entries.get(group) else { break };
            if let Some(flag) = self.touched.get(group) {
                if !flag.load(Ordering::Relaxed) {
                    flag.store(true, Ordering::Relaxed);
                }
            }
            let member = match entry {
                Entry::To(member) => *member,
                Entry::Balance { strategy, members, alive } => self.balance(group, *strategy, members, alive, conn),
            };
            match member {
                Member::Out(i) => return Action::Outbound(i),
                Member::Reject => return Action::Reject,
                Member::RejectDrop => return Action::RejectDrop,
                Member::Group(next) => group = next as usize,
            }
        }
        Action::Reject
    }

    fn balance(&self, group: usize, strategy: Strategy, members: &[Member], alive: &[bool], conn: &Conn) -> Member {
        let n = members.len();
        if n == 0 {
            return Member::Reject;
        }
        let first_alive = || alive.iter().position(|a| *a).map_or(members[0], |i| members[i]);
        match strategy {
            Strategy::RoundRobin => {
                let start = self.rr.get(group).map_or(0, |c| c.fetch_add(1, Ordering::Relaxed));
                (0..n).map(|k| (start + k) % n).find(|i| alive[*i]).map_or(members[start % n], |i| members[i])
            }
            Strategy::ConsistentHashing | Strategy::StickySessions => {
                let mut key = destination_hash(conn);
                if strategy == Strategy::StickySessions {
                    key = match conn.src {
                        Some(std::net::IpAddr::V4(v4)) => fnv(key, &v4.octets()),
                        Some(std::net::IpAddr::V6(v6)) => fnv(key, &v6.octets()),
                        None => key,
                    };
                }
                for retry in 0..HASH_RETRIES {
                    let i = jump_hash(key.wrapping_add(retry), n);
                    if alive[i] {
                        return members[i];
                    }
                }
                first_alive()
            }
        }
    }

    /// Whether decide went through `group` since the last call (and clears it).
    fn take_touched(&self, group: usize) -> bool {
        self.touched.get(group).is_some_and(|f| f.swap(false, Ordering::Relaxed))
    }
}

/// What a probe said about one outbound at one URL.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Sample {
    /// `None`: the probe failed (no response, timeout, unexpected status).
    pub delay_ms: Option<u32>,
    pub at: Instant,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Health {
    Unknown,
    Alive(u32),
    Dead,
}

impl Health {
    /// Untested counts as alive, as in mihomo (a fresh proxy is alive).
    fn usable(self) -> bool {
        self != Health::Dead
    }
}

/// One delay test to run.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TestJob {
    pub tag: String,
    pub url: String,
    pub timeout_ms: u32,
    /// The group whose expected status applies.
    pub group: u32,
}

/// Which groups a test round covers.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Force {
    /// Groups whose interval elapsed (and, when lazy, that were used), plus untested ones.
    Due,
    One(u32),
    All,
}

/// Something that can run a delay test (the host; a fake in tests).
pub trait Prober: Sync {
    fn probe(&self, tag: &str, url: &str, timeout_ms: u32) -> Result<FkbProbeResult, HostError>;
}

impl Prober for HostApi {
    fn probe(&self, tag: &str, url: &str, timeout_ms: u32) -> Result<FkbProbeResult, HostError> {
        HostApi::probe(self, tag, url, timeout_ms)
    }
}

/// Runs the jobs with at most `limit` in flight; results in job order.
pub fn probe_all(prober: &dyn Prober, jobs: &[TestJob], limit: usize) -> Vec<Result<FkbProbeResult, HostError>> {
    let workers = limit.max(1).min(jobs.len());
    let next = AtomicUsize::new(0);
    let results: Mutex<Vec<Option<Result<FkbProbeResult, HostError>>>> = Mutex::new(vec![None; jobs.len()]);
    std::thread::scope(|scope| {
        for _ in 0..workers {
            scope.spawn(|| loop {
                let i = next.fetch_add(1, Ordering::Relaxed);
                let Some(job) = jobs.get(i) else { break };
                let outcome = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| prober.probe(&job.tag, &job.url, job.timeout_ms)))
                    .unwrap_or_else(|_| Err(HostError { code: FKB_E_PANIC, message: "probe panicked".into() }));
                if let Ok(mut r) = results.lock() {
                    r[i] = Some(outcome);
                }
            });
        }
    });
    results
        .into_inner()
        .unwrap_or_default()
        .into_iter()
        .map(|r| r.unwrap_or_else(|| Err(HostError { code: FKB_E_HOST, message: "probe not run".into() })))
        .collect()
}

struct State {
    set: Arc<GroupSet>,
    /// The member each group is on: chosen by hand (select) or by the last
    /// evaluation (url-test, fallback); unused for load-balance.
    current: Vec<usize>,
    last_test: Vec<Option<Instant>>,
    samples: HashMap<(String, String), Sample>,
    store: Option<PathBuf>,
    touched: Arc<Vec<AtomicBool>>,
    rr: Arc<Vec<AtomicUsize>>,
    /// Bumped by every change of the choices.
    version: u32,
}

impl Default for State {
    fn default() -> State {
        State {
            set: Arc::new(GroupSet::default()),
            current: Vec::new(),
            last_test: Vec::new(),
            samples: HashMap::new(),
            store: None,
            touched: Arc::default(),
            rr: Arc::default(),
            version: 0,
        }
    }
}

/// The groups' control state (see the module documentation).
#[derive(Default)]
pub struct Groups {
    state: Mutex<State>,
    /// Counts of the C control path, for the UI and the tests.
    pub probes: AtomicU32,
}

/// An error of the control path: an `FKB_E_*` code and a message.
pub type ControlError = (i32, String);

fn read_store(path: &Path) -> BTreeMap<String, String> {
    let Ok(text) = std::fs::read_to_string(path) else { return BTreeMap::new() };
    let Ok(value) = serde_json::from_str::<Value>(&text) else { return BTreeMap::new() };
    value
        .get("selected")
        .and_then(Value::as_object)
        .map(|m| m.iter().filter_map(|(k, v)| Some((k.clone(), v.as_str()?.to_string()))).collect())
        .unwrap_or_default()
}

fn write_store(path: &Path, selected: &BTreeMap<String, String>) -> std::io::Result<()> {
    let text = serde_json::to_string(&json!({ "version": 1, "selected": selected })).unwrap_or_default();
    let tmp = path.with_extension("tmp");
    std::fs::write(&tmp, text)?;
    std::fs::rename(&tmp, path)
}

impl State {
    fn health(&self, member: Member, url: &str) -> Health {
        match member {
            Member::Reject | Member::RejectDrop => Health::Dead,
            Member::Out(i) => {
                let Some(tag) = self.set.outbounds.get(i as usize) else { return Health::Dead };
                match self.samples.get(&(tag.clone(), url.to_string())) {
                    None => Health::Unknown,
                    Some(Sample { delay_ms: Some(d), .. }) => Health::Alive(*d),
                    Some(Sample { delay_ms: None, .. }) => Health::Dead,
                }
            }
            Member::Group(g) => {
                let spec = &self.set.specs[g as usize];
                if spec.kind == GroupKind::LoadBalance {
                    // Its best member: alive if any is.
                    let all: Vec<Health> = spec.members.iter().map(|m| self.health(*m, url)).collect();
                    let best = all.iter().filter_map(|h| if let Health::Alive(d) = h { Some(*d) } else { None }).min();
                    match best {
                        Some(d) => Health::Alive(d),
                        None if all.iter().any(|h| *h == Health::Unknown) => Health::Unknown,
                        None => Health::Dead,
                    }
                } else {
                    self.health(spec.members[self.current[g as usize]], url)
                }
            }
        }
    }

    /// Re-chooses url-test and fallback members, members before their groups.
    fn evaluate(&mut self) {
        let set = self.set.clone();
        for &g in &set.order {
            let g = g as usize;
            let spec = &set.specs[g];
            let health: Vec<Health> = spec.members.iter().map(|m| self.health(*m, &spec.url)).collect();
            let current = self.current[g];
            let next = match spec.kind {
                GroupKind::Select | GroupKind::LoadBalance => current,
                GroupKind::Fallback => health.iter().position(|h| h.usable()).unwrap_or(0),
                GroupKind::UrlTest => {
                    let fastest =
                        health.iter().enumerate().filter_map(|(i, h)| if let Health::Alive(d) = h { Some((*d, i)) } else { None }).min();
                    match (fastest, health[current]) {
                        // Keep the current member unless another is faster by more than the tolerance.
                        (Some((best, _)), Health::Alive(now)) if now <= best.saturating_add(spec.tolerance_ms) => current,
                        (Some((_, i)), _) => i,
                        // Nothing measured: stay unless the current member is known dead.
                        (None, Health::Dead) => health.iter().position(|h| h.usable()).unwrap_or(current),
                        (None, _) => current,
                    }
                }
            };
            if next != current {
                self.current[g] = next;
                self.version = self.version.wrapping_add(1);
            }
        }
    }

    fn table(&self) -> GroupTable {
        let entries = self
            .set
            .specs
            .iter()
            .enumerate()
            .map(|(g, spec)| match spec.kind {
                GroupKind::LoadBalance => Entry::Balance {
                    strategy: spec.strategy,
                    members: spec.members.clone(),
                    alive: spec.members.iter().map(|m| self.health(*m, &spec.url).usable()).collect(),
                },
                _ => Entry::To(spec.members[self.current[g]]),
            })
            .collect();
        GroupTable { base: self.set.base, entries, touched: self.touched.clone(), rr: self.rr.clone() }
    }

    /// The outbounds a test of `member` measures: a node itself; for a
    /// group, what it routes to now (all members of a load-balance group).
    fn leaves(&self, member: Member, out: &mut Vec<u32>) {
        match member {
            Member::Out(i) => {
                if !out.contains(&i) {
                    out.push(i);
                }
            }
            Member::Reject | Member::RejectDrop => {}
            Member::Group(g) => {
                let spec = &self.set.specs[g as usize];
                if spec.kind == GroupKind::LoadBalance {
                    for m in &spec.members {
                        self.leaves(*m, out);
                    }
                } else {
                    self.leaves(spec.members[self.current[g as usize]], out);
                }
            }
        }
    }
}

impl Groups {
    fn lock(&self) -> MutexGuard<'_, State> {
        self.state.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    /// Where manual selections are kept (`group_store` of the profile
    /// options); `None` keeps them in memory only.
    pub fn set_store(&self, path: Option<PathBuf>) {
        self.lock().store = path;
    }

    /// Takes over a new configuration's groups. A group keeps its member
    /// across a reload when both names still exist; else a select group
    /// takes the persisted selection, then its default. Every group is
    /// tested again soon (its nodes may have changed).
    pub fn install(&self, set: Arc<GroupSet>) -> Arc<GroupTable> {
        let mut st = self.lock();
        let stored = st.store.as_deref().map(read_store).unwrap_or_default();
        let old = st.set.clone();
        let mut current = Vec::with_capacity(set.specs.len());
        for spec in &set.specs {
            let previous = old.group(&spec.name).map(|g| (&old.specs[g as usize], st.current[g as usize]));
            let kept = previous
                .and_then(|(o, i)| o.member_names.get(i))
                .filter(|_| previous.is_some_and(|(o, _)| o.kind == spec.kind))
                .and_then(|name| spec.member_names.iter().position(|n| n == name));
            let persisted = (spec.kind == GroupKind::Select)
                .then(|| stored.get(&spec.name))
                .flatten()
                .and_then(|name| spec.member_names.iter().position(|n| n == name));
            current.push(kept.or(persisted).unwrap_or(spec.default));
        }
        st.samples.retain(|(tag, _), _| set.outbounds.contains(tag));
        st.current = current;
        st.last_test = vec![None; set.specs.len()];
        st.touched = Arc::new((0..set.specs.len()).map(|_| AtomicBool::new(false)).collect());
        st.rr = Arc::new((0..set.specs.len()).map(|_| AtomicUsize::new(0)).collect());
        st.set = set;
        st.version = st.version.wrapping_add(1);
        st.evaluate();
        Arc::new(st.table())
    }

    /// The table of the current choices.
    pub fn table(&self) -> Arc<GroupTable> {
        Arc::new(self.lock().table())
    }

    pub fn set(&self) -> Arc<GroupSet> {
        self.lock().set.clone()
    }

    /// The member a group is on now (load-balance: none).
    pub fn now(&self, group: &str) -> Option<String> {
        let st = self.lock();
        let g = st.set.group(group)? as usize;
        let spec = &st.set.specs[g];
        (spec.kind != GroupKind::LoadBalance).then(|| spec.member_names[st.current[g]].clone())
    }

    /// A manual selection: select groups only.
    pub fn select(&self, group: &str, member: &str) -> Result<bool, ControlError> {
        let mut st = self.lock();
        let g = st.set.group(group).ok_or_else(|| (FKB_E_NOT_FOUND, format!("unknown group {group:?}")))? as usize;
        let spec = &st.set.specs[g];
        if spec.automatic() {
            return Err((FKB_E_INVALID, format!("group {group:?} is {} and chooses its member itself", spec.kind.as_str())));
        }
        let i = spec
            .member_names
            .iter()
            .position(|n| n == member)
            .ok_or_else(|| (FKB_E_NOT_FOUND, format!("group {group:?} has no member {member:?}")))?;
        let name = spec.name.clone();
        if st.current[g] == i {
            return Ok(false);
        }
        st.current[g] = i;
        st.version = st.version.wrapping_add(1);
        // A select group may sit inside an automatic one.
        st.evaluate();
        if let Some(path) = st.store.clone() {
            let mut stored = read_store(&path);
            stored.insert(name, member.to_string());
            write_store(&path, &stored).map_err(|e| (FKB_E_HOST, format!("group store {}: {e}", path.display())))?;
        }
        Ok(true)
    }

    /// The tests of one round (`now` is the scheduler's clock) and the groups they cover.
    pub fn due(&self, now: Instant, force: Force) -> (Vec<u32>, Vec<TestJob>) {
        let st = self.lock();
        let table = st.table();
        let mut groups = Vec::new();
        let mut jobs: Vec<TestJob> = Vec::new();
        for (g, spec) in st.set.specs.iter().enumerate() {
            let run = match force {
                Force::All => true,
                Force::One(one) => one as usize == g,
                Force::Due => {
                    spec.automatic()
                        && match st.last_test[g] {
                            None => true,
                            Some(last) => {
                                !spec.interval.is_zero()
                                    && now.saturating_duration_since(last) >= spec.interval
                                    && (!spec.lazy || table.take_touched(g))
                            }
                        }
                }
            };
            if !run {
                continue;
            }
            groups.push(g as u32);
            let mut leaves = Vec::new();
            for m in &spec.members {
                st.leaves(*m, &mut leaves);
            }
            for i in leaves {
                let tag = &st.set.outbounds[i as usize];
                if !jobs.iter().any(|j| &j.tag == tag && j.url == spec.url) {
                    jobs.push(TestJob { tag: tag.clone(), url: spec.url.clone(), timeout_ms: spec.timeout_ms, group: g as u32 });
                }
            }
        }
        (groups, jobs)
    }

    /// Records a round's results; returns whether any choice changed.
    pub fn record(&self, groups: &[u32], jobs: &[TestJob], results: Vec<Result<FkbProbeResult, HostError>>, now: Instant) -> bool {
        let mut st = self.lock();
        let before = st.version;
        for (job, result) in jobs.iter().zip(results) {
            let Some(spec) = st.set.specs.get(job.group as usize) else { continue };
            let delay_ms = match result {
                Ok(r) if spec.expects(r.http_status) => Some(r.latency_ms.max(1)),
                _ => None,
            };
            self.probes.fetch_add(1, Ordering::Relaxed);
            st.samples.insert((job.tag.clone(), job.url.clone()), Sample { delay_ms, at: now });
        }
        for g in groups {
            if let Some(slot) = st.last_test.get_mut(*g as usize) {
                *slot = Some(now);
            }
        }
        // A test is fresh use-information: the next periodic one waits for new traffic.
        let table = st.table();
        for g in groups {
            table.take_touched(*g as usize);
        }
        st.evaluate();
        st.version != before
    }

    /// The groups for the UI (replaces the Clash API's /proxies): see
    /// docs/architecture/brain-groups.md for the format.
    pub fn view(&self, now: Instant) -> Value {
        let st = self.lock();
        let groups: Vec<Value> = st
            .set
            .specs
            .iter()
            .enumerate()
            .map(|(g, spec)| {
                let members: Vec<Value> = spec
                    .members
                    .iter()
                    .zip(&spec.member_names)
                    .map(|(m, name)| {
                        let kind = match m {
                            Member::Group(_) => "group",
                            Member::Out(_) if st.set.outbounds.iter().any(|t| t == name) => "node",
                            _ => "builtin",
                        };
                        let (alive, delay) = match st.health(*m, &spec.url) {
                            Health::Unknown => (Value::Null, Value::Null),
                            Health::Alive(d) => (Value::Bool(true), json!(d)),
                            Health::Dead => (Value::Bool(false), Value::Null),
                        };
                        json!({ "name": name, "kind": kind, "alive": alive, "delay_ms": delay })
                    })
                    .collect();
                let now_member = (spec.kind != GroupKind::LoadBalance).then(|| spec.member_names[st.current[g]].clone());
                let tested = st.last_test[g].map(|t| now.saturating_duration_since(t).as_secs());
                json!({
                    "name": spec.name,
                    "type": spec.kind.as_str(),
                    "selectable": !spec.automatic(),
                    "now": now_member,
                    "strategy": (spec.kind == GroupKind::LoadBalance).then(|| spec.strategy.as_str()),
                    "url": spec.url,
                    "interval_secs": spec.interval.as_secs(),
                    "tested_secs_ago": tested,
                    "hidden": spec.hidden,
                    "icon": spec.icon,
                    "members": members,
                })
            })
            .collect();
        json!({ "version": st.version, "groups": groups })
    }
}
