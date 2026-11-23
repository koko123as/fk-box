//! The engine end to end: import, compile, decide.

use std::cell::RefCell;
use std::collections::HashMap;
use std::net::IpAddr;
use std::sync::Arc;

use serde_json::json;

use super::geo::testdata as geodata;
use super::geo::{GeoIpDat, GeoSiteDb};
use super::import_clash::{self, ClashOptions, ProviderRef};
use super::import_singbox::{self, RuleSetRef};
use super::mmdb::testdata as mmdbdata;
use super::mmdb::Mmdb;
use super::ruleset::testdata as rsdata;
use super::*;

const OUTBOUNDS: [&str; 4] = ["DIRECT", "Proxy", "HK", "direct"];
const INBOUNDS: [&str; 2] = ["tun-in", "mixed-in"];

fn outbounds() -> Vec<String> {
    OUTBOUNDS.iter().map(|s| s.to_string()).collect()
}

fn inbounds() -> Vec<String> {
    INBOUNDS.iter().map(|s| s.to_string()).collect()
}

fn ob(name: &str) -> Action {
    Action::Outbound(OUTBOUNDS.iter().position(|o| *o == name).unwrap() as u32)
}

fn ip(s: &str) -> IpAddr {
    s.parse().unwrap()
}

#[derive(Default)]
struct FakeDns {
    answers: HashMap<String, Vec<IpAddr>>,
    prefetched: RefCell<Vec<String>>,
}

impl FakeDns {
    fn with(entries: &[(&str, &[&str])]) -> FakeDns {
        FakeDns {
            answers: entries.iter().map(|(d, ips)| (d.to_string(), ips.iter().map(|i| ip(i)).collect())).collect(),
            prefetched: RefCell::new(Vec::new()),
        }
    }
}

impl DnsView for FakeDns {
    fn lookup(&self, domain: &str, out: &mut [IpAddr; MAX_CACHED_ADDRS]) -> Option<usize> {
        let ips = self.answers.get(domain)?;
        let n = ips.len().min(MAX_CACHED_ADDRS);
        out[..n].copy_from_slice(&ips[..n]);
        Some(n)
    }

    fn prefetch(&self, domain: &str) {
        self.prefetched.borrow_mut().push(domain.to_string());
    }
}

fn clash(yaml: &str, opts: ClashOptions) -> RouterIr {
    import_clash::import(yaml, &|p: &ProviderRef| -> Result<Vec<u8>, String> { Err(format!("no file for {}", p.name)) }, &opts).unwrap()
}

fn router_with(ir: &RouterIr, geosite: Option<&GeoSiteDb>, geoip: Option<&GeoIpDat>, mmdb: Option<Arc<Mmdb>>) -> Result<Router, String> {
    let (o, i) = (outbounds(), inbounds());
    compile(ir, &Env { outbounds: &o, inbounds: &i, geosite, geoip, mmdb, default_final: None })
}

fn router(ir: &RouterIr) -> Router {
    router_with(ir, None, None, None).unwrap()
}

fn tcp<'a>(domain: Option<&'a str>, dst: Option<&str>, port: u16) -> Conn<'a> {
    Conn {
        network: Some(Network::Tcp),
        src: Some(ip("172.19.0.1")),
        src_port: 40000,
        dst: dst.map(ip),
        dst_port: port,
        domain,
        inbound: Some(0),
        ..Conn::default()
    }
}

fn udp<'a>(domain: Option<&'a str>, dst: Option<&str>, port: u16) -> Conn<'a> {
    Conn { network: Some(Network::Udp), ..tcp(domain, dst, port) }
}

fn decide(r: &Router, c: &Conn) -> (Action, Option<u32>) {
    let v = r.decide(c, &NoDns);
    (v.action, v.rule)
}

const MIXED: &str = r#"
rules:
  - DOMAIN-SUFFIX,google.com,Proxy
  - AND,((NETWORK,UDP),(DST-PORT,443)),REJECT
  - DOMAIN-KEYWORD,ads,REJECT
  - DOMAIN,exact.cn,HK
  - IP-CIDR,10.0.0.0/8,DIRECT,no-resolve
  - IP-CIDR6,2001:db8::/32,HK,no-resolve
  - SRC-IP-CIDR,192.168.50.0/24,DIRECT
  - DST-PORT,22,REJECT-DROP
  - SRC-PORT,5000-6000,HK
  - PROCESS-NAME,com.tencent.mm,DIRECT
  - UID,10100,HK
  - IN-NAME,mixed-in,Proxy
  - DOMAIN-REGEX,^tracker\d+\.,REJECT
  - DOMAIN-WILDCARD,*.wild.test,HK
  - IP-CIDR,1.1.1.0/24,Proxy
  - DOMAIN-SUFFIX,测试,HK
  - MATCH,DIRECT
"#;

#[test]
fn first_match_across_indexed_and_generic_rules() {
    let r = router(&clash(MIXED, ClashOptions::default()));
    let stats = r.stats();
    assert_eq!((stats.rules, stats.indexed, stats.generic), (17, 11, 6));
    assert_eq!(decide(&r, &tcp(Some("www.Google.com."), None, 443)), (ob("Proxy"), Some(0)));
    // Rule 0 comes before the generic QUIC block.
    assert_eq!(decide(&r, &udp(Some("www.google.com"), None, 443)), (ob("Proxy"), Some(0)));
    assert_eq!(decide(&r, &udp(Some("youtube.com"), None, 443)), (Action::Reject, Some(1)));
    assert_eq!(decide(&r, &tcp(Some("myads.net"), None, 443)), (Action::Reject, Some(2)));
    assert_eq!(decide(&r, &tcp(Some("exact.cn"), None, 443)), (ob("HK"), Some(3)));
    assert_eq!(decide(&r, &tcp(Some("sub.exact.cn"), None, 443)), (ob("DIRECT"), Some(16)));
    assert_eq!(decide(&r, &tcp(None, Some("10.1.2.3"), 443)), (ob("DIRECT"), Some(4)));
    assert_eq!(decide(&r, &tcp(None, Some("::ffff:10.1.2.3"), 443)), (ob("DIRECT"), Some(4)));
    assert_eq!(decide(&r, &tcp(None, Some("2001:db8::1"), 443)), (ob("HK"), Some(5)));
    let from_lan = Conn { src: Some(ip("192.168.50.7")), ..tcp(None, Some("8.8.8.8"), 443) };
    assert_eq!(decide(&r, &from_lan), (ob("DIRECT"), Some(6)));
    assert_eq!(decide(&r, &tcp(Some("host.example"), None, 22)), (Action::RejectDrop, Some(7)));
    assert_eq!(decide(&r, &Conn { src_port: 5500, ..tcp(None, Some("8.8.8.8"), 443) }), (ob("HK"), Some(8)));
    assert_eq!(decide(&r, &Conn { package: Some("com.tencent.mm"), ..tcp(None, Some("8.8.8.8"), 443) }), (ob("DIRECT"), Some(9)));
    assert_eq!(decide(&r, &Conn { process: Some("COM.TENCENT.MM"), ..tcp(None, Some("8.8.8.8"), 443) }), (ob("DIRECT"), Some(9)));
    assert_eq!(decide(&r, &Conn { uid: Some(10100), ..tcp(None, Some("8.8.8.8"), 443) }), (ob("HK"), Some(10)));
    assert_eq!(decide(&r, &Conn { inbound: Some(1), ..tcp(None, Some("8.8.8.8"), 443) }), (ob("Proxy"), Some(11)));
    assert_eq!(decide(&r, &tcp(Some("tracker12.example.com"), None, 443)), (Action::Reject, Some(12)));
    assert_eq!(decide(&r, &tcp(Some("a.wild.test"), None, 443)), (ob("HK"), Some(13)));
    assert_eq!(decide(&r, &tcp(None, Some("1.1.1.1"), 443)), (ob("Proxy"), Some(14)));
    assert_eq!(decide(&r, &tcp(Some("例子.测试"), None, 443)), (ob("HK"), Some(15)));
    assert_eq!(decide(&r, &tcp(Some("xn--fsqu00a.xn--0zwm56d"), None, 443)), (ob("HK"), Some(15)));
    assert_eq!(decide(&r, &tcp(Some("nothing.example"), Some("8.8.8.8"), 443)), (ob("DIRECT"), Some(16)));
    assert_eq!(decide(&r, &Conn::default()), (ob("DIRECT"), Some(16)));
}

#[test]
fn domain_only_ip_rules_read_the_dns_cache() {
    let r = router(&clash(MIXED, ClashOptions::default()));
    let dns = FakeDns::with(&[("one.one", &["1.1.1.1"]), ("mapped.test", &["::ffff:10.0.0.9"])]);
    let v = r.decide(&tcp(Some("one.one"), None, 443), &dns);
    assert_eq!((v.action, v.rule, v.dns_miss), (ob("Proxy"), Some(14), false));
    // no-resolve rules (10.0.0.0/8 here) never read the cache.
    let v = r.decide(&tcp(Some("mapped.test"), None, 443), &dns);
    assert_eq!((v.action, v.rule), (ob("DIRECT"), Some(16)));
    let v = r.decide(&tcp(Some("unknown.test"), None, 443), &dns);
    assert_eq!((v.action, v.rule, v.dns_miss), (ob("DIRECT"), Some(16), true));
    assert_eq!(*dns.prefetched.borrow(), ["unknown.test"]);
    // A domain matched before any resolving rule does not touch the cache.
    let v = r.decide(&tcp(Some("www.google.com"), None, 443), &dns);
    assert!(!v.dns_miss);
    assert_eq!(dns.prefetched.borrow().len(), 1);
}

#[test]
fn miss_policies_and_three_valued_logic() {
    let yaml = r#"
rules:
  - NOT,((IP-CIDR,1.1.1.0/24)),REJECT
  - MATCH,DIRECT
"#;
    let skip = router(&clash(yaml, ClashOptions { miss: MissPolicy::Skip }));
    let no_resolve = router(&clash(yaml, ClashOptions { miss: MissPolicy::NoResolve }));
    let conn = tcp(Some("x.test"), None, 443);
    let miss = FakeDns::default();
    let v = skip.decide(&conn, &miss);
    assert_eq!((v.action, v.rule, v.dns_miss), (ob("DIRECT"), Some(1), true));
    let v = no_resolve.decide(&conn, &miss);
    assert_eq!((v.action, v.rule, v.dns_miss), (Action::Reject, Some(0), true));
    let inside = FakeDns::with(&[("x.test", &["1.1.1.1"])]);
    let outside = FakeDns::with(&[("x.test", &["8.8.8.8"])]);
    assert_eq!(skip.decide(&conn, &inside).rule, Some(1));
    assert_eq!(skip.decide(&conn, &outside).rule, Some(0));
    // A cached empty answer is a definite "no address".
    let empty = FakeDns::with(&[("x.test", &[])]);
    let v = skip.decide(&conn, &empty);
    assert_eq!((v.rule, v.dns_miss), (Some(0), false));
    // With no-resolve the cache is never read.
    let explicit = router(&clash("rules:\n  - NOT,((IP-CIDR,1.1.1.0/24,no-resolve)),REJECT\n  - MATCH,DIRECT\n", ClashOptions::default()));
    let v = explicit.decide(&conn, &inside);
    assert_eq!((v.rule, v.dns_miss), (Some(0), false));

    let logic = r#"
rules:
  - AND,((DOMAIN-SUFFIX,a.test),(IP-CIDR,1.1.1.0/24)),HK
  - OR,((IP-CIDR,1.1.1.0/24),(DOMAIN,b.test)),Proxy
  - MATCH,DIRECT
"#;
    let r = router(&clash(logic, ClashOptions::default()));
    // AND(T, U) = U: skipped.
    assert_eq!(r.decide(&tcp(Some("x.a.test"), None, 443), &miss).rule, Some(2));
    assert_eq!(r.decide(&tcp(Some("x.a.test"), None, 443), &FakeDns::with(&[("x.a.test", &["1.1.1.2"])])).rule, Some(0));
    // OR(U, T) = T.
    assert_eq!(r.decide(&tcp(Some("b.test"), None, 443), &miss).rule, Some(1));
}

#[test]
fn sub_rules_pass_and_cycles() {
    let yaml = r#"
rules:
  - SUB-RULE,(NETWORK,TCP),web
  - MATCH,DIRECT
sub-rules:
  web:
    - DOMAIN,pass.test,PASS
    - DOMAIN,pass.test,HK
    - DST-PORT,443,Proxy
    - SUB-RULE,(DOMAIN-SUFFIX,hk.test),hk
  hk:
    - MATCH,HK
"#;
    let r = router(&clash(yaml, ClashOptions::default()));
    assert_eq!(r.stats().sub_programs, 2);
    assert_eq!(decide(&r, &tcp(Some("x.test"), None, 443)), (ob("Proxy"), Some(0)));
    assert_eq!(decide(&r, &tcp(Some("a.hk.test"), None, 80)), (ob("HK"), Some(0)));
    assert_eq!(decide(&r, &tcp(Some("x.test"), None, 80)), (ob("DIRECT"), Some(1)));
    assert_eq!(decide(&r, &udp(Some("a.hk.test"), None, 443)), (ob("DIRECT"), Some(1)));
    assert_eq!(decide(&r, &tcp(Some("pass.test"), None, 80)), (ob("HK"), Some(0)));
    let cyclic =
        "rules:\n  - SUB-RULE,(NETWORK,TCP),a\nsub-rules:\n  a:\n    - SUB-RULE,(NETWORK,TCP),b\n  b:\n    - SUB-RULE,(NETWORK,UDP),a\n";
    let err = router_with(&clash(cyclic, ClashOptions::default()), None, None, None).err().unwrap();
    assert!(err.contains("refers back"), "{err}");
}

fn geo() -> (GeoSiteDb, GeoIpDat, Arc<Mmdb>) {
    let site = geodata::geosite(&[
        ("CN", &[(2, "cn", &[]), (3, "www.baidu.com", &[]), (0, "taobao", &[]), (2, "ads.cn-example.com", &["ads"])]),
        ("GOOGLE", &[(2, "google.com", &[]), (1, r"^www\.google\.[a-z]+$", &[])]),
    ]);
    let ipdat = geodata::geoip(&[("CN", &["1.0.1.0/24", "2400:3200::/32"]), ("PRIVATE", &["10.0.0.0/8"])]);
    let compat = |a: [u8; 4]| u128::from(u32::from_be_bytes(a));
    let mmdb = mmdbdata::build(
        "Meta-geoip0",
        &[
            (compat([114, 114, 0, 0]), 112, mmdbdata::Data::Code("cn")),
            (compat([1, 1, 1, 0]), 120, mmdbdata::Data::Codes(&["au", "cloudflare"])),
        ],
    );
    (
        GeoSiteDb::parse(Arc::from(site)).unwrap(),
        GeoIpDat::parse(Arc::from(ipdat)).unwrap(),
        Arc::new(Mmdb::parse(Arc::from(mmdb)).unwrap()),
    )
}

#[test]
fn geosite_and_geoip_from_the_installed_formats() {
    let (site, ipdat, mmdb) = geo();
    let yaml = r#"
rules:
  - GEOSITE,cn@ads,REJECT
  - GEOSITE,google,Proxy
  - GEOSITE,cn,DIRECT
  - GEOIP,CN,DIRECT
  - GEOIP,LAN,DIRECT,no-resolve
  - GEOIP,cloudflare,HK,no-resolve
  - MATCH,Proxy
"#;
    let ir = clash(yaml, ClashOptions::default());
    // geoip.dat: GEOIP rules are indexed; the cloudflare code only the mmdb has.
    let r = router_with(&ir, Some(&site), Some(&ipdat), Some(mmdb.clone())).unwrap();
    assert_eq!(r.stats().generic, 1);
    assert_eq!(decide(&r, &tcp(Some("x.ads.cn-example.com"), None, 443)), (Action::Reject, Some(0)));
    assert_eq!(decide(&r, &tcp(Some("mail.google.com"), None, 443)), (ob("Proxy"), Some(1)));
    assert_eq!(decide(&r, &tcp(Some("www.google.co"), None, 443)), (ob("Proxy"), Some(1)));
    assert_eq!(decide(&r, &tcp(Some("www.taobao.example"), None, 443)), (ob("DIRECT"), Some(2)));
    assert_eq!(decide(&r, &tcp(Some("www.baidu.com"), None, 443)), (ob("DIRECT"), Some(2)));
    assert_eq!(decide(&r, &tcp(Some("x.gov.cn"), None, 443)), (ob("DIRECT"), Some(2)));
    assert_eq!(decide(&r, &tcp(None, Some("1.0.1.9"), 443)), (ob("DIRECT"), Some(3)));
    assert_eq!(decide(&r, &tcp(None, Some("2400:3200::53"), 443)), (ob("DIRECT"), Some(3)));
    assert_eq!(decide(&r, &tcp(None, Some("10.9.9.9"), 443)), (ob("DIRECT"), Some(4)));
    assert_eq!(decide(&r, &tcp(None, Some("1.1.1.1"), 443)), (ob("HK"), Some(5)));
    assert_eq!(decide(&r, &tcp(None, Some("8.8.8.8"), 443)), (ob("Proxy"), Some(6)));
    // mmdb only: GEOIP is evaluated through the database.
    let r = router_with(&ir, Some(&site), None, Some(mmdb.clone())).unwrap();
    assert_eq!(r.stats().generic, 2);
    assert_eq!(decide(&r, &tcp(None, Some("114.114.114.114"), 443)), (ob("DIRECT"), Some(3)));
    assert_eq!(decide(&r, &tcp(None, Some("::ffff:114.114.1.1"), 443)), (ob("DIRECT"), Some(3)));
    assert_eq!(decide(&r, &tcp(None, Some("1.1.1.1"), 443)), (ob("HK"), Some(5)));
    let dns = FakeDns::with(&[("cn.test", &["114.114.114.114"])]);
    assert_eq!(r.decide(&tcp(Some("cn.test"), None, 443), &dns).rule, Some(3));
    // No data: a clear error, not a silent non-match.
    let err = router_with(&ir, None, None, None).err().unwrap();
    assert!(err.contains("no geosite data"), "{err}");
    let only_ip = clash("rules:\n  - GEOIP,US,DIRECT\n", ClashOptions::default());
    assert!(router_with(&only_ip, None, Some(&ipdat), None).err().unwrap().contains("no geoip data has this code"));
    // Without data, private/lan fall back to the built-in ranges.
    let lan = clash("rules:\n  - GEOIP,private,DIRECT,no-resolve\n  - MATCH,Proxy\n", ClashOptions::default());
    let r = router_with(&lan, None, None, None).unwrap();
    assert_eq!(decide(&r, &tcp(None, Some("192.168.1.1"), 443)), (ob("DIRECT"), Some(0)));
}

#[test]
fn clash_rule_providers_in_every_format() {
    let yaml = r#"
rules:
  - RULE-SET,ads,REJECT
  - RULE-SET,cnip,DIRECT
  - RULE-SET,apps,HK
  - RULE-SET,lan,DIRECT,src
  - MATCH,Proxy
rule-providers:
  ads: {type: http, behavior: domain, format: mrs, url: "https://e.test/ads.mrs"}
  cnip: {type: http, behavior: ipcidr, format: mrs, url: "https://e.test/cn.mrs"}
  apps: {type: file, behavior: classical, path: ./apps.txt, format: text}
  lan: {type: inline, behavior: classical, payload: ["IP-CIDR,192.168.0.0/16", "DST-PORT,5353"]}
"#;
    let loader = |p: &ProviderRef| -> Result<Vec<u8>, String> {
        Ok(match p.name.as_str() {
            "ads" => rsdata::mrs_domain(&["+.doubleclick.net", ".ads.test", "exact.ad", "*.track.test"]),
            "cnip" => rsdata::mrs_ipcidr(&[("1.0.1.0", "1.0.1.255")]),
            "apps" => b"PROCESS-NAME,com.example.hk\nAND,((NETWORK,UDP),(DST-PORT,3478))\n".to_vec(),
            other => return Err(format!("unexpected {other}")),
        })
    };
    let ir = import_clash::import(yaml, &loader, &ClashOptions::default()).unwrap();
    let r = router(&ir);
    assert_eq!(decide(&r, &tcp(Some("doubleclick.net"), None, 443)), (Action::Reject, Some(0)));
    assert_eq!(decide(&r, &tcp(Some("x.doubleclick.net"), None, 443)), (Action::Reject, Some(0)));
    assert_eq!(decide(&r, &tcp(Some("ads.test"), None, 443)).1, Some(4));
    assert_eq!(decide(&r, &tcp(Some("a.ads.test"), None, 443)), (Action::Reject, Some(0)));
    assert_eq!(decide(&r, &tcp(Some("a.track.test"), None, 443)), (Action::Reject, Some(0)));
    assert_eq!(decide(&r, &tcp(Some("a.b.track.test"), None, 443)).1, Some(4));
    assert_eq!(decide(&r, &tcp(None, Some("1.0.1.1"), 443)), (ob("DIRECT"), Some(1)));
    // An ipcidr provider without no-resolve reads the DNS cache.
    let dns = FakeDns::with(&[("cn.test", &["1.0.1.7"])]);
    assert_eq!(r.decide(&tcp(Some("cn.test"), None, 443), &dns).rule, Some(1));
    assert_eq!(decide(&r, &Conn { package: Some("com.example.hk"), ..tcp(None, Some("8.8.8.8"), 443) }), (ob("HK"), Some(2)));
    assert_eq!(decide(&r, &udp(None, Some("8.8.8.8"), 3478)), (ob("HK"), Some(2)));
    // `src`: the set's IP conditions read the source, its ports the source port.
    let lan = Conn { src: Some(ip("192.168.3.4")), ..tcp(None, Some("8.8.8.8"), 443) };
    assert_eq!(decide(&r, &lan), (ob("DIRECT"), Some(3)));
    assert_eq!(decide(&r, &tcp(None, Some("192.168.3.4"), 443)).1, Some(4));
    assert_eq!(decide(&r, &Conn { src_port: 5353, ..tcp(None, Some("8.8.8.8"), 443) }).1, Some(3));
    assert_eq!(decide(&r, &tcp(None, Some("8.8.8.8"), 5353)).1, Some(4));
}

#[test]
fn sing_box_route_with_binary_rule_sets() {
    let ads = rsdata::srs(&[(&[rsdata::Item::Domains(&["ad.example"], &["doubleclick.net", ".tracker.test"])], false)]);
    let cnip = rsdata::srs(&[(&[rsdata::Item::Ips(6, &[("1.0.1.0", "1.0.1.255")])], false)]);
    let config = json!({
        "outbounds": [{"type": "direct", "tag": "direct"}],
        "route": {
            "rules": [
                {"action": "sniff"},
                {"protocol": "dns", "action": "hijack-dns"},
                {"rule_set": "ads", "action": "reject"},
                {"rule_set": "cnip", "rule_set_ip_cidr_match_source": true, "outbound": "HK"},
                {"rule_set": "cnip", "outbound": "direct"},
                {"type": "logical", "mode": "and", "rules": [{"network": "udp"}, {"port": 443}], "action": "reject", "method": "drop"},
                {"ip_is_private": true, "outbound": "direct"},
                {"inbound": "mixed-in", "domain_suffix": "example.com", "outbound": "HK"}
            ],
            "rule_set": [
                {"type": "remote", "tag": "ads", "url": "https://e.test/ads.srs"},
                {"type": "local", "tag": "cnip", "path": "/data/cnip.srs"}
            ],
            "final": "Proxy"
        }
    });
    let loader = |s: &RuleSetRef| -> Result<Vec<u8>, String> {
        Ok(match s.tag.as_str() {
            "ads" => ads.clone(),
            "cnip" => cnip.clone(),
            other => return Err(format!("unexpected {other}")),
        })
    };
    let out = import_singbox::import_value(&config, &loader).unwrap();
    assert_eq!(out.host.len(), 1);
    let r = router(&out.router);
    let dns = Conn { protocol: Some("dns"), ..udp(None, Some("8.8.8.8"), 53) };
    assert_eq!(decide(&r, &dns), (Action::HijackDns, Some(0)));
    assert_eq!(decide(&r, &tcp(Some("ad.example"), None, 443)), (Action::Reject, Some(1)));
    assert_eq!(decide(&r, &tcp(Some("sub.ad.example"), None, 443)).1, None);
    assert_eq!(decide(&r, &tcp(Some("x.doubleclick.net"), None, 443)), (Action::Reject, Some(1)));
    assert_eq!(decide(&r, &tcp(Some("tracker.test"), None, 443)).1, None);
    assert_eq!(decide(&r, &tcp(Some("a.tracker.test"), None, 443)), (Action::Reject, Some(1)));
    let from_cn = Conn { src: Some(ip("1.0.1.5")), ..tcp(None, Some("8.8.8.8"), 443) };
    assert_eq!(decide(&r, &from_cn), (ob("HK"), Some(2)));
    assert_eq!(decide(&r, &tcp(None, Some("1.0.1.5"), 443)), (ob("direct"), Some(3)));
    // sing-box IP items do not resolve: a domain-only connection never matches them.
    let cache = FakeDns::with(&[("cn.test", &["1.0.1.5"])]);
    let v = r.decide(&tcp(Some("cn.test"), None, 443), &cache);
    assert_eq!((v.rule, v.dns_miss), (None, false));
    assert_eq!(decide(&r, &udp(Some("quic.test"), None, 443)), (Action::RejectDrop, Some(4)));
    assert_eq!(decide(&r, &tcp(None, Some("192.168.1.1"), 443)), (ob("direct"), Some(5)));
    assert_eq!(decide(&r, &Conn { inbound: Some(1), ..tcp(Some("a.example.com"), None, 443) }), (ob("HK"), Some(6)));
    assert_eq!(decide(&r, &tcp(Some("a.example.com"), None, 443)), (ob("Proxy"), None));
}

#[test]
fn compile_errors_name_the_rule() {
    let ir = clash("rules:\n  - DOMAIN,a.com,Nowhere\n", ClashOptions::default());
    let err = router_with(&ir, None, None, None).err().unwrap();
    assert!(err.contains("rule 1 (DOMAIN,a.com,Nowhere)") && err.contains("unknown outbound"), "{err}");
    let ir = clash("rules:\n  - IN-NAME,nope,DIRECT\n", ClashOptions::default());
    assert!(router_with(&ir, None, None, None).err().unwrap().contains("unknown inbound"));
    let no_final = RouterIr::default();
    assert!(router_with(&no_final, None, None, None).err().unwrap().contains("no final target"));
    let pass_final = RouterIr { final_target: Some(Target::Pass), ..RouterIr::default() };
    assert!(router_with(&pass_final, None, None, None).err().unwrap().contains("final"));
    let nested = RouterIr {
        rules: vec![Rule {
            cond: Cond::RuleSet { name: "a".into(), src_ip: false, swap_ports: false, no_resolve: false },
            target: Target::Reject,
            source: "x".into(),
        }],
        rule_sets: [(
            "a".to_string(),
            RuleSet {
                behavior: Behavior::Classical,
                rules: vec![Cond::RuleSet { name: "a".into(), src_ip: false, swap_ports: false, no_resolve: false }],
            },
        )]
        .into_iter()
        .collect(),
        final_target: Some(Target::Reject),
        ..RouterIr::default()
    };
    assert!(router_with(&nested, None, None, None).err().unwrap().contains("inside a rule set"));
}

/// The real geo files of the pinned meta-rules-dat commit, when CI fetched
/// them (`FKBOX_GEO_DIR`, see .github/workflows/brain.yml); skipped otherwise.
#[test]
fn real_geo_files_when_available() {
    let Ok(dir) = std::env::var("FKBOX_GEO_DIR") else {
        eprintln!("FKBOX_GEO_DIR not set: real geo data check skipped");
        return;
    };
    let read = |name: &str| -> Arc<[u8]> { Arc::from(std::fs::read(std::path::Path::new(&dir).join(name)).unwrap()) };
    let site = GeoSiteDb::parse(read("geosite.dat")).unwrap();
    let ipdat = GeoIpDat::parse(read("geoip.dat")).unwrap();
    let mmdb = Arc::new(Mmdb::parse(read("geoip.metadb")).unwrap());
    assert!(site.categories() > 1000, "{}", site.categories());
    assert!(site.domains("cn").unwrap().len() > 50_000);
    assert_eq!(mmdb.database_type, "Meta-geoip0");
    assert_eq!(mmdb.codes(ip("114.114.114.114")), ["cn"]);
    assert!(mmdb.has_code(ip("1.1.1.1"), "cloudflare"));
    assert!(mmdb.has_code(ip("192.168.1.1"), "private"));
    assert!(ipdat.nets("cn").unwrap().len() > 1000);
    assert!(!site.domains("category-ads-all").unwrap().is_empty());
    let yaml = "rules:\n  - GEOSITE,google,Proxy\n  - GEOSITE,cn,DIRECT\n  - GEOIP,CN,DIRECT\n  - MATCH,Proxy\n";
    let ir = clash(yaml, ClashOptions::default());
    for (label, r) in [
        ("geoip.dat", router_with(&ir, Some(&site), Some(&ipdat), None).unwrap()),
        ("geoip.metadb", router_with(&ir, Some(&site), None, Some(mmdb.clone())).unwrap()),
    ] {
        assert_eq!(decide(&r, &tcp(Some("www.google.com"), None, 443)).0, ob("Proxy"), "{label}");
        assert_eq!(decide(&r, &tcp(Some("www.baidu.com"), None, 443)).0, ob("DIRECT"), "{label}");
        assert_eq!(decide(&r, &tcp(None, Some("223.5.5.5"), 443)).0, ob("DIRECT"), "{label}");
        assert_eq!(decide(&r, &tcp(None, Some("8.8.8.8"), 443)).0, ob("Proxy"), "{label}");
        eprintln!("{label}: {:?}", r.stats());
    }
}
