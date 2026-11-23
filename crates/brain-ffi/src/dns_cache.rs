//! The brain's DNS cache as decide() reads it: sharded, read-mostly maps (a
//! read lock per lookup, held for one hash probe) and a bounded prefetch
//! queue that a miss feeds without ever blocking (`try_lock`). Stage 4 fills
//! the cache from the brain's own resolver and drains the queue; stage 1
//! only defines the contract and fills it from tests.

use std::collections::{HashMap, VecDeque};
use std::hash::{BuildHasherDefault, Hash, Hasher};
use std::net::IpAddr;
use std::sync::{Mutex, RwLock};
use std::time::{Duration, Instant};

use fkbox_client_core::rules::engine::domain::{normalize, FxHasher};
use fkbox_client_core::rules::engine::{DnsView, MAX_CACHED_ADDRS};

struct Entry {
    addrs: Vec<IpAddr>,
    expires: Instant,
}

type Shard = RwLock<HashMap<Box<str>, Entry, BuildHasherDefault<FxHasher>>>;

pub struct DnsCache {
    shards: Box<[Shard]>,
    prefetch: Mutex<VecDeque<Box<str>>>,
    prefetch_cap: usize,
}

impl Default for DnsCache {
    fn default() -> DnsCache {
        DnsCache::new(16, 256)
    }
}

impl DnsCache {
    pub fn new(shards: usize, prefetch_cap: usize) -> DnsCache {
        let shards = (0..shards.max(1)).map(|_| RwLock::new(HashMap::default())).collect();
        DnsCache { shards, prefetch: Mutex::new(VecDeque::new()), prefetch_cap }
    }

    fn shard(&self, domain: &str) -> &Shard {
        let mut h = FxHasher::default();
        domain.hash(&mut h);
        &self.shards[(h.finish() as usize) % self.shards.len()]
    }

    /// Caches an answer (an empty list is a cached "no address").
    pub fn insert(&self, domain: &str, addrs: &[IpAddr], ttl: Duration) {
        let Some(domain) = normalize(domain) else { return };
        let entry = Entry { addrs: addrs.iter().take(MAX_CACHED_ADDRS).copied().collect(), expires: Instant::now() + ttl };
        if let Ok(mut shard) = self.shard(&domain).write() {
            shard.insert(domain.into_owned().into_boxed_str(), entry);
        }
    }

    /// Drops expired entries.
    pub fn purge(&self) {
        let now = Instant::now();
        for shard in self.shards.iter() {
            if let Ok(mut s) = shard.write() {
                s.retain(|_, e| e.expires > now);
            }
        }
    }

    pub fn len(&self) -> usize {
        self.shards.iter().map(|s| s.read().map(|s| s.len()).unwrap_or(0)).sum()
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    /// The domains misses asked to warm, oldest first (the resolver's work list).
    pub fn take_prefetch(&self) -> Vec<Box<str>> {
        self.prefetch.lock().map(|mut q| q.drain(..).collect()).unwrap_or_default()
    }
}

impl DnsView for DnsCache {
    fn lookup(&self, domain: &str, out: &mut [IpAddr; MAX_CACHED_ADDRS]) -> Option<usize> {
        let shard = self.shard(domain).read().ok()?;
        let entry = shard.get(domain)?;
        if entry.expires <= Instant::now() {
            return None;
        }
        let n = entry.addrs.len().min(MAX_CACHED_ADDRS);
        out[..n].copy_from_slice(&entry.addrs[..n]);
        Some(n)
    }

    fn prefetch(&self, domain: &str) {
        // Never wait: a contended queue drops the hint (the next miss re-sends it).
        if let Ok(mut q) = self.prefetch.try_lock() {
            if q.len() < self.prefetch_cap && !q.iter().any(|d| &**d == domain) {
                q.push_back(domain.into());
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hits_misses_expiry_and_prefetch() {
        let cache = DnsCache::new(4, 2);
        let mut out = [IpAddr::from([0, 0, 0, 0]); MAX_CACHED_ADDRS];
        assert_eq!(cache.lookup("a.test", &mut out), None);
        cache.insert("A.Test.", &["1.2.3.4".parse().unwrap(), "::1".parse().unwrap()], Duration::from_secs(60));
        assert_eq!(cache.lookup("a.test", &mut out), Some(2));
        assert_eq!(out[0], "1.2.3.4".parse::<IpAddr>().unwrap());
        cache.insert("empty.test", &[], Duration::from_secs(60));
        assert_eq!(cache.lookup("empty.test", &mut out), Some(0));
        cache.insert("old.test", &["1.1.1.1".parse().unwrap()], Duration::from_secs(0));
        assert_eq!(cache.lookup("old.test", &mut out), None);
        assert_eq!(cache.len(), 3);
        cache.purge();
        assert_eq!(cache.len(), 2);
        cache.prefetch("x.test");
        cache.prefetch("x.test");
        cache.prefetch("y.test");
        cache.prefetch("z.test");
        assert_eq!(cache.take_prefetch(), vec![Box::<str>::from("x.test"), Box::from("y.test")]);
        assert!(cache.take_prefetch().is_empty());
        let many: Vec<IpAddr> = (0..20u8).map(|i| IpAddr::from([10, 0, 0, i])).collect();
        cache.insert("many.test", &many, Duration::from_secs(60));
        assert_eq!(cache.lookup("many.test", &mut out), Some(MAX_CACHED_ADDRS));
    }
}
