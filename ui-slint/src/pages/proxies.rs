//! Proxies (pages/ProxiesPage.kt): filtering, sorting, testing, selection,
//! folding (saved per profile in proxyUnfolded), the page and node menus.

use std::cell::RefCell;
use std::collections::HashSet;
use std::rc::Rc;

use serde_json::{json, Map, Value};
use slint::{ComponentHandle, Model, ModelRc, VecModel};

use crate::app::{self, data, long, rows, ss, strings, text, truthy};
use crate::format;
use crate::i18n::tr;
use crate::nav::{self, MenuEntry};
use crate::pages::{mark, Mark};
use crate::{AppWindow, GroupRow, KeyValue, NodeRow, Proxies};

#[derive(Default)]
struct Local {
    query: String,
    testing: HashSet<String>,
    saving: bool,
    unfolded: Option<(String, Vec<String>)>,
    details: Vec<(String, String)>,
}

thread_local! {
    static LOCAL: RefCell<Local> = RefCell::new(Local::default());
}

fn local<R>(f: impl FnOnce(&mut Local) -> R) -> R {
    LOCAL.with(|l| f(&mut l.borrow_mut()))
}

fn profile_key() -> String {
    data(|d| if d.remote() { format!("remote:{}", text(d.dashboard.get("remote").unwrap_or(&Value::Null))) } else { format!("local:{}", d.selected_profile_id()) })
}

/// savedUnfolded: the groups open for this profile.
fn unfolded() -> Vec<String> {
    let key = profile_key();
    if let Some((k, list)) = local(|l| l.unfolded.clone()) {
        if k == key {
            return list;
        }
    }
    let list = data(|d| d.pref("proxyUnfolded").get(&key).map(strings).unwrap_or_default());
    local(|l| l.unfolded = Some((key, list.clone())));
    list
}

fn nodes(group: &Value, query: &str, hide: bool, sort: &str) -> Vec<Value> {
    let words: Vec<String> = query.to_lowercase().split_whitespace().map(str::to_string).collect();
    let mut result: Vec<Value> = rows(&group["items"])
        .into_iter()
        .filter(|n| {
            let tag = text(&n["tag"]).to_lowercase();
            words.iter().all(|w| tag.contains(w)) && !(hide && long(&n["testedAt"]).unwrap_or(0) > 0 && long(&n["delay"]).unwrap_or(0) <= 0)
        })
        .collect();
    match sort {
        "Name" => result.sort_by_key(|n| text(&n["tag"])),
        "Latency" => result.sort_by_key(|n| {
            let d = long(&n["delay"]).unwrap_or(0);
            if d > 0 {
                d
            } else {
                i32::MAX as i64
            }
        }),
        _ => {}
    }
    result
}

pub fn project(w: &AppWindow) {
    let p = w.global::<Proxies>();
    let (query, testing) = local(|l| (l.query.clone(), l.testing.clone()));
    let open = unfolded();
    let (list, density, size, layout, icon, hide, sort) = data(|d| {
        (
            d.pref_str("proxyLayout", "Tabs") == "List",
            d.pref_str("proxyDensity", "Standard"),
            d.preferences.get("proxyCardSize").and_then(|v| v.as_str()).map(str::to_string),
            d.pref_str("proxyLayout", ""),
            d.pref_str("proxyIconStyle", "Filled"),
            truthy(&d.pref("hideTimeoutProxies")),
            d.pref_str("proxySort", "Default"),
        )
    });
    p.set_list(list);
    p.set_density_adjust(match density.as_str() {
        "Tight" => 1,
        "Loose" => -1,
        _ => 0,
    });
    p.set_compact(size.as_deref() == Some("Compact") || (size.is_none() && layout == "Compact"));
    p.set_loose(size.as_deref() == Some("Loose"));
    p.set_icon_style(icon.into());
    // A group's node model is updated in place when the group is still there,
    // so a delay update does not rebuild every node card.
    let current = p.get_groups();
    let reuse = |tag: &str, items: Vec<NodeRow>| -> ModelRc<NodeRow> {
        let existing = (0..current.row_count()).filter_map(|i| current.row_data(i)).find(|g| g.tag == tag).map(|g| g.items);
        match existing {
            Some(model) => match crate::rows::update(model.clone(), items) {
                None => model,
                Some(fresh) => fresh,
            },
            None => ModelRc::new(VecModel::from(items)),
        }
    };
    let groups: Vec<GroupRow> = data(|d| {
        d.groups
            .iter()
            .map(|g| {
                let tag = text(&g["tag"]);
                let selected = text(&g["selected"]);
                let items: Vec<NodeRow> = nodes(g, &query, hide, &sort)
                    .iter()
                    .map(|n| {
                        let node = text(&n["tag"]);
                        let delay = long(&n["delay"]).unwrap_or(0);
                        let tested = long(&n["testedAt"]).unwrap_or(0) > 0;
                        NodeRow {
                            selected: node == selected,
                            testing: testing.contains(&tag) || testing.contains(&node),
                            tag: ss(&node),
                            kind: ss(text(&n["type"])),
                            delay: ss(if delay > 0 { format!("{delay} ms") } else if tested { tr("Unreachable") } else { "—".into() }),
                            tested,
                            delay_ms: delay as i32,
                        }
                    })
                    .collect();
                let selected_index = items.iter().position(|n| n.selected).map(|i| i as i32).unwrap_or(-1);
                GroupRow {
                    kind: ss(text(&g["type"])),
                    monogram: ss(format::monogram(&tag)),
                    selected: ss(&selected),
                    selectable: truthy(&g["selectable"]),
                    expanded: open.contains(&tag),
                    testing: testing.contains(&tag),
                    selected_index,
                    items: reuse(&tag, items),
                    tag: ss(&tag),
                }
            })
            .collect()
    });
    let count = groups.len() as i32;
    if let Some(m) = crate::rows::update(p.get_groups(), groups) {
        p.set_groups(m);
    }
    if p.get_tab() >= count {
        p.set_tab((count - 1).max(0));
    }
    let details = local(|l| l.details.clone());
    if let Some(m) = crate::rows::update(p.get_details(), details.into_iter().map(|(k, v)| KeyValue { key: k.into(), value: v.into() }).collect::<Vec<_>>()) {
        p.set_details(m);
    }
}

/// test(tag): a URL test of a group or a node.
fn test(tag: String) {
    if !data(|d| d.running()) || local(|l| l.testing.contains(&tag)) {
        return;
    }
    local(|l| l.testing.insert(tag.clone()));
    mark(Mark::PROXIES | Mark::DASH);
    app::act_then("urlTest", json!({ "tag": tag }), move |_| {
        local(|l| l.testing.remove(&tag));
        mark(Mark::PROXIES | Mark::DASH);
    });
}

fn select(group: &str, node: &str) {
    let ok = data(|d| {
        d.running()
            && d.groups.iter().any(|g| text(&g["tag"]) == group && truthy(&g["selectable"]) && text(&g["selected"]) != node)
    });
    if !ok {
        return;
    }
    app::act_then("selectOutbound", json!({ "group": group, "tag": node }), |ok| {
        if ok && data(|d| truthy(&d.pref("closeConnectionsOnSelect"))) {
            app::act("closeConnections", json!({}));
        }
    });
}

fn toggle(name: &str) {
    if local(|l| l.saving) {
        return;
    }
    let key = profile_key();
    let old = unfolded();
    let mut next = old.clone();
    if next.iter().any(|n| n == name) {
        next.retain(|n| n != name);
    } else {
        next.push(name.to_string());
    }
    local(|l| {
        l.unfolded = Some((key.clone(), next.clone()));
        l.saving = true;
    });
    mark(Mark::PROXIES);
    let mut saved: Map<String, Value> = data(|d| d.pref("proxyUnfolded").as_object().cloned().unwrap_or_default());
    saved.remove(&key);
    saved.insert(key.clone(), json!(next));
    while saved.len() > 128 {
        let first = saved.keys().next().cloned();
        if let Some(first) = first {
            saved.remove(&first);
        }
    }
    app::set_preferences(json!({ "proxyUnfolded": saved }), move |ok| {
        local(|l| {
            l.saving = false;
            if !ok {
                l.unfolded = Some((key, old));
            }
        });
        mark(Mark::PROXIES);
    });
}

pub fn act(id: &str, arg: &str) -> bool {
    match id {
        "proxies-test" | "test-group" | "test-node" => test(arg.to_string()),
        "proxies-search" => {
            local(|l| l.query = arg.to_string());
            mark(Mark::PROXIES);
        }
        "toggle-group" => toggle(arg),
        _ => return false,
    }
    true
}

pub fn act2(id: &str, a: &str, b: &str) -> bool {
    match id {
        "select-node" => select(a, b),
        _ => return false,
    }
    true
}

fn options(label: &str, icon: &'static str, key: &'static str, values: &[&'static str], fallback: &str) -> MenuEntry {
    let current = data(|d| d.pref_str(key, fallback));
    MenuEntry::submenu(
        label,
        icon,
        values
            .iter()
            .map(|v| {
                let v = *v;
                MenuEntry::plain(v, move || app::set_preference(key, json!(v))).checked(current == v)
            })
            .collect(),
    )
}

pub fn menu(key: &str) -> Vec<MenuEntry> {
    if key == "proxies" {
        let (list, hide, close) = data(|d| (d.pref_str("proxyLayout", "Tabs") == "List", truthy(&d.pref("hideTimeoutProxies")), truthy(&d.pref("closeConnectionsOnSelect"))));
        let mut items = vec![
            options("Style", "apps-list", "proxyLayout", &["Tabs", "List"], "Tabs"),
            options("Sort", "sort", "proxySort", &["Default", "Latency", "Name"], "Default"),
            options("Layout", "dashboard", "proxyDensity", &["Tight", "Standard", "Loose"], "Standard"),
            options("Size", "text-size", "proxyCardSize", &["Compact", "Standard", "Loose"], "Standard"),
        ];
        if list {
            items.push(options("Icon style", "icon-tile", "proxyIconStyle", &["Filled", "Plain", "Hidden"], "Filled"));
        }
        items.push(MenuEntry::act("Hide timeout proxies", "eye-off", move || app::set_preference("hideTimeoutProxies", json!(!hide))).checked(hide).section(""));
        items.push(MenuEntry::act("Test all", "bolt", || {
            if data(|d| d.running()) {
                for tag in data(|d| d.groups.iter().map(|g| text(&g["tag"])).collect::<Vec<_>>()) {
                    test(tag);
                }
            }
        }));
        items.push(MenuEntry::act("Close connections after switching", "connections", move || app::set_preference("closeConnectionsOnSelect", json!(!close))).checked(close));
        return items;
    }
    if key == "proxy-tabs" {
        let current = crate::app::window().map(|w| w.global::<Proxies>().get_tab()).unwrap_or(0);
        return data(|d| d.groups.iter().map(|g| text(&g["tag"])).collect::<Vec<_>>())
            .into_iter()
            .enumerate()
            .map(|(i, tag)| {
                // A group's own name, not translated.
                let entry = MenuEntry::new("", None, Some(Rc::new(move || crate::app::with_window(|w| w.global::<Proxies>().set_tab(i as i32)))));
                MenuEntry { label: tag, ..entry }.checked(i as i32 == current)
            })
            .collect();
    }
    if let Some(rest) = key.strip_prefix("node:") {
        let Some((group_tag, node_tag)) = rest.split_once('\n') else { return Vec::new() };
        let (group, node, running) = data(|d| {
            let g = d.groups.iter().find(|g| text(&g["tag"]) == group_tag).cloned();
            let n = g.as_ref().and_then(|g| rows(&g["items"]).into_iter().find(|n| text(&n["tag"]) == node_tag));
            (g, n, d.running())
        });
        let (Some(group), Some(node)) = (group, node) else { return Vec::new() };
        let testing = local(|l| l.testing.contains(group_tag) || l.testing.contains(node_tag));
        let selected = text(&group["selected"]) == node_tag;
        let delay = long(&node["delay"]).unwrap_or(0);
        let tested = long(&node["testedAt"]).unwrap_or(0) > 0;
        let delay_text = if delay > 0 { format!("{delay} ms") } else if tested { tr("Unreachable") } else { "—".into() };
        let mut items = Vec::new();
        let (g, n) = (group_tag.to_string(), node_tag.to_string());
        if truthy(&group["selectable"]) {
            let (g, n) = (g.clone(), n.clone());
            items.push(MenuEntry::act("Select", "check-circle", move || select(&g, &n)).checked(selected).when(running));
        }
        {
            let n = n.clone();
            items.push(MenuEntry::act("Test latency", "bolt", move || test(n.clone())).when(running && !testing));
        }
        let details = vec![
            (tr("Name"), n.clone()),
            (tr("Protocol"), text(&node["type"])),
            (tr("Latency"), delay_text),
            (tr("Proxy groups"), g.clone()),
        ];
        items.push(MenuEntry::act("Details", "info", move || {
            local(|l| l.details = details.clone());
            mark(Mark::PROXIES);
            nav::form("node-details", "");
        }));
        return items;
    }
    Vec::new()
}
