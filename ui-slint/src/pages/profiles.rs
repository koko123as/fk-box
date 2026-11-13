//! Profiles (pages/ProfilesPage.kt, pages/ProfileEditor.kt,
//! pages/SubscriptionInfoView.kt, ui/QrCode.kt): the profile grid and its card
//! menu, the add menu and Add profile page, ProfileForm, ProfileOrderPage,
//! ProfileDetailsPage, the code editor and the QR code page. The grid, the
//! code editor and the QR page are Slint (ui/pages/profiles.slint); the rest
//! are Rust-built rows (src/rows.rs).

use std::cell::RefCell;
use std::collections::HashSet;
use std::rc::Rc;

use serde_json::{json, Value};
use slint::ComponentHandle;

use crate::app::{self, data, long, obj, ss, text, truthy, Json};
use crate::format;
use crate::i18n::tr;
use crate::nav::{self, glyph, MenuEntry};
use crate::pages::dashboard::{percent, SubInfo};
use crate::pages::{mark, Mark};
use crate::rows::{self, Bar, Page, Row};
use crate::{AppWindow, BarAction, Nav, ProfileRow, Profiles};

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------

#[derive(Default)]
struct Local {
    updating: HashSet<i64>,
    updating_all: bool,
    importing: bool,
}

thread_local! {
    static LOCAL: RefCell<Local> = RefCell::new(Local::default());
    static EDITOR: RefCell<Option<Editor>> = const { RefCell::new(None) };
}

fn local<R>(f: impl FnOnce(&mut Local) -> R) -> R {
    LOCAL.with(|l| f(&mut l.borrow_mut()))
}

fn id_of(p: &Json) -> i64 {
    p.get("id").and_then(long).unwrap_or(-1)
}

fn is_remote(p: &Json) -> bool {
    p.get("remote").map(truthy).unwrap_or(false)
}

fn s(p: &Json, k: &str) -> String {
    p.get(k).map(text).unwrap_or_default()
}

/// Whether a profile runs on sing-box, whose options the structured pages edit.
fn runs_sing_box(p: &Json) -> bool {
    let core = s(p, "resolvedCore");
    core.is_empty() || core == "sing-box"
}

/// coreLabels(): the cores a profile can run on, "auto" first.
fn core_labels() -> Vec<(&'static str, String)> {
    vec![("auto", tr("Automatic")), ("sing-box", "sing-box".into()), ("xray", "Xray".into()), ("mihomo", "mihomo".into())]
}

/// ProfileCoreChip's label.
pub fn core_chip(core: &str) -> String {
    if core.is_empty() {
        return String::new();
    }
    core_labels().into_iter().find(|(k, _)| *k == core).map(|(_, l)| l).unwrap_or_else(|| core.to_string())
}

/// libbox remote_profile.go stores the display name in the URL fragment.
pub fn remote_profile_link(p: &Json) -> String {
    fn enc(v: &str, fragment: bool) -> String {
        let mut out = String::new();
        for b in v.bytes() {
            let keep = b.is_ascii_alphanumeric() || b"-_.~".contains(&b) || (fragment && b"!$&'()*+,;=:@/?".contains(&b));
            if keep {
                out.push(b as char);
            } else {
                out.push_str(&format!("%{b:02X}"));
            }
        }
        out
    }
    format!("sing-box://import-remote-profile?url={}#{}", enc(&s(p, "url"), false), enc(&s(p, "name"), true))
}

fn now() -> i64 {
    format::now_ms()
}

fn expire_date(info: &SubInfo) -> Option<String> {
    info.expire_date()
}

/// The expiry chip's tone: error once past, warning within a week.
fn expire_tone(info: &SubInfo, now: i64) -> i32 {
    match info.expire_ms() {
        Some(e) if e <= now => 2,
        Some(e) if (e - now) / 86_400_000 < 7 => 1,
        _ => 0,
    }
}

/// subscriptionTrafficLine
fn traffic_line(info: &SubInfo) -> Option<String> {
    if info.total <= 0 {
        return None;
    }
    Some(format!("{} / {} · {} {}", format::bytes(info.used() as f64), format::bytes(info.total as f64), tr("Remaining"), format::bytes(info.remaining() as f64)))
}

fn web_page(p: &Json) -> Option<String> {
    let url = s(p, "webPageUrl");
    (is_remote(p) && !url.is_empty()).then_some(url)
}

// ---------------------------------------------------------------------------
// Projection
// ---------------------------------------------------------------------------

pub fn project(w: &AppWindow, _m: Mark) {
    let g = w.global::<Profiles>();
    let now = now();
    let chinese = crate::i18n::is_chinese();
    let (updating, updating_all) = local(|l| (l.updating.clone(), l.updating_all));
    let (rows, any_usage, remote, count, has_remote) = data(|d| {
        let selected = d.selected_profile_id();
        let mut any_usage = false;
        let rows: Vec<ProfileRow> = d
            .profiles
            .iter()
            .map(|v| {
                let p = obj(v);
                let id = id_of(&p);
                let info = SubInfo::of(&p);
                let usage = info.as_ref().and_then(|i| i.usage());
                any_usage |= usage.is_some();
                let core = s(&p, "resolvedCore");
                ProfileRow {
                    id: ss(id.to_string()),
                    name: ss(s(&p, "name")),
                    core: ss(core_chip(&core)),
                    remote: is_remote(&p),
                    selected: id == selected,
                    updated: ss(format::update_label(p.get("updatedAt").and_then(long).unwrap_or(0), now, chinese)),
                    source: ss(info.as_ref().and_then(traffic_line).unwrap_or_else(|| tr(if is_remote(&p) { "Remote" } else { "Local" }))),
                    days: ss(info.as_ref().and_then(expire_date).unwrap_or_default()),
                    days_tone: info.as_ref().map(|i| expire_tone(i, now)).unwrap_or(0),
                    bar_tone: info.as_ref().map(|i| i.tone(now)).unwrap_or(0),
                    has_usage: usage.is_some(),
                    used: ss(info.as_ref().map(|i| format::bytes(i.used() as f64)).unwrap_or_default()),
                    total: ss(info.as_ref().map(|i| format::bytes(i.total as f64)).unwrap_or_default()),
                    ratio: usage.unwrap_or(0.0) as f32,
                    percent: ss(usage.map(percent).unwrap_or_default()),
                    updating: updating.contains(&id),
                    invalid: false,
                }
            })
            .collect();
        let has_remote = d.profiles.iter().any(|p| p.get("remote").map(truthy).unwrap_or(false));
        (rows, any_usage, d.remote(), d.profiles.len(), has_remote)
    });
    if let Some(m) = rows::update(g.get_rows(), rows) {
        g.set_rows(m);
    }
    g.set_any_usage(any_usage);
    let busy = updating_all || !updating.is_empty();
    let actions = if remote || count == 0 {
        Vec::new()
    } else {
        vec![
            BarAction { id: "add".into(), glyph: glyph("add-circle"), tooltip: ss(tr("Add profile")), enabled: !local(|l| l.importing), selected: false, loading: false },
            BarAction { id: "update-all".into(), glyph: glyph("sync"), tooltip: ss(tr("Update all")), enabled: !busy && has_remote, selected: false, loading: busy },
            BarAction { id: "reorder".into(), glyph: glyph("sort"), tooltip: ss(tr("Reorder")), enabled: count >= 2, selected: false, loading: false },
        ]
    };
    if let Some(m) = rows::update(g.get_bar_actions(), actions) {
        g.set_bar_actions(m);
    }
    project_editor(w);
}

// ---------------------------------------------------------------------------
// Actions
// ---------------------------------------------------------------------------

fn update(id: i64, then: impl FnOnce() + 'static) {
    if !local(|l| l.updating.insert(id)) {
        then();
        return;
    }
    mark(Mark::PROFILES);
    app::act_then("updateProfile", json!({ "id": id }), move |_| {
        local(|l| l.updating.remove(&id));
        mark(Mark::PROFILES);
        then();
    });
}

/// updateAll: native profile writes share a mutex; updates run one by one.
fn update_all() {
    if local(|l| l.updating_all || !l.updating.is_empty()) {
        return;
    }
    let ids: Vec<i64> = data(|d| d.profiles.iter().map(obj).filter(is_remote).map(|p| id_of(&p)).collect());
    local(|l| l.updating_all = true);
    mark(Mark::PROFILES);
    fn next(mut ids: Vec<i64>) {
        if ids.is_empty() || data(|d| d.remote()) {
            local(|l| l.updating_all = false);
            mark(Mark::PROFILES);
            return;
        }
        let id = ids.remove(0);
        update(id, move || next(ids));
    }
    next(ids);
}

fn export(p: &Json) {
    let id = id_of(p);
    let name = s(p, "name");
    app::fetch("readProfile", json!({ "id": id }), move |content| {
        app::act("saveDocument", json!({ "name": format!("{name}.json"), "content": text(&content) }));
    });
}

fn share(p: &Json) {
    if is_remote(p) {
        app::act("shareText", json!({ "content": remote_profile_link(p) }));
    } else {
        app::fetch("readProfile", json!({ "id": id_of(p) }), |content| {
            app::act("shareText", json!({ "content": text(&content) }));
        });
    }
}

fn select(id: i64, picker: bool) {
    if data(|d| d.transitioning() || d.remote()) {
        return;
    }
    app::act_then("selectProfile", json!({ "id": id }), move |ok| {
        if ok && picker {
            nav::close_kind("profiles-picker");
        }
    });
}

fn card_menu(p: Json) -> Vec<MenuEntry> {
    let mut items = Vec::new();
    let a = p.clone();
    items.push(MenuEntry::act("Edit", "edit", move || open_details(Some(id_of(&a)), true)));
    if is_remote(&p) {
        let id = id_of(&p);
        items.push(MenuEntry::act("Update", "sync", move || update(id, || {})));
        let a = p.clone();
        items.push(MenuEntry::act("Copy link", "copy", move || {
            app::copy_text(&s(&a, "url"));
            app::message("Copied");
        }));
        let a = p.clone();
        items.push(MenuEntry::act("QR code", "qr-code", move || open_qr(&remote_profile_link(&a))));
    }
    let a = p.clone();
    items.push(MenuEntry::act("Share", "upload", move || share(&a)));
    let a = p.clone();
    items.push(MenuEntry::act("Export", "export", move || export(&a)));
    let id = id_of(&p);
    items.push(
        MenuEntry::act("Delete", "delete", move || {
            nav::confirm("Delete this profile?", Some("This cannot be undone."), "Delete", move |ok| {
                if ok {
                    app::act("deleteProfile", json!({ "id": id }));
                }
            });
        })
        .danger(),
    );
    items
}

// ---------------------------------------------------------------------------
// Adding profiles
// ---------------------------------------------------------------------------

/// openImported: one profile opens the form; a JSON array of configs
/// (v2rayNG's custom-config list) arrives split as "entries", one profile each.
fn open_imported(data: Value) {
    if let Some(entries) = data.get("entries").and_then(Value::as_array).cloned() {
        let core = data.get("core").and_then(Value::as_str).unwrap_or("auto").to_string();
        let n = entries.len();
        app::fetch("saveProfiles", json!({ "entries": entries, "core": core }), move |ids| {
            let count = ids.as_array().map(Vec::len).unwrap_or(n);
            crate::nav::toast(&tr("Imported %d profiles").replace("%d", &count.to_string()), false);
        });
        return;
    }
    open_form(obj(&data));
}

/// decodeImport: links become profiles natively; anything else must pass the config check.
fn decode_import(text_value: String, then: impl FnOnce(Result<Value, String>) + 'static) {
    if text_value.starts_with("https://") || text_value.starts_with("http://") || text_value.starts_with("sing-box://") {
        app::call("decodeProfileIntent", json!({ "value": text_value }), then);
        return;
    }
    app::call("decodeProfileText", json!({ "content": text_value, "name": "Imported profile" }), move |r| {
        then(r.map(|decoded| {
            if decoded.get("entries").map(Value::is_array).unwrap_or(false) {
                decoded
            } else {
                json!({ "name": decoded.get("name").cloned().unwrap_or(Value::Null), "content": decoded.get("content").cloned().unwrap_or(Value::Null), "core": "auto" })
            }
        }))
    });
}

fn import_done(r: Result<Value, String>) {
    local(|l| l.importing = false);
    mark(Mark::PROFILES | Mark::ROWS);
    match r {
        Ok(v) if !v.is_null() => open_imported(v),
        Ok(_) => {}
        Err(e) => app::report(&e),
    }
}

fn import(source: &str) {
    if local(|l| std::mem::replace(&mut l.importing, true)) {
        return;
    }
    mark(Mark::PROFILES | Mark::ROWS);
    match source {
        "File" => app::call("importProfileFile", json!({}), import_done),
        "Clipboard" => app::call("clipboardText", json!({}), |r| match r {
            Ok(v) => {
                let t = text(&v).trim().to_string();
                if t.is_empty() {
                    import_done(Err(tr("Empty clipboard")));
                } else {
                    decode_import(t, import_done);
                }
            }
            Err(e) => import_done(Err(e)),
        }),
        // ScanPage: the native camera Activity returns the decoded profile, or
        // the text of a QR code that is not a profile link.
        _ => app::call("scanQrCode", json!({ "title": tr("Scan QR code"), "flash": tr("Flash"), "camera": tr("Camera"), "retry": tr("Retry"), "scanning": tr("Scanning configuration"), "dark": crate::theme_apply::is_dark() }), |r| match r {
            Ok(v) if v.get("profile").map(Value::is_object).unwrap_or(false) => import_done(Ok(v["profile"].clone())),
            Ok(v) if v.get("text").map(Value::is_string).unwrap_or(false) => decode_import(text(&v["text"]), import_done),
            Ok(_) => import_done(Ok(Value::Null)),
            Err(e) => import_done(Err(e)),
        }),
    }
}

/// AddProfileMenu: the add button's menu of import sources.
fn add_menu() -> Vec<MenuEntry> {
    vec![
        MenuEntry::act("Scan QR code", "qr-code", || import("Scan QR code")),
        MenuEntry::act("File", "import-file", || import("File")),
        MenuEntry::act("Import from URL", "cloud-download", || open_form(remote_template())),
        MenuEntry::act("Clipboard", "copy", || import("Clipboard")),
        MenuEntry::act("New profile", "customize", || open_form(local_template())).section(""),
    ]
}

fn remote_template() -> Json {
    obj(&json!({ "url": "", "remote": true }))
}

fn local_template() -> Json {
    obj(&json!({ "content": "{}" }))
}

/// AddProfilePage
fn add_page() -> Page {
    let loading = local(|l| l.importing);
    let mut rows = vec![Row::open(&tr("Import from URL"), Some("link"), || open_form(remote_template()))];
    for (label, icon) in [("File", "profiles"), ("Clipboard", "copy"), ("Scan QR code", "qr-code")] {
        rows.push(Row::item(&tr(label)).icon(icon).on(move |_| import(label)).enabled(!loading));
    }
    rows.push(Row::open(&tr("New profile"), Some("add"), || open_form(local_template())));
    Page::new(&tr("Add profile")).loading(loading).rows(rows)
}

// ---------------------------------------------------------------------------
// ProfileForm
// ---------------------------------------------------------------------------

struct Form {
    initial: Json,
    name: String,
    url: String,
    interval: String,
    initial_interval: String,
    remote: bool,
    auto: bool,
    core: String,
    saving: bool,
    attempted: bool,
}

impl Form {
    fn issue(&self) -> Option<(&'static str, String)> {
        if self.name.trim().is_empty() {
            return Some(("name", format!("{}: {}", tr("Name"), tr("Required"))));
        }
        if !self.remote {
            return None;
        }
        let url = self.url.trim();
        let ok = ["http://", "https://"].iter().any(|scheme| {
            url.len() > scheme.len()
                && url[..scheme.len()].eq_ignore_ascii_case(scheme)
                && !url[scheme.len()..].split(['/', '?', '#']).next().unwrap_or("").split('@').next_back().unwrap_or("").is_empty()
        });
        if !ok {
            return Some(("url", tr("Subscription URL")));
        }
        if self.auto && self.interval.parse::<i64>().unwrap_or(0) < 15 {
            return Some(("interval", tr("At least 15 minutes")));
        }
        None
    }

    fn dirty(&self) -> bool {
        self.name != self.initial.get("name").and_then(Value::as_str).unwrap_or("")
            || self.url != self.initial.get("url").and_then(Value::as_str).unwrap_or("")
            || self.interval != self.initial_interval
            || self.core != self.initial.get("core").and_then(Value::as_str).unwrap_or("auto")
    }
}

fn interval_text(v: Option<&Value>) -> String {
    match v {
        Some(Value::Number(n)) => n.as_i64().map(|i| i.to_string()).unwrap_or_else(|| n.to_string().trim_end_matches(".0").to_string()),
        Some(Value::String(s)) => s.trim_end_matches(".0").to_string(),
        _ => "60".into(),
    }
}

/// ProfileForm: name, remote URL and its updates.
pub fn open_form(initial: Json) {
    let url = s(&initial, "url");
    let initial_interval = interval_text(initial.get("interval"));
    let form = Rc::new(RefCell::new(Form {
        name: initial.get("name").and_then(Value::as_str).unwrap_or("").to_string(),
        remote: initial.get("remote").map(truthy).unwrap_or(false) || !url.is_empty(),
        url,
        interval: initial_interval.clone(),
        initial_interval,
        auto: initial.get("autoUpdate").map(|v| v != &Value::Bool(false)).unwrap_or(true),
        core: initial.get("core").and_then(Value::as_str).unwrap_or("auto").to_string(),
        saving: false,
        attempted: false,
        initial,
    }));
    let f = form.clone();
    let id = rows::form(move || form_page(&f));
    let f = form.clone();
    nav::guard(id, move || f.borrow().dirty());
    let f = form.clone();
    nav::busy_guard(id, move || f.borrow().saving);
}

fn form_save(form: &Rc<RefCell<Form>>) {
    let profile = {
        let mut f = form.borrow_mut();
        if f.saving {
            return;
        }
        if f.issue().is_some() {
            f.attempted = true;
            mark(Mark::ROWS);
            return;
        }
        f.saving = true;
        let parsed = f.interval.parse::<i64>().ok();
        let previous = f.initial.get("interval").and_then(long).unwrap_or(60);
        let mut p = f.initial.clone();
        p.insert("name".into(), json!(f.name.trim()));
        p.insert("url".into(), json!(if f.remote { f.url.trim().to_string() } else { String::new() }));
        p.insert("autoUpdate".into(), json!(f.remote && f.auto));
        p.insert(
            "interval".into(),
            json!(match parsed {
                Some(v) if v >= 15 => v,
                _ if previous >= 15 => previous,
                _ => 60,
            }),
        );
        // A typed interval outranks the provider's profile-update-interval.
        let edited = f.initial.get("intervalEdited").map(truthy).unwrap_or(false) || (f.remote && f.auto && f.interval != f.initial_interval);
        p.insert("intervalEdited".into(), json!(edited));
        p.insert("core".into(), json!(f.core));
        p
    };
    mark(Mark::ROWS);
    let f = form.clone();
    let route = nav::top().map(|r| r.id);
    app::call("saveProfile", json!({ "profile": Value::Object(profile) }), move |r| {
        f.borrow_mut().saving = false;
        match r {
            Ok(_) => {
                if let Some(id) = route {
                    nav::close(id);
                }
            }
            Err(e) => app::report(&e),
        }
        mark(Mark::ROWS);
    });
}

fn form_page(form: &Rc<RefCell<Form>>) -> Page {
    let f = form.borrow();
    let shown = if f.attempted { f.issue() } else { None };
    let bad = |k: &str| shown.as_ref().map(|(x, _)| *x == k).unwrap_or(false);
    let title = tr(if f.initial.get("id").map(|v| !v.is_null()).unwrap_or(false) { "Edit" } else { "Add profile" });
    let mut rows = Vec::new();
    if let Some((_, message)) = &shown {
        rows.push(Row::error(message));
        rows.push(Row::spacer(8));
    }
    let a = form.clone();
    rows.push(Row::field(&tr("Name"), &f.name, &tr("Required"), move |v| a.borrow_mut().name = v).key("name").invalid(bad("name")));
    let a = form.clone();
    rows.push(Row::switch(&tr("Remote"), f.remote, move |v| a.borrow_mut().remote = v).key("remote"));
    let a = form.clone();
    let current_core = core_labels().into_iter().find(|(k, _)| *k == f.core).map(|(_, l)| l).unwrap_or_else(|| f.core.clone());
    rows.push(
        Row::item(&tr("Core"))
            .value(&current_core)
            .key("core")
            .on(|_| {})
            .menu(move || {
                let current = a.borrow().core.clone();
                core_labels()
                    .into_iter()
                    .map(|(value, label)| {
                        let b = a.clone();
                        MenuEntry::plain(&label, move || {
                            b.borrow_mut().core = value.to_string();
                            mark(Mark::ROWS);
                        })
                        .checked(current == value)
                    })
                    .collect()
            }),
    );
    if f.remote {
        let a = form.clone();
        rows.push(Row::field(&tr("URL"), &f.url, &tr("Required"), move |v| a.borrow_mut().url = v).key("url").invalid(bad("url")).mono());
    }
    if f.remote {
        rows.push(Row::gap());
        let a = form.clone();
        rows.push(Row::switch(&tr("Auto update"), f.auto, move |v| a.borrow_mut().auto = v).key("auto"));
        if f.auto {
            let a = form.clone();
            rows.push(
                Row::field(&tr("Update interval"), &f.interval, &tr("Required"), move |v| a.borrow_mut().interval = v.chars().filter(char::is_ascii_digit).collect())
                    .key("interval")
                    .number()
                    .value(&f.interval)
                    .subtitle(&tr("minutes"))
                    .invalid(bad("interval")),
            );
            rows.push(Row::footer(&tr("At least 15 minutes")));
        }
    }
    if f.initial.get("id").map(|v| !v.is_null()).unwrap_or(false) {
        rows.push(Row::gap());
        let p = f.initial.clone();
        rows.push(Row::open(&tr("Open configuration"), Some("code"), move || open_editor(p.clone(), None)));
        let updated = f.initial.get("updatedAt").and_then(long).unwrap_or(0);
        rows.push(Row::item(&tr("Last updated")).subtitle(&if updated <= 0 { tr("Unknown") } else { format::local_date(updated) }));
    }
    let saving = f.saving;
    drop(f);
    let a = form.clone();
    Page::new(&title).loading(saving).confirm(Bar::new("check", "Save", move || form_save(&a)).when(!saving).loading(saving)).rows(rows)
}

// ---------------------------------------------------------------------------
// ProfileOrderPage
// ---------------------------------------------------------------------------

fn open_order() {
    let list: Rc<RefCell<(Vec<Json>, bool)>> = Rc::new(RefCell::new((data(|d| d.profiles.iter().map(obj).collect()), false)));
    let l = list.clone();
    rows::form(move || {
        let (profiles, saving) = l.borrow().clone();
        let rows: Vec<Row> = profiles
            .iter()
            .enumerate()
            .map(|(i, p)| {
                let a = l.clone();
                Row::sort(&s(p, "name"), move |offset| {
                    let mut st = a.borrow_mut();
                    let n = st.0.len() as i32;
                    let to = (i as i32 + offset).clamp(0, n - 1) as usize;
                    if to != i {
                        let item = st.0.remove(i);
                        st.0.insert(to, item);
                    }
                    drop(st);
                    mark(Mark::ROWS);
                })
                .key(&id_of(p).to_string())
                .enabled(!saving)
            })
            .collect();
        let a = l.clone();
        Page::new(&tr("Reorder"))
            .confirm(
                Bar::new("check", "Save", move || {
                    let ids: Vec<i64> = a.borrow().0.iter().map(id_of).collect();
                    a.borrow_mut().1 = true;
                    mark(Mark::ROWS);
                    let b = a.clone();
                    let route = nav::top().map(|r| r.id);
                    app::act_then("reorderProfiles", json!({ "ids": ids }), move |ok| {
                        if ok {
                            if let Some(id) = route {
                                nav::close(id);
                            }
                        } else {
                            b.borrow_mut().1 = false;
                        }
                        mark(Mark::ROWS);
                    });
                })
                .when(!saving)
                .loading(saving),
            )
            .rows(rows)
    });
}

// ---------------------------------------------------------------------------
// ProfileDetailsPage
// ---------------------------------------------------------------------------

/// Opens the details of a profile (None: the selected one).
pub fn open_details(id: Option<i64>, as_form: bool) {
    let builder = move || details_page(id);
    if as_form {
        rows::form(builder);
    } else {
        rows::push(builder);
    }
}

fn details_page(id: Option<i64>) -> Page {
    let (remote, current, groups, remote_name) = data(|d| {
        let current = if d.remote() {
            None
        } else {
            match id {
                None => d.selected_profile(),
                Some(id) => d.profile(id),
            }
        };
        (d.remote(), current, d.groups.len(), d.dashboard.get("remote").map(text).unwrap_or_default())
    });
    if !remote && id.is_some() && current.is_none() {
        return Page::new(&tr("Edit")).rows(vec![Row::empty(&tr("Profile no longer exists"), "", "profiles")]);
    }
    let Some(p) = current else {
        let dash = |v: String| if v.is_empty() { "—".to_string() } else { v };
        return Page::new(&tr("Edit")).rows(vec![
            Row::item(&tr("Name")).value("—"),
            Row::item(&tr("Remote control")).value(&dash(remote_name)),
            Row::item(&tr("Proxy groups")).value(&groups.to_string()),
        ]);
    };
    let pid = id_of(&p);
    let now = now();
    let mut bar = Vec::new();
    let a = p.clone();
    bar.push(Bar::new("edit", "Edit", move || open_form(a.clone())));
    if is_remote(&p) {
        let updating = local(|l| l.updating.contains(&pid));
        bar.push(Bar::new("sync", "Update", move || update(pid, || {})).when(!updating).loading(updating));
    }
    let mut rows = vec![Row::title(&s(&p, "name"), "")];
    // ProfileStats
    let stats = data(|d| d.profile_stats.get(&pid).cloned());
    match stats {
        Some(Some(st)) => {
            let f = |k: &str| st.get(k).map(text).filter(|t| !t.is_empty()).unwrap_or_else(|| "—".into());
            rows.push(Row::stats(&f("groups"), &f("outbounds"), &f("rules"), false));
        }
        Some(None) => rows.push(Row::stats("", "", "", true)),
        None => {
            app::profile_stats(pid, || mark(Mark::ROWS));
            rows.push(Row::stats("", "", "", true));
        }
    }
    // The subscription section, shown only when the provider sent subscription-userinfo.
    let web = web_page(&p);
    if let Some(info) = SubInfo::of(&p) {
        rows.push(Row::section(&tr("Subscription info")));
        let line = if info.total > 0 { format!("{} / {}", format::bytes(info.used() as f64), format::bytes(info.total as f64)) } else { tr("Traffic not reported") };
        let expire = match expire_date(&info) {
            Some(date) => format!("{} {} · {}", tr("Expires"), date, info.days_text(now)),
            None => tr("No expiry"),
        };
        rows.push(Row::usage(&line, &expire, info.usage().map(|u| u as f32), info.tone(now)));
        if info.total > 0 {
            rows.push(Row::item(&tr("Used traffic")).value(&format::bytes(info.used() as f64)));
            rows.push(Row::item(&tr("Total traffic")).value(&format::bytes(info.total as f64)));
            rows.push(Row::item(&tr("Remaining traffic")).value(&format::bytes(info.remaining() as f64)));
        }
        let date = expire_date(&info);
        let mut r = Row::item(&tr("Expire time")).value(&date.clone().unwrap_or_else(|| tr("No expiry")));
        if date.is_some() {
            r = r.subtitle(&info.days_text(now));
        }
        rows.push(r);
        if let Some(url) = web.clone() {
            rows.push(Row::item(&tr("Open website")).subtitle(&url).lines(1).subtitle_lines(1).on(move |_| app::act("openUrl", json!({ "url": url }))));
        }
    } else if let Some(url) = web {
        rows.push(Row::section(&tr("Subscription info")));
        rows.push(Row::item(&tr("Open website")).subtitle(&url).subtitle_lines(1).on(move |_| app::act("openUrl", json!({ "url": url }))));
    }
    rows.push(Row::gap());
    let remote_profile = is_remote(&p);
    if remote_profile {
        let url = s(&p, "url");
        let u = url.clone();
        rows.push(Row::item(&tr("URL")).subtitle(&url).subtitle_lines(1).key("url").trailing("copy", move || {
            app::copy_text(&u);
            app::message("Copied");
        }));
    }
    let updated = p.get("updatedAt").and_then(long).unwrap_or(0);
    rows.push(Row::item(&tr("Last updated")).subtitle(&if updated <= 0 { tr("Unknown") } else { format::local_date(updated) }));
    if remote_profile {
        rows.push(Row::item(&tr("Auto update")).value(&tr(if p.get("autoUpdate").map(truthy).unwrap_or(false) { "Turn on" } else { "Turn off" })));
        rows.push(Row::item(&tr("Update interval (minutes)")).value(&interval_text(p.get("interval"))));
    }
    rows.push(Row::gap());
    // The structured editor understands sing-box options only.
    if runs_sing_box(&p) {
        let a = p.clone();
        rows.push(Row::open(&tr("Advanced configuration"), Some("customize"), move || crate::pages::config::open(a.clone(), false)));
    }
    let a = p.clone();
    rows.push(Row::open(&tr("Open configuration"), Some("code"), move || open_editor(a.clone(), None)));
    Page::new(&tr("Edit")).bar(bar).rows(rows)
}

// ---------------------------------------------------------------------------
// QR code
// ---------------------------------------------------------------------------

/// QrCodeView: dark modules on white with the four-module quiet zone; text
/// too long for a code is shown as text instead.
pub fn open_qr(value: &str) {
    const QUIET: i32 = 4;
    let (path, size) = match qrcodegen::QrCode::encode_text(value, qrcodegen::QrCodeEcc::Low) {
        Ok(qr) => {
            let n = qr.size();
            let mut path = String::new();
            for y in 0..n {
                for x in 0..n {
                    if qr.get_module(x, y) {
                        path.push_str(&format!("M{} {}h1v1h-1z", x + QUIET, y + QUIET));
                    }
                }
            }
            (path, (n + QUIET * 2) as f32)
        }
        Err(_) => (String::new(), 1.0),
    };
    let value = value.to_string();
    crate::app::with_window(move |w| {
        let g = w.global::<Profiles>();
        g.set_qr_path(path.into());
        g.set_qr_size(size);
        g.set_qr_text(value.into());
    });
    nav::form("profile-qr", "");
}

// ---------------------------------------------------------------------------
// The code editor (ProfileEditor)
// ---------------------------------------------------------------------------

/// EditorHistory: edits coalesce while typing within a second.
#[derive(Default)]
struct History {
    undo: Vec<String>,
    redo: Vec<String>,
    last_push: i64,
}

impl History {
    fn record(&mut self, previous: &str, next: &str) {
        if previous == next {
            return;
        }
        let now = now();
        if self.undo.is_empty() || now - self.last_push > 1000 {
            self.undo.push(previous.to_string());
            if self.undo.len() > 100 {
                self.undo.remove(0);
            }
        }
        self.last_push = now;
        self.redo.clear();
    }
}

struct Editor {
    route: i32,
    profile: Json,
    text: String,
    original: Option<String>,
    revision: Option<String>,
    language: String,
    error: Option<String>,
    saving: bool,
    checking: bool,
    loading: bool,
    search: bool,
    find: String,
    cursor: usize,
    history: History,
    select: (i32, i32, i32, i32),
    text_tick: i32,
    on_close: Option<Box<dyn Fn()>>,
    generation: u64,
}

impl Editor {
    fn busy(&self) -> bool {
        self.saving || self.checking || self.loading
    }

    fn dirty(&self) -> bool {
        self.original.as_ref().map(|o| *o != self.text).unwrap_or(false)
    }
}

fn editor<R>(f: impl FnOnce(&mut Editor) -> R) -> Option<R> {
    EDITOR.with(|e| e.borrow_mut().as_mut().map(f))
}

/// Opens a profile's configuration in the code editor; `on_close` runs when it closes.
pub fn open_editor(profile: Json, on_close: Option<Box<dyn Fn()>>) {
    // One editor at a time: a second one replaces the first.
    if let Some(previous) = EDITOR.with(|e| e.borrow().as_ref().map(|ed| ed.route)) {
        nav::close(previous);
    }
    let route = nav::next_id();
    EDITOR.with(|e| {
        *e.borrow_mut() = Some(Editor {
            route,
            profile,
            text: String::new(),
            original: None,
            revision: None,
            language: "json".into(),
            error: None,
            saving: false,
            checking: false,
            loading: false,
            search: false,
            find: String::new(),
            cursor: 0,
            history: History::default(),
            select: (0, 0, 0, 0),
            text_tick: 0,
            on_close,
            generation: 0,
        })
    });
    let id = nav::push("code-editor", "");
    nav::guard(id, || editor(|e| e.dirty()).unwrap_or(false));
    nav::busy_guard(id, || editor(|e| e.saving).unwrap_or(false));
    editor_load(false);
}

fn set_editor_text(e: &mut Editor, next: String, record: bool) {
    if record {
        let previous = std::mem::take(&mut e.text);
        e.history.record(&previous, &next);
    }
    e.text = next;
    e.text_tick += 1;
}

fn editor_load(confirm_discard: bool) {
    let Some((busy, dirty)) = editor(|e| (e.busy(), e.dirty())) else { return };
    if busy {
        return;
    }
    if confirm_discard && dirty {
        nav::confirm("Discard changes?", None, "Discard", |ok| {
            if ok {
                editor_load(false);
            }
        });
        return;
    }
    let Some((id, generation)) = editor(|e| {
        e.error = None;
        e.loading = true;
        e.generation += 1;
        (id_of(&e.profile), e.generation)
    }) else {
        return;
    };
    mark(Mark::PROFILES);
    app::call("profileConfig", json!({ "id": id }), move |r| {
        editor(|e| {
            if e.generation != generation {
                return;
            }
            e.loading = false;
            match r {
                Ok(v) => {
                    let content = text(&v["content"]);
                    set_editor_text(e, content.clone(), false);
                    e.history = History::default();
                    e.original = Some(content);
                    e.revision = Some(text(&v["revision"]));
                    e.language = v.get("language").and_then(Value::as_str).unwrap_or("json").to_string();
                }
                Err(err) => e.error = Some(format::clean_error(&err)),
            }
        });
        mark(Mark::PROFILES);
    });
}

fn editor_save() {
    let Some(Some((id, content, revision))) = editor(|e| {
        let revision = e.revision.clone()?;
        if e.busy() {
            return None;
        }
        e.saving = true;
        e.error = None;
        Some((id_of(&e.profile), e.text.clone(), revision))
    }) else {
        return;
    };
    mark(Mark::PROFILES);
    app::call("writeProfile", json!({ "id": id, "content": content, "revision": revision }), move |r| {
        editor(|e| {
            e.saving = false;
            match r {
                Ok(v) => {
                    e.original = Some(content);
                    e.revision = Some(text(&v["revision"]));
                    app::message("Done");
                }
                Err(err) => e.error = Some(format::clean_error(&err)),
            }
        });
        mark(Mark::PROFILES);
    });
}

fn editor_check(format_it: bool) {
    let Some(Some((core, content))) = editor(|e| {
        if e.busy() || e.original.is_none() {
            return None;
        }
        e.checking = true;
        e.error = None;
        let core = e.profile.get("core").and_then(Value::as_str).unwrap_or("auto").to_string();
        Some((core, e.text.clone()))
    }) else {
        return;
    };
    mark(Mark::PROFILES);
    let method = if format_it { "formatConfig" } else { "checkConfig" };
    app::call(method, json!({ "core": core, "content": content }), move |r| {
        editor(|e| {
            e.checking = false;
            match r {
                Ok(v) => {
                    if format_it {
                        set_editor_text(e, text(&v), true);
                    } else {
                        app::message("Configuration is valid");
                    }
                }
                Err(err) => e.error = Some(format::clean_error(&err)),
            }
        });
        mark(Mark::PROFILES);
    });
}

fn char_to_byte(s: &str, chars: usize) -> usize {
    s.char_indices().nth(chars).map(|(i, _)| i).unwrap_or(s.len())
}

/// nextMatch: from the selection's end, wrapping to the start.
fn editor_next() {
    editor(|e| {
        if e.find.is_empty() {
            return;
        }
        let start = e.cursor.min(e.text.len());
        let start = if e.text.is_char_boundary(start) { start } else { 0 };
        let index = e.text[start..].find(&e.find).map(|i| i + start).or_else(|| e.text.find(&e.find));
        let Some(index) = index else { return };
        let end = index + e.find.len();
        e.cursor = end;
        let line = e.text[..index].matches('\n').count() as i32;
        // TextInput selection offsets are in bytes (UTF-8).
        e.select = (e.select.0 + 1, index as i32, end as i32, line);
    });
    mark(Mark::PROFILES);
    let _ = char_to_byte;
}

fn editor_undo(redo: bool) {
    editor(|e| {
        if e.busy() {
            return;
        }
        let current = e.text.clone();
        let next = if redo { e.history.redo.pop() } else { e.history.undo.pop() };
        if let Some(next) = next {
            if redo {
                e.history.undo.push(current);
            } else {
                e.history.redo.push(current);
            }
            e.text = next;
            e.text_tick += 1;
        }
    });
    mark(Mark::PROFILES);
}

fn editor_bar() -> (Vec<Bar>, Vec<Bar>) {
    let Some((busy, loaded, search)) = editor(|e| (e.busy(), e.original.is_some(), e.search)) else { return (Vec::new(), Vec::new()) };
    let all = vec![
        Bar::new("refresh", "Reload", || editor_load(true)).when(!busy),
        Bar::new("code", "Format", || editor_check(true)).when(!busy && loaded),
        Bar::new("verified", "Validate", || editor_check(false)).when(!busy && loaded),
        Bar::new("search", "Search", || {
            editor(|e| e.search = !e.search);
            mark(Mark::PROFILES);
        })
        .selected(search),
        Bar::new("undo", "Undo", || editor_undo(false)).when(!busy),
        Bar::new("redo", "Redo", || editor_undo(true)).when(!busy),
    ];
    // foldBarActions: the confirmation and the overflow button leave one slot.
    let mut shown = all;
    let folded = shown.split_off(1);
    shown.push(Bar::new("check", "Save", editor_save).when(!busy && loaded));
    (shown, folded)
}

fn project_editor(w: &AppWindow) {
    let g = w.global::<Profiles>();
    let state = editor(|e| {
        (
            format!("{}{}", s(&e.profile, "name"), if e.dirty() { " •" } else { "" }),
            e.busy() || (e.original.is_none() && e.error.is_none()),
            e.original.is_some(),
            e.busy(),
            e.search,
            e.error.clone().unwrap_or_default(),
            if is_remote(&e.profile) { tr("Configuration changes will replace the remote content until its next update.") } else { String::new() },
            e.text.matches('\n').count() + 1,
            e.select,
            e.text_tick,
            e.text.clone(),
            e.find.clone(),
        )
    });
    let Some((title, loading, ready, busy, search, error, note, lines, select, text_tick, content, find)) = state else { return };
    let (shown, _) = editor_bar();
    let actions: Vec<BarAction> = shown
        .iter()
        .map(|b| BarAction { id: ss(b.icon), glyph: glyph(b.icon), tooltip: ss(&b.tooltip), enabled: b.enabled, selected: b.selected, loading: b.loading })
        .collect();
    g.set_editor_title(title.into());
    g.set_config_loading(loading);
    g.set_editor_ready(ready);
    g.set_editor_busy(busy);
    g.set_editor_search(search);
    g.set_editor_error(error.into());
    g.set_editor_note(note.into());
    if g.get_editor_find().as_str() != find {
        g.set_editor_find(find.into());
    }
    let numbers: Vec<String> = (1..=lines).map(|i| i.to_string()).collect();
    let numbers = numbers.join("\n");
    if g.get_editor_lines().as_str() != numbers {
        g.set_editor_lines(numbers.into());
    }
    if g.get_editor_text_tick() != text_tick {
        g.set_config_text(content.into());
        g.set_editor_text_tick(text_tick);
    }
    if g.get_editor_select_tick() != select.0 {
        g.set_editor_select_start(select.1);
        g.set_editor_select_end(select.2);
        g.set_editor_select_line(select.3);
        g.set_editor_select_tick(select.0);
    }
    if let Some(m) = rows::update(g.get_editor_actions(), actions) {
        g.set_editor_actions(m);
    }
}

fn editor_act(id: &str, arg: &str) -> bool {
    match id {
        "editor-edited" => {
            editor(|e| {
                let previous = std::mem::take(&mut e.text);
                e.history.record(&previous, arg);
                e.text = arg.to_string();
                // Typing keeps the field's own text; only the gutter and title follow.
            });
            mark(Mark::PROFILES);
        }
        "editor-find" => {
            editor(|e| e.find = arg.to_string());
        }
        "editor-next" => {
            editor(|e| e.find = arg.to_string());
            editor_next();
        }
        "editor-reload" => editor_load(false),
        _ => {
            let Some(bar) = id.strip_prefix("editor-") else { return false };
            let (shown, _) = editor_bar();
            if let Some(f) = shown.into_iter().find(|b| b.icon == bar && b.enabled).and_then(|b| b.action) {
                f();
            }
        }
    }
    true
}

// ---------------------------------------------------------------------------
// Dispatch
// ---------------------------------------------------------------------------

pub fn opened(kind: &str, arg: &str, id: i32) {
    match kind {
        "profile-details" => {
            let target = if arg == "remote" || arg.is_empty() { None } else { arg.parse::<i64>().ok() };
            rows::adopt(id, move || details_page(target));
        }
        "profile-add" => rows::adopt(id, add_page),
        "profile-config" => {
            // The dashboard's "Advanced configuration" of the selected profile.
            if let Some(p) = data(|d| d.selected_profile()) {
                slint::Timer::single_shot(std::time::Duration::ZERO, move || {
                    nav::close(id);
                    crate::pages::config::open(p.clone(), false);
                });
            }
        }
        _ => {}
    }
}

pub fn closed(kind: &str, _arg: &str, id: i32) {
    if kind == "code-editor" {
        let callback = EDITOR.with(|e| {
            let mut e = e.borrow_mut();
            if e.as_ref().map(|ed| ed.route == id).unwrap_or(false) {
                e.take().and_then(|ed| ed.on_close)
            } else {
                None
            }
        });
        if let Some(f) = callback {
            f();
        }
    }
    if matches!(kind, "profile-details" | "profile-add") {
        rows::closed(id);
    }
}

pub fn act(id: &str, arg: &str) -> bool {
    if id.starts_with("editor-") {
        return editor_act(id, arg);
    }
    match id {
        "profiles-bar" => {
            match arg {
                "add" => {
                    let (x, y) = menu_anchor();
                    nav::open_menu_at(add_menu(), x, y);
                }
                "update-all" => update_all(),
                "reorder" => open_order(),
                _ => {}
            }
            true
        }
        _ => false,
    }
}

fn menu_anchor() -> (f32, f32) {
    let mut out = (0.0, 0.0);
    crate::app::with_window(|w| {
        let size = w.window().size();
        let scale = w.window().scale_factor();
        let nav = w.global::<Nav>();
        out = (size.width as f32 / scale - 16.0, nav.get_inset_top() + 56.0);
    });
    out
}

pub fn act2(id: &str, a: &str, b: &str) -> bool {
    match id {
        "profile-select" => {
            if let Ok(pid) = a.parse::<i64>() {
                select(pid, b == "1");
            }
            true
        }
        _ => false,
    }
}

pub fn menu(key: &str) -> Vec<MenuEntry> {
    if key == "add-profile" {
        return add_menu();
    }
    if key == "code-editor" {
        let (_, folded) = editor_bar();
        return folded
            .into_iter()
            .map(|b| {
                let mut m = MenuEntry { label: b.tooltip.clone(), icon: Some(b.icon), checked: b.selected, action: b.action.clone(), ..Default::default() };
                if !b.enabled {
                    m.action = None;
                }
                m
            })
            .collect();
    }
    if let Some(rest) = key.strip_prefix("profile:") {
        if local(|l| rest.parse::<i64>().map(|id| l.updating.contains(&id)).unwrap_or(false)) {
            return Vec::new();
        }
        if let Some(p) = rest.parse::<i64>().ok().and_then(|id| data(|d| d.profile(id))) {
            return card_menu(p);
        }
    }
    Vec::new()
}

pub fn install(_w: &AppWindow) {}

/// Back handled by the page itself: closes the editor's search bar.
pub fn back() -> bool {
    let top_is_editor = nav::top().map(|r| r.kind == "code-editor").unwrap_or(false);
    if top_is_editor && editor(|e| std::mem::replace(&mut e.search, false)).unwrap_or(false) {
        mark(Mark::PROFILES);
        return true;
    }
    false
}

/// Drains imports and shares (HomePage's LaunchedEffect over pendingIntents).
pub fn drain_intents() {
    if !data(|d| d.ready) {
        return;
    }
    while let Some(uri) = app::data_mut(|d| d.pending_intents.pop_front()) {
        if let Some(token) = uri.strip_prefix("bbox-share:") {
            crate::pages::tools::open_share(token);
            continue;
        }
        app::call("decodeProfileIntent", json!({ "value": uri }), |r| match r {
            Ok(v) => open_imported(v),
            Err(e) => app::report(&e),
        });
    }
}
