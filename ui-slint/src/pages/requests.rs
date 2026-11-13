//! Requests and logs (pages/RequestsPage.kt, pages/ToolsPage.kt's
//! ConnectionsFeed/ConnectionDetail/LogsPage, ui/Records.kt): the recent,
//! running and captured feeds, connection and request details, capture
//! settings, applications, domains and logs, and the MITM certificate.
//! Rust-built rows with record cards.

use std::cell::{Cell, RefCell};
use std::collections::BTreeSet;
use std::rc::Rc;
use std::time::Duration;

use serde_json::{json, Value};

use crate::app::{self, data, long, num, obj, rows as list, text, truthy};
use crate::format;
use crate::forms;
use crate::i18n::tr;
use crate::nav::{self, MenuEntry};
use crate::pages::{mark, Mark};
use crate::records::{same, Facet, Filters, Grouper};
use crate::rows::{self, Bar, Page, Row};
use crate::AppWindow;

const LOCAL_ONLY: &str = "HTTP debugging is only available for the local service";

fn s(v: &Value, k: &str) -> String {
    match v.get(k) {
        None | Some(Value::Null) => String::new(),
        Some(x) => text(x),
    }
}

fn or_dash(v: String) -> String {
    if v.is_empty() {
        "—".into()
    } else {
        v
    }
}

fn pinned() -> bool {
    !nav::mobile()
}

fn local_only(title: &str) -> Page {
    Page::new(&tr(title)).rows(vec![Row::empty(&tr(LOCAL_ONLY), "", "info")])
}

/// RecordDetailRow: a title and its value, stacked or side by side; a tap copies the value.
fn detail_row(title: &str, value: &str, stacked: bool) -> Row {
    let v = value.to_string();
    let r = Row::item(title).on(move |_| {
        app::copy_text(&v);
        app::message("Copied");
    });
    if stacked {
        r.subtitle(&or_dash(value.to_string())).lines(1)
    } else {
        r.value(&or_dash(value.to_string()))
    }
}

/// rememberCapturePoll: fetches every two seconds while `alive` says so.
fn poll(alive: Rc<dyn Fn() -> bool>, fetch: Rc<dyn Fn()>) -> Rc<slint::Timer> {
    let timer = Rc::new(slint::Timer::default());
    fetch();
    let f = fetch.clone();
    timer.start(slint::TimerMode::Repeated, Duration::from_secs(2), move || {
        if alive() && !data(|d| d.remote()) {
            f();
        }
    });
    timer
}

// ---------------------------------------------------------------------------
// Connections
// ---------------------------------------------------------------------------

fn connection_app(c: &Value) -> String {
    let p = &c["process"];
    if let Some(pkgs) = p["packages"].as_array() {
        if let Some(first) = pkgs.iter().filter_map(Value::as_str).find(|s| !s.is_empty()) {
            return first.to_string();
        }
    }
    p["path"].as_str().map(|path| path.rsplit('/').next().unwrap_or("").to_string()).unwrap_or_default()
}

fn connection_chain(c: &Value) -> Vec<String> {
    let chain: Vec<String> = c["chain"].as_array().map(|a| a.iter().filter_map(Value::as_str).filter(|s| !s.is_empty()).map(str::to_string).collect()).unwrap_or_default();
    if chain.is_empty() {
        c["outbound"].as_str().filter(|s| !s.is_empty()).map(|s| vec![s.to_string()]).unwrap_or_default()
    } else {
        chain
    }
}

fn network(c: &Value) -> String {
    s(c, "network").to_lowercase()
}

static CONNECTION_FACETS: [Facet; 4] = [
    Facet { kind: "Network", values: |c| vec![network(c)], offered: true, order: &["tcp", "udp"], label: |v| v.to_uppercase() },
    Facet { kind: "Rule", values: |c| vec![s(c, "rule")], offered: true, order: &[], label: same },
    Facet { kind: "App", values: |c| vec![connection_app(c)], offered: false, order: &[], label: same },
    Facet { kind: "Proxy", values: connection_chain, offered: false, order: &[], label: same },
];

static CONNECTION_GROUPERS: [Grouper; 4] = [
    Grouper { id: "app", label: "App", key: connection_app },
    Grouper { id: "host", label: "Host", key: |c| { let d = s(c, "displayDestination"); if d.is_empty() { s(c, "destination") } else { d } } },
    Grouper { id: "rule", label: "Rule", key: |c| s(c, "rule") },
    Grouper { id: "exit", label: "Exit", key: |c| connection_chain(c).last().cloned().unwrap_or_default() },
];

fn matches_words(text: &str, query: &str) -> bool {
    let hay = text.to_lowercase();
    query.to_lowercase().split(' ').all(|w| hay.contains(w))
}

struct Feed {
    recent: bool,
    query: String,
    filter: &'static str,
    sort: &'static str,
    filters: Filters,
}

type FeedRef = Rc<RefCell<Feed>>;

fn new_feed(recent: bool) -> FeedRef {
    let id = if recent { "recentRequests" } else { "connections" };
    Rc::new(RefCell::new(Feed { recent, query: String::new(), filter: if recent { "Closed" } else { "Active" }, sort: if recent { "Created" } else { "Speed" }, filters: Filters::new(id, &CONNECTION_GROUPERS) }))
}

fn connection_record(c: &Value, feed: &FeedRef) -> Row {
    let closed = num(&c["closedAt"]) > 0.0;
    let running = data(|d| d.running());
    let net = network(c);
    let app_name = connection_app(c);
    let mut labels = vec![net.to_uppercase()];
    if !app_name.is_empty() {
        labels.push(app_name.clone());
    }
    if closed {
        labels.push(tr("Closed"));
    }
    let title = { let d = s(c, "displayDestination"); if d.is_empty() { or_dash(s(c, "destination")) } else { d } };
    let rule = s(c, "rule");
    let chain = connection_chain(c);
    let bytes_line = format!(
        "↑ {} · {}/s    ↓ {} · {}/s",
        format::bytes(num(&c["uploadTotal"])),
        format::bytes(num(&c["upload"])),
        format::bytes(num(&c["downloadTotal"])),
        format::bytes(num(&c["download"]))
    );
    let id = s(c, "id");
    let cc = c.clone();
    let mut row = Row::record(&format::record_time(long(&c["createdAt"]).unwrap_or(0)), labels, &title, move || open_connection(cc.clone()))
        .chain(&rule, if chain.is_empty() { vec!["—".into()] } else { chain.clone() })
        .subtitle(&format!("{}\n{bytes_line}", or_dash(s(c, "source"))))
        .key(&id);
    if !closed && running {
        let id2 = id.clone();
        row = row.trailing("block", move || app::act("closeConnection", json!({ "id": id2 })));
    }
    let f = feed.clone();
    let has_app = !app_name.is_empty();
    row.on_tap(move |tap| {
        let mut feed = f.borrow_mut();
        match tap.as_str() {
            "l0" => feed.filters.add(&CONNECTION_FACETS, "Network", net.clone()),
            "l1" if has_app => feed.filters.add(&CONNECTION_FACETS, "App", app_name.clone()),
            "r" => feed.filters.add(&CONNECTION_FACETS, "Rule", rule.clone()),
            t if t.starts_with('c') => {
                if let Some(o) = t[1..].parse::<usize>().ok().and_then(|i| chain.get(i)) {
                    feed.filters.add(&CONNECTION_FACETS, "Proxy", o.clone());
                }
            }
            _ => {}
        }
    })
}

fn connections_page(feed: &FeedRef, title: String, title_menu: Option<Rc<dyn Fn() -> Vec<MenuEntry>>>) -> Page {
    let (source, running) = data(|d| (d.connections.clone(), d.running()));
    let mut f = feed.borrow_mut();
    let blank = f.query.trim().is_empty();
    let searched: Vec<Value> = source
        .into_iter()
        .filter(|c| {
            let closed = num(&c["closedAt"]) > 0.0;
            if (f.filter == "Active" && closed) || (f.filter == "Closed" && !closed) {
                return false;
            }
            if blank {
                return true;
            }
            let hay = ["destination", "domain", "outbound", "network", "rule", "source", "process"].iter().map(|k| c.get(*k).map(|v| if v.is_null() { "null".into() } else { text(v) }).unwrap_or_else(|| "null".into())).collect::<Vec<_>>().join(" ");
            matches_words(&hay, &f.query)
        })
        .collect();
    f.filters.offer(&CONNECTION_FACETS, &searched);
    let key = match f.sort {
        "Created" => Some("createdAt"),
        "Upload" => Some("uploadTotal"),
        "Download" => Some("downloadTotal"),
        _ => None,
    };
    let rank = |c: &Value| match key {
        None => num(&c["upload"]) + num(&c["download"]),
        Some(k) => num(&c[k]),
    };
    let mut shown = f.filters.apply(&CONNECTION_FACETS, searched);
    shown.sort_by(|a, b| rank(b).partial_cmp(&rank(a)).unwrap_or(std::cmp::Ordering::Equal));
    let searching = !blank || f.filters.is_filtering();
    let fa = feed.clone();
    let toggle: Rc<dyn Fn(String)> = Rc::new(move |k| {
        let mut x = fa.borrow_mut();
        if !x.filters.open.remove(&k) {
            x.filters.open.insert(k);
        }
    });
    let fb = feed.clone();
    let rows_list = f.filters.rows(&CONNECTION_GROUPERS, &shown, toggle, &|c| connection_record(c, &fb));
    let keywords = f.filters.keywords(&CONNECTION_FACETS, pinned());
    let recent = f.recent;
    drop(f);
    let mut bar = Vec::new();
    if !recent {
        bar.push(Bar::new("clear-all", "Close all connections", || {
            nav::confirm_styled("Close all active connections?", None, "Close", false, |ok| {
                if ok {
                    app::act("closeConnections", json!({}));
                }
            });
        }).when(running));
    }
    let (fc, fd, fe) = (feed.clone(), feed.clone(), feed.clone());
    let mut page = Page::new(&title)
        .bar(bar)
        .rows(rows_list)
        .search(move |q| fc.borrow_mut().query = q)
        .keywords(keywords, move |k| fd.borrow_mut().filters.keyword(&CONNECTION_FACETS, &k))
        .menu(move || connections_menu(&fe));
    if shown.is_empty() {
        page = page.empty(&tr(if searching { "No results" } else { "No connections" }), "", "connections");
    }
    if let Some(m) = title_menu {
        page.title_menu = Some(m);
    }
    page
}

fn connections_menu(feed: &FeedRef) -> Vec<MenuEntry> {
    let f = feed.borrow();
    let mut items = Vec::new();
    if f.recent {
        for item in ["Closed", "All"] {
            let a = feed.clone();
            let mut m = MenuEntry::plain(&tr(item), move || {
                a.borrow_mut().filter = item;
                mark(Mark::ROWS);
            })
            .checked(f.filter == item);
            if item == "Closed" {
                m = m.section(&tr("Connections"));
            }
            items.push(m);
        }
    }
    for item in ["Speed", "Created", "Upload", "Download"] {
        let a = feed.clone();
        items.push(
            MenuEntry::act(item, "sort", move || {
                a.borrow_mut().sort = item;
                mark(Mark::ROWS);
            })
            .checked(f.sort == item),
        );
    }
    let a = feed.clone();
    items.extend(f.filters.menu_items(&CONNECTION_FACETS, &CONNECTION_GROUPERS, pinned(), Rc::new(move |g: &dyn Fn(&mut Filters)| g(&mut a.borrow_mut().filters))));
    items
}

fn open_connection(initial: Value) {
    let cached = Rc::new(RefCell::new(initial.clone()));
    let id = s(&initial, "id");
    rows::push(move || {
        let live = data(|d| d.connections.iter().find(|c| s(c, "id") == id).cloned());
        if let Some(l) = &live {
            *cached.borrow_mut() = l.clone();
        }
        let present = live.is_some();
        let c = cached.borrow().clone();
        let closed_at = num(&c["closedAt"]);
        let mut values: Vec<(&str, String)> = vec![
            ("Destination", s(&c, "displayDestination")),
            ("Source", s(&c, "source")),
            ("Network", s(&c, "network")),
            ("Protocol", s(&c, "protocol")),
            ("Inbound", s(&c, "inbound")),
            ("Outbound", s(&c, "outbound")),
            ("Rule matched", s(&c, "rule")),
            ("Chain", c["chain"].as_array().map(|a| a.iter().map(text).collect::<Vec<_>>().join(" → ")).unwrap_or_default()),
            ("Created", format::local_date(long(&c["createdAt"]).unwrap_or(0))),
        ];
        if closed_at > 0.0 {
            values.push(("Closed", format::local_date(closed_at as i64)));
        }
        values.push(("Upload", format::bytes(num(&c["uploadTotal"]))));
        values.push(("Download", format::bytes(num(&c["downloadTotal"]))));
        if let Some(p) = c["process"].as_object() {
            values.push(("Process", p.iter().map(|(k, v)| format!("{k}: {}", text(v))).collect::<Vec<_>>().join("\n")));
        }
        let copy_all = values.iter().map(|(k, v)| format!("{}: {v}", tr(k))).collect::<Vec<_>>().join("\n");
        let rows_list: Vec<Row> = values.iter().map(|(k, v)| detail_row(&tr(k), v, false)).collect();
        let running = data(|d| d.running());
        let cid = s(&c, "id");
        Page::new(&tr("Details"))
            .bar(vec![
                Bar::new("copy", "Copy", move || {
                    app::copy_text(&copy_all);
                    app::message("Copied");
                }),
                Bar::new("close", "Close this connection", move || app::act("closeConnection", json!({ "id": cid }))).when(running && present && closed_at == 0.0),
            ])
            .rows(rows_list)
    });
}

// ---------------------------------------------------------------------------
// The Requests page (recent, running, captured)
// ---------------------------------------------------------------------------

const FEEDS: [&str; 3] = ["recent", "live", "captured"];

fn feed_label(feed: &str) -> &'static str {
    match feed {
        "recent" => "Recent requests",
        "live" => "Running",
        _ => "Captured",
    }
}

fn capture_available() -> bool {
    data(|d| d.selected_profile().map(|p| p.get("resolvedCore").and_then(Value::as_str).unwrap_or("sing-box") == "sing-box").unwrap_or(true))
}

struct Requests {
    feed: String,
    initial: bool,
    recent: FeedRef,
    live: FeedRef,
    captured: Rc<RefCell<Captured>>,
    alive: Rc<dyn Fn() -> bool>,
}

fn requests_builder(initial_feed: Option<String>, alive: Rc<dyn Fn() -> bool>) -> impl Fn() -> Page {
    let feed = initial_feed.clone().unwrap_or_else(|| data(|d| d.preferences.get("requestFeed").and_then(Value::as_str).unwrap_or("recent").to_string()));
    let st = Rc::new(RefCell::new(Requests {
        feed,
        initial: initial_feed.is_some(),
        recent: new_feed(true),
        live: new_feed(false),
        captured: Rc::new(RefCell::new(Captured::new())),
        alive: alive.clone(),
    }));
    move || {
        let r = st.borrow();
        let available = capture_available();
        let shown = if !FEEDS.contains(&r.feed.as_str()) || (r.feed == "captured" && !available) { "recent".to_string() } else { r.feed.clone() };
        let a = st.clone();
        let sh = shown.clone();
        let title_menu: Rc<dyn Fn() -> Vec<MenuEntry>> = Rc::new(move || {
            FEEDS
                .iter()
                .map(|item| {
                    let unavailable = *item == "captured" && !capture_available();
                    let b = a.clone();
                    let mut label = tr(feed_label(item));
                    if unavailable {
                        label = format!("{label}\n{}", tr("HTTP capture needs the sing-box core"));
                    }
                    let m = MenuEntry::plain(&label, move || {
                        let mut r = b.borrow_mut();
                        if r.feed != *item {
                            r.feed = item.to_string();
                            if !r.initial {
                                app::set_preference("requestFeed", json!(item));
                            }
                        }
                        drop(r);
                        mark(Mark::ROWS);
                    })
                    .checked(*item == sh);
                    if unavailable {
                        m.when(false)
                    } else {
                        m
                    }
                })
                .collect()
        });
        let title = tr(feed_label(&shown));
        match shown.as_str() {
            "captured" => captured_page(&r.captured, title, Some(title_menu), r.alive.clone()),
            "live" => connections_page(&r.live, title, Some(title_menu)),
            _ => connections_page(&r.recent, title, Some(title_menu)),
        }
    }
}

// ---------------------------------------------------------------------------
// Captured requests
// ---------------------------------------------------------------------------

fn capture_app(item: &Value) -> String {
    item["packages"].as_array().and_then(|a| a.iter().filter_map(Value::as_str).find(|s| !s.is_empty())).unwrap_or("").to_string()
}

fn capture_mitm(item: &Value) -> String {
    if truthy(&item["domainOnly"]) {
        "domain".into()
    } else if item["url"].as_str().map(|u| u.to_lowercase().starts_with("https://")).unwrap_or(false) {
        "mitm".into()
    } else {
        "http".into()
    }
}

fn capture_host(item: &Value) -> String {
    let url = s(item, "url");
    let after = url.split_once("://").map(|x| x.1).unwrap_or(&url);
    let host = after.split('/').next().unwrap_or("").split('?').next().unwrap_or("");
    host.rsplit('@').next().unwrap_or("").to_string()
}

fn mitm_label(v: &str) -> String {
    match v {
        "mitm" => "MITM".into(),
        "http" => "HTTP".into(),
        _ => tr("Domain only"),
    }
}

static CAPTURE_FACETS: [Facet; 2] = [
    Facet { kind: "MITM", values: |i| vec![capture_mitm(i)], offered: true, order: &["mitm", "http", "domain"], label: mitm_label },
    Facet { kind: "App", values: |i| i["packages"].as_array().map(|a| a.iter().filter_map(Value::as_str).filter(|s| !s.is_empty()).map(str::to_string).collect()).unwrap_or_default(), offered: false, order: &[], label: same },
];

static CAPTURE_GROUPERS: [Grouper; 2] = [Grouper { id: "app", label: "App", key: capture_app }, Grouper { id: "host", label: "Host", key: capture_host }];

struct Captured {
    query: String,
    limit: i64,
    busy: bool,
    snapshot: Option<Value>,
    error: Option<String>,
    filters: Filters,
    timer: Option<Rc<slint::Timer>>,
    generation: u64,
}

impl Captured {
    fn new() -> Captured {
        Captured { query: String::new(), limit: 50, busy: false, snapshot: None, error: None, filters: Filters::new("captured", &CAPTURE_GROUPERS), timer: None, generation: 0 }
    }
}

fn captured_fetch(st: &Rc<RefCell<Captured>>) {
    let (limit, query, generation) = {
        let s = st.borrow();
        (s.limit, s.query.clone(), s.generation)
    };
    let a = st.clone();
    app::call("capture.snapshot", json!({ "limit": limit, "query": query }), move |r| {
        let mut s = a.borrow_mut();
        if s.generation != generation {
            return;
        }
        match r {
            Ok(v) => {
                s.snapshot = Some(v);
                s.error = None;
            }
            Err(e) => s.error = Some(format::clean_error(&e)),
        }
        drop(s);
        mark(Mark::ROWS);
    });
}

fn captured_refresh(st: &Rc<RefCell<Captured>>) {
    st.borrow_mut().generation += 1;
    captured_fetch(st);
}

fn captured_act(st: &Rc<RefCell<Captured>>, method: &'static str, args: Value) {
    if std::mem::replace(&mut st.borrow_mut().busy, true) {
        return;
    }
    mark(Mark::ROWS);
    let a = st.clone();
    app::act_then(method, args, move |_| {
        a.borrow_mut().busy = false;
        captured_refresh(&a);
    });
}

fn captured_record(item: &Value, st: &Rc<RefCell<Captured>>) -> Row {
    let status = long(&item["status"]).unwrap_or(0);
    let mitm = capture_mitm(item);
    let mut labels = vec![s(item, "method")];
    labels.push(if truthy(&item["domainOnly"]) {
        tr("Domain only")
    } else if status > 0 {
        status.to_string()
    } else {
        tr(if truthy(&item["complete"]) { "Error" } else { "Active" })
    });
    if mitm != "domain" {
        labels.push(mitm_label(&mitm));
    }
    let app_name = capture_app(item);
    if !app_name.is_empty() {
        labels.push(app_name.clone());
    }
    let id = s(item, "id");
    let i2 = id.clone();
    let packages = item["packages"].as_array().map(|a| a.iter().map(text).collect::<Vec<_>>().join(", ")).unwrap_or_default();
    let mut lines = vec![format!("{} · {} ms · {}", s(item, "protocol"), long(&item["durationMs"]).unwrap_or(0), format::bytes(num(&item["bytes"])))];
    if !packages.is_empty() {
        lines.push(packages);
    }
    let error = s(item, "error");
    let f = st.clone();
    Row::record(&format::record_time(long(&item["startedAt"]).unwrap_or(0)), labels, &s(item, "url"), move || open_request(i2.clone()))
        .subtitle(&lines.join("\n"))
        .hint(&error)
        .key(&id)
        .on_tap(move |tap| {
            let mut c = f.borrow_mut();
            let domain = mitm == "domain";
            match tap.as_str() {
                "l1" if domain => c.filters.add(&CAPTURE_FACETS, "MITM", mitm.clone()),
                "l2" if !domain => c.filters.add(&CAPTURE_FACETS, "MITM", mitm.clone()),
                t if (t == "l3" && !domain) || (t == "l2" && domain) => {
                    if !app_name.is_empty() {
                        c.filters.add(&CAPTURE_FACETS, "App", app_name.clone());
                    }
                }
                _ => {}
            }
        })
}

fn captured_page(st: &Rc<RefCell<Captured>>, title: String, title_menu: Option<Rc<dyn Fn() -> Vec<MenuEntry>>>, alive: Rc<dyn Fn() -> bool>) -> Page {
    if data(|d| d.remote()) {
        let mut p = local_only(&title);
        p.title = title;
        p.title_menu = title_menu;
        return p;
    }
    if st.borrow().timer.is_none() {
        let a = st.clone();
        let al = alive.clone();
        let timer = poll(al, Rc::new(move || captured_fetch(&a)));
        st.borrow_mut().timer = Some(timer);
    }
    let (running, transitioning) = data(|d| (d.running(), d.transitioning()));
    let mut c = st.borrow_mut();
    let snapshot = c.snapshot.clone();
    let state = snapshot.as_ref().map(|v| v["state"].clone()).unwrap_or(Value::Null);
    let page_v = snapshot.as_ref().map(|v| v["page"].clone()).unwrap_or(Value::Null);
    let active = truthy(&state["active"]);
    let entries = list(&page_v["items"]);
    c.filters.offer(&CAPTURE_FACETS, &entries);
    let shown = c.filters.apply(&CAPTURE_FACETS, entries.clone());
    let busy = c.busy;
    let mut rows_list = Vec::new();
    let rewrite = truthy(&state["rewriteActive"]);
    let a = st.clone();
    rows_list.push(
        Row::switch(&tr("Capture requests"), active, move |_| captured_act(&a, if active { "stopCapture" } else { "startCapture" }, json!({})))
            .subtitle(&tr(if active { "Capturing new connections" } else { "Capture stopped" }))
            .key("capture")
            .enabled(!(busy || snapshot.is_none() || !running || transitioning)),
    );
    if rewrite {
        rows_list.push(Row::item(&tr("Profile HTTP rewriting is active")).subtitle(&format!("{} · {}", s(&state, "rewriteRules").parse::<i64>().unwrap_or(0), tr("Rules remain active when request recording is stopped."))));
    }
    if !running {
        rows_list.push(Row::text(&tr("Start the local service before capturing requests")));
    }
    if snapshot.is_some() {
        rows_list.push(Row::text(&format!("{} {} · {} / 32 MiB", long(&page_v["total"]).unwrap_or(0), tr("Requests"), format::bytes(num(&page_v["storedBytes"])))));
    }
    let page_error = s(&page_v, "error");
    if !page_error.is_empty() {
        rows_list.push(Row::text(&page_error).danger());
    }
    if let Some(e) = &c.error {
        let a = st.clone();
        rows_list.push(Row::empty(&tr("Error"), e, "info").value(&tr("Retry")).on(move |_| captured_refresh(&a)));
    }
    if snapshot.is_some() && shown.is_empty() {
        let message = if c.query.is_empty() { tr("HTTP and HTTPS capture follow the local service. HTTPS requires a trusted MITM CA. Start capture, then reconnect the target app.") } else { String::new() };
        rows_list.push(Row::empty(&tr(if c.query.is_empty() && !c.filters.is_filtering() { "No captured requests" } else { "No results" }), &message, "requests"));
    }
    rows_list.push(Row::spacer(8));
    let fa = st.clone();
    let toggle: Rc<dyn Fn(String)> = Rc::new(move |k| {
        let mut x = fa.borrow_mut();
        if !x.filters.open.remove(&k) {
            x.filters.open.insert(k);
        }
    });
    let fb = st.clone();
    rows_list.extend(c.filters.rows(&CAPTURE_GROUPERS, &shown, toggle, &|i| captured_record(i, &fb)));
    if long(&page_v["total"]).unwrap_or(0) > entries.len() as i64 {
        let a = st.clone();
        rows_list.push(Row::button(&tr("Show more"), 2, move || {
            let mut x = a.borrow_mut();
            x.limit = (x.limit + 50).clamp(1, 300);
            drop(x);
            captured_refresh(&a);
        }).key("more"));
    }
    let keywords = c.filters.keywords(&CAPTURE_FACETS, pinned());
    let loading = busy || (snapshot.is_none() && c.error.is_none());
    drop(c);
    let (a, b, d, e) = (st.clone(), st.clone(), st.clone(), st.clone());
    let empty_entries = entries.is_empty();
    let mut page = Page::new(&title)
        .loading(loading)
        .bar(vec![Bar::new("refresh", "Refresh", move || captured_refresh(&a))])
        .search(move |q| {
            let mut x = b.borrow_mut();
            x.query = q;
            x.limit = 50;
            drop(x);
            captured_refresh(&b);
        })
        .keywords(keywords, move |k| d.borrow_mut().filters.keyword(&CAPTURE_FACETS, &k))
        .menu(move || captured_menu(&e, active, empty_entries))
        .rows(rows_list);
    page.title_menu = title_menu;
    page
}

fn captured_menu(st: &Rc<RefCell<Captured>>, active: bool, empty: bool) -> Vec<MenuEntry> {
    let busy = st.borrow().busy;
    let mut items = Vec::new();
    let a = st.clone();
    items.push(
        MenuEntry::act("Capture settings", "sliders", move || {
            a.borrow_mut().busy = true;
            mark(Mark::ROWS);
            let b = a.clone();
            app::call("capture.settings", json!({}), move |r| match r {
                Ok(source) => {
                    let c = b.clone();
                    open_request_settings(obj(&source["config"]), source, Rc::new(move || {
                        c.borrow_mut().busy = false;
                        captured_refresh(&c);
                    }));
                }
                Err(e) => {
                    app::report(&e);
                    b.borrow_mut().busy = false;
                    captured_refresh(&b);
                }
            });
        })
        .when(!busy && !active),
    );
    items.push(MenuEntry::act("MITM certificate", "lock", open_mitm_certificate));
    items.push(MenuEntry::act("Capture logs", "logs", open_capture_logs));
    let a = st.clone();
    items.push(
        MenuEntry::act("Export HAR", "arrow-down", move || {
            let q = a.borrow().query.clone();
            captured_act(&a, "capture.export", json!({ "format": "har", "query": q }));
        })
        .when(!busy && !empty),
    );
    let a = st.clone();
    items.push(
        MenuEntry::act("Clear requests", "delete", move || {
            let b = a.clone();
            nav::confirm("Clear captured requests?", None, "Delete", move |ok| {
                if ok {
                    captured_act(&b, "capture.clear", json!({}));
                }
            });
        })
        .when(!busy),
    );
    let a = st.clone();
    items.extend(st.borrow().filters.menu_items(&CAPTURE_FACETS, &CAPTURE_GROUPERS, pinned(), Rc::new(move |g: &dyn Fn(&mut Filters)| g(&mut a.borrow_mut().filters))));
    items
}

// ---------------------------------------------------------------------------
// Request details
// ---------------------------------------------------------------------------

struct Cookie {
    name: String,
    value: String,
    attributes: Vec<String>,
    raw: String,
}

/// capturedCookies: Cookie headers split on ';', Set-Cookie one per header with its attributes.
fn captured_cookies(headers: &[Value], response: bool) -> Vec<Cookie> {
    let mut out = Vec::new();
    for h in headers {
        if s(h, "name").to_lowercase() != if response { "set-cookie" } else { "cookie" } {
            continue;
        }
        let raw = s(h, "value");
        let entries: Vec<String> = if response { vec![raw.clone()] } else { raw.split(';').map(str::to_string).collect() };
        for entry in entries {
            let parts: Vec<&str> = entry.split(';').collect();
            let pair = parts[0].trim();
            let Some(sep) = pair.find('=') else { continue };
            if sep == 0 {
                continue;
            }
            let attributes = if response { parts[1..].iter().map(|p| p.trim().to_string()).filter(|p| !p.is_empty()).collect() } else { Vec::new() };
            out.push(Cookie { name: pair[..sep].trim().to_string(), value: pair[sep + 1..].trim().to_string(), attributes, raw: if response { entry.trim().to_string() } else { pair.to_string() } });
        }
    }
    out
}

fn open_request(id: String) {
    let st: Rc<RefCell<(Option<Value>, Option<String>, String, bool)>> = Rc::new(RefCell::new((None, None, "Response".into(), false)));
    let alive = Rc::new(Cell::new(true));
    let (a, i) = (st.clone(), id.clone());
    let al = alive.clone();
    let timer = poll(Rc::new(move || al.get()), Rc::new(move || {
        let b = a.clone();
        app::call("capture.detail", json!({ "id": i }), move |r| {
            match r {
                Ok(v) => {
                    b.borrow_mut().0 = Some(v);
                    b.borrow_mut().1 = None;
                }
                Err(e) => b.borrow_mut().1 = Some(format::clean_error(&e)),
            }
            mark(Mark::ROWS);
        });
    }));
    let route = rows::push(move || {
        let _keep = &timer;
        request_page(&st, &id)
    });
    nav::on_closed(route, move || alive.set(false));
}

fn request_page(st: &Rc<RefCell<(Option<Value>, Option<String>, String, bool)>>, id: &str) -> Page {
    if data(|d| d.remote()) {
        return local_only("Requests");
    }
    let (detail, error, part, busy) = st.borrow().clone();
    let mut rows_list = Vec::new();
    if let Some(e) = &error {
        rows_list.push(Row::empty(&tr("Error"), e, "info"));
    }
    let Some(detail) = detail else {
        return Page::new(&tr("Request details")).loading(error.is_none()).rows(rows_list);
    };
    let metadata = &detail["metadata"];
    let request = &detail["request"];
    let socket = &metadata["socket"];
    let tls = &metadata["tls"];
    let response = part == "Response";
    let message = &detail[if response { "response" } else { "request" }];
    let original = !detail["originalUrl"].is_null();
    let mut values: Vec<(&str, String)> = vec![
        ("URL", s(request, "url")),
        ("Method", s(request, "method")),
        ("Created", format::local_date(long(&detail["startedAt"]).unwrap_or(0))),
    ];
    if original {
        values.push(("Original URL", s(&detail, "originalUrl")));
    }
    values.push(("Duration", format!("{} ms", long(&detail["durationMs"]).unwrap_or(0))));
    values.push(("Status", s(&detail["response"], "status")));
    values.push(("Protocol", s(request, "protocol")));
    values.push((if original { "Original outbound" } else { "Outbound" }, s(metadata, "outbound")));
    values.push((if original { "Original rule matched" } else { "Rule matched" }, s(metadata, "rule")));
    values.push(("App", metadata["packages"].as_array().map(|a| a.iter().map(text).collect::<Vec<_>>().join(", ")).unwrap_or_default()));
    values.push(("UID", s(metadata, "uid")));
    if !metadata["captureSource"].is_null() {
        values.push(("Capture source", s(metadata, "captureSource")));
    }
    if !metadata["pid"].is_null() {
        values.push(("PID", s(metadata, "pid")));
    }
    if !s(metadata, "tlsAdapter").is_empty() {
        values.push(("TLS implementation", s(metadata, "tlsAdapter")));
    }
    let has_tls = tls.as_object().map(|o| !o.is_empty()).unwrap_or(false);
    if has_tls {
        values.push(("TLS server name", s(tls, "serverName")));
        values.push(("Client TLS", s(tls, "clientVersion")));
        values.push(("Upstream TLS", s(tls, "upstreamVersion")));
    }
    if !s(tls, "upstreamServerName").is_empty() {
        values.push(("Upstream TLS server name", s(tls, "upstreamServerName")));
    }
    if has_tls {
        let alpn = s(tls, "alpn");
        values.push(("ALPN", if alpn.is_empty() { tr("Not negotiated") } else { alpn }));
        values.push(("Upstream certificate SHA-256", s(tls, "upstreamCertificate")));
    }
    if socket.as_object().map(|o| !o.is_empty()).unwrap_or(false) {
        values.push(("Socket FD", s(socket, "fd")));
        values.push(("Socket inode", s(socket, "inode")));
    }
    values.push(("Source", s(metadata, "source")));
    values.push(("Destination", s(metadata, "destination")));
    if !metadata["originalDestination"].is_null() {
        values.push(("Original destination", s(metadata, "originalDestination")));
    }
    if !s(&detail, "error").is_empty() {
        values.push(("Error", s(&detail, "error")));
    }
    if !s(&detail, "note").is_empty() {
        values.push(("Note", s(&detail, "note")));
    }
    let stacked = ["URL", "Original URL", "App", "Error", "Note", "Upstream certificate SHA-256"];
    for (k, v) in &values {
        rows_list.push(detail_row(&tr(k), v, stacked.contains(k)));
    }
    let copy_row = |label: &str, text_value: String| {
        let enabled = !text_value.is_empty();
        Row::item(label).icon("copy").on(move |_| {
            app::copy_text(&text_value);
            app::message("Copied");
        }).enabled(enabled)
    };
    if part == "Cookies" {
        for response in [false, true] {
            let msg = if response { &detail["response"] } else { request };
            let cookies = captured_cookies(&list(&msg["headers"]), response);
            rows_list.push(Row::section(&tr(if response { "Response cookies" } else { "Request cookies" })));
            if cookies.is_empty() {
                rows_list.push(Row::item(&tr("No cookies captured")));
            } else {
                for c in &cookies {
                    let mut sub = if c.value.is_empty() { tr("Empty value") } else { c.value.clone() };
                    if !c.attributes.is_empty() {
                        sub = format!("{sub}\n{}", c.attributes.join("\n"));
                    }
                    let raw = c.raw.clone();
                    rows_list.push(Row::item(&c.name).subtitle(&sub).on(move |_| {
                        app::copy_text(&raw);
                        app::message("Copied");
                    }));
                }
                rows_list.push(copy_row(&tr("Copy cookies"), cookies.iter().map(|c| c.raw.clone()).collect::<Vec<_>>().join("\n")));
            }
            if truthy(&msg["headersTruncated"]) {
                rows_list.push(Row::footer(&tr("Headers truncated")));
            }
        }
    } else {
        let headers = list(&message["headers"]);
        rows_list.push(Row::section(&tr(if response { "Response headers" } else { "Request headers" })));
        if headers.is_empty() {
            rows_list.push(Row::item(&tr("No headers captured")));
        } else {
            for h in &headers {
                rows_list.push(detail_row(&s(h, "name"), &s(h, "value"), true));
            }
            rows_list.push(copy_row(&tr("Copy headers"), headers.iter().map(|h| format!("{}: {}", s(h, "name"), s(h, "value"))).collect::<Vec<_>>().join("\n")));
        }
        if truthy(&message["headersTruncated"]) {
            rows_list.push(Row::footer(&tr("Headers truncated")));
        }
        // CapturedBody: the preview, JSON pretty-printed when it is complete text.
        let preview = &detail[if response { "responsePreview" } else { "requestPreview" }];
        let raw = s(preview, "text");
        let complete = s(preview, "encoding") == "text" && !truthy(&preview["truncated"]) && truthy(&message["bodyComplete"]) && !truthy(&message["bodyTruncated"]);
        let pretty = if complete { serde_json::from_str::<Value>(&raw).ok().and_then(|v| serde_json::to_string_pretty(&v).ok()) } else { None };
        rows_list.push(Row::section(&tr("Body")));
        if raw.is_empty() {
            rows_list.push(Row::item(&tr("No body captured")));
        } else {
            let shown: String = pretty.unwrap_or_else(|| raw.clone()).chars().take(200_000).collect();
            rows_list.push(Row::text(&shown).mono().checked(true));
            rows_list.push(copy_row(&tr("Copy body preview"), raw));
        }
        let trailers = list(&message["trailers"]);
        if !trailers.is_empty() {
            rows_list.push(Row::section(&tr("Trailers")));
            for t in &trailers {
                rows_list.push(detail_row(&s(t, "name"), &s(t, "value"), true));
            }
            if truthy(&message["trailersTruncated"]) {
                rows_list.push(Row::footer(&tr("Headers truncated")));
            }
        }
    }
    let curl_blocked = busy
        || !truthy(&request["bodyComplete"])
        || truthy(&request["bodyTruncated"])
        || truthy(&request["headersTruncated"])
        || !list(&request["trailers"]).is_empty()
        || truthy(&request["trailersTruncated"])
        || s(request, "method") == "TLS";
    let url = s(request, "url");
    let (a, i) = (st.clone(), id.to_string());
    let b = st.clone();
    Page::new(&tr("Request details"))
        .loading(busy)
        .bar(vec![
            Bar::new("copy", "Copy URL", move || {
                app::copy_text(&url);
                app::message("Copied");
            }),
            Bar::new("arrow-down", "Export cURL", move || {
                a.borrow_mut().3 = true;
                mark(Mark::ROWS);
                let c = a.clone();
                app::act_then("capture.export", json!({ "format": "curl", "id": i }), move |_| {
                    c.borrow_mut().3 = false;
                    mark(Mark::ROWS);
                });
            })
            .when(!curl_blocked),
        ])
        .menu(move || {
            let part = b.borrow().2.clone();
            ["Request", "Response", "Cookies"]
                .iter()
                .map(|item| {
                    let c = b.clone();
                    let mut m = MenuEntry::plain(&tr(item), move || {
                        c.borrow_mut().2 = item.to_string();
                        mark(Mark::ROWS);
                    })
                    .checked(part == *item);
                    if *item == "Request" {
                        m = m.section(&tr("Message type"));
                    }
                    m
                })
                .collect()
        })
        .rows(rows_list)
}

// ---------------------------------------------------------------------------
// Capture settings, applications, domains and logs
// ---------------------------------------------------------------------------

fn split_lines(v: &str) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for p in v.trim().split(|c: char| c.is_whitespace() || c == ',' || c == ';').filter(|p| !p.is_empty()) {
        if !out.iter().any(|x| x == p) {
            out.push(p.to_string());
        }
    }
    out
}

fn string_list(v: &Value) -> Vec<String> {
    v.as_array().map(|a| a.iter().map(text).collect()).unwrap_or_default()
}

struct Settings {
    decrypt: String,
    bypass: String,
    ports: String,
    https: bool,
    body_kib: i64,
    packages: Vec<String>,
    extract: bool,
    block_quic: bool,
    busy: bool,
    dirty: bool,
    error: Option<String>,
    revision: Option<String>,
}

pub fn open_request_settings(initial: crate::app::Json, source: Value, on_closed: Rc<dyn Fn()>) {
    let get = |k: &str| initial.get(k).cloned().unwrap_or(Value::Null);
    let ports = get("ports").as_array().map(|a| a.iter().map(text).collect::<Vec<_>>().join(", ")).unwrap_or_else(|| "80, 443, 8080, 8443".into());
    let st = Rc::new(RefCell::new(Settings {
        decrypt: string_list(&get("decryptHosts")).join("\n"),
        bypass: string_list(&get("decryptBypassHosts")).join("\n"),
        ports,
        https: truthy(&get("https")),
        body_kib: ((get("bodyLimit").as_f64().unwrap_or(65536.0)) / 1024.0).round() as i64,
        packages: string_list(&get("packages")),
        extract: truthy(&get("extractHosts")),
        block_quic: truthy(&get("blockQUIC")),
        busy: false,
        dirty: false,
        error: None,
        revision: source.get("revision").and_then(Value::as_str).map(str::to_string),
    }));
    let a = st.clone();
    let id = rows::push(move || settings_page(&a));
    let a = st.clone();
    nav::guard(id, move || a.borrow().dirty);
    let a = st.clone();
    nav::busy_guard(id, move || a.borrow().busy);
    nav::on_closed(id, move || on_closed());
}

fn settings_save(st: &Rc<RefCell<Settings>>) {
    let args = {
        let mut x = st.borrow_mut();
        if x.busy || data(|d| d.remote()) {
            return;
        }
        x.busy = true;
        x.error = None;
        let parsed: Vec<i64> = split_lines(&x.ports).iter().map(|p| p.parse::<i64>().unwrap_or(-1)).collect();
        if parsed.is_empty() || parsed.iter().any(|p| !(1..=65535).contains(p)) {
            x.error = Some(tr("Ports must be between 1 and 65535"));
            x.busy = false;
            drop(x);
            mark(Mark::ROWS);
            return;
        }
        json!({ "revision": x.revision, "config": {
            "version": 2, "https": x.https, "decryptHosts": split_lines(&x.decrypt), "packages": x.packages, "ports": parsed,
            "bodyLimit": x.body_kib * 1024, "decryptBypassHosts": split_lines(&x.bypass), "extractHosts": x.extract, "blockQUIC": x.block_quic,
        } })
    };
    mark(Mark::ROWS);
    let a = st.clone();
    let route = nav::top().map(|r| r.id);
    app::call("configureCapture", args, move |r| {
        let mut x = a.borrow_mut();
        x.busy = false;
        match r {
            Ok(_) => {
                x.dirty = false;
                drop(x);
                if let Some(id) = route {
                    nav::close(id);
                }
            }
            Err(e) => x.error = Some(format::clean_error(&e)),
        }
        mark(Mark::ROWS);
    });
}

fn settings_page(st: &Rc<RefCell<Settings>>) -> Page {
    if data(|d| d.remote()) {
        return local_only("Capture settings");
    }
    let x = st.borrow();
    let mut rows_list = Vec::new();
    if let Some(e) = &x.error {
        rows_list.push(Row::error(e));
        rows_list.push(Row::spacer(8));
    }
    rows_list.push(Row::footer(&tr("Saved on this device. Capture stops when the local service restarts.")));
    rows_list.push(Row::section("HTTPS / MITM"));
    let a = st.clone();
    rows_list.push(Row::switch(&tr("Decrypt HTTPS"), x.https, move |v| {
        let mut x = a.borrow_mut();
        x.https = v;
        x.dirty = true;
    }).key("https"));
    rows_list.push(Row::item(&tr("MITM certificate")).key("cert").on(|_| open_mitm_certificate()));
    // A list field: the text itself, and its quick edit page.
    let field = |rows_list: &mut Vec<Row>, key: &'static str, label: &str, value: &str, hint: &str, footer: &str, setter: Rc<dyn Fn(&mut Settings, String)>, joiner: &'static str| {
        rows_list.push(Row::section(&tr(label)));
        let (a, f) = (st.clone(), setter.clone());
        rows_list.push(Row::editor("", value, 3, move |v| {
            let mut x = a.borrow_mut();
            f(&mut x, v.chars().take(65536).collect());
            x.dirty = true;
        }).hint(hint).key(key));
        let (a, f) = (st.clone(), setter.clone());
        let title = tr(label);
        let items = split_lines(value);
        let hint_s = hint.to_string();
        rows_list.push(Row::item(&tr("Quick edit")).icon("compose").key(&format!("{key}-quick")).on(move |_| {
            let (a, f) = (a.clone(), f.clone());
            forms::list_quick_edit(&title, items.clone(), Some(&hint_s), None, None, Rc::new(move |list: Vec<String>, done: forms::Done| {
                let mut x = a.borrow_mut();
                f(&mut x, list.join(joiner));
                x.dirty = true;
                drop(x);
                mark(Mark::ROWS);
                done(Ok(()));
            }));
        }));
        if !footer.is_empty() {
            rows_list.push(Row::footer(&tr(footer)));
        }
    };
    if x.https {
        field(&mut rows_list, "decrypt", "HTTPS domains", &x.decrypt, "*.example.com", "Leave empty to decrypt all HTTPS domains. Exact names and *.example.com are accepted; exclusions take priority.", Rc::new(|x, v| x.decrypt = v), "\n");
        let a = st.clone();
        rows_list.push(Row::item(&tr("Add captured HTTPS domains")).key("add-decrypt").on(move |_| add_domains(&a, 0)));
        field(&mut rows_list, "bypass", "HTTPS excluded domains", &x.bypass, "pinned.example.com", "Pass these TLS connections through unchanged. Visible hostnames can still be extracted.", Rc::new(|x, v| x.bypass = v), "\n");
        let a = st.clone();
        rows_list.push(Row::item(&tr("Add captured HTTPS exclusions")).key("add-bypass").on(move |_| add_domains(&a, 1)));
        rows_list.push(Row::footer(&tr("Install and trust the MITM CA, start capture, then reconnect the target app. Only traffic routed through the local service is captured. Server certificates are always verified.")));
        rows_list.push(Row::gap());
        let a = st.clone();
        rows_list.push(Row::switch(&tr("Request TCP fallback for QUIC"), x.block_quic, move |v| {
            let mut x = a.borrow_mut();
            x.block_quic = v;
            x.dirty = true;
        }).subtitle(&tr("Reject only observed QUIC connections whose names match the HTTPS filters. Apps may retry over TCP. Unknown names and other UDP stay unchanged.")).key("quic"));
    }
    rows_list.push(Row::gap());
    let a = st.clone();
    rows_list.push(Row::switch(&tr("Extract TLS/QUIC domains"), x.extract, move |v| {
        let mut x = a.borrow_mut();
        x.extract = v;
        x.dirty = true;
    }).subtitle(&tr("Collect visible TLS/QUIC hostnames through the local service. These records contain names only.")).key("extract"));
    rows_list.push(Row::section(&tr("Filters and storage")));
    let a = st.clone();
    let packages = x.packages.clone();
    rows_list.push(
        Row::item(&tr("Capture applications"))
            .subtitle(&if packages.is_empty() { tr("All applications") } else { packages.join(", ") })
            .icon("apps-list")
            .key("apps")
            .on(move |_| {
                let b = a.clone();
                open_capture_apps(packages.clone(), Rc::new(move |selected| {
                    let mut x = b.borrow_mut();
                    x.packages = selected;
                    x.dirty = true;
                    drop(x);
                    mark(Mark::ROWS);
                }));
            }),
    );
    let a = st.clone();
    rows_list.push(
        forms::options_row(
            &tr("Body limit per message"),
            vec!["64".into(), "128".into(), "256".into(), "1024".into()],
            &x.body_kib.to_string(),
            Rc::new(|v: &str| format!("{v} KiB")),
            Rc::new(move |v| {
                let mut x = a.borrow_mut();
                x.body_kib = v.parse().unwrap_or(64);
                x.dirty = true;
                drop(x);
                mark(Mark::ROWS);
            }),
        )
        .key("body"),
    );
    field(&mut rows_list, "ports", "Capture ports", &x.ports, "80, 443, 8080, 8443", "Keeps up to 300 requests and 32 MiB. Large bodies keep a prefix. Port filters use the connection destination.", Rc::new(|x, v| x.ports = v), ", ");
    let busy = x.busy;
    drop(x);
    let a = st.clone();
    Page::new(&tr("Capture settings")).loading(busy).confirm(Bar::new("check", "Save", move || settings_save(&a)).when(!busy)).rows(rows_list)
}

fn add_domains(st: &Rc<RefCell<Settings>>, target: i32) {
    let a = st.clone();
    open_capture_domains(String::new(), Some(Rc::new(move |selected: Vec<String>| {
        if selected.is_empty() {
            return;
        }
        let mut x = a.borrow_mut();
        let current = if target == 0 { &x.decrypt } else { &x.bypass };
        let mut merged = split_lines(current);
        for d in selected {
            if !merged.contains(&d) {
                merged.push(d);
            }
        }
        if target == 0 {
            x.decrypt = merged.join("\n");
        } else {
            x.bypass = merged.join("\n");
        }
        x.dirty = true;
        drop(x);
        mark(Mark::ROWS);
    })));
}

fn open_capture_apps(initial: Vec<String>, done: Rc<dyn Fn(Vec<String>)>) {
    let st: Rc<RefCell<(BTreeSet<String>, String, Option<Vec<Value>>, Option<String>)>> = Rc::new(RefCell::new((initial.into_iter().collect(), String::new(), None, None)));
    let load: Rc<dyn Fn()> = {
        let st = st.clone();
        Rc::new(move || {
            st.borrow_mut().3 = None;
            let a = st.clone();
            app::call("listApps", json!({ "refresh": true, "system": true, "nonInternet": false }), move |r| {
                match r {
                    Ok(v) => a.borrow_mut().2 = Some(list(&v)),
                    Err(e) => a.borrow_mut().3 = Some(format::clean_error(&e)),
                }
                mark(Mark::ROWS);
            });
        })
    };
    load();
    rows::push(move || {
        let x = st.borrow();
        let mut all = x.2.clone().unwrap_or_default();
        let installed: Vec<String> = all.iter().map(|a| s(a, "package")).collect();
        for p in x.0.iter().filter(|p| !installed.contains(p)) {
            all.push(json!({ "name": p, "package": p }));
        }
        let q = x.1.to_lowercase();
        let mut rows_list = vec![Row::text(&tr("An empty selection captures all applications routed through the local service. Unknown owners do not match a selected application. Applications that share a UID cannot be told apart."))];
        if let Some(e) = &x.3 {
            let l = load.clone();
            rows_list.push(Row::empty(&tr("Error"), e, "info").value(&tr("Retry")).on(move |_| l()));
        }
        for a in all.iter().filter(|a| format!("{} {}", s(a, "name"), s(a, "package")).to_lowercase().contains(&q)) {
            let pkg = s(a, "package");
            let name = { let n = s(a, "name"); if n.is_empty() { pkg.clone() } else { n } };
            let (b, p) = (st.clone(), pkg.clone());
            rows_list.push(Row::check(&name, x.0.contains(&pkg), move |_| {
                let mut x = b.borrow_mut();
                if !x.0.remove(&p) {
                    x.0.insert(p.clone());
                }
            }).subtitle(&pkg).key(&pkg));
        }
        let loading = x.2.is_none() && x.3.is_none();
        drop(x);
        let (a, b, c, d) = (st.clone(), st.clone(), st.clone(), done.clone());
        Page::new(&tr("Capture applications"))
            .loading(loading)
            .search(move |q| a.borrow_mut().1 = q.to_lowercase())
            .bar(vec![
                Bar::new("compose", "Quick edit", move || {
                    let items: Vec<String> = b.borrow().0.iter().cloned().collect();
                    let b2 = b.clone();
                    forms::list_quick_edit(&tr("Capture applications"), items, Some("com.example.app"), None, None, Rc::new(move |list: Vec<String>, done: forms::Done| {
                        b2.borrow_mut().0 = list.into_iter().collect();
                        mark(Mark::ROWS);
                        done(Ok(()));
                    }));
                }),
                Bar::new("clear-all", "All applications", move || {
                    c.borrow_mut().0.clear();
                    mark(Mark::ROWS);
                }),
            ])
            .confirm({
                let st2 = st.clone();
                Bar::new("check", "Done", move || {
                    let selected: Vec<String> = st2.borrow().0.iter().cloned().collect();
                    nav::pop();
                    d(selected);
                })
            })
            .rows(rows_list)
    });
}

pub fn open_capture_domains(query: String, select: Option<Rc<dyn Fn(Vec<String>)>>) {
    let st: Rc<RefCell<(Option<Vec<String>>, Option<String>, BTreeSet<String>, bool)>> = Rc::new(RefCell::new((None, None, BTreeSet::new(), false)));
    let load: Rc<dyn Fn()> = {
        let (st, q) = (st.clone(), query.clone());
        Rc::new(move || {
            {
                let mut x = st.borrow_mut();
                x.0 = None;
                x.1 = None;
            }
            let a = st.clone();
            app::call("capture.domains", json!({ "query": q }), move |r| {
                match r {
                    Ok(v) => a.borrow_mut().0 = Some(string_list(&v)),
                    Err(e) => a.borrow_mut().1 = Some(format::clean_error(&e)),
                }
                mark(Mark::ROWS);
            });
        })
    };
    load();
    rows::push(move || {
        if data(|d| d.remote()) {
            return local_only("Extract domains");
        }
        let x = st.borrow();
        let domains = x.0.clone().unwrap_or_default();
        let mut rows_list = vec![Row::footer(&tr("Domains are extracted from retained requests and visible TLS/QUIC names, using the current search filter. Encrypted hostnames cannot be extracted."))];
        if let Some(e) = &x.1 {
            let l = load.clone();
            rows_list.push(Row::empty(&tr("Error"), e, "info").value(&tr("Retry")).on(move |_| l()));
        }
        if x.0.is_some() && domains.is_empty() {
            rows_list.push(Row::empty(&tr("No captured domains"), "", "info"));
        }
        for host in &domains {
            let h = host.clone();
            if select.is_some() {
                let b = st.clone();
                rows_list.push(Row::check(host, x.2.contains(host), move |_| {
                    let mut x = b.borrow_mut();
                    if !x.2.remove(&h) {
                        x.2.insert(h.clone());
                    }
                }).key(host));
            } else {
                rows_list.push(Row::item(host).key(host).on(move |_| {
                    app::copy_text(&h);
                    app::message("Copied");
                }));
            }
        }
        let busy = x.3;
        let loading = busy || (x.0.is_none() && x.1.is_none());
        let selected: Vec<String> = x.2.iter().cloned().collect();
        drop(x);
        let all = domains.join("\n");
        let mut bar = vec![Bar::new("copy", "Copy domains", move || {
            app::copy_text(&all);
            app::message("Copied");
        }).when(!domains.is_empty())];
        if select.is_none() {
            let (a, q) = (st.clone(), query.clone());
            bar.push(Bar::new("arrow-down", "Export domains", move || {
                a.borrow_mut().3 = true;
                mark(Mark::ROWS);
                let b = a.clone();
                app::act_then("capture.export", json!({ "format": "domains", "query": q }), move |_| {
                    b.borrow_mut().3 = false;
                    mark(Mark::ROWS);
                });
            }).when(!domains.is_empty() && !busy));
        }
        let l = load.clone();
        bar.push(Bar::new("refresh", "Refresh", move || l()));
        let mut page = Page::new(&tr("Extract domains")).loading(loading).bar(bar).rows(rows_list);
        if let Some(f) = select.clone() {
            page = page.confirm(Bar::new("check", "Add domains", move || {
                nav::pop();
                f(selected.clone());
            }).when(!st.borrow().2.is_empty()));
        }
        page
    });
}

fn capture_line(log: &Value) -> String {
    let ms = long(&log["receivedAt"]).unwrap_or(0);
    format!("{} [{}] {}", format::local_date(ms), s(log, "level"), s(log, "message"))
}

pub fn open_capture_logs() {
    let st: Rc<RefCell<(Option<Value>, Option<String>, String, bool, bool)>> = Rc::new(RefCell::new((None, None, String::new(), false, false)));
    let alive = Rc::new(Cell::new(true));
    let a = st.clone();
    let fetch: Rc<dyn Fn()> = Rc::new(move || {
        let b = a.clone();
        app::call("capture.logs", json!({}), move |r| {
            match r {
                Ok(v) => {
                    b.borrow_mut().0 = Some(v);
                    b.borrow_mut().1 = None;
                }
                Err(e) => b.borrow_mut().1 = Some(format::clean_error(&e)),
            }
            mark(Mark::ROWS);
        });
    });
    let al = alive.clone();
    let timer = poll(Rc::new(move || al.get()), fetch.clone());
    let route = rows::push(move || {
        let _keep = &timer;
        if data(|d| d.remote()) {
            return local_only("Capture logs");
        }
        let x = st.borrow();
        let state = x.0.as_ref().map(|v| v["state"].clone()).unwrap_or(Value::Null);
        let active = truthy(&state["active"]);
        let words: Vec<String> = x.2.to_lowercase().split_whitespace().map(str::to_string).collect();
        let mut logs: Vec<Value> = x.0.as_ref().map(|v| list(&v["logs"])).unwrap_or_default().into_iter().filter(|l| {
            let hay = format!("{} {}", s(l, "level"), s(l, "message")).to_lowercase();
            words.iter().all(|w| hay.contains(w.as_str()))
        }).collect();
        logs.sort_by(|a, b| num(&a["receivedAt"]).partial_cmp(&num(&b["receivedAt"])).unwrap_or(std::cmp::Ordering::Equal).then(num(&a["id"]).partial_cmp(&num(&b["id"])).unwrap_or(std::cmp::Ordering::Equal)));
        let all_text = logs.iter().map(capture_line).collect::<Vec<_>>().join("\n");
        let mut rows_list = Vec::new();
        if x.0.is_some() {
            rows_list.push(Row::item(&tr("Status")).subtitle(&tr(if active { "Capturing new connections" } else { "Capture stopped" })));
            if truthy(&state["rewriteActive"]) {
                rows_list.push(Row::item(&tr("Profile HTTP rewriting is active")).subtitle(&tr("Rules remain active when request recording is stopped.")));
            }
            if active {
                let backend = { let b = s(&state, "backend"); if b.is_empty() { "HTTP".into() } else { b } };
                rows_list.push(Row::item(&backend).subtitle(&tr("Reconnect target applications to capture their new connections.")));
            }
            if num(&state["skipped"]) > 0.0 {
                rows_list.push(detail_row(&tr("Connections skipped or interrupted"), &s(&state, "skipped"), false));
            }
            if !state["backend"].is_null() {
                let n = |k: &str| { let v = s(&state, k); if v.is_empty() { "0".to_string() } else { v } };
                let a = st.clone();
                rows_list.push(Row::item(&tr("Capture statistics")).subtitle(&format!("TLS {} · {} {} · {} {}", n("tlsOpened"), tr("Plaintext"), n("plaintextConnections"), tr("Parsed"), n("parsedRequests"))).key("stats").on(move |_| {
                    let v = !a.borrow().4;
                    a.borrow_mut().4 = v;
                }));
                if x.4 {
                    let stats = [
                        ("TLS handshakes attempted", n("tlsOpened")),
                        ("TLS handshakes failed", n("tlsFailed")),
                        ("Connections with plaintext", format!("{} · {}", n("plaintextConnections"), format::bytes(num(&state["plaintextBytes"])))),
                        ("Parsed requests", n("parsedRequests")),
                        ("Requests excluded by filters", n("filteredRequests")),
                        ("Requests saved", n("recordedRequests")),
                        ("QUIC connections rejected", n("blockedQUIC")),
                    ];
                    for (k, v) in stats {
                        rows_list.push(detail_row(&tr(k), &v, true));
                    }
                }
            }
            let latest = s(&state, "error");
            if !latest.is_empty() {
                rows_list.push(detail_row(&tr("Latest error"), &latest, true));
            }
        }
        rows_list.push(Row::footer(&tr("Recent logs are kept until the app process exits. Export logs to keep them.")));
        if let Some(e) = &x.1 {
            rows_list.push(Row::empty(&tr("Error"), e, "info"));
        }
        if x.0.is_some() && logs.is_empty() {
            rows_list.push(Row::empty(&tr(if x.2.trim().is_empty() { "No capture logs" } else { "No results" }), "", "logs"));
        }
        for log in logs.iter().rev() {
            let level = { let l = s(log, "level"); if l.is_empty() { "info".to_string() } else { l } };
            let line = capture_line(log);
            let mut r = Row::record(&format::record_time(long(&log["receivedAt"]).unwrap_or(0)), vec![level.clone()], &s(log, "message"), move || {
                app::copy_text(&line);
                app::message("Copied");
            })
            .key(&s(log, "id"));
            r.data.lines = if level == "error" { 2 } else if level == "warn" { 1 } else { 0 };
            rows_list.push(r);
        }
        let busy = x.3;
        let loading = busy || (x.0.is_none() && x.1.is_none());
        drop(x);
        let (a, b, c, f) = (st.clone(), st.clone(), all_text.clone(), fetch.clone());
        let empty = logs.is_empty();
        Page::new(&tr("Capture logs"))
            .loading(loading)
            .search(move |q| a.borrow_mut().2 = q)
            .bar(vec![Bar::new("refresh", "Refresh", move || f())])
            .menu(move || {
                let (c1, c2, b2) = (c.clone(), c.clone(), b.clone());
                vec![
                    MenuEntry::act("Copy logs", "copy", move || {
                        app::copy_text(&c1);
                        app::message("Copied");
                    })
                    .when(!empty),
                    MenuEntry::act("Export", "export", move || app::act("saveDocument", json!({ "name": "FK-box-capture.log", "content": c2 }))).when(!empty),
                    MenuEntry::act("Clear", "delete", move || {
                        b2.borrow_mut().3 = true;
                        let b3 = b2.clone();
                        app::act_then("capture.clearLogs", json!({}), move |_| {
                            b3.borrow_mut().3 = false;
                            mark(Mark::ROWS);
                        });
                    })
                    .when(!b.borrow().3),
                ]
            })
            .rows(rows_list)
    });
    nav::on_closed(route, move || alive.set(false));
}

// ---------------------------------------------------------------------------
// MITM certificate
// ---------------------------------------------------------------------------

pub fn open_mitm_certificate() {
    let st: Rc<RefCell<(Option<Value>, Option<String>, bool)>> = Rc::new(RefCell::new((None, None, false)));
    let refresh: Rc<dyn Fn()> = {
        let st = st.clone();
        Rc::new(move || {
            if st.borrow().2 || data(|d| d.remote()) {
                return;
            }
            st.borrow_mut().2 = true;
            st.borrow_mut().1 = None;
            mark(Mark::ROWS);
            let a = st.clone();
            app::call("capture.caSnapshot", json!({}), move |r| {
                let mut x = a.borrow_mut();
                x.2 = false;
                match r {
                    Ok(v) => x.0 = Some(v),
                    Err(e) => x.1 = Some(format::clean_error(&e)),
                }
                drop(x);
                mark(Mark::ROWS);
            });
        })
    };
    refresh();
    let route = rows::push(move || {
        if data(|d| d.remote()) {
            return local_only("MITM certificate");
        }
        let x = st.borrow();
        let busy = x.2;
        let mut rows_list = vec![Row::footer(&tr("HTTPS decryption requires the target app to trust this CA. Apps with certificate pinning or their own trust store may reject it; add those domains to the excluded list."))];
        if let Some(e) = &x.1 {
            let r = refresh.clone();
            rows_list.push(Row::empty(&tr("Error"), e, "info").value(&tr("Retry")).on(move |_| r()));
        }
        let current = x.0.clone();
        let exists = current.as_ref().map(|c| truthy(&c["exists"])).unwrap_or(false);
        let usable = exists && current.as_ref().map(|c| truthy(&c["valid"]) && truthy(&c["keyAvailable"])).unwrap_or(false);
        if let Some(c) = &current {
            let trust = if truthy(&c["systemTrusted"]) {
                "Present in system CA store"
            } else if truthy(&c["userTrusted"]) {
                "Present in user CA store"
            } else {
                "CA not installed"
            };
            let locked = truthy(&c["captureActive"]) || truthy(&c["rewriteActive"]);
            rows_list.push(Row::section(&tr("Certificate")));
            rows_list.push(Row::item(&tr(if exists { trust } else { "No MITM certificate" })).subtitle(&tr("Trust status describes this app process. It does not prove that every target app trusts the CA.")));
            if exists {
                rows_list.push(detail_row(&tr("Subject"), &s(c, "subject"), true));
                rows_list.push(detail_row("SHA-256", &s(c, "fingerprint"), true));
                rows_list.push(detail_row(&tr("Expires"), &format::local_date(long(&c["expiresAt"]).unwrap_or(0)), false));
                if !usable {
                    rows_list.push(Row::item(&tr("Certificate expired or signing key unavailable. Regenerate the CA.")));
                }
            }
            let a = st.clone();
            let mut g = Row::item(&tr(if exists { "Regenerate CA" } else { "Generate CA" })).key("generate").on(move |_| cert_run(&a, "generate")).enabled(!busy && !locked);
            if locked {
                g = g.subtitle(&tr("Stop capture and the local service before changing the MITM certificate"));
            }
            rows_list.push(g);
        }
        if exists {
            rows_list.push(Row::section(&tr("Install and export")));
            for (label, method) in [("Export public CA certificate", "export"), ("Open certificate settings", "install"), ("Export system CA module", "module")] {
                let a = st.clone();
                rows_list.push(Row::item(&tr(label)).key(method).on(move |_| cert_run(&a, method)).enabled(!busy && usable));
            }
            rows_list.push(Row::footer(&tr("On Android 11 or later, export the certificate first, then choose CA certificate in Android security settings. For system trust, install the exported module with a root manager and reboot.")));
        }
        rows_list.push(Row::footer(&tr("The private signing key stays on this device. Exports contain only the public CA. Regeneration requires replacing installed certificates; remove system modules too.")));
        drop(x);
        let r = refresh.clone();
        Page::new(&tr("MITM certificate")).loading(busy).bar(vec![Bar::new("refresh", "Refresh trust", move || r()).when(!busy)]).rows(rows_list)
    });
    // Refresh whenever the page is shown again (the app returning from certificate settings).
    let _ = route;
}

fn cert_run(st: &Rc<RefCell<(Option<Value>, Option<String>, bool)>>, method: &'static str) {
    let Some(current) = st.borrow().0.clone() else { return };
    if st.borrow().2 || data(|d| d.remote()) {
        return;
    }
    let a = st.clone();
    let go = move || {
        a.borrow_mut().2 = true;
        a.borrow_mut().1 = None;
        mark(Mark::ROWS);
        let revision = current.get("revision").and_then(Value::as_str).map(str::to_string);
        let (call_method, args) = match method {
            "generate" => ("capture.caGenerate", json!({ "revision": revision })),
            "export" => ("capture.exportCertificate", json!({ "revision": revision, "module": false })),
            "module" => ("capture.exportCertificate", json!({ "revision": revision, "module": true })),
            _ => ("capture.installCertificate", json!({ "revision": revision })),
        };
        let b = a.clone();
        app::call(call_method, args, move |r| {
            let mut x = b.borrow_mut();
            x.2 = false;
            match r {
                Ok(v) => {
                    if method == "generate" {
                        x.0 = Some(v);
                    } else if v == Value::Bool(true) {
                        let message = match method {
                            "install" => "Certificate settings opened. Refresh trust after installation.",
                            "module" => "CA module exported. Install it with your root manager, reboot, then refresh trust.",
                            _ => "Public CA certificate exported",
                        };
                        app::message(message);
                    }
                }
                Err(e) => x.1 = Some(format::clean_error(&e)),
            }
            drop(x);
            mark(Mark::ROWS);
        });
    };
    let exists = st.borrow().0.as_ref().map(|c| truthy(&c["exists"])).unwrap_or(false);
    if method == "generate" && exists {
        nav::confirm_styled("Regenerate the MITM CA? Replace the old certificate in Android and target applications afterwards.", None, "Confirm", false, move |ok| {
            if ok {
                go()
            }
        });
    } else {
        go();
    }
}

// ---------------------------------------------------------------------------
// Logs
// ---------------------------------------------------------------------------

fn log_priority(level: &str) -> Option<i32> {
    Some(match level {
        "panic" => 0,
        "fatal" => 1,
        "error" => 2,
        "warn" | "warning" => 3,
        "info" => 4,
        "debug" => 5,
        "trace" => 6,
        _ => return None,
    })
}

static LOG_FACETS: [Facet; 1] = [Facet {
    kind: "Level",
    values: |l| vec![s(l, "level").to_lowercase()],
    offered: true,
    order: &["trace", "debug", "info", "warn", "warning", "error", "fatal", "panic"],
    label: same,
}];

struct Logs {
    query: String,
    level: &'static str,
    paused: Option<Vec<Value>>,
    filters: Filters,
}

fn logs_builder() -> impl Fn() -> Page {
    let st = Rc::new(RefCell::new(Logs { query: String::new(), level: "Default", paused: None, filters: Filters::new("logs", &[]) }));
    move || logs_page(&st)
}

fn logs_page(st: &Rc<RefCell<Logs>>) -> Page {
    let (live, default_level) = data(|d| (d.logs.clone(), d.default_log_level.clone()));
    let mut x = st.borrow_mut();
    let source = x.paused.clone().unwrap_or(live);
    let level = x.level;
    let matches_level = |item: &Value| -> bool {
        let item_level = s(item, "level").to_lowercase();
        if level == "Default" {
            // Before the core reports its level, keep received records available.
            let Some(threshold) = default_level.as_deref().and_then(|l| log_priority(&l.to_lowercase())) else { return true };
            return log_priority(&item_level).map(|p| p <= threshold).unwrap_or(false);
        }
        level == "All" || item_level == level.to_lowercase()
    };
    let words: Vec<String> = x.query.to_lowercase().split_whitespace().map(str::to_string).collect();
    let searched: Vec<Value> = source
        .into_iter()
        .filter(|item| matches_level(item) && (words.is_empty() || { let hay = format!("{} {}", s(item, "level"), s(item, "message")).to_lowercase(); words.iter().all(|w| hay.contains(w.as_str())) }))
        .collect();
    x.filters.offer(&LOG_FACETS, &searched);
    let logs = x.filters.apply(&LOG_FACETS, searched);
    let mut rows_list = Vec::new();
    for log in &logs {
        let item_level = s(log, "level").to_lowercase();
        let tone = match item_level.as_str() {
            "error" | "fatal" | "panic" => 2,
            "warn" | "warning" => 1,
            _ => 0,
        };
        let received = long(&log["receivedAt"]).unwrap_or(0);
        let f = st.clone();
        let lv = item_level.clone();
        let time = if received > 0 { format::record_time(received) } else { String::new() };
        let mut r = Row::record(&time, vec![item_level.clone()], &s(log, "message"), || {})
            .key(&s(log, "id"))
            .on_tap(move |t| {
                if t == "l0" {
                    f.borrow_mut().filters.add(&LOG_FACETS, "Level", lv.clone());
                }
            });
        r.data.lines = tone;
        r.data.mono = false;
        rows_list.push(r);
    }
    let searching = !x.query.trim().is_empty() || !matches!(level, "Default" | "All") || x.filters.is_filtering();
    let keywords = x.filters.keywords(&LOG_FACETS, pinned());
    let paused = x.paused.is_some();
    drop(x);
    let export = logs.iter().map(|l| format!("{} [{}] {}", format::record_time(long(&l["receivedAt"]).unwrap_or(0)), s(l, "level"), s(l, "message"))).collect::<Vec<_>>().join("\n");
    let (a, b, c) = (st.clone(), st.clone(), st.clone());
    let mut page = Page::new(&tr("Logs"))
        .reverse(true)
        .search(move |q| a.borrow_mut().query = q)
        .keywords(keywords, move |k| b.borrow_mut().filters.keyword(&LOG_FACETS, &k))
        .bar(vec![Bar::new("export", "Export", move || app::act("saveDocument", json!({ "name": "B-box.log", "content": export })))])
        .menu(move || logs_menu(&c, paused))
        .rows(rows_list);
    if logs.is_empty() {
        page = page.empty(&tr(if searching { "No results" } else { "No logs" }), "", "logs");
    }
    page
}

fn logs_menu(st: &Rc<RefCell<Logs>>, paused: bool) -> Vec<MenuEntry> {
    let mut items = Vec::new();
    let a = st.clone();
    items.push(MenuEntry::act(if paused { "Resume" } else { "Pause" }, if paused { "start" } else { "pause" }, move || {
        let mut x = a.borrow_mut();
        x.paused = if x.paused.is_some() { None } else { Some(data(|d| d.logs.clone())) };
        drop(x);
        mark(Mark::ROWS);
    }));
    let a = st.clone();
    items.push(MenuEntry::act("Clear", "delete", move || {
        let b = a.clone();
        app::act_then("clearLogs", json!({}), move |ok| {
            if ok {
                b.borrow_mut().paused = None;
                mark(Mark::ROWS);
            }
        });
    }));
    let current = st.borrow().level;
    let default_level = data(|d| d.default_log_level.clone());
    for item in ["Default", "All", "Trace", "Debug", "Info", "Warn", "Error", "Fatal", "Panic"] {
        let label = if item == "Default" {
            format!("{}{}", tr("Follow configuration"), default_level.as_ref().map(|l| format!(" ({})", tr(l))).unwrap_or_default())
        } else {
            tr(item)
        };
        let a = st.clone();
        let mut m = MenuEntry::plain(&label, move || {
            a.borrow_mut().level = item;
            mark(Mark::ROWS);
        })
        .checked(current == item);
        if item == "Default" {
            m = m.section(&tr("Log level"));
        }
        items.push(m);
    }
    let a = st.clone();
    items.extend(st.borrow().filters.menu_items(&LOG_FACETS, &[], pinned(), Rc::new(move |g: &dyn Fn(&mut Filters)| g(&mut a.borrow_mut().filters))));
    items
}

// ---------------------------------------------------------------------------
// Dispatch
// ---------------------------------------------------------------------------

pub fn project(_w: &AppWindow, _m: Mark) {}

pub fn opened(kind: &str, arg: &str, id: i32) {
    match kind {
        "requests" => {
            let alive = Rc::new(Cell::new(true));
            let a = alive.clone();
            nav::on_closed(id, move || a.set(false));
            let initial = if arg.is_empty() { None } else { Some(arg.to_string()) };
            rows::adopt(id, requests_builder(initial, Rc::new(move || alive.get())));
        }
        "logs" => rows::adopt(id, logs_builder()),
        _ => {}
    }
}

pub fn closed(kind: &str, _arg: &str, id: i32) {
    if matches!(kind, "requests" | "logs") {
        rows::closed(id);
    }
}

pub fn act(_id: &str, _arg: &str) -> bool {
    false
}

pub fn act2(_id: &str, _a: &str, _b: &str) -> bool {
    false
}

pub fn menu(_key: &str) -> Vec<MenuEntry> {
    Vec::new()
}

pub fn install(_w: &AppWindow) {
    rows::set_home(rows::REQUESTS_ID, requests_builder(None, Rc::new(|| nav::tab() == "Requests" && nav::top().is_none())));
    rows::set_home(rows::LOGS_ID, logs_builder());
}
