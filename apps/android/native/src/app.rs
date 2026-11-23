//! App state (compose/BoxController.kt): the BoxBridge snapshot fed by
//! events, the bridge calls, messages and the start-up handshake. Pages read
//! [Data] and project it onto their Slint globals (src/pages/*.rs). Everything
//! here runs on the Slint event-loop thread.

use std::cell::{Cell, RefCell};
use std::collections::{HashMap, VecDeque};
use std::rc::Rc;

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
    // The rows behind [`Data::connections`], built from the host's
    // "connectionEvents" batches.
    pub connection_events: fkbox_client_core::connections::Connections,
    pub logs: Vec<Value>,
    // The records behind [`Data::logs`], built from the host's "logEvents"
    // batches.
    pub log_records: fkbox_client_core::logs::Logs,
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
    // The host's raw answers of a few commands are shaped here (bridge.rs).
    let shaped = method.to_string();
    let reply = move |result: Result<Value, String>| reply(result.map(|v| fkbox_mobile_ffi::bridge::finish(&shaped, v)));
    let id = CALLS.with(|c| {
        let mut c = c.borrow_mut();
        c.next += 1;
        let id = c.next;
        c.pending.insert(id, Box::new(reply));
        id
    });
    // The Taildrop inbox's subscribe/unsubscribe stay in the runtime (taildrop.rs).
    if let Some(result) = with_runtime(|rt| rt.tool_status.inbox.command(method, &args)) {
        match result {
            Ok(out) => {
                on_reply(id, true, &json!({ "v": Value::Null }).to_string());
                run_outputs(out);
            }
            Err(error) => on_reply(id, false, &json!({ "v": error }).to_string()),
        }
        return;
    }
    // The checks BoxBridge ran before a command: its arguments against the
    // service and remote session state, their defaults, the stream pages
    // (mobile-ffi bridge.rs).
    let args = match with_runtime(|rt| rt.step(method, args)) {
        fkbox_mobile_ffi::bridge::Step::Forward(args) => args,
        fkbox_mobile_ffi::bridge::Step::Answer(value, out) => {
            on_reply(id, true, &json!({ "v": value }).to_string());
            run_outputs(out);
            return;
        }
        fkbox_mobile_ffi::bridge::Step::Reject(error) => {
            on_reply(id, false, &json!({ "v": error }).to_string());
            return;
        }
    };
    // A command naming a tool peer, exit node or USBIP server is checked
    // against the status lists, and gets the peer's details (tool_status.rs).
    let args = match with_runtime(|rt| rt.tool_status.prepare(method, args)) {
        Ok(args) => args,
        Err(error) => {
            on_reply(id, false, &json!({ "v": error }).to_string());
            return;
        }
    };
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

/// Switches the system HTTP proxy of the local service (the dashboard's
/// switch): ignored while unavailable or already switching.
pub fn set_system_proxy(enabled: bool) {
    if !with_runtime(|rt| rt.system_proxy_switchable()) {
        return;
    }
    with_runtime(|rt| rt.system_proxy_switching());
    call("setSystemProxy", json!({ "enabled": enabled }), move |result| {
        let out = with_runtime(|rt| rt.system_proxy_switched(result.as_ref().ok().map(|_| enabled)));
        if let Err(error) = result {
            report(&error);
        }
        run_outputs(out);
    });
}

/// Clears the logs: through the core when it runs (its broadcast clears the
/// stream), otherwise the waiting lines here. `then` gets whether it worked.
pub fn clear_logs(then: impl FnOnce(bool) + 'static) {
    call("clearLogs", json!({}), move |result| match result {
        Ok(sent) => {
            if sent.as_bool() == Some(false) {
                with_runtime(|rt| rt.clear_logs());
                schedule_logs();
            }
            then(true)
        }
        Err(error) => {
            report(&error);
            then(false)
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
    let value = serde_json::from_str::<Value>(payload).ok().and_then(|mut v| v.get_mut("v").map(Value::take)).unwrap_or(Value::Null);
    if ok {
        reply(Ok(value))
    } else {
        let message = text(&value);
        reply(Err(if message.is_empty() { "Operation failed".into() } else { message }))
    }
}

// ---------------------------------------------------------------------------
// Settings and preferences (client store; host for platform effects)
// ---------------------------------------------------------------------------

/// Saves interface preferences in the client store; without one (the
/// screenshot harness) the host keeps them.
pub fn set_preferences(values: Value, then: impl FnOnce(bool) + 'static) {
    if let Some(store) = crate::store::get() {
        match crate::store::update_preferences(&store, &values) {
            Ok(saved) => {
                data_mut(|d| d.preferences = saved);
                pages::mark(Mark::ALL);
                then(true)
            }
            Err(error) => {
                report(&error);
                then(false)
            }
        }
        return;
    }
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

/// The profile rows from the client store; `None` without a store. A read
/// failure is reported and keeps the rows shown.
fn stored_profiles() -> Option<Vec<Value>> {
    let store = crate::store::get()?;
    match crate::store::profiles(&store) {
        Ok(rows) => Some(rows),
        Err(error) => {
            crate::defer("store", move || report(&error));
            None
        }
    }
}

fn stored_preferences() -> Option<Json> {
    let store = crate::store::get()?;
    match crate::store::preferences(&store) {
        Ok(values) => Some(values),
        Err(error) => {
            crate::defer("store", move || report(&error));
            None
        }
    }
}

/// The service settings the pages show: the client store's snapshot
/// (`fkbox_client_core::service_settings`) with the platform's own state
/// from `payload` on top (the battery optimization exemption). Without a
/// store (the screenshot harness) the payload is the whole snapshot; a read
/// failure is reported and keeps `current`.
pub fn service_settings(current: &Json, payload: &Value) -> Json {
    let mut out = match crate::store::get().map(|store| crate::store::service_settings(&store)) {
        Some(Ok(snapshot)) => snapshot,
        Some(Err(error)) => {
            crate::defer("store", move || report(&error));
            current.clone()
        }
        None => Json::new(),
    };
    for (key, value) in obj(payload) {
        out.insert(key, value);
    }
    out
}

/// The platform's own entries of the shown settings (kept across a write
/// the UI made itself until the host answers).
fn platform_settings(settings: &Json) -> Value {
    Value::Object(settings.iter().filter(|(k, _)| k.as_str() == "batteryExempt").map(|(k, v)| (k.clone(), v.clone())).collect())
}

/// Writes service settings straight to the client store when they need
/// nothing from the platform first (no package query switch, no app scan),
/// then tells the host what was written (its settings cache, setup
/// options, geo schedule). `None` when the host must save them.
fn write_settings_here(values: &Value, then: impl FnOnce(Result<(), String>) + 'static) -> Option<()> {
    let store = crate::store::get()?;
    let map = values.as_object()?;
    let written = match crate::store::plan_service_settings(&store, map) {
        Ok(plan) if plan.store_only() => crate::store::patch_service_settings(&store, map),
        Ok(_) => return None,
        Err(error) => Err(error),
    };
    match written {
        Ok(written) => {
            data_mut(|d| {
                let platform = platform_settings(&d.settings);
                d.settings = service_settings(&d.settings, &platform);
            });
            call("serviceSettingsWritten", json!({ "written": written }), move |result| {
                match result {
                    Ok(platform) => data_mut(|d| d.settings = service_settings(&d.settings, &platform)),
                    Err(error) => report(&error),
                }
                pages::mark(Mark::ALL);
            });
            then(Ok(()))
        }
        Err(error) => then(Err(error)),
    }
    Some(())
}

/// Saves the profile order in the client store (through the host without one).
pub fn reorder_profiles(ids: Vec<i64>, then: impl FnOnce(bool) + 'static) {
    let Some(store) = crate::store::get() else {
        act_then("reorderProfiles", json!({ "ids": ids }), then);
        return;
    };
    match crate::store::reorder_profiles(&store, &ids) {
        Ok(()) => {
            if let Some(rows) = stored_profiles() {
                data_mut(|d| {
                    d.profiles_revision += 1;
                    d.profiles = rows;
                });
            }
            pages::mark(Mark::DASH | Mark::PROFILES | Mark::SETTINGS);
            then(true)
        }
        Err(error) => {
            report(&error);
            then(false)
        }
    }
}

pub fn set_preference(key: &str, value: Value) {
    set_preferences(json!({ key: value }), |_| {});
}

/// An auto-restart setting restarts a running local service itself. A
/// setting that needs nothing from the platform first is written to the
/// client store here; the rest (query mode, managed app scans, restarts) go
/// through the host, which writes them through client-core as well.
pub fn set_setting(key: &str, value: Value, restart: bool, auto_restart: bool) {
    let (busy, running, remote, transitioning) = data(|d| (d.service_settings_changing, d.running(), d.remote(), d.transitioning()));
    if busy || (auto_restart && transitioning && !remote) {
        return;
    }
    let restart_service = auto_restart && running && !remote;
    if !restart_service {
        let handled = write_settings_here(&json!({ key: value.clone() }), move |result| {
            match result {
                Ok(()) => data_mut(|d| {
                    if restart && !auto_restart && d.running() && !d.remote() {
                        d.apply_required = Some("Restart".into());
                    }
                }),
                Err(error) => report(&error),
            }
            pages::mark(Mark::ALL);
        });
        if handled.is_some() {
            return;
        }
    }
    if auto_restart {
        data_mut(|d| d.service_settings_changing = true);
        pages::mark(Mark::SETTINGS);
    }
    call("setSetting", json!({ "key": key, "value": value, "restart": restart_service }), move |result| {
        if auto_restart {
            data_mut(|d| d.service_settings_changing = false);
        }
        match result {
            Ok(saved) => data_mut(|d| {
                d.settings = service_settings(&d.settings, &saved);
                if restart_service {
                    d.apply_required = None;
                } else if restart && !auto_restart && d.running() && !d.remote() {
                    d.apply_required = Some("Restart".into());
                }
            }),
            Err(error) => report(&error),
        }
        pages::mark(Mark::ALL);
    });
}

pub fn set_settings(values: Value, restart: bool, then: impl FnOnce(bool) + 'static) {
    let then = std::rc::Rc::new(std::cell::RefCell::new(Some(then)));
    let here = then.clone();
    let handled = write_settings_here(&values, move |result| {
        let ok = match result {
            Ok(()) => {
                data_mut(|d| {
                    if restart && d.running() && !d.remote() {
                        d.apply_required = Some("Restart".into());
                    }
                });
                true
            }
            Err(error) => {
                report(&error);
                false
            }
        };
        pages::mark(Mark::ALL);
        if let Some(then) = here.borrow_mut().take() {
            then(ok)
        }
    });
    if handled.is_some() {
        return;
    }
    call("updateSettings", json!({ "values": values }), move |result| {
        let ok = match result {
            Ok(saved) => {
                data_mut(|d| {
                    d.settings = service_settings(&d.settings, &saved);
                    if restart && d.running() && !d.remote() {
                        d.apply_required = Some("Restart".into());
                    }
                });
                pages::mark(Mark::ALL);
                true
            }
            Err(error) => {
                report(&error);
                false
            }
        };
        if let Some(then) = then.borrow_mut().take() {
            then(ok)
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
pub fn set_lan_proxy(
    enabled: bool,
    port: Option<i64>,
    profile_id: Option<i64>,
    revision: Option<String>,
    then: impl FnOnce(bool) + 'static,
) {
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
    call("setLanProxy", json!({ "profileId": target, "revision": revision, "enabled": enabled, "port": port }), move |result| {
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
    });
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
    match kind.as_str() {
        // The host's raw libbox command stream callbacks and its service
        // state go through the stream runtime (mobile-ffi runtime.rs), which
        // answers with the events below.
        "stream" => {
            let out = with_runtime(|rt| rt.stream(&value));
            run_outputs(out);
        }
        // A tool status subscription's update (BoxToolStatus).
        "toolStatus" => {
            let out = with_runtime(|rt| rt.tool_status_event(&value));
            run_outputs(out);
        }
        // A libbox diagnostic's callback (BoxDiagnostics).
        "diagnostic" => {
            let out = with_runtime(|rt| rt.diagnostics.event(&value));
            run_outputs(out);
        }
        "hostState" => {
            let out = with_runtime(|rt| rt.host_state(&value));
            run_outputs(out);
        }
        "foreground" => {
            with_runtime(|rt| rt.set_foreground(value.as_bool().unwrap_or(true)));
            apply(&kind, value);
        }
        _ => apply(&kind, value),
    }
}

thread_local! {
    static RUNTIME: RefCell<fkbox_mobile_ffi::runtime::Runtime> = RefCell::new(fkbox_mobile_ffi::runtime::Runtime::new());
    static LOGS_FLUSH: Cell<(bool, i64)> = const { Cell::new((false, 0)) };
}

pub fn with_runtime<R>(f: impl FnOnce(&mut fkbox_mobile_ffi::runtime::Runtime) -> R) -> R {
    RUNTIME.with(|rt| f(&mut rt.borrow_mut()))
}

fn now_millis() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}

/// Applies what the stream runtime answered: its events, the dashboard probes
/// it asks the host for, and log lines at most about once a second
/// (`UI_UPDATE_INTERVAL_MILLIS` on the host before).
pub fn run_outputs(out: Vec<fkbox_mobile_ffi::runtime::Output>) {
    use fkbox_mobile_ffi::runtime::Output;
    for output in out {
        match output {
            Output::Event(name, value) => apply(name, value),
            Output::Probe(probe) => {
                call("dashboardProbe", json!({ "local": probe.local, "notes": probe.notes }), |result| {
                    if let Ok(result) = result {
                        let out = with_runtime(|rt| rt.probe(&result));
                        run_outputs(out);
                    }
                });
            }
            Output::LogsPending => schedule_logs(),
            Output::Host(method, args) => act(method, args),
            Output::Error(message) => report(&message),
        }
    }
}

// ---------------------------------------------------------------------------
// Diagnostics (mobile-ffi diagnostics.rs; the host runs the libbox tests)
// ---------------------------------------------------------------------------

fn diagnostics_then(f: impl FnOnce(&mut fkbox_mobile_ffi::runtime::Runtime) -> Result<Vec<fkbox_mobile_ffi::runtime::Output>, String>) {
    match with_runtime(f) {
        Ok(out) => run_outputs(out),
        Err(error) => report(&error),
    }
}

/// Starts a STUN test (through `outbound` while the service runs).
pub fn start_stun(server: &str, outbound: &str) {
    diagnostics_then(|rt| {
        let service = rt.service_available();
        rt.diagnostics.start_stun(server, outbound, service)
    });
}

/// Starts a network quality test (`url`, `outbound`, `serial`, `http3`,
/// `seconds`), after a confirmation on a metered network.
pub fn start_quality(args: Value) {
    if with_runtime(|rt| rt.diagnostics.running(fkbox_mobile_ffi::diagnostics::Kind::Quality)) {
        report("A network quality test is already running");
        return;
    }
    call("tools.metered", json!({}), move |result| {
        let metered = result.as_ref().ok().and_then(Value::as_bool).unwrap_or(false);
        diagnostics_then(|rt| {
            let service = rt.service_available();
            rt.diagnostics.start_quality(args, metered, service)
        });
    });
}

/// The metered-network warning was answered (`start`: run the test).
pub fn confirm_quality(start: bool) {
    let out = with_runtime(|rt| {
        let service = rt.service_available();
        rt.diagnostics.confirm_quality(start, service)
    });
    run_outputs(out);
}

/// Pings a Tailscale peer's first address through its endpoint.
pub fn start_ping(endpoint: &str, address: &str) {
    let out = with_runtime(|rt| rt.diagnostics.start_ping(endpoint, address));
    run_outputs(out);
}

/// Stops a diagnostic: "stun", "quality" or "ping".
pub fn stop_diagnostic(kind: &str) {
    let Some(kind) = fkbox_mobile_ffi::diagnostics::Kind::from_key(kind) else { return };
    if !with_runtime(|rt| rt.diagnostics.running(kind)) {
        return;
    }
    let out = with_runtime(|rt| rt.diagnostics.cancel(kind));
    run_outputs(out);
}

const LOGS_INTERVAL_MILLIS: i64 = 1000;

fn schedule_logs() {
    let (pending, last) = LOGS_FLUSH.with(Cell::get);
    if pending {
        return;
    }
    let wait = (last + LOGS_INTERVAL_MILLIS - now_millis()).clamp(0, LOGS_INTERVAL_MILLIS);
    LOGS_FLUSH.with(|f| f.set((true, last)));
    slint::Timer::single_shot(std::time::Duration::from_millis(wait as u64), || {
        crate::guarded("logs", || {
            LOGS_FLUSH.with(|f| f.set((false, now_millis())));
            if let Some(batch) = with_runtime(|rt| rt.drain_logs()) {
                apply("logEvents", batch);
            }
        })
    });
}

fn apply(kind: &str, value: Value) {
    let mark = data_mut(|d| match kind {
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
            d.dashboard = obj(&fkbox_client_core::dashboard::view(&value));
            Mark::ALL
        }
        // The host changed profiles in the client store (no payload: re-read
        // it); a host without a store sends the rows.
        "profiles" => {
            d.profiles_revision += 1;
            d.profile_stats.clear();
            d.profiles = if value.is_array() { rows(&value) } else { stored_profiles().unwrap_or_else(|| d.profiles.clone()) };
            Mark::DASH | Mark::PROFILES | Mark::SETTINGS
        }
        "groups" => {
            d.groups = rows(&value);
            Mark::DASH | Mark::PROXIES
        }
        // The core's connection events since the last batch (BoxBridge
        // flushConnections); "dnsQueries" is the local core's own count.
        "connectionEvents" => {
            let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0);
            d.connection_events.apply_batch(&value, now);
            d.connections = d.connection_events.rows();
            d.connection_stats = obj(&d.connection_events.stats(long(&value["dnsQueries"])));
            Mark::RECORDS | Mark::RATES
        }
        // The core's log lines since the last batch (BoxBridge logs
        // stream), after a clear when "reset" is set.
        "logEvents" => {
            let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0);
            d.log_records.apply_batch(&value, now);
            d.logs = d.log_records.rows();
            d.default_log_level = d.log_records.default_level().map(str::to_string);
            Mark::RECORDS
        }
        "settings" => {
            d.settings = service_settings(&d.settings, &value);
            Mark::ALL
        }
        "preferences" => {
            d.preferences = if value.is_object() { obj(&value) } else { stored_preferences().unwrap_or_else(|| d.preferences.clone()) };
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
            crate::defer("error", move || report(&message));
            Mark::NONE
        }
        // The host's own view of the window's system bar insets, in physical
        // pixels. Android sends it because Slint reads screen-level insets,
        // which are wrong in floating and split-screen windows; a platform
        // that sends none keeps Window.safe-area-insets.
        "insets" => {
            let value = value.clone();
            crate::defer("insets", move || apply_insets(&value));
            Mark::NONE
        }
        // The system's animator duration scale changed (developer options or
        // Remove animations in accessibility; resent on resume).
        "motion" => {
            let scale = crate::theme_apply::motion_scale(value["scale"].as_f64().unwrap_or(1.0));
            crate::defer("motion", move || {
                crate::theme_apply::set_motion_scale(scale);
                pages::reset_gestures();
            });
            Mark::NONE
        }
        // The host's own menu or dialog (src/nav.rs).
        "ui" => {
            let value = value.clone();
            crate::defer("ui", move || crate::nav::on_native_event(&value));
            Mark::NONE
        }
        // The native text editor (src/native_ime.rs).
        "ime" => {
            let value = value.clone();
            crate::defer("ime", move || crate::native_ime::on_event(&value));
            Mark::NONE
        }
        // The host went to the background (false) or came back (true).
        "foreground" => {
            let active = value.as_bool().unwrap_or(true);
            crate::defer("foreground", move || pages::set_foreground(active));
            Mark::NONE
        }
        "intentAvailable" => {
            crate::defer("intent", || {
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
                // The host's service state; the stream runtime composes the
                // dashboard from it (and later from the streams).
                let out = with_runtime(|rt| rt.host_state(&result["hostState"]));
                for output in &out {
                    if let fkbox_mobile_ffi::runtime::Output::Event("dashboard", value) = output {
                        d.dashboard = obj(&fkbox_client_core::dashboard::view(value));
                    }
                }
                let probes: Vec<_> = out.into_iter().filter(|o| matches!(o, fkbox_mobile_ffi::runtime::Output::Probe(_))).collect();
                if !probes.is_empty() {
                    crate::defer("probe", move || run_outputs(probes));
                }
                // The host opened the client store before answering.
                d.profiles = stored_profiles().unwrap_or_else(|| rows(&result["profiles"]));
                d.settings = service_settings(&d.settings, &result["settings"]);
                d.preferences = stored_preferences().unwrap_or_else(|| obj(&result["preferences"]));
                if result["lan"].is_object() {
                    d.lan = obj(&result["lan"]);
                }
                d.tool_state = obj(&with_runtime(|rt| rt.tool_status.snapshot()));
                // The diagnostics' defaults (STUN server, quality URL).
                let (stun, quality) = with_runtime(|rt| {
                    use fkbox_mobile_ffi::diagnostics::Kind;
                    rt.diagnostics.defaults(&result["diagnostics"]);
                    (rt.diagnostics.state(Kind::Stun).clone(), rt.diagnostics.state(Kind::Quality).clone())
                });
                d.stun = obj(&stun);
                d.quality = obj(&quality);
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
            // Absent from hosts without the setting: motion stays at full scale.
            let motion = crate::theme_apply::motion_scale(value["animatorScale"].as_f64().unwrap_or(1.0));
            crate::theme_apply::set_motion_scale(motion);
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
