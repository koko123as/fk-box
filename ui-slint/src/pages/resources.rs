//! Resources (pages/ResourcesPage.kt): geo file auto update, its interval in
//! hours, and one row per geo file with its size, last update, single update,
//! URL edit and copy. Rust-built rows.

use std::cell::RefCell;
use std::collections::HashSet;
use std::rc::Rc;

use serde_json::{json, Value};

use crate::app::{self, data, long, num, rows as list, text, truthy};
use crate::format;
use crate::i18n::tr;
use crate::nav::{self, MenuEntry};
use crate::pages::{mark, Mark};
use crate::rows::{self, Bar, Page, Row};

#[derive(Default)]
struct Res {
    items: Option<Vec<Value>>,
    updating: HashSet<String>,
}

type ResRef = Rc<RefCell<Res>>;

fn reload(st: &ResRef) {
    let a = st.clone();
    app::call("geoList", json!({}), move |r| {
        let mut s = a.borrow_mut();
        match r {
            Ok(v) => s.items = Some(list(&v)),
            Err(e) => {
                if s.items.is_none() {
                    s.items = Some(Vec::new());
                }
                app::report(&e);
            }
        }
        drop(s);
        mark(Mark::ROWS);
    });
}

/// updateOne: `done(false)` when the update failed; the old file stays either way.
fn update_one(st: &ResRef, name: String, label: String, announce: bool, done: impl FnOnce(bool) + 'static) {
    if !st.borrow_mut().updating.insert(name.clone()) {
        done(true);
        return;
    }
    mark(Mark::ROWS);
    let a = st.clone();
    app::call("geoUpdate", json!({ "name": name }), move |r| {
        a.borrow_mut().updating.remove(&name);
        mark(Mark::ROWS);
        match r {
            Ok(changed) => {
                if announce {
                    nav::toast(&format!("{label}: {}", tr(if truthy(&changed) { "Updated" } else { "Already up to date" })), false);
                }
                done(true)
            }
            Err(e) => {
                app::report(&format!("{label}: {e}"));
                done(false)
            }
        }
    });
}

fn update_all(st: &ResRef) {
    let Some(items) = st.borrow().items.clone() else { return };
    let remaining = Rc::new(RefCell::new((items.len(), true)));
    for item in items {
        let (a, r) = (st.clone(), remaining.clone());
        update_one(st, text(&item["name"]), text(&item["label"]), false, move |ok| {
            let mut x = r.borrow_mut();
            x.0 -= 1;
            x.1 &= ok;
            if x.0 == 0 {
                let all = x.1;
                drop(x);
                reload(&a);
                if all {
                    app::message("Updated");
                }
            }
        });
    }
}

fn valid_url(v: &str) -> bool {
    let t = v.trim();
    let rest = t.strip_prefix("http://").or_else(|| t.strip_prefix("https://"));
    match rest {
        Some(r) => r.chars().next().map(|c| !c.is_whitespace() && c != '/').unwrap_or(false),
        None => false,
    }
}

fn edit_url(st: &ResRef, name: String, label: String, url: String, default_url: String) {
    let mut spec = nav::InputSpec::new(&format!("{label} · {}", tr("Download URL")), &url);
    spec.reset = Some(default_url);
    spec.validator = Some(Box::new(|v: &str| if valid_url(v) { None } else { Some(tr("Not a valid HTTP or HTTPS URL")) }));
    let a = st.clone();
    nav::input(spec, move |value| {
        let Some(value) = value else { return };
        let b = a.clone();
        app::fetch("geoSetUrl", json!({ "name": name, "url": value.trim() }), move |v| {
            b.borrow_mut().items = Some(list(&v));
            mark(Mark::ROWS);
        });
    });
}

pub fn open() {
    let st: ResRef = Rc::new(RefCell::new(Res::default()));
    reload(&st);
    rows::push(move || page(&st));
}

fn page(st: &ResRef) -> Page {
    let s = st.borrow();
    let (auto, interval) = data(|d| (truthy(&d.setting("geoAutoUpdate")), long(&d.setting("geoUpdateInterval")).unwrap_or(24)));
    let busy = !s.updating.is_empty();
    let mut rows = vec![Row::switch(&tr("Auto update"), auto, |v| app::set_setting("geoAutoUpdate", json!(v), false, false)).key("auto")];
    if auto {
        let hours = tr("hours");
        rows.push(Row::item(&tr("Update interval")).value(&format!("{interval} {hours}")).key("interval").on(move |_| {
            let mut spec = nav::InputSpec::new(&tr("Update interval"), &interval.to_string());
            spec.number = true;
            spec.suffix = tr("hours");
            spec.validator = Some(Box::new(|v: &str| if (1..=8760).contains(&v.trim().parse::<i64>().unwrap_or(0)) { None } else { Some("1–8760".into()) }));
            nav::input(spec, |v| {
                if let Some(n) = v.and_then(|v| v.trim().parse::<i64>().ok()) {
                    app::set_setting("geoUpdateInterval", json!(n), false, false);
                }
            });
        }));
    }
    rows.push(Row::section(&tr("Geo resources")));
    for item in s.items.clone().unwrap_or_default() {
        let name = text(&item["name"]);
        let label = text(&item["label"]);
        let url = item.get("url").and_then(Value::as_str).unwrap_or("").to_string();
        let default_url = item.get("defaultUrl").and_then(Value::as_str).unwrap_or("").to_string();
        let updated_at = long(&item["updatedAt"]);
        let source = match item.get("source").and_then(Value::as_str) {
            Some("builtin") => Some(tr("Built in")),
            Some("download") => Some(tr("Downloaded")),
            None => None,
            Some(_) => Some("mihomo".to_string()),
        };
        let subtitle = match updated_at {
            Some(ms) => {
                let date: String = format::local_date(ms).chars().take(16).collect();
                [Some(date), source].into_iter().flatten().collect::<Vec<_>>().join(" · ")
            }
            None => tr("Unknown"),
        };
        let updating = s.updating.contains(&name);
        let size = if item["size"].is_null() { "—".to_string() } else { format::bytes(num(&item["size"])) };
        let (a, n, l, u, d) = (st.clone(), name.clone(), label.clone(), url.clone(), default_url.clone());
        let (b, n2, l2, u2, d2) = (st.clone(), name.clone(), label.clone(), url.clone(), default_url.clone());
        let mut row = Row::item(&label)
            .subtitle(&subtitle)
            .value(if updating { "" } else { &size })
            .loading(updating)
            .key(&name)
            .on(move |_| edit_url(&a, n.clone(), l.clone(), u.clone(), d.clone()))
            .menu(move || {
                let mut m = Vec::new();
                if !b.borrow().updating.contains(&n2) {
                    let (c, n, l) = (b.clone(), n2.clone(), l2.clone());
                    m.push(MenuEntry::act("Update", "sync", move || {
                        let c2 = c.clone();
                        update_one(&c, n.clone(), l.clone(), true, move |_| reload(&c2));
                    }));
                }
                let (c, n, l, u, d) = (b.clone(), n2.clone(), l2.clone(), u2.clone(), d2.clone());
                m.push(MenuEntry::act("Edit", "edit", move || edit_url(&c, n.clone(), l.clone(), u.clone(), d.clone())));
                let u = u2.clone();
                m.push(MenuEntry::act("Copy link", "copy", move || {
                    app::copy_text(&u);
                    app::message("Copied");
                }));
                m
            });
        // ContextMenuRegion: the menu opens on a long press, not from a trailing button.
        row.data.has_trailing = false;
        rows.push(row);
    }
    rows.push(Row::footer(&tr("Xray and mihomo share these files. A failed update keeps the current file; changes apply when the core next starts.")));
    let a = st.clone();
    let empty = s.items.as_ref().map(Vec::is_empty).unwrap_or(true);
    Page::new(&tr("Resources"))
        .loading(s.items.is_none())
        .bar(vec![Bar::new("sync", "Update all", move || update_all(&a)).when(!empty && !busy).loading(busy)])
        .rows(rows)
}
