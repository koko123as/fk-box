//! The brain's state: the current rule snapshot (read lock-free by every
//! decide, swapped atomically by reload and by group changes), the DNS cache
//! decide reads, the resolver's state (settings, answers, fake-ip), the
//! proxy groups' control state and the host table.

use std::collections::HashSet;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, RwLock};
use std::time::Instant;

use arc_swap::ArcSwapOption;
use fkbox_client_core::rules::engine::{Action, Conn, DnsView, Router};

use crate::abi::*;
use crate::dns::DnsState;
use crate::dns_cache::DnsCache;
use crate::groups::{probe_all, ControlError, Force, GroupSet, GroupTable, Groups, Prober, PROBE_CONCURRENCY};
use crate::host::{HostApi, HostError};

/// What the resolver needs from a configuration besides the router.
#[derive(Debug, Clone, Default)]
pub struct Routing {
    /// fkbrain's outbound table (the router's `Outbound(i)` indexes it).
    pub outbounds: Vec<String>,
    /// The nodes' server names: always resolved through the direct upstreams.
    pub node_servers: HashSet<String>,
    /// The proxy groups; the router's indices from `outbounds.len()` on name them.
    pub groups: Arc<GroupSet>,
}

/// One compiled configuration: the router, the generation the host knows
/// its outbound/inbound tables by, and the groups' current choices. A
/// group change publishes a new snapshot sharing everything but `groups`.
pub struct Snapshot {
    pub generation: u32,
    pub router: Arc<Router>,
    pub outbounds: Arc<Vec<String>>,
    pub node_servers: Arc<HashSet<String>>,
    pub groups: Arc<GroupTable>,
}

impl Snapshot {
    /// The router's action for a connection, followed through the groups.
    pub fn decide(&self, conn: &Conn, dns: &dyn DnsView) -> (Action, Option<u32>, bool) {
        let verdict = self.router.decide(conn, dns);
        (self.groups.resolve(verdict.action, conn), verdict.rule, verdict.dns_miss)
    }
}

pub type SnapshotGuard = arc_swap::Guard<Option<Arc<Snapshot>>>;

/// Decision counters: how often a rule needed the DNS cache and missed.
#[derive(Default)]
pub struct DecideCounters {
    pub decided: AtomicU64,
    pub dns_miss: AtomicU64,
    /// Decisions whose destination was a fake-ip address, decided on its name.
    pub fake_ip: AtomicU64,
}

pub struct Brain {
    snapshot: ArcSwapOption<Snapshot>,
    dns: Arc<DnsCache>,
    dns_state: DnsState,
    groups: Groups,
    host: RwLock<Option<HostApi>>,
    /// Serializes start/reload/stop and holds the last generation handed out.
    lifecycle: Mutex<u32>,
    pub counters: DecideCounters,
}

impl Default for Brain {
    fn default() -> Brain {
        Brain::new()
    }
}

fn host_missing() -> HostError {
    HostError { code: FKB_E_NOT_READY, message: "no host attached".into() }
}

impl Brain {
    pub fn new() -> Brain {
        Brain {
            snapshot: ArcSwapOption::empty(),
            dns: Arc::new(DnsCache::default()),
            dns_state: DnsState::default(),
            groups: Groups::default(),
            host: RwLock::new(None),
            lifecycle: Mutex::new(0),
            counters: DecideCounters::default(),
        }
    }

    pub fn dns_state(&self) -> &DnsState {
        &self.dns_state
    }

    pub fn groups(&self) -> &Groups {
        &self.groups
    }

    pub fn snapshot_guard(&self) -> SnapshotGuard {
        self.snapshot.load()
    }

    pub fn set_host(&self, host: HostApi) {
        if let Ok(mut h) = self.host.write() {
            *h = Some(host);
        }
    }

    pub fn host(&self) -> Option<HostApi> {
        self.host.read().ok().and_then(|h| *h)
    }

    pub fn dns(&self) -> &DnsCache {
        &self.dns
    }

    pub fn generation(&self) -> Option<u32> {
        self.snapshot.load().as_ref().map(|s| s.generation)
    }

    /// Installs a router directly (no host call): tests and benchmarks.
    pub fn install(&self, generation: u32, router: Router) {
        self.install_with(generation, router, Routing::default());
    }

    pub fn install_with(&self, generation: u32, router: Router, routing: Routing) {
        let Routing { outbounds, node_servers, groups } = routing;
        let groups = self.groups.install(groups);
        let snapshot =
            Snapshot { generation, router: Arc::new(router), outbounds: Arc::new(outbounds), node_servers: Arc::new(node_servers), groups };
        self.snapshot.store(Some(Arc::new(snapshot)));
    }

    fn lock_lifecycle(&self) -> Result<std::sync::MutexGuard<'_, u32>, HostError> {
        self.lifecycle.lock().map_err(|_| HostError { code: FKB_E_PANIC, message: "lifecycle lock poisoned".into() })
    }

    /// Publishes the groups' current choices: a new snapshot with the same
    /// router and generation, swapped in one store (decide sees the old
    /// choices or the new ones). Under the lifecycle lock, so a reload
    /// never gets the previous configuration's table.
    pub fn publish_groups(&self) {
        let Ok(_lifecycle) = self.lock_lifecycle() else { return };
        let Some(current) = self.snapshot.load_full() else { return };
        let snapshot = Snapshot {
            generation: current.generation,
            router: current.router.clone(),
            outbounds: current.outbounds.clone(),
            node_servers: current.node_servers.clone(),
            groups: self.groups.table(),
        };
        self.snapshot.store(Some(Arc::new(snapshot)));
    }

    /// A manual selection in a select group; takes effect for the next
    /// connection and the next DNS route, without touching the host.
    pub fn select_group(&self, group: &str, member: &str) -> Result<(), ControlError> {
        match self.groups.select(group, member) {
            Ok(changed) => {
                if changed {
                    self.publish_groups();
                }
                Ok(())
            }
            // The selection is made; only its store failed.
            Err((FKB_E_HOST, message)) => {
                self.publish_groups();
                Err((FKB_E_HOST, message))
            }
            Err(e) => Err(e),
        }
    }

    /// One test round through `prober`: the due groups, one group or all.
    /// Returns the number of probes run.
    pub fn test_groups_with(&self, prober: &dyn Prober, force: Force, now: Instant) -> usize {
        let (groups, jobs) = self.groups.due(now, force);
        if groups.is_empty() {
            return 0;
        }
        let results = probe_all(prober, &jobs, PROBE_CONCURRENCY);
        // Published even when no url-test/fallback choice changed: the
        // alive flags of load-balance members may have.
        self.groups.record(&groups, &jobs, results, now);
        self.publish_groups();
        jobs.len()
    }

    /// As [`Brain::test_groups_with`], through the host's probe.
    pub fn test_groups(&self, force: Force) -> Result<usize, HostError> {
        let host = self.host().ok_or_else(host_missing)?;
        if self.snapshot.load().is_none() {
            return Err(HostError { code: FKB_E_NOT_READY, message: "not running".into() });
        }
        Ok(self.test_groups_with(&host, force, Instant::now()))
    }

    fn next_generation(
        &self,
        apply: impl FnOnce(&HostApi, u32) -> Result<(), HostError>,
        router: Router,
        routing: Routing,
    ) -> Result<u32, HostError> {
        let mut last = self.lock_lifecycle()?;
        let host = self.host().ok_or_else(host_missing)?;
        // Every attempt takes a fresh number, so a failed one never shares
        // its generation with the configuration that follows.
        let generation = last.wrapping_add(1).max(1);
        *last = generation;
        // The host learns the new tables first; decisions switch only after it accepted them.
        apply(&host, generation)?;
        self.install_with(generation, router, routing);
        Ok(generation)
    }

    /// Starts the host with `config_json` (whose outbound/inbound order the
    /// router was compiled against) and installs the router.
    pub fn start(&self, config_json: &str, router: Router) -> Result<u32, HostError> {
        self.start_with(config_json, router, Routing::default())
    }

    pub fn start_with(&self, config_json: &str, router: Router, routing: Routing) -> Result<u32, HostError> {
        self.next_generation(|h, g| h.start(config_json, g), router, routing)
    }

    /// Replaces the configuration; on failure the previous snapshot stays.
    pub fn reload(&self, config_json: &str, router: Router) -> Result<u32, HostError> {
        self.reload_with(config_json, router, Routing::default())
    }

    pub fn reload_with(&self, config_json: &str, router: Router, routing: Routing) -> Result<u32, HostError> {
        self.next_generation(|h, g| h.reload(config_json, g), router, routing)
    }

    /// Stops the host; decide then answers `FKB_E_NOT_READY`.
    pub fn stop(&self) -> Result<(), HostError> {
        let _last = self.lock_lifecycle()?;
        let host = self.host().ok_or_else(host_missing)?;
        self.snapshot.store(None);
        self.dns_state.save_fake_ip();
        host.stop()
    }

    /// One decision for a metadata record.
    pub fn decide_meta(&self, meta: &FkbConnMeta, out: &mut FkbDecision) -> i32 {
        let mut conn = match conn_of(meta) {
            Ok(c) => c,
            Err(code) => return code,
        };
        let guard = self.snapshot.load();
        let Some(snapshot) = guard.as_ref() else { return FKB_E_NOT_READY };
        // A fake-ip destination stands for a name: decide on the name (the
        // host normally rewrote the destination already, then dst is unset).
        let reversed: Option<String> = match conn.dst {
            Some(ip) => self.dns_state.reverse(ip).ok().flatten(),
            None => None,
        };
        if let Some(name) = reversed.as_deref() {
            conn.dst = None;
            conn.domain = Some(name);
            self.counters.fake_ip.fetch_add(1, Ordering::Relaxed);
        }
        let (verdict_action, rule, dns_miss) = snapshot.decide(&conn, &*self.dns);
        self.counters.decided.fetch_add(1, Ordering::Relaxed);
        if dns_miss {
            self.counters.dns_miss.fetch_add(1, Ordering::Relaxed);
        }
        let (action, outbound) = match verdict_action {
            Action::Outbound(i) => (FKB_ACTION_ROUTE, i),
            Action::Reject => (FKB_ACTION_REJECT, 0),
            Action::RejectDrop => (FKB_ACTION_REJECT_DROP, 0),
            Action::HijackDns => (FKB_ACTION_HIJACK_DNS, 0),
        };
        *out = FkbDecision {
            action,
            flags: if dns_miss { FKB_DECISION_DNS_MISS } else { 0 },
            outbound,
            rule_index: rule.unwrap_or(FKB_RULE_FINAL),
            generation: snapshot.generation,
        };
        FKB_OK
    }
}
