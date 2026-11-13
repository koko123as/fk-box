//! Traffic routing (pages/RoutingPage.kt): one rule set per core
//! (bridge/CoreRouting.kt's wire form, kept as JSON here), the rule editor,
//! the preview and the last start's status. Rust-built rows.

use std::cell::RefCell;
use std::rc::Rc;

use serde_json::{json, Value};

use crate::app::{self, data, long, text, truthy};
use crate::format;
use crate::forms;
use crate::i18n::tr;
use crate::nav::{self, MenuEntry};
use crate::pages::{mark, Mark};
use crate::rows::{self, Bar, Page, Row};

const CORES: [&str; 3] = ["sing-box", "xray", "mihomo"];
const MODES: [&str; 3] = ["prepend", "override", "off"];
// In the order the editor offers them; "all" with apps, a network or ports
// is a rule that matches by those alone (an app-only rule).
const CONTENTS: [&str; 4] = ["match", "preset", "list", "all"];
const TARGETS: [&str; 4] = ["proxy", "direct", "block", "node"];
const FINALS: [&str; 5] = ["config", "proxy", "direct", "block", "node"];
const NETWORKS: [&str; 3] = ["any", "tcp", "udp"];
const PRESETS: [(&str, &str, &str); 4] = [
    ("cn-direct", "Bypass mainland China", "geosite:cn, geoip:cn and private addresses go direct"),
    ("gfw-proxy", "GFW list through proxy, the rest direct", "geosite:gfw through the proxy; all other traffic direct"),
    ("ads-block", "Block ads", "geosite:category-ads-all is blocked"),
    ("lan-direct", "LAN direct", "Private and LAN addresses go direct"),
];

fn core_title(core: &str) -> &str {
    match core {
        "xray" => "Xray",
        other => other,
    }
}

fn mode_title(v: &str) -> String {
    tr(match v {
        "prepend" => "Before the profile's rules",
        "override" => "Replace the profile's rules",
        "off" => "Off (profile only)",
        o => o,
    })
}

fn target_title(v: &str) -> String {
    tr(match v {
        "config" => "Profile's own",
        "proxy" => "Proxy",
        "direct" => "Direct",
        "block" => "Block",
        "node" => "Node or group",
        o => o,
    })
}

/// The choice in the editor: "all" reads as "no destination condition".
fn content_choice(v: &str) -> String {
    if v == "all" {
        tr("Any destination")
    } else {
        content_title(v)
    }
}

fn content_title(v: &str) -> String {
    tr(match v {
        "all" => "All traffic",
        "match" => "Domains, IPs or geo",
        "preset" => "Preset",
        "list" => "Rule list URL",
        o => o,
    })
}

fn network_title(v: &str) -> String {
    tr(match v {
        "any" => "TCP and UDP",
        "tcp" => "TCP",
        "udp" => "UDP",
        o => o,
    })
}

fn s(v: &Value, k: &str) -> String {
    v.get(k).and_then(Value::as_str).unwrap_or("").to_string()
}

fn list_of(v: &Value, k: &str) -> Vec<String> {
    v[k].as_array().map(|a| a.iter().map(text).filter(|x| !x.trim().is_empty()).collect()).unwrap_or_default()
}

/// Apps, a network or ports: the conditions a rule can match by without a destination.
fn has_conditions(rule: &Value) -> bool {
    let network = s(rule, "network");
    !list_of(rule, "apps").is_empty() || (network != "any" && !network.is_empty()) || !s(rule, "ports").trim().is_empty()
}

thread_local! {
    /// App names by package for the rules' summaries (the host's appLabels).
    static APP_LABELS: RefCell<std::collections::HashMap<String, String>> = RefCell::new(std::collections::HashMap::new());
    static LABELS_ASKED: RefCell<std::collections::HashSet<String>> = RefCell::new(std::collections::HashSet::new());
}

fn app_label(package: &str) -> String {
    APP_LABELS.with(|l| l.borrow().get(package).cloned()).unwrap_or_else(|| package.to_string())
}

/// Asks the host once for the names of packages not known yet.
fn request_labels(packages: impl IntoIterator<Item = String>) {
    let missing: Vec<String> = LABELS_ASKED.with(|asked| {
        let mut asked = asked.borrow_mut();
        packages.into_iter().filter(|p| asked.insert(p.clone())).collect()
    });
    if missing.is_empty() {
        return;
    }
    app::call("appLabels", json!({ "packages": missing }), move |r| {
        if let Ok(Value::Object(map)) = r {
            APP_LABELS.with(|l| {
                let mut l = l.borrow_mut();
                for (k, v) in map {
                    if let Some(v) = v.as_str().filter(|v| !v.trim().is_empty()) {
                        l.insert(k, v.to_string());
                    }
                }
            });
            mark(Mark::ROWS);
        }
    });
}

/// "微信、Chrome +2": the apps of a rule by name.
fn apps_text(apps: &[String]) -> String {
    let shown: Vec<String> = apps.iter().take(2).map(|a| app_label(a)).collect();
    let mut t = shown.join(tr(", ").as_str());
    if apps.len() > 2 {
        t.push_str(&format!(" +{}", apps.len() - 2));
    }
    t
}

fn uuid() -> String {
    // A random v4 UUID from the clock and a counter (no RNG crate needed).
    thread_local!(static N: std::cell::Cell<u64> = const { std::cell::Cell::new(0) });
    let n = N.with(|n| {
        n.set(n.get().wrapping_add(1));
        n.get()
    });
    let mut x = (format::now_ms() as u64).wrapping_mul(0x9E37_79B9_7F4A_7C15) ^ n.wrapping_mul(0xBF58_476D_1CE4_E5B9) ^ (std::process::id() as u64) << 32;
    let mut bytes = [0u8; 16];
    for b in bytes.iter_mut() {
        x ^= x >> 33;
        x = x.wrapping_mul(0xFF51_AFD7_ED55_8CCD);
        x ^= x >> 29;
        *b = (x & 0xff) as u8;
    }
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    let h: String = bytes.iter().map(|b| format!("{b:02x}")).collect();
    format!("{}-{}-{}-{}-{}", &h[0..8], &h[8..12], &h[12..16], &h[16..20], &h[20..32])
}

fn new_rule() -> Value {
    json!({ "id": uuid(), "enabled": true, "name": "", "content": "match", "values": [], "preset": "", "url": "", "target": { "type": "proxy", "tag": "" }, "apps": [], "network": "any", "ports": "" })
}

fn preset_rules(id: &str) -> Vec<Value> {
    let rule = |name: &str, content: &str, preset: &str, target: &str| {
        let mut r = new_rule();
        r["name"] = json!(name);
        r["content"] = json!(content);
        r["preset"] = json!(preset);
        r["target"] = json!({ "type": target, "tag": "" });
        r
    };
    match id {
        "cn-direct" => vec![rule("Bypass mainland China", "preset", "cn-direct", "direct")],
        "gfw-proxy" => vec![rule("GFW list", "preset", "gfw-proxy", "proxy"), rule("Everything else", "all", "", "direct")],
        "ads-block" => vec![rule("Block ads", "preset", "ads-block", "block")],
        "lan-direct" => vec![rule("LAN direct", "preset", "lan-direct", "direct")],
        _ => Vec::new(),
    }
}

fn default_set() -> Value {
    json!({ "mode": "prepend", "final": { "type": "config", "tag": "" }, "rules": [] })
}

/// storedRouting: the saved rules, defaults filled in.
fn stored() -> Value {
    let wire = data(|d| d.settings.get("routingRules").cloned().unwrap_or(Value::Null));
    let mut rules = json!({ "version": 1, "cores": {} });
    if let Some(cores) = wire.get("cores").and_then(Value::as_object) {
        for (core, set) in cores {
            let mut full = default_set();
            if let Some(o) = set.as_object() {
                for (k, v) in o {
                    full[k] = v.clone();
                }
            }
            let rules_list: Vec<Value> = full["rules"].as_array().cloned().unwrap_or_default().into_iter().map(|r| {
                let mut base = new_rule();
                if let Some(o) = r.as_object() {
                    for (k, v) in o {
                        base[k] = v.clone();
                    }
                }
                base
            }).collect();
            full["rules"] = json!(rules_list);
            rules["cores"][core] = full;
        }
    }
    rules
}

fn core_set(rules: &Value, core: &str) -> Value {
    rules["cores"].get(core).cloned().unwrap_or_else(default_set)
}

/// splitValues: one per line, or several on a line split by commas or spaces; `regexp:` lines whole.
fn split_values(text: &str) -> Vec<String> {
    text.lines()
        .flat_map(|line| {
            let t = line.trim();
            if t.len() >= 7 && t[..7].eq_ignore_ascii_case("regexp:") {
                vec![t.to_string()]
            } else {
                t.split([',', ' ', '\t']).map(str::to_string).collect()
            }
        })
        .map(|v| v.trim().to_string())
        .filter(|v| !v.is_empty())
        .collect()
}

fn ports_valid(text: &str) -> bool {
    if text.trim().is_empty() {
        return true;
    }
    if !text.chars().all(|c| c.is_ascii_digit() || matches!(c, ',' | ' ' | '-' | '\t')) {
        return false;
    }
    let ranges: Vec<&str> = text.split([',', ' ']).filter(|p| !p.trim().is_empty()).collect();
    if ranges.is_empty() || ranges.len() > 28 {
        return false;
    }
    ranges.iter().all(|part| {
        let bounds: Vec<Option<u32>> = part.split('-').map(|b| b.trim().parse::<u32>().ok().filter(|_| b.trim().len() <= 5)).collect();
        if bounds.iter().any(Option::is_none) || bounds.len() > 2 {
            return false;
        }
        let b: Vec<u32> = bounds.into_iter().flatten().collect();
        b.iter().all(|v| (1..=65535).contains(v)) && (b.len() == 1 || b[0] <= b[1])
    })
}

fn package_valid(name: &str) -> bool {
    name.len() < 256 && !name.is_empty() && name.split('.').all(|p| !p.is_empty() && p.chars().all(|c| c.is_ascii_alphanumeric() || c == '_'))
}

fn url_valid(url: &str) -> bool {
    url.len() <= 2048 && (url.starts_with("https://") || url.starts_with("http://")) && url.len() > 8
}

/// validateRule
fn validate_rule(rule: &Value, core: &str) -> Result<(), String> {
    let content = s(rule, "content");
    let target = rule["target"].clone();
    let values: Vec<String> = rule["values"].as_array().map(|a| a.iter().map(text).collect()).unwrap_or_default();
    let apps: Vec<String> = rule["apps"].as_array().map(|a| a.iter().map(text).collect()).unwrap_or_default();
    if s(&target, "type") == "node" && s(&target, "tag").trim().is_empty() {
        return Err(tr("Choose a node or group"));
    }
    if values.len() > 5000 || values.iter().any(|v| v.len() > 2048) {
        return Err(tr("Too many or too long entries"));
    }
    if content == "match" && !values.iter().any(|v| !v.trim().is_empty()) && !has_conditions(rule) {
        return Err(tr("Add a domain, IP or geo entry, or choose apps, a network or ports"));
    }
    if core == "xray" && apps.iter().any(|a| !a.trim().is_empty()) {
        return Err(tr("Xray cannot route by app. Remove the apps, or use the sing-box or mihomo core (or Access control)."));
    }
    if content == "preset" && !PRESETS.iter().any(|p| p.0 == s(rule, "preset")) {
        return Err(format!("{}: {}", tr("Unknown preset"), s(rule, "preset")));
    }
    if content == "list" && !url_valid(&s(rule, "url")) {
        return Err(tr("Enter an http(s) rule list URL"));
    }
    if apps.len() > 1000 || !apps.iter().all(|a| package_valid(a)) {
        return Err(tr("Invalid package name"));
    }
    if !ports_valid(&s(rule, "ports")) {
        return Err(tr("Ports are 1–65535, as 443 or 8000-9000, separated by commas"));
    }
    Ok(())
}

fn rule_summary(rule: &Value) -> String {
    let content = s(rule, "content");
    let apps = list_of(rule, "apps");
    let what = match content.as_str() {
        // An app-only (or network/port-only) rule: its conditions say it all.
        "all" if has_conditions(rule) => String::new(),
        "all" => tr("All traffic"),
        "preset" => tr(PRESETS.iter().find(|p| p.0 == s(rule, "preset")).map(|p| p.1).unwrap_or(&s(rule, "preset"))),
        "list" => s(rule, "url").split_once("://").map(|x| x.1).unwrap_or(&s(rule, "url")).chars().take(48).collect(),
        _ => {
            let values: Vec<String> = rule["values"].as_array().map(|a| a.iter().map(text).collect()).unwrap_or_default();
            let shown = values.iter().take(3).cloned().collect::<Vec<_>>().join(", ");
            if values.len() > 3 {
                format!("{shown} +{}", values.len() - 3)
            } else {
                shown
            }
        }
    };
    let mut parts: Vec<String> = Some(what).filter(|w| !w.is_empty()).into_iter().collect();
    if !apps.is_empty() {
        parts.push(apps_text(&apps));
    }
    let network = s(rule, "network");
    if network != "any" && !network.is_empty() {
        parts.push(network.to_uppercase());
    }
    let ports = s(rule, "ports");
    if !ports.trim().is_empty() {
        parts.push(ports);
    }
    format!("{} → {}", parts.join(" · "), target_text(&rule["target"]))
}

/// The list title of a rule without a name: what it matches.
fn rule_title(rule: &Value, name: &str) -> String {
    if !name.trim().is_empty() {
        return name.to_string();
    }
    let apps = list_of(rule, "apps");
    if s(rule, "content") == "all" && !apps.is_empty() {
        return apps_text(&apps);
    }
    content_title(&s(rule, "content"))
}

fn target_text(target: &Value) -> String {
    if s(target, "type") == "node" {
        let tag = s(target, "tag");
        if tag.trim().is_empty() {
            tr("Node or group")
        } else {
            tag
        }
    } else {
        target_title(&s(target, "type"))
    }
}

struct Routing {
    saved: Value,
    draft: Value,
    core: String,
    saving: bool,
    error: Option<String>,
    status: Option<Value>,
}

type RoutingRef = Rc<RefCell<Routing>>;

fn update(st: &RoutingRef, f: impl FnOnce(&mut Value)) {
    let mut r = st.borrow_mut();
    let core = r.core.clone();
    let mut set = core_set(&r.draft, &core);
    f(&mut set);
    r.draft["cores"][core.as_str()] = set;
    drop(r);
    mark(Mark::ROWS);
}

fn load_status(st: &RoutingRef) {
    let a = st.clone();
    app::call("routingStatus", json!({}), move |r| {
        if let Ok(v) = r {
            a.borrow_mut().status = if v.is_object() { Some(v) } else { None };
            mark(Mark::ROWS);
        }
    });
}

pub fn open() {
    let saved = stored();
    let st: RoutingRef = Rc::new(RefCell::new(Routing { draft: saved.clone(), saved, core: "sing-box".into(), saving: false, error: None, status: None }));
    load_status(&st);
    let a = st.clone();
    let id = rows::push(move || routing_page(&a));
    let a = st.clone();
    nav::guard(id, move || {
        let r = a.borrow();
        r.draft != r.saved
    });
    let a = st.clone();
    nav::busy_guard(id, move || a.borrow().saving);
}

fn routing_page(st: &RoutingRef) -> Page {
    let r = st.borrow();
    let set = core_set(&r.draft, &r.core);
    let core = r.core.clone();
    let saving = r.saving;
    let dirty = r.draft != r.saved;
    let mut rows = Vec::new();
    let a = st.clone();
    rows.push(Row::segments(CORES.iter().map(|c| core_title(c).to_string()).collect(), CORES.iter().position(|c| *c == core).unwrap_or(0) as i32, move |i| {
        a.borrow_mut().core = CORES[i.min(2)].to_string();
        mark(Mark::ROWS);
    }).key("cores"));
    if let Some(e) = &r.error {
        rows.push(Row::text(e).danger());
    }
    if let Some(status) = &r.status {
        let title = core_title(&s(status, "core")).to_string();
        let error = s(status, "error");
        let warnings = status["warnings"].as_array().map(Vec::len).unwrap_or(0);
        let line = if !error.is_empty() {
            format!("{} ({title})", tr("Not applied at the last start; the profile runs without the rules"))
        } else if truthy(&status["active"]) {
            let mut l = format!("{}: {title} · {} {}", tr("Applied at the last start"), long(&status["applied"]).unwrap_or(0), tr("rules"));
            if warnings > 0 {
                l.push_str(&format!(" · {} {warnings}", tr("Warnings")));
            }
            l
        } else {
            format!("{} ({title})", tr("No routing changes at the last start"))
        };
        rows.push(Row::spacer(8));
        let a = st.clone();
        let mut row = Row::item(&line).lines(2).key("status").on(move |_| open_status(&a));
        if !error.is_empty() {
            row = row.danger();
        }
        rows.push(row);
        rows.push(Row::spacer(4));
    }
    rows.push(Row::spacer(12));
    let a = st.clone();
    rows.push(
        forms::options_row(&tr("Merge mode"), MODES.iter().map(|m| m.to_string()).collect(), &s(&set, "mode"), Rc::new(|m: &str| mode_title(m)), Rc::new(move |m| update(&a, |set| set["mode"] = json!(m))))
            .key("mode"),
    );
    let final_target = set["final"].clone();
    let a = st.clone();
    let ft = final_target.clone();
    rows.push(
        forms::options_row(
            &tr("Fallback"),
            FINALS.iter().map(|m| m.to_string()).collect(),
            &s(&final_target, "type"),
            Rc::new(move |t: &str| if t == "node" && s(&ft, "type") == "node" { target_text(&ft) } else { target_title(t) }),
            Rc::new(move |t| {
                if t == "node" {
                    let a = a.clone();
                    let current = s(&core_set(&a.borrow().draft, &a.borrow().core)["final"], "tag");
                    let mut spec = nav::InputSpec::new(&tr("Node or group"), &current);
                    spec.validator = Some(Box::new(|v: &str| if v.trim().is_empty() { Some(tr("Required")) } else { None }));
                    nav::input(spec, move |tag| {
                        if let Some(tag) = tag.filter(|t| !t.trim().is_empty()) {
                            update(&a, |set| set["final"] = json!({ "type": "node", "tag": tag.trim() }));
                        }
                    });
                } else {
                    update(&a, |set| set["final"] = json!({ "type": t, "tag": "" }));
                }
            }),
        )
        .key("final"),
    );
    rows.push(Row::spacer(16));
    let list: Vec<Value> = set["rules"].as_array().cloned().unwrap_or_default();
    request_labels(list.iter().flat_map(|r| list_of(r, "apps")));
    if list.is_empty() {
        rows.push(Row::empty(&tr("No rules"), &tr("Add a rule, a preset, or copy the rules of another core from the menu."), "rules"));
    }
    for (i, rule) in list.iter().enumerate() {
        let id = s(rule, "id");
        let name = s(rule, "name");
        let title = rule_title(rule, &name);
        let (a, b, c) = (st.clone(), st.clone(), st.clone());
        let rid = id.clone();
        let mut row = Row::sort(&title, move |offset| {
            if a.borrow().saving {
                return;
            }
            update(&a, |set| {
                if let Some(rules) = set["rules"].as_array_mut() {
                    let to = (i as i32 + offset).clamp(0, rules.len() as i32 - 1) as usize;
                    if to != i {
                        let item = rules.remove(i);
                        rules.insert(to, item);
                    }
                }
            });
        })
        .subtitle(&rule_summary(rule))
        .key(&id)
        .checked(truthy(&rule["enabled"]))
        .hint("switch")
        .enabled(!saving);
        row.edit = Some(Rc::new(move |v: String| {
            let rid = rid.clone();
            update(&b, |set| {
                if let Some(rules) = set["rules"].as_array_mut() {
                    for r in rules.iter_mut() {
                        if s(r, "id") == rid {
                            r["enabled"] = json!(v == "1");
                        }
                    }
                }
            })
        }));
        row.done = Some(Rc::new(move |_| {
            if !c.borrow().saving {
                open_rule(&c, Some(i));
            }
        }));
        row.data.clickable = !saving;
        rows.push(row);
    }
    drop(r);
    let (a, b, c) = (st.clone(), st.clone(), st.clone());
    Page::new(&tr("Traffic routing"))
        .loading(saving)
        .bar(vec![Bar::new("add", "Add rule", move || open_rule(&a, None)).when(!saving)])
        .confirm(Bar::new("check", "Save", move || save(&b)).when(!saving && dirty))
        .menu(move || routing_menu(&c))
        .rows(rows)
}

fn routing_menu(st: &RoutingRef) -> Vec<MenuEntry> {
    let mut items = Vec::new();
    for (id, title, description) in PRESETS {
        let a = st.clone();
        let mut m = MenuEntry::plain(&tr(title), move || {
            let added = preset_rules(id);
            update(&a, |set| {
                if let Some(rules) = set["rules"].as_array_mut() {
                    rules.extend(added);
                } else {
                    set["rules"] = json!(added);
                }
            })
        });
        m.label = format!("{}\n{}", tr(title), tr(description));
        items.push(m);
    }
    let core = st.borrow().core.clone();
    for from in CORES.iter().filter(|c| **c != core) {
        let a = st.clone();
        let from = from.to_string();
        let label = format!("{} {}", tr("Copy from"), core_title(&from));
        items.push(MenuEntry::plain(&label, move || {
            let (count, empty, to) = {
                let r = a.borrow();
                (core_set(&r.draft, &from)["rules"].as_array().map(Vec::len).unwrap_or(0), core_set(&r.draft, &r.core)["rules"].as_array().map(Vec::is_empty).unwrap_or(true), r.core.clone())
            };
            let a2 = a.clone();
            let from2 = from.clone();
            let copy = move || {
                let mut r = a2.borrow_mut();
                let mut source = core_set(&r.draft, &from2);
                if let Some(rules) = source["rules"].as_array_mut() {
                    for rule in rules.iter_mut() {
                        rule["id"] = json!(uuid());
                    }
                }
                let to = r.core.clone();
                r.draft["cores"][to.as_str()] = source;
                drop(r);
                mark(Mark::ROWS);
            };
            if empty {
                copy();
            } else {
                let title = format!("{} {}?", tr("Replace the rules of"), core_title(&to));
                let message = format!("{}: {count}", core_title(&from));
                nav::confirm_styled(&title, Some(&message), "Replace", false, move |ok| {
                    if ok {
                        copy()
                    }
                });
            }
        }));
    }
    let a = st.clone();
    items.push(MenuEntry::act("Preview", "code", move || {
        let (draft, core) = {
            let r = a.borrow();
            (r.draft.clone(), r.core.clone())
        };
        open_preview(draft, core);
    }));
    items
}

fn save(st: &RoutingRef) {
    let draft = {
        let mut r = st.borrow_mut();
        if r.saving {
            return;
        }
        r.saving = true;
        r.error = None;
        r.draft.clone()
    };
    mark(Mark::ROWS);
    let a = st.clone();
    app::call("saveRouting", json!({ "value": draft }), move |res| {
        let mut r = a.borrow_mut();
        r.saving = false;
        match res {
            Ok(settings) => {
                app::data_mut(|d| d.settings = app::obj(&settings));
                r.saved = stored();
                r.draft = r.saved.clone();
            }
            Err(e) => r.error = Some(format::clean_error(&e)),
        }
        drop(r);
        mark(Mark::ALL);
    });
}

// ---------------------------------------------------------------------------
// The rule editor
// ---------------------------------------------------------------------------

struct Editor {
    initial: Value,
    rule: Value,
    values: String,
    apps: String,
    error: Option<String>,
    tags: Vec<String>,
    is_new: bool,
}

impl Editor {
    fn result(&self) -> Value {
        let mut r = self.rule.clone();
        let content = s(&r, "content");
        r["name"] = json!(s(&r, "name").trim());
        r["values"] = json!(if content == "match" { split_values(&self.values) } else { Vec::new() });
        r["apps"] = json!(split_values(&self.apps));
        // No entries but apps, a network or ports: a rule by those alone,
        // which every core reads as content "all" with conditions.
        if content == "match" && r["values"].as_array().map(Vec::is_empty).unwrap_or(true) && has_conditions(&r) {
            r["content"] = json!("all");
        }
        r["url"] = json!(if content == "list" { s(&r, "url").trim().to_string() } else { String::new() });
        r["preset"] = json!(if content == "preset" { s(&r, "preset") } else { String::new() });
        r["ports"] = json!(s(&r, "ports").trim());
        r
    }
}

fn open_rule(st: &RoutingRef, index: Option<usize>) {
    let (rule, core) = {
        let r = st.borrow();
        let set = core_set(&r.draft, &r.core);
        (index.and_then(|i| set["rules"].get(i).cloned()).unwrap_or_else(new_rule), r.core.clone())
    };
    let join = |k: &str| rule[k].as_array().map(|a| a.iter().map(text).collect::<Vec<_>>().join("\n")).unwrap_or_default();
    let ed = Rc::new(RefCell::new(Editor { initial: rule.clone(), values: join("values"), apps: join("apps"), rule, error: None, tags: Vec::new(), is_new: index.is_none() }));
    // Outbounds, groups and balancers of the selected profile when it runs on this core.
    let e = ed.clone();
    app::call("routingTargets", json!({ "core": core }), move |r| {
        if let Ok(v) = r {
            e.borrow_mut().tags = v.as_array().map(|a| a.iter().map(text).collect()).unwrap_or_default();
            mark(Mark::ROWS);
        }
    });
    let (e, routing) = (ed.clone(), st.clone());
    let id = rows::push(move || rule_page(&e, &routing));
    let e = ed.clone();
    nav::guard(id, move || {
        let e = e.borrow();
        e.result() != e.initial
    });
}

fn rule_page(ed: &Rc<RefCell<Editor>>, routing: &RoutingRef) -> Page {
    let e = ed.borrow();
    let rule = e.rule.clone();
    let mut rows = Vec::new();
    if let Some(err) = &e.error {
        rows.push(Row::error(err));
        rows.push(Row::spacer(8));
    }
    let set = |f: Rc<dyn Fn(&mut Value, String)>| {
        let a = ed.clone();
        move |v: String| {
            f(&mut a.borrow_mut().rule, v);
            mark(Mark::ROWS);
        }
    };
    let a = ed.clone();
    rows.push(Row::field(&tr("Name"), &s(&rule, "name"), &tr("Optional"), move |v| a.borrow_mut().rule["name"] = json!(v)).key("name"));
    let a = ed.clone();
    rows.push(Row::switch(&tr("Enabled"), truthy(&rule["enabled"]), move |v| a.borrow_mut().rule["enabled"] = json!(v)).key("enabled"));
    rows.push(Row::section(&tr("Match")));
    rows.push(
        forms::options_row(
            &tr("Match by"),
            CONTENTS.iter().map(|c| c.to_string()).collect(),
            &s(&rule, "content"),
            Rc::new(|c: &str| content_choice(c)),
            Rc::new(set(Rc::new(|r: &mut Value, v: String| {
                if v == "preset" && s(r, "preset").trim().is_empty() {
                    r["preset"] = json!(PRESETS[0].0);
                }
                r["content"] = json!(v);
            }))),
        )
        .key("content"),
    );
    match s(&rule, "content").as_str() {
        "match" => {
            rows.push(Row::spacer(12));
            let a = ed.clone();
            let mut editor = Row::editor(&tr("Entries"), &e.values, 6, move |v| a.borrow_mut().values = v).key("values");
            editor.data.hint = tr("One per line: example.com, geosite:cn, geoip:cn, 1.1.1.0/24").into();
            editor.data.subtitle = tr("Leave empty to match by the apps, network or ports below only.").into();
            rows.push(editor);
        }
        "preset" => {
            rows.push(Row::gap());
            rows.push(
                forms::options_row(
                    &tr("Preset"),
                    PRESETS.iter().map(|p| p.0.to_string()).collect(),
                    &s(&rule, "preset"),
                    Rc::new(|id: &str| PRESETS.iter().find(|p| p.0 == id).map(|p| tr(p.1)).unwrap_or_else(|| id.to_string())),
                    Rc::new(set(Rc::new(|r: &mut Value, v: String| r["preset"] = json!(v)))),
                )
                .key("preset"),
            );
        }
        "all" => {
            rows.push(Row::footer(&tr(if has_conditions(&e.result()) {
                "Matches by the apps, network or ports below."
            } else {
                "Matches all traffic. Choose apps, a network or ports below to narrow it."
            })));
        }
        "list" => {
            rows.push(Row::gap());
            let a = ed.clone();
            rows.push(Row::field(&tr("URL"), &s(&rule, "url"), "https://", move |v| a.borrow_mut().rule["url"] = json!(v)).key("url").mono());
        }
        _ => {}
    }
    rows.push(Row::section(&tr("Action")));
    let target = rule["target"].clone();
    let a = ed.clone();
    let tg = target.clone();
    rows.push(
        forms::options_row(
            &tr("Target"),
            TARGETS.iter().map(|t| t.to_string()).collect(),
            &s(&target, "type"),
            Rc::new(move |t: &str| if t == "node" && s(&tg, "type") == "node" && !s(&tg, "tag").trim().is_empty() { s(&tg, "tag") } else { target_title(t) }),
            Rc::new(move |t| {
                if t == "node" {
                    let a = a.clone();
                    let current = s(&a.borrow().rule["target"], "tag");
                    let mut spec = nav::InputSpec::new(&tr("Node or group"), &current);
                    spec.validator = Some(Box::new(|v: &str| if v.trim().is_empty() { Some(tr("Required")) } else { None }));
                    nav::input(spec, move |tag| {
                        if let Some(tag) = tag.filter(|t| !t.trim().is_empty()) {
                            a.borrow_mut().rule["target"] = json!({ "type": "node", "tag": tag.trim() });
                            mark(Mark::ROWS);
                        }
                    });
                } else {
                    a.borrow_mut().rule["target"] = json!({ "type": t, "tag": "" });
                    mark(Mark::ROWS);
                }
            }),
        )
        .key("target"),
    );
    if s(&target, "type") == "node" && !e.tags.is_empty() {
        let a = ed.clone();
        rows.push(Row::chips(e.tags.iter().take(60).cloned().collect(), move |tag| {
            a.borrow_mut().rule["target"] = json!({ "type": "node", "tag": tag });
            mark(Mark::ROWS);
        }).key("tags"));
    }
    rows.push(Row::section(&tr("Conditions")));
    rows.push(
        forms::options_row(
            &tr("Network"),
            NETWORKS.iter().map(|n| n.to_string()).collect(),
            &s(&rule, "network"),
            Rc::new(|n: &str| network_title(n)),
            Rc::new(set(Rc::new(|r: &mut Value, v: String| r["network"] = json!(v)))),
        )
        .key("network"),
    );
    let a = ed.clone();
    rows.push(Row::field(&tr("Ports"), &s(&rule, "ports"), "443, 8000-9000", move |v| {
        a.borrow_mut().rule["ports"] = json!(v);
        mark(Mark::ROWS);
    }).key("ports").invalid(!ports_valid(&s(&rule, "ports"))));
    // Apps: picked from the installed list like Access control (Xray cannot match apps).
    let chosen = split_values(&e.apps);
    let xray = routing.borrow().core == "xray";
    request_labels(chosen.iter().cloned());
    if !xray || !chosen.is_empty() {
        rows.push(Row::gap());
        let a = ed.clone();
        let value = if chosen.is_empty() { tr("All apps") } else { format!("{} {}", tr("Selected"), chosen.len()) };
        let mut apps_row = Row::item(&tr("Apps")).value(&value).chevron().key("apps");
        if !chosen.is_empty() {
            apps_row = apps_row.subtitle(&apps_text(&chosen));
        }
        if xray {
            apps_row = apps_row.danger();
        }
        rows.push(apps_row.on(move |_| {
            let a = a.clone();
            let current = split_values(&a.borrow().apps);
            crate::pages::settings::pick_apps(current, move |list| {
                a.borrow_mut().apps = list.join("\n");
                mark(Mark::ROWS);
            });
        }));
        if xray && !chosen.is_empty() {
            rows.push(Row::footer(&tr("Xray cannot route by app. Remove the apps, or use the sing-box or mihomo core (or Access control).")));
        }
    }
    if !e.is_new {
        rows.push(Row::spacer(24));
        let (a, r) = (ed.clone(), routing.clone());
        rows.push(Row::button(&tr("Delete rule"), 0, move || {
            let (a, r) = (a.clone(), r.clone());
            nav::confirm(&format!("{}?", tr("Delete rule")), None, "Delete", move |ok| {
                if ok {
                    let id = s(&a.borrow().initial, "id");
                    update(&r, |set| {
                        if let Some(rules) = set["rules"].as_array_mut() {
                            rules.retain(|x| s(x, "id") != id);
                        }
                    });
                    // result() borrows the editor; take it before borrowing mutably
                    // (doing both in one statement panicked and closed the app).
                    let done = a.borrow().result();
                    a.borrow_mut().initial = done;
                    nav::pop();
                }
            });
        }).icon("delete").key("delete"));
    }
    let title = tr(if e.is_new { "Add rule" } else { "Edit rule" });
    drop(e);
    let (a, r) = (ed.clone(), routing.clone());
    Page::new(&title)
        .confirm(Bar::new("check", "Save", move || {
            let next = a.borrow().result();
            let core = r.borrow().core.clone();
            match validate_rule(&next, &core) {
                Ok(()) => {
                    let is_new = a.borrow().is_new;
                    let id = s(&next, "id");
                    let n2 = next.clone();
                    update(&r, move |set| {
                        let rules = set["rules"].as_array_mut();
                        match rules {
                            Some(rules) => {
                                if is_new {
                                    rules.push(n2);
                                } else {
                                    for x in rules.iter_mut() {
                                        if s(x, "id") == id {
                                            *x = n2.clone();
                                        }
                                    }
                                }
                            }
                            None => set["rules"] = json!([n2]),
                        }
                    });
                    a.borrow_mut().initial = next;
                    nav::pop();
                }
                Err(err) => {
                    a.borrow_mut().error = Some(err);
                    mark(Mark::ROWS);
                }
            }
        }))
        .rows(rows)
}

// ---------------------------------------------------------------------------
// Preview and status
// ---------------------------------------------------------------------------

fn open_preview(draft: Value, tab_core: String) {
    let st: Rc<RefCell<Option<Result<Value, String>>>> = Rc::new(RefCell::new(None));
    let a = st.clone();
    app::call("routingPreview", json!({ "draft": draft }), move |r| {
        *a.borrow_mut() = Some(r.map_err(|e| format::clean_error(&e)));
        mark(Mark::ROWS);
    });
    rows::push(move || {
        let result = st.borrow().clone();
        let mut rows = Vec::new();
        match result {
            None => return Page::new(&tr("Preview")).loading(true),
            Some(Err(e)) => rows.push(Row::empty(&tr("Preview unavailable"), &e, "info")),
            Some(Ok(v)) => {
                let core = s(&v, "core");
                rows.push(Row::item(&tr("Profile")).value(&text(&v["profile"])));
                rows.push(Row::item(&tr("Core")).value(core_title(&core)));
                rows.push(Row::item(&tr("Rules applied")).value(&text(&v["applied"])));
                if core != tab_core {
                }
                if !truthy(&v["active"]) {
                    rows.push(Row::footer(&tr("No routing changes: the profile starts as it is.")));
                }
                let warnings: Vec<String> = v["warnings"].as_array().map(|a| a.iter().map(text).collect()).unwrap_or_default();
                if !warnings.is_empty() {
                    rows.push(Row::section(&tr("Warnings")));
                    for w in warnings {
                        rows.push(Row::item(&w).lines(20));
                    }
                }
                rows.push(Row::section(&tr("Content handed to the core")));
                let content: String = text(&v["content"]).chars().take(200_000).collect();
                rows.push(Row::text(&content).mono().checked(true));
            }
        }
        Page::new(&tr("Preview")).rows(rows)
    });
}

fn open_status(routing: &RoutingRef) {
    let retry = Rc::new(RefCell::new((false, None::<String>)));
    let r = routing.clone();
    rows::push(move || {
        let status = r.borrow().status.clone();
        let Some(current) = status else {
            return Page::new(&tr("Routing status")).rows(vec![Row::empty(&tr("Not started yet"), &tr("The status appears after the service starts."), "info")]);
        };
        let error = s(&current, "error");
        let time = format::local_date(long(&current["time"]).unwrap_or(0));
        let time = time.split(' ').nth(1).unwrap_or(&time).to_string();
        let mut rows = vec![
            Row::item(&tr("Core")).value(core_title(&s(&current, "core"))),
            Row::item(&tr("Started")).value(&time),
            Row::item(&tr("Rules applied")).value(&if error.is_empty() { text(&current["applied"]) } else { "0".into() }),
        ];
        if !error.is_empty() {
            rows.push(Row::section(&tr("Not applied")));
            rows.push(Row::item(&error).lines(20).danger());
            rows.push(Row::spacer(12));
            let (retrying, failure) = retry.borrow().clone();
            let (a, rr) = (retry.clone(), r.clone());
            rows.push(Row::button(&tr("Reload service"), 0, move || {
                a.borrow_mut().0 = true;
                a.borrow_mut().1 = None;
                mark(Mark::ROWS);
                let (b, rr) = (a.clone(), rr.clone());
                app::call("reloadService", json!({}), move |res| {
                    let mut x = b.borrow_mut();
                    x.0 = false;
                    if let Err(e) = res {
                        x.1 = Some(format::clean_error(&e));
                    }
                    drop(x);
                    load_status(&rr);
                    mark(Mark::ROWS);
                });
            }).icon("refresh").enabled(!retrying).key("reload"));
            if let Some(f) = failure {
                rows.push(Row::footer(&f));
            }
        }
        let warnings: Vec<String> = current["warnings"].as_array().map(|a| a.iter().map(text).collect()).unwrap_or_default();
        if !warnings.is_empty() {
            rows.push(Row::section(&tr("Warnings")));
            for w in warnings {
                rows.push(Row::item(&w).lines(20));
            }
        }
        Page::new(&tr("Routing status")).rows(rows)
    });
}


#[cfg(test)]
mod tests {
    use super::*;

    fn editor(rule: Value, values: &str, apps: &str) -> Editor {
        Editor { initial: rule.clone(), rule, values: values.into(), apps: apps.into(), error: None, tags: Vec::new(), is_new: true }
    }

    #[test]
    fn a_rule_with_only_apps_saves_as_an_app_only_rule() {
        let rule = editor(new_rule(), "", "com.tencent.mm\ncom.android.chrome").result();
        assert_eq!(s(&rule, "content"), "all");
        assert_eq!(list_of(&rule, "apps"), vec!["com.tencent.mm", "com.android.chrome"]);
        assert!(validate_rule(&rule, "sing-box").is_ok());
        assert!(validate_rule(&rule, "mihomo").is_ok());
        assert!(validate_rule(&rule, "xray").unwrap_err().contains("Xray"));
        // The list shows the apps, not "All traffic".
        let summary = rule_summary(&rule);
        assert!(summary.starts_with("com.tencent.mm, com.android.chrome →"), "{summary}");
        assert_eq!(rule_title(&rule, ""), "com.tencent.mm, com.android.chrome");
        APP_LABELS.with(|l| l.borrow_mut().insert("com.tencent.mm".into(), "微信".into()));
        assert!(rule_summary(&rule).starts_with("微信, com.android.chrome →"));
    }

    #[test]
    fn network_or_ports_alone_also_make_a_rule_but_nothing_does_not() {
        let mut r = new_rule();
        r["ports"] = json!("443");
        let rule = editor(r, "  ", "").result();
        assert_eq!(s(&rule, "content"), "all");
        assert!(validate_rule(&rule, "xray").is_ok());
        let empty = editor(new_rule(), "", "").result();
        assert_eq!(s(&empty, "content"), "match");
        assert!(validate_rule(&empty, "sing-box").is_err());
        // Entries keep the rule a destination rule, apps narrowing it.
        let both = editor(new_rule(), "example.com", "com.a").result();
        assert_eq!(s(&both, "content"), "match");
        assert!(validate_rule(&both, "sing-box").is_ok());
        // A deliberate "all" with no conditions stays a catch-all.
        let mut all = new_rule();
        all["content"] = json!("all");
        let all = editor(all, "", "").result();
        assert!(validate_rule(&all, "sing-box").is_ok());
        assert!(rule_summary(&all).starts_with("All traffic"));
    }
}
