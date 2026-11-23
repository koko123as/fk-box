//! The brain's state: the current rule snapshot (read lock-free by every
//! decide, swapped atomically by reload), the DNS cache and the host table.

use std::sync::{Arc, Mutex, RwLock};

use arc_swap::ArcSwapOption;
use fkbox_client_core::rules::engine::{Action, Router};

use crate::abi::*;
use crate::dns_cache::DnsCache;
use crate::host::{HostApi, HostError};

/// One compiled configuration: the router and the generation the host
/// knows its outbound/inbound tables by.
pub struct Snapshot {
    pub generation: u32,
    pub router: Router,
}

pub struct Brain {
    snapshot: ArcSwapOption<Snapshot>,
    dns: Arc<DnsCache>,
    host: RwLock<Option<HostApi>>,
    /// Serializes start/reload/stop and holds the last generation handed out.
    lifecycle: Mutex<u32>,
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
        Brain { snapshot: ArcSwapOption::empty(), dns: Arc::new(DnsCache::default()), host: RwLock::new(None), lifecycle: Mutex::new(0) }
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
        self.snapshot.store(Some(Arc::new(Snapshot { generation, router })));
    }

    fn next_generation(&self, apply: impl FnOnce(&HostApi, u32) -> Result<(), HostError>, router: Router) -> Result<u32, HostError> {
        let mut last = self.lifecycle.lock().map_err(|_| HostError { code: FKB_E_PANIC, message: "lifecycle lock poisoned".into() })?;
        let host = self.host().ok_or_else(host_missing)?;
        // Every attempt takes a fresh number, so a failed one never shares
        // its generation with the configuration that follows.
        let generation = last.wrapping_add(1).max(1);
        *last = generation;
        // The host learns the new tables first; decisions switch only after it accepted them.
        apply(&host, generation)?;
        self.install(generation, router);
        Ok(generation)
    }

    /// Starts the host with `config_json` (whose outbound/inbound order the
    /// router was compiled against) and installs the router.
    pub fn start(&self, config_json: &str, router: Router) -> Result<u32, HostError> {
        self.next_generation(|h, g| h.start(config_json, g), router)
    }

    /// Replaces the configuration; on failure the previous snapshot stays.
    pub fn reload(&self, config_json: &str, router: Router) -> Result<u32, HostError> {
        self.next_generation(|h, g| h.reload(config_json, g), router)
    }

    /// Stops the host; decide then answers `FKB_E_NOT_READY`.
    pub fn stop(&self) -> Result<(), HostError> {
        let _last = self.lifecycle.lock().map_err(|_| HostError { code: FKB_E_PANIC, message: "lifecycle lock poisoned".into() })?;
        let host = self.host().ok_or_else(host_missing)?;
        self.snapshot.store(None);
        host.stop()
    }

    /// One decision for a metadata record.
    pub fn decide_meta(&self, meta: &FkbConnMeta, out: &mut FkbDecision) -> i32 {
        let conn = match conn_of(meta) {
            Ok(c) => c,
            Err(code) => return code,
        };
        let guard = self.snapshot.load();
        let Some(snapshot) = guard.as_ref() else { return FKB_E_NOT_READY };
        let verdict = snapshot.router.decide(&conn, &*self.dns);
        let (action, outbound) = match verdict.action {
            Action::Outbound(i) => (FKB_ACTION_ROUTE, i),
            Action::Reject => (FKB_ACTION_REJECT, 0),
            Action::RejectDrop => (FKB_ACTION_REJECT_DROP, 0),
            Action::HijackDns => (FKB_ACTION_HIJACK_DNS, 0),
        };
        *out = FkbDecision {
            action,
            flags: if verdict.dns_miss { FKB_DECISION_DNS_MISS } else { 0 },
            outbound,
            rule_index: verdict.rule.unwrap_or(FKB_RULE_FINAL),
            generation: snapshot.generation,
        };
        FKB_OK
    }
}
