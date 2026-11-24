//! Groups against a fake probe: url-test switching and tolerance, fallback
//! on failure, lazy tests, load-balance distribution, the concurrency
//! limit, persistence, nested groups, and atomic snapshot updates under
//! concurrent decides (with the DNS route following).

use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use fkbox_client_core::config::brain::{import, Loaders};
use fkbox_client_core::rules::engine::import_clash::{self, ClashOptions, ProviderRef};
use fkbox_client_core::rules::engine::{compile, Action, Conn, Env, Router, Target};

use super::*;
use crate::brain::{Brain, Routing};
use crate::dns::Route;

const YAML: &str = r#"
proxies:
  - {name: a, type: ss, server: 127.0.0.1, port: 1001, cipher: aes-128-gcm, password: pw}
  - {name: b, type: ss, server: 127.0.0.1, port: 1002, cipher: aes-128-gcm, password: pw}
  - {name: c, type: ss, server: 127.0.0.1, port: 1003, cipher: aes-128-gcm, password: pw}
proxy-groups:
  - {name: Auto, type: url-test, proxies: [a, b, c], tolerance: 30, interval: 60, lazy: false}
  - {name: Fall, type: fallback, proxies: [a, b, c], interval: 60, lazy: false}
  - {name: LB, type: load-balance, proxies: [a, b, c], strategy: consistent-hashing}
  - {name: RR, type: load-balance, proxies: [a, b, c], strategy: round-robin}
  - {name: Sticky, type: load-balance, proxies: [a, b, c], strategy: sticky-sessions}
  - {name: Pick, type: select, proxies: [Auto, b, DIRECT, REJECT]}
  - {name: Lazy, type: url-test, proxies: [b, c], interval: 60}
"#;

const RULES: &str = "rules:\n  - DOMAIN-SUFFIX,auto.test,Auto\n  - DOMAIN-SUFFIX,lb.test,LB\n  - MATCH,Pick\n";

fn outbounds() -> Vec<String> {
    ["a", "b", "c", "fk-direct", "fk-block"].iter().map(|s| s.to_string()).collect()
}

fn group_set() -> Arc<GroupSet> {
    let result = import(YAML, &Loaders::none()).unwrap();
    Arc::new(GroupSet::build(&result.groups, &outbounds(), "fk-direct").unwrap())
}

fn router(set: &GroupSet) -> Router {
    let mut ir =
        import_clash::import(RULES, &|_: &ProviderRef| -> Result<Vec<u8>, String> { Err("no files".into()) }, &ClashOptions::default())
            .unwrap();
    // The Clash import's own final target is DIRECT; profile.rs maps it to fk-direct.
    ir.final_target = Some(Target::Outbound("fk-direct".into()));
    let targets = set.target_names();
    let inbounds = vec!["fk-tun".to_string()];
    compile(&ir, &Env { outbounds: &targets, inbounds: &inbounds, geosite: None, geoip: None, mmdb: None, default_final: None }).unwrap()
}

#[derive(Default)]
struct FakeProbe {
    /// Delay per tag; absent or `None`: the probe fails.
    delays: Mutex<HashMap<String, Option<u32>>>,
    calls: AtomicUsize,
    in_flight: AtomicUsize,
    max_in_flight: AtomicUsize,
    sleep: Duration,
}

impl FakeProbe {
    fn with(delays: &[(&str, Option<u32>)]) -> FakeProbe {
        let probe = FakeProbe::default();
        probe.set(delays);
        probe
    }

    fn set(&self, delays: &[(&str, Option<u32>)]) {
        let mut map = self.delays.lock().unwrap();
        for (tag, delay) in delays {
            map.insert(tag.to_string(), *delay);
        }
    }
}

impl Prober for FakeProbe {
    fn probe(&self, tag: &str, _url: &str, _timeout_ms: u32) -> Result<FkbProbeResult, HostError> {
        self.calls.fetch_add(1, Ordering::SeqCst);
        let n = self.in_flight.fetch_add(1, Ordering::SeqCst) + 1;
        self.max_in_flight.fetch_max(n, Ordering::SeqCst);
        if !self.sleep.is_zero() {
            std::thread::sleep(self.sleep);
        }
        self.in_flight.fetch_sub(1, Ordering::SeqCst);
        match self.delays.lock().unwrap().get(tag).copied().flatten() {
            Some(d) => Ok(FkbProbeResult { http_status: 204, latency_ms: d, ..FkbProbeResult::default() }),
            None => Err(HostError { code: FKB_E_TIMEOUT, message: "timeout".into() }),
        }
    }
}

fn round(groups: &Groups, probe: &FakeProbe, force: Force, now: Instant) -> usize {
    let (covered, jobs) = groups.due(now, force);
    let results = probe_all(probe, &jobs, PROBE_CONCURRENCY);
    groups.record(&covered, &jobs, results, now);
    jobs.len()
}

fn index(set: &GroupSet, name: &str) -> u32 {
    set.group(name).unwrap()
}

fn virtual_of(set: &GroupSet, name: &str) -> Action {
    Action::Outbound(set.outbounds.len() as u32 + index(set, name))
}

fn conn(domain: &'static str) -> Conn<'static> {
    Conn { domain: Some(domain), ..Conn::default() }
}

#[test]
fn url_test_takes_the_fastest_within_tolerance() {
    let set = group_set();
    let groups = Groups::default();
    groups.install(set.clone());
    assert_eq!(groups.now("Auto").as_deref(), Some("a"), "untested: the first member");
    let probe = FakeProbe::with(&[("a", Some(200)), ("b", Some(100)), ("c", Some(300))]);
    let t0 = Instant::now();
    assert!(round(&groups, &probe, Force::Due, t0) > 0);
    assert_eq!(groups.now("Auto").as_deref(), Some("b"));
    let table = groups.table();
    assert_eq!(table.resolve(virtual_of(&set, "Auto"), &conn("x.auto.test")), Action::Outbound(1));
    // 100 ms is within 80 + 30: stay.
    probe.set(&[("a", Some(80))]);
    round(&groups, &probe, Force::One(index(&set, "Auto")), t0);
    assert_eq!(groups.now("Auto").as_deref(), Some("b"), "tolerance keeps the current member");
    // 100 ms is more than 50 + 30: switch.
    probe.set(&[("a", Some(50))]);
    round(&groups, &probe, Force::One(index(&set, "Auto")), t0);
    assert_eq!(groups.now("Auto").as_deref(), Some("a"));
    // The current member dies: the fastest alive one.
    probe.set(&[("a", None)]);
    round(&groups, &probe, Force::One(index(&set, "Auto")), t0);
    assert_eq!(groups.now("Auto").as_deref(), Some("b"));
    // The old table still answers with its own choice (snapshots are immutable).
    assert_eq!(table.resolve(virtual_of(&set, "Auto"), &conn("x.auto.test")), Action::Outbound(1));
}

#[test]
fn fallback_takes_the_first_alive_member() {
    let set = group_set();
    let groups = Groups::default();
    groups.install(set.clone());
    let probe = FakeProbe::with(&[("a", None), ("b", Some(400)), ("c", Some(10))]);
    let fall = Force::One(index(&set, "Fall"));
    round(&groups, &probe, fall, Instant::now());
    assert_eq!(groups.now("Fall").as_deref(), Some("b"), "order, not speed");
    probe.set(&[("a", Some(900))]);
    round(&groups, &probe, fall, Instant::now());
    assert_eq!(groups.now("Fall").as_deref(), Some("a"), "back to the first once it recovers");
    probe.set(&[("a", None), ("b", None), ("c", None)]);
    round(&groups, &probe, fall, Instant::now());
    assert_eq!(groups.now("Fall").as_deref(), Some("a"), "all dead: the first member");
}

#[test]
fn lazy_groups_are_tested_only_when_used() {
    let set = group_set();
    let groups = Groups::default();
    groups.install(set.clone());
    let probe = FakeProbe::with(&[("a", Some(10)), ("b", Some(20)), ("c", Some(30))]);
    let t0 = Instant::now();
    let (first, _) = groups.due(t0, Force::Due);
    let names: Vec<&str> = first.iter().map(|g| set.specs[*g as usize].name.as_str()).collect();
    // Untested automatic groups are due at once; select groups never on their own.
    assert_eq!(names, ["Auto", "Fall", "LB", "RR", "Sticky", "Lazy"]);
    round(&groups, &probe, Force::Due, t0);
    assert!(groups.due(t0 + Duration::from_secs(30), Force::Due).0.is_empty(), "interval not elapsed");
    let later = t0 + Duration::from_secs(61);
    let (due, _) = groups.due(later, Force::Due);
    assert!(due.contains(&index(&set, "Auto")) && due.contains(&index(&set, "Fall")), "lazy: false");
    assert!(!due.contains(&index(&set, "Lazy")), "lazy and unused");
    // A decision through the group marks it used.
    let rule = Action::Outbound(set.outbounds.len() as u32 + index(&set, "Lazy"));
    assert_eq!(groups.table().resolve(rule, &conn("x.test")), Action::Outbound(1));
    let (due, jobs) = groups.due(later, Force::Due);
    assert!(due.contains(&index(&set, "Lazy")));
    assert!(jobs.iter().any(|j| j.tag == "b") && jobs.iter().any(|j| j.tag == "c"));
}

#[test]
fn load_balance_spreads_and_keeps_destinations() {
    let set = group_set();
    let groups = Groups::default();
    groups.install(set.clone());
    let table = groups.table();
    let lb = virtual_of(&set, "LB");
    let names: Vec<String> = (0..3000).map(|i| format!("site{i}.test")).collect();
    let pick = |table: &GroupTable, name: &str| -> u32 {
        let c = Conn { domain: Some(name), ..Conn::default() };
        match table.resolve(lb, &c) {
            Action::Outbound(i) => i,
            other => panic!("{other:?}"),
        }
    };
    let before: Vec<u32> = names.iter().map(|n| pick(&table, n)).collect();
    let mut counts = [0usize; 3];
    for i in &before {
        counts[*i as usize] += 1;
    }
    for n in counts {
        assert!((700..=1300).contains(&n), "consistent hashing spread {counts:?}");
    }
    // One site, one member: subdomains and repeats.
    assert_eq!(pick(&table, "a.site7.test"), pick(&table, "b.site7.test"));
    assert_eq!(pick(&table, "site7.test"), before[7]);
    // b dies: nothing goes to b, and what was not on b stays where it was.
    let probe = FakeProbe::with(&[("a", Some(10)), ("b", None), ("c", Some(10))]);
    round(&groups, &probe, Force::One(index(&set, "LB")), Instant::now());
    let after = groups.table();
    let mut moved = 0;
    for (name, old) in names.iter().zip(&before) {
        let new = pick(&after, name);
        assert_ne!(new, 1);
        if *old != 1 {
            assert_eq!(new, *old, "{name} moved off an alive member");
        } else {
            moved += 1;
        }
    }
    assert!(moved > 0);
    // Round-robin: even, skipping the dead member.
    let rr = virtual_of(&set, "RR");
    let mut counts = [0usize; 3];
    for _ in 0..300 {
        let Action::Outbound(i) = groups.table().resolve(rr, &conn("x.test")) else { panic!() };
        counts[i as usize] += 1;
    }
    // The dead member's turn goes to the next alive one (as in mihomo).
    assert_eq!(counts, [100, 0, 200], "round-robin");
    // Sticky: one client keeps one member per site; clients spread.
    let sticky = virtual_of(&set, "Sticky");
    let table = groups.table();
    let from = |src: &str| Conn { domain: Some("video.example"), src: Some(src.parse().unwrap()), ..Conn::default() };
    assert_eq!(table.resolve(sticky, &from("10.0.0.1")), table.resolve(sticky, &from("10.0.0.1")));
    let mut spread: Vec<u32> = (1..64)
        .map(|i| match table.resolve(sticky, &from(&format!("10.0.0.{i}"))) {
            Action::Outbound(o) => o,
            other => panic!("{other:?}"),
        })
        .collect();
    spread.sort_unstable();
    spread.dedup();
    assert_eq!(spread, [0, 2], "clients spread over the alive members");
}

#[test]
fn probes_are_limited_in_flight() {
    let probe = FakeProbe { sleep: Duration::from_millis(20), ..FakeProbe::default() };
    let jobs: Vec<TestJob> =
        (0..24).map(|i| TestJob { tag: format!("n{i}"), url: DEFAULT_URL.into(), timeout_ms: 1000, group: 0 }).collect();
    probe.set(&jobs.iter().map(|j| (j.tag.as_str(), Some(5))).collect::<Vec<_>>());
    let results = probe_all(&probe, &jobs, 4);
    assert_eq!(results.len(), 24);
    assert!(results.iter().all(|r| r.as_ref().is_ok_and(|r| r.latency_ms == 5)));
    assert_eq!(probe.calls.load(Ordering::SeqCst), 24);
    let max = probe.max_in_flight.load(Ordering::SeqCst);
    assert!((2..=4).contains(&max), "max in flight {max}");
    // A round over every group probes each outbound once per URL.
    let groups = Groups::default();
    groups.install(group_set());
    let (_, jobs) = groups.due(Instant::now(), Force::All);
    let mut tags: Vec<&str> = jobs.iter().map(|j| j.tag.as_str()).collect();
    tags.sort_unstable();
    assert_eq!(tags, ["a", "b", "c", "fk-direct"]);
}

#[test]
fn selections_persist_and_automatic_groups_refuse() {
    let path = std::env::temp_dir().join(format!("fkb-groups-{}.json", std::process::id()));
    let _ = std::fs::remove_file(&path);
    let set = group_set();
    let groups = Groups::default();
    groups.set_store(Some(path.clone()));
    groups.install(set.clone());
    assert_eq!(groups.now("Pick").as_deref(), Some("Auto"));
    assert_eq!(groups.select("Pick", "b"), Ok(true));
    assert_eq!(groups.select("Pick", "b"), Ok(false));
    assert_eq!(groups.select("Auto", "a").unwrap_err().0, FKB_E_INVALID, "url-test is not selectable");
    assert_eq!(groups.select("LB", "a").unwrap_err().0, FKB_E_INVALID);
    assert_eq!(groups.select("Fall", "a").unwrap_err().0, FKB_E_INVALID);
    assert_eq!(groups.select("Pick", "zz").unwrap_err().0, FKB_E_NOT_FOUND);
    assert_eq!(groups.select("Nope", "b").unwrap_err().0, FKB_E_NOT_FOUND);
    assert!(std::fs::read_to_string(&path).unwrap().contains("\"Pick\":\"b\""));
    // A new process (a new Groups) starts on the stored member.
    let restarted = Groups::default();
    restarted.set_store(Some(path.clone()));
    restarted.install(set.clone());
    assert_eq!(restarted.now("Pick").as_deref(), Some("b"));
    // Memory only: a reload keeps the selection, a fresh state does not.
    let memory = Groups::default();
    memory.install(set.clone());
    memory.select("Pick", "DIRECT").unwrap();
    memory.install(set.clone());
    assert_eq!(memory.now("Pick").as_deref(), Some("DIRECT"));
    let _ = std::fs::remove_file(&path);
    // The view tells the UI what it may select.
    let view = memory.view(Instant::now());
    let pick = view["groups"].as_array().unwrap().iter().find(|g| g["name"] == "Pick").unwrap();
    assert_eq!((pick["selectable"].as_bool(), pick["now"].as_str()), (Some(true), Some("DIRECT")));
    assert_eq!(pick["members"][0]["kind"], "group");
    assert_eq!(pick["members"][2]["kind"], "builtin");
    let auto = view["groups"].as_array().unwrap().iter().find(|g| g["name"] == "Auto").unwrap();
    assert_eq!(auto["selectable"], false);
    assert_eq!(auto["members"][0]["alive"], serde_json::Value::Null, "untested");
}

#[test]
fn nested_groups_and_builtin_members() {
    let set = group_set();
    let groups = Groups::default();
    groups.install(set.clone());
    let probe = FakeProbe::with(&[("a", Some(300)), ("b", Some(200)), ("c", Some(100))]);
    round(&groups, &probe, Force::One(index(&set, "Auto")), Instant::now());
    let pick = virtual_of(&set, "Pick");
    // Pick -> Auto -> c.
    assert_eq!(groups.table().resolve(pick, &conn("x.test")), Action::Outbound(2));
    groups.select("Pick", "REJECT").unwrap();
    assert_eq!(groups.table().resolve(pick, &conn("x.test")), Action::Reject);
    groups.select("Pick", "DIRECT").unwrap();
    assert_eq!(groups.table().resolve(pick, &conn("x.test")), Action::Outbound(3));
    // A real index and non-route actions pass through.
    assert_eq!(groups.table().resolve(Action::Outbound(0), &conn("x.test")), Action::Outbound(0));
    assert_eq!(groups.table().resolve(Action::HijackDns, &conn("x.test")), Action::HijackDns);
    // Testing an automatic group that holds a select group measures the select group's member.
    let nested = r#"
proxies:
  - {name: a, type: ss, server: 127.0.0.1, port: 1001, cipher: aes-128-gcm, password: pw}
  - {name: b, type: ss, server: 127.0.0.1, port: 1002, cipher: aes-128-gcm, password: pw}
proxy-groups:
  - {name: Inner, type: select, proxies: [b, a]}
  - {name: Outer, type: fallback, proxies: [Inner, a]}
"#;
    let result = import(nested, &Loaders::none()).unwrap();
    let set = Arc::new(GroupSet::build(&result.groups, &outbounds(), "fk-direct").unwrap());
    let groups = Groups::default();
    groups.install(set.clone());
    let (_, jobs) = groups.due(Instant::now(), Force::One(index(&set, "Outer")));
    assert_eq!(jobs.iter().map(|j| j.tag.as_str()).collect::<Vec<_>>(), ["b", "a"]);
    let probe = FakeProbe::with(&[("a", Some(50)), ("b", None)]);
    round(&groups, &probe, Force::One(index(&set, "Outer")), Instant::now());
    assert_eq!(groups.now("Outer").as_deref(), Some("a"), "Inner's member b is dead");
    // Loops and unknown members are refused when the plan is made.
    let mut looped = result.groups.clone();
    looped[0].members.push("Outer".into());
    assert!(GroupSet::build(&looped, &outbounds(), "fk-direct").unwrap_err().contains("loop"));
    let mut unknown = result.groups.clone();
    unknown[0].members.push("ghost".into());
    assert!(GroupSet::build(&unknown, &outbounds(), "fk-direct").unwrap_err().contains("ghost"));
    assert_eq!(parse_expected(Some("200-299/204")).unwrap(), vec![(200, 299), (204, 204)]);
    assert!(parse_expected(Some("abc")).is_err());
}

fn meta(domain: &str) -> FkbConnMeta {
    let mut m = FkbConnMeta::new();
    m.network = FKB_NET_TCP;
    m.dst_port = 443;
    m.inbound_index = 0;
    m.set_domain(domain, FKB_DOMAIN_SNIFFED);
    m
}

/// Readers never see a torn update: every decision names a member the
/// group had, the generation never moves (no restart), and once select
/// returns the next decision and the next DNS route use the new member.
#[test]
fn group_changes_swap_whole_snapshots_under_concurrent_decides() {
    let set = group_set();
    let brain = Arc::new(Brain::new());
    let routing = Routing { outbounds: outbounds(), groups: set.clone(), ..Routing::default() };
    brain.install_with(1, router(&set), routing);
    brain.select_group("Pick", "b").unwrap();
    let stop = Arc::new(AtomicBool::new(false));
    let mut readers = Vec::new();
    for _ in 0..4 {
        let brain = brain.clone();
        let stop = stop.clone();
        readers.push(std::thread::spawn(move || {
            let m = meta("www.example.org");
            let mut out = FkbDecision::default();
            let mut n = 0u64;
            while !stop.load(Ordering::Relaxed) || n < 10_000 {
                assert_eq!(brain.decide_meta(&m, &mut out), FKB_OK);
                assert_eq!(out.generation, 1);
                assert!(out.action == FKB_ACTION_ROUTE && (out.outbound == 1 || out.outbound == 3), "{out:?}");
                n += 1;
            }
            n
        }));
    }
    for i in 0..400 {
        brain.select_group("Pick", if i % 2 == 0 { "DIRECT" } else { "b" }).unwrap();
        let mut out = FkbDecision::default();
        assert_eq!(brain.decide_meta(&meta("www.example.org"), &mut out), FKB_OK);
        assert_eq!(out.outbound, if i % 2 == 0 { 3 } else { 1 }, "the next decision sees the selection");
        let Route::Via { outbound, .. } = brain.dns_route("www.example.org") else { panic!("dns route") };
        assert_eq!(outbound, if i % 2 == 0 { "fk-direct" } else { "b" }, "DNS follows the group");
    }
    stop.store(true, Ordering::Relaxed);
    let total: u64 = readers.into_iter().map(|r| r.join().unwrap()).sum();
    assert!(total >= 40_000);
    // A url-test switch through the brain: published, still generation 1.
    let probe = FakeProbe::with(&[("a", Some(300)), ("b", Some(20)), ("c", Some(100))]);
    assert!(brain.test_groups_with(&probe, Force::One(index(&set, "Auto")), Instant::now()) >= 3);
    let mut out = FkbDecision::default();
    assert_eq!(brain.decide_meta(&meta("x.auto.test"), &mut out), FKB_OK);
    assert_eq!((out.outbound, out.generation), (1, 1));
    probe.set(&[("b", None)]);
    brain.test_groups_with(&probe, Force::One(index(&set, "Auto")), Instant::now());
    assert_eq!(brain.decide_meta(&meta("x.auto.test"), &mut out), FKB_OK);
    assert_eq!((out.outbound, out.generation), (2, 1), "switched to c without a reload");
    assert_eq!(brain.select_group("Auto", "a").unwrap_err().0, FKB_E_INVALID);
}
