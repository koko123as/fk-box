//! Settings (pages/SettingsPages.kt): General, Service and Core settings,
//! About with the update check and the licenses, Backup and restore with the
//! WebDAV form, and Access control (per-app proxy). Rust-built rows.

use std::cell::RefCell;
use std::collections::{BTreeSet, HashMap};
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

fn setting(key: &str) -> Value {
    data(|d| d.settings.get(key).cloned().unwrap_or(Value::Null))
}

/// NativeToggle: a native setting, restarting the service as asked.
fn native_toggle(title: &str, key: &'static str, restart: bool, auto_restart: bool) -> Row {
    let (busy, transitioning, remote) = data(|d| (d.service_settings_changing, d.transitioning(), d.remote()));
    let enabled = !(busy || (auto_restart && transitioning && !remote));
    Row::switch(&tr(title), truthy(&setting(key)), move |v| app::set_setting(key, json!(v), restart, auto_restart)).key(key).enabled(enabled)
}

pub fn open_general() {
    rows::push(|| {
        Page::new(&tr("General")).rows(vec![
            Row::open(&tr("Service"), Some("vpn"), open_service),
            Row::open(&tr("Core"), Some("cpu"), open_core),
        ])
    });
}

fn open_service() {
    rows::push(|| {
        let busy = data(|d| d.service_settings_changing);
        let exempt = truthy(&setting("batteryExempt"));
        Page::new(&tr("Service")).loading(busy).rows(vec![
            native_toggle("Allow VPN bypass", "allowBypass", true, true),
            native_toggle("Auto redirect", "autoRedirect", true, true),
            native_toggle("Dynamic notification", "dynamicNotification", true, true),
            Row::item(&tr("Battery optimization"))
                .subtitle(&tr(if exempt { "Unrestricted" } else { "Allow background operation" }))
                .key("battery")
                .on(|_| app::act("openBatterySettings", json!({}))),
        ])
    });
}

fn memory_limit_text(v: &Value) -> String {
    long(v).unwrap_or(50).to_string()
}

fn parse_memory_limit(text: &str) -> Option<i64> {
    let t = text.trim();
    let t = t.strip_suffix("MiB").or_else(|| t.strip_suffix("MB")).unwrap_or(t).trim();
    t.parse::<i64>().ok().filter(|v| (10..=4096).contains(v))
}

fn open_core() {
    rows::push(|| {
        let (busy, versions) = data(|d| (d.service_settings_changing, [d.core_version.clone(), d.xray_version.clone(), d.mihomo_version.clone()]));
        // One switch per linked core, with its version; a core that is off is
        // skipped by auto and refuses to start. The last one cannot go off.
        let cores = [("sing-box", "singBoxEnabled"), ("Xray", "xrayEnabled"), ("mihomo", "mihomoEnabled")];
        let on_count = cores.iter().filter(|(_, k)| setting(k) != Value::Bool(false)).count();
        let mut rows = vec![Row::first_section(&tr("Cores"))];
        for (i, (name, key)) in cores.into_iter().enumerate() {
            let on = setting(key) != Value::Bool(false);
            let last_on = on && on_count == 1;
            let mut r = Row::switch(name, on, move |v| app::set_setting(key, json!(v), false, false)).key(key).enabled(!busy && !last_on);
            if !versions[i].is_empty() {
                r = r.subtitle(&versions[i]);
            }
            rows.push(r);
        }
        rows.push(Row::footer(&tr("A core that is off is skipped when choosing automatically, and profiles that need it will not start.")));
        rows.push(Row::gap());
        rows.push(native_toggle("Disable deprecation warnings", "disableDeprecatedWarnings", false, false));
        rows.push(native_toggle("Enable memory protection", "oomKillerEnabled", true, false));
        rows.push(native_toggle("Disable core memory killer", "oomKillerDisabled", true, false));
        // The dialog edits the bare number; "MiB" is only shown beside it.
        let limit = memory_limit_text(&setting("oomMemoryLimitMB"));
        rows.push(
            forms::input_row(
                &tr("Memory limit"),
                &limit,
                true,
                "MiB",
                Some(Rc::new(|v: &str| if parse_memory_limit(v).is_some() { None } else { Some("10–4096 MiB".to_string()) })),
                Rc::new(|v| {
                    if let Some(limit) = parse_memory_limit(&v) {
                        app::set_setting("oomMemoryLimitMB", json!(limit), true, false);
                    }
                }),
            )
            .key("memory"),
        );
        rows.push(native_toggle("Power reports", "powerReportEnabled", true, false));
        rows.push(Row::footer(&tr("Deprecation warnings and memory protection apply to the sing-box core only.")));
        Page::new(&tr("Core")).rows(rows)
    });
}

// ---------------------------------------------------------------------------
// About
// ---------------------------------------------------------------------------

pub fn open_about() {
    let checking = Rc::new(RefCell::new(false));
    let c = checking.clone();
    rows::push(move || {
        let (version, core, xray, mihomo) = data(|d| (d.version.clone(), d.core_version.clone(), d.xray_version.clone(), d.mihomo_version.clone()));
        let busy = *c.borrow();
        let mut rows = Vec::new();
        // The hero: the icon, the name, the version and license pills, the update button.
        let mut hero = Row::title("FK-box", &tr("Kotlin + Jetpack Compose three-core client"));
        hero.data.kind = "hero".into();
        hero.data.value = format!("v{version}").into();
        hero.data.hint = "GPL-3.0".into();
        rows.push(hero);
        let a = c.clone();
        let mut update = Row::button(&tr("Check for updates"), 0, move || check_updates(&a)).icon("sync").key("update").enabled(!busy);
        update.data.checked = false;
        rows.push(update);
        let join = |v: &str, repo: &str| [v, repo].iter().filter(|s| !s.is_empty()).cloned().collect::<Vec<_>>().join(" · ");
        let links = vec![
            ("code", "Source code", "github.com/koko123as/fk-box".to_string(), "https://github.com/koko123as/fk-box"),
            ("code", "Based on sing-box-for-android", "SagerNet/sing-box-for-android · GPL-3.0".into(), "https://github.com/SagerNet/sing-box-for-android"),
            ("code", "Predecessor: B-box", "giturass/B-box · GPL-3.0".into(), "https://github.com/giturass/B-box"),
            ("cpu", "sing-box", join(&core, "SagerNet/sing-box"), "https://github.com/SagerNet/sing-box"),
            ("cpu", "Xray", join(&xray, "XTLS/Xray-core"), "https://github.com/XTLS/Xray-core"),
            ("cpu", "mihomo", join(&mihomo, "MetaCubeX/mihomo"), "https://github.com/MetaCubeX/mihomo"),
            ("paintbrush", "FlClash", "Page layout reference · GPL-3.0".into(), "https://github.com/chen08209/FlClash/tree/7fd417fb9a9a277c6a9339a8ac87f907df7c25f7"),
        ];
        rows.push(Row::section(&tr("More")));
        for (icon, title, subtitle, url) in links {
            rows.push(Row::item(&tr(title)).subtitle(&subtitle).lines(1).icon(icon).key(title).trailing("open-external", move || app::act("openUrl", json!({ "url": url }))).on(move |_| app::act("openUrl", json!({ "url": url }))));
        }
        rows.push(Row::item(&tr("Open-source licenses")).icon("info").key("licenses").on(|_| open_licenses()));
        Page::new(&tr("About")).loading(busy).rows(rows)
    });
}

fn check_updates(checking: &Rc<RefCell<bool>>) {
    if std::mem::replace(&mut *checking.borrow_mut(), true) {
        return;
    }
    mark(Mark::ROWS);
    let c = checking.clone();
    app::call("checkUpdates", json!({}), move |r| {
        *c.borrow_mut() = false;
        mark(Mark::ROWS);
        match r {
            Ok(result) => update_dialog(result),
            Err(e) => app::report(&e),
        }
    });
}

/// UpdateDialog
fn update_dialog(result: Value) {
    let available = truthy(&result["available"]);
    let no_metadata = result["hasMetadata"] == Value::Bool(false);
    let mut message = if available { format!("FK-box {}", text(&result["version"])) } else { tr(if no_metadata { "No release metadata available" } else { "You are up to date" }) };
    let notes = result.get("notes").and_then(Value::as_str).unwrap_or("");
    if available && !notes.is_empty() {
        message = format!("{message}\n\n{notes}");
    }
    let title = tr(if available { "Update available" } else { "Check for updates" });
    let mut actions = vec![("Close", None)];
    let open = if result["downloadUrl"].is_null() { "Releases" } else { "Download" };
    if available || no_metadata {
        actions.push((open, Some("open")));
    }
    let url = [&result["downloadUrl"], &result["url"]].iter().find_map(|v| v.as_str().map(str::to_string)).unwrap_or_else(|| "https://github.com/koko123as/fk-box/releases".into());
    nav::choose(&title, Some(&message), actions, move |picked| {
        if picked.as_deref() == Some("open") {
            app::act("openUrl", json!({ "url": url }));
        }
    });
}

/// showLicensePage: the projects FK-box ships or borrows from.
const LICENSES: [(&str, &str, &str, &str); 19] = [
    ("FK-box", "GPL-3.0", "This app", "https://github.com/koko123as/fk-box"),
    ("sing-box-for-android", "GPL-3.0", "Base Android app and backend", "https://github.com/SagerNet/sing-box-for-android"),
    ("B-box", "GPL-3.0", "Direct predecessor: core extensions, MITM, multi-core", "https://github.com/giturass/B-box"),
    ("sing-box", "GPL-3.0", "Proxy core (libbox)", "https://github.com/SagerNet/sing-box"),
    ("Xray-core", "MPL-2.0", "Second core", "https://github.com/XTLS/Xray-core"),
    ("mihomo", "GPL-3.0", "Third core (Clash YAML)", "https://github.com/MetaCubeX/mihomo"),
    ("FlClash", "GPL-3.0", "Page layout reference", "https://github.com/chen08209/FlClash"),
    ("shadowsocks-android", "GPL-3.0", "DefaultNetworkListener", "https://github.com/shadowsocks/shadowsocks-android"),
    ("Android Open Source Project", "Apache-2.0", "ParceledListSlice, Material 3 shapes", "https://github.com/androidx/androidx"),
    ("Slint", "GPL-3.0", "UI framework", "https://github.com/slint-ui/slint"),
    ("AndroidX Graphics Shapes", "Apache-2.0", "Shape morphing", "https://developer.android.com/jetpack/androidx/releases/graphics"),
    ("CameraX", "Apache-2.0", "QR code camera", "https://developer.android.com/jetpack/androidx/releases/camera"),
    ("ZXing", "Apache-2.0", "QR code scanning and generation", "https://github.com/zxing/zxing"),
    ("libghostty", "MIT", "Terminal", "https://github.com/ghostty-org/ghostty"),
    ("Shizuku API", "MIT", "Shizuku access", "https://github.com/RikkaApps/Shizuku-API"),
    ("libsu", "Apache-2.0", "Root shell and service", "https://github.com/topjohnwu/libsu"),
    ("smali / dexlib2", "BSD-3-Clause", "Per-app proxy scanning", "https://github.com/google/smali"),
    ("Goja", "MIT", "JavaScript for HTTP rewrite scripts", "https://github.com/dop251/goja"),
    ("JetBrains Mono", "OFL-1.1", "Monospace font", "https://github.com/JetBrains/JetBrainsMono"),
];

fn open_licenses() {
    rows::push(|| {
        let version = data(|d| d.version.clone());
        let mut rows = vec![Row::title(&format!("FK-box {version}"), "")];
        for (name, license, taken, url) in LICENSES {
            rows.push(Row::item(name).subtitle(&format!("{license} · {}", tr(taken))).key(name).trailing("open-external", move || app::act("openUrl", json!({ "url": url }))).on(move |_| app::act("openUrl", json!({ "url": url }))));
        }
        Page::new(&tr("Open-source licenses")).rows(rows)
    });
}

// ---------------------------------------------------------------------------
// Backup
// ---------------------------------------------------------------------------

const DEFAULT_BACKUP_FILE_NAME: &str = "B-box-backup.json";

fn backup_file_issue(value: &str) -> Option<&'static str> {
    if value.is_empty() || value.chars().count() > 128 || value == "." || value == ".." || value.chars().any(|c| (c as u32) < 32 || c == '/' || c == '\\') {
        Some("Invalid backup file name")
    } else {
        None
    }
}

#[derive(Default)]
struct Backup {
    account: Option<Json>,
    account_error: Option<String>,
    locked: bool,
    loading: bool,
    checking: bool,
    connected: Option<bool>,
    connection_error: Option<String>,
    check_generation: u64,
    account_generation: u64,
}

type BackupRef = Rc<RefCell<Backup>>;

fn backup_check(st: &BackupRef) {
    let generation = {
        let mut s = st.borrow_mut();
        s.check_generation += 1;
        if s.account.is_none() {
            s.checking = false;
            s.connected = None;
            return;
        }
        s.checking = true;
        s.connected = None;
        s.connection_error = None;
        s.check_generation
    };
    let a = st.clone();
    app::call("checkWebdav", json!({}), move |r| {
        let mut s = a.borrow_mut();
        if s.check_generation != generation {
            return;
        }
        match r {
            Ok(v) => s.connected = Some(v == Value::Bool(true)),
            Err(e) => {
                s.connected = Some(false);
                s.connection_error = Some(format::clean_error(&e));
            }
        }
        s.checking = false;
        drop(s);
        mark(Mark::ROWS);
    });
}

fn backup_update_account(st: &BackupRef, value: Option<Json>) {
    let changed = {
        let mut s = st.borrow_mut();
        s.account_generation += 1;
        let previous = s.account.take();
        let changed = match (&previous, &value) {
            (Some(p), Some(v)) => ["uri", "user", "password"].iter().any(|k| p.get(*k) != v.get(*k)),
            _ => true,
        };
        s.account = value;
        s.account_error = None;
        changed
    };
    if changed {
        backup_check(st);
    }
    mark(Mark::ROWS);
}

fn backup_load_account(st: &BackupRef) {
    let revision = st.borrow().account_generation;
    let a = st.clone();
    app::call("webdavAccount", json!({}), move |r| {
        if a.borrow().account_generation != revision {
            return;
        }
        match r {
            Ok(v) => backup_update_account(&a, if v.is_object() { Some(obj(&v)) } else { None }),
            Err(e) => {
                a.borrow_mut().account_error = Some(format::clean_error(&e));
                mark(Mark::ROWS);
            }
        }
    });
}

fn backup_edit(st: &BackupRef) {
    if std::mem::replace(&mut st.borrow_mut().locked, true) {
        return;
    }
    let initial = st.borrow().account.clone();
    let a = st.clone();
    webdav_form(initial, Rc::new(move |result: Option<Value>| {
        a.borrow_mut().locked = false;
        if let Some(v) = result {
            if truthy(&v["deleted"]) {
                backup_update_account(&a, None);
            } else {
                backup_update_account(&a, Some(obj(&v)));
            }
        }
        mark(Mark::ROWS);
    }));
}

fn backup_run(st: &BackupRef, remote: bool) {
    {
        let mut s = st.borrow_mut();
        if s.locked {
            return;
        }
        s.locked = true;
        s.loading = true;
    }
    mark(Mark::ROWS);
    let a = st.clone();
    let finish = move |saved: Result<bool, String>| {
        {
            let mut s = a.borrow_mut();
            s.loading = false;
            s.locked = false;
        }
        mark(Mark::ROWS);
        match saved {
            Ok(true) => nav::choose(&tr("Backup"), Some(&tr("Backup complete")), vec![("Close", None)], |_| {}),
            Ok(false) => {}
            Err(e) => app::report(&e),
        }
    };
    if remote {
        app::call("uploadWebdavBackup", json!({}), move |r| finish(r.map(|v| truthy(&v))));
    } else {
        app::call("createBackup", json!({}), move |r| match r {
            Ok(data) => app::call("saveDocument", json!({ "name": DEFAULT_BACKUP_FILE_NAME, "content": data.to_string() }), move |r| finish(r.map(|v| truthy(&v)))),
            Err(e) => finish(Err(e)),
        });
    }
}

fn backup_restore(st: &BackupRef, remote: bool) {
    if st.borrow().locked || data(|d| d.remote()) {
        return;
    }
    let strategy = data(|d| d.preferences.get("restoreStrategy").and_then(Value::as_str).unwrap_or("Compatible").to_string());
    st.borrow_mut().locked = true;
    let unlock = {
        let a = st.clone();
        move || {
            let mut s = a.borrow_mut();
            s.locked = false;
            s.loading = false;
            drop(s);
            mark(Mark::ROWS);
        }
    };
    let a = st.clone();
    // The scope dialog: profiles only or all data.
    nav::choose(&tr("Restore"), None, vec![("Restore profiles only", Some("profiles")), ("Restore all data", Some("all")), ("Cancel", None)], move |picked| {
        let Some(scope) = picked else {
            unlock();
            return;
        };
        a.borrow_mut().loading = true;
        mark(Mark::ROWS);
        let b = a.clone();
        app::call(if remote { "readWebdavBackup" } else { "readBackup" }, json!({ "scope": scope }), move |r| {
            b.borrow_mut().loading = false;
            mark(Mark::ROWS);
            let backup = match r {
                Ok(v) if !v.is_null() => v,
                Ok(_) => return unlock(),
                Err(e) => {
                    app::report(&e);
                    return unlock();
                }
            };
            let count = list(&backup["profiles"]).len();
            let override_ = strategy == "Override";
            let mut message = tr("Restore {count} profiles?").replace("{count}", &count.to_string());
            message.push_str("\n\n");
            message.push_str(&tr(if override_ { "Profiles missing from this backup will be removed." } else { "Matching profiles will be replaced. Other profiles will be kept." }));
            if scope == "all" {
                message.push_str("\n\n");
                message.push_str(&tr("Application settings and remote servers will also be restored."));
            }
            let c = b.clone();
            nav::choose(&tr("Restore backup"), Some(&message), vec![("Cancel", None), ("Restore", Some("restore"))], move |ok| {
                if ok.is_none() {
                    return unlock();
                }
                c.borrow_mut().loading = true;
                mark(Mark::ROWS);
                let d = c.clone();
                app::call("restoreBackup", json!({ "backup": backup, "scope": scope, "strategy": strategy }), move |r| {
                    match r {
                        Ok(_) => {
                            if scope == "all" {
                                backup_load_account(&d);
                            }
                            nav::choose(&tr("Restore"), Some(&tr("Restore complete")), vec![("Close", None)], |_| {});
                        }
                        Err(e) => app::report(&e),
                    }
                    unlock();
                });
            });
        });
    });
}

pub fn open_backup() {
    let st: BackupRef = Rc::new(RefCell::new(Backup::default()));
    backup_load_account(&st);
    let a = st.clone();
    let id = rows::push(move || backup_page(&a));
    let a = st.clone();
    nav::busy_guard(id, move || a.borrow().locked);
}

fn backup_page(st: &BackupRef) -> Page {
    let s = st.borrow();
    let remote_session = data(|d| d.remote());
    let mut rows = vec![Row::first_section(&tr("Remote"))];
    if let Some(e) = &s.account_error {
        let a = st.clone();
        rows.push(Row::empty(&tr("Error"), e, "info").value(&tr("Retry")).on(move |_| backup_load_account(&a)));
    } else if let Some(account) = &s.account {
        let user = account.get("user").and_then(Value::as_str).unwrap_or("");
        let title = if user.is_empty() { account.get("uri").and_then(Value::as_str).unwrap_or("").to_string() } else { user.to_string() };
        let label = if s.checking {
            "Checking connection"
        } else if s.connected == Some(true) {
            "Connected"
        } else {
            "Connection failed"
        };
        let status = if s.checking || s.connected == Some(true) {
            format!("{} · {}", tr("Connectivity"), tr(label))
        } else {
            format!("{} · {}", tr("Connectivity"), s.connection_error.clone().unwrap_or_else(|| tr(label)))
        };
        let a = st.clone();
        let mut r = Row::item(&title).subtitle(&status).icon("account").lines(1).value(&tr("Edit")).key("account").on(move |_| backup_edit(&a)).loading(s.checking);
        r.data.danger = false;
        rows.push(r);
        let file_name = account.get("fileName").and_then(Value::as_str).unwrap_or(DEFAULT_BACKUP_FILE_NAME).to_string();
        let a = st.clone();
        rows.push(Row::item(&tr("File")).subtitle(&file_name).key("file").on(move |_| {
            let mut spec = nav::InputSpec::new(&tr("File"), &file_name);
            spec.reset = Some(DEFAULT_BACKUP_FILE_NAME.into());
            spec.validator = Some(Box::new(|v: &str| backup_file_issue(v).map(tr)));
            let b = a.clone();
            nav::input(spec, move |value| {
                let Some(value) = value else { return };
                let Some(mut account) = b.borrow().account.clone() else { return };
                if std::mem::replace(&mut b.borrow_mut().locked, true) {
                    return;
                }
                account.insert("fileName".into(), json!(value));
                let c = b.clone();
                app::call("setWebdavAccount", json!({ "value": Value::Object(account) }), move |r| {
                    c.borrow_mut().locked = false;
                    match r {
                        Ok(v) => backup_update_account(&c, Some(obj(&v))),
                        Err(e) => app::report(&e),
                    }
                });
            });
        }));
        let a = st.clone();
        rows.push(Row::item(&tr("Backup")).key("rb").on(move |_| backup_run(&a, true)));
        let a = st.clone();
        rows.push(Row::item(&tr("Restore")).key("rr").on(move |_| backup_restore(&a, true)).enabled(!remote_session));
    } else {
        let a = st.clone();
        rows.push(Row::item(&tr("No account")).subtitle(&tr("Bind a WebDAV account")).icon("account").value(&tr("Bind")).key("bind").on(move |_| backup_edit(&a)).enabled(!s.locked));
    }
    rows.push(Row::section(&tr("Local")));
    let a = st.clone();
    rows.push(Row::item(&tr("Backup")).key("lb").on(move |_| backup_run(&a, false)));
    let a = st.clone();
    rows.push(Row::item(&tr("Restore")).key("lr").on(move |_| backup_restore(&a, false)).enabled(!remote_session));
    rows.push(Row::section(&tr("Options")));
    let strategy = data(|d| d.preferences.get("restoreStrategy").and_then(Value::as_str).unwrap_or("Compatible").to_string());
    let (compatible, override_) = (tr("Compatible"), tr("Override"));
    rows.push(
        forms::options_row(
            &tr("Restore strategy"),
            vec!["Compatible".into(), "Override".into()],
            &strategy,
            Rc::new(move |v: &str| if v == "Override" { override_.clone() } else { compatible.clone() }),
            Rc::new(|v| app::set_preference("restoreStrategy", json!(v))),
        )
        .key("strategy"),
    );
    Page::new(&tr("Backup and restore")).loading(s.loading).rows(rows)
}

fn webdav_issue(uri: &str, user: &str, password: &str) -> Option<&'static str> {
    const INVALID: &str = "Enter a WebDAV HTTP or HTTPS directory address";
    let u = uri.trim();
    let lower = u.to_ascii_lowercase();
    let rest = if lower.starts_with("https://") { &u[8..] } else if lower.starts_with("http://") { &u[7..] } else { return Some(INVALID) };
    if u.len() > 4096 || u.contains('?') || u.contains('#') {
        return Some(INVALID);
    }
    let authority = rest.split('/').next().unwrap_or("");
    if authority.is_empty() || authority.contains('@') {
        return Some(INVALID);
    }
    let host_port = authority;
    let (host, port) = if let Some(stripped) = host_port.strip_prefix('[') {
        match stripped.split_once(']') {
            Some((h, p)) => (h, p.strip_prefix(':')),
            None => return Some(INVALID),
        }
    } else {
        match host_port.rsplit_once(':') {
            Some((h, p)) => (h, Some(p)),
            None => (host_port, None),
        }
    };
    if host.is_empty() {
        return Some(INVALID);
    }
    if let Some(p) = port {
        match p.parse::<u32>() {
            Ok(p) if (1..=65535).contains(&p) => {}
            _ => return Some(INVALID),
        }
    }
    if user.len() > 4096 || password.len() > 4096 {
        return Some("Invalid WebDAV credentials");
    }
    None
}

struct WebDav {
    initial: Option<Json>,
    uri: String,
    user: String,
    password: String,
    attempted: bool,
    saving: bool,
    error: Option<String>,
    result: Option<Value>,
}

fn webdav_form(initial: Option<Json>, on_closed: Rc<dyn Fn(Option<Value>)>) {
    let get = |k: &str| initial.as_ref().and_then(|i| i.get(k)).and_then(Value::as_str).unwrap_or("").to_string();
    let st = Rc::new(RefCell::new(WebDav { uri: get("uri"), user: get("user"), password: get("password"), initial, attempted: false, saving: false, error: None, result: None }));
    let a = st.clone();
    let id = rows::form(move || webdav_page(&a));
    let a = st.clone();
    nav::guard(id, move || {
        let s = a.borrow();
        let get = |k: &str| s.initial.as_ref().and_then(|i| i.get(k)).and_then(Value::as_str).unwrap_or("").to_string();
        s.result.is_none() && (s.uri != get("uri") || s.user != get("user") || s.password != get("password"))
    });
    let a = st.clone();
    nav::busy_guard(id, move || a.borrow().saving);
    nav::on_closed(id, move || on_closed(st.borrow_mut().result.take()));
}

fn webdav_page(st: &Rc<RefCell<WebDav>>) -> Page {
    let s = st.borrow();
    let issue = if s.attempted { webdav_issue(&s.uri, &s.user, &s.password) } else { None };
    let mut rows = Vec::new();
    if let Some(e) = s.error.clone().or_else(|| issue.map(tr)) {
        rows.push(Row::error(&e));
        rows.push(Row::spacer(8));
    }
    let a = st.clone();
    rows.push(
        Row::field(&tr("Address"), &s.uri, &tr("Required"), move |v| {
            let mut s = a.borrow_mut();
            s.uri = v.chars().take(4096).collect();
            s.error = None;
        })
        .key("uri")
        .mono()
        .invalid(issue == Some("Enter a WebDAV HTTP or HTTPS directory address")),
    );
    let a = st.clone();
    rows.push(Row::field(&tr("Account"), &s.user, &tr("Optional"), move |v| {
        let mut s = a.borrow_mut();
        s.user = v.chars().take(4096).collect();
        s.error = None;
    }).key("user"));
    let a = st.clone();
    rows.push(Row::field(&tr("Password"), &s.password, &tr("Optional"), move |v| {
        let mut s = a.borrow_mut();
        s.password = v.chars().take(4096).collect();
        s.error = None;
    }).key("password").secret(true));
    rows.push(Row::footer(&tr("Enter the WebDAV directory URL. Backups are stored in its B-box folder.")));
    if s.initial.is_some() {
        rows.push(Row::gap());
        let a = st.clone();
        rows.push(Row::item(&tr("Delete")).danger().key("delete").on(move |_| {
            if a.borrow().saving {
                return;
            }
            let b = a.clone();
            nav::confirm("Delete WebDAV account", None, "Delete", move |ok| {
                if !ok {
                    return;
                }
                b.borrow_mut().saving = true;
                b.borrow_mut().error = None;
                mark(Mark::ROWS);
                let c = b.clone();
                let route = nav::top().map(|r| r.id);
                app::call("setWebdavAccount", json!({ "value": {} }), move |r| {
                    c.borrow_mut().saving = false;
                    match r {
                        Ok(_) => {
                            c.borrow_mut().result = Some(json!({ "deleted": true }));
                            if let Some(r) = route {
                                nav::close(r);
                            }
                        }
                        Err(e) => c.borrow_mut().error = Some(format::clean_error(&e)),
                    }
                    mark(Mark::ROWS);
                });
            });
        }));
    }
    let saving = s.saving;
    drop(s);
    let a = st.clone();
    Page::new(&tr("WebDAV configuration"))
        .loading(saving)
        .confirm(
            Bar::new("check", "Save", move || {
                let args = {
                    let mut s = a.borrow_mut();
                    if s.saving {
                        return;
                    }
                    if webdav_issue(&s.uri, &s.user, &s.password).is_some() {
                        s.attempted = true;
                        drop(s);
                        mark(Mark::ROWS);
                        return;
                    }
                    s.saving = true;
                    s.error = None;
                    let file = s.initial.as_ref().and_then(|i| i.get("fileName").cloned()).unwrap_or(json!(DEFAULT_BACKUP_FILE_NAME));
                    json!({ "value": { "uri": s.uri.trim(), "user": s.user, "password": s.password, "fileName": file } })
                };
                mark(Mark::ROWS);
                let b = a.clone();
                let route = nav::top().map(|r| r.id);
                app::call("setWebdavAccount", args, move |r| {
                    b.borrow_mut().saving = false;
                    match r {
                        Ok(v) => {
                            b.borrow_mut().result = Some(v);
                            if let Some(r) = route {
                                nav::close(r);
                            }
                        }
                        Err(e) => b.borrow_mut().error = Some(format::clean_error(&e)),
                    }
                    mark(Mark::ROWS);
                });
            })
            .when(!saving),
        )
        .rows(rows)
}

// ---------------------------------------------------------------------------
// Access control
// ---------------------------------------------------------------------------

fn string_set(v: &Value) -> BTreeSet<String> {
    v.as_array().map(|a| a.iter().map(text).collect()).unwrap_or_default()
}

#[derive(Clone, PartialEq)]
struct Draft {
    enabled: bool,
    managed: bool,
    mode: i64,
    list: BTreeSet<String>,
}

struct Access {
    draft: Draft,
    saved: Draft,
    pinned: BTreeSet<String>,
    query: String,
    sort: &'static str,
    system: bool,
    non_internet: bool,
    selected_only: bool,
    saving: bool,
    scanning: bool,
    apps: Option<Vec<Value>>,
    error: Option<String>,
    refresh: bool,
    generation: u64,
}

type AccessRef = Rc<RefCell<Access>>;

fn access_selected(s: &Access) -> BTreeSet<String> {
    if s.draft.managed {
        string_set(&setting("perAppManagedList"))
    } else {
        s.draft.list.clone()
    }
}

fn access_load(st: &AccessRef) {
    let (refresh, system, non_internet, generation) = {
        let mut s = st.borrow_mut();
        s.generation += 1;
        s.error = None;
        s.apps = None;
        (s.refresh, s.system, s.non_internet, s.generation)
    };
    mark(Mark::ROWS);
    let a = st.clone();
    app::call("listApps", json!({ "refresh": refresh, "system": system, "nonInternet": non_internet }), move |r| {
        let mut s = a.borrow_mut();
        if s.generation != generation {
            return;
        }
        match r {
            Ok(v) => s.apps = Some(list(&v)),
            Err(e) => s.error = Some(format::clean_error(&e)),
        }
        s.refresh = false;
        drop(s);
        mark(Mark::ROWS);
    });
}

fn access_reload(st: &AccessRef) {
    APP_ICONS.with(|i| i.borrow_mut().clear());
    st.borrow_mut().refresh = true;
    access_load(st);
}

fn access_set(st: &AccessRef, f: impl FnOnce(&mut Draft)) {
    let mut s = st.borrow_mut();
    if !s.saving {
        f(&mut s.draft);
    }
    drop(s);
    mark(Mark::ROWS);
}

fn access_save(st: &AccessRef) {
    let values = {
        let mut s = st.borrow_mut();
        if s.saving {
            return;
        }
        s.saving = true;
        let d = &s.draft;
        json!({ "perAppEnabled": d.enabled, "perAppManaged": d.managed, "perAppMode": d.mode, "perAppList": d.list.iter().collect::<Vec<_>>() })
    };
    mark(Mark::ROWS);
    let a = st.clone();
    app::set_settings(values, true, move |ok| {
        let mut s = a.borrow_mut();
        s.saving = false;
        if ok {
            s.saved = s.draft.clone();
        }
        drop(s);
        mark(Mark::ROWS);
    });
}

fn access_query_settings(st: &AccessRef, retry: bool) {
    let previous = setting("queryMode");
    let requested = Rc::new(RefCell::new(false));
    let r = requested.clone();
    let id = rows::form(move || {
        let mode = setting("queryMode").as_str().unwrap_or("SHIZUKU").to_string();
        let r2 = r.clone();
        Page::new(&tr("Application query")).rows(vec![
            forms::options_row(
                &tr("Application query"),
                vec!["SHIZUKU".into(), "ROOT".into()],
                &mode,
                Rc::new(|v: &str| if v == "ROOT" { "Root".into() } else { "Shizuku".into() }),
                Rc::new(|v| app::set_setting("queryMode", json!(v), false, false)),
            )
            .key("mode"),
            Row::item(&tr("Grant Shizuku permission")).key("grant").on(move |_| {
                *r2.borrow_mut() = true;
                app::act("requestAppsPermission", json!({}));
            }),
        ])
    });
    let a = st.clone();
    nav::on_closed(id, move || {
        if retry || *requested.borrow() || setting("queryMode") != previous {
            access_reload(&a);
        }
    });
}

pub fn open_access() {
    let initial = Draft {
        enabled: truthy(&setting("perAppEnabled")),
        managed: truthy(&setting("perAppManaged")),
        mode: if long(&setting("perAppMode")).unwrap_or(1) == 2 { 2 } else { 1 },
        list: string_set(&setting("perAppList")),
    };
    let selected = if initial.managed { string_set(&setting("perAppManagedList")) } else { initial.list.clone() };
    let st: AccessRef = Rc::new(RefCell::new(Access {
        draft: initial.clone(),
        saved: initial,
        pinned: selected,
        query: String::new(),
        sort: "Default",
        system: false,
        non_internet: false,
        selected_only: false,
        saving: false,
        scanning: false,
        apps: None,
        error: None,
        refresh: true,
        generation: 0,
    }));
    access_load(&st);
    let a = st.clone();
    let id = rows::push(move || access_page(&a));
    let a = st.clone();
    nav::guard(id, move || {
        let s = a.borrow();
        s.draft != s.saved
    });
    let a = st.clone();
    nav::busy_guard(id, move || a.borrow().saving);
}

fn access_visible(s: &Access, selected: &BTreeSet<String>) -> Vec<Value> {
    let words: Vec<String> = s.query.to_lowercase().split_whitespace().map(str::to_string).collect();
    let mut visible: Vec<Value> = s
        .apps
        .clone()
        .unwrap_or_default()
        .into_iter()
        .filter(|app| {
            let pkg = text(&app["package"]);
            (s.system || !truthy(&app["system"]))
                && (s.non_internet || app["internet"] != Value::Bool(false))
                && (!s.selected_only || selected.contains(&pkg))
                && {
                    let hay = format!("{} {}", text(&app["name"]), pkg).to_lowercase();
                    words.iter().all(|w| hay.contains(w.as_str()))
                }
        })
        .collect();
    let sort = s.sort;
    visible.sort_by(|a, b| {
        let (pa, pb) = (text(&a["package"]), text(&b["package"]));
        let pin = (s.pinned.contains(&pb) as i32) - (s.pinned.contains(&pa) as i32);
        if pin != 0 {
            return pin.cmp(&0);
        }
        if sort == "Time" {
            let t = num(&b["updatedAt"]).partial_cmp(&num(&a["updatedAt"])).unwrap_or(std::cmp::Ordering::Equal);
            if t != std::cmp::Ordering::Equal {
                return t;
            }
        }
        let (an, bn) = (text(&a["name"]), text(&b["name"]));
        let name = if sort == "Name" { an.cmp(&bn) } else { an.to_lowercase().cmp(&bn.to_lowercase()) };
        if name != std::cmp::Ordering::Equal {
            name
        } else {
            pa.cmp(&pb)
        }
    });
    visible
}

fn access_page(st: &AccessRef) -> Page {
    let s = st.borrow();
    let selected = access_selected(&s);
    let enabled = s.draft.enabled;
    let managed = s.draft.managed;
    let saving = s.saving;
    let has_changes = s.draft != s.saved;
    let visible = access_visible(&s, &selected);
    let mode = if managed { 1 } else { s.draft.mode };
    let mut bar = Vec::new();
    if enabled && !managed && !visible.is_empty() {
        let all = visible.iter().all(|a| selected.contains(&text(&a["package"])));
        let packages: Vec<String> = visible.iter().map(|a| text(&a["package"])).collect();
        let a = st.clone();
        bar.push(
            Bar::new(if all { "deselect" } else { "select-all" }, if all { "Deselect all" } else { "Select all" }, move || {
                access_set(&a, |d| {
                    for p in &packages {
                        if all {
                            d.list.remove(p);
                        } else {
                            d.list.insert(p.clone());
                        }
                    }
                })
            })
            .when(!saving),
        );
    }
    let mut rows = Vec::new();
    let mut page_empty = None;
    if let Some(e) = &s.error {
        page_empty = Some(e.clone());
    } else if let Some(_) = &s.apps {
        if visible.is_empty() {
            rows.push(Row::empty(&tr("No results"), "", "apps-list"));
        }
        for app_v in &visible {
            let pkg = text(&app_v["package"]);
            let p = pkg.clone();
            let a = st.clone();
            let toggle = move || {
                let s = a.borrow();
                if !s.draft.enabled || s.draft.managed || s.saving {
                    return;
                }
                drop(s);
                let p = p.clone();
                access_set(&a, move |d| {
                    if !d.list.insert(p.clone()) {
                        d.list.remove(&p);
                    }
                });
            };
            let t2 = toggle.clone();
            let mut r = Row::check(&text(&app_v["name"]), selected.contains(&pkg), move |_| t2())
                .subtitle(&pkg)
                .lines(1)
                .key(&pkg)
                .icon("apps-list")
                .image(app_icon(&pkg))
                .enabled(enabled && !managed && !saving);
            r.data.position = 0;
            rows.push(r);
            let _ = toggle;
        }
        rows.push(Row::spacer(56));
    }
    let a = st.clone();
    let b = st.clone();
    let mut page = Page::new(&tr("Access control")).loading(saving || s.scanning || s.apps.is_none() && s.error.is_none()).bar(bar).rows(rows).search(move |q| {
        let mut s = a.borrow_mut();
        s.query = q;
        s.pinned = access_selected(&s);
    });
    if has_changes {
        page = page.confirm(Bar::new("check", "Save", move || access_save(&b)).when(!saving));
    }
    if let Some(e) = page_empty {
        let a = st.clone();
        page = page.empty(&tr("Application query failed"), &e, "lock-open").empty_action(&tr("Application query"), move || access_query_settings(&a, true));
    }
    let a = st.clone();
    let sort = s.sort;
    let (system, non_internet, selected_only, scanning) = (s.system, s.non_internet, s.selected_only, s.scanning);
    page = page.menu(move || access_menu(&a, enabled, managed, mode, sort, system, non_internet, selected_only, scanning, has_changes));
    let foot_text = tr(if !enabled {
        "Access control is disabled"
    } else if managed {
        "Disable managed app list to edit selection"
    } else if mode == 2 {
        "Only selected apps use the VPN"
    } else {
        "Selected apps bypass the VPN"
    });
    let a = st.clone();
    if enabled {
        page = page.foot(&foot_text, "apps-list", &format!("{} {}", tr("Selected"), selected.len()), "", || {});
    } else {
        page = page.foot(&foot_text, "info", "", &tr("Turn on"), move || access_set(&a, |d| d.enabled = true));
    }
    page
}

#[allow(clippy::too_many_arguments)]
fn access_menu(st: &AccessRef, enabled: bool, managed: bool, mode: i64, sort: &'static str, system: bool, non_internet: bool, selected_only: bool, scanning: bool, has_changes: bool) -> Vec<MenuEntry> {
    let set = |f: Rc<dyn Fn(&mut Draft)>| {
        let a = st.clone();
        move || {
            let f = f.clone();
            access_set(&a, move |d| f(d))
        }
    };
    let view = |f: Rc<dyn Fn(&mut Access)>, reload: bool| {
        let a = st.clone();
        move || {
            f(&mut a.borrow_mut());
            if reload {
                access_load(&a);
            }
            mark(Mark::ROWS);
        }
    };
    let (proxy, bypass) = (tr("Proxy selected apps"), tr("Bypass selected apps"));
    let mut settings_sub = vec![
        MenuEntry::plain(&proxy, set(Rc::new(|d| d.mode = 2))).checked(mode == 2).section(&tr("Mode")),
        MenuEntry::plain(&bypass, set(Rc::new(|d| d.mode = 1))).checked(mode == 1),
        MenuEntry::plain(&tr("Default"), view(Rc::new(|s| s.sort = "Default"), false)).checked(sort == "Default").section(&tr("Sort")),
        MenuEntry::plain(&tr("Name"), view(Rc::new(|s| s.sort = "Name"), false)).checked(sort == "Name"),
        MenuEntry::plain(&tr("Time"), view(Rc::new(|s| s.sort = "Time"), false)).checked(sort == "Time"),
        MenuEntry::plain(&tr("Show system apps"), view(Rc::new(|s| s.system = !s.system), true)).checked(system).section(&tr("Source")),
        MenuEntry::plain(&tr("Show apps without Internet permission"), view(Rc::new(|s| s.non_internet = !s.non_internet), true)).checked(non_internet),
        MenuEntry::plain(&tr("Selected apps"), view(Rc::new(|s| s.selected_only = !s.selected_only), false)).checked(selected_only),
        MenuEntry::plain(&tr("Managed app list"), set(Rc::new(|d| d.managed = !d.managed))).checked(managed),
    ];
    for m in settings_sub.iter_mut() {
        m.icon = None;
    }
    let mut action_sub = Vec::new();
    let a = st.clone();
    action_sub.push(MenuEntry::act("Clipboard export", "copy", move || {
        let selected = access_selected(&a.borrow());
        app::copy_text(&selected.into_iter().collect::<Vec<_>>().join("\n"));
    }));
    if !managed {
        let a = st.clone();
        action_sub.push(MenuEntry::act("Clipboard import", "paste", move || {
            let b = a.clone();
            app::call("clipboardText", json!({}), move |r| {
                if let Ok(Value::String(t)) = r {
                    let list: BTreeSet<String> = t.split_whitespace().map(str::to_string).collect();
                    access_set(&b, move |d| d.list = list);
                }
            });
        }));
    }
    let a = st.clone();
    action_sub.push(MenuEntry::act("Refresh", "refresh", move || access_reload(&a)));
    if managed {
        let a = st.clone();
        let entry = MenuEntry::act("Rescan managed apps", "apps-list", move || {
            a.borrow_mut().scanning = true;
            mark(Mark::ROWS);
            let b = a.clone();
            app::call("rescanApps", json!({}), move |r| {
                b.borrow_mut().scanning = false;
                match r {
                    Ok(v) => app::data_mut(|d| d.settings = obj(&v)),
                    Err(e) => app::report(&e),
                }
                mark(Mark::ALL);
            });
        });
        let saving = st.borrow().saving;
        action_sub.push(entry.when(!(scanning || saving || has_changes)));
    }
    let a = st.clone();
    action_sub.push(MenuEntry::act("Application query", "lock-open", move || access_query_settings(&a, false)));
    let mut items = vec![
        MenuEntry::act("Turn on", "toggle-on", set(Rc::new(|d| d.enabled = true))).checked(enabled).section(&tr("Access control")),
        MenuEntry::act("Turn off", "toggle", set(Rc::new(|d| d.enabled = false))).checked(!enabled),
    ];
    let mut settings = MenuEntry::submenu("Settings", "sliders", settings_sub);
    settings.section = Some(String::new());
    items.push(settings);
    items.push(MenuEntry::submenu("Action", "more-circle", action_sub));
    items
}

// ---------------------------------------------------------------------------
// App icons (PackageIcon): PNGs the native side renders into its cache.
// ---------------------------------------------------------------------------

thread_local! {
    static APP_ICONS: RefCell<HashMap<String, Option<slint::Image>>> = RefCell::new(HashMap::new());
}

pub fn app_icon(pkg: &str) -> Option<slint::Image> {
    if let Some(known) = APP_ICONS.with(|i| i.borrow().get(pkg).cloned()) {
        return known;
    }
    APP_ICONS.with(|i| i.borrow_mut().insert(pkg.to_string(), None));
    let p = pkg.to_string();
    app::call("appIcon", json!({ "package": pkg }), move |r| {
        if let Ok(Value::String(path)) = r {
            if let Ok(image) = slint::Image::load_from_path(std::path::Path::new(&path)) {
                APP_ICONS.with(|i| i.borrow_mut().insert(p, Some(image)));
                mark(Mark::ROWS);
            }
        }
    });
    None
}

// ---------------------------------------------------------------------------
// Dispatch
// ---------------------------------------------------------------------------

pub fn project(_w: &AppWindow, _m: Mark) {}
pub fn opened(_kind: &str, _arg: &str, _id: i32) {}
pub fn closed(_kind: &str, _arg: &str, _id: i32) {}
pub fn act(_id: &str, _arg: &str) -> bool {
    false
}
pub fn act2(_id: &str, _a: &str, _b: &str) -> bool {
    false
}
pub fn menu(_key: &str) -> Vec<MenuEntry> {
    Vec::new()
}
pub fn install(_w: &AppWindow) {}
