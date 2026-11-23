//! What a decision reads: the connection, and the brain's DNS cache.

use std::net::IpAddr;

use super::ir::Network;

/// One new connection as the host reports it.
#[derive(Debug, Clone, Copy, Default)]
pub struct Conn<'a> {
    pub network: Option<Network>,
    pub src: Option<IpAddr>,
    pub src_port: u16,
    /// The destination address; `None` for a domain-only destination.
    pub dst: Option<IpAddr>,
    pub dst_port: u16,
    /// The destination domain (FQDN destination, or sniffed host/SNI);
    /// normalized by the decision.
    pub domain: Option<&'a str>,
    /// The sniffed protocol, lowercase (`tls`, `http`, `quic`, …).
    pub protocol: Option<&'a str>,
    pub uid: Option<u32>,
    pub package: Option<&'a str>,
    pub process: Option<&'a str>,
    /// Index into the host's inbound table.
    pub inbound: Option<u32>,
}

/// Addresses one cache lookup returns at most.
pub const MAX_CACHED_ADDRS: usize = 8;

/// The brain's DNS cache as decisions see it: a synchronous lookup that
/// never blocks on the network.
pub trait DnsView {
    /// `Some(n)` on a hit (`n` addresses written; 0 for a cached empty
    /// answer), `None` on a miss.
    fn lookup(&self, domain: &str, out: &mut [IpAddr; MAX_CACHED_ADDRS]) -> Option<usize>;

    /// Called once per decision on a miss that a rule needed: the hook that
    /// warms the cache asynchronously (stage 4); it must not block.
    fn prefetch(&self, _domain: &str) {}
}

/// No cache: every lookup misses.
pub struct NoDns;

impl DnsView for NoDns {
    fn lookup(&self, _domain: &str, _out: &mut [IpAddr; MAX_CACHED_ADDRS]) -> Option<usize> {
        None
    }
}
