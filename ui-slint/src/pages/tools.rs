//! Tools (pages/ToolsPage.kt ToolsPage, pages/ToolPages.kt): the Tools tab,
//! network quality and STUN tests, crash/memory/power reports, remote
//! control, the endpoints (Tailscale with Taildrop, OpenVPN, OpenConnect,
//! USB/IP), their peers, authentication and the Android share page. All are
//! Rust-built rows (src/rows.rs).

use std::cell::RefCell;
use std::collections::HashMap;
use std::rc::Rc;

use serde_json::{json, Value};

use crate::app::{self, data, long, num, obj, rows as list, text, truthy, Json};
use crate::format;
use crate::forms;
use crate::i18n::tr;
use crate::nav::{self, MenuEntry};
use crate::pages::{mark, Mark};
use crate::rows::{self, Bar, Page, Row};
use crate::AppWindow;

const ENDPOINT_KINDS: [&str; 4] = ["tailscale", "openvpn", "openconnect", "usbip"];
const REPORT_KINDS: [&str; 3] = ["crash", "oom", "power"];

fn s(v: &Value, k: &str) -> String {
    v.get(k).map(text).unwrap_or_default()
}

fn or_dash(v: String) -> String {
    if v.is_empty() || v == "null" {
        "—".into()
    } else {
        v
    }
}

/// DetailRows: a title and its selectable value per row.
pub fn detail_rows(values: Vec<(&str, String)>) -> Vec<Row> {
    values.into_iter().map(|(k, v)| Row::item(&tr(k)).subtitle(&or_dash(v))).collect()
}

fn strings(v: &Value) -> Vec<String> {
    v.as_array().map(|a| a.iter().map(text).collect()).unwrap_or_default()
}

/// ErrorState(error, retry)
fn error_row(error: &str, retry: Option<Rc<dyn Fn()>>) -> Vec<Row> {
    let mut r = Row::empty(&tr("Error"), error, "info");
    if let Some(f) = retry {
        r = r.value(&tr("Retry")).on(move |_| f());
    }
    vec![r]
}

// ---------------------------------------------------------------------------
// The Tools tab
// ---------------------------------------------------------------------------

fn tools_page() -> Page {
    let sidebar = !nav::mobile();
    let (running, endpoints) = data(|d| {
        let endpoints: Vec<&'static str> = if d.running() { ENDPOINT_KINDS.into_iter().filter(|k| d.tool_state.get(*k).map(|v| !list(v).is_empty()).unwrap_or(false)).collect() } else { Vec::new() };
        (d.running(), endpoints)
    });
    let _ = running;
    let mut rows = Vec::new();
    if !sidebar {
        rows.push(Row::open(&tr("Requests"), Some("requests"), || {
            nav::push("requests", "");
        }));
    }
    rows.push(Row::open(&tr("Resources"), Some("resources"), crate::pages::resources::open));
    if !sidebar {
        rows.push(Row::open(&tr("Logs"), Some("logs"), || {
            nav::push("logs", "");
        }));
    }
    for kind in endpoints {
        rows.push(Row::open(&endpoint_name(kind), Some("devices"), move || open_endpoints(kind)));
    }
    rows.push(Row::open(&tr("Terminal"), Some("code"), || crate::pages::terminal::open(None, None, None)));
    rows.push(Row::open(&tr("Remote control"), Some("proxies"), open_remote));
    // Theme, access, routing rules, backup, general (fk-box follows the system language).
    rows.push(Row::section(&tr("Settings")));
    rows.push(Row::open(&tr("Appearance"), Some("paintbrush"), crate::pages::appearance::open));
    rows.push(Row::open(&tr("Access control"), Some("apps-list"), crate::pages::settings::open_access));
    rows.push(Row::open(&tr("Traffic routing"), Some("rules"), crate::pages::routing::open));
    rows.push(Row::open(&tr("Backup and restore"), Some("cloud-sync"), crate::pages::settings::open_backup));
    rows.push(Row::open(&tr("General"), Some("settings"), crate::pages::settings::open_general));
    rows.push(Row::section(&tr("Other")));
    rows.push(Row::open(&tr("Network quality"), Some("speed"), || open_diagnostics(true)));
    rows.push(Row::open(&tr("STUN test"), Some("proxies"), || open_diagnostics(false)));
    for kind in REPORT_KINDS {
        rows.push(Row::open(&tr(report_title(kind)), Some("profiles"), move || open_reports(kind)));
    }
    rows.push(Row::open(&tr("About"), Some("info"), crate::pages::settings::open_about));
    let mut first = Row::first_section("");
    first.data.title = "".into();
    rows.insert(0, first);
    Page::new(&tr("Tools")).rows(rows)
}

// ---------------------------------------------------------------------------
// Diagnostics
// ---------------------------------------------------------------------------

struct Diag {
    address: String,
    outbound: String,
    serial: bool,
    http3: bool,
    seconds: i64,
    asking: bool,
}

fn open_diagnostics(quality: bool) {
    let initial = data(|d| if quality { s(&Value::Object(d.quality.clone()), "configURL") } else { s(&Value::Object(d.stun.clone()), "server") });
    let st = Rc::new(RefCell::new(Diag { address: initial, outbound: String::new(), serial: false, http3: false, seconds: 30, asking: false }));
    let a = st.clone();
    let id = rows::push(move || diagnostics_page(&a, quality));
    nav::on_closed(id, move || app::act(if quality { "tools.stopQuality" } else { "tools.stopStun" }, json!({})));
}

fn diagnostics_page(st: &Rc<RefCell<Diag>>, quality: bool) -> Page {
    let (state, service_running, outbounds) = data(|d| {
        let state = Value::Object(if quality { d.quality.clone() } else { d.stun.clone() });
        let mut outbounds = vec![String::new()];
        for o in &d.outbounds {
            let t = text(&o["tag"]);
            if !outbounds.contains(&t) {
                outbounds.push(t);
            }
        }
        (state, d.running(), outbounds)
    });
    let running = truthy(&state["isRunning"]);
    // The metered-network confirmation.
    if quality && truthy(&state["showMeteredWarning"]) && !st.borrow().asking {
        st.borrow_mut().asking = true;
        let a = st.clone();
        slint::Timer::single_shot(std::time::Duration::ZERO, move || {
            let a = a.clone();
            nav::confirm_styled("Network quality", Some("This test transfers data on a metered network. Continue?"), "Start", false, move |ok| {
                app::act_then(if ok { "tools.confirmQuality" } else { "tools.dismissQuality" }, json!({}), move |_| a.borrow_mut().asking = false);
            });
        });
    }
    let d = st.borrow();
    let mut rows = Vec::new();
    let a = st.clone();
    rows.push(Row::input(&tr(if quality { "URL" } else { "Address" }), &d.address, "", move |v| a.borrow_mut().address = v).key("address").enabled(!running).mono());
    rows.push(Row::spacer(8));
    if service_running {
        let a = st.clone();
        let current = if outbounds.contains(&d.outbound) { d.outbound.clone() } else { String::new() };
        let default = tr("Default");
        rows.push(
            forms::options_row(
                &tr("Outbound"),
                outbounds,
                &current,
                Rc::new(move |v: &str| if v.is_empty() { default.clone() } else { v.to_string() }),
                Rc::new(move |v| {
                    if !running {
                        a.borrow_mut().outbound = v;
                        mark(Mark::ROWS);
                    }
                }),
            )
            .key("outbound"),
        );
    }
    if quality {
        let a = st.clone();
        rows.push(Row::switch(&tr("Serial test"), d.serial, move |v| a.borrow_mut().serial = v).key("serial").enabled(!running));
        let a = st.clone();
        rows.push(Row::switch("HTTP/3", d.http3, move |v| a.borrow_mut().http3 = v).key("http3").enabled(!running));
        let a = st.clone();
        rows.push(
            forms::options_row(
                &tr("Duration"),
                vec!["30".into(), "60".into()],
                &d.seconds.to_string(),
                Rc::new(|v: &str| format!("{v} s")),
                Rc::new(move |v| {
                    if !running {
                        a.borrow_mut().seconds = v.parse().unwrap_or(30);
                        mark(Mark::ROWS);
                    }
                }),
            )
            .key("duration"),
        );
    }
    rows.push(Row::spacer(20));
    let a = st.clone();
    let mut start = Row::button(&tr(if running { "Stop" } else { "Start" }), 0, move || {
        if running {
            app::act(if quality { "tools.stopQuality" } else { "tools.stopStun" }, json!({}));
        } else {
            let d = a.borrow();
            if quality {
                app::act("tools.startQuality", json!({ "url": d.address.trim(), "outbound": d.outbound, "serial": d.serial, "http3": d.http3, "seconds": d.seconds }));
            } else {
                app::act("tools.startStun", json!({ "server": d.address.trim(), "outbound": d.outbound }));
            }
        }
    })
    .key("start");
    start.data.checked = true;
    rows.push(start);
    if running {
        rows.push(Row::progress("", -1.0));
    }
    let phase = state.get("phase").map(num).filter(|_| state.get("phase").map(|p| !p.is_null()).unwrap_or(false)).unwrap_or(-1.0);
    if phase >= 0.0 {
        rows.push(Row::spacer(16));
        let values = if quality {
            vec![
                ("Latency", format!("{} ms", state.get("idleLatencyMs").map(text).filter(|t| !t.is_empty() && t != "null").unwrap_or_else(|| "0".into()))),
                ("Download", format!("{:.2} Mbps", num(&state["downloadCapacity"]) / 1_000_000.0)),
                ("Upload", format!("{:.2} Mbps", num(&state["uploadCapacity"]) / 1_000_000.0)),
                ("Download RPM", s(&state, "downloadRPM")),
                ("Upload RPM", s(&state, "uploadRPM")),
            ]
        } else {
            let mut v = vec![
                ("Address", s(&state, "externalAddr")),
                ("Latency", format!("{} ms", state.get("latencyMs").map(text).filter(|t| !t.is_empty() && t != "null").unwrap_or_else(|| "0".into()))),
            ];
            if truthy(&state["natTypeSupported"]) {
                v.push(("NAT mapping", s(&state, "mapping")));
                v.push(("NAT filtering", s(&state, "filtering")));
            }
            v
        };
        rows.extend(detail_rows(values));
    }
    Page::new(&tr(if quality { "Network quality" } else { "STUN test" })).rows(rows)
}

// ---------------------------------------------------------------------------
// Reports
// ---------------------------------------------------------------------------

pub fn report_title(kind: &str) -> &'static str {
    match kind {
        "crash" => "Crash reports",
        "oom" => "Memory reports",
        "power" => "Power reports",
        _ => "Reports",
    }
}

#[derive(Default)]
struct Loaded {
    items: Option<Vec<Value>>,
    error: Option<String>,
    generation: u64,
}

type LoadedRef = Rc<RefCell<Loaded>>;

/// Runs `method` and keeps its list (LaunchedEffect(attempt) { … }).
fn reload(st: &LoadedRef, method: &'static str, args: Value) {
    let generation = {
        let mut s = st.borrow_mut();
        s.generation += 1;
        s.error = None;
        s.generation
    };
    let a = st.clone();
    app::call(method, args, move |r| {
        let mut s = a.borrow_mut();
        if s.generation != generation {
            return;
        }
        match r {
            Ok(v) => s.items = Some(list(&v)),
            Err(e) => s.error = Some(format::clean_error(&e)),
        }
        drop(s);
        mark(Mark::ROWS);
    });
}

fn open_reports(kind: &'static str) {
    let st: LoadedRef = Rc::new(RefCell::new(Loaded::default()));
    let refresh: Rc<dyn Fn()> = {
        let st = st.clone();
        Rc::new(move || {
            st.borrow_mut().items = None;
            reload(&st, "reports.list", json!({ "kind": kind }));
            mark(Mark::ROWS);
        })
    };
    refresh();
    rows::push(move || {
        let s = st.borrow();
        let r1 = refresh.clone();
        let r2 = refresh.clone();
        let bar = vec![
            Bar::new("refresh", "Refresh", move || r1()),
            Bar::new("delete", "Clear", move || {
                let r = r2.clone();
                nav::confirm("Delete all reports?", None, "Delete", move |ok| {
                    if ok {
                        app::act_then("reports.clear", json!({ "kind": kind }), move |_| r());
                    }
                });
            }),
        ];
        let mut rows = Vec::new();
        if let Some(e) = &s.error {
            rows.extend(error_row(e, Some(refresh.clone())));
        }
        let loading = s.items.is_none() && s.error.is_none();
        if s.items.as_ref().map(Vec::is_empty).unwrap_or(false) {
            rows.push(Row::empty(&tr("No reports"), "", "info"));
        }
        for report in s.items.clone().unwrap_or_default() {
            let id = s_of(&report, "id");
            let r = refresh.clone();
            rows.push(
                Row::item(&format::local_date(long(&report["date"]).unwrap_or(0)))
                    .subtitle(&id)
                    .icon(if truthy(&report["read"]) { "profiles" } else { "info" })
                    .key(&id)
                    .on(move |_| open_report(kind, id.clone(), r.clone())),
            );
        }
        Page::new(&tr(report_title(kind))).loading(loading).bar(bar).rows(rows)
    });
}

fn s_of(v: &Value, k: &str) -> String {
    s(v, k)
}

struct ReportExport {
    config: bool,
    logs: bool,
    encrypt: bool,
    busy: bool,
}

fn open_report(kind: &'static str, id: String, on_closed: Rc<dyn Fn()>) {
    let files: LoadedRef = Rc::new(RefCell::new(Loaded::default()));
    reload(&files, "reports.files", json!({ "kind": kind, "id": id }));
    let ex = Rc::new(RefCell::new(ReportExport { config: false, logs: true, encrypt: true, busy: false }));
    let route = rows::push(move || {
        let f = files.borrow();
        let e = ex.borrow();
        let id_d = id.clone();
        let bar = vec![Bar::new("delete", "Delete", move || {
            let id = id_d.clone();
            let route = nav::top().map(|r| r.id);
            nav::confirm("Delete", Some(&id.clone()), "Delete", move |ok| {
                if ok {
                    app::act_then("reports.delete", json!({ "kind": kind, "id": id }), move |done| {
                        if done {
                            if let Some(r) = route {
                                nav::close(r);
                            }
                        }
                    });
                }
            });
        })];
        let mut rows = Vec::new();
        if let Some(err) = &f.error {
            rows.extend(error_row(err, None));
        } else {
            rows.push(Row::spacer(24));
            for file in f.items.clone().unwrap_or_default() {
                let name = s(&file, "name");
                let label = s(&file, "label");
                let (n1, n2, id1, id2) = (name.clone(), name.clone(), id.clone(), id.clone());
                let is_profile = s(&file, "kind") == "PROFILE";
                let l = label.clone();
                rows.push(
                    Row::item(&label)
                        .subtitle(&format::bytes(num(&file["size"])))
                        .key(&format!("f:{name}"))
                        .on(move |_| {
                            if is_profile {
                                app::act("reports.save", json!({ "kind": kind, "id": id1, "name": n1 }));
                            } else {
                                open_report_text(kind, id1.clone(), n1.clone(), l.clone());
                            }
                        })
                        .trailing("arrow-down", move || app::act("reports.save", json!({ "kind": kind, "id": id2, "name": n2 }))),
                );
            }
        }
        rows.push(Row::section(&tr("Export")));
        let a = ex.clone();
        rows.push(Row::switch(&tr("Include configuration"), e.config, move |v| a.borrow_mut().config = v).key("config"));
        let a = ex.clone();
        rows.push(Row::switch(&tr("Include logs"), e.logs, move |v| a.borrow_mut().logs = v).key("logs"));
        let a = ex.clone();
        rows.push(Row::switch(&tr("Encrypt for sing-box maintainers"), e.encrypt, move |v| a.borrow_mut().encrypt = v).key("encrypt"));
        let a = ex.clone();
        let id_x = id.clone();
        rows.push(
            Row::item(&tr("Export archive"))
                .key("export")
                .on(move |_| {
                    let (config, logs, encrypt) = {
                        let mut x = a.borrow_mut();
                        if x.busy {
                            return;
                        }
                        x.busy = true;
                        (x.config, x.logs, x.encrypt)
                    };
                    let b = a.clone();
                    app::act_then("reports.export", json!({ "kind": kind, "id": id_x, "config": config, "logs": logs, "encrypt": encrypt }), move |_| {
                        b.borrow_mut().busy = false;
                        mark(Mark::ROWS);
                    });
                })
                .enabled(!e.busy),
        );
        Page::new(&tr(report_title(kind))).loading(e.busy).bar(bar).rows(rows)
    });
    nav::on_closed(route, move || on_closed());
}

fn open_report_text(kind: &'static str, id: String, name: String, title: String) {
    let st: Rc<RefCell<(Option<String>, Option<String>)>> = Rc::new(RefCell::new((None, None)));
    let a = st.clone();
    app::call("reports.read", json!({ "kind": kind, "id": id, "name": name }), move |r| {
        match r {
            Ok(v) => a.borrow_mut().0 = Some(text(&v)),
            Err(e) => a.borrow_mut().1 = Some(format::clean_error(&e)),
        }
        mark(Mark::ROWS);
    });
    rows::push(move || {
        let s = st.borrow();
        let rows = match (&s.0, &s.1) {
            (_, Some(e)) => error_row(e, None),
            (Some(content), _) => vec![Row::text(content).mono().checked(true)],
            _ => Vec::new(),
        };
        Page::new(&title).loading(s.0.is_none() && s.1.is_none()).rows(rows)
    });
}

// ---------------------------------------------------------------------------
// Remote control
// ---------------------------------------------------------------------------

fn open_remote() {
    let st: LoadedRef = Rc::new(RefCell::new(Loaded::default()));
    reload(&st, "remoteServers", json!({}));
    let refresh: Rc<dyn Fn()> = {
        let st = st.clone();
        Rc::new(move || reload(&st, "remoteServers", json!({})))
    };
    rows::push(move || {
        let s = st.borrow();
        let r = refresh.clone();
        let bar = vec![Bar::new("add", "Add server", move || remote_form(Json::new(), r.clone()))];
        let mut rows = Vec::new();
        let (remote, remote_name) = data(|d| (d.remote(), d.dashboard.get("remote").map(text).unwrap_or_default()));
        if remote {
            rows.push(Row::item(&remote_name).trailing("close", || app::act("disconnectRemoteServer", json!({}))).value(&tr("Disconnect")).key("current"));
        }
        if let Some(e) = &s.error {
            rows.extend(error_row(e, None));
        }
        if s.items.as_ref().map(Vec::is_empty).unwrap_or(false) {
            rows.push(Row::empty(&tr("No remote servers"), "", "info"));
        }
        for server in s.items.clone().unwrap_or_default() {
            let id = long(&server["id"]).unwrap_or(-1);
            let name = s_of(&server, "name");
            let srv = obj(&server);
            let r = refresh.clone();
            rows.push(
                Row::card(&name, &s_of(&server, "url"), move || app::act("connectRemoteServer", json!({ "id": id })))
                    .key(&id.to_string())
                    .menu(move || {
                        let (srv, r1, r2, name) = (srv.clone(), r.clone(), r.clone(), name.clone());
                        vec![
                            MenuEntry::act("Edit", "edit", move || remote_form(srv.clone(), r1.clone())),
                            MenuEntry::act("Delete", "delete", move || {
                                let r = r2.clone();
                                nav::confirm("Delete", Some(&name), "Delete", move |ok| {
                                    if ok {
                                        app::act_then("deleteRemoteServer", json!({ "id": id }), move |_| r());
                                    }
                                });
                            })
                            .danger(),
                        ]
                    }),
            );
            rows.push(Row::spacer(12));
        }
        Page::new(&tr("Remote control")).bar(bar).rows(rows)
    });
}

fn remote_form(initial: Json, on_closed: Rc<dyn Fn()>) {
    let get = |k: &str| initial.get(k).and_then(Value::as_str).unwrap_or("").to_string();
    let st = Rc::new(RefCell::new((get("name"), get("url"), get("secret"), false)));
    let id = initial.get("id").and_then(long);
    let a = st.clone();
    let route = rows::form(move || {
        let s = a.borrow();
        let mut rows = Vec::new();
        let b = a.clone();
        rows.push(Row::input(&tr("Name"), &s.0, "", move |v| b.borrow_mut().0 = v).key("name"));
        rows.push(Row::spacer(16));
        let b = a.clone();
        rows.push(Row::input(&tr("Address"), &s.1, "", move |v| b.borrow_mut().1 = v).key("url").mono());
        rows.push(Row::spacer(16));
        let b = a.clone();
        rows.push(Row::input(&tr("Secret"), &s.2, "", move |v| b.borrow_mut().2 = v).key("secret").secret(true));
        let saving = s.3;
        let b = a.clone();
        Page::new(&tr("Remote control"))
            .loading(saving)
            .confirm(
                Bar::new("check", "Save", move || {
                    let (name, url, secret) = {
                        let mut s = b.borrow_mut();
                        s.3 = true;
                        (s.0.clone(), s.1.clone(), s.2.clone())
                    };
                    mark(Mark::ROWS);
                    let c = b.clone();
                    let route = nav::top().map(|r| r.id);
                    app::act_then("saveRemoteServer", json!({ "id": id, "name": name, "url": url, "secret": secret }), move |ok| {
                        c.borrow_mut().3 = false;
                        if ok {
                            if let Some(r) = route {
                                nav::close(r);
                            }
                        }
                        mark(Mark::ROWS);
                    });
                })
                .when(!saving),
            )
            .rows(rows)
    });
    nav::on_closed(route, move || on_closed());
}

// ---------------------------------------------------------------------------
// Endpoints
// ---------------------------------------------------------------------------

pub fn endpoint_name(kind: &str) -> String {
    match kind {
        "tailscale" => "Tailscale".into(),
        "openconnect" => "OpenConnect".into(),
        "openvpn" => "OpenVPN".into(),
        "usbip" => "USB/IP".into(),
        _ => kind.into(),
    }
}

pub fn peer_name(peer: &Value) -> String {
    let dns = s(peer, "dnsName");
    if !dns.is_empty() {
        return dns.trim_end_matches('.').to_string();
    }
    let host = s(peer, "hostName");
    if !host.is_empty() {
        host
    } else {
        s(peer, "id")
    }
}

pub fn endpoint_peers(endpoint: &Value) -> Vec<Value> {
    list(&endpoint["userGroups"]).iter().flat_map(|g| list(&g["peers"])).collect()
}

fn endpoint_tag(endpoint: &Value) -> String {
    let t = s(endpoint, "endpointTag");
    if t.is_empty() {
        s(endpoint, "serverTag")
    } else {
        t
    }
}

fn endpoints_of(kind: &str) -> Vec<Value> {
    data(|d| d.tool_state.get(kind).map(list).unwrap_or_default())
}

fn open_endpoints(kind: &'static str) {
    rows::push(move || {
        let endpoints = endpoints_of(kind);
        let mut rows = Vec::new();
        if endpoints.is_empty() {
            rows.push(Row::empty(&tr("No endpoints"), "", "info"));
        }
        for e in endpoints {
            let tag = endpoint_tag(&e);
            let t = tag.clone();
            rows.push(Row::open(&tag, Some("devices"), move || open_endpoint(kind, t.clone())).subtitle(&s(&e, "stateText")).key(&tag));
        }
        Page::new(&endpoint_name(kind)).rows(rows)
    });
}

fn open_endpoint(kind: &'static str, tag: String) {
    rows::push(move || endpoint_page(kind, &tag));
}

fn endpoint_page(kind: &'static str, tag: &str) -> Page {
    let Some(endpoint) = endpoints_of(kind).into_iter().find(|e| endpoint_tag(e) == tag) else {
        return Page::new(tag).rows(vec![Row::empty(&tr("Endpoint disconnected"), "", "info")]);
    };
    let challenge = endpoint.get(if kind == "openvpn" { "challenge" } else { "authChallenge" }).cloned().unwrap_or(Value::Null);
    let has_challenge = challenge.as_object().map(|o| !o.is_empty()).unwrap_or(false);
    let mut rows = Vec::new();
    let tag_s = tag.to_string();
    match kind {
        "tailscale" => {
            rows.extend(detail_rows(vec![
                ("Status", s(&endpoint, "stateText")),
                ("Network", s(&endpoint, "networkName")),
                ("Address", strings(&endpoint["selfPeer"]["tailscaleIPs"]).join("\n")),
            ]));
            let auth = s(&endpoint, "authURL");
            if !auth.is_empty() {
                rows.push(Row::gap());
                rows.push(Row::item(&tr("Sign in")).trailing("open-external", {
                    let a = auth.clone();
                    move || app::act("openUrl", json!({ "url": a }))
                }).on(move |_| app::act("openUrl", json!({ "url": auth }))));
            }
            if truthy(&endpoint["canShareFiles"]) {
                rows.push(Row::section("Taildrop"));
                let t = tag_s.clone();
                let waiting = endpoint.get("waitingFileCount").map(text).filter(|v| !v.is_empty() && v != "null").unwrap_or_else(|| "0".into());
                rows.push(Row::open(&tr("Received files"), None, move || open_inbox(t.clone())).subtitle(&waiting));
                rows.push(Row::open(&tr("Transfers"), None, open_transfers));
            }
            let peers = endpoint_peers(&endpoint);
            rows.push(Row::section(&tr("Exit node")));
            let mut options = vec![String::new()];
            options.extend(peers.iter().filter(|p| truthy(&p["exitNodeOption"])).map(|p| s(p, "stableID")));
            let current = s(&endpoint["exitNode"], "stableID");
            let none = tr("None");
            let peers_l = peers.clone();
            let t = tag_s.clone();
            rows.push(forms::options_row(
                &tr("Exit node"),
                options,
                if current == "null" { "" } else { &current },
                Rc::new(move |id: &str| {
                    if id.is_empty() {
                        none.clone()
                    } else {
                        peers_l.iter().find(|p| s(p, "stableID") == id).map(peer_name).unwrap_or_else(|| id.to_string())
                    }
                }),
                Rc::new(move |id| app::act("tools.setTailscaleExitNode", json!({ "endpoint": t, "peer": id }))),
            ));
            for group in list(&endpoint["userGroups"]) {
                let title = { let d = s(&group, "displayName"); if d.is_empty() || d == "null" { s(&group, "loginName") } else { d } };
                rows.push(Row::section(&title));
                for peer in list(&group["peers"]) {
                    let pid = s(&peer, "id");
                    let t = tag_s.clone();
                    let p = pid.clone();
                    let mut r = Row::item(&peer_name(&peer))
                        .subtitle(&strings(&peer["tailscaleIPs"]).join(" · "))
                        .icon("devices")
                        .chevron()
                        .key(&format!("peer:{pid}"))
                        .on(move |_| open_peer(t.clone(), p.clone()));
                    // The online tint is the leading icon's color (primary / outline).
                    r.data.checked = truthy(&peer["online"]);
                    rows.push(r);
                }
            }
            rows.push(Row::gap());
            let t = tag_s.clone();
            rows.push(Row::item(&tr("Sign out")).key("logout").on(move |_| {
                let t = t.clone();
                nav::confirm_styled("Sign out", None, "Sign out", false, move |ok| {
                    if ok {
                        app::act("tools.tailscaleLogout", json!({ "endpoint": t }));
                    }
                });
            }));
        }
        "usbip" => {
            rows.push(Row::section(&tr("Shared devices")));
            for device in list(&endpoint["devices"]) {
                let product = s(&device, "product");
                let title = if product.is_empty() || product == "null" { s(&device, "busId") } else { product };
                let d = device.clone();
                rows.push(
                    Row::open(&title, None, move || {
                        let d = d.clone();
                        rows::push(move || {
                            let values: Vec<(String, String)> = d.as_object().map(|o| o.iter().map(|(k, v)| (k.clone(), text(v))).collect()).unwrap_or_default();
                            Page::new("USB/IP").rows(values.iter().map(|(k, v)| Row::item(k).subtitle(&or_dash(v.clone()))).collect())
                        });
                    })
                    .subtitle(&s(&device, "state")),
                );
            }
            if !data(|d| d.remote()) {
                rows.push(Row::gap());
                let t = tag_s.clone();
                rows.push(Row::item(&tr("Attach USB device")).trailing("add", { let t = t.clone(); move || usb_picker(t.clone()) }).on(move |_| usb_picker(t.clone())).key("attach"));
                let attached: Vec<Value> = data(|d| d.usb_devices.iter().filter(|u| text(&u["server"]) == tag_s).cloned().collect());
                for device in attached {
                    let id = s(&device, "id");
                    let err = s(&device, "error");
                    rows.push(
                        Row::item(&s(&device, "label"))
                            .subtitle(&format!("{}\n{}", s(&device, "state"), if err == "null" { "" } else { &err }))
                            .key(&format!("usb:{id}"))
                            .trailing("close", move || app::act("tools.detachUsb", json!({ "id": id }))),
                    );
                }
            }
        }
        _ => {
            let mut values = vec![("Status".to_string(), s(&endpoint, "stateText"))];
            let err = s(&endpoint, "error");
            if !err.is_empty() && err != "null" {
                values.push(("Error".into(), err));
            }
            if let Some(info) = endpoint["tunnelInfo"].as_object() {
                for (k, v) in info {
                    values.push((k.clone(), text(v)));
                }
            }
            for (k, v) in values {
                rows.push(Row::item(&tr(&k)).subtitle(&or_dash(v)));
            }
            if has_challenge {
                rows.push(Row::section(&tr("Authentication")));
                let message = { let m = s(&challenge, "message"); if m.is_empty() || m == "null" { s(&challenge, "banner") } else { m } };
                let t = tag_s.clone();
                let c = challenge.clone();
                rows.push(Row::item(&tr("Authenticate")).subtitle(&message).key("auth").on(move |_| open_authentication(kind, t.clone(), c.clone())));
            }
        }
    }
    Page::new(tag).rows(rows)
}

fn open_peer(endpoint: String, id: String) {
    let ping = Rc::new(RefCell::new(false));
    let p = ping.clone();
    let route = rows::push(move || peer_page(&endpoint, &id, &p));
    nav::on_closed(route, move || {
        if *ping.borrow() {
            app::act("tools.stopTailscalePing", json!({}));
        }
    });
}

fn peer_page(endpoint_tag: &str, id: &str, ping: &Rc<RefCell<bool>>) -> Page {
    let peer = endpoints_of("tailscale").into_iter().find(|e| s(e, "endpointTag") == endpoint_tag).and_then(|e| endpoint_peers(&e).into_iter().find(|p| s(p, "id") == id));
    let Some(peer) = peer else {
        return Page::new("Tailscale").rows(vec![Row::empty(&tr("Peer disconnected"), "", "info")]);
    };
    let mut rows = detail_rows(vec![
        ("Address", strings(&peer["tailscaleIPs"]).join("\n")),
        ("OS", s(&peer, "os")),
        ("Status", tr(if truthy(&peer["online"]) { "Online" } else { "Offline" })),
        ("Download", format::bytes(num(&peer["rxBytes"]))),
        ("Upload", format::bytes(num(&peer["txBytes"]))),
    ]);
    rows.push(Row::gap());
    let (e, i, p) = (endpoint_tag.to_string(), id.to_string(), ping.clone());
    rows.push(Row::item("Ping").key("ping").on(move |_| {
        *p.borrow_mut() = true;
        app::act("tools.tailscalePing", json!({ "endpoint": e, "peer": i }));
    }));
    if !strings(&peer["sshHostKeys"]).is_empty() {
        let (e, i, n) = (endpoint_tag.to_string(), id.to_string(), peer_name(&peer));
        rows.push(Row::open("SSH", None, move || crate::pages::terminal::open(Some(e.clone()), Some(i.clone()), Some(n.clone()))));
    }
    if truthy(&peer["canReceiveFiles"]) {
        let (e, i) = (endpoint_tag.to_string(), id.to_string());
        rows.push(Row::item(&tr("Send file")).key("send").on(move |_| {
            app::fetch("tools.sendTaildropFile", json!({ "endpoint": e, "peer": i }), |transfer| {
                if !transfer.is_null() {
                    open_transfers();
                }
            });
        }));
    }
    if *ping.borrow() {
        rows.push(Row::spacer(16));
        let state = data(|d| Value::Object(d.ping.clone()));
        if truthy(&state["isRunning"]) {
            rows.push(Row::progress("", -1.0));
        }
        let mut values = vec![
            ("Latency", format!("{} ms", { let l = s(&state, "latencyMs"); if l.is_empty() || l == "null" { "0".into() } else { l } })),
            ("Path", if truthy(&state["isDirect"]) { tr("Direct") } else { s(&state, "derpRegionCode") }),
        ];
        let err = s(&state, "error");
        if !err.is_empty() && err != "null" {
            values.push(("Error", err));
        }
        rows.extend(detail_rows(values));
    }
    Page::new(&peer_name(&peer)).rows(rows)
}

fn usb_picker(server: String) {
    let st: Rc<RefCell<(Loaded, bool)>> = Rc::new(RefCell::new((Loaded::default(), true)));
    let load: Rc<dyn Fn()> = {
        let st = st.clone();
        Rc::new(move || {
            st.borrow_mut().1 = true;
            let a = st.clone();
            app::call("tools.usbDevices", json!({}), move |r| {
                let mut s = a.borrow_mut();
                s.1 = false;
                match r {
                    Ok(v) => {
                        s.0.items = Some(list(&v));
                        s.0.error = None;
                    }
                    Err(e) => s.0.error = Some(format::clean_error(&e)),
                }
                drop(s);
                mark(Mark::ROWS);
            });
        })
    };
    load();
    rows::form(move || {
        let s = st.borrow();
        let mut rows = Vec::new();
        if let Some(e) = &s.0.error {
            rows.extend(error_row(e, Some(load.clone())));
        }
        if s.0.items.as_ref().map(Vec::is_empty).unwrap_or(false) {
            rows.push(Row::empty(&tr("No USB devices"), "", "info"));
        }
        for device in s.0.items.clone().unwrap_or_default() {
            let id = s_of(&device, "id");
            let srv = server.clone();
            rows.push(Row::item(&s_of(&device, "name")).key(&id.clone()).on(move |_| {
                let route = nav::top().map(|r| r.id);
                app::act_then("tools.attachUsb", json!({ "server": srv, "device": id }), move |ok| {
                    if ok {
                        if let Some(r) = route {
                            nav::close(r);
                        }
                    }
                });
            }));
        }
        Page::new(&tr("Attach USB device")).loading(s.1).rows(rows)
    });
}

// ---------------------------------------------------------------------------
// Taildrop
// ---------------------------------------------------------------------------

fn open_inbox(endpoint: String) {
    app::act("tools.subscribeTaildrop", json!({ "endpoint": endpoint }));
    let route = rows::push(move || {
        let state = data(|d| Value::Object(d.inbox.clone()));
        let mut rows = Vec::new();
        let files = list(&state["files"]);
        let receiving = list(&state["receiving"]);
        if files.is_empty() && receiving.is_empty() {
            rows.push(Row::empty(&tr("No received files"), "", "info"));
        }
        for file in receiving {
            let (e, sender, name) = (endpoint.clone(), s(&file, "senderID"), s(&file, "name"));
            rows.push(
                Row::item(&name)
                    .subtitle(&format!("{} / {}", format::bytes(num(&file["receivedBytes"])), format::bytes(num(&file["size"]))))
                    .key(&format!("r:{name}"))
                    .trailing("close", move || app::act("tools.cancelTaildropReceive", json!({ "endpoint": e, "sender": sender, "name": name }))),
            );
        }
        for file in files {
            let name = s(&file, "name");
            let (e1, n1, e2, n2) = (endpoint.clone(), name.clone(), endpoint.clone(), name.clone());
            rows.push(
                Row::item(&name)
                    .subtitle(&format!("{} · {}", format::bytes(num(&file["size"])), s(&file, "senderName")))
                    .key(&format!("f:{name}"))
                    .on(move |_| app::act("tools.saveTaildropFile", json!({ "endpoint": e1, "name": n1 })))
                    .trailing("delete", move || {
                        let (e, n) = (e2.clone(), n2.clone());
                        nav::confirm("Delete", Some(&n2.clone()), "Delete", move |ok| {
                            if ok {
                                app::act("tools.deleteTaildropFile", json!({ "endpoint": e, "name": n }));
                            }
                        });
                    }),
            );
        }
        Page::new(&tr("Received files")).rows(rows)
    });
    nav::on_closed(route, || app::act("tools.unsubscribeTaildrop", json!({})));
}

fn open_transfers() {
    rows::push(|| {
        let transfers = data(|d| d.transfers.clone());
        let mut rows = Vec::new();
        if transfers.is_empty() {
            rows.push(Row::empty(&tr("No transfers"), "", "info"));
        }
        for transfer in transfers {
            let tid = long(&transfer["id"]).unwrap_or(0);
            rows.push(Row::section(&s(&transfer, "peerName")));
            for file in list(&transfer["files"]) {
                let size = num(&file["size"]);
                let sent = num(&file["sentBytes"]);
                let progress = if truthy(&file["completed"]) { 1.0 } else if size > 0.0 { (sent / size).clamp(0.0, 1.0) as f32 } else { -1.0 };
                rows.push(Row::item(&s(&file, "name")).subtitle(&format!("{} / {}", format::bytes(sent), format::bytes(size))).key(&format!("{tid}:{}", s(&file, "name"))));
                rows.push(Row::progress("", progress));
            }
            if !transfer["error"].is_null() {
                rows.push(Row::item(&tr("Error")).subtitle(&s(&transfer, "error")));
            }
            let finished = truthy(&transfer["finished"]);
            rows.push(Row::item(&tr(if finished { "Done" } else { "Cancel" })).key(&format!("{tid}:done")).on(move |_| {
                app::act(if finished { "tools.dismissTaildropSend" } else { "tools.cancelTaildropSend" }, json!({ "id": tid }));
            }));
        }
        Page::new(&tr("Transfers")).rows(rows)
    });
}

/// SharePage: Android ACTION_SEND content stays owned by the native host until sent or discarded.
pub fn open_share(token: &str) {
    let token = token.to_string();
    let st = Rc::new(RefCell::new((false, false)));
    let a = st.clone();
    let t = token.clone();
    let route = rows::push(move || {
        let (sending, _) = *a.borrow();
        let (running, endpoints) = data(|d| (d.running(), d.tool_state.get("tailscale").map(list).unwrap_or_default()));
        let endpoints: Vec<Value> = endpoints.into_iter().filter(|e| truthy(&e["canShareFiles"])).collect();
        let mut rows = Vec::new();
        if !running {
            rows.push(Row::empty(&tr("Service not started"), "", "info").value(&tr("Start")).on(|_| app::act("startService", json!({}))));
        }
        if running && endpoints.is_empty() {
            rows.push(Row::empty(&tr("No recipients"), "", "info"));
        }
        for endpoint in endpoints {
            let self_id = s(&endpoint["selfPeer"], "stableID");
            let etag = s(&endpoint, "endpointTag");
            rows.push(Row::section(&etag));
            for peer in endpoint_peers(&endpoint).into_iter().filter(|p| truthy(&p["canReceiveFiles"]) && s(p, "stableID") != self_id) {
                let online = truthy(&peer["online"]);
                let (b, tok, e, pid) = (a.clone(), t.clone(), etag.clone(), s(&peer, "id"));
                rows.push(
                    Row::item(&peer_name(&peer))
                        .subtitle(&tr(if online { "Online" } else { "Offline" }))
                        .key(&format!("{etag}:{pid}"))
                        .on(move |_| {
                            b.borrow_mut().0 = true;
                            mark(Mark::ROWS);
                            let c = b.clone();
                            let route = nav::top().map(|r| r.id);
                            app::act_then("tools.sendShare", json!({ "token": tok, "endpoint": e, "peer": pid }), move |ok| {
                                *c.borrow_mut() = (false, ok);
                                if ok {
                                    if let Some(r) = route {
                                        nav::close(r);
                                    }
                                    open_transfers();
                                }
                                mark(Mark::ROWS);
                            });
                        })
                        .enabled(!sending && online),
                );
            }
        }
        Page::new(&tr("Send with Taildrop")).loading(sending).rows(rows)
    });
    nav::on_closed(route, move || {
        if !st.borrow().1 {
            app::act("tools.discardShare", json!({ "token": token }));
        }
    });
}

// ---------------------------------------------------------------------------
// Authentication
// ---------------------------------------------------------------------------

thread_local! {
    /// The open AuthenticationPage per challenge id, closed when the sign-in page completes.
    static AUTH: RefCell<HashMap<String, (i32, Rc<RefCell<bool>>)>> = RefCell::new(HashMap::new());
}

struct Auth {
    texts: HashMap<String, String>,
    busy: bool,
    complete: Rc<RefCell<bool>>,
}

fn open_authentication(kind: &'static str, endpoint: String, challenge: Value) {
    let id = s(&challenge, "id");
    let fields = list(&challenge["form"]["fields"]);
    let mut texts = HashMap::new();
    if kind == "openconnect" {
        for f in &fields {
            texts.insert(s(f, "submissionKey"), f.get("value").and_then(Value::as_str).unwrap_or("").to_string());
        }
    } else {
        for key in ["username", "password", "secret"] {
            texts.insert(key.to_string(), if key == "username" { challenge.get(key).and_then(Value::as_str).unwrap_or("").to_string() } else { String::new() });
        }
    }
    let complete = Rc::new(RefCell::new(false));
    let st = Rc::new(RefCell::new(Auth { texts, busy: false, complete: complete.clone() }));
    let (e, c, i) = (endpoint.clone(), challenge.clone(), id.clone());
    let route = rows::push(move || auth_page(kind, &e, &c, &i, &st));
    AUTH.with(|a| a.borrow_mut().insert(id.clone(), (route, complete.clone())));
    let cid = id.clone();
    nav::on_closed(route, move || {
        AUTH.with(|a| a.borrow_mut().remove(&cid));
        if !*complete.borrow() {
            let method = if kind == "openconnect" { "tools.cancelOpenConnect" } else { "tools.cancelOpenVPN" };
            app::act(method, json!({ "endpoint": endpoint, "challenge": id }));
        }
    });
}

fn auth_page(kind: &'static str, endpoint: &str, challenge: &Value, id: &str, st: &Rc<RefCell<Auth>>) -> Page {
    let a = st.borrow();
    let mut rows = Vec::new();
    for key in ["banner", "message", "error", "previousError"] {
        let v = s(challenge, key);
        if !v.is_empty() && v != "null" {
            rows.push(Row::text(&v).checked(true));
        }
    }
    let field = |rows: &mut Vec<Row>, key: &str, label: &str, secret: bool| {
        let b = st.clone();
        let k = key.to_string();
        rows.push(Row::input(label, a.texts.get(key).map(String::as_str).unwrap_or(""), "", move |v| {
            b.borrow_mut().texts.insert(k.clone(), v);
        })
        .key(key)
        .secret(secret));
        rows.push(Row::spacer(16));
    };
    if kind == "openconnect" {
        if challenge["browser"].as_object().map(|o| !o.is_empty()).unwrap_or(false) {
            let (e, i) = (endpoint.to_string(), id.to_string());
            rows.push(Row::item(&tr("Open sign-in page")).key("browser").on(move |_| {
                // AuthBrowser: the native WebView Activity; its result arrives as browserComplete.
                app::act("openConnectBrowser", json!({ "endpoint": e, "challenge": i, "title": tr("Sign in"), "dark": crate::theme_apply::is_dark() }));
            }));
        }
        for f in list(&challenge["form"]["fields"]) {
            let key = s(&f, "submissionKey");
            let label = { let l = s(&f, "label"); if l.is_empty() || l == "null" { s(&f, "name") } else { l } };
            if s(&f, "kind") == "select" {
                let options = list(&f["options"]);
                let values: Vec<String> = options.iter().map(|o| s(o, "value")).collect();
                let b = st.clone();
                let k = key.clone();
                rows.push(forms::options_row(
                    &label,
                    values,
                    a.texts.get(&key).map(String::as_str).unwrap_or(""),
                    Rc::new(move |v: &str| options.iter().find(|o| s(o, "value") == v).map(|o| s(o, "label")).unwrap_or_else(|| v.to_string())),
                    Rc::new(move |v| {
                        b.borrow_mut().texts.insert(k.clone(), v);
                        mark(Mark::ROWS);
                    }),
                ));
            } else {
                field(&mut rows, &key, &label, s(&f, "kind") == "password");
            }
        }
    } else if s(challenge, "kind") == "open-url" {
        let url = s(challenge, "url");
        let u = url.clone();
        rows.push(Row::item(&tr("Open sign-in page")).subtitle(&url).subtitle_lines(1).key("url").on(move |_| app::act("openUrl", json!({ "url": u }))));
    } else {
        if s(challenge, "kind") == "credentials" {
            field(&mut rows, "username", &tr("Username"), false);
            field(&mut rows, "password", &tr("Password"), true);
        }
        let secret_message = s(challenge, "secretMessage");
        let has_secret_message = !secret_message.is_empty() && secret_message != "null";
        if s(challenge, "kind") == "secret" || has_secret_message {
            let label = if has_secret_message { secret_message } else { tr("Verification code") };
            field(&mut rows, "secret", &label, !truthy(&challenge["echo"]));
        }
    }
    let can_submit = if kind == "openconnect" { challenge["form"].as_object().map(|o| !o.is_empty()).unwrap_or(false) } else { s(challenge, "kind") != "open-url" };
    let busy = a.busy;
    drop(a);
    if can_submit {
        let (b, e, i) = (st.clone(), endpoint.to_string(), id.to_string());
        let mut submit = Row::button(&tr("Submit"), 0, move || {
            let values = {
                let mut x = b.borrow_mut();
                if x.busy {
                    return;
                }
                x.busy = true;
                x.texts.clone()
            };
            mark(Mark::ROWS);
            let (method, args) = if kind == "openconnect" {
                ("tools.submitOpenConnect", json!({ "endpoint": e, "challenge": i, "values": values }))
            } else {
                ("tools.submitOpenVPN", json!({ "endpoint": e, "challenge": i, "username": values.get("username"), "password": values.get("password"), "secret": values.get("secret") }))
            };
            let c = b.clone();
            let route = nav::top().map(|r| r.id);
            app::act_then(method, args, move |ok| {
                c.borrow_mut().busy = false;
                if ok {
                    *c.borrow().complete.borrow_mut() = true;
                    if let Some(r) = route {
                        nav::close(r);
                    }
                }
                mark(Mark::ROWS);
            });
        })
        .key("submit")
        .enabled(!busy);
        submit.data.checked = true;
        rows.push(submit);
    }
    Page::new(&tr("Authentication")).loading(busy).rows(rows)
}

/// The sign-in page finished (BoxController.browserEvents): close its AuthenticationPage on success.
pub fn browser_complete(value: &Value) {
    // The native sign-in page closes with its challenge.
    app::act("closeBrowser", json!({}));
    let id = s(value, "id");
    if !truthy(&value["success"]) {
        return;
    }
    if let Some((route, complete)) = AUTH.with(|a| a.borrow().get(&id).cloned()) {
        *complete.borrow_mut() = true;
        nav::close(route);
    }
}

// ---------------------------------------------------------------------------
// Dispatch
// ---------------------------------------------------------------------------

pub fn project(_w: &AppWindow, _m: Mark) {}
pub fn opened(_kind: &str, _arg: &str, _id: i32) {}
pub fn closed(_kind: &str, _arg: &str, _id: i32) {}

pub fn install(_w: &AppWindow) {
    rows::set_tools(tools_page);
}

/// MitmCertificatePage (pages/RequestsPage.kt).
pub fn open_mitm_certificate() {
    crate::pages::requests::open_mitm_certificate();
}

