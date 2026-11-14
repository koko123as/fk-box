//! App state (compose/BoxController.kt): the BoxBridge snapshot fed by
//! events, the bridge calls, messages and the start-up handshake. Pages read
//! [Data] and project it onto their Slint globals (src/pages/*.rs). Everything
//! here runs on the Slint event-loop thread.

use std::cell::RefCell;
use std::rc::Rc;
use std::collections::{HashMap, VecDeque};

use serde_json::{json, Map, Value};
use slint::{ComponentHandle, SharedString};

use crate::format;
use crate::host;
use crate::i18n::tr;
use crate::pages::{self, Mark};
use crate::{AppWindow, Nav};

pub type Json = Map<String, Value>;

type Reply = Box<dyn FnOnce(Result<Value, String>)>;

/// BoxController's fields.
#[derive(Default)]
pub struct Data {
    pub dashboard: Json,
    pub settings: Json,
    pub preferences: Json,
    pub rates: Json,
    pub tool_state: Json,
    pub stun: Json,
    pub quality: Json,
    pub inbox: Json,
    pub ping: Json,
    pub terminal: Json,
    pub memory: Json,
    // The dashboard's connection counts (BoxBridge connectionStats).
    pub connection_stats: Json,
    pub lan: Json,
    pub transfers: Vec<Value>,
    pub usb_devices: Vec<Value>,
    pub outbounds: Vec<Value>,
    pub profiles: Vec<Value>,
    pub groups: Vec<Value>,
    pub connections: Vec<Value>,
    pub logs: Vec<Value>,
    pub default_log_level: Option<String>,
    pub upload_history: VecDeque<f64>,
    pub download_history: VecDeque<f64>,
    pub version: String,
    pub core_version: String,
    pub xray_version: String,
    pub mihomo_version: String,
    pub system_accent: u32,
    pub profiles_revision: u64,
    pub ready: bool,
    pub lan_changing: bool,
    pub service_settings_changing: bool,
    pub apply_required: Option<String>,
    pub pending_intents: VecDeque<String>,
    pub profile_stats: HashMap<i64, Option<Json>>,
}

#[derive(Default)]
struct Calls {
    next: i64,
    pending: HashMap<i64, Reply>,
}

thread_local! {
    static DATA: RefCell<Data> = RefCell::new(Data { system_accent: 0xff007aff, ..Default::default() });
    static CALLS: RefCell<Calls> = RefCell::new(Calls::default());
    static WINDOW: RefCell<Option<slint::Weak<AppWindow>>> = const { RefCell::new(None) };
}

pub fn data<R>(f: impl FnOnce(&Data) -> R) -> R {
    DATA.with(|d| f(&d.borrow()))
}

pub fn data_mut<R>(f: impl FnOnce(&mut Data) -> R) -> R {
    DATA.with(|d| f(&mut d.borrow_mut()))
}

pub fn window() -> Option<AppWindow> {
    WINDOW.with(|w| w.borrow().as_ref().and_then(|w| w.upgrade()))
}

pub fn with_window(f: impl FnOnce(&AppWindow)) {
    if let Some(w) = window() {
        f(&w)
    }
}

// ---------------------------------------------------------------------------
// JSON helpers (BoxController.json / rows / asLong)
// ---------------------------------------------------------------------------

pub fn obj(value: &Value) -> Json {
    value.as_object().cloned().unwrap_or_default()
}

pub fn rows(value: &Value) -> Vec<Value> {
    value.as_array().cloned().unwrap_or_default()
}

pub fn long(value: &Value) -> Option<i64> {
    value.as_i64().or_else(|| value.as_f64().map(|v| v as i64))
}

pub fn num(value: &Value) -> f64 {
    value.as_f64().unwrap_or(0.0)
}

pub fn text(value: &Value) -> String {
    match value {
        Value::String(s) => s.clone(),
        Value::Null => String::new(),
        other => other.to_string(),
    }
}

pub fn opt_text(value: &Value) -> Option<String> {
    match value {
        Value::String(s) if !s.is_empty() => Some(s.clone()),
        _ => None,
    }
}

pub fn truthy(value: &Value) -> bool {
    value.as_bool() == Some(true)
}

pub fn strings(value: &Value) -> Vec<String> {
    value.as_array().map(|a| a.iter().filter_map(|v| v.as_str().map(str::to_string)).collect()).unwrap_or_default()
}

pub fn ss(value: impl AsRef<str>) -> SharedString {
    SharedString::from(value.as_ref())
}

// ---------------------------------------------------------------------------
// Derived values (BoxController's getters)
// ---------------------------------------------------------------------------

impl Data {
    pub fn remote(&self) -> bool {
        self.dashboard.get("remote").map(|v| !v.is_null()).unwrap_or(false)
    }

    pub fn running(&self) -> bool {
        if self.remote() {
            truthy(&self.dashboard.get("remoteConnected").cloned().unwrap_or_default())
        } else {
            self.status() == "Started"
        }
    }

    pub fn status(&self) -> String {
        self.dashboard.get("status").map(text).unwrap_or_default()
    }

    pub fn transitioning(&self) -> bool {
        matches!(self.status().as_str(), "Starting" | "Stopping")
    }

    pub fn selected_profile_id(&self) -> i64 {
        self.dashboard.get("selectedProfileId").and_then(long).unwrap_or(-1)
    }

    pub fn selected_profile(&self) -> Option<Json> {
        let id = self.selected_profile_id();
        self.profiles.iter().find(|p| long(&p["id"]) == Some(id)).map(obj)
    }

    pub fn profile(&self, id: i64) -> Option<Json> {
        self.profiles.iter().find(|p| long(&p["id"]) == Some(id)).map(obj)
    }

    pub fn pref(&self, key: &str) -> Value {
        self.preferences.get(key).cloned().unwrap_or(Value::Null)
    }

    pub fn pref_str(&self, key: &str, fallback: &str) -> String {
        self.preferences.get(key).and_then(|v| v.as_str()).unwrap_or(fallback).to_string()
    }

    pub fn setting(&self, key: &str) -> Value {
        self.settings.get(key).cloned().unwrap_or(Value::Null)
    }

    pub fn lan_addresses(&self) -> Vec<String> {
        self.lan.get("addresses").map(strings).unwrap_or_default()
    }

    pub fn can_set_lan_proxy(&self) -> bool {
        !self.remote()
            && !self.transitioning()
            && !self.lan_changing
            && self.lan.get("canToggle").map(truthy).unwrap_or(false)
            && self.lan.get("profileId").and_then(long) == Some(self.selected_profile_id())
    }

    pub fn lan_proxy_endpoints(&self) -> Vec<String> {
        if self.remote() || !self.running() || !self.lan.get("running").map(truthy).unwrap_or(false) {
            return Vec::new();
        }
        let mut out: Vec<String> = Vec::new();
        for binding in self.lan.get("activeBindings").map(rows).unwrap_or_default() {
            for endpoint in strings(&binding["endpoints"]) {
                if !out.contains(&endpoint) {
                    out.push(endpoint);
                }
            }
        }
        out
    }

    pub fn lan_copy_text(&self) -> Option<String> {
        let addresses = self.lan_addresses();
        let endpoints = self.lan_proxy_endpoints();
        for address in &addresses {
            for endpoint in &endpoints {
                if endpoint.starts_with(&format!("{address}:")) || endpoint.starts_with(&format!("[{address}]:")) {
                    return Some(endpoint.clone());
                }
            }
        }
        endpoints.first().cloned().or_else(|| addresses.first().cloned())
    }
}

// ---------------------------------------------------------------------------
// Calls
// ---------------------------------------------------------------------------

/// Runs one bridge command; `reply` runs on the UI thread with its result.
pub fn call(method: &str, args: Value, reply: impl FnOnce(Result<Value, String>) + 'static) {
    let id = CALLS.with(|c| {
        let mut c = c.borrow_mut();
        c.next += 1;
        let id = c.next;
        c.pending.insert(id, Box::new(reply));
        id
    });
    if let Err(error) = host::invoke(id, method, &args.to_string()) {
        on_reply(id, false, &json!({ "v": error }).to_string());
    }
}

/// act(): runs a command and reports its failure.
pub fn act(method: &str, args: Value) {
    call(method, args, |result| {
        if let Err(error) = result {
            report(&error);
        }
    });
}

/// launchAct(then): `then` gets whether the command succeeded.
pub fn act_then(method: &str, args: Value, then: impl FnOnce(bool) + 'static) {
    call(method, args, move |result| match result {
        Ok(_) => then(true),
        Err(error) => {
            report(&error);
            then(false)
        }
    });
}

/// A call whose value the caller needs; failures are reported.
pub fn fetch(method: &str, args: Value, then: impl FnOnce(Value) + 'static) {
    call(method, args, move |result| match result {
        Ok(value) => then(value),
        Err(error) => report(&error),
    });
}

/// An error in the snackbar, translated when it is one of the host's own
/// messages (BoxBridge require/check texts).
pub fn report(error: &str) {
    crate::nav::toast(&tr(&format::clean_error(error)), true);
}

/// box.message(): a translated line in the snackbar.
pub fn message(text: &str) {
    crate::nav::toast(&tr(text), false);
}

pub fn copy_text(value: &str) {
    call("copyText", json!({ "text": value }), |_| {});
}

/// A reply from Kotlin: `{"v": value}`, with `ok` false carrying the error text.
pub fn on_reply(id: i64, ok: bool, payload: &str) {
    let reply = CALLS.with(|c| c.borrow_mut().pending.remove(&id));
    let Some(reply) = reply else { return };
    let value = serde_json::from_str::<Value>(payload)
        .ok()
        .and_then(|mut v| v.get_mut("v").map(Value::take))
        .unwrap_or(Value::Null);
    if ok {
        reply(Ok(value))
    } else {
        let message = text(&value);
        reply(Err(if message.is_empty() { "Operation failed".into() } else { message }))
    }
}

// ---------------------------------------------------------------------------
// Settings and preferences (BoxController.setPreferences / setSetting / setSettings)
// ---------------------------------------------------------------------------

pub fn set_preferences(values: Value, then: impl FnOnce(bool) + 'static) {
    call("setPreferences", json!({ "values": values }), move |result| match result {
        Ok(value) => {
            data_mut(|d| d.preferences = obj(&value));
            pages::mark(Mark::ALL);
            then(true)
        }
        Err(error) => {
            report(&error);
            then(false)
        }
    });
}

pub fn set_preference(key: &str, value: Value) {
    set_preferences(json!({ key: value }), |_| {});
}

/// An auto-restart setting restarts a running local service itself.
pub fn set_setting(key: &str, value: Value, restart: bool, auto_restart: bool) {
    let (busy, running, remote, transitioning) =
        data(|d| (d.service_settings_changing, d.running(), d.remote(), d.transitioning()));
    if busy || (auto_restart && transitioning && !remote) {
        return;
    }
    let restart_service = auto_restart && running && !remote;
    if auto_restart {
        data_mut(|d| d.service_settings_changing = true);
        pages::mark(Mark::SETTINGS);
    }
    call(
        "setSetting",
        json!({ "key": key, "value": value, "restart": restart_service }),
        move |result| {
            if auto_restart {
                data_mut(|d| d.service_settings_changing = false);
            }
            match result {
                Ok(saved) => data_mut(|d| {
                    d.settings = obj(&saved);
                    if restart_service {
                        d.apply_required = None;
                    } else if restart && !auto_restart && d.running() && !d.remote() {
                        d.apply_required = Some("Restart".into());
                    }
                }),
                Err(error) => report(&error),
            }
            pages::mark(Mark::ALL);
        },
    );
}

pub fn set_settings(values: Value, restart: bool, then: impl FnOnce(bool) + 'static) {
    call("updateSettings", json!({ "values": values }), move |result| match result {
        Ok(saved) => {
            data_mut(|d| {
                d.settings = obj(&saved);
                if restart && d.running() && !d.remote() {
                    d.apply_required = Some("Restart".into());
                }
            });
            pages::mark(Mark::ALL);
            then(true)
        }
        Err(error) => {
            report(&error);
            then(false)
        }
    });
}

pub fn clear_apply_required() {
    data_mut(|d| d.apply_required = None);
    pages::mark(Mark::DASH | Mark::SETTINGS);
}

/// profileStats(id), cached until the profiles change.
pub fn profile_stats(id: i64, then: impl FnOnce() + 'static) {
    let known = data(|d| d.profile_stats.contains_key(&id));
    if known {
        return;
    }
    data_mut(|d| d.profile_stats.insert(id, None));
    let revision = data(|d| d.profiles_revision);
    call("profileStats", json!({ "id": id }), move |result| {
        if data(|d| d.profiles_revision) != revision {
            return;
        }
        let stats = result.ok().map(|v| obj(&v)).unwrap_or_default();
        data_mut(|d| d.profile_stats.insert(id, Some(stats)));
        then();
    });
}

pub fn refresh_lan() {
    call("refreshLan", json!({}), |result| match result {
        Ok(value) if value.is_object() => {
            data_mut(|d| d.lan = obj(&value));
            pages::mark(Mark::DASH);
        }
        Ok(_) => {}
        Err(error) => report(&error),
    });
}

/// setLanProxy: false when the switch could not be changed.
pub fn set_lan_proxy(enabled: bool, port: Option<i64>, profile_id: Option<i64>, revision: Option<String>, then: impl FnOnce(bool) + 'static) {
    let (can, selected, current_revision) = data(|d| (d.can_set_lan_proxy(), d.selected_profile_id(), d.lan.get("revision").map(text)));
    if !can {
        then(false);
        return;
    }
    let target = profile_id.unwrap_or(selected);
    if target != selected {
        report("Selected profile changed; reload it before saving");
        then(false);
        return;
    }
    data_mut(|d| d.lan_changing = true);
    pages::mark(Mark::DASH);
    let revision = revision.or(current_revision);
    call(
        "setLanProxy",
        json!({ "profileId": target, "revision": revision, "enabled": enabled, "port": port }),
        move |result| {
            data_mut(|d| d.lan_changing = false);
            match result {
                Ok(value) => {
                    data_mut(|d| {
                        if !d.remote() && d.selected_profile_id() == target {
                            d.lan = obj(&value);
                        }
                    });
                    pages::mark(Mark::DASH);
                    then(true)
                }
                Err(error) => {
                    report(&error);
                    refresh_lan();
                    pages::mark(Mark::DASH);
                    then(false)
                }
            }
        },
    );
}

// ---------------------------------------------------------------------------
// Events
// ---------------------------------------------------------------------------

/// A BoxBridge event: `{"v": {"type": ..., "value": ...}}`.
pub fn on_event(payload: &str) {
    let Ok(mut root) = serde_json::from_str::<Value>(payload) else { return };
    let mut event = root.get_mut("v").map(Value::take).unwrap_or(Value::Null);
    let kind = text(&event["type"]);
    let value = event.get_mut("value").map(Value::take).unwrap_or(Value::Null);
    let mark = data_mut(|d| match kind.as_str() {
        "tools" => {
            d.tool_state = obj(&value);
            Mark::TOOLS
        }
        "outbounds" => {
            d.outbounds = rows(&value);
            Mark::TOOLS
        }
        "stun" => {
            d.stun = obj(&value);
            Mark::TOOLS
        }
        "quality" => {
            d.quality = obj(&value);
            Mark::TOOLS
        }
        "inbox" => {
            d.inbox = obj(&value);
            Mark::TOOLS
        }
        "ping" => {
            d.ping = obj(&value);
            Mark::TOOLS
        }
        "transfers" => {
            d.transfers = rows(&value);
            Mark::TOOLS
        }
        "usbDevices" => {
            d.usb_devices = rows(&value);
            Mark::TOOLS
        }
        "terminal" => {
            d.terminal = obj(&value);
            Mark::TOOLS
        }
        "memory" => {
            d.memory = obj(&value);
            Mark::DASH
        }
        "lan" => {
            d.lan = obj(&value);
            Mark::DASH
        }
        "browserComplete" => {
            crate::pages::tools::browser_complete(&value);
            Mark::TOOLS
        }
        "dashboard" => {
            d.dashboard = obj(&value);
            Mark::ALL
        }
        "profiles" => {
            d.profiles_revision += 1;
            d.profile_stats.clear();
            d.profiles = rows(&value);
            Mark::DASH | Mark::PROFILES | Mark::SETTINGS
        }
        "groups" => {
            d.groups = rows(&value);
            Mark::DASH | Mark::PROXIES
        }
        "connections" => {
            d.connections = rows(&value);
            Mark::RECORDS
        }
        "connectionStats" => {
            d.connection_stats = obj(&value);
            Mark::RATES
        }
        "logs" => {
            let entries = rows(&value["entries"]);
            if value["reset"] == Value::Bool(false) {
                let first = long(&value["firstId"]);
                let last = d.logs.last().and_then(|l| long(&l["id"])).unwrap_or(-1);
                match first {
                    Some(first) => d.logs.retain(|l| long(&l["id"]).unwrap_or(0) >= first),
                    None => d.logs.clear(),
                }
                d.logs.extend(entries.into_iter().filter(|e| long(&e["id"]).unwrap_or(0) > last));
            } else {
                d.logs = entries;
            }
            d.default_log_level = value["defaultLogLevel"].as_str().map(str::to_string);
            Mark::RECORDS
        }
        "settings" => {
            d.settings = obj(&value);
            Mark::ALL
        }
        "preferences" => {
            d.preferences = obj(&value);
            Mark::ALL
        }
        "systemAccent" => {
            if let Some(v) = long(&value) {
                d.system_accent = v as u32;
            }
            Mark::THEME
        }
        "rates" => {
            d.rates = obj(&value);
            d.upload_history.push_back(num(&value["upload"]));
            d.download_history.push_back(num(&value["download"]));
            while d.upload_history.len() > 30 {
                d.upload_history.pop_front();
            }
            while d.download_history.len() > 30 {
                d.download_history.pop_front();
            }
            Mark::RATES
        }
        "error" => {
            let message = text(&value);
            slint::Timer::single_shot(std::time::Duration::ZERO, move || report(&message));
            Mark::NONE
        }
        // The host's own view of the window's system bar insets, in physical
        // pixels. Android sends it because Slint reads screen-level insets,
        // which are wrong in floating and split-screen windows; a platform
        // that sends none keeps Window.safe-area-insets.
        "insets" => {
            let value = value.clone();
            slint::Timer::single_shot(std::time::Duration::ZERO, move || apply_insets(&value));
            Mark::NONE
        }
        // The host's own menu or dialog (src/nav.rs).
        "ui" => {
            let value = value.clone();
            slint::Timer::single_shot(std::time::Duration::ZERO, move || crate::nav::on_native_event(&value));
            Mark::NONE
        }
        // The native text editor (src/native_ime.rs).
        "ime" => {
            let value = value.clone();
            slint::Timer::single_shot(std::time::Duration::ZERO, move || crate::native_ime::on_event(&value));
            Mark::NONE
        }
        // The host went to the background (false) or came back (true).
        "foreground" => {
            let active = value.as_bool().unwrap_or(true);
            slint::Timer::single_shot(std::time::Duration::ZERO, move || pages::set_foreground(active));
            Mark::NONE
        }
        "intentAvailable" => {
            slint::Timer::single_shot(std::time::Duration::ZERO, || {
                call("takeIntent", json!({}), |result| match result {
                    Ok(Value::String(uri)) => {
                        data_mut(|d| d.pending_intents.push_back(uri));
                        pages::profiles::drain_intents();
                    }
                    Ok(_) => {}
                    Err(error) => report(&error),
                })
            });
            Mark::NONE
        }
        _ => Mark::NONE,
    });
    pages::mark(mark);
}

/// The host's insets (physical pixels) replace the last ones. They are kept
/// physical: the window converts them with its scale factor whenever that
/// changes (ui/app.slint), so insets that arrive before the window knows its
/// scale (a new surface or activity after the background) are not stretched by
/// it. The logical values are also set here at once for this frame.
pub fn apply_insets(value: &Value) {
    with_window(|w| {
        let px = |key: &str| (num(&value[key]) as f32).max(0.0);
        let scale = w.window().scale_factor().max(0.1);
        let nav = w.global::<crate::Nav>();
        nav.set_host_top_px(px("top"));
        nav.set_host_bottom_px(px("bottom"));
        nav.set_host_left_px(px("left"));
        nav.set_host_right_px(px("right"));
        nav.set_host_insets(true);
        nav.set_inset_top(px("top") / scale);
        nav.set_inset_bottom(px("bottom") / scale);
        nav.set_inset_left(px("left") / scale);
        nav.set_inset_right(px("right") / scale);
    });
}

// ---------------------------------------------------------------------------
// Start-up
// ---------------------------------------------------------------------------

fn initialize() {
    with_window(|w| w.global::<Nav>().set_startup_error(SharedString::new()));
    call("attach", json!({}), |_| {});
    call("ready", json!({}), |result| match result {
        Ok(result) => {
            data_mut(|d| {
                d.version = text(&result["version"]);
                d.core_version = text(&result["coreVersion"]);
                d.xray_version = text(&result["xrayVersion"]);
                d.mihomo_version = text(&result["mihomoVersion"]);
                d.system_accent = long(&result["systemAccentColor"]).map(|v| v as u32).unwrap_or(0xff007aff);
                d.dashboard = obj(&result["dashboard"]);
                d.profiles = rows(&result["profiles"]);
                d.settings = obj(&result["settings"]);
                d.preferences = obj(&result["preferences"]);
                if result["lan"].is_object() {
                    d.lan = obj(&result["lan"]);
                }
                d.tool_state = obj(&result["tools"]);
                d.stun = obj(&result["diagnostics"]["stun"]);
                d.quality = obj(&result["diagnostics"]["quality"]);
                if let Some(intent) = result["intent"].as_str() {
                    d.pending_intents.push_back(intent.to_string());
                }
                d.ready = true;
            });
            with_window(|w| w.global::<Nav>().set_ready(true));
            pages::mark(Mark::ALL);
            pages::sync_visibility(true);
            pages::profiles::drain_intents();
        }
        Err(error) => with_window(|w| w.global::<Nav>().set_startup_error(format::clean_error(&error).into())),
    });
}

/// Creates the window and starts the bridge handshake; the caller runs it.
pub fn start() -> Result<(AppWindow, Rc<slint::Timer>), slint::PlatformError> {
    let window = AppWindow::new()?;
    WINDOW.with(|w| *w.borrow_mut() = Some(window.as_weak()));
    crate::theme_apply::apply();
    crate::nav::install(&window);
    crate::native_ime::install(&window);
    pages::install(&window);

    // The system language and time zone, then the bridge.
    call("locale", json!({}), |result| {
        if let Ok(value) = result {
            crate::i18n::set_language(&text(&value["language"]));
            format::set_utc_offset_minutes(long(&value["utcOffsetMinutes"]).unwrap_or(0));
            let scale = value["fontScale"].as_f64().unwrap_or(1.0) as f32;
            crate::theme_apply::SYSTEM_FONT_SCALE.with(|s| s.set(scale));
        }
        pages::mark(Mark::ALL);
        initialize();
    });
    window.global::<Nav>().on_retry_startup(initialize);

    // The relative times ("2 min ago"); stopped in the background.
    let clock = Rc::new(slint::Timer::default());
    clock.start(slint::TimerMode::Repeated, std::time::Duration::from_secs(1), || pages::mark(Mark::CLOCK));
    pages::pausable(&clock, None);
    Ok((window, clock))
}

pub fn run() -> Result<(), slint::PlatformError> {
    let (window, _clock) = start()?;
    window.run()
}
