//! The dashboard (pages/DashboardPage.kt, StatusPanel.kt, DashboardEditor.kt):
//! the cards the preferences order and hide, each panel's values, the
//! editor, and the memory, outbound IP, intranet and new LAN proxy pages.

use std::cell::RefCell;

use serde_json::{json, Value};
use slint::{ComponentHandle, SharedString};

use crate::app::{self, data, long, num, rows, ss, strings, text, truthy};
use crate::format;
use crate::i18n::tr;
use crate::nav::{self, glyph, MenuEntry};
use crate::pages::{egress, mark, Mark};
use crate::{AppWindow, Dash, KeyValue, ModeItem, Note};

pub const CARDS: [&str; 8] = ["Status", "Network speed", "Outbound mode", "Current profile", "Proxy groups", "Memory", "Intranet IP", "System HTTP proxy"];
const DEFAULT_CARDS: [&str; 5] = ["Status", "Network speed", "Outbound mode", "Current profile", "Proxy groups"];
const DEFAULT_HIDDEN: [&str; 2] = ["Memory", "Intranet IP"];

#[derive(Default)]
struct Local {
    editing: Option<Vec<String>>,
    saving: bool,
    updating: bool,
    releasing: bool,
    lan_port: String,
    lan_port_invalid: bool,
    lan_target: Option<(i64, String)>,
}

thread_local! {
    static LOCAL: RefCell<Local> = RefCell::new(Local { lan_port: "2080".into(), ..Default::default() });
}

fn local<R>(f: impl FnOnce(&mut Local) -> R) -> R {
    LOCAL.with(|l| f(&mut l.borrow_mut()))
}

/// dashboardOrder: the saved order, then the cards it does not name.
fn order() -> Vec<String> {
    data(|d| {
        let saved = d.preferences.get("dashboardCards").map(strings).unwrap_or_else(|| DEFAULT_CARDS.iter().map(|s| s.to_string()).collect());
        let mut out: Vec<String> = Vec::new();
        for card in saved.into_iter().filter(|c| CARDS.contains(&c.as_str())).chain(CARDS.iter().map(|s| s.to_string())) {
            if !out.contains(&card) {
                out.push(card);
            }
        }
        out
    })
}

fn hidden() -> Vec<String> {
    data(|d| d.preferences.get("hiddenCards").map(strings).unwrap_or_else(|| DEFAULT_HIDDEN.iter().map(|s| s.to_string()).collect()))
}

/// The cards this device can show.
fn available() -> Vec<String> {
    data(|d| {
        let modes = d.dashboard.get("modes").and_then(|m| m.as_array()).map(|m| !m.is_empty()).unwrap_or(false);
        let system_proxy = d.dashboard.get("systemProxyAvailable").map(truthy).unwrap_or(false);
        CARDS
            .iter()
            .filter(|c| match **c {
                "Outbound mode" => modes,
                "System HTTP proxy" => system_proxy,
                _ => true,
            })
            .map(|s| s.to_string())
            .collect()
    })
}

pub fn shown_cards() -> Vec<String> {
    let available = available();
    let hidden = hidden();
    order().into_iter().filter(|c| available.contains(c) && (c == "Status" || !hidden.contains(c))).collect()
}

fn card_label(card: &str) -> String {
    tr(card)
}

/// saveLayout: cards this device cannot show keep their place.
fn save_layout(next: Vec<String>) {
    let available = available();
    let order = order();
    let mut result = next.clone();
    let mut hidden_set: Vec<String> = hidden().into_iter().filter(|c| CARDS.contains(&c.as_str())).collect();
    let mut at = 0;
    for card in &order {
        if available.contains(card) {
            if let Some(index) = result.iter().position(|c| c == card) {
                at = index + 1;
            }
        } else {
            result.insert(at.min(result.len()), card.clone());
            at += 1;
        }
    }
    for card in &order {
        if !result.contains(card) {
            result.push(card.clone());
        }
    }
    for card in &available {
        if next.contains(card) {
            hidden_set.retain(|c| c != card);
        } else if !hidden_set.contains(card) {
            hidden_set.push(card.clone());
        }
    }
    hidden_set.retain(|c| c != "Status");
    local(|l| l.saving = true);
    mark(Mark::DASH);
    app::set_preferences(json!({ "dashboardCards": result, "hiddenCards": hidden_set }), |_| {
        local(|l| l.saving = false);
        mark(Mark::DASH);
    });
}

fn kv(key: impl AsRef<str>, value: impl AsRef<str>) -> KeyValue {
    KeyValue { key: ss(key), value: ss(value) }
}

/// Sets a model property in place (rows::update): rows that did not change keep
/// their item instances, so a projection every second rebuilds nothing.
macro_rules! sync {
    ($dash:expr, $get:ident, $set:ident, $value:expr $(,)?) => {{
        let next: Vec<_> = $value;
        if let Some(model) = crate::rows::update($dash.$get(), next) {
            $dash.$set(model);
        }
    }};
}

/// LineChart (ui/LineChart.kt): the last `CAPACITY` samples right-aligned in a
/// zero-padded window, Gaussian smoothed (sigma 1.5), drawn as monotone cubic
/// segments one step apart, the height scaled to the larger of the peak and
/// 8 KiB with the baseline at the bottom; in the chart's own logical px.
fn chart(values: &[f64], width: f64, height: f64) -> (String, String) {
    const CAPACITY: usize = 30;
    const MIN_SCALE: f64 = 8.0 * 1024.0;
    const SIGMA: f64 = 1.5;
    const STROKE: f64 = 2.0;
    if width <= 1.0 || height <= 1.0 {
        return (String::new(), String::new());
    }
    let radius = (SIGMA * 2.5).ceil() as usize;
    let kernel: Vec<f64> = (0..=radius).map(|d| (-((d * d) as f64) / (2.0 * SIGMA * SIGMA)).exp()).collect();
    let length = CAPACITY + radius + 1;
    let mut series = vec![0.0; length];
    let count = values.len().min(length);
    series[length - count..].copy_from_slice(&values[values.len() - count..]);
    let heights: Vec<f64> = (0..length)
        .map(|i| {
            let (mut sum, mut weight) = (0.0, 0.0);
            for j in i.saturating_sub(radius)..=(i + radius).min(length - 1) {
                let w = kernel[i.abs_diff(j)];
                sum += w * series[j];
                weight += w;
            }
            sum / weight
        })
        .collect();
    let last = length - 1;
    let slope: Vec<f64> = (0..length)
        .map(|i| if i == 0 || i == last { 0.0 } else { ((heights[i + 1] - heights[i - 1]) / 2.0).clamp(-3.0 * heights[i], 3.0 * heights[i]) })
        .collect();
    let step = width / (CAPACITY - 1) as f64;
    let first_x = width - last as f64 * step;
    // The curve's highest point, control points included (Path.getBounds()).
    let mut peak = heights.iter().cloned().fold(0.0, f64::max);
    for i in 1..length {
        peak = peak.max(heights[i - 1] + slope[i - 1] / 3.0).max(heights[i] - slope[i] / 3.0);
    }
    let top = peak.max(MIN_SCALE);
    let scale = (height - STROKE) / top;
    let y = |v: f64| height - v * scale;
    let mut line = format!("M {:.2} {:.2}", first_x, y(heights[0]));
    let mut x = first_x;
    for i in 1..length {
        let next = x + step;
        line.push_str(&format!(
            " C {:.2} {:.2} {:.2} {:.2} {:.2} {:.2}",
            x + step / 3.0,
            y(heights[i - 1] + slope[i - 1] / 3.0),
            next - step / 3.0,
            y(heights[i] - slope[i] / 3.0),
            next,
            y(heights[i])
        ));
        x = next;
    }
    let area = format!("{line} L {:.2} {:.2} L {:.2} {:.2} Z", x, height, first_x, height);
    (line, area)
}

/// Re-draws both charts at the current size.
fn project_charts(dash: &Dash) {
    let (w, h) = (dash.get_chart_w() as f64, dash.get_chart_h() as f64);
    data(|d| {
        let up: Vec<f64> = d.upload_history.iter().cloned().collect();
        let down: Vec<f64> = d.download_history.iter().cloned().collect();
        let (ul, ua) = chart(&up, w, h);
        let (dl, da) = chart(&down, w, h);
        dash.set_up_line(ul.into());
        dash.set_up_area(ua.into());
        dash.set_down_line(dl.into());
        dash.set_down_area(da.into());
    });
}

pub struct SubInfo {
    pub upload: i64,
    pub download: i64,
    pub total: i64,
    pub expire: i64,
}

impl SubInfo {
    pub fn of(profile: &crate::app::Json) -> Option<SubInfo> {
        if !profile.get("remote").map(truthy).unwrap_or(false) {
            return None;
        }
        let g = |k: &str| profile.get(k).and_then(long).unwrap_or(0);
        let info = SubInfo { upload: g("upload"), download: g("download"), total: g("total"), expire: g("expire") };
        if info.upload == 0 && info.download == 0 && info.total == 0 && info.expire == 0 {
            None
        } else {
            Some(info)
        }
    }

    pub fn used(&self) -> i64 {
        self.upload + self.download
    }

    pub fn remaining(&self) -> i64 {
        (self.total - self.used()).max(0)
    }

    pub fn usage(&self) -> Option<f64> {
        if self.total > 0 {
            Some(self.used() as f64 / self.total as f64)
        } else {
            None
        }
    }

    pub fn expire_ms(&self) -> Option<i64> {
        if self.expire > 0 {
            Some(self.expire.saturating_mul(1000))
        } else {
            None
        }
    }

    pub fn expire_date(&self) -> Option<String> {
        self.expire_ms().map(|ms| format::local_date(ms).chars().take(10).collect())
    }

    /// 0 normal, 1 warning, 2 error.
    pub fn tone(&self, now: i64) -> i32 {
        let usage = self.usage();
        let expire = self.expire_ms();
        if usage.map(|u| u >= 1.0).unwrap_or(false) || expire.map(|e| e <= now).unwrap_or(false) {
            2
        } else if usage.map(|u| u >= 0.9).unwrap_or(false) || expire.map(|e| (e - now) / 86_400_000 < 7).unwrap_or(false) {
            1
        } else {
            0
        }
    }

    pub fn days_text(&self, now: i64) -> String {
        match self.expire_ms() {
            None => tr("No expiry"),
            Some(e) if e <= now => tr("Expired"),
            Some(e) => tr("%d days left").replace("%d", &((e - now) / 86_400_000).max(0).to_string()),
        }
    }

    /// The dashboard's end label and its tone.
    pub fn days_label(&self, now: i64) -> Option<(String, i32)> {
        let e = self.expire_ms()?;
        let tone = if e <= now {
            2
        } else if (e - now) / 86_400_000 < 7 {
            1
        } else {
            0
        };
        Some((self.days_text(now), tone))
    }
}

pub fn percent(share: f64) -> String {
    format!("{}%", ((share * 100.0) as i64).clamp(0, 100))
}

pub fn lan_subtitle() -> String {
    data(|d| {
        if d.selected_profile_id() == -1 {
            return tr("No profile");
        }
        if d.lan.get("error").map(|e| !e.is_null()).unwrap_or(false) {
            return tr("Unavailable");
        }
        let active = d.running() && d.lan.get("running").map(truthy).unwrap_or(false);
        let bindings = d.lan.get(if active { "activeBindings" } else { "bindings" }).map(rows).unwrap_or_default();
        if bindings.is_empty() {
            return tr("Add proxy listener");
        }
        if !d.lan.get("enabled").map(truthy).unwrap_or(false) {
            return tr("Disabled");
        }
        let mut ports: Vec<i64> = Vec::new();
        for b in bindings.iter().filter(|b| truthy(&b["lan"])) {
            let p = long(&b["port"]).unwrap_or(0);
            if !ports.contains(&p) {
                ports.push(p);
            }
        }
        let ports = format!(
            "{}: {}",
            tr("Port"),
            ports.iter().map(|p| if *p == 0 { tr("Automatic port") } else { p.to_string() }).collect::<Vec<_>>().join(", ")
        );
        if !active {
            return format!("{ports} · {}", tr("Not running"));
        }
        if d.lan_proxy_endpoints().is_empty() {
            return format!("{ports} · {}", tr("Unavailable"));
        }
        ports
    })
}

fn protocol(binding: &Value) -> String {
    match binding["type"].as_str() {
        Some("mixed") => tr(if truthy(&binding["tls"]) { "HTTP / SOCKS over TLS" } else { "HTTP / SOCKS" }),
        Some("http") => if truthy(&binding["tls"]) { "HTTPS" } else { "HTTP" }.into(),
        _ => "SOCKS".into(),
    }
}

/// changeLan
fn change_lan(enabled: bool) {
    if !data(|d| d.can_set_lan_proxy()) {
        return;
    }
    let empty = data(|d| d.lan.get("bindings").map(rows).unwrap_or_default().is_empty());
    if enabled && empty {
        let target = data(|d| (d.selected_profile_id(), d.lan.get("revision").map(text).unwrap_or_default()));
        local(|l| {
            l.lan_target = Some(target);
            l.lan_port = "2080".into();
            l.lan_port_invalid = false;
        });
        nav::form("lan-add", "");
    } else {
        app::set_lan_proxy(enabled, None, None, None, |_| {});
    }
}

pub fn project(w: &AppWindow, m: Mark) {
    let dash = w.global::<Dash>();
    let now = format::now_ms();
    if m.has(Mark::RATES) || m == Mark::ALL {
        data(|d| {
            let (uf, uu) = format::bytes_parts(d.rates.get("upload").map(num).unwrap_or(0.0));
            let (df, du) = format::bytes_parts(d.rates.get("download").map(num).unwrap_or(0.0));
            dash.set_up_figure(uf.into());
            dash.set_up_unit(uu.into());
            dash.set_down_figure(df.into());
            dash.set_down_unit(du.into());
            dash.set_up_total(d.rates.get("uploadTotal").map(text).filter(|s| !s.is_empty()).unwrap_or_else(|| "0 B".into()).into());
            dash.set_down_total(d.rates.get("downloadTotal").map(text).filter(|s| !s.is_empty()).unwrap_or_else(|| "0 B".into()).into());
        });
        project_charts(&dash);
    }
    if m.has(Mark::DASH) || m.has(Mark::PROXIES) || m.has(Mark::PROFILES) || m == Mark::ALL {
        egress::sync();
    }
    // The status panel.
    let view = egress::view();
    let info = view.egress.clone().unwrap_or_default();
    let ip = info.get("ip").and_then(|v| v.as_str()).map(str::to_string);
    data(|d| {
        let status = if d.transitioning() {
            d.status()
        } else if d.running() {
            "Running".into()
        } else {
            "Not running".into()
        };
        dash.set_status_label(tr(&status).into());
        dash.set_start_enabled((d.running() || d.selected_profile().is_some()) && !view.busy && !d.transitioning());
        dash.set_start_busy(view.busy || d.transitioning());
        dash.set_egress_ip(ip.clone().unwrap_or_default().into());
        dash.set_egress_country(info.get("country").map(text).unwrap_or_default().to_uppercase().into());
        dash.set_egress_loading(view.ip_loading || view.checking);
        dash.set_egress_skeleton(view.ip_loading && ip.is_none());
        dash.set_egress_failed(view.error);
        dash.set_egress_can_retry(!d.transitioning() && !d.remote() && !view.checking);
        dash.set_egress_type(tr(info.get("type").and_then(|v| v.as_str()).unwrap_or("Unknown type")).into());
        let quality = info.get("quality").and_then(|v| v.as_str()).unwrap_or("");
        dash.set_egress_quality(if quality.is_empty() { SharedString::new() } else { tr(quality).into() });
        dash.set_egress_quality_tone(match quality {
            "Good" => 1,
            "Risky" => 2,
            _ => 0,
        });
        let organization = info.get("organization").and_then(|v| v.as_str()).filter(|s| !s.is_empty()).map(str::to_string).or_else(|| info.get("asn").and_then(|v| v.as_str()).map(str::to_string));
        dash.set_organization(organization.unwrap_or_default().into());
        dash.set_organization_loading(view.loading);
        let started = if d.running() { d.dashboard.get("startedAt").and_then(long) } else { None };
        dash.set_runtime(
            match started {
                None => "—".to_string(),
                Some(s) => {
                    let secs = ((now - s) / 1000).clamp(0, 999 * 3600 + 59 * 60 + 59);
                    format!("{:02}:{:02}:{:02}", secs / 3600, secs / 60 % 60, secs % 60)
                }
            }
            .into(),
        );
    });
    // The outbound IP details.
    {
        let mut rows_out = vec![
            kv(tr("IP"), info.get("ip").map(text).unwrap_or_default()),
            kv(tr("Country"), info.get("country").map(text).unwrap_or_default()),
            kv(tr("Organization"), info.get("organization").map(text).unwrap_or_default()),
            kv(tr("ASN"), info.get("asn").map(text).unwrap_or_default()),
            kv(tr("Type"), tr(info.get("type").and_then(|v| v.as_str()).unwrap_or("Unknown type"))),
        ];
        if let Some(q) = info.get("quality").and_then(|v| v.as_str()) {
            rows_out.push(kv(tr("Quality"), tr(q)));
        }
        rows_out.push(kv(tr("IP query source"), info.get("ipSource").map(text).unwrap_or_default()));
        rows_out.push(kv(tr("Quality query source"), info.get("source").map(text).unwrap_or_default()));
        let checked = info.get("qualityCheckedAt").or_else(|| info.get("checkedAt")).and_then(long).unwrap_or(0);
        rows_out.push(kv(tr("Last checked"), if checked > 0 { format::local_date(checked) } else { "—".into() }));
        if info.get("qualityError").map(|v| !v.is_null()).unwrap_or(false) {
            for f in info.get("qualityFailures").map(rows).unwrap_or_default() {
                let label = match f["status"].as_str() {
                    Some("noType") => "No type data",
                    Some("timeout") => "Timeout",
                    Some("rateLimited") => "Rate limited",
                    Some("ipMismatch") => "IP mismatch",
                    _ => "Unavailable",
                };
                rows_out.push(kv(format!("{} · {}", tr("IP quality unavailable"), text(&f["source"])), tr(label)));
            }
        }
        sync!(dash, get_egress_rows, set_egress_rows, rows_out);
    }
    if m.has(Mark::RATES) && m.0 == Mark::RATES.0 {
        return;
    }

    let (editing, saving, updating, releasing) = local(|l| (l.editing.clone(), l.saving, l.updating, l.releasing));
    let available_cards = available();
    sync!(dash, get_cards, set_cards, shown_cards().iter().map(ss).collect());
    sync!(dash, get_available, set_available, available_cards.iter().map(ss).collect());
    dash.set_editing(editing.is_some());
    dash.set_saving(saving);
    if let Some(shown) = &editing {
        sync!(dash, get_edit_shown, set_edit_shown, shown.iter().map(|c| kv(c, card_label(c))).collect());
        sync!(dash, get_edit_hidden, set_edit_hidden, available_cards.iter().filter(|c| !shown.contains(c)).map(|c| kv(c, card_label(c))).collect());
    }
    data(|d| {
        dash.set_apply_required(d.apply_required.is_some());
        sync!(dash, get_deprecated, set_deprecated, 
            d.dashboard
                .get("deprecated")
                .map(rows)
                .unwrap_or_default()
                .iter()
                .map(|n| Note { message: ss(text(&n["message"])), url: ss(text(&n["url"])) })
                .collect()
        );

        // Outbound mode
        let modes: Vec<ModeItem> = d
            .dashboard
            .get("modes")
            .map(strings)
            .unwrap_or_default()
            .iter()
            .map(|mode| {
                let key = mode.to_lowercase();
                let (label, icon) = match key.as_str() {
                    "rule" => ("Rule", "rules"),
                    "global" => ("Global", "proxies"),
                    "direct" => ("Direct", "target"),
                    _ => (mode.as_str(), "split"),
                };
                ModeItem { key: ss(&key), mode: ss(mode), label: ss(tr(label)), glyph: glyph(icon) }
            })
            .collect();
        sync!(dash, get_modes, set_modes, modes);
        dash.set_mode(d.dashboard.get("mode").map(text).unwrap_or_default().to_lowercase().into());

        // Current profile
        let profile = if d.remote() { None } else { d.selected_profile() };
        dash.set_has_profile(profile.is_some());
        dash.set_profile_updating(updating);
        match &profile {
            Some(p) => {
                dash.set_profile_name(text(&p["name"]).into());
                let core = p.get("resolvedCore").map(text).filter(|s| !s.is_empty()).unwrap_or_else(|| p.get("core").map(text).unwrap_or_default());
                dash.set_profile_core(crate::pages::profiles::core_chip(&core).into());
                dash.set_profile_remote(p.get("remote").map(truthy).unwrap_or(false));
                dash.set_profile_updated(
                    format!("{} {}", tr("Last updated"), format::update_label(p.get("updatedAt").and_then(long).unwrap_or(0), now, crate::i18n::is_chinese())).into(),
                );
                let info = SubInfo::of(p);
                let (days, tone) = info.as_ref().and_then(|i| i.days_label(now)).unwrap_or_default();
                dash.set_profile_days(days.into());
                dash.set_profile_days_tone(tone);
                dash.set_profile_source(tr(if p.get("remote").map(truthy).unwrap_or(false) { "Remote" } else { "Local" }).into());
                match info.as_ref().and_then(|i| i.usage().map(|u| (i, u))) {
                    Some((i, usage)) => {
                        dash.set_profile_usage(true);
                        dash.set_usage_used(format::bytes(i.used() as f64).into());
                        dash.set_usage_total(format::bytes(i.total as f64).into());
                        dash.set_usage_ratio(usage as f32);
                        dash.set_usage_percent(percent(usage).into());
                    }
                    None => dash.set_profile_usage(false),
                }
                let id = long(&p["id"]).unwrap_or(-1);
                let stats = d.profile_stats.get(&id).cloned();
                match stats {
                    Some(Some(s)) => {
                        dash.set_stats_loading(false);
                        let f = |k: &str| s.get(k).map(text).filter(|t| !t.is_empty()).unwrap_or_else(|| "—".into());
                        dash.set_stat_groups(f("groups").into());
                        dash.set_stat_outbounds(f("outbounds").into());
                        dash.set_stat_rules(f("rules").into());
                    }
                    _ => {
                        dash.set_stats_loading(true);
                    }
                }
            }
            None => {
                dash.set_profile_name(SharedString::new());
                dash.set_profile_core(SharedString::new());
                dash.set_profile_remote(false);
                dash.set_profile_usage(false);
                dash.set_profile_days(SharedString::new());
                dash.set_profile_updated(SharedString::new());
                dash.set_profile_source(tr("Remote").into());
                dash.set_stats_loading(false);
                for f in [Dash::set_stat_groups, Dash::set_stat_outbounds, Dash::set_stat_rules] {
                    f(&dash, "—".into());
                }
            }
        }

        // Proxy groups
        let groups = &d.groups;
        let timeout = {
            let mut tags: Vec<String> = Vec::new();
            for g in groups {
                for n in rows(&g["items"]) {
                    if long(&n["testedAt"]).unwrap_or(0) > 0 && long(&n["delay"]).unwrap_or(0) <= 0 {
                        let t = text(&n["tag"]);
                        if !tags.contains(&t) {
                            tags.push(t);
                        }
                    }
                }
            }
            tags.len()
        };
        dash.set_group_count(groups.len() as i32);
        dash.set_timeout_count(timeout as i32);
        sync!(dash, get_group_letters, set_group_letters, groups.iter().take(4).map(|g| ss(format::letters(&text(&g["tag"])))).collect());
        dash.set_group_rest(groups.len().saturating_sub(4) as i32);

        // Memory
        let mem = &d.memory;
        let remote = mem.get("remote").map(truthy).unwrap_or(false);
        dash.set_memory_empty(mem.is_empty());
        dash.set_memory_remote(remote);
        dash.set_memory_running(mem.get("running").map(truthy).unwrap_or(false));
        let (figure, unit) = format::bytes_parts(mem.get("total").map(num).unwrap_or(0.0));
        dash.set_memory_figure(if mem.is_empty() { "—".into() } else { figure.into() });
        dash.set_memory_unit(unit.into());
        let app_bytes = mem.get("app").map(num).unwrap_or(0.0);
        let core_bytes = mem.get("core").map(num).unwrap_or(0.0);
        dash.set_memory_app(if mem.is_empty() { "—".into() } else { format::bytes(app_bytes).into() });
        dash.set_memory_core(if mem.is_empty() { "—".into() } else { format::bytes(core_bytes).into() });
        let total = (if remote { 0.0 } else { app_bytes.max(0.0) }) + core_bytes.max(0.0);
        let weight = |v: f64| if total <= 0.0 || v <= 0.0 { 0.0 } else { ((v * 100.0 / total).round() * 100.0).max(1.0) as f32 };
        dash.set_memory_app_weight(if remote { 0.0 } else { weight(app_bytes) });
        dash.set_memory_core_weight(weight(core_bytes));
        sync!(dash, get_memory_app_rows, set_memory_app_rows, vec![
            kv(tr("Process RSS"), format::bytes(mem.get("rss").map(num).unwrap_or(0.0))),
            kv(tr("App and shared memory"), format::bytes(app_bytes)),
        ]);
        let mut core_rows = Vec::new();
        let mut percents = Vec::new();
        if !mem.get("running").map(truthy).unwrap_or(false) && !remote {
            core_rows.push(kv(tr("Not running"), ""));
            percents.push(SharedString::new());
        }
        for (key, label) in [("heapInuse", "Heap in use"), ("heapIdle", "Retained idle heap"), ("stack", "Stack"), ("runtimeOther", "Other runtime memory")] {
            if let Some(v) = mem.get(key).and_then(|v| v.as_f64()) {
                core_rows.push(kv(tr(label), format::bytes(v)));
                percents.push(ss(format!("{}%", if core_bytes == 0.0 { "0.0".into() } else { format!("{:.1}", v * 100.0 / core_bytes) })));
            }
        }
        if let Some(g) = mem.get("goroutines").filter(|v| !v.is_null()) {
            core_rows.push(kv(tr("Goroutines"), text(g)));
            percents.push(SharedString::new());
        }
        sync!(dash, get_memory_core_rows, set_memory_core_rows, core_rows);
        sync!(dash, get_memory_core_percents, set_memory_core_percents, percents);
        dash.set_releasing(releasing);

        // Intranet
        let addresses = d.lan_addresses();
        dash.set_lan_loading(!d.lan.get("addresses").map(|a| a.is_array()).unwrap_or(false));
        dash.set_lan_address(addresses.first().cloned().unwrap_or_default().into());
        sync!(dash, get_lan_addresses, set_lan_addresses, addresses.iter().map(ss).collect());
        dash.set_lan_enabled(d.lan.get("enabled").map(truthy).unwrap_or(false));
        dash.set_lan_can_toggle(d.can_set_lan_proxy());
        dash.set_lan_can_copy(d.lan_copy_text().is_some());
        dash.set_lan_changing(d.lan_changing);
        dash.set_lan_error(d.lan.get("error").filter(|e| !e.is_null()).map(text).unwrap_or_default().into());
        let active = !d.remote() && d.running() && d.lan.get("running").map(truthy).unwrap_or(false);
        dash.set_lan_active(active);
        let bindings = d.lan.get(if active { "activeBindings" } else { "bindings" }).map(rows).unwrap_or_default();
        let mut listeners = Vec::new();
        if !active {
            listeners.push(kv(tr("Not running"), ""));
        }
        for b in &bindings {
            let tag = b["tag"].as_str().filter(|s| !s.is_empty()).map(str::to_string).unwrap_or_else(|| protocol(b));
            let port = long(&b["port"]).unwrap_or(0);
            let mut subtitle = format!("{} · {}", protocol(b), if port == 0 { tr("Automatic port") } else { format!("{}: {port}", tr("Port")) });
            if truthy(&b["authentication"]) {
                subtitle.push_str(&format!(" · {}", tr("Authentication required")));
            }
            listeners.push(kv(tag, subtitle));
            if active {
                for e in strings(&b["endpoints"]) {
                    listeners.push(kv(e, "copy"));
                }
            }
        }
        if bindings.is_empty() {
            listeners.push(kv(tr("No local proxy listener"), ""));
        }
        sync!(dash, get_lan_listeners, set_lan_listeners, listeners);

        dash.set_system_proxy(d.dashboard.get("systemProxy").map(truthy).unwrap_or(false));
    });
    dash.set_lan_subtitle(lan_subtitle().into());
    let (port, invalid) = local(|l| (l.lan_port.clone(), l.lan_port_invalid));
    if dash.get_lan_port().as_str() != port {
        dash.set_lan_port(port.into());
    }
    dash.set_lan_port_invalid(invalid);

    // The selected profile's stats (ProfileStats).
    if let Some(id) = data(|d| if d.remote() { None } else { d.selected_profile().and_then(|p| long(&p["id"])) }) {
        app::profile_stats(id, || mark(Mark::DASH));
    }
}

pub fn opened(kind: &str, _arg: &str, id: i32) {
    if kind == "lan-add" {
        nav::busy_guard(id, || data(|d| d.lan_changing));
    }
}

pub fn back() -> bool {
    if local(|l| l.editing.is_some()) && nav::top().is_none() && nav::tab() == "Dashboard" {
        local(|l| l.editing = None);
        mark(Mark::DASH);
        return true;
    }
    false
}

pub fn act(id: &str, arg: &str) -> bool {
    match id {
        "toggle-service" => egress::toggle_service(),
        "egress-retry" => egress::retry(),
        "egress-copy" => {
            if let Some(ip) = egress::view().egress.and_then(|e| e.get("ip").map(text)) {
                app::copy_text(&ip);
                app::message("Copied");
            }
        }
        "set-mode" => app::act("setMode", json!({ "mode": arg })),
        "profile-panel" => {
            let (remote, profile) = data(|d| (d.remote(), d.selected_profile()));
            if remote {
                nav::form("profile-details", "remote");
            } else if let Some(p) = profile {
                nav::form("profile-details", &text(&p["id"]));
            } else {
                nav::form("profile-add", "");
            }
        }
        "update-selected-profile" => {
            if let Some(id) = data(|d| d.selected_profile().and_then(|p| long(&p["id"]))) {
                local(|l| l.updating = true);
                mark(Mark::DASH);
                app::act_then("updateProfile", json!({ "id": id }), |_| {
                    local(|l| l.updating = false);
                    mark(Mark::DASH);
                });
            }
        }
        "memory-release" => {
            local(|l| l.releasing = true);
            mark(Mark::DASH);
            app::call("releaseMemory", json!({}), |r| {
                local(|l| l.releasing = false);
                match r {
                    Ok(v) => {
                        if v.is_object() {
                            app::data_mut(|d| d.memory = crate::app::obj(&v));
                        }
                        app::message("Memory released");
                    }
                    Err(e) => app::report(&e),
                }
                mark(Mark::DASH);
            });
        }
        "lan-toggle" => change_lan(arg == "1"),
        "lan-copy" => {
            if let Some(t) = data(|d| d.lan_copy_text()) {
                app::copy_text(&t);
                app::message("Copied");
            }
        }
        "lan-refresh" => app::refresh_lan(),
        "lan-port-edited" => {
            local(|l| l.lan_port_invalid = false);
            mark(Mark::DASH);
        }
        "lan-add-save" => {
            local(|l| l.lan_port = arg.to_string());
            match arg.trim().parse::<i64>().ok().filter(|p| (1..=65535).contains(p)) {
                None => {
                    local(|l| l.lan_port_invalid = true);
                    mark(Mark::DASH);
                }
                Some(port) => {
                    let target = local(|l| l.lan_target.clone());
                    let (profile, revision) = target.map(|(p, r)| (Some(p), Some(r))).unwrap_or((None, None));
                    app::set_lan_proxy(true, Some(port), profile, revision, |ok| {
                        if ok {
                            nav::close_kind("lan-add");
                        }
                    });
                }
            }
        }
        "system-proxy" => app::act("setSystemProxy", json!({ "enabled": arg == "1" })),
        "remote-disconnect" => app::act("disconnectRemoteServer", json!({})),
        "apply-restart" => app::act_then("reloadService", json!({ "restart": true }), |ok| {
            if ok {
                app::clear_apply_required();
            }
        }),
        "dash-edit" => {
            local(|l| l.editing = Some(shown_cards()));
            mark(Mark::DASH);
        }
        "dash-done" => {
            local(|l| l.editing = None);
            mark(Mark::DASH);
        }
        _ => return false,
    }
    true
}

pub fn act2(id: &str, a: &str, b: &str) -> bool {
    let next = local(|l| {
        let list = l.editing.as_mut()?;
        match id {
            "dash-move" => {
                let i = list.iter().position(|c| c == a)?;
                let j = (i as i64 + b.parse::<i64>().ok()?).clamp(0, list.len() as i64 - 1) as usize;
                let card = list.remove(i);
                list.insert(j, card);
            }
            "dash-hide" if a != "Status" => list.retain(|c| c != a),
            "dash-show" if !list.iter().any(|c| c == a) => list.push(a.to_string()),
            _ => return None,
        }
        Some(list.clone())
    });
    match next {
        Some(list) => {
            save_layout(list);
            true
        }
        None => matches!(id, "dash-move" | "dash-hide" | "dash-show"),
    }
}

pub fn menu(_key: &str) -> Vec<MenuEntry> {
    Vec::new()
}

pub fn install(w: &AppWindow) {
    let weak = w.as_weak();
    w.global::<Dash>().on_chart_resized(move || {
        if let Some(w) = weak.upgrade() {
            project_charts(&w.global::<Dash>());
        }
    });
}
