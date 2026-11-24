//! The brain's DNS (stage 4): hosts, an answer cache with TTLs, fake-ip,
//! and the choice of upstream and outbound by the routing rules. The only
//! way out is the host's `exchange(outbound_tag, upstream, message)`, which
//! dials through the sing-box outbound (and so the protected dialer); the
//! brain never opens a socket and never asks the system resolver.
//!
//! - An app's query (fkbrain's port-53 hijack, `FKB_DNS_CLIENT`) for A/AAAA
//!   gets a fake address unless the name is in `fake_ip_filter`; the
//!   connection that follows is decided on the name (reverse lookup).
//! - Everything else (fake-ip off, filtered names, sing-box resolving a
//!   domain destination or a node's server name, the prefetch of names an
//!   IP rule needed) is resolved for real: hosts, then the cache, then the
//!   upstreams of the route — a `policy` entry, the direct upstreams for
//!   names the rules send to `fk-direct` and for the nodes' own server
//!   names, or the proxy upstreams through the node the rules picked.
//! - Other query types are forwarded as they are along the same route
//!   (HTTPS/SVCB of fake-ip names get an empty answer: their address hints
//!   would bypass fake-ip).

pub mod fakeip;
pub mod settings;
pub mod wire;

use std::collections::HashMap;
use std::net::IpAddr;
use std::path::Path;
use std::sync::atomic::{AtomicU16, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, RwLock};
use std::time::{Duration, Instant};

use fkbox_client_core::config::brain::generate::{TAG_BLOCK, TAG_DIRECT};
use fkbox_client_core::rules::engine::domain::normalize;
use fkbox_client_core::rules::engine::{Action, Conn, NoDns};

use crate::abi::*;
use crate::brain::Brain;
use fakeip::FakeIpPool;
use settings::DnsSettings;
use wire::*;

/// TTL of a fake-ip answer: apps ask again soon, the slot stays the same.
pub const FAKE_TTL: u32 = 1;
const HOSTS_TTL: u32 = 60;
/// Answers without records (NXDOMAIN, NODATA) are kept this long.
const NEGATIVE_TTL: u32 = 30;
const MAX_TTL: u32 = 3600;
const CACHE_CAP: usize = 8192;
/// The shortest attempt one upstream gets while time remains.
const MIN_ATTEMPT_MS: u64 = 800;

/// Counters of the brain's DNS (read by tests and the CI report).
#[derive(Default)]
pub struct DnsCounters {
    pub messages: AtomicU64,
    pub fake_answers: AtomicU64,
    pub hosts_answers: AtomicU64,
    pub cache_hits: AtomicU64,
    pub upstream_queries: AtomicU64,
    pub upstream_failures: AtomicU64,
    pub forwarded: AtomicU64,
    pub prefetched: AtomicU64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct DnsCountersSnapshot {
    pub messages: u64,
    pub fake_answers: u64,
    pub hosts_answers: u64,
    pub cache_hits: u64,
    pub upstream_queries: u64,
    pub upstream_failures: u64,
    pub forwarded: u64,
    pub prefetched: u64,
}

impl DnsCounters {
    pub fn snapshot(&self) -> DnsCountersSnapshot {
        let l = |c: &AtomicU64| c.load(Ordering::Relaxed);
        DnsCountersSnapshot {
            messages: l(&self.messages),
            fake_answers: l(&self.fake_answers),
            hosts_answers: l(&self.hosts_answers),
            cache_hits: l(&self.cache_hits),
            upstream_queries: l(&self.upstream_queries),
            upstream_failures: l(&self.upstream_failures),
            forwarded: l(&self.forwarded),
            prefetched: l(&self.prefetched),
        }
    }
}

fn bump(counter: &AtomicU64) {
    counter.fetch_add(1, Ordering::Relaxed);
}

struct Cached {
    rcode: u8,
    addrs: Vec<IpAddr>,
    stored: Instant,
    ttl: u32,
}

/// The DNS part of the brain's state.
pub struct DnsState {
    settings: RwLock<Arc<DnsSettings>>,
    fake: RwLock<Option<FakeIpPool>>,
    answers: Mutex<HashMap<(Box<str>, u16), Cached>>,
    next_id: AtomicU16,
    pub counters: DnsCounters,
}

impl Default for DnsState {
    fn default() -> DnsState {
        let state = DnsState {
            settings: RwLock::new(Arc::new(DnsSettings::default())),
            fake: RwLock::new(None),
            answers: Mutex::new(HashMap::new()),
            next_id: AtomicU16::new(seed()),
            counters: DnsCounters::default(),
        };
        // The defaults are valid by construction.
        let _ = state.apply(DnsSettings::default());
        state
    }
}

fn seed() -> u16 {
    let nanos = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.subsec_nanos()).unwrap_or(0);
    (nanos ^ (nanos >> 16) ^ std::process::id()) as u16
}

/// Why a real resolution failed.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DnsError {
    /// The name cannot be put in a query.
    Invalid,
    /// No host attached (nothing can leave the process).
    NotReady,
    Timeout,
    /// Every upstream failed; the last reason.
    Upstream(i32, String),
}

impl DnsError {
    pub fn code(&self) -> i32 {
        match self {
            DnsError::Invalid => FKB_E_INVALID,
            DnsError::NotReady => FKB_E_NOT_READY,
            DnsError::Timeout => FKB_E_TIMEOUT,
            DnsError::Upstream(code, _) => *code,
        }
    }
}

/// Where a name's queries go.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Route {
    /// The rules reject the name: NXDOMAIN without asking anyone.
    Reject,
    Via {
        outbound: String,
        upstreams: Vec<String>,
    },
}

impl DnsState {
    pub fn settings(&self) -> Arc<DnsSettings> {
        self.settings.read().map(|s| s.clone()).unwrap_or_default()
    }

    /// Installs new settings: the fake-ip pool is kept when its ranges did
    /// not change (rebuilt and reloaded from the store otherwise), the
    /// answer cache is dropped (upstreams may differ).
    pub fn apply(&self, settings: DnsSettings) -> Result<(), String> {
        let ranges = if settings.fake_ip {
            let v4 = fakeip::parse_v4(&settings.fake_ip_range)?;
            let v6 = settings.fake_ip_range6.as_deref().map(fakeip::parse_v6).transpose()?;
            Some((v4, v6))
        } else {
            None
        };
        {
            let mut fake = self.fake.write().map_err(|_| "fake-ip lock poisoned")?;
            let keep = matches!((&*fake, ranges), (Some(pool), Some(r)) if pool.ranges() == r);
            if !keep {
                *fake = ranges.map(|(v4, v6)| {
                    let mut pool = FakeIpPool::new(v4, v6);
                    if let Some(path) = &settings.fake_ip_store {
                        // A store that cannot be read starts empty; the next save replaces it.
                        let _ = pool.load(Path::new(path));
                    }
                    pool
                });
            }
        }
        if let Ok(mut answers) = self.answers.lock() {
            answers.clear();
        }
        if let Ok(mut current) = self.settings.write() {
            *current = Arc::new(settings);
        }
        Ok(())
    }

    /// Writes the fake-ip table to its store when it changed.
    pub fn save_fake_ip(&self) {
        let path = self.settings().fake_ip_store.clone();
        let Some(path) = path else { return };
        if let Ok(mut fake) = self.fake.write() {
            if let Some(pool) = fake.as_mut().filter(|p| p.is_dirty()) {
                let _ = pool.save(Path::new(&path));
            }
        }
    }

    /// The name a fake address stands for (see `fkb_fakeip_reverse`).
    /// `try_read`: a reverse lookup never waits for an allocation; a
    /// contended pool reports "not fake" and the host keeps the address.
    pub fn reverse(&self, ip: IpAddr) -> Result<Option<String>, ()> {
        let Ok(fake) = self.fake.try_read() else { return Ok(None) };
        match fake.as_ref() {
            None => Ok(None),
            Some(pool) => pool.reverse(ip).map(|n| n.map(str::to_string)),
        }
    }

    /// Fake addresses of a name (allocating its slot), `None` with fake-ip off.
    fn fake_addrs(&self, name: &str) -> Option<Vec<IpAddr>> {
        let mut fake = self.fake.write().ok()?;
        let pool = fake.as_mut()?;
        let slot = pool.allocate(name);
        let mut addrs = vec![IpAddr::V4(pool.v4(slot))];
        if let Some(v6) = pool.v6(slot) {
            addrs.push(IpAddr::V6(v6));
        }
        Some(addrs)
    }

    fn fake_ip_on(&self) -> bool {
        self.fake.read().map(|f| f.is_some()).unwrap_or(false)
    }

    fn cached(&self, name: &str, qtype: u16) -> Option<(u8, Vec<IpAddr>, u32)> {
        let answers = self.answers.lock().ok()?;
        let entry = answers.get(&(Box::from(name), qtype))?;
        let age = entry.stored.elapsed().as_secs() as u32;
        (age < entry.ttl).then(|| (entry.rcode, entry.addrs.clone(), entry.ttl - age))
    }

    fn store(&self, name: &str, qtype: u16, rcode: u8, addrs: &[IpAddr], ttl: u32) {
        let Ok(mut answers) = self.answers.lock() else { return };
        if answers.len() >= CACHE_CAP {
            let now = Instant::now();
            answers.retain(|_, e| now.duration_since(e.stored).as_secs() < e.ttl as u64);
            if answers.len() >= CACHE_CAP {
                answers.clear();
            }
        }
        answers.insert((Box::from(name), qtype), Cached { rcode, addrs: addrs.to_vec(), stored: Instant::now(), ttl });
    }

    /// Entries in the answer cache.
    pub fn cache_len(&self) -> usize {
        self.answers.lock().map(|a| a.len()).unwrap_or(0)
    }

    pub fn purge(&self) {
        if let Ok(mut answers) = self.answers.lock() {
            let now = Instant::now();
            answers.retain(|_, e| now.duration_since(e.stored).as_secs() < e.ttl as u64);
        }
    }

    fn query_id(&self) -> u16 {
        // A Weyl step over 16 bits: every ID once per 65536 queries, not sequential.
        self.next_id.fetch_add(0x9E37, Ordering::Relaxed).wrapping_mul(0x6A09) ^ 0x5BD1
    }
}

fn deadline_of(timeout_ms: u32, settings: &DnsSettings) -> Instant {
    let ms = if timeout_ms == 0 { settings.timeout_ms } else { timeout_ms };
    Instant::now() + Duration::from_millis(ms as u64)
}

fn family_of(qtype: u16) -> fn(&IpAddr) -> bool {
    if qtype == TYPE_AAAA {
        IpAddr::is_ipv6
    } else {
        IpAddr::is_ipv4
    }
}

impl Brain {
    /// The route of a name's queries: a policy entry, the nodes' server
    /// names (always direct: a node cannot carry the lookup of its own
    /// address), else the rules (decided on the name alone, no cache).
    pub fn dns_route(&self, name: &str) -> Route {
        let settings = self.dns_state().settings();
        let guard = self.snapshot_guard();
        let snapshot = guard.as_ref();
        let ruled = || -> Option<String> {
            let Some(snapshot) = snapshot else { return Some(TAG_DIRECT.to_string()) };
            if snapshot.node_servers.contains(name) {
                return Some(TAG_DIRECT.to_string());
            }
            let conn = Conn { domain: Some(name), ..Conn::default() };
            match snapshot.decide(&conn, &NoDns).0 {
                Action::Outbound(i) => {
                    let tag = snapshot.outbounds.get(i as usize).cloned().unwrap_or_else(|| TAG_DIRECT.to_string());
                    (tag != TAG_BLOCK).then_some(tag)
                }
                Action::Reject | Action::RejectDrop => None,
                Action::HijackDns => Some(TAG_DIRECT.to_string()),
            }
        };
        if let Some(policy) = settings.policy_for(name) {
            let outbound = match &policy.outbound {
                Some(tag) => tag.clone(),
                None => match ruled() {
                    Some(tag) => tag,
                    None => return Route::Reject,
                },
            };
            return Route::Via { outbound, upstreams: policy.upstreams.clone() };
        }
        match ruled() {
            None => Route::Reject,
            Some(tag) if tag == TAG_DIRECT => Route::Via { outbound: tag, upstreams: settings.direct_upstreams.clone() },
            Some(tag) => Route::Via { outbound: tag, upstreams: settings.proxy_upstreams.clone() },
        }
    }

    /// Sends `message` (built by `make(id)` per attempt) to the route's
    /// upstreams in order until one answers usefully (`accept`).
    fn ask<T>(
        &self,
        outbound: &str,
        upstreams: &[String],
        deadline: Instant,
        make: impl Fn(u16) -> Option<Vec<u8>>,
        accept: impl Fn(&[u8], u16) -> Result<T, String>,
    ) -> Result<T, DnsError> {
        let host = self.host().ok_or(DnsError::NotReady)?;
        let counters = &self.dns_state().counters;
        let mut last = DnsError::Timeout;
        for (i, upstream) in upstreams.iter().enumerate() {
            let left = deadline.saturating_duration_since(Instant::now()).as_millis() as u64;
            if left == 0 {
                return Err(DnsError::Timeout);
            }
            let share = (left / (upstreams.len() - i) as u64).max(MIN_ATTEMPT_MS).min(left);
            let id = self.dns_state().query_id();
            let message = make(id).ok_or(DnsError::Invalid)?;
            bump(&counters.upstream_queries);
            match host.exchange(outbound, upstream, &message, share as u32) {
                Ok(reply) => match accept(&reply, id) {
                    Ok(value) => return Ok(value),
                    Err(why) => last = DnsError::Upstream(FKB_E_HOST, format!("{upstream} via {outbound}: {why}")),
                },
                Err(e) => {
                    last = if e.code == FKB_E_TIMEOUT {
                        DnsError::Timeout
                    } else {
                        DnsError::Upstream(e.code, format!("{upstream} via {outbound}: {}", e.message))
                    }
                }
            }
            bump(&counters.upstream_failures);
        }
        Err(last)
    }

    /// Real addresses of `name` (normalized) for one query type (A or
    /// AAAA): hosts, cache, then the route's upstreams. Returns the rcode,
    /// the addresses and the TTL left.
    pub fn resolve_real(&self, name: &str, qtype: u16, deadline: Instant) -> Result<(u8, Vec<IpAddr>, u32), DnsError> {
        let state = self.dns_state();
        let settings = state.settings();
        let keep = family_of(qtype);
        if let Some(addrs) = settings.hosts_for(name) {
            bump(&state.counters.hosts_answers);
            return Ok((RCODE_NOERROR, addrs.iter().copied().filter(keep).collect(), HOSTS_TTL));
        }
        if let Some(hit) = state.cached(name, qtype) {
            bump(&state.counters.cache_hits);
            return Ok(hit);
        }
        let (outbound, upstreams) = match self.dns_route(name) {
            Route::Reject => return Ok((RCODE_NXDOMAIN, Vec::new(), NEGATIVE_TTL)),
            Route::Via { outbound, upstreams } => (outbound, upstreams),
        };
        let answer = self.ask(
            &outbound,
            &upstreams,
            deadline,
            |id| build_query(id, name, qtype),
            |reply, id| {
                let (_, answer) = parse_reply(reply, id)?;
                match answer.rcode {
                    RCODE_NOERROR | RCODE_NXDOMAIN => Ok(answer),
                    rcode => Err(format!("rcode {rcode}")),
                }
            },
        )?;
        let addrs: Vec<IpAddr> = answer.addrs.into_iter().filter(keep).collect();
        let ttl = match answer.ttl {
            Some(ttl) if !addrs.is_empty() => ttl.clamp(1, MAX_TTL),
            _ => NEGATIVE_TTL,
        };
        state.store(name, qtype, answer.rcode, &addrs, ttl);
        if answer.rcode == RCODE_NOERROR {
            self.dns().insert_family(name, qtype == TYPE_AAAA, &addrs, Duration::from_secs(ttl as u64));
        }
        Ok((answer.rcode, addrs, ttl))
    }

    /// One DNS message (`fkb_dns_message`): the reply bytes.
    pub fn dns_message(&self, query: &[u8], flags: u32, timeout_ms: u32) -> Result<Vec<u8>, i32> {
        let state = self.dns_state();
        bump(&state.counters.messages);
        let Some(q) = parse_query(query) else {
            return error_reply(query, RCODE_FORMERR).ok_or(FKB_E_INVALID);
        };
        let settings = state.settings();
        let deadline = deadline_of(timeout_ms, &settings);
        let client = flags & FKB_DNS_CLIENT != 0;
        let Some(name) = normalize(&q.name).map(|n| n.into_owned()) else {
            return Ok(build_reply(query, &q, RCODE_REFUSED, &[], 0));
        };
        let fake = client
            && q.qclass == CLASS_IN
            && state.fake_ip_on()
            && !settings.fake_ip_filtered(&name)
            && settings.hosts_for(&name).is_none();
        match q.qtype {
            TYPE_A | TYPE_AAAA if q.qclass == CLASS_IN => {
                if fake {
                    if let Some(addrs) = state.fake_addrs(&name) {
                        bump(&state.counters.fake_answers);
                        return Ok(build_reply(query, &q, RCODE_NOERROR, &addrs, FAKE_TTL));
                    }
                }
                match self.resolve_real(&name, q.qtype, deadline) {
                    Ok((rcode, addrs, ttl)) => Ok(build_reply(query, &q, rcode, &addrs, ttl)),
                    Err(DnsError::Invalid) => Ok(build_reply(query, &q, RCODE_FORMERR, &[], 0)),
                    Err(_) => Ok(build_reply(query, &q, RCODE_SERVFAIL, &[], 0)),
                }
            }
            TYPE_HTTPS | TYPE_SVCB if fake => Ok(build_reply(query, &q, RCODE_NOERROR, &[], 0)),
            _ => {
                let (outbound, upstreams) = match self.dns_route(&name) {
                    Route::Reject => return Ok(build_reply(query, &q, RCODE_NXDOMAIN, &[], 0)),
                    Route::Via { outbound, upstreams } => (outbound, upstreams),
                };
                bump(&state.counters.forwarded);
                let original = q.id;
                let forwarded = self.ask(
                    &outbound,
                    &upstreams,
                    deadline,
                    |id| {
                        let mut message = query.to_vec();
                        message[..2].copy_from_slice(&id.to_be_bytes());
                        Some(message)
                    },
                    |reply, id| {
                        parse_reply(reply, id)?;
                        let mut reply = reply.to_vec();
                        reply[..2].copy_from_slice(&original.to_be_bytes());
                        Ok(reply)
                    },
                );
                Ok(forwarded.unwrap_or_else(|_| build_reply(query, &q, RCODE_SERVFAIL, &[], 0)))
            }
        }
    }

    /// Resolves the names misses queued (A, then AAAA) so the next decide
    /// on them hits the cache. Returns how many names were looked up.
    pub fn run_prefetch(&self, names: Vec<Box<str>>) -> usize {
        let state = self.dns_state();
        let mut done = 0;
        for name in names {
            let deadline = deadline_of(0, &state.settings());
            if self.resolve_real(&name, TYPE_A, deadline).is_ok() {
                let _ = self.resolve_real(&name, TYPE_AAAA, deadline_of(0, &state.settings()));
            }
            bump(&state.counters.prefetched);
            done += 1;
        }
        done
    }
}

#[cfg(test)]
mod tests;
