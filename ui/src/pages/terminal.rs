//! Terminal (pages/TerminalPage.kt): the SSH prompt, sessions, the peer picker
//! and the Ghostty settings, themes, fonts and configuration editor. The
//! Ghostty surface itself is the native TerminalActivity (bridge/NativePages.kt),
//! opened over the UI through JNI.

use std::cell::RefCell;
use std::rc::Rc;

use serde_json::{json, Value};

use crate::app::{self, data, long, obj, rows as list, text, truthy};
use crate::format;
use crate::i18n::tr;
use crate::nav;
use crate::pages::{mark, Mark};
use crate::rows::{self, Bar, Page, Row};

fn s(v: &Value, k: &str) -> String {
    match v.get(k) {
        None | Some(Value::Null) => String::new(),
        Some(x) => text(x),
    }
}

/// The texts the native page shows (its extra keys and prompts), translated here.
fn native_texts() -> Value {
    let mut m = serde_json::Map::new();
    for k in [
        "Esc", "Tab", "Ctrl", "Alt", "Home", "End", "PgUp", "PgDn", "Paste", "Keyboard", "Paste multiple lines?", "Pasted text may execute commands in this terminal.",
        "Allow terminal to read clipboard?", "Allow", "Cancel", "Link", "Open", "Copy",
    ] {
        m.insert(k.into(), json!(tr(k)));
    }
    Value::Object(m)
}

/// GhosttySurface: the native terminal page for `session` (or the preview).
fn open_surface(session: &str, title: &str, preview: bool) {
    app::act("openTerminalView", json!({ "id": session, "title": title, "preview": preview, "dark": crate::theme_apply::is_dark(), "texts": native_texts() }));
}

struct Term {
    endpoint: Option<String>,
    peer: Option<String>,
    name: Option<String>,
    username: String,
    terminal_type: String,
    quick: bool,
    prompt: bool,
    loading: bool,
    busy: bool,
    error: Option<String>,
    /// The session the native surface was last opened for.
    shown: Option<String>,
}

type TermRef = Rc<RefCell<Term>>;

fn start(st: &TermRef) {
    let args = {
        let mut t = st.borrow_mut();
        if t.busy || t.username.trim().is_empty() {
            return;
        }
        t.busy = true;
        json!({ "endpoint": t.endpoint, "peer": t.peer, "username": t.username.trim(), "terminalType": t.terminal_type.trim(), "remember": t.quick })
    };
    mark(Mark::ROWS);
    let a = st.clone();
    app::call("ssh.start", args, move |r| {
        let mut t = a.borrow_mut();
        match r {
            Ok(v) => {
                app::data_mut(|d| d.terminal = obj(&v));
                t.prompt = false;
                t.error = None;
            }
            Err(e) => t.error = Some(format::clean_error(&e)),
        }
        t.busy = false;
        drop(t);
        mark(Mark::ALL);
    });
}

pub fn open(endpoint: Option<String>, peer: Option<String>, name: Option<String>) {
    let prompt = peer.is_some();
    let st: TermRef = Rc::new(RefCell::new(Term {
        endpoint: endpoint.clone(),
        peer: peer.clone(),
        name,
        username: "root".into(),
        terminal_type: "xterm-256color".into(),
        quick: false,
        prompt,
        loading: true,
        busy: false,
        error: None,
        shown: None,
    }));
    let a = st.clone();
    app::call("ssh.snapshot", json!({}), move |r| match r {
        Ok(v) => {
            app::data_mut(|d| d.terminal = obj(&v));
            if a.borrow().prompt {
                let b = a.clone();
                app::call("ssh.options", json!({ "endpoint": endpoint, "peer": peer }), move |r| {
                    match r {
                        Ok(o) => {
                            let mut t = b.borrow_mut();
                            t.username = o.get("username").and_then(Value::as_str).unwrap_or("root").to_string();
                            t.terminal_type = o.get("terminalType").and_then(Value::as_str).unwrap_or("xterm-256color").to_string();
                            t.quick = truthy(&o["remember"]);
                            t.loading = false;
                            t.error = None;
                        }
                        Err(e) => {
                            let mut t = b.borrow_mut();
                            t.loading = false;
                            t.error = Some(format::clean_error(&e));
                        }
                    }
                    let quick = b.borrow().quick;
                    if quick {
                        start(&b);
                    }
                    mark(Mark::ROWS);
                });
            } else {
                a.borrow_mut().loading = false;
                mark(Mark::ALL);
            }
        }
        Err(e) => {
            let mut t = a.borrow_mut();
            t.loading = false;
            t.error = Some(format::clean_error(&e));
            drop(t);
            mark(Mark::ROWS);
        }
    });
    rows::push(move || terminal_page(&st));
}

fn new_session() {
    // TerminalPeerPicker: the Tailscale peers with SSH host keys.
    rows::form(|| {
        let mut peers = Vec::new();
        for endpoint in data(|d| d.tool_state.get("tailscale").map(list).unwrap_or_default()) {
            for group in list(&endpoint["userGroups"]) {
                for peer in list(&group["peers"]) {
                    if peer["sshHostKeys"].as_array().map(|a| !a.is_empty()).unwrap_or(false) {
                        peers.push((s(&endpoint, "endpointTag"), peer));
                    }
                }
            }
        }
        let mut rows_list = Vec::new();
        if peers.is_empty() {
            rows_list.push(Row::empty(&tr("No SSH peers"), &tr("Start a Tailscale endpoint with SSH-enabled peers."), "devices"));
        }
        for (endpoint, peer) in peers {
            let (id, host) = (s(&peer, "id"), s(&peer, "hostName"));
            let ips = peer["tailscaleIPs"].as_array().map(|a| a.iter().map(text).collect::<Vec<_>>().join(" · ")).unwrap_or_default();
            rows_list.push(Row::item(&host).subtitle(&ips).key(&id.clone()).on(move |_| {
                nav::pop();
                open(Some(endpoint.clone()), Some(id.clone()), Some(host.clone()));
            }));
        }
        Page::new(&tr("New session")).rows(rows_list)
    });
}

fn terminal_page(st: &TermRef) -> Page {
    let snapshot = data(|d| Value::Object(d.terminal.clone()));
    let sessions = list(&snapshot["sessions"]);
    let active_id = s(&snapshot, "activeId");
    let active = sessions.iter().find(|x| s(x, "id") == active_id).cloned();
    let t = st.borrow();
    let title = if t.prompt {
        format!("SSH · {}", t.name.clone().unwrap_or_default())
    } else {
        active.as_ref().map(|a| s(a, "name")).filter(|n| !n.is_empty()).unwrap_or_else(|| tr("Terminal"))
    };
    let mut rows_list = Vec::new();
    let mut page_empty = None;
    if t.loading {
    } else if t.prompt {
        let a = st.clone();
        rows_list.push(Row::field(&tr("Username"), &t.username, "", move |v| a.borrow_mut().username = v).key("username"));
        rows_list.push(Row::spacer(16));
        let a = st.clone();
        rows_list.push(Row::field(&tr("Terminal type"), &t.terminal_type, "", move |v| a.borrow_mut().terminal_type = v).key("type").mono());
        rows_list.push(Row::spacer(16));
        let a = st.clone();
        rows_list.push(Row::switch(&tr("Quick connect"), t.quick, move |v| a.borrow_mut().quick = v).subtitle(&tr("Remember options and connect directly next time")).key("quick"));
        if let Some(e) = &t.error {
            rows_list.push(Row::text(e).danger());
        }
        rows_list.push(Row::spacer(20));
        let a = st.clone();
        let mut connect = Row::button(&tr("Connect"), 1, move || start(&a)).key("connect").enabled(!t.busy);
        connect.data.checked = true;
        rows_list.push(connect);
    } else if let Some(session) = &active {
        let sid = s(session, "id");
        let name = s(session, "name");
        let phase = s(session, "phase");
        let progress = &session["progress"];
        if phase == "CONNECTING" || progress.as_object().map(|o| !o.is_empty()).unwrap_or(false) {
            let percent = progress["percent"].as_f64();
            rows_list.push(Row::progress("", if phase == "CONNECTING" || percent.is_none() { -1.0 } else { (percent.unwrap_or(0.0) / 100.0) as f32 }));
        }
        let banner = s(session, "banner");
        if !banner.is_empty() {
            rows_list.push(Row::text(&banner).checked(true));
        }
        let (i, n) = (sid.clone(), name.clone());
        rows_list.push(Row::item(&name).subtitle(&format!("{}@{}", s(session, "username"), s(session, "host"))).icon("code").chevron().key("surface").on(move |_| open_surface(&i, &n, false)));
        if phase == "FINISHED" {
            let i = sid.clone();
            rows_list.push(Row::button(&tr("Close session"), 2, move || app::act("ssh.closeSession", json!({ "id": i }))).key("close"));
        }
        // The native surface opens over the page for each newly active session.
        if t.shown.as_deref() != Some(sid.as_str()) && phase != "FINISHED" {
            let (a, i, n) = (st.clone(), sid.clone(), name.clone());
            crate::defer("terminal surface", move || {
                if a.borrow().shown.as_deref() != Some(i.as_str()) {
                    a.borrow_mut().shown = Some(i.clone());
                    open_surface(&i, &n, false);
                }
            });
        }
    } else {
        page_empty = Some(t.error.clone().unwrap_or_default());
    }
    let busy = t.loading || t.busy;
    drop(t);
    let mut page = Page::new(&title)
        .loading(busy)
        .bar(vec![Bar::new("add", "New session", new_session), Bar::new("layers", "Sessions", open_sessions), Bar::new("settings", "Ghostty settings", open_settings)])
        .rows(rows_list);
    if let Some(message) = page_empty {
        page = page.empty(&tr("No terminal sessions"), &message, "code").empty_action(&tr("New session"), new_session);
    }
    page
}

fn open_sessions() {
    rows::form(|| {
        let snapshot = data(|d| Value::Object(d.terminal.clone()));
        let sessions = list(&snapshot["sessions"]);
        let active = s(&snapshot, "activeId");
        let mut rows_list = Vec::new();
        if sessions.is_empty() {
            rows_list.push(Row::empty(&tr("No terminal sessions"), "", "code"));
        }
        for session in &sessions {
            let id = s(session, "id");
            let phase = match s(session, "phase").as_str() {
                "RUNNING" => "Running",
                "CONNECTING" => "Connecting",
                _ => "Disconnected",
            };
            let (i1, i2) = (id.clone(), id.clone());
            rows_list.push(
                Row::item(&s(session, "name"))
                    .subtitle(&format!("{}@{} · {}", s(session, "username"), s(session, "host"), tr(phase)))
                    .icon(if id == active { "check-circle" } else { "code" })
                    .key(&id)
                    .on(move |_| {
                        let route = nav::top().map(|r| r.id);
                        app::act_then("ssh.select", json!({ "id": i1 }), move |ok| {
                            if ok {
                                if let Some(r) = route {
                                    nav::close(r);
                                }
                            }
                        });
                    })
                    .trailing("close", move || {
                        let i = i2.clone();
                        nav::confirm_styled("Close this SSH session?", None, "Close", false, move |ok| {
                            if ok {
                                app::act("ssh.closeSession", json!({ "id": i }));
                            }
                        });
                    }),
            );
        }
        let mut bar = Vec::new();
        if !sessions.is_empty() {
            bar.push(Bar::new("copy", "Duplicate session", || {
                let route = nav::top().map(|r| r.id);
                app::act_then("ssh.duplicate", json!({}), move |ok| {
                    if ok {
                        if let Some(r) = route {
                            nav::close(r);
                        }
                    }
                });
            }));
        }
        Page::new(&tr("Sessions")).bar(bar).rows(rows_list)
    });
}

// ---------------------------------------------------------------------------
// Ghostty settings
// ---------------------------------------------------------------------------

type Loaded = Rc<RefCell<(Option<Value>, Option<String>, bool)>>;

fn load_settings(st: &Loaded) {
    let a = st.clone();
    app::call("terminalSettings", json!({}), move |r| {
        match r {
            Ok(v) => {
                a.borrow_mut().0 = Some(v);
                a.borrow_mut().1 = None;
            }
            Err(e) => a.borrow_mut().1 = Some(format::clean_error(&e)),
        }
        mark(Mark::ROWS);
    });
}

fn set_setting(st: &Loaded, key: &str, value: Value) {
    if std::mem::replace(&mut st.borrow_mut().2, true) {
        return;
    }
    mark(Mark::ROWS);
    let a = st.clone();
    app::call("setTerminalSetting", json!({ "key": key, "value": value }), move |r| {
        match r {
            Ok(v) => a.borrow_mut().0 = Some(v),
            Err(e) => app::report(&e),
        }
        a.borrow_mut().2 = false;
        mark(Mark::ROWS);
    });
}

fn open_settings() {
    let st: Loaded = Rc::new(RefCell::new((None, None, false)));
    load_settings(&st);
    rows::push(move || {
        let (current, error, saving) = st.borrow().clone();
        let mut rows_list = Vec::new();
        if let Some(e) = &error {
            let a = st.clone();
            rows_list.push(Row::empty(&tr("Error"), e, "info").value(&tr("Retry")).on(move |_| load_settings(&a)));
        }
        if let Some(cur) = &current {
            rows_list.push(Row::item(&tr("Terminal preview")).icon("code").chevron().key("preview").on(|_| open_surface("", &tr("Terminal preview"), true)));
            for dark in [false, true] {
                let theme_key = if dark { "darkTheme" } else { "lightTheme" };
                let custom = s(cur, theme_key).is_empty() && !cur[theme_key].is_null();
                rows_list.push(Row::section(&tr(if dark { "Dark configuration" } else { "Light configuration" })));
                let a = st.clone();
                rows_list.push(Row::item(&tr("Theme")).subtitle(&if custom { tr("Custom configuration") } else { s(cur, theme_key) }).chevron().key(&format!("{theme_key}-theme")).on(move |_| {
                    let b = a.clone();
                    open_themes(dark, Rc::new(move || load_settings(&b)));
                }));
                let a = st.clone();
                let default = cur[if dark { "defaultDarkTheme" } else { "defaultLightTheme" }].clone();
                rows_list.push(Row::switch(&tr("Custom configuration"), custom, move |on| set_setting(&a, theme_key, if on { json!("") } else { default.clone() })).enabled(!saving).key(&format!("{theme_key}-custom")));
                if custom {
                    let a = st.clone();
                    let initial = s(cur, if dark { "darkConfig" } else { "lightConfig" });
                    rows_list.push(Row::item(&tr("Edit Ghostty configuration")).chevron().key(&format!("{theme_key}-edit")).on(move |_| {
                        let b = a.clone();
                        open_config(dark, initial.clone(), Rc::new(move || load_settings(&b)));
                    }));
                }
            }
            let follow = cur["fontFollowTheme"] != Value::Bool(false);
            let size = long(&cur["fontSize"]).unwrap_or(14);
            rows_list.push(Row::section(&tr("Font")));
            let a = st.clone();
            rows_list.push(Row::switch(&tr("Font follows theme"), follow, move |v| set_setting(&a, "fontFollowTheme", json!(v))).enabled(!saving).key("follow"));
            if !follow {
                let path = s(cur, "fontPath");
                let family = s(cur, "fontFamily");
                let shown = if !path.is_empty() { path.rsplit('/').next().unwrap_or("").to_string() } else if family.is_empty() { "monospace".into() } else { family };
                let a = st.clone();
                rows_list.push(Row::item(&tr("Font family")).subtitle(&shown).chevron().key("family").on(move |_| {
                    let b = a.clone();
                    open_fonts(Rc::new(move || load_settings(&b)));
                }));
            }
            let (a, b) = (st.clone(), st.clone());
            rows_list.push(
                Row::field(&tr("Font size"), &size.to_string(), "8-48", |_| {})
                .on_done(move |v| {
                    if let Ok(n) = v.trim().parse::<i64>() {
                        set_setting(&b, "fontSize", json!(n.clamp(8, 48)))
                    }
                })
                .number()
                .key("size")
                .trailing("remove", move || {
                    if size > 8 {
                        set_setting(&a, "fontSize", json!(size - 1))
                    }
                }),
            );
            let c = st.clone();
            rows_list.push(Row::item(&tr("Increase font size")).key("size-up").enabled(size < 48).trailing("add", move || {
                if size < 48 {
                    set_setting(&c, "fontSize", json!(size + 1))
                }
            }));
        }
        Page::new(&tr("Ghostty settings")).loading(saving || (current.is_none() && error.is_none())).rows(rows_list)
    });
}

fn open_themes(dark: bool, on_closed: Rc<dyn Fn()>) {
    let st: Rc<RefCell<(Option<Vec<Value>>, Option<String>, Option<String>, String)>> = Rc::new(RefCell::new((None, None, None, String::new())));
    let load: Rc<dyn Fn()> = {
        let st = st.clone();
        Rc::new(move || {
            let a = st.clone();
            app::call("terminalSettings", json!({}), move |r| match r {
                Ok(settings) => {
                    a.borrow_mut().2 = settings.get(if dark { "darkTheme" } else { "lightTheme" }).and_then(Value::as_str).map(str::to_string);
                    let b = a.clone();
                    app::call("terminalThemes", json!({ "dark": dark }), move |r| {
                        match r {
                            Ok(v) => {
                                b.borrow_mut().0 = Some(list(&v));
                                b.borrow_mut().1 = None;
                            }
                            Err(e) => b.borrow_mut().1 = Some(format::clean_error(&e)),
                        }
                        mark(Mark::ROWS);
                    });
                }
                Err(e) => {
                    a.borrow_mut().1 = Some(format::clean_error(&e));
                    mark(Mark::ROWS);
                }
            });
        })
    };
    load();
    let id = rows::push(move || {
        let x = st.borrow();
        let mut rows_list = Vec::new();
        if let Some(e) = &x.1 {
            let l = load.clone();
            rows_list.push(Row::empty(&tr("Error"), e, "info").value(&tr("Retry")).on(move |_| l()));
        }
        let q = x.3.to_lowercase();
        for theme in x.0.clone().unwrap_or_default().into_iter().filter(|t| s(t, "name").to_lowercase().contains(&q)) {
            let name = s(&theme, "name");
            let n = name.clone();
            let mut r = Row::item(&name).key(&name).on(move |_| {
                let route = nav::top().map(|r| r.id);
                app::act_then("setTerminalSetting", json!({ "key": if dark { "darkTheme" } else { "lightTheme" }, "value": n }), move |ok| {
                    if ok {
                        if let Some(r) = route {
                            nav::close(r);
                        }
                    }
                });
            });
            if x.2.as_deref() == Some(name.as_str()) {
                r = r.trailing("check-circle", || {});
            }
            rows_list.push(r);
        }
        let loading = x.0.is_none() && x.1.is_none();
        drop(x);
        let a = st.clone();
        Page::new(&tr(if dark { "Dark theme" } else { "Light theme" })).loading(loading).search(move |q| a.borrow_mut().3 = q).rows(rows_list)
    });
    nav::on_closed(id, move || on_closed());
}

fn open_fonts(on_closed: Rc<dyn Fn()>) {
    let st: Rc<RefCell<(Option<Value>, Option<Value>, Option<String>)>> = Rc::new(RefCell::new((None, None, None)));
    let load: Rc<dyn Fn()> = {
        let st = st.clone();
        Rc::new(move || {
            let a = st.clone();
            app::call("terminalFonts", json!({}), move |r| match r {
                Ok(fonts) => {
                    a.borrow_mut().0 = Some(fonts);
                    let b = a.clone();
                    app::call("terminalSettings", json!({}), move |r| {
                        match r {
                            Ok(v) => {
                                b.borrow_mut().1 = Some(v);
                                b.borrow_mut().2 = None;
                            }
                            Err(e) => b.borrow_mut().2 = Some(format::clean_error(&e)),
                        }
                        mark(Mark::ROWS);
                    });
                }
                Err(e) => {
                    a.borrow_mut().2 = Some(format::clean_error(&e));
                    mark(Mark::ROWS);
                }
            });
        })
    };
    load();
    let id = rows::push(move || {
        let (fonts, settings, error) = st.borrow().clone();
        let mut rows_list = Vec::new();
        if let Some(e) = &error {
            let l = load.clone();
            rows_list.push(Row::empty(&tr("Error"), e, "info").value(&tr("Retry")).on(move |_| l()));
        }
        let settings = settings.unwrap_or(Value::Null);
        let select = |key: &'static str, value: String, l: Rc<dyn Fn()>| {
            move |_: String| {
                let l = l.clone();
                app::act_then("setTerminalSetting", json!({ "key": key, "value": value }), move |ok| {
                    if ok {
                        l()
                    }
                });
            }
        };
        rows_list.push(Row::section(&tr("System fonts")));
        for font in fonts.as_ref().map(|f| f["system"].as_array().cloned().unwrap_or_default()).unwrap_or_default() {
            let font = text(&font);
            let mut r = Row::item(&font).key(&format!("s:{font}")).on(select("fontFamily", font.clone(), load.clone()));
            if s(&settings, "fontFamily") == font && s(&settings, "fontPath").is_empty() {
                r = r.trailing("check-circle", || {});
            }
            rows_list.push(r);
        }
        rows_list.push(Row::section(&tr("Imported fonts")));
        for font in fonts.as_ref().map(|f| list(&f["imported"])).unwrap_or_default() {
            let path = s(&font, "path");
            let p = path.clone();
            let l = load.clone();
            let mut r = Row::item(&s(&font, "name")).key(&format!("i:{path}")).on(select("fontPath", path.clone(), load.clone())).trailing("delete", move || {
                let (p, l) = (p.clone(), l.clone());
                nav::confirm("Delete this font?", None, "Delete", move |ok| {
                    if ok {
                        app::act_then("deleteTerminalFont", json!({ "path": p }), move |_| l());
                    }
                });
            });
            if s(&settings, "fontPath") == path {
                r = r.icon("check-circle");
            }
            rows_list.push(r);
        }
        let l = load.clone();
        Page::new(&tr("Font"))
            .loading(fonts.is_none() && error.is_none())
            .bar(vec![Bar::new("add", "Import font", move || {
                let l = l.clone();
                app::act_then("importTerminalFont", json!({}), move |_| l());
            })])
            .rows(rows_list)
    });
    nav::on_closed(id, move || on_closed());
}

fn open_config(dark: bool, initial: String, on_closed: Rc<dyn Fn()>) {
    let st = Rc::new(RefCell::new((initial.clone(), initial, false)));
    let a = st.clone();
    let id = rows::push(move || {
        let (text_value, _saved, saving) = a.borrow().clone();
        let b = a.clone();
        let rows_list = vec![
            Row::editor("", &text_value, 20, move |v| b.borrow_mut().0 = v).mono().key("config"),
        ];
        let c = a.clone();
        Page::new(&tr(if dark { "Dark configuration" } else { "Light configuration" }))
            .loading(saving)
            .confirm(
                Bar::new("check", "Save", move || {
                    let value = {
                        let mut x = c.borrow_mut();
                        x.2 = true;
                        x.0.clone()
                    };
                    mark(Mark::ROWS);
                    let d = c.clone();
                    app::act_then("setTerminalSetting", json!({ "key": if dark { "darkConfig" } else { "lightConfig" }, "value": value.clone() }), move |ok| {
                        let mut x = d.borrow_mut();
                        x.2 = false;
                        if ok {
                            x.1 = value.clone();
                            app::message("Done");
                        }
                        drop(x);
                        mark(Mark::ROWS);
                    });
                })
                .when(!saving),
            )
            .rows(rows_list)
    });
    let a = st.clone();
    nav::guard(id, move || {
        let x = a.borrow();
        x.0 != x.1
    });
    nav::on_closed(id, move || on_closed());
}
