//! The pages: each module projects [crate::app::Data] onto its Slint global
//! and handles its actions (`Nav.act(id, arg)` / `Nav.act2(id, a, b)`) and
//! menus (`Nav.open-menu(key, x, y)`), dispatched here by name.

use std::cell::Cell;
use std::time::Duration;

use slint::{ComponentHandle, Model};

use crate::app::{data, with_window};
use crate::nav::MenuEntry;
use crate::{AppWindow, Nav};

pub mod dashboard;
pub mod egress;
pub mod profiles;
pub mod proxies;
pub mod requests;
pub mod settings;
pub mod tools;

/// What changed, so only the affected pages are projected again.
#[derive(Clone, Copy, PartialEq, Eq)]
pub struct Mark(pub u32);

impl Mark {
    pub const NONE: Mark = Mark(0);
    pub const DASH: Mark = Mark(1);
    pub const PROXIES: Mark = Mark(2);
    pub const PROFILES: Mark = Mark(4);
    pub const TOOLS: Mark = Mark(8);
    pub const SETTINGS: Mark = Mark(16);
    pub const RECORDS: Mark = Mark(32);
    pub const THEME: Mark = Mark(64);
    pub const RATES: Mark = Mark(128);
    pub const CLOCK: Mark = Mark(256);
    pub const ALL: Mark = Mark(0xffff);
}

impl std::ops::BitOr for Mark {
    type Output = Mark;
    fn bitor(self, rhs: Mark) -> Mark {
        Mark(self.0 | rhs.0)
    }
}

impl Mark {
    pub fn has(self, other: Mark) -> bool {
        self.0 & other.0 != 0
    }
}

thread_local! {
    static PENDING: Cell<u32> = const { Cell::new(0) };
    static SCHEDULED: Cell<bool> = const { Cell::new(false) };
    static VISIBLE: Cell<[bool; 4]> = const { Cell::new([false; 4]) };
}

/// Schedules a projection of the marked pages on the next turn of the event loop.
pub fn mark(m: Mark) {
    if m.0 == 0 {
        return;
    }
    PENDING.with(|p| p.set(p.get() | m.0));
    if !SCHEDULED.with(|s| s.replace(true)) {
        slint::Timer::single_shot(Duration::ZERO, flush);
    }
}

fn flush() {
    SCHEDULED.with(|s| s.set(false));
    let m = Mark(PENDING.with(|p| p.replace(0)));
    if !data(|d| d.ready) && !m.has(Mark::THEME) && m != Mark::ALL {
        return;
    }
    let Some(w) = crate::app::window() else { return };
    if m.has(Mark::THEME) || m.has(Mark::SETTINGS) || m == Mark::ALL {
        crate::theme_apply::apply();
    }
    if !data(|d| d.ready) {
        return;
    }
    project_app(&w);
    if m.has(Mark::DASH) || m.has(Mark::RATES) || m.has(Mark::CLOCK) || m.has(Mark::PROFILES) || m.has(Mark::PROXIES) {
        dashboard::project(&w, m);
    }
    if m.has(Mark::PROXIES) || m.has(Mark::SETTINGS) {
        proxies::project(&w);
    }
    if m.has(Mark::PROFILES) || m.has(Mark::DASH) || m.has(Mark::CLOCK) {
        profiles::project(&w, m);
    }
    if m.has(Mark::TOOLS) || m.has(Mark::DASH) || m.has(Mark::SETTINGS) || m.has(Mark::CLOCK) {
        tools::project(&w, m);
    }
    if m.has(Mark::SETTINGS) || m.has(Mark::DASH) || m.has(Mark::PROFILES) {
        settings::project(&w, m);
    }
    if m.has(Mark::RECORDS) || m.has(Mark::SETTINGS) {
        requests::project(&w, m);
    }
    sync_visibility(false);
}

fn project_app(w: &AppWindow) {
    let app = w.global::<crate::App>();
    data(|d| {
        app.set_status(d.status().into());
        app.set_running(d.running());
        app.set_transitioning(d.transitioning());
        app.set_remote(d.remote());
        app.set_remote_name(d.dashboard.get("remote").map(crate::app::text).unwrap_or_default().into());
        app.set_has_selected_profile(d.selected_profile().is_some());
        app.set_profile_count(d.profiles.len() as i32);
        app.set_version(d.version.as_str().into());
        app.set_core_version(d.core_version.as_str().into());
        app.set_xray_version(d.xray_version.as_str().into());
        app.set_mihomo_version(d.mihomo_version.as_str().into());
    });
}

/// BoxDataSubscription: native sampling only while a page shows the data.
pub fn sync_visibility(force: bool) {
    let Some(w) = crate::app::window() else { return };
    if !data(|d| d.ready) {
        return;
    }
    let nav = w.global::<Nav>();
    let tab = nav.get_tab().to_string();
    let routes: Vec<String> = nav.get_routes().iter().map(|r| r.kind.to_string()).collect();
    let covered = nav.get_mobile() && !routes.is_empty();
    let dash_shown = tab == "Dashboard" && !covered;
    let cards = dashboard::shown_cards();
    let memory = (dash_shown && cards.iter().any(|c| c == "Memory")) || routes.iter().any(|r| r == "memory");
    let lan = (dash_shown && cards.iter().any(|c| c == "Intranet IP")) || routes.iter().any(|r| r == "intranet" || r == "lan-add");
    let logs = (!covered && tab == "Logs") || routes.iter().any(|r| r == "logs");
    let connections = (!covered && tab == "Requests") || routes.iter().any(|r| r == "requests" || r == "connection");
    let next = [memory, lan, logs, connections];
    let previous = VISIBLE.with(|v| v.replace(next));
    let names = ["setMemoryVisible", "setLanVisible", "setLogsVisible", "setConnectionsVisible"];
    for i in 0..4 {
        if force || previous[i] != next[i] {
            crate::app::act(names[i], serde_json::json!({ "visible": next[i] }));
        }
    }
}

/// A route opened: pages prepare its state.
pub fn opened(kind: &str, arg: &str, id: i32) {
    dashboard::opened(kind, arg, id);
    proxies::opened(kind, arg, id);
    profiles::opened(kind, arg, id);
    tools::opened(kind, arg, id);
    settings::opened(kind, arg, id);
    requests::opened(kind, arg, id);
}

pub fn closed(kind: &str, arg: &str, id: i32) {
    profiles::closed(kind, arg, id);
    tools::closed(kind, arg, id);
    settings::closed(kind, arg, id);
    requests::closed(kind, arg, id);
}

/// Back handled by a page itself (a search bar, the dashboard editor).
pub fn back() -> bool {
    dashboard::back() || profiles::back()
}

fn act(id: &str, arg: &str) {
    match id {
        "copy" => {
            crate::app::copy_text(arg);
            crate::app::message("Copied");
        }
        "open-url" => crate::app::act("openUrl", serde_json::json!({ "url": arg })),
        _ => {
            let _ = dashboard::act(id, arg)
                || proxies::act(id, arg)
                || profiles::act(id, arg)
                || tools::act(id, arg)
                || settings::act(id, arg)
                || requests::act(id, arg);
        }
    }
}

fn act2(id: &str, a: &str, b: &str) {
    let _ = dashboard::act2(id, a, b)
        || proxies::act2(id, a, b)
        || profiles::act2(id, a, b)
        || tools::act2(id, a, b)
        || settings::act2(id, a, b)
        || requests::act2(id, a, b);
}

/// The menu for a key: the page that owns it builds the entries.
pub fn menu(key: &str) -> Vec<MenuEntry> {
    let mut entries = proxies::menu(key);
    if entries.is_empty() {
        entries = profiles::menu(key);
    }
    if entries.is_empty() {
        entries = tools::menu(key);
    }
    if entries.is_empty() {
        entries = settings::menu(key);
    }
    if entries.is_empty() {
        entries = requests::menu(key);
    }
    if entries.is_empty() {
        entries = dashboard::menu(key);
    }
    entries
}

pub fn install(window: &AppWindow) {
    let nav = window.global::<Nav>();
    nav.on_act(|id, arg| act(&id, &arg));
    nav.on_act2(|id, a, b| act2(&id, &a, &b));
    dashboard::install(window);
    proxies::install(window);
    profiles::install(window);
    tools::install(window);
    settings::install(window);
    requests::install(window);
    with_window(|_| {});
}
