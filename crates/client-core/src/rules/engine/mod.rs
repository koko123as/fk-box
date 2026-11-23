//! The routing rule engine ("Rust brain" router): one internal rule
//! representation ([`ir`]) that Clash/mihomo ([`import_clash`]) and sing-box
//! ([`import_singbox`]) configurations import into, compiled ([`compile`])
//! into indexes (domain hash per label boundary, Aho-Corasick keywords, one
//! regex set, CIDR prefix tries) and evaluated per connection by
//! [`Router::decide`]: synchronous, no I/O, safe to call from many threads
//! at once (a compiled [`Router`] is immutable; reload builds a new one).
//!
//! Geo data comes in the formats the existing geo policy installs
//! ([`geo`]: `geosite.dat`, `geoip.dat`; [`mmdb`]: `geoip.metadb`); rule
//! sets in mihomo's yaml/text/mrs and sing-box's source/binary forms
//! ([`ruleset`]). Rules that need a DNS answer read the brain's cache
//! ([`DnsView`]) and never wait for one; see
//! docs/architecture/brain-dns-rules.md.
//!
//! Independent of the per-core routing model in [`super::routing`] and of
//! the running service: nothing here is wired into the Android app yet
//! (stage 1 of docs/architecture/brain-ffi.md).

pub mod compile;
pub mod conn;
pub mod domain;
pub mod geo;
pub mod import_clash;
pub mod import_singbox;
pub mod ip;
pub mod ir;
pub mod mmdb;
pub mod ruleset;

#[cfg(test)]
mod tests;

pub use compile::{compile, Action, Env, Router, RouterStats, Verdict};
pub use conn::{Conn, DnsView, NoDns, MAX_CACHED_ADDRS};
pub use ir::{Behavior, Cond, ImportError, IpSide, MissPolicy, Network, Ranges, RouterIr, Rule, RuleSet, Target};
