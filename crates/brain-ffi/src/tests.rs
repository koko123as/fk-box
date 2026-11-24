//! The C surface against a fake host: layout, metadata conversion, the
//! start/reload/stop lifecycle with generations, the Rust -> Go wrappers,
//! concurrent decides during reloads, and the global entry points.

use std::ffi::c_void;
use std::net::IpAddr;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use fkbox_client_core::rules::engine::import_clash::{self, ClashOptions, ProviderRef};
use fkbox_client_core::rules::engine::{compile, Env, Network, Router};

use crate::abi::*;
use crate::*;

fn router(yaml: &str) -> Router {
    let ir = import_clash::import(yaml, &|_: &ProviderRef| -> Result<Vec<u8>, String> { Err("no files".into()) }, &ClashOptions::default())
        .unwrap();
    let outbounds = vec!["DIRECT".to_string(), "proxy".to_string()];
    let inbounds = vec!["tun-in".to_string()];
    compile(&ir, &Env { outbounds: &outbounds, inbounds: &inbounds, geosite: None, geoip: None, mmdb: None, default_final: None }).unwrap()
}

const RULES: &str = "rules:\n  - DOMAIN-SUFFIX,google.com,proxy\n  - DST-PORT,25,REJECT\n  - IP-CIDR,1.1.1.0/24,proxy\n  - PROCESS-NAME,com.example,REJECT-DROP\n  - MATCH,DIRECT\n";

fn meta(domain: &str, port: u16) -> FkbConnMeta {
    let mut m = FkbConnMeta::new();
    m.network = FKB_NET_TCP;
    m.set_src("172.19.0.1".parse().unwrap());
    m.src_port = 40000;
    m.dst_port = port;
    m.inbound_index = 0;
    if !domain.is_empty() {
        m.set_domain(domain, FKB_DOMAIN_SNIFFED);
    }
    m
}

#[derive(Default)]
struct FakeHost {
    log: Mutex<Vec<String>>,
    reply: Vec<u8>,
    probes: AtomicU64,
}

fn fake(ctx: *mut c_void) -> &'static FakeHost {
    // SAFETY: every ctx in these tests is a leaked FakeHost.
    unsafe { &*(ctx as *const FakeHost) }
}

fn write_err(err: *mut FkbBuf, msg: &str) {
    // SAFETY: the brain passes a valid buffer of `cap` bytes.
    unsafe {
        let b = &mut *err;
        let n = msg.len().min(b.cap);
        std::ptr::copy_nonoverlapping(msg.as_ptr(), b.ptr, n);
        b.len = n;
    }
}

unsafe extern "C" fn fake_start(ctx: *mut c_void, json: FkbStr, generation: u32, err: *mut FkbBuf) -> i32 {
    let text = String::from_utf8_lossy(unsafe { json.as_bytes() }).into_owned();
    fake(ctx).log.lock().unwrap().push(format!("start {generation} {text}"));
    if text.contains("fail") {
        write_err(err, "bad config");
        return FKB_E_HOST;
    }
    FKB_OK
}

unsafe extern "C" fn fake_reload(ctx: *mut c_void, json: FkbStr, generation: u32, err: *mut FkbBuf) -> i32 {
    let text = String::from_utf8_lossy(unsafe { json.as_bytes() }).into_owned();
    fake(ctx).log.lock().unwrap().push(format!("reload {generation} {text}"));
    if text.contains("fail") {
        write_err(err, "bad config");
        return FKB_E_HOST;
    }
    FKB_OK
}

unsafe extern "C" fn fake_stop(ctx: *mut c_void, _err: *mut FkbBuf) -> i32 {
    fake(ctx).log.lock().unwrap().push("stop".into());
    FKB_OK
}

unsafe extern "C" fn fake_probe(
    ctx: *mut c_void,
    tag: FkbStr,
    url: FkbStr,
    timeout_ms: u32,
    out: *mut FkbProbeResult,
    err: *mut FkbBuf,
) -> i32 {
    let host = fake(ctx);
    host.probes.fetch_add(1, Ordering::Relaxed);
    let tag = String::from_utf8_lossy(unsafe { tag.as_bytes() }).into_owned();
    let url = String::from_utf8_lossy(unsafe { url.as_bytes() }).into_owned();
    host.log.lock().unwrap().push(format!("probe {tag} {url} {timeout_ms}"));
    if tag != "proxy" {
        write_err(err, "unknown outbound");
        return FKB_E_NOT_FOUND;
    }
    unsafe { out.write(FkbProbeResult { http_status: 204, latency_ms: 42, flags: 0, reserved: 0 }) };
    FKB_OK
}

unsafe extern "C" fn fake_exchange(
    ctx: *mut c_void,
    _tag: FkbStr,
    upstream: FkbStr,
    message: FkbStr,
    _timeout_ms: u32,
    response: *mut FkbBuf,
    _err: *mut FkbBuf,
) -> i32 {
    let host = fake(ctx);
    let up = String::from_utf8_lossy(unsafe { upstream.as_bytes() }).into_owned();
    host.log.lock().unwrap().push(format!("exchange {up} {}", message.len));
    let resp = unsafe { &mut *response };
    if resp.cap < host.reply.len() {
        resp.len = host.reply.len();
        return FKB_E_BUFFER;
    }
    unsafe { std::ptr::copy_nonoverlapping(host.reply.as_ptr(), resp.ptr, host.reply.len()) };
    resp.len = host.reply.len();
    FKB_OK
}

unsafe extern "C" fn fake_stats(_ctx: *mut c_void, out: *mut FkbStats) -> i32 {
    unsafe { out.write(FkbStats { uplink_bytes: 10, downlink_bytes: 20, connections_tcp: 3, ..FkbStats::default() }) };
    FKB_OK
}

fn host_table(host: &'static FakeHost) -> FkbHostApi {
    FkbHostApi {
        abi_version: FKB_ABI_VERSION,
        size: std::mem::size_of::<FkbHostApi>() as u32,
        ctx: host as *const FakeHost as *mut c_void,
        start: Some(fake_start),
        stop: Some(fake_stop),
        reload: Some(fake_reload),
        probe: Some(fake_probe),
        exchange: Some(fake_exchange),
        stats: Some(fake_stats),
    }
}

fn leaked(reply: Vec<u8>) -> &'static FakeHost {
    Box::leak(Box::new(FakeHost { reply, ..FakeHost::default() }))
}

#[test]
fn sizes_match_the_header() {
    let sizes = [
        (FKB_STRUCT_CONN_META, 576),
        (FKB_STRUCT_DECISION, 16),
        (FKB_STRUCT_STR, 16),
        (FKB_STRUCT_BUF, 24),
        (FKB_STRUCT_RESOLVE_QUERY, 272),
        (FKB_STRUCT_RESOLVE_RESULT, 288),
        (FKB_STRUCT_PROBE_RESULT, 16),
        (FKB_STRUCT_STATS, 56),
        (FKB_STRUCT_BRAIN_API, 40),
        (FKB_STRUCT_HOST_API, 64),
    ];
    for (which, size) in sizes {
        assert_eq!(fkb_struct_size(which), size, "{which}");
    }
    assert_eq!(fkb_struct_size(99), 0);
    assert_eq!(fkb_brain_abi_version(), 2);
}

#[test]
fn metadata_becomes_a_connection() {
    let mut m = meta("www.Example.com", 443);
    m.network = FKB_NET_UDP;
    m.protocol = 3;
    m.uid = 10123;
    m.set_dst("::ffff:1.2.3.4".parse().unwrap());
    m.set_package("com.example.app");
    m.set_process("example");
    let c = conn_of(&m).unwrap();
    assert_eq!(c.network, Some(Network::Udp));
    assert_eq!(c.protocol, Some("quic"));
    assert_eq!(c.domain, Some("www.Example.com"));
    assert_eq!(c.dst, Some("::ffff:1.2.3.4".parse::<IpAddr>().unwrap()));
    assert_eq!(c.src, Some("172.19.0.1".parse::<IpAddr>().unwrap()));
    assert_eq!((c.src_port, c.dst_port, c.uid, c.inbound), (40000, 443, Some(10123), Some(0)));
    assert_eq!((c.package, c.process), (Some("com.example.app"), Some("example")));
    let blank = FkbConnMeta::new();
    let empty = conn_of(&blank).unwrap();
    assert_eq!((empty.network, empty.domain, empty.uid, empty.inbound, empty.dst), (None, None, None, None, None));
    for broken in [
        FkbConnMeta { abi_version: 3, ..m },
        FkbConnMeta { size: 304, ..m },
        FkbConnMeta { network: 9, ..m },
        FkbConnMeta { protocol: 200, ..m },
        FkbConnMeta { dst_ip_version: 5, ..m },
        FkbConnMeta { domain_len: 300, ..m },
    ] {
        assert_eq!(conn_of(&broken).err(), Some(FKB_E_INVALID));
    }
    let mut bad_utf8 = meta("", 443);
    bad_utf8.domain[..2].copy_from_slice(&[0xff, 0xfe]);
    bad_utf8.domain_len = 2;
    assert_eq!(conn_of(&bad_utf8).err(), Some(FKB_E_INVALID));
    let mut long = FkbConnMeta::new();
    long.set_package(&"é".repeat(100));
    assert_eq!((long.package_len, long.flags & 0x01), (128, 0x01));
    assert!(conn_of(&long).is_ok());
}

#[test]
fn lifecycle_generations_and_failed_reloads() {
    let brain = Brain::new();
    let mut out = FkbDecision::default();
    assert_eq!(brain.decide_meta(&meta("www.google.com", 443), &mut out), FKB_E_NOT_READY);
    assert_eq!(brain.start("{}", router(RULES)).unwrap_err().code, FKB_E_NOT_READY);
    let host = leaked(Vec::new());
    brain.set_host(HostApi::from_raw(host_table(host)).unwrap());
    assert_eq!(brain.start(r#"{"outbounds":["DIRECT","proxy"]}"#, router(RULES)).unwrap(), 1);
    assert_eq!(brain.decide_meta(&meta("www.google.com", 443), &mut out), FKB_OK);
    assert_eq!(out, FkbDecision { action: FKB_ACTION_ROUTE, flags: 0, outbound: 1, rule_index: 0, generation: 1 });
    assert_eq!(brain.decide_meta(&meta("mail.example", 25), &mut out), FKB_OK);
    assert_eq!((out.action, out.rule_index), (FKB_ACTION_REJECT, 1));
    let mut app = meta("", 443);
    app.set_dst("8.8.8.8".parse().unwrap());
    app.set_package("com.example");
    assert_eq!(brain.decide_meta(&app, &mut out), FKB_OK);
    assert_eq!((out.action, out.rule_index), (FKB_ACTION_REJECT_DROP, 3));
    let err = brain.reload("fail", router("rules:\n  - MATCH,proxy\n")).unwrap_err();
    assert_eq!((err.code, err.message.as_str()), (FKB_E_HOST, "bad config"));
    assert_eq!(brain.generation(), Some(1));
    brain.decide_meta(&meta("nothing.example", 443), &mut out);
    assert_eq!((out.outbound, out.generation, out.rule_index), (0, 1, 4));
    assert_eq!(brain.reload("{}", router("rules:\n  - MATCH,proxy\n")).unwrap(), 3);
    brain.decide_meta(&meta("nothing.example", 443), &mut out);
    assert_eq!((out.outbound, out.generation, out.rule_index), (1, 3, 0));
    brain.stop().unwrap();
    assert_eq!(brain.decide_meta(&meta("nothing.example", 443), &mut out), FKB_E_NOT_READY);
    let log = host.log.lock().unwrap().clone();
    assert_eq!(log, [r#"start 1 {"outbounds":["DIRECT","proxy"]}"#, "reload 2 fail", "reload 3 {}", "stop"]);
}

#[test]
fn final_target_and_dns_miss_flag() {
    let brain = Brain::new();
    brain.install(5, router("rules:\n  - IP-CIDR,1.1.1.0/24,proxy\n"));
    let mut out = FkbDecision::default();
    assert_eq!(brain.decide_meta(&meta("one.test", 443), &mut out), FKB_OK);
    assert_eq!(
        out,
        FkbDecision { action: FKB_ACTION_ROUTE, flags: FKB_DECISION_DNS_MISS, outbound: 0, rule_index: FKB_RULE_FINAL, generation: 5 }
    );
    assert_eq!(brain.dns().take_prefetch(), vec![Box::<str>::from("one.test")]);
    brain.dns().insert("one.test", &["1.1.1.1".parse().unwrap()], Duration::from_secs(60));
    assert_eq!(brain.decide_meta(&meta("ONE.test", 443), &mut out), FKB_OK);
    assert_eq!((out.outbound, out.flags, out.rule_index), (1, 0, 0));
}

#[test]
fn host_wrappers() {
    let host = leaked(vec![7u8; 6000]);
    let api = HostApi::from_raw(host_table(host)).unwrap();
    let probe = api.probe("proxy", "https://www.gstatic.com/generate_204", 3000).unwrap();
    assert_eq!((probe.http_status, probe.latency_ms), (204, 42));
    let err = api.probe("nope", "https://x.test", 3000).unwrap_err();
    assert_eq!((err.code, err.message.as_str()), (FKB_E_NOT_FOUND, "unknown outbound"));
    // The reply is larger than the first buffer: the wrapper grows it once.
    let reply = api.exchange("proxy", "udp://8.8.8.8:53", &[1, 2, 3], 2000).unwrap();
    assert_eq!(reply.len(), 6000);
    let stats = api.stats().unwrap();
    assert_eq!((stats.uplink_bytes, stats.downlink_bytes, stats.connections_tcp), (10, 20, 3));
    assert_eq!(host.probes.load(Ordering::Relaxed), 2);
    let partial = FkbHostApi { probe: None, exchange: None, stats: None, ..host_table(host) };
    let partial = HostApi::from_raw(partial).unwrap();
    assert_eq!(partial.probe("proxy", "u", 1).unwrap_err().code, FKB_E_UNIMPLEMENTED);
    assert_eq!(partial.exchange("proxy", "u", &[], 1).unwrap_err().code, FKB_E_UNIMPLEMENTED);
    assert_eq!(partial.stats().unwrap_err().code, FKB_E_UNIMPLEMENTED);
    assert_eq!(HostApi::from_raw(FkbHostApi { size: 8, ..host_table(host) }).err(), Some(FKB_E_INVALID));
    assert_eq!(HostApi::from_raw(FkbHostApi { abi_version: 0, ..host_table(host) }).err(), Some(FKB_E_INVALID));
}

/// Readers never see half a reload: the outbound always belongs to the
/// generation the same decision reports.
#[test]
fn concurrent_decides_see_whole_snapshots() {
    let brain = Arc::new(Brain::new());
    let odd = "rules:\n  - DOMAIN-SUFFIX,a.test,DIRECT\n  - MATCH,DIRECT\n";
    let even = "rules:\n  - DOMAIN-SUFFIX,a.test,proxy\n  - MATCH,proxy\n";
    brain.install(1, router(odd));
    let stop = Arc::new(AtomicBool::new(false));
    let mut readers = Vec::new();
    for t in 0..4 {
        let brain = brain.clone();
        let stop = stop.clone();
        readers.push(std::thread::spawn(move || {
            let m = meta(if t % 2 == 0 { "x.a.test" } else { "other.test" }, 443);
            let mut out = FkbDecision::default();
            let mut n = 0u64;
            while !stop.load(Ordering::Relaxed) || n < 10_000 {
                assert_eq!(brain.decide_meta(&m, &mut out), FKB_OK);
                assert_eq!(out.outbound, if out.generation % 2 == 1 { 0 } else { 1 }, "generation {}", out.generation);
                n += 1;
            }
            n
        }));
    }
    for g in 2..=200u32 {
        brain.install(g, router(if g % 2 == 1 { odd } else { even }));
    }
    stop.store(true, Ordering::Relaxed);
    let total: u64 = readers.into_iter().map(|r| r.join().unwrap()).sum();
    assert!(total >= 40_000);
    assert_eq!(brain.generation(), Some(200));
}

#[test]
fn global_entry_points() {
    let host = leaked(Vec::new());
    let table = host_table(host);
    let mut api = FkbBrainApi { abi_version: 0, size: 0, decide: None, resolve: None, dns_message: None, fakeip_reverse: None };
    assert_eq!(unsafe { fkb_brain_attach(&table, std::ptr::null_mut()) }, FKB_E_INVALID);
    let bad = FkbHostApi { size: 63, ..table };
    assert_eq!(unsafe { fkb_brain_attach(&bad, &mut api) }, FKB_E_INVALID);
    assert_eq!(unsafe { fkb_brain_attach(&table, &mut api) }, FKB_OK);
    assert_eq!((api.abi_version, api.size), (FKB_ABI_VERSION, 40));
    assert!(api.dns_message.is_some() && api.fakeip_reverse.is_some());
    let decide = api.decide.unwrap();
    let resolve = api.resolve.unwrap();
    let m = meta("www.google.com", 443);
    let mut out = FkbDecision::default();
    assert_eq!(unsafe { decide(&m, &mut out) }, FKB_E_NOT_READY);
    global().install(7, router(RULES));
    assert_eq!(unsafe { decide(&m, &mut out) }, FKB_OK);
    assert_eq!((out.outbound, out.generation), (1, 7));
    assert_eq!(unsafe { decide(std::ptr::null(), &mut out) }, FKB_E_INVALID);
    assert_eq!(unsafe { decide(&m, std::ptr::null_mut()) }, FKB_E_INVALID);
    let wrong = FkbConnMeta { size: 304, ..m };
    assert_eq!(unsafe { decide(&wrong, &mut out) }, FKB_E_INVALID);
    let query = FkbResolveQuery {
        abi_version: FKB_ABI_VERSION,
        size: std::mem::size_of::<FkbResolveQuery>() as u32,
        qtype: 1,
        domain_len: 0,
        timeout_ms: 1000,
        domain: [0; FKB_DOMAIN_MAX],
    };
    let mut result =
        FkbResolveResult { count: 0, ttl_secs: 0, rcode: 0, reserved: 0, family: [0; FKB_RESOLVE_MAX], addrs: [[0; 16]; FKB_RESOLVE_MAX] };
    // An empty name is malformed (resolve is implemented since ABI 2, see dns::tests).
    assert_eq!(unsafe { resolve(&query, &mut result) }, FKB_E_INVALID);
    assert_eq!(unsafe { resolve(&FkbResolveQuery { size: 1, ..query }, &mut result) }, FKB_E_INVALID);
    // A start through the global brain reaches the attached host.
    assert_eq!(global().start("{}", router(RULES)).unwrap(), 1);
    assert_eq!(unsafe { decide(&m, &mut out) }, FKB_OK);
    assert_eq!(out.generation, 1);
    assert_eq!(host.log.lock().unwrap().as_slice(), ["start 1 {}"]);
}
