//! Window state: turns BoxBridge events into page rows and page actions into
//! bridge calls. Everything here runs on the Slint event-loop thread.

use std::cell::RefCell;
use std::collections::{HashMap, HashSet, VecDeque};
use std::rc::Rc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use serde_json::{json, Value};
use slint::{ComponentHandle, ModelRc, SharedString, Timer, TimerMode, VecModel};

use crate::format;
use crate::host;
use crate::{AppWindow, GroupRow, LogRow, NodeRow, ProfileRow};

const MAX_LOGS: usize = 500;

type Reply = Box<dyn FnOnce(Result<Value, String>)>;

#[derive(Default)]
struct State {
    window: slint::Weak<AppWindow>,
    next_call: i64,
    pending: HashMap<i64, Reply>,
    dashboard: Value,
    profiles: Vec<Value>,
    groups: Vec<Value>,
    expanded: HashMap<String, bool>,
    testing: HashSet<String>,
    logs: VecDeque<LogRow>,
    logs_model: Option<Rc<VecModel<LogRow>>>,
    started_at: i64,
}

thread_local! {
    static STATE: RefCell<State> = RefCell::new(State::default());
}

fn now_ms() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}

fn num(value: &Value) -> f64 {
    value.as_f64().unwrap_or(0.0)
}

fn int(value: &Value) -> i64 {
    value.as_i64().or_else(|| value.as_f64().map(|v| v as i64)).unwrap_or(0)
}

fn text(value: &Value) -> String {
    match value {
        Value::String(s) => s.clone(),
        Value::Null => String::new(),
        other => other.to_string(),
    }
}

fn with_window(f: impl FnOnce(&AppWindow)) {
    let window = STATE.with(|s| s.borrow().window.upgrade());
    if let Some(window) = window {
        f(&window);
    }
}

/// Calls a BoxBridge method; `reply` runs on the UI thread with its result.
pub fn call(method: &str, args: Value, reply: impl FnOnce(Result<Value, String>) + 'static) {
    let id = STATE.with(|s| {
        let mut s = s.borrow_mut();
        s.next_call += 1;
        let id = s.next_call;
        s.pending.insert(id, Box::new(reply));
        id
    });
    if let Err(error) = host::invoke(id, method, &args.to_string()) {
        on_reply(id, false, &json!({ "v": error }).to_string());
    }
}

/// Fire-and-forget call: failures are shown as a toast.
fn act(method: &str, args: Value) {
    call(method, args, |result| {
        if let Err(error) = result {
            toast(&error);
        }
    });
}

fn toast(message: &str) {
    let message = message.trim().to_string();
    with_window(|w| w.set_toast(message.clone().into()));
    Timer::single_shot(Duration::from_secs(4), move || {
        with_window(|w| {
            if w.get_toast().as_str() == message {
                w.set_toast(SharedString::new());
            }
        })
    });
}

/// A reply from Kotlin: `{"v": value}`, with `ok` false carrying the error text.
pub fn on_reply(id: i64, ok: bool, payload: &str) {
    let reply = STATE.with(|s| s.borrow_mut().pending.remove(&id));
    let Some(reply) = reply else { return };
    let value = serde_json::from_str::<Value>(payload)
        .ok()
        .and_then(|mut v| v.get_mut("v").map(Value::take))
        .unwrap_or(Value::Null);
    if ok {
        reply(Ok(value))
    } else {
        let message = text(&value);
        reply(Err(if message.is_empty() { "操作失败".into() } else { message }))
    }
}

/// A BoxBridge event: `{"v": {"type": ..., "value": ...}}`.
pub fn on_event(payload: &str) {
    let Ok(mut root) = serde_json::from_str::<Value>(payload) else { return };
    let mut event = root.get_mut("v").map(Value::take).unwrap_or(Value::Null);
    let kind = text(&event["type"]);
    let value = event.get_mut("value").map(Value::take).unwrap_or(Value::Null);
    match kind.as_str() {
        "dashboard" => set_dashboard(value),
        "profiles" => set_profiles(value),
        "groups" => set_groups(value),
        "rates" => set_rates(&value),
        "logs" => add_logs(&value),
        "error" => toast(&text(&value)),
        _ => {}
    }
}

fn set_dashboard(value: Value) {
    if !value.is_object() {
        return;
    }
    let status = text(&value["status"]);
    let started = int(&value["startedAt"]);
    let modes: Vec<SharedString> = value["modes"]
        .as_array()
        .map(|m| m.iter().map(|x| text(x).into()).collect())
        .unwrap_or_default();
    let mode = text(&value["mode"]);
    STATE.with(|s| {
        let mut s = s.borrow_mut();
        s.dashboard = value;
        s.started_at = started;
    });
    with_window(|w| {
        w.set_status(status.clone().into());
        w.set_status_text(format::status_text(&status).into());
        w.set_busy(status == "Starting" || status == "Stopping");
        w.set_modes(ModelRc::new(VecModel::from(modes)));
        w.set_mode(mode.into());
        if status != "Started" {
            w.set_uptime(SharedString::new());
            w.set_groups(ModelRc::new(VecModel::<GroupRow>::default()));
        }
    });
    refresh_profiles();
    tick();
}

fn set_profiles(value: Value) {
    let list = match value {
        Value::Array(list) => list,
        _ => return,
    };
    STATE.with(|s| s.borrow_mut().profiles = list);
    refresh_profiles();
}

fn refresh_profiles() {
    let now = now_ms();
    let (rows, current) = STATE.with(|s| {
        let s = s.borrow();
        let selected = int(&s.dashboard["selectedProfileId"]);
        let mut current = String::new();
        let rows: Vec<ProfileRow> = s
            .profiles
            .iter()
            .map(|p| {
                let id = int(&p["id"]);
                let name = text(&p["name"]);
                let remote = p["remote"].as_bool().unwrap_or(false);
                if id == selected {
                    current = name.clone();
                }
                let core = text(&p["resolvedCore"]);
                let core = if core.is_empty() { text(&p["core"]) } else { core };
                let mut detail = format!("{} · {}", if remote { "订阅" } else { "本地" }, format::core_label(&core));
                if remote {
                    detail.push_str(" · ");
                    detail.push_str(&format::ago(int(&p["updatedAt"]), now));
                }
                let total = num(&p["total"]);
                let used = num(&p["upload"]) + num(&p["download"]);
                let expire = int(&p["expire"]);
                let mut usage = String::new();
                if total > 0.0 {
                    usage = format!("已用 {} / {}", format::bytes(used), format::bytes(total));
                }
                if expire > 0 {
                    if !usage.is_empty() {
                        usage.push_str(" · ");
                    }
                    usage.push_str(&format!("到期 {}", format::date(expire)));
                }
                ProfileRow {
                    id: id.to_string().into(),
                    name: name.into(),
                    detail: detail.into(),
                    usage: usage.into(),
                    usage_ratio: if total > 0.0 { (used / total) as f32 } else { 0.0 },
                    remote,
                    selected: id == selected,
                }
            })
            .collect();
        (rows, current)
    });
    with_window(|w| {
        w.set_profiles(ModelRc::new(VecModel::from(rows)));
        w.set_profile_name(current.into());
    });
}

fn set_groups(value: Value) {
    let list = match value {
        Value::Array(list) => list,
        _ => return,
    };
    STATE.with(|s| s.borrow_mut().groups = list);
    refresh_groups();
}

fn refresh_groups() {
    let rows: Vec<GroupRow> = STATE.with(|s| {
        let s = s.borrow();
        s.groups
            .iter()
            .map(|g| {
                let tag = text(&g["tag"]);
                let selected = text(&g["selected"]);
                let expanded = s.expanded.get(&tag).copied().unwrap_or_else(|| g["expanded"].as_bool().unwrap_or(false));
                let items: Vec<NodeRow> = g["items"]
                    .as_array()
                    .map(|items| {
                        items
                            .iter()
                            .map(|n| {
                                let node = text(&n["tag"]);
                                let (delay, level) = format::delay(int(&n["delay"]));
                                NodeRow {
                                    selected: node == selected,
                                    tag: node.into(),
                                    kind: text(&n["type"]).into(),
                                    delay: delay.into(),
                                    level,
                                }
                            })
                            .collect()
                    })
                    .unwrap_or_default();
                let testing = s.testing.contains(&tag);
                GroupRow {
                    kind: if testing { "测速中".into() } else { text(&g["type"]).into() },
                    selected: selected.into(),
                    selectable: g["selectable"].as_bool().unwrap_or(false),
                    expanded,
                    items: ModelRc::new(VecModel::from(items)),
                    tag: tag.into(),
                }
            })
            .collect()
    });
    with_window(|w| w.set_groups(ModelRc::new(VecModel::from(rows))));
}

fn set_rates(value: &Value) {
    let upload = format::rate(num(&value["upload"]));
    let download = format::rate(num(&value["download"]));
    let memory = value.get("memoryBytes").map(num);
    with_window(|w| {
        w.set_upload(upload.into());
        w.set_download(download.into());
        if let Some(memory) = memory {
            w.set_memory(if memory > 0.0 { format::bytes(memory).into() } else { "-".into() });
        }
    });
}

fn add_logs(value: &Value) {
    let reset = value["reset"].as_bool().unwrap_or(false);
    let entries: Vec<LogRow> = value["entries"]
        .as_array()
        .map(|list| {
            list.iter()
                .map(|e| LogRow { level: text(&e["level"]).to_uppercase().into(), message: text(&e["message"]).into() })
                .collect()
        })
        .unwrap_or_default();
    STATE.with(|s| {
        let mut s = s.borrow_mut();
        if reset {
            s.logs.clear();
        }
        s.logs.extend(entries);
        while s.logs.len() > MAX_LOGS {
            s.logs.pop_front();
        }
        if let Some(model) = &s.logs_model {
            model.set_vec(s.logs.iter().cloned().collect::<Vec<_>>());
        }
    });
}

fn tick() {
    let (status, started) = STATE.with(|s| {
        let s = s.borrow();
        (text(&s.dashboard["status"]), s.started_at)
    });
    let uptime = if status == "Started" && started > 0 { format::duration(now_ms() - started) } else { String::new() };
    with_window(|w| w.set_uptime(uptime.into()));
}

fn load(window: &AppWindow) {
    window.set_startup_error(SharedString::new());
    call("attach", json!({}), |_| {});
    call("ready", json!({}), |result| match result {
        Ok(value) => {
            let versions = format!(
                "FK-box {} · sing-box {} · Xray {} · mihomo {}",
                text(&value["version"]),
                text(&value["coreVersion"]),
                text(&value["xrayVersion"]),
                text(&value["mihomoVersion"])
            );
            with_window(|w| w.set_versions(versions.into()));
            let mut value = value;
            set_profiles(value.get_mut("profiles").map(Value::take).unwrap_or(Value::Null));
            set_dashboard(value.get_mut("dashboard").map(Value::take).unwrap_or(Value::Null));
            with_window(|w| w.set_ready(true));
        }
        Err(error) => with_window(|w| w.set_startup_error(error.into())),
    });
}

pub fn run() -> Result<(), slint::PlatformError> {
    let window = AppWindow::new()?;
    let logs_model = Rc::new(VecModel::<LogRow>::default());
    window.set_logs(ModelRc::from(logs_model.clone()));
    STATE.with(|s| {
        let mut s = s.borrow_mut();
        s.window = window.as_weak();
        s.logs_model = Some(logs_model);
    });

    window.on_retry(|| with_window(load));
    window.on_dismiss_toast(|| with_window(|w| w.set_toast(SharedString::new())));
    window.on_tab_changed(|tab| act("setLogsVisible", json!({ "visible": tab == 3 })));
    window.on_toggle_service(|| {
        let status = STATE.with(|s| text(&s.borrow().dashboard["status"]));
        match status.as_str() {
            "Started" => act("stopService", json!({})),
            "Stopped" | "" => act("startService", json!({})),
            _ => {}
        }
    });
    window.on_set_mode(|mode| act("setMode", json!({ "mode": mode.as_str() })));
    window.on_select_profile(|id| {
        if let Ok(id) = id.parse::<i64>() {
            act("selectProfile", json!({ "id": id }));
        }
    });
    window.on_update_profile(|id| {
        if let Ok(id) = id.parse::<i64>() {
            toast("正在更新订阅…");
            call("updateProfile", json!({ "id": id }), |result| match result {
                Ok(_) => toast("订阅已更新"),
                Err(error) => toast(&error),
            });
        }
    });
    window.on_delete_profile(|id| {
        if let Ok(id) = id.parse::<i64>() {
            act("deleteProfile", json!({ "id": id }));
        }
    });
    window.on_add_profile(|name, url| {
        let url = url.trim().to_string();
        if !(url.starts_with("http://") || url.starts_with("https://")) {
            toast("请输入 http(s) 订阅链接");
            return;
        }
        let name = name.trim().to_string();
        let name = if name.is_empty() { "订阅".to_string() } else { name };
        let profile = json!({ "name": name, "url": url, "autoUpdate": true, "interval": 60, "core": "auto" });
        call("saveProfile", json!({ "profile": profile }), |result| match result {
            Ok(_) => {
                with_window(|w| w.set_adding(false));
                toast("订阅已添加");
            }
            Err(error) => toast(&error),
        });
    });
    window.on_toggle_group(|tag| {
        let tag = tag.to_string();
        STATE.with(|s| {
            let mut s = s.borrow_mut();
            let current = s
                .expanded
                .get(&tag)
                .copied()
                .or_else(|| s.groups.iter().find(|g| text(&g["tag"]) == tag).and_then(|g| g["expanded"].as_bool()))
                .unwrap_or(false);
            s.expanded.insert(tag, !current);
        });
        refresh_groups();
    });
    window.on_test_group(|tag| {
        let tag = tag.to_string();
        STATE.with(|s| s.borrow_mut().testing.insert(tag.clone()));
        refresh_groups();
        let done = tag.clone();
        call("urlTest", json!({ "tag": tag }), move |result| {
            STATE.with(|s| s.borrow_mut().testing.remove(&done));
            refresh_groups();
            if let Err(error) = result {
                toast(&error);
            }
        });
    });
    window.on_select_node(|group, node| act("selectOutbound", json!({ "group": group.as_str(), "tag": node.as_str() })));
    window.on_clear_logs(|| {
        STATE.with(|s| {
            let mut s = s.borrow_mut();
            s.logs.clear();
            if let Some(model) = &s.logs_model {
                model.set_vec(Vec::new());
            }
        });
        act("clearLogs", json!({}));
    });

    let clock = Timer::default();
    clock.start(TimerMode::Repeated, Duration::from_secs(1), tick);

    load(&window);
    window.run()
}
