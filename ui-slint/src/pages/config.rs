//! The structured configuration of a sing-box profile (pages/Configuration.kt):
//! ProfileConfigStore over profileConfig / writeProfile, and the Network, DNS,
//! routing, rule set, log and HTTP rewriting pages with their list editors.

use std::cell::RefCell;
use std::rc::Rc;

use serde_json::{json, Map, Value};

use crate::app::{self, call, long, obj, text, truthy};
use crate::forms::{self, decode, encode, encode_line, Done};
use crate::i18n::tr;
use crate::nav::{self, MenuEntry};
use crate::pages::{mark, Mark};
use crate::rows::{self, Bar, Page, Row};

pub struct Store {
    pub profile: crate::app::Json,
    pub root: Value,
    pub revision: String,
    pub error: Option<String>,
    pub ready: bool,
    pub saving: bool,
    pub loading: bool,
    generation: u64,
}

pub type StoreRef = Rc<RefCell<Store>>;

impl Store {
    pub fn value(&self, path: &[&str]) -> Value {
        let mut v = &self.root;
        for k in path {
            match v.get(k) {
                Some(next) => v = next,
                None => return Value::Null,
            }
        }
        v.clone()
    }

    pub fn entries(&self, path: &[&str]) -> Vec<Value> {
        self.value(path).as_array().cloned().unwrap_or_default().into_iter().filter(|v| v.is_object()).collect()
    }

    pub fn tags(&self, path: &[&str]) -> Vec<String> {
        self.entries(path).iter().filter_map(|e| e["tag"].as_str().map(str::to_string)).collect()
    }
}

pub fn new_store(profile: crate::app::Json) -> StoreRef {
    let store = Rc::new(RefCell::new(Store {
        profile,
        root: Value::Object(Map::new()),
        revision: String::new(),
        error: None,
        ready: false,
        saving: false,
        loading: false,
        generation: 0,
    }));
    load(&store);
    store
}

fn receive(store: &StoreRef, data: &Value) {
    let mut s = store.borrow_mut();
    s.root = decode(data["formatted"].as_str().unwrap_or("{}")).ok().filter(|v| v.is_object()).unwrap_or(Value::Object(Map::new()));
    s.revision = text(&data["revision"]);
    s.ready = true;
}

pub fn load(store: &StoreRef) {
    if store.borrow().saving {
        return;
    }
    let (generation, id) = {
        let mut s = store.borrow_mut();
        s.generation += 1;
        s.loading = true;
        s.error = None;
        (s.generation, s.profile.get("id").and_then(long))
    };
    mark(Mark::ROWS);
    let st = store.clone();
    call("profileConfig", json!({ "id": id }), move |r| {
        if st.borrow().generation != generation {
            return;
        }
        match r {
            Ok(v) => receive(&st, &v),
            Err(e) => st.borrow_mut().error = Some(crate::format::clean_error(&e)),
        }
        st.borrow_mut().loading = false;
        mark(Mark::ROWS);
    });
}

/// set(): writes `value` at `path` (or removes it) over the loaded revision.
pub fn set(store: &StoreRef, path: &[&str], value: Option<Value>, expected: Option<String>, done: impl FnOnce(Result<(), String>) + 'static) {
    {
        let s = store.borrow();
        if !s.ready || s.saving || s.loading {
            done(Err(String::new()));
            return;
        }
        if let Some(e) = &expected {
            if *e != s.revision {
                drop(s);
                let message = tr("Configuration changed; reload it before saving");
                store.borrow_mut().error = Some(message.clone());
                done(Err(message));
                return;
            }
        }
    }
    let mut candidate = store.borrow().root.clone();
    // A configuration whose top level is not an object (or an empty path) cannot be edited here.
    if path.is_empty() || !candidate.is_object() {
        done(Err(tr("Configuration changed; reload it before saving")));
        return;
    }
    {
        let mut parent = &mut candidate;
        for k in &path[..path.len() - 1] {
            let Some(object) = parent.as_object_mut() else { break };
            if !object.get(*k).map(Value::is_object).unwrap_or(false) {
                object.insert(k.to_string(), Value::Object(Map::new()));
            }
            parent = parent.get_mut(*k).expect("inserted above");
        }
        let last = path[path.len() - 1].to_string();
        let Some(map) = parent.as_object_mut() else {
            done(Err(tr("Configuration changed; reload it before saving")));
            return;
        };
        match value {
            Some(v) => {
                map.insert(last, v);
            }
            None => {
                map.shift_remove(&last);
            }
        }
    }
    let (id, revision) = {
        let mut s = store.borrow_mut();
        s.saving = true;
        s.error = None;
        (s.profile.get("id").and_then(long), s.revision.clone())
    };
    mark(Mark::ROWS);
    let st = store.clone();
    call("writeProfile", json!({ "id": id, "content": encode(&candidate), "revision": revision }), move |r| {
        st.borrow_mut().saving = false;
        match r {
            Ok(v) => {
                receive(&st, &v);
                mark(Mark::ROWS);
                done(Ok(()))
            }
            Err(e) => {
                let e = crate::format::clean_error(&e);
                st.borrow_mut().error = Some(e.clone());
                mark(Mark::ROWS);
                done(Err(e))
            }
        }
    });
}

/// launchSet: a set whose failure is reported.
pub fn launch_set(store: &StoreRef, path: &[&str], value: Option<Value>) {
    set(store, path, value, None, |r| {
        if let Err(e) = r {
            if !e.is_empty() {
                app::report(&e)
            }
        }
    });
}

/// A save for an editor opened earlier: guards the revision it saw.
fn saver(store: &StoreRef, path: Vec<&'static str>) -> Rc<dyn Fn(Value, Done)> {
    let st = store.clone();
    let revision = store.borrow().revision.clone();
    Rc::new(move |value: Value, done: Done| {
        set(&st, &path, Some(value), Some(revision.clone()), move |r| done(r.map_err(|e| if e.is_empty() { None } else { Some(e) })));
    })
}

fn error_rows(store: &StoreRef) -> Vec<Row> {
    match &store.borrow().error {
        Some(e) => vec![Row::text(e).danger(), Row::spacer(12)],
        None => Vec::new(),
    }
}

fn capitalized(v: &str) -> String {
    let mut c = v.chars();
    match c.next() {
        Some(f) => f.to_uppercase().collect::<String>() + c.as_str(),
        None => String::new(),
    }
}

/// ProfileConfigurationPage
pub fn open(profile: crate::app::Json, resources: bool) {
    let store = new_store(profile);
    if resources {
        // The Resources page opens straight on the rule sets.
        let st = store.clone();
        rows::push(move || {
            let s = st.borrow();
            if !s.ready {
                let mut p = Page::new(&tr("Resources")).loading(true);
                if let Some(e) = &s.error {
                    p = p.rows(vec![Row::error(e)]);
                }
                return p;
            }
            drop(s);
            list_page(&st, "Rule sets", vec!["route", "rule_set"], json!({ "type": "inline", "tag": "new-rules", "rules": [] }))
        });
        return;
    }
    let st = store.clone();
    rows::push(move || {
        let s = st.borrow();
        let title = tr("Advanced configuration");
        if !s.ready {
            let mut p = Page::new(&title).loading(s.error.is_none());
            if let Some(e) = &s.error {
                let st2 = st.clone();
                p = p.empty(e, "", "info").empty_action(&tr("Retry"), move || load(&st2));
            }
            return p;
        }
        let name = text(s.profile.get("name").unwrap_or(&Value::Null));
        drop(s);
        let mut out = error_rows(&st);
        let a = st.clone();
        out.push(Row::open(&tr("Network"), Some("key"), move || network_page(&a)));
        let a = st.clone();
        out.push(Row::open("DNS", Some("dns"), move || dns_page(&a)));
        let a = st.clone();
        out.push(Row::open(&tr("Routing rules"), Some("rules"), move || route_page(&a)));
        let a = st.clone();
        out.push(Row::open(&tr("Rule sets"), Some("resources"), move || {
            let a = a.clone();
            rows::push(move || list_page(&a, "Rule sets", vec!["route", "rule_set"], json!({ "type": "inline", "tag": "new-rules", "rules": [] })));
        }));
        let a = st.clone();
        out.push(Row::open(&tr("Log settings"), Some("logs"), move || log_page(&a)));
        let a = st.clone();
        out.push(Row::open(&tr("HTTP rewriting"), Some("code"), move || http_rewrite_page(&a)));
        out.push(Row::footer(&name));
        out.push(Row::gap());
        let a = st.clone();
        out.push(Row::open(&tr("Open configuration"), Some("code"), move || {
            let profile = a.borrow().profile.clone();
            let a2 = a.clone();
            crate::pages::profiles::open_editor(profile, Some(Box::new(move || load(&a2))));
        }));
        let st2 = st.clone();
        let saving = st.borrow().saving;
        Page::new(&title).loading(saving).bar(vec![Bar::new("refresh", "Reload", move || load(&st2)).when(!saving)]).rows(out)
    });
}

fn network_page(store: &StoreRef) {
    let st = store.clone();
    rows::push(move || {
        let saving = st.borrow().saving;
        let a = st.clone();
        let b = st.clone();
        let c = st.clone();
        Page::new(&tr("Network")).loading(saving).rows(vec![
            Row::open(&tr("Inbounds"), Some("devices"), move || {
                let a = a.clone();
                rows::push(move || list_page(&a, "Inbounds", vec!["inbounds"], json!({ "type": "socks", "tag": "new-socks", "listen": "127.0.0.1", "listen_port": 2080 })));
            }),
            Row::open(&tr("Outbounds"), Some("proxies"), move || {
                let b = b.clone();
                rows::push(move || list_page(&b, "Outbounds", vec!["outbounds"], json!({ "type": "direct", "tag": "new-direct" })));
            }),
            Row::open(&tr("Endpoints"), Some("devices"), move || {
                let c = c.clone();
                rows::push(move || list_page(&c, "Endpoints", vec!["endpoints"], json!({})));
            }),
        ])
    });
}

fn dns_page(store: &StoreRef) {
    let st = store.clone();
    rows::push(move || {
        let s = st.borrow();
        let saving = s.saving;
        let servers = s.entries(&["dns", "servers"]).len();
        let mut options = vec![String::new()];
        options.extend(s.tags(&["dns", "servers"]));
        let current = s.value(&["dns", "final"]).as_str().unwrap_or("").to_string();
        let reverse = truthy(&s.value(&["dns", "reverse_mapping"]));
        let disable_cache = truthy(&s.value(&["dns", "disable_cache"]));
        let disable_expire = truthy(&s.value(&["dns", "disable_expire"]));
        let capacity = long(&s.value(&["dns", "cache_capacity"])).unwrap_or(0);
        drop(s);
        let (a, b, c, d, e, f, g) = (st.clone(), st.clone(), st.clone(), st.clone(), st.clone(), st.clone(), st.clone());
        Page::new("DNS").loading(saving).rows(vec![
            Row::open(&tr("DNS servers"), Some("dns"), move || {
                let a = a.clone();
                rows::push(move || list_page(&a, "DNS servers", vec!["dns", "servers"], json!({ "type": "local", "tag": "new-dns" })));
            })
            .subtitle(&servers.to_string()),
            Row::open(&tr("DNS rules"), Some("rules"), move || {
                let b = b.clone();
                rows::push(move || list_page(&b, "DNS rules", vec!["dns", "rules"], json!({})));
            }),
            forms::options_row(
                &tr("Default DNS server"),
                options,
                &current,
                Rc::new(|v: &str| if v.is_empty() { tr("Default") } else { v.to_string() }),
                Rc::new(move |v: String| launch_set(&c, &["dns", "final"], if v.is_empty() { None } else { Some(json!(v)) })),
            ),
            Row::switch(&tr("Reverse mapping"), reverse, move |v| launch_set(&d, &["dns", "reverse_mapping"], Some(json!(v)))),
            Row::switch(&tr("Disable DNS cache"), disable_cache, move |v| launch_set(&e, &["dns", "disable_cache"], Some(json!(v)))),
            Row::switch(&tr("Disable DNS cache expiration"), disable_expire, move |v| launch_set(&f, &["dns", "disable_expire"], Some(json!(v)))),
            forms::input_row(
                &tr("DNS cache capacity"),
                &capacity.to_string(),
                true,
                "",
                Some(Rc::new(|v: &str| match v.parse::<i64>() {
                    Ok(n) if (0..=0xffff_ffff).contains(&n) => None,
                    _ => Some(tr("Invalid value")),
                })),
                Rc::new(move |v: String| {
                    if let Ok(n) = v.parse::<i64>() {
                        launch_set(&g, &["dns", "cache_capacity"], Some(json!(n)))
                    }
                }),
            ),
        ])
    });
}

fn route_page(store: &StoreRef) {
    let st = store.clone();
    rows::push(move || {
        let s = st.borrow();
        let saving = s.saving;
        let count = s.entries(&["route", "rules"]).len();
        let mut options = vec![String::new()];
        options.extend(s.tags(&["outbounds"]));
        options.extend(s.tags(&["endpoints"]));
        let current = s.value(&["route", "final"]).as_str().unwrap_or("").to_string();
        let find = truthy(&s.value(&["route", "find_process"]));
        let route = s.value(&["route"]);
        drop(s);
        let (a, b, c, d) = (st.clone(), st.clone(), st.clone(), st.clone());
        Page::new(&tr("Routing rules")).loading(saving).rows(vec![
            Row::open(&tr("Routing rules"), Some("rules"), move || {
                let a = a.clone();
                rows::push(move || list_page(&a, "Routing rules", vec!["route", "rules"], json!({})));
            })
            .subtitle(&count.to_string()),
            forms::options_row(
                &tr("Default outbound"),
                options,
                &current,
                Rc::new(|v: &str| if v.is_empty() { tr("Default") } else { v.to_string() }),
                Rc::new(move |v: String| launch_set(&b, &["route", "final"], if v.is_empty() { None } else { Some(json!(v)) })),
            ),
            Row::switch(&tr("Find process"), find, move |v| launch_set(&c, &["route", "find_process"], Some(json!(v)))),
            Row::gap(),
            Row::open(&tr("Edit route options"), Some("code"), move || {
                let initial = if route.is_null() { json!({}) } else { route.clone() };
                forms::value_editor("Routing rules", &initial, saver(&d, vec!["route"]));
            }),
        ])
    });
}

fn log_page(store: &StoreRef) {
    let st = store.clone();
    rows::push(move || {
        let s = st.borrow();
        let saving = s.saving;
        let enabled = !truthy(&s.value(&["log", "disabled"]));
        let level = s.value(&["log", "level"]).as_str().unwrap_or("warn").to_string();
        let timestamp = truthy(&s.value(&["log", "timestamp"]));
        drop(s);
        let (a, b, c) = (st.clone(), st.clone(), st.clone());
        Page::new(&tr("Log settings")).loading(saving).rows(vec![
            Row::switch(&tr("Enable logging"), enabled, move |v| launch_set(&a, &["log", "disabled"], Some(json!(!v)))),
            forms::options_row(
                &tr("Log level"),
                ["trace", "debug", "info", "warn", "error", "fatal", "panic"].iter().map(|s| s.to_string()).collect(),
                &level,
                Rc::new(|v: &str| tr(&capitalized(v))),
                Rc::new(move |v: String| launch_set(&b, &["log", "level"], Some(json!(v)))),
            ),
            Row::switch(&tr("Log timestamps"), timestamp, move |v| launch_set(&c, &["log", "timestamp"], Some(json!(v)))),
        ])
    });
}

const HTTP_REWRITE: [&str; 2] = ["experimental", "http_rewrite"];

fn hr(rest: &[&'static str]) -> Vec<&'static str> {
    let mut v: Vec<&'static str> = HTTP_REWRITE.to_vec();
    v.extend_from_slice(rest);
    v
}

fn http_rewrite_page(store: &StoreRef) {
    let st = store.clone();
    rows::push(move || {
        let s = st.borrow();
        let saving = s.saving;
        let enabled = truthy(&s.value(&hr(&["enabled"])));
        let mitm = s.value(&hr(&["mitm"]));
        let hosts: Vec<String> = s.value(&hr(&["mitm", "hosts"])).as_array().cloned().unwrap_or_default().iter().map(text).collect();
        let excluded: Vec<String> = s.value(&hr(&["mitm", "excluded_hosts"])).as_array().cloned().unwrap_or_default().iter().map(text).collect();
        let resources = s.value(&hr(&["resources"]));
        let whole = s.value(&HTTP_REWRITE);
        let counts: Vec<usize> = ["header_rewrite", "url_rewrite", "map_remote", "body_rewrite", "map_local", "scripts"].iter().map(|k| s.entries(&hr(&[k])).len()).collect();
        drop(s);
        let pattern = "^https?://example\\.com/(.*)";
        let sections: Vec<(&str, &'static str, Value)> = vec![
            ("Header Rewrite", "header_rewrite", json!({ "phase": "request", "operation": "add", "header": "X-B-box", "value": "1" })),
            ("URL Rewrite", "url_rewrite", json!({ "action": "header", "replacement": "https://example.com/$1" })),
            ("Map Remote", "map_remote", json!({ "replacement": "https://example.net/$1" })),
            ("Body Rewrite", "body_rewrite", json!({ "phase": "response", "regex": "old", "replacement": "new" })),
            ("Map Local / API Mock", "map_local", json!({ "status": 200, "data_type": "text", "data": "{}", "headers": [{ "name": "Content-Type", "value": "application/json" }] })),
            ("JavaScript", "scripts", json!({ "phase": "response", "requires_body": true, "source": "$done({body: $response.body});" })),
        ];
        let mut out = Vec::new();
        out.extend(error_rows(&st));
        let a = st.clone();
        out.push(Row::switch(&tr("Enable HTTP rewriting"), enabled, move |v| launch_set(&a, &hr(&["enabled"]), Some(json!(v)))).enabled(!saving));
        let a = st.clone();
        out.push(
            Row::open("MITM", Some("lock"), move || {
                let initial = if mitm.is_null() { json!({ "enabled": false, "hosts": [], "excluded_hosts": [], "block_quic": false }) } else { mitm.clone() };
                forms::value_editor("MITM", &initial, saver(&a, hr(&["mitm"])));
            })
            .subtitle(&tr("Choose HTTPS hosts in this profile")),
        );
        for (label, key, items) in [("MITM hosts", "hosts", hosts), ("MITM excluded hosts", "excluded_hosts", excluded)] {
            let a = st.clone();
            let count = items.len();
            out.push(
                Row::open(&tr(label), Some("compose"), move || {
                    let a = a.clone();
                    let revision = a.borrow().revision.clone();
                    let path = hr(&["mitm", key]);
                    forms::list_quick_edit(
                        &tr(label),
                        items.clone(),
                        Some("*.example.com"),
                        None,
                        None,
                        Rc::new(move |entries: Vec<String>, done: Done| {
                            set(&a, &path, Some(json!(entries)), Some(revision.clone()), move |r| done(r.map_err(|e| if e.is_empty() { None } else { Some(e) })));
                        }),
                    );
                })
                .subtitle(&count.to_string()),
            );
        }
        out.push(Row::open(&tr("MITM certificate"), Some("key"), || crate::pages::tools::open_mitm_certificate()));
        out.push(Row::gap());
        for (i, (title, key, initial)) in sections.into_iter().enumerate() {
            let a = st.clone();
            let mut initial_map = json!({ "pattern": pattern, "disabled": true });
            for (k, v) in initial.as_object().unwrap() {
                initial_map.as_object_mut().unwrap().insert(k.clone(), v.clone());
            }
            out.push(
                Row::open(title, Some("code"), move || {
                    let a = a.clone();
                    let init = initial_map.clone();
                    rows::push(move || list_page_titled(&a, title, title.to_string(), hr(&[key]), init.clone()));
                })
                .subtitle(&counts[i].to_string()),
            );
        }
        out.push(Row::gap());
        let a = st.clone();
        out.push(Row::open(&tr("Embedded rewrite resources"), Some("resources"), move || {
            let initial = if resources.is_null() { json!({}) } else { resources.clone() };
            forms::value_editor("Embedded rewrite resources", &initial, saver(&a, hr(&["resources"])));
        }));
        let a = st.clone();
        out.push(Row::open(&tr("Edit HTTP rewrite options"), Some("code"), move || {
            let initial = if whole.is_null() { json!({ "enabled": false }) } else { whole.clone() };
            forms::value_editor("HTTP rewriting", &initial, saver(&a, HTTP_REWRITE.to_vec()));
        }));
        Page::new(&tr("HTTP rewriting")).loading(saving).rows(out)
    });
}

/// ConfigListPage
pub fn list_page(store: &StoreRef, title: &'static str, path: Vec<&'static str>, initial: Value) -> Page {
    list_page_titled(store, title, tr(title), path, initial)
}

fn edit_entry(store: &StoreRef, path: Vec<&'static str>, initial: Value, index: Option<usize>) {
    let current = store.borrow().entries(&path);
    let revision = store.borrow().revision.clone();
    let mut value = match index {
        None => initial,
        Some(i) => current.get(i).cloned().unwrap_or(json!({})),
    };
    if index.is_none() {
        if let Some(base) = value["tag"].as_str().map(str::to_string) {
            let tags = store.borrow().tags(&path);
            let mut suffix = 2;
            while value["tag"].as_str().map(|t| tags.iter().any(|x| x == t)).unwrap_or(false) {
                value["tag"] = json!(format!("{base}-{suffix}"));
                suffix += 1;
            }
        }
    }
    let st = store.clone();
    forms::value_editor(
        if index.is_none() { "Add" } else { "Edit" },
        &value,
        Rc::new(move |edited: Value, done: Done| {
            if !edited.is_object() {
                done(Err(Some(tr("JSON object required"))));
                return;
            }
            let mut next = current.clone();
            match index {
                None => next.push(edited),
                Some(i) if i < next.len() => next[i] = edited,
                Some(_) => next.push(edited),
            }
            set(&st, &path, Some(Value::Array(next)), Some(revision.clone()), move |r| done(r.map_err(|e| if e.is_empty() { None } else { Some(e) })));
        }),
    );
}

fn list_page_titled(store: &StoreRef, title_key: &'static str, title: String, path: Vec<&'static str>, initial: Value) -> Page {
    let s = store.borrow();
    let saving = s.saving;
    let entries = s.entries(&path);
    drop(s);
    let add = {
        let (st, p, i) = (store.clone(), path.clone(), initial.clone());
        move || edit_entry(&st, p.clone(), i.clone(), None)
    };
    let quick = {
        let (st, p) = (store.clone(), path.clone());
        let title = title.clone();
        move || {
            let current = st.borrow().entries(&p);
            let revision = st.borrow().revision.clone();
            let content = current.iter().map(encode_line).collect::<Vec<_>>().join("\n");
            let (st2, p2) = (st.clone(), p.clone());
            forms::quick_edit(
                &title,
                &content,
                Some("{\"tag\": \"example\"}"),
                Rc::new(move |content: String, done: Done| {
                    let mut issues = Vec::new();
                    let mut next = Vec::new();
                    for (line, text) in forms::parse_lines(&content) {
                        match decode(&text) {
                            Ok(v) if v.is_object() => next.push(v),
                            _ => issues.push(forms::line_issue(line, &tr("JSON object required"))),
                        }
                    }
                    if !issues.is_empty() {
                        done(Err(Some(issues.into_iter().take(3).collect::<Vec<_>>().join("\n"))));
                        return;
                    }
                    set(&st2, &p2, Some(Value::Array(next)), Some(revision.clone()), move |r| done(r.map_err(|_| None)));
                }),
            );
        }
    };
    let reload = {
        let st = store.clone();
        move || load(&st)
    };
    let reorder = {
        let (st, p) = (store.clone(), path.clone());
        move || order_page(&st, p.clone())
    };
    let mut bar = vec![
        Bar::new("add", "Add", add.clone()).when(!saving),
        Bar::new("compose", "Quick edit", quick).when(!saving),
        Bar::new("refresh", "Reload", reload).when(!saving),
    ];
    if entries.len() > 1 {
        bar.push(Bar::new("sort", "Reorder", reorder).when(!saving));
    }
    let mut out = Vec::new();
    for (index, entry) in entries.iter().enumerate() {
        let label = entry
            .get("tag")
            .or_else(|| entry.get("action"))
            .or_else(|| entry.get("type"))
            .map(text)
            .unwrap_or_else(|| format!("{} {}", title, index + 1));
        let subtitle = entry
            .get("url")
            .or_else(|| entry.get("path"))
            .or_else(|| entry.get("server"))
            .map(text)
            .unwrap_or_else(|| encode(entry).split_whitespace().collect::<Vec<_>>().join(" "));
        let (st, p, i) = (store.clone(), path.clone(), initial.clone());
        let (st_m, p_m, i_m, label_m) = (store.clone(), path.clone(), initial.clone(), label.clone());
        out.push(
            Row::card(&label, &subtitle, move || edit_entry(&st, p.clone(), i.clone(), Some(index)))
                .icon("profiles")
                .lines(2)
                .menu(move || {
                    let (st, p, i) = (st_m.clone(), p_m.clone(), i_m.clone());
                    let (st_d, p_d, label_d) = (st_m.clone(), p_m.clone(), label_m.clone());
                    vec![
                        MenuEntry::act("Edit", "edit", move || edit_entry(&st, p.clone(), i.clone(), Some(index))),
                        MenuEntry::act("Delete", "delete", move || {
                            let (st, p) = (st_d.clone(), p_d.clone());
                            nav::confirm("Delete", Some(&label_d), "Delete", move |ok| {
                                if ok {
                                    let mut next = st.borrow().entries(&p);
                                    if index < next.len() {
                                        next.remove(index);
                                    }
                                    let revision = st.borrow().revision.clone();
                                    set(&st, &p, Some(Value::Array(next)), Some(revision), |r| {
                                        if let Err(e) = r {
                                            if !e.is_empty() {
                                                app::report(&e)
                                            }
                                        }
                                    });
                                }
                            });
                        })
                        .danger(),
                    ]
                }),
        );
        out.push(Row::spacer(8));
    }
    let _ = title_key;
    let mut page = Page::new(&title).loading(saving).bar(bar).rows(out);
    if entries.is_empty() {
        page = page.empty(&tr("No results"), "", "profiles").empty_action(&tr("Add"), add);
    }
    page
}

/// ConfigOrderPage: move entries up and down, then save.
fn order_page(store: &StoreRef, path: Vec<&'static str>) {
    let entries = Rc::new(RefCell::new(store.borrow().entries(&path)));
    let original = encode(&Value::Array(entries.borrow().clone()));
    let revision = store.borrow().revision.clone();
    let saving = Rc::new(RefCell::new(false));
    let error: Rc<RefCell<Option<String>>> = Rc::new(RefCell::new(None));
    let idc = Rc::new(RefCell::new(0));
    let (en, sv, er, st, idc2) = (entries.clone(), saving.clone(), error.clone(), store.clone(), idc.clone());
    let id = rows::form(move || {
        let list = en.borrow().clone();
        let busy = *sv.borrow();
        let save = {
            let (en, sv, er, st, p, rev, idc) = (en.clone(), sv.clone(), er.clone(), st.clone(), path.clone(), revision.clone(), idc2.clone());
            move || {
                *sv.borrow_mut() = true;
                mark(Mark::ROWS);
                let (sv, er, idc) = (sv.clone(), er.clone(), idc.clone());
                set(&st, &p, Some(Value::Array(en.borrow().clone())), Some(rev.clone()), move |r| {
                    *sv.borrow_mut() = false;
                    match r {
                        Ok(()) => nav::close(*idc.borrow()),
                        Err(e) => *er.borrow_mut() = Some(e),
                    }
                    mark(Mark::ROWS);
                });
            }
        };
        let mut out = Vec::new();
        if let Some(e) = er.borrow().as_ref() {
            out.push(Row::text(e).danger());
        }
        let n = list.len();
        for (i, entry) in list.iter().enumerate() {
            let label = entry.get("tag").or_else(|| entry.get("action")).map(text).unwrap_or_else(|| (i + 1).to_string());
            let en2 = en.clone();
            let row = Row::item(&label).menu(move || {
                let (a, b) = (en2.clone(), en2.clone());
                vec![
                    MenuEntry::act("Move up", "arrow-up", move || {
                        if i > 0 {
                            a.borrow_mut().swap(i, i - 1);
                            mark(Mark::ROWS);
                        }
                    })
                    .when(i > 0),
                    MenuEntry::act("Move down", "arrow-down", move || {
                        if i + 1 < n {
                            b.borrow_mut().swap(i, i + 1);
                            mark(Mark::ROWS);
                        }
                    })
                    .when(i + 1 < n),
                ]
            });
            out.push(row);
        }
        Page::new(&tr("Reorder")).loading(busy).confirm(Bar::new("check", "Save", save).when(!busy)).rows(out)
    });
    *idc.borrow_mut() = id;
    let en = entries.clone();
    nav::guard(id, move || encode(&Value::Array(en.borrow().clone())) != original);
    nav::busy_guard(id, move || *saving.borrow());
    let _ = error;
}

pub fn _obj(v: &Value) -> crate::app::Json {
    obj(v)
}
