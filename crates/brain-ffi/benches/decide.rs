//! decide() latency on a realistic rule set, through the C entry point the
//! Go host calls (`fkb_brain_api.decide`, i.e. metadata conversion + rules),
//! and through the engine alone.
//!
//! Rules (generated deterministically, nothing downloaded into the repo):
//! ~4000 DOMAIN-SUFFIX, 800 DOMAIN, 100 DOMAIN-KEYWORD, 10 DOMAIN-REGEX,
//! 600 IPv4 + 100 IPv6 CIDRs, 40 PROCESS-NAME and a few logic/port rules,
//! geosite categories (category-ads-all, google, geolocation-!cn, cn), GEOIP,CN
//! and MATCH. With `FKBOX_GEO_DIR` (CI fetches the pinned meta-rules-dat
//! files there) the geo rules use the real geosite.dat / geoip.dat /
//! geoip.metadb; otherwise synthetic categories of similar size.
//!
//! `cargo bench -p fkbox-brain-ffi --bench decide`; `BENCH_SUMMARY=<file>`
//! appends the markdown table to that file.

use std::fmt::Write as _;
use std::net::IpAddr;
use std::sync::Arc;
use std::time::{Duration, Instant};

use fkbox_brain_ffi::abi::*;
use fkbox_brain_ffi::{brain_api, fkb_brain_attach, global};
use fkbox_client_core::rules::engine::geo::{GeoIpDat, GeoSiteDb, SiteDomain};
use fkbox_client_core::rules::engine::import_clash::{self, ClashOptions, ProviderRef};
use fkbox_client_core::rules::engine::mmdb::Mmdb;
use fkbox_client_core::rules::engine::{compile, conn::Conn, DnsView, Env, Network, Router};

struct Rng(u64);

impl Rng {
    fn next(&mut self) -> u64 {
        self.0 = self.0.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
        self.0 >> 33
    }
    fn below(&mut self, n: usize) -> usize {
        (self.next() % n as u64) as usize
    }
}

const SYLLABLES: [&str; 16] = ["ka", "lo", "mi", "ne", "ro", "ta", "vu", "xi", "ze", "po", "qu", "sa", "bi", "de", "fu", "gy"];
const TLDS: [&str; 8] = ["com", "net", "org", "io", "cn", "jp", "co", "app"];

fn word(rng: &mut Rng) -> String {
    (0..2 + rng.below(3)).map(|_| SYLLABLES[rng.below(SYLLABLES.len())]).collect()
}

fn domain(rng: &mut Rng) -> String {
    format!("{}.{}", word(rng), TLDS[rng.below(TLDS.len())])
}

fn v4(rng: &mut Rng) -> [u8; 4] {
    let x = rng.next() as u32;
    x.to_be_bytes()
}

// --- synthetic geo files in the real wire formats (when no real data) ---

fn varint(out: &mut Vec<u8>, mut v: u64) {
    while v >= 0x80 {
        out.push((v as u8) | 0x80);
        v >>= 7;
    }
    out.push(v as u8);
}

fn field(out: &mut Vec<u8>, f: u32, bytes: &[u8]) {
    varint(out, u64::from(f) << 3 | 2);
    varint(out, bytes.len() as u64);
    out.extend_from_slice(bytes);
}

fn synthetic_geosite(rng: &mut Rng, cn: &mut Vec<String>) -> Vec<u8> {
    let mut list = Vec::new();
    for (code, count) in [("CN", 30_000usize), ("GOOGLE", 800), ("CATEGORY-ADS-ALL", 3_000), ("GEOLOCATION-!CN", 8_000)] {
        let mut site = Vec::new();
        field(&mut site, 1, code.as_bytes());
        for i in 0..count {
            let name = domain(rng);
            if code == "CN" {
                cn.push(name.clone());
            }
            let mut d = Vec::new();
            let kind = if i % 50 == 0 { 3 } else { 2 };
            varint(&mut d, 1 << 3);
            varint(&mut d, kind);
            field(&mut d, 2, name.as_bytes());
            field(&mut site, 2, &d);
        }
        field(&mut list, 1, &site);
    }
    list
}

fn synthetic_geoip(rng: &mut Rng, cn_ips: &mut Vec<IpAddr>) -> Vec<u8> {
    let mut e = Vec::new();
    field(&mut e, 1, b"CN");
    for _ in 0..6_000 {
        let a = v4(rng);
        cn_ips.push(IpAddr::from([a[0], a[1], a[2], 7]));
        let mut c = Vec::new();
        field(&mut c, 1, &[a[0], a[1], a[2], 0]);
        varint(&mut c, 2 << 3);
        varint(&mut c, 24);
        field(&mut e, 2, &c);
    }
    let mut list = Vec::new();
    field(&mut list, 1, &e);
    list
}

struct Setup {
    router: Router,
    metas: Vec<FkbConnMeta>,
    label: String,
    compile: Duration,
}

fn rules_text(rng: &mut Rng, suffixes: &mut Vec<String>, cidrs: &mut Vec<[u8; 4]>, apps: &mut Vec<String>) -> String {
    let targets = ["Proxy", "DIRECT", "HK"];
    let mut y = String::from("rules:\n  - DST-PORT,22,REJECT\n  - GEOSITE,category-ads-all,REJECT\n");
    for i in 0..4000 {
        let d = domain(rng);
        let _ = writeln!(y, "  - DOMAIN-SUFFIX,{d},{}", targets[i % 3]);
        suffixes.push(d);
    }
    for i in 0..800 {
        let _ = writeln!(y, "  - DOMAIN,{}.{},{}", word(rng), domain(rng), targets[i % 3]);
    }
    for i in 0..100 {
        let _ = writeln!(y, "  - DOMAIN-KEYWORD,{}{},{}", word(rng), word(rng), targets[i % 3]);
    }
    for i in 0..10 {
        let _ = writeln!(y, "  - DOMAIN-REGEX,^{}[0-9]+\\.{},{}", word(rng), TLDS[i % TLDS.len()], targets[i % 3]);
    }
    let _ = writeln!(y, "  - AND,((NETWORK,UDP),(DST-PORT,443)),REJECT");
    for i in 0..40 {
        let app = format!("com.{}.{}", word(rng), word(rng));
        let _ = writeln!(y, "  - PROCESS-NAME,{app},{}", targets[i % 3]);
        apps.push(app);
    }
    for i in 0..600 {
        let a = v4(rng);
        let prefix = 16 + rng.below(9);
        let _ = writeln!(y, "  - IP-CIDR,{}.{}.{}.0/{prefix},{},no-resolve", a[0], a[1], a[2], targets[i % 3]);
        cidrs.push(a);
    }
    for i in 0..100 {
        let _ = writeln!(y, "  - IP-CIDR6,2001:{:x}:{:x}::/48,{},no-resolve", rng.below(0xffff), rng.below(0xffff), targets[i % 3]);
    }
    y.push_str("  - OR,((DST-PORT,6881-6889),(DOMAIN-KEYWORD,tracker)),DIRECT\n");
    y.push_str("  - GEOSITE,google,Proxy\n  - GEOSITE,geolocation-!cn,Proxy\n  - GEOSITE,cn,DIRECT\n  - GEOIP,LAN,DIRECT,no-resolve\n  - GEOIP,CN,DIRECT\n  - MATCH,Proxy\n");
    y
}

struct Cache {
    answers: std::collections::HashMap<String, Vec<IpAddr>>,
}

impl DnsView for Cache {
    fn lookup(&self, domain: &str, out: &mut [IpAddr; 8]) -> Option<usize> {
        let a = self.answers.get(domain)?;
        let n = a.len().min(8);
        out[..n].copy_from_slice(&a[..n]);
        Some(n)
    }
}

fn setup(geo_dir: Option<&str>, use_mmdb: bool) -> (Setup, Vec<(String, IpAddr)>) {
    let mut rng = Rng(0x5eed_f00d);
    let (mut suffixes, mut cidrs, mut apps) = (Vec::new(), Vec::new(), Vec::new());
    let yaml = rules_text(&mut rng, &mut suffixes, &mut cidrs, &mut apps);
    let ir = import_clash::import(&yaml, &|_: &ProviderRef| -> Result<Vec<u8>, String> { Err("none".into()) }, &ClashOptions::default())
        .expect("import");
    let mut cn_domains: Vec<String> = Vec::new();
    let mut cn_ips: Vec<IpAddr> = Vec::new();
    let (site, ipdat, mmdb, label) = match geo_dir {
        Some(dir) => {
            let read = |n: &str| -> Arc<[u8]> { Arc::from(std::fs::read(std::path::Path::new(dir).join(n)).expect("geo file")) };
            let site = GeoSiteDb::parse(read("geosite.dat")).expect("geosite");
            for d in site.domains("cn").expect("cn") {
                if let SiteDomain::Suffix(s) | SiteDomain::Full(s) = d {
                    if s.contains('.') && cn_domains.len() < 20_000 {
                        cn_domains.push(format!("www.{s}"));
                    }
                }
            }
            cn_ips.extend(["223.5.5.5", "114.114.114.114", "119.29.29.29", "180.76.76.76"].iter().map(|s| s.parse::<IpAddr>().unwrap()));
            if use_mmdb {
                (site, None, Some(Arc::new(Mmdb::parse(read("geoip.metadb")).expect("mmdb"))), "real geosite.dat + geoip.metadb")
            } else {
                (site, Some(GeoIpDat::parse(read("geoip.dat")).expect("geoip")), None, "real geosite.dat + geoip.dat")
            }
        }
        None => {
            let site = GeoSiteDb::parse(Arc::from(synthetic_geosite(&mut rng, &mut cn_domains))).expect("geosite");
            let ipdat = GeoIpDat::parse(Arc::from(synthetic_geoip(&mut rng, &mut cn_ips))).expect("geoip");
            (site, Some(ipdat), None, "synthetic geosite/geoip (FKBOX_GEO_DIR unset)")
        }
    };
    let outbounds: Vec<String> = ["DIRECT", "Proxy", "HK"].iter().map(|s| s.to_string()).collect();
    let inbounds = vec!["tun-in".to_string()];
    let started = Instant::now();
    let router = compile(
        &ir,
        &Env { outbounds: &outbounds, inbounds: &inbounds, geosite: Some(&site), geoip: ipdat.as_ref(), mmdb, default_final: None },
    )
    .expect("compile");
    let compile_time = started.elapsed();

    let mut metas = Vec::with_capacity(10_000);
    let mut cached = Vec::new();
    for i in 0..10_000 {
        let mut m = FkbConnMeta::new();
        m.network = FKB_NET_TCP;
        m.set_src(IpAddr::from([172, 19, 0, 1]));
        m.src_port = 30000 + (i % 30000) as u16;
        m.dst_port = 443;
        m.inbound_index = 0;
        m.uid = 10000 + (i % 200) as i32;
        if i % 3 == 0 {
            m.set_package(&apps[rng.below(apps.len())]);
        }
        m.protocol = 2;
        match rng.below(100) {
            0..=24 => {
                let d = format!("{}.{}", word(&mut rng), suffixes[rng.below(suffixes.len())]);
                m.set_domain(&d, FKB_DOMAIN_SNIFFED);
            }
            25..=39 => m.set_domain(&cn_domains[rng.below(cn_domains.len())], FKB_DOMAIN_SNIFFED),
            40..=49 => m.set_domain(&format!("{}.google.com", word(&mut rng)), FKB_DOMAIN_SNIFFED),
            50..=69 => {
                let d = format!("{}.{}.example", word(&mut rng), word(&mut rng));
                m.set_domain(&d, FKB_DOMAIN_FQDN);
                if rng.below(2) == 0 {
                    let ip = if rng.below(2) == 0 { cn_ips[rng.below(cn_ips.len())] } else { IpAddr::from(v4(&mut rng)) };
                    cached.push((d, ip));
                }
            }
            70..=89 => {
                let ip = if rng.below(2) == 0 {
                    let a = cidrs[rng.below(cidrs.len())];
                    IpAddr::from([a[0], a[1], a[2], 9])
                } else {
                    IpAddr::from(v4(&mut rng))
                };
                m.set_dst(ip);
                m.protocol = 0;
            }
            _ => {
                m.network = FKB_NET_UDP;
                m.protocol = 3;
                m.set_domain(&domain(&mut rng), FKB_DOMAIN_SNIFFED);
            }
        }
        metas.push(m);
    }
    (Setup { router, metas, label: label.to_string(), compile: compile_time }, cached)
}

fn percentile(sorted: &[u32], p: f64) -> u32 {
    sorted[((sorted.len() as f64 - 1.0) * p).round() as usize]
}

fn timer_overhead() -> u32 {
    let mut samples: Vec<u32> = (0..200_000)
        .map(|_| {
            let t = Instant::now();
            std::hint::black_box(());
            t.elapsed().as_nanos() as u32
        })
        .collect();
    samples.sort_unstable();
    percentile(&samples, 0.5)
}

fn run(geo_dir: Option<&str>, use_mmdb: bool, table: &mut String) {
    let (setup, cached) = setup(geo_dir, use_mmdb);
    let stats = setup.router.stats();
    let api = brain_api();
    let decide = api.decide.expect("decide");
    let brain = global();
    for (d, ip) in &cached {
        brain.dns().insert(d, &[*ip], Duration::from_secs(3600));
    }
    let engine_cache = Cache { answers: cached.iter().map(|(d, ip)| (d.clone(), vec![*ip])).collect() };
    // Engine-only inputs (the same connections, already converted).
    let conns: Vec<Conn> = setup.metas.iter().map(|m| conn_of(m).expect("conn")).collect();
    let mut out = FkbDecision::default();
    let mut tally = [0u64; 5];
    for m in &setup.metas {
        let _ = unsafe { decide(m, &mut out) };
    }
    brain.install(1, setup.router);
    for m in setup.metas.iter().cycle().take(200_000) {
        assert_eq!(unsafe { decide(m, &mut out) }, FKB_OK);
        tally[out.action as usize] += 1;
        if out.flags & FKB_DECISION_DNS_MISS != 0 {
            tally[4] += 1;
        }
    }
    // Per-call latency through the C entry point.
    const N: usize = 1_000_000;
    let mut samples = Vec::with_capacity(N);
    for m in setup.metas.iter().cycle().take(N) {
        let t = Instant::now();
        let rc = unsafe { decide(std::hint::black_box(m), &mut out) };
        samples.push(t.elapsed().as_nanos() as u32);
        std::hint::black_box((rc, out));
    }
    samples.sort_unstable();
    // Tight loop mean.
    let started = Instant::now();
    for m in setup.metas.iter().cycle().take(2 * N) {
        std::hint::black_box(unsafe { decide(std::hint::black_box(m), &mut out) });
    }
    let mean_c = started.elapsed().as_nanos() as f64 / (2 * N) as f64;
    // Engine alone (no struct conversion, the bench's own DNS map).
    let snapshot_router = {
        let (s, _) = setup_router_only(geo_dir, use_mmdb);
        s
    };
    let started = Instant::now();
    for c in conns.iter().cycle().take(2 * N) {
        std::hint::black_box(snapshot_router.decide(std::hint::black_box(c), &engine_cache));
    }
    let mean_engine = started.elapsed().as_nanos() as f64 / (2 * N) as f64;
    // 4 threads, one shared snapshot.
    let threads = 4;
    let started = Instant::now();
    std::thread::scope(|s| {
        for t in 0..threads {
            let metas = &setup.metas;
            s.spawn(move || {
                let mut out = FkbDecision::default();
                for m in metas.iter().cycle().skip(t * 997).take(N) {
                    std::hint::black_box(unsafe { decide(std::hint::black_box(m), &mut out) });
                }
            });
        }
    });
    let parallel = started.elapsed().as_nanos() as f64 / (threads * N) as f64;
    let overhead = timer_overhead();
    let _ = writeln!(
        table,
        "| {} | {} ({} indexed / {} generic) | {:.0} ms | {} | {} | {} | {} | {} | {:.0} | {:.0} | {:.1} | {} |",
        setup.label,
        stats.rules,
        stats.indexed,
        stats.generic,
        setup.compile.as_secs_f64() * 1000.0,
        percentile(&samples, 0.5),
        percentile(&samples, 0.9),
        percentile(&samples, 0.99),
        percentile(&samples, 0.999),
        samples[samples.len() - 1],
        mean_c,
        mean_engine,
        parallel,
        overhead,
    );
    println!(
        "{}: rules {:?}; actions over 200k: route {} reject {} drop {} hijack {} (dns-miss flagged {})",
        setup.label, stats, tally[0], tally[1], tally[2], tally[3], tally[4]
    );
}

fn setup_router_only(geo_dir: Option<&str>, use_mmdb: bool) -> (Router, ()) {
    let (s, _) = setup(geo_dir, use_mmdb);
    (s.router, ())
}

/// Following a rule's group to a real outbound (`GroupTable::resolve`,
/// what decide adds per connection that names a group).
fn groups_bench(table: &mut String) {
    use fkbox_brain_ffi::groups::{GroupSet, Groups};
    use fkbox_client_core::config::brain::{import, Loaders};
    use fkbox_client_core::rules::engine::Action;
    let yaml = "proxies:\n  - {name: a, type: ss, server: 127.0.0.1, port: 1001, cipher: aes-128-gcm, password: pw}\n  - {name: b, type: ss, server: 127.0.0.1, port: 1002, cipher: aes-128-gcm, password: pw}\n  - {name: c, type: ss, server: 127.0.0.1, port: 1003, cipher: aes-128-gcm, password: pw}\nproxy-groups:\n  - {name: Auto, type: url-test, proxies: [a, b, c]}\n  - {name: Pick, type: select, proxies: [Auto, a]}\n  - {name: LB, type: load-balance, proxies: [a, b, c], strategy: consistent-hashing}\n";
    let result = import(yaml, &Loaders::none()).expect("groups yaml");
    let outbounds: Vec<String> = ["a", "b", "c", "fk-direct", "fk-block"].iter().map(|s| s.to_string()).collect();
    let set = Arc::new(GroupSet::build(&result.groups, &outbounds, "fk-direct").expect("group set"));
    let groups = Groups::default();
    let resolved = groups.install(set.clone());
    let names: Vec<String> = (0..1024).map(|i| format!("www.site{i}.example")).collect();
    let conns: Vec<Conn> = names.iter().map(|n| Conn { domain: Some(n.as_str()), ..Conn::default() }).collect();
    const N: usize = 2_000_000;
    let _ = writeln!(table, "\n### group resolution (per decision that names a group)\n\n| path | mean ns |\n| --- | --- |");
    for (label, name) in [("select -> url-test -> node (2 hops)", "Pick"), ("load-balance consistent-hashing", "LB")] {
        let action = Action::Outbound(outbounds.len() as u32 + set.group(name).expect("group"));
        let started = Instant::now();
        for c in conns.iter().cycle().take(N) {
            std::hint::black_box(resolved.resolve(std::hint::black_box(action), c));
        }
        let mean = started.elapsed().as_nanos() as f64 / N as f64;
        let _ = writeln!(table, "| {label} | {mean:.1} |");
    }
}

fn main() {
    // `cargo bench` passes --bench; nothing else is read from the arguments.
    let geo_dir = std::env::var("FKBOX_GEO_DIR").ok();
    let mut api = FkbBrainApi { abi_version: 0, size: 0, decide: None, resolve: None, dns_message: None, fakeip_reverse: None };
    assert_eq!(unsafe { fkb_brain_attach(std::ptr::null(), &mut api) }, FKB_OK);
    let mut table = String::new();
    table.push_str(
        "| data | rules | compile | p50 ns | p90 ns | p99 ns | p99.9 ns | max ns | C-call mean ns | engine mean ns | 4-thread ns/op | timer ns |\n",
    );
    table.push_str("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
    match geo_dir.as_deref() {
        Some(dir) => {
            run(Some(dir), false, &mut table);
            run(Some(dir), true, &mut table);
        }
        None => run(None, false, &mut table),
    }
    groups_bench(&mut table);
    let cpus = std::thread::available_parallelism().map(|n| n.get()).unwrap_or(0);
    let note = format!(
        "\n{} CPUs, {}. Per-call numbers time each call with `Instant` (the timer column is the cost of an empty timed section, included in each sample); means are tight loops over 10k recorded connections. C-call = `fkb_brain_api.decide` (metadata conversion + snapshot load + rules); engine = `Router::decide` alone.\n",
        cpus,
        std::env::consts::ARCH
    );
    table.push_str(&note);
    println!("{table}");
    if let Ok(path) = std::env::var("BENCH_SUMMARY") {
        use std::io::Write;
        if let Ok(mut f) = std::fs::OpenOptions::new().create(true).append(true).open(path) {
            let _ = f.write_all(b"### decide() latency\n\n");
            let _ = f.write_all(table.as_bytes());
        }
    }
    let _ = Network::Tcp;
}
