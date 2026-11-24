//! The brain's DNS against a fake host whose `exchange` plays every
//! upstream: hosts, fake-ip (allocation, reverse lookup in decide,
//! persistence), the answer cache and its TTL, the route of a query (rules,
//! policy, node servers, reject), upstream failover, forwarding, the
//! prefetch of names that decide missed, and the C entry points.

use std::ffi::c_void;
use std::net::IpAddr;
use std::sync::Mutex;
use std::time::{Duration, Instant};

use fkbox_client_core::rules::engine::import_clash::{self, ClashOptions, ProviderRef};
use fkbox_client_core::rules::engine::{compile, Env, Router};
use serde_json::json;

use super::settings::DnsSettings;
use super::wire::*;
use super::*;
use crate::brain::Routing;
use crate::host::HostApi;

struct Upstreams {
    log: Mutex<Vec<String>>,
    failing: Vec<&'static str>,
}

impl Upstreams {
    fn calls(&self) -> Vec<String> {
        self.log.lock().unwrap().clone()
    }
}

fn text(s: &FkbStr) -> String {
    // SAFETY: the brain passes strings that live for the call.
    String::from_utf8_lossy(unsafe { s.as_bytes() }).into_owned()
}

const REAL_V4: [u8; 4] = [93, 184, 216, 34];

unsafe extern "C" fn exchange(
    ctx: *mut c_void,
    tag: FkbStr,
    upstream: FkbStr,
    message: FkbStr,
    _timeout_ms: u32,
    response: *mut FkbBuf,
    err: *mut FkbBuf,
) -> i32 {
    // SAFETY: ctx is a leaked Upstreams.
    let up = unsafe { &*(ctx as *const Upstreams) };
    let (tag, upstream) = (text(&tag), text(&upstream));
    // SAFETY: as in text().
    let msg = unsafe { message.as_bytes() };
    let Some(q) = parse_query(msg) else { return FKB_E_INVALID };
    up.log.lock().unwrap().push(format!("{tag} {upstream} {} {}", q.name, q.qtype));
    if up.failing.iter().any(|f| upstream.contains(f)) {
        // SAFETY: the brain passes a writable error buffer.
        unsafe { crate::write_buf(&mut *err, b"connection refused") };
        return FKB_E_HOST;
    }
    let reply = if q.name == "nx.test" {
        build_reply(msg, &q, RCODE_NXDOMAIN, &[], 0)
    } else {
        let ttl = if q.name.starts_with("short") { 1 } else { 300 };
        build_reply(msg, &q, RCODE_NOERROR, &[IpAddr::from(REAL_V4), "2001:db8::1".parse().unwrap()], ttl)
    };
    // SAFETY: the brain passes a writable response buffer.
    unsafe { crate::write_buf(&mut *response, &reply) }
}

const RULES: &str = "rules:\n  - DOMAIN-SUFFIX,proxy.test,proxy\n  - DOMAIN-SUFFIX,ad.test,REJECT\n  - IP-CIDR,93.184.216.0/24,proxy\n  - MATCH,fk-direct\n";

fn router(yaml: &str, outbounds: &[String]) -> Router {
    let ir = import_clash::import(yaml, &|_: &ProviderRef| -> Result<Vec<u8>, String> { Err("no files".into()) }, &ClashOptions::default())
        .unwrap();
    let inbounds = vec!["fk-tun".to_string()];
    compile(&ir, &Env { outbounds, inbounds: &inbounds, geosite: None, geoip: None, mmdb: None, default_final: None }).unwrap()
}

fn brain_with(settings: serde_json::Value, failing: Vec<&'static str>) -> (Brain, &'static Upstreams) {
    let up: &'static Upstreams = Box::leak(Box::new(Upstreams { log: Mutex::new(Vec::new()), failing }));
    let table = FkbHostApi {
        abi_version: FKB_ABI_VERSION,
        size: std::mem::size_of::<FkbHostApi>() as u32,
        ctx: up as *const Upstreams as *mut c_void,
        start: None,
        stop: None,
        reload: None,
        probe: None,
        exchange: Some(exchange),
        stats: None,
    };
    let brain = Brain::new();
    brain.set_host(HostApi::from_raw(table).unwrap());
    brain.dns_state().apply(DnsSettings::from_value(&settings).unwrap()).unwrap();
    let outbounds: Vec<String> = ["proxy", "fk-direct", "fk-block"].iter().map(|s| s.to_string()).collect();
    let routing = Routing { outbounds: outbounds.clone(), node_servers: ["node.proxy.test".to_string()].into_iter().collect() };
    brain.install_with(1, router(RULES, &outbounds), routing);
    (brain, up)
}

fn query(name: &str, qtype: u16) -> Vec<u8> {
    build_query(0x4242, name, qtype).unwrap()
}

fn answer(reply: &[u8]) -> Answer {
    parse_reply(reply, 0x4242).unwrap().1
}

fn soon() -> Instant {
    Instant::now() + Duration::from_secs(5)
}

#[test]
fn hosts_win_over_fake_ip_and_upstreams() {
    let (brain, up) = brain_with(json!({"hosts": {"router.test": ["192.168.1.1", "fd00::1"]}}), vec![]);
    let a = answer(&brain.dns_message(&query("Router.Test.", TYPE_A), FKB_DNS_CLIENT, 1000).unwrap());
    assert_eq!(a.addrs, vec!["192.168.1.1".parse::<IpAddr>().unwrap()]);
    let aaaa = answer(&brain.dns_message(&query("router.test", TYPE_AAAA), 0, 1000).unwrap());
    assert_eq!(aaaa.addrs, vec!["fd00::1".parse::<IpAddr>().unwrap()]);
    assert!(up.calls().is_empty());
    assert_eq!(brain.dns_state().counters.snapshot().hosts_answers, 2);
}

#[test]
fn fake_ip_for_apps_real_for_sing_box() {
    let (brain, up) = brain_with(json!({}), vec![]);
    let reply = brain.dns_message(&query("www.proxy.test", TYPE_A), FKB_DNS_CLIENT, 1000).unwrap();
    let fake = answer(&reply);
    assert_eq!(fake.ttl, Some(FAKE_TTL));
    let IpAddr::V4(v4) = fake.addrs[0] else { panic!("not v4") };
    assert_eq!(v4.octets()[..2], [198, 18]);
    let fake6 = answer(&brain.dns_message(&query("www.proxy.test", TYPE_AAAA), FKB_DNS_CLIENT, 1000).unwrap());
    assert!(matches!(fake6.addrs[0], IpAddr::V6(v6) if v6.segments()[0] == 0xfc00));
    assert_eq!(brain.dns_state().reverse(fake.addrs[0]), Ok(Some("www.proxy.test".to_string())));
    assert_eq!(brain.dns_state().reverse(fake6.addrs[0]), Ok(Some("www.proxy.test".to_string())));
    assert!(up.calls().is_empty(), "a fake answer asks nobody");
    // The same name for sing-box itself (no client flag): real, through the rule's outbound.
    let real = answer(&brain.dns_message(&query("www.proxy.test", TYPE_A), 0, 1000).unwrap());
    assert_eq!(real.addrs, vec![IpAddr::from(REAL_V4)]);
    assert_eq!(up.calls(), ["proxy tcp://1.1.1.1:53 www.proxy.test 1"]);
    // Filtered names and their HTTPS records; HTTPS of a fake name is empty.
    let lan = answer(&brain.dns_message(&query("nas.lan", TYPE_A), FKB_DNS_CLIENT, 1000).unwrap());
    assert_eq!(lan.addrs, vec![IpAddr::from(REAL_V4)]);
    let https = answer(&brain.dns_message(&query("www.proxy.test", TYPE_HTTPS), FKB_DNS_CLIENT, 1000).unwrap());
    assert_eq!((https.rcode, https.addrs.len()), (RCODE_NOERROR, 0));
    assert_eq!(up.calls().len(), 2);

    // decide on a fake destination uses the name: DOMAIN-SUFFIX,proxy.test,proxy.
    let mut meta = FkbConnMeta::new();
    meta.network = FKB_NET_TCP;
    meta.dst_port = 443;
    meta.set_dst(fake.addrs[0]);
    let mut out = FkbDecision::default();
    assert_eq!(brain.decide_meta(&meta, &mut out), FKB_OK);
    assert_eq!((out.action, out.outbound, out.rule_index), (FKB_ACTION_ROUTE, 0, 0));
    assert_eq!(brain.counters.fake_ip.load(std::sync::atomic::Ordering::Relaxed), 1);
    // A stale fake address (inside the range, never handed out) is reported.
    assert_eq!(brain.dns_state().reverse("198.19.255.254".parse().unwrap()), Err(()));

    // fake-ip off: apps get real answers too.
    let (plain, _) = brain_with(json!({"fake_ip": false}), vec![]);
    let real = answer(&plain.dns_message(&query("a.example", TYPE_A), FKB_DNS_CLIENT, 1000).unwrap());
    assert_eq!(real.addrs, vec![IpAddr::from(REAL_V4)]);
    assert_eq!(plain.dns_state().reverse(fake.addrs[0]), Ok(None));
}

#[test]
fn cache_keeps_answers_for_their_ttl() {
    let (brain, up) = brain_with(json!({}), vec![]);
    let first = brain.resolve_real("a.example", TYPE_A, soon()).unwrap();
    let second = brain.resolve_real("a.example", TYPE_A, soon()).unwrap();
    assert_eq!(first.1, second.1);
    assert!(second.2 <= 300 && second.2 >= 299);
    assert_eq!(up.calls().len(), 1, "the second answer came from the cache");
    assert_eq!(brain.dns_state().counters.snapshot().cache_hits, 1);
    // The decide cache got the address too.
    let mut out = [IpAddr::from([0, 0, 0, 0]); fkbox_client_core::rules::engine::MAX_CACHED_ADDRS];
    use fkbox_client_core::rules::engine::DnsView;
    assert_eq!(brain.dns().lookup("a.example", &mut out), Some(1));
    // TTL 1: gone after a second.
    brain.resolve_real("short.example", TYPE_A, soon()).unwrap();
    brain.resolve_real("short.example", TYPE_A, soon()).unwrap();
    assert_eq!(up.calls().len(), 2);
    std::thread::sleep(Duration::from_millis(1100));
    brain.resolve_real("short.example", TYPE_A, soon()).unwrap();
    assert_eq!(up.calls().len(), 3, "expired");
    // NXDOMAIN is cached (negative TTL) and answered with its rcode.
    let nx = answer(&brain.dns_message(&query("nx.test", TYPE_A), 0, 1000).unwrap());
    assert_eq!(nx.rcode, RCODE_NXDOMAIN);
    brain.dns_message(&query("nx.test", TYPE_A), 0, 1000).unwrap();
    assert_eq!(up.calls().len(), 4);
    // New settings drop the cache.
    brain.dns_state().apply(DnsSettings::default()).unwrap();
    assert_eq!(brain.dns_state().cache_len(), 0);
}

#[test]
fn queries_follow_the_rules_policy_and_node_servers() {
    let (brain, up) = brain_with(
        json!({"policy": [{"domains": ["+.corp.test"], "upstreams": ["udp://10.1.1.1:53"], "outbound": "fk-direct"},
                          {"domains": ["ruled.proxy.test"], "upstreams": ["tls://9.9.9.9?sni=dns.quad9.net"]}]}),
        vec![],
    );
    for name in ["x.proxy.test", "y.example", "node.proxy.test", "intra.corp.test", "ruled.proxy.test"] {
        brain.resolve_real(name, TYPE_A, soon()).unwrap();
    }
    assert_eq!(
        up.calls(),
        [
            "proxy tcp://1.1.1.1:53 x.proxy.test 1",
            "fk-direct udp://223.5.5.5:53 y.example 1",
            "fk-direct udp://223.5.5.5:53 node.proxy.test 1",
            "fk-direct udp://10.1.1.1:53 intra.corp.test 1",
            "proxy tls://9.9.9.9?sni=dns.quad9.net ruled.proxy.test 1",
        ]
    );
    // Rejected by the rules: NXDOMAIN, nobody asked.
    let (rcode, addrs, _) = brain.resolve_real("tracker.ad.test", TYPE_A, soon()).unwrap();
    assert_eq!((rcode, addrs.len()), (RCODE_NXDOMAIN, 0));
    assert_eq!(brain.dns_route("tracker.ad.test"), Route::Reject);
    assert_eq!(up.calls().len(), 5);
    // Before any configuration everything is direct.
    let empty = Brain::new();
    assert!(matches!(empty.dns_route("x.proxy.test"), Route::Via { outbound, .. } if outbound == "fk-direct"));
}

#[test]
fn failover_forwarding_and_errors() {
    let (brain, up) = brain_with(json!({"proxy_upstreams": ["udp://192.0.2.1:53", "tcp://1.1.1.1:53"]}), vec!["192.0.2.1"]);
    let (_, addrs, _) = brain.resolve_real("b.proxy.test", TYPE_A, soon()).unwrap();
    assert_eq!(addrs.len(), 1);
    assert_eq!(up.calls().len(), 2);
    assert_eq!(brain.dns_state().counters.snapshot().upstream_failures, 1);
    // Every upstream failing: SERVFAIL to the app, an error to resolve().
    let (dead, _) = brain_with(json!({"direct_upstreams": ["udp://192.0.2.1:53"]}), vec!["192.0.2.1"]);
    let reply = answer(&dead.dns_message(&query("c.example", TYPE_A), 0, 1000).unwrap());
    assert_eq!(reply.rcode, RCODE_SERVFAIL);
    assert!(matches!(dead.resolve_real("c.example", TYPE_A, soon()), Err(DnsError::Upstream(FKB_E_HOST, _))));
    // Other types are forwarded with the app's ID restored.
    let txt = query("t.example", 16);
    let forwarded = brain.dns_message(&txt, FKB_DNS_CLIENT, 1000).unwrap();
    assert_eq!(&forwarded[..2], &[0x42, 0x42]);
    assert_eq!(parse_reply(&forwarded, 0x4242).unwrap().0, 16);
    assert!(up.calls().last().unwrap().starts_with("fk-direct udp://223.5.5.5:53 t.example 16"));
    // Garbage: FORMERR when the ID is readable, an error otherwise.
    assert_eq!(answer(&brain.dns_message(&[0x42, 0x42, 0, 0, 0, 9], 0, 1000).unwrap()).rcode, RCODE_FORMERR);
    assert_eq!(brain.dns_message(&[1], 0, 1000), Err(FKB_E_INVALID));
    // No host: fake answers still work, real ones fail.
    let lonely = Brain::new();
    let fake = answer(&lonely.dns_message(&query("d.example", TYPE_A), FKB_DNS_CLIENT, 1000).unwrap());
    assert_eq!(fake.ttl, Some(FAKE_TTL));
    assert_eq!(answer(&lonely.dns_message(&query("d.example", TYPE_A), 0, 1000).unwrap()).rcode, RCODE_SERVFAIL);
    assert_eq!(lonely.resolve_real("d.example", TYPE_A, soon()), Err(DnsError::NotReady));
}

#[test]
fn prefetch_turns_misses_into_hits() {
    let (brain, up) = brain_with(json!({}), vec![]);
    let names: Vec<String> = (0..50).map(|i| format!("site{i}.example")).collect();
    let decide = |pass: &str| -> (usize, f64) {
        let mut misses = 0;
        let start = Instant::now();
        for name in &names {
            let mut meta = FkbConnMeta::new();
            meta.network = FKB_NET_TCP;
            meta.dst_port = 443;
            meta.set_domain(name, FKB_DOMAIN_FQDN);
            let mut out = FkbDecision::default();
            assert_eq!(brain.decide_meta(&meta, &mut out), FKB_OK);
            if out.flags & FKB_DECISION_DNS_MISS != 0 {
                misses += 1;
                // The IP rule is skipped on a miss: MATCH (fk-direct, index 1).
                assert_eq!(out.outbound, 1, "{pass}");
            } else {
                // Resolved into 93.184.216.0/24: IP-CIDR -> proxy.
                assert_eq!(out.outbound, 0, "{pass}");
            }
        }
        (misses, start.elapsed().as_nanos() as f64 / names.len() as f64)
    };
    let (cold, cold_ns) = decide("cold");
    assert_eq!(cold, names.len());
    let queued = brain.dns().take_prefetch();
    assert_eq!(queued.len(), names.len().min(256));
    assert_eq!(brain.run_prefetch(queued), names.len());
    assert_eq!(up.calls().len(), names.len() * 2, "A and AAAA per name");
    let (warm, warm_ns) = decide("warm");
    assert_eq!(warm, 0);
    println!(
        "STAGE4 DNS_MISS ratio: cold {cold}/{n} ({:.0}%), after prefetch {warm}/{n} ({:.0}%); decide mean cold {cold_ns:.0} ns, warm {warm_ns:.0} ns; prefetch upstream queries {}",
        100.0 * cold as f64 / names.len() as f64,
        100.0 * warm as f64 / names.len() as f64,
        up.calls().len(),
        n = names.len()
    );
}

#[test]
fn fake_ip_store_survives_a_restart_and_resolve_entry() {
    let path = std::env::temp_dir().join(format!("fkb-dns-store-{}.txt", std::process::id()));
    let settings = json!({"fake_ip_range": "198.18.128.0/20", "fake_ip_store": path.to_str().unwrap()});
    let (brain, _) = brain_with(settings.clone(), vec![]);
    let fake = answer(&brain.dns_message(&query("kept.example", TYPE_A), FKB_DNS_CLIENT, 1000).unwrap()).addrs[0];
    assert!(matches!(fake, IpAddr::V4(v4) if v4.octets()[..3] == [198, 18, 128]));
    brain.dns_state().save_fake_ip();
    let (restarted, _) = brain_with(settings, vec![]);
    assert_eq!(restarted.dns_state().reverse(fake), Ok(Some("kept.example".to_string())));
    let _ = std::fs::remove_file(&path);

    // resolve(): both families, real addresses only.
    let mut q = FkbResolveQuery {
        abi_version: FKB_ABI_VERSION,
        size: std::mem::size_of::<FkbResolveQuery>() as u32,
        qtype: 0,
        domain_len: 0,
        timeout_ms: 1000,
        domain: [0; FKB_DOMAIN_MAX],
    };
    let name = b"kept.example";
    q.domain[..name.len()].copy_from_slice(name);
    q.domain_len = name.len() as u16;
    let result = crate::resolve_query(&brain, &q).unwrap();
    assert_eq!((result.count, result.rcode, result.ttl_secs), (2, 0, 300));
    assert_eq!((result.family[0], &result.addrs[0][..4]), (4, &REAL_V4[..]));
    assert_eq!(result.family[1], 6);
    assert_eq!(crate::resolve_query(&brain, &FkbResolveQuery { domain_len: 0, ..q }).err(), Some(FKB_E_INVALID));
    assert_eq!(crate::resolve_query(&brain, &FkbResolveQuery { qtype: 15, ..q }).err(), Some(FKB_E_INVALID));
}
