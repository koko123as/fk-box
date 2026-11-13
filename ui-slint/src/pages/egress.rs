//! The status panel's routed outbound IP and IP quality (pages/StatusPanel.kt
//! StatusModel, common/IpQuality.kt; adapted from FlClash, GPL-3.0): route
//! stamp, 5 s throttle, 300 ms settle, quality expiry, manual retry, the
//! provider waves with a 1 s hedge, the inferred fallback and the caches.

use std::cell::RefCell;
use std::collections::HashMap;
use std::rc::Rc;
use std::time::Duration;

use serde_json::{json, Value};
use slint::Timer;

use crate::app::{self, call, data, long, obj, opt_text, text, Json};
use crate::pages::{mark, Mark};

const THROTTLE_MILLIS: u64 = 5000;
const MOVE_SETTLE_MILLIS: u64 = 300;
const MAX_AGE: i64 = 3 * 24 * 60 * 60 * 1000;
const FAILURE_COOLDOWN: i64 = 5 * 60 * 1000;
const SOURCE_TIMEOUT: i64 = 8000;
const HEDGE_MILLIS: u64 = 1000;

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum Source {
    IdentMe,
    IpApiCom,
    IpQuery,
    IpLocate,
    ProxyCheck,
    IpApiIs,
}

impl Source {
    fn label(self) -> &'static str {
        match self {
            Source::IdentMe => "ident.me",
            Source::IpApiCom => "ip-api.com",
            Source::IpQuery => "ipquery.io",
            Source::IpLocate => "iplocate.io",
            Source::ProxyCheck => "proxycheck.io",
            Source::IpApiIs => "ipapi.is",
        }
    }

    fn name(self) -> &'static str {
        match self {
            Source::IdentMe => "identMe",
            Source::IpApiCom => "ipApiCom",
            Source::IpQuery => "ipQuery",
            Source::IpLocate => "ipLocate",
            Source::ProxyCheck => "proxyCheck",
            Source::IpApiIs => "ipApiIs",
        }
    }

    fn of(name: &str) -> Option<Source> {
        [Source::IdentMe, Source::IpApiCom, Source::IpQuery, Source::IpLocate, Source::ProxyCheck, Source::IpApiIs]
            .into_iter()
            .find(|s| s.name() == name)
    }

    fn order(self) -> usize {
        self as usize
    }

    fn url(self, ip: &str) -> String {
        match self {
            Source::IdentMe => "https://ident.me/json".into(),
            // ip-api.com serves HTTPS only on its paid plan.
            Source::IpApiCom => format!("http://ip-api.com/json/{ip}?fields=status,isp,org,as,mobile,proxy,hosting"),
            Source::IpQuery => format!("https://api.ipquery.io/{ip}?format=json"),
            Source::IpLocate => format!("https://iplocate.io/api/lookup/{ip}"),
            Source::ProxyCheck => format!("https://proxycheck.io/v3/{ip}"),
            Source::IpApiIs => format!("https://api.ipapi.is/?q={ip}"),
        }
    }
}

const WAVES: [&[Source]; 4] = [
    &[Source::IdentMe, Source::IpApiCom, Source::IpQuery],
    &[Source::IpLocate],
    &[Source::IpApiIs],
    &[Source::ProxyCheck],
];

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum IpType {
    Residential,
    Mobile,
    Business,
    Hosting,
}

impl IpType {
    fn name(self) -> &'static str {
        match self {
            IpType::Residential => "residential",
            IpType::Mobile => "mobile",
            IpType::Business => "business",
            IpType::Hosting => "hosting",
        }
    }

    fn label(self) -> &'static str {
        match self {
            IpType::Residential => "Residential",
            IpType::Mobile => "Mobile network",
            IpType::Business => "Business",
            IpType::Hosting => "Data center",
        }
    }

    fn of(name: &str) -> Option<IpType> {
        [IpType::Residential, IpType::Mobile, IpType::Business, IpType::Hosting].into_iter().find(|t| t.name() == name)
    }
}

#[derive(Clone, Debug)]
struct Quality {
    ip: String,
    source: Source,
    kind: IpType,
    organization: Option<String>,
    asn: Option<i64>,
    is_proxy: bool,
    is_vpn: bool,
    is_tor: bool,
    is_abuser: bool,
}

impl Quality {
    fn level(&self) -> &'static str {
        if self.is_tor || self.is_abuser {
            "Risky"
        } else if self.kind == IpType::Hosting {
            "Normal"
        } else {
            "Good"
        }
    }

    fn to_json(&self) -> Value {
        json!({
            "ip": self.ip, "source": self.source.name(), "type": self.kind.name(), "organization": self.organization,
            "asn": self.asn, "isProxy": self.is_proxy, "isVpn": self.is_vpn, "isTor": self.is_tor, "isAbuser": self.is_abuser,
        })
    }

    fn from_json(v: &Value) -> Option<Quality> {
        Some(Quality {
            ip: v["ip"].as_str()?.to_string(),
            source: Source::of(v["source"].as_str()?)?,
            kind: IpType::of(v["type"].as_str()?)?,
            organization: opt_text(&v["organization"]),
            asn: long(&v["asn"]),
            is_proxy: v["isProxy"].as_bool().unwrap_or(false),
            is_vpn: v["isVpn"].as_bool().unwrap_or(false),
            is_tor: v["isTor"].as_bool().unwrap_or(false),
            is_abuser: v["isAbuser"].as_bool().unwrap_or(false),
        })
    }

    fn to_dashboard(&self, checked_at: i64, into: &mut Json) {
        into.insert("organization".into(), json!(self.organization));
        into.insert("asn".into(), json!(self.asn.map(|a| format!("AS{a}"))));
        into.insert("type".into(), json!(self.kind.label()));
        into.insert("quality".into(), json!(self.level()));
        into.insert("source".into(), json!(self.source.label()));
        into.insert("qualityCheckedAt".into(), json!(checked_at));
    }
}

#[derive(Clone)]
struct Record {
    quality: Quality,
    checked_at: i64,
}

#[derive(Clone, Copy, Debug)]
enum Status {
    NoType,
    Timeout,
    RateLimited,
    Failed,
    IpMismatch,
}

impl Status {
    fn name(self) -> &'static str {
        match self {
            Status::NoType => "noType",
            Status::Timeout => "timeout",
            Status::RateLimited => "rateLimited",
            Status::Failed => "failed",
            Status::IpMismatch => "ipMismatch",
        }
    }
}

// ---------------------------------------------------------------------------
// Parsing
// ---------------------------------------------------------------------------

fn flag(v: &Value) -> bool {
    v.as_bool() == Some(true)
}

fn asn_of(v: &Value) -> Option<i64> {
    let asn = match v {
        Value::Number(n) => n.as_i64(),
        Value::String(s) => {
            let digits: String = s.chars().skip_while(|c| !c.is_ascii_digit()).take_while(|c| c.is_ascii_digit()).collect();
            digits.parse().ok()
        }
        _ => None,
    };
    asn.filter(|a| *a != 0)
}

fn declared(v: &Value) -> Option<IpType> {
    match v.as_str()? {
        "isp" => Some(IpType::Residential),
        "business" | "education" | "government" | "banking" => Some(IpType::Business),
        "hosting" => Some(IpType::Hosting),
        _ => None,
    }
}

fn pick(hosting: bool, mobile: bool, declared: Option<IpType>) -> Option<IpType> {
    if hosting || declared == Some(IpType::Hosting) {
        Some(IpType::Hosting)
    } else if mobile {
        Some(IpType::Mobile)
    } else {
        declared
    }
}

struct Answer {
    quality: Quality,
    inferred: bool,
}

#[allow(clippy::too_many_arguments)]
fn answer(ip: &str, source: Source, kind: Option<IpType>, inferred: bool, organization: Option<String>, asn: Option<i64>, flags: [bool; 4]) -> Option<Answer> {
    Some(Answer {
        quality: Quality {
            ip: ip.into(),
            source,
            kind: kind?,
            organization,
            asn,
            is_proxy: flags[0],
            is_vpn: flags[1],
            is_tor: flags[2],
            is_abuser: flags[3],
        },
        inferred,
    })
}

fn parse(source: Source, ip: &str, j: &Value) -> Result<Answer, Status> {
    let result = match source {
        Source::IdentMe => {
            if j["ip"].as_str() != Some(ip) {
                return Err(Status::IpMismatch);
            }
            answer(ip, source, declared(&j["type"]), false, opt_text(&j["aso"]), asn_of(&j["asn"]), [false; 4])
        }
        Source::IpApiCom => {
            if j["status"].as_str() != Some("success") {
                return Err(Status::Failed);
            }
            let kind = pick(flag(&j["hosting"]), flag(&j["mobile"]), None);
            answer(
                ip,
                source,
                Some(kind.unwrap_or(IpType::Residential)),
                kind.is_none(),
                opt_text(&j["org"]).or_else(|| opt_text(&j["isp"])),
                asn_of(&j["as"]),
                [flag(&j["proxy"]), false, false, false],
            )
        }
        Source::IpQuery => {
            let risk = &j["risk"];
            let isp = &j["isp"];
            let kind = pick(flag(&risk["is_datacenter"]), flag(&risk["is_mobile"]), None);
            answer(
                ip,
                source,
                Some(kind.unwrap_or(IpType::Residential)),
                kind.is_none(),
                opt_text(&isp["org"]).or_else(|| opt_text(&isp["isp"])),
                asn_of(&isp["asn"]),
                [flag(&risk["is_proxy"]), flag(&risk["is_vpn"]), flag(&risk["is_tor"]), false],
            )
        }
        Source::IpLocate => {
            let privacy = &j["privacy"];
            let company = &j["company"];
            let asn = &j["asn"];
            answer(
                ip,
                source,
                pick(flag(&privacy["is_hosting"]), false, declared(&company["type"]).or_else(|| declared(&asn["type"]))),
                false,
                opt_text(&company["name"]).or_else(|| opt_text(&asn["name"])),
                asn_of(&asn["asn"]),
                [flag(&privacy["is_proxy"]), flag(&privacy["is_vpn"]), flag(&privacy["is_tor"]), flag(&privacy["is_abuser"])],
            )
        }
        Source::ProxyCheck => {
            let status = j["status"].as_str().unwrap_or("");
            if status == "denied" {
                return Err(Status::RateLimited);
            }
            if status != "ok" && status != "warning" {
                return Err(Status::Failed);
            }
            let result = &j[ip];
            let network = &result["network"];
            let detections = &result["detections"];
            let network_type = network["type"].as_str().unwrap_or("");
            let declared = match network_type {
                "Residential" => Some(IpType::Residential),
                "Business" => Some(IpType::Business),
                _ => None,
            };
            answer(
                ip,
                source,
                pick(flag(&detections["hosting"]) || network_type == "Hosting", network_type == "Wireless", declared),
                false,
                opt_text(&network["organisation"]),
                asn_of(&network["asn"]),
                [flag(&detections["proxy"]), flag(&detections["vpn"]), flag(&detections["tor"]), false],
            )
        }
        Source::IpApiIs => {
            let company = &j["company"];
            let asn = &j["asn"];
            answer(
                ip,
                source,
                pick(flag(&j["is_datacenter"]), flag(&j["is_mobile"]), declared(&company["type"]).or_else(|| declared(&asn["type"]))),
                false,
                opt_text(&company["name"]).or_else(|| opt_text(&asn["org"])),
                asn_of(&asn["asn"]),
                [flag(&j["is_proxy"]), flag(&j["is_vpn"]), flag(&j["is_tor"]), flag(&j["is_abuser"])],
            )
        }
    };
    result.ok_or(Status::NoType)
}

// ---------------------------------------------------------------------------
// The status model
// ---------------------------------------------------------------------------

#[derive(Default)]
struct Model {
    busy: bool,
    checking: bool,
    quality_checking: bool,
    egress: Option<Json>,
    error: Option<String>,
    quality_error: Option<String>,
    route_changed: bool,
    ip_due: bool,
    stamp: Option<String>,
    lan_stamp: Option<Value>,
    probe: Option<String>,
    generation: u64,
    cooldown: bool,
    settle: bool,
    quality_due: bool,
    manual: bool,
    probe_serial: u64,
    expiry: Option<Timer>,
    records: HashMap<String, Record>,
    failures: HashMap<String, (i64, Vec<(Source, Status)>)>,
}

thread_local! {
    static MODEL: RefCell<Model> = RefCell::new(Model { ip_due: true, ..Default::default() });
}

fn model<R>(f: impl FnOnce(&mut Model) -> R) -> R {
    MODEL.with(|m| f(&mut m.borrow_mut()))
}

/// What the status panel shows.
pub struct View {
    pub egress: Option<Json>,
    pub error: bool,
    pub ip_loading: bool,
    pub loading: bool,
    pub checking: bool,
    pub busy: bool,
}

pub fn view() -> View {
    let (transitioning, remote) = data(|d| (d.transitioning(), d.remote()));
    model(|m| {
        let ip_loading = m.checking || (m.route_changed && m.ip_due && !transitioning && !remote);
        let has_ip = m.egress.as_ref().and_then(|e| e.get("ip")).map(|v| v.is_string()).unwrap_or(false);
        View {
            egress: m.egress.clone(),
            error: m.error.is_some(),
            ip_loading,
            loading: (ip_loading && !has_ip) || m.quality_checking,
            checking: m.checking || m.quality_checking,
            busy: m.busy,
        }
    })
}

pub fn set_busy(busy: bool) {
    model(|m| m.busy = busy);
    mark(Mark::DASH);
}

/// _syncRoute: route changes cancel stale requests.
pub fn sync() {
    let (stamp, lan_stamp) = data(|d| {
        let mut selections: Vec<String> = d.groups.iter().map(|g| format!("{}:{}", text(&g["tag"]), text(&g["selected"]))).collect();
        selections.sort();
        let mut addresses = d.lan_addresses();
        addresses.sort();
        let route = if d.running() {
            format!(
                "{}:{}:{}:{}",
                text(d.dashboard.get("startedAt").unwrap_or(&Value::Null)),
                d.selected_profile_id(),
                text(d.dashboard.get("mode").unwrap_or(&Value::Null)).to_lowercase(),
                selections.join(",")
            )
        } else {
            String::new()
        };
        let stamp = format!(
            "{}:{}:{}:{}:{}:{:?}",
            d.status(),
            d.running(),
            d.remote(),
            text(d.dashboard.get("remote").unwrap_or(&Value::Null)),
            route,
            addresses
        );
        (stamp, d.lan.get("stamp").cloned())
    });
    let changed = model(|m| {
        let previous_lan = m.lan_stamp.take();
        m.lan_stamp = lan_stamp.clone();
        if m.stamp.as_deref() == Some(stamp.as_str()) && (previous_lan == lan_stamp || previous_lan.is_none()) {
            return None;
        }
        let changed = m.stamp.is_some();
        m.stamp = Some(stamp);
        m.generation += 1;
        if let Some(probe) = m.probe.take() {
            close_probe(probe);
        }
        m.expiry = None;
        m.egress = None;
        m.error = None;
        m.quality_error = None;
        m.checking = false;
        m.quality_checking = false;
        m.ip_due = true;
        m.quality_due = false;
        m.manual = false;
        m.route_changed = changed;
        Some(changed && !m.settle)
    });
    match changed {
        None => {
            // The transition flags still matter for a pending refresh.
            schedule();
        }
        Some(settle) => {
            if settle {
                model(|m| m.settle = true);
                Timer::single_shot(Duration::from_millis(MOVE_SETTLE_MILLIS), || {
                    model(|m| m.settle = false);
                    schedule();
                });
            }
            schedule();
            mark(Mark::DASH);
        }
    }
}

pub fn retry() {
    let skip = model(|m| m.checking || m.quality_checking);
    if skip {
        return;
    }
    model(|m| {
        m.ip_due = true;
        m.manual = true;
    });
    schedule();
}

fn schedule() {
    Timer::single_shot(Duration::ZERO, flush);
}

fn flush() {
    let (transitioning, remote) = data(|d| (d.transitioning(), d.remote()));
    let run = model(|m| {
        if m.checking || m.quality_checking || transitioning || remote {
            return None;
        }
        if m.ip_due && !m.manual && (m.settle || (!m.route_changed && m.cooldown)) {
            return None;
        }
        if m.ip_due || m.quality_due {
            Some((m.ip_due, m.manual))
        } else {
            None
        }
    });
    if let Some((read_ip, manual)) = run {
        refresh(read_ip, manual);
    }
}

fn new_probe_id() -> String {
    model(|m| {
        m.probe_serial += 1;
        format!("{}:{}", crate::format::now_ms(), m.probe_serial)
    })
}

fn close_probe(id: String) {
    call("closeProbe", json!({ "id": id }), |_| {});
}

fn current(generation: u64) -> bool {
    model(|m| m.generation == generation)
}

fn refresh(read_ip: bool, manual: bool) {
    let probe = new_probe_id();
    let (generation, retry_quality_for, previous) = model(|m| {
        let retry_quality_for = if manual && m.quality_error.is_some() { m.egress.as_ref().and_then(|e| e.get("ip")).map(text) } else { None };
        m.ip_due = false;
        m.quality_due = false;
        m.manual = false;
        m.route_changed = false;
        if read_ip {
            m.cooldown = true;
        }
        m.generation += 1;
        m.expiry = None;
        if let Some(old) = m.probe.replace(probe.clone()) {
            close_probe(old);
        }
        m.checking = read_ip;
        m.quality_checking = !read_ip;
        m.error = None;
        m.quality_error = None;
        (m.generation, retry_quality_for, m.egress.clone().unwrap_or_default())
    });
    if read_ip {
        Timer::single_shot(Duration::from_millis(THROTTLE_MILLIS), || {
            model(|m| m.cooldown = false);
            schedule();
        });
    }
    mark(Mark::DASH);
    let after_ip = {
        let probe = probe.clone();
        move |result: Json| {
            if !current(generation) {
                return;
            }
            let ip = result.get("ip").and_then(|v| v.as_str()).map(str::to_string);
            model(|m| {
                m.egress = if result.is_empty() { None } else { Some(result.clone()) };
                m.checking = false;
                m.quality_checking = ip.is_some();
            });
            mark(Mark::DASH);
            let Some(ip) = ip else {
                finish(generation, &probe);
                return;
            };
            let refresh = !read_ip || Some(&ip) == retry_quality_for.as_ref();
            let opened = {
                let probe = probe.clone();
                let ip = ip.clone();
                move || lookup(generation, probe, ip, refresh, result)
            };
            if read_ip {
                opened();
            } else {
                call("openProbe", json!({ "id": probe }), move |r| match r {
                    Ok(_) => opened(),
                    Err(_) => {}
                });
            }
        }
    };
    if read_ip {
        let probe2 = probe.clone();
        call("egress", json!({ "id": probe }), move |r| match r {
            Ok(value) => after_ip(obj(&value)),
            Err(error) => {
                if current(generation) {
                    model(|m| {
                        m.egress = None;
                        m.error = Some(crate::format::clean_error(&error));
                    });
                }
                finish(generation, &probe2);
            }
        });
    } else {
        after_ip(previous);
    }
}

fn finish(generation: u64, probe: &str) {
    close_probe(probe.to_string());
    if current(generation) {
        let again = model(|m| {
            m.checking = false;
            m.quality_checking = false;
            m.probe = None;
            m.ip_due || m.quality_due
        });
        if again {
            schedule();
        }
    }
    mark(Mark::DASH);
}

fn fresh(record: &Record, ip: &str) -> bool {
    let age = crate::format::now_ms() - record.checked_at;
    record.quality.ip == ip && (0..MAX_AGE).contains(&age)
}

/// IpQualityCache.lookup, then the panel update.
fn lookup(generation: u64, probe: String, ip: String, refresh: bool, result: Json) {
    let now = crate::format::now_ms();
    let cached = model(|m| {
        m.records.retain(|k, r| r.quality.ip == *k && (0..MAX_AGE).contains(&(now - r.checked_at)));
        m.failures.retain(|_, (at, _)| (0..FAILURE_COOLDOWN).contains(&(now - *at)));
        if refresh {
            m.records.remove(&ip);
            m.failures.remove(&ip);
            None
        } else if let Some(r) = m.records.get(&ip) {
            Some(Ok(r.clone()))
        } else {
            m.failures.get(&ip).map(|(_, f)| Err(f.clone()))
        }
    });
    let done = {
        let probe = probe.clone();
        move |outcome: Result<Record, Vec<(Source, Status)>>| {
            if !current(generation) {
                close_probe(probe);
                return;
            }
            let mut result = result;
            result.remove("qualityError");
            result.remove("qualityFailures");
            match outcome {
                Ok(record) => {
                    record.quality.to_dashboard(record.checked_at, &mut result);
                    let remaining = record.checked_at + MAX_AGE - crate::format::now_ms();
                    let timer = Timer::default();
                    timer.start(slint::TimerMode::SingleShot, Duration::from_millis(remaining.max(0) as u64), move || {
                        if current(generation) {
                            model(|m| m.quality_due = true);
                            schedule();
                        }
                    });
                    model(|m| {
                        m.egress = Some(result);
                        m.expiry = Some(timer);
                    });
                }
                Err(failures) => {
                    let message = "IP quality unavailable".to_string();
                    result.insert("qualityError".into(), json!(message));
                    result.insert(
                        "qualityFailures".into(),
                        Value::Array(failures.iter().map(|(s, st)| json!({ "source": s.label(), "status": st.name() })).collect()),
                    );
                    model(|m| {
                        m.quality_error = Some(message);
                        m.egress = Some(result);
                    });
                }
            }
            finish(generation, &probe);
        }
    };
    match cached {
        Some(outcome) => done(outcome),
        None if refresh => run_waves(generation, probe, ip, Box::new(done)),
        None => {
            // The persistent cache, then the network.
            call("readQualityCache", json!({ "ip": ip }), move |r| {
                if !current(generation) {
                    close_probe(probe);
                    return;
                }
                let record = r.ok().filter(|v| v.is_object()).and_then(|v| {
                    Some(Record { quality: Quality::from_json(&v["quality"])?, checked_at: long(&v["checkedAt"])? })
                });
                match record {
                    Some(record) if fresh(&record, &ip) => {
                        model(|m| m.records.insert(ip.clone(), record.clone()));
                        done(Ok(record))
                    }
                    _ => run_waves(generation, probe, ip, Box::new(done)),
                }
            });
        }
    }
}

type Done = Box<dyn FnOnce(Result<Record, Vec<(Source, Status)>>)>;

struct Lookup {
    generation: u64,
    probe: String,
    ip: String,
    wave: usize,
    in_flight: usize,
    fallback: Option<Quality>,
    failures: Vec<(Source, Status)>,
    done: Option<Done>,
    hedge: Option<Timer>,
}

fn run_waves(generation: u64, probe: String, ip: String, done: Done) {
    let state = Rc::new(RefCell::new(Lookup { generation, probe, ip, wave: 0, in_flight: 0, fallback: None, failures: Vec::new(), done: Some(done), hedge: None }));
    launch_wave(&state);
}

fn complete(state: &Rc<RefCell<Lookup>>, outcome: Result<Quality, ()>) {
    let (done, ip, failures) = {
        let mut s = state.borrow_mut();
        s.hedge = None;
        let Some(done) = s.done.take() else { return };
        let mut failures = s.failures.clone();
        failures.sort_by_key(|(src, _)| src.order());
        (done, s.ip.clone(), failures)
    };
    match outcome {
        Ok(quality) => {
            let record = Record { quality, checked_at: crate::format::now_ms() };
            model(|m| {
                m.records.insert(ip.clone(), record.clone());
                m.failures.remove(&ip);
            });
            let stored = json!({ "quality": record.quality.to_json(), "checkedAt": record.checked_at });
            call("writeQualityCache", json!({ "ip": ip, "value": stored }), |_| {});
            done(Ok(record));
        }
        Err(()) => {
            if current(state.borrow().generation) {
                model(|m| m.failures.insert(ip, (crate::format::now_ms(), failures.clone())));
            }
            done(Err(failures));
        }
    }
}

fn launch_wave(state: &Rc<RefCell<Lookup>>) {
    if state.borrow().done.is_none() {
        return;
    }
    if !current(state.borrow().generation) {
        state.borrow_mut().done = None;
        return;
    }
    let wave = state.borrow().wave;
    if wave == WAVES.len() {
        let fallback = state.borrow().fallback.clone();
        complete(state, fallback.ok_or(()));
        return;
    }
    let sources = WAVES[wave];
    {
        let mut s = state.borrow_mut();
        s.wave += 1;
        s.in_flight += sources.len();
    }
    for &source in sources {
        let (probe, ip) = {
            let s = state.borrow();
            (s.probe.clone(), s.ip.clone())
        };
        let st = state.clone();
        call("probeGet", json!({ "id": probe, "url": source.url(&ip), "timeoutMillis": SOURCE_TIMEOUT }), move |r| {
            st.borrow_mut().in_flight -= 1;
            if st.borrow().done.is_none() {
                return;
            }
            if !current(st.borrow().generation) {
                st.borrow_mut().done = None;
                return;
            }
            let ip = st.borrow().ip.clone();
            let outcome = match r {
                Ok(v) => {
                    let status = long(&v["status"]).unwrap_or(503);
                    if status == 429 {
                        Err(Status::RateLimited)
                    } else if status != 200 {
                        Err(Status::Failed)
                    } else {
                        match serde_json::from_str::<Value>(v["body"].as_str().unwrap_or("")) {
                            Ok(body) => parse(source, &ip, &body),
                            Err(_) => Err(Status::Failed),
                        }
                    }
                }
                Err(e) => Err(if e.contains("deadline exceeded") { Status::Timeout } else { Status::Failed }),
            };
            match outcome {
                Ok(answer) if !answer.inferred => complete(&st, Ok(answer.quality)),
                Ok(answer) => {
                    if st.borrow().fallback.is_none() {
                        st.borrow_mut().fallback = Some(answer.quality);
                    }
                    if st.borrow().in_flight == 0 {
                        launch_wave(&st);
                    }
                }
                Err(status) => {
                    st.borrow_mut().failures.push((source, status));
                    if st.borrow().in_flight == 0 {
                        launch_wave(&st);
                    }
                }
            }
        });
    }
    if state.borrow().wave < WAVES.len() {
        let st = state.clone();
        let timer = Timer::default();
        timer.start(slint::TimerMode::SingleShot, Duration::from_millis(HEDGE_MILLIS), move || launch_wave(&st));
        state.borrow_mut().hedge = Some(timer);
    }
}

/// The start toggle (StatusPanel's StartToggle onClick).
pub fn toggle_service() {
    let (busy, transitioning, running, has_profile) = (
        model(|m| m.busy),
        data(|d| d.transitioning()),
        data(|d| d.running()),
        data(|d| d.selected_profile().is_some()),
    );
    if busy || transitioning {
        return;
    }
    if !running && !has_profile {
        crate::nav::form("profile-add", "");
        return;
    }
    set_busy(true);
    app::act_then(if running { "stopService" } else { "startService" }, json!({}), |_| set_busy(false));
}
