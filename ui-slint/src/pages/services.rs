//! Service status, after FlClash v0.9.3 (GPL-3.0): the dashboard card
//! (lib/views/dashboard/widgets/service_status.dart ServiceStatusCard), its
//! sheet with every service (ServiceStatusSheet) and the service manager
//! (ServiceManageView), with FlClash's thirteen targets and rules
//! (core/service_check.go, core/service_rules.go, ported to
//! libbox/ui_service_check.go and run through the dashboard's egress probe:
//! the running core's routing, or the device network while it is stopped).
//!
//! The card shows the first four enabled services (FlClash: at most what the
//! core checks at once) and keeps them fresh while it is on screen and the
//! app is in the foreground: once when it appears, after a route change has
//! settled and every two minutes. Nothing runs in the background; coming back
//! checks once what went stale. The service status page lists every enabled
//! service; it checks the ones never checked when it opens, a row checks its
//! service again and the bar checks all. Checks run at most four at a time.

use std::cell::RefCell;
use std::collections::VecDeque;
use std::time::Duration;

use serde_json::{json, Value};
use slint::{Timer, TimerMode};

use crate::app::{call, data, long, text};
use crate::i18n::tr;
use crate::pages::{mark, Mark};

/// FlClash ServiceTarget: (id, label, icon). The id is the rule's name in
/// libbox/ui_service_check.go; the icon is a mark in service_icons.rs.
pub const TARGETS: [(&str, &str, &str); 13] = [
    ("google", "Google", "google"),
    ("github", "GitHub", "github"),
    ("youtube", "YouTube", "youtube"),
    ("chatgpt", "ChatGPT", "openai"),
    ("claude", "Claude", "claude"),
    ("gemini", "Gemini", "gemini"),
    ("netflix", "Netflix", "netflix"),
    ("disney-plus", "Disney+", "disneyplus"),
    ("prime-video", "Prime Video", "primevideo"),
    ("spotify", "Spotify", "spotify"),
    ("tiktok", "TikTok", "tiktok"),
    ("discord", "Discord", "discord"),
    ("bilibili", "Bilibili", "bilibili"),
];

/// Services on the card (FlClash: two rows of the card's columns, at most
/// four, what the core checks at once).
pub const CARD_COUNT: usize = 4;
/// FlClash serviceCheckConcurrency.
const PARALLEL: usize = 4;
/// Each request's timeout; a check gets six times that (libbox).
const TIMEOUT_MILLIS: i64 = 8000;
/// probeSettleDuration: a route change is checked once it has settled.
const SETTLE_MILLIS: u64 = 1500;
/// probeThrottleDuration: an automatic check waits this long after the last.
const THROTTLE_MILLIS: i64 = 5000;
/// While the card is shown, answers older than this are checked again.
const INTERVAL_SECS: u64 = 120;

/// FlClash ServiceProbeStatus.
#[derive(Clone, Copy, PartialEq, Debug)]
pub enum Status {
    Pending,
    Available,
    Unavailable,
    Restricted,
    DisallowedIsp,
    Blocked,
    UnsupportedRegion,
    OriginalsOnly,
    ComingSoon,
    Timeout,
    Failed,
}

impl Status {
    /// The id libbox reports (ServiceProbeStatus.byId; unknown is a failure).
    pub fn from_id(id: &str) -> Status {
        match id {
            "available" => Status::Available,
            "unavailable" => Status::Unavailable,
            "restricted" => Status::Restricted,
            "disallowed-isp" => Status::DisallowedIsp,
            "blocked" => Status::Blocked,
            "unsupported-region" => Status::UnsupportedRegion,
            "originals-only" => Status::OriginalsOnly,
            "coming-soon" => Status::ComingSoon,
            "timeout" => Status::Timeout,
            _ => Status::Failed,
        }
    }

    /// _statusLabel, localized.
    pub fn label(self) -> String {
        tr(match self {
            Status::Pending => "Pending",
            Status::Available => "Available",
            Status::Unavailable => "Unavailable",
            Status::Restricted => "Access restricted",
            Status::DisallowedIsp => "Disallowed ISP",
            Status::Blocked => "Blocked",
            Status::UnsupportedRegion => "Unsupported region",
            Status::OriginalsOnly => "Originals only",
            Status::ComingSoon => "Coming soon",
            Status::Timeout => "Timeout",
            Status::Failed => "Check failed",
        })
    }

    /// _statusColor: 0 idle, 1 success, 2 warning, 3 error.
    pub fn tone(self) -> i32 {
        match self {
            Status::Pending => 0,
            Status::Available => 1,
            Status::Restricted | Status::DisallowedIsp | Status::Blocked | Status::UnsupportedRegion | Status::OriginalsOnly | Status::ComingSoon => 2,
            Status::Unavailable | Status::Timeout | Status::Failed => 3,
        }
    }
}

/// A probe error from the host (the session could not open, the service
/// changed): the deadline is a timeout, anything else a failure.
pub fn status_from_error(message: &str) -> Status {
    let lower = message.to_lowercase();
    if lower.contains("deadline") || lower.contains("timeout") || lower.contains("timed out") {
        Status::Timeout
    } else {
        Status::Failed
    }
}

#[derive(Clone, Default)]
struct Entry {
    status: Option<Status>,
    region: String,
    delay: i64,
    checked_at: i64,
    checking: bool,
    queued: bool,
}

#[derive(Default)]
struct Model {
    entries: Vec<Entry>,
    stamp: Option<String>,
    generation: u64,
    serial: u64,
    card_shown: bool,
    page_shown: bool,
    queue: VecDeque<usize>,
    running: usize,
    settle: Option<Timer>,
    ticker: Option<Timer>,
}

thread_local! {
    static MODEL: RefCell<Model> = RefCell::new(Model { entries: vec![Entry::default(); TARGETS.len()], ..Default::default() });
}

fn model<R>(f: impl FnOnce(&mut Model) -> R) -> R {
    MODEL.with(|m| f(&mut m.borrow_mut()))
}

fn index_of(id: &str) -> Option<usize> {
    TARGETS.iter().position(|t| t.0 == id)
}

// ---------------------------------------------------------------------------
// Order and enabled services (FlClash serviceOrder, disabledServices)
// ---------------------------------------------------------------------------

fn preference_ids(key: &str) -> Vec<String> {
    data(|d| d.preferences.get(key).and_then(|v| v.as_array()).map(|a| a.iter().filter_map(|v| v.as_str().map(str::to_string)).collect()).unwrap_or_default())
}

/// orderServiceTargets: the saved order first, then the rest in FlClash's order.
pub fn ordered_from(order: &[String]) -> Vec<usize> {
    let mut out: Vec<usize> = Vec::with_capacity(TARGETS.len());
    for id in order {
        if let Some(i) = index_of(id) {
            if !out.contains(&i) {
                out.push(i);
            }
        }
    }
    for i in 0..TARGETS.len() {
        if !out.contains(&i) {
            out.push(i);
        }
    }
    out
}

fn ordered() -> Vec<usize> {
    ordered_from(&preference_ids("serviceOrder"))
}

fn disabled() -> Vec<String> {
    preference_ids("disabledServices")
}

/// enabledServiceTargets, in order.
pub fn enabled() -> Vec<usize> {
    let off = disabled();
    ordered().into_iter().filter(|i| !off.iter().any(|d| d == TARGETS[*i].0)).collect()
}

fn card_targets() -> Vec<usize> {
    enabled().into_iter().take(CARD_COUNT).collect()
}

/// Turns a service on or off; the last enabled one stays on (FlClash locks it).
pub fn toggle(id: &str) {
    let Some(index) = index_of(id) else { return };
    let mut off = disabled();
    if let Some(pos) = off.iter().position(|d| d == id) {
        off.remove(pos);
    } else {
        if enabled().len() <= 1 {
            return;
        }
        off.push(id.to_string());
    }
    crate::app::set_preferences(json!({ "disabledServices": off }), move |ok| {
        if ok {
            // A service that just came on and is on screen gets its answer.
            check_with(index, Check::Auto);
        }
    });
}

/// Moves a service one place up or down in the order (FlClash reorders by drag).
pub fn move_by(id: &str, delta: i32) {
    let Some(index) = index_of(id) else { return };
    let mut order = ordered();
    let Some(pos) = order.iter().position(|i| *i == index) else { return };
    let next = pos as i32 + delta;
    if next < 0 || next >= order.len() as i32 {
        return;
    }
    order.swap(pos, next as usize);
    let ids: Vec<&str> = order.iter().map(|i| TARGETS[*i].0).collect();
    crate::app::set_preferences(json!({ "serviceOrder": ids }), |ok| {
        if ok {
            for index in card_targets() {
                check_with(index, Check::Auto);
            }
        }
    });
}

// ---------------------------------------------------------------------------
// Views
// ---------------------------------------------------------------------------

/// One service as the card, the page and the manager show it.
pub struct View {
    pub id: &'static str,
    pub label: &'static str,
    pub icon: &'static str,
    /// The latency of an available service, or its status.
    pub status: String,
    pub tone: i32,
    pub loading: bool,
    /// The region the service placed the visitor in and when it was checked.
    pub subtitle: String,
    pub enabled: bool,
    pub on_card: bool,
}

fn view_of(index: usize, e: &Entry, enabled: bool, on_card: bool, now: i64) -> View {
    let (id, label, icon) = TARGETS[index];
    let (status, tone) = match e.status {
        None if e.checking => (tr("Checking"), 0),
        None => (Status::Pending.label(), 0),
        Some(Status::Available) if e.delay > 0 => (format!("{} ms", e.delay), 1),
        Some(s) => (s.label(), s.tone()),
    };
    let mut parts = Vec::new();
    if !e.region.is_empty() {
        parts.push(format!("{} {}", tr("Region"), e.region));
    }
    if e.checked_at > 0 {
        parts.push(crate::format::ago(e.checked_at, now).replace("更新", "检测"));
    }
    View {
        id,
        label,
        icon,
        status,
        tone,
        loading: e.checking && e.status.is_none(),
        subtitle: parts.join(" · "),
        enabled,
        on_card,
    }
}

/// The card's services.
pub fn card_view() -> Vec<View> {
    let now = crate::format::now_ms();
    let card = card_targets();
    model(|m| card.iter().map(|i| view_of(*i, &m.entries[*i], true, true, now)).collect())
}

/// Every enabled service (the page).
pub fn page_view() -> Vec<View> {
    let now = crate::format::now_ms();
    let card = card_targets();
    let on = enabled();
    model(|m| on.iter().map(|i| view_of(*i, &m.entries[*i], true, card.contains(i), now)).collect())
}

/// Every service in order, enabled or not (the manager).
pub fn manage_view() -> Vec<View> {
    let now = crate::format::now_ms();
    let card = card_targets();
    let on = enabled();
    model(|m| ordered().iter().map(|i| view_of(*i, &m.entries[*i], on.contains(i), card.contains(i), now)).collect())
}

/// The card's verdict chip: (label, tone 1 ok / 2 warn), or none while a
/// service has no answer yet.
pub fn verdict() -> Option<(String, i32)> {
    let card = card_targets();
    model(|m| {
        let problems = card.iter().filter(|i| matches!(m.entries[**i].status, Some(s) if s != Status::Available)).count();
        if problems > 0 {
            Some((tr("{} abnormal").replace("{}", &problems.to_string()), 2))
        } else if !card.is_empty() && card.iter().all(|i| m.entries[*i].status.is_some()) {
            Some((tr("All available"), 1))
        } else {
            None
        }
    })
}

/// Whether a check of a card service runs.
pub fn checking() -> bool {
    let card = card_targets();
    model(|m| card.iter().any(|i| m.entries[*i].checking || m.entries[*i].queued))
}

/// Whether any enabled service is being checked (the page's bar).
pub fn checking_any() -> bool {
    let on = enabled();
    model(|m| on.iter().any(|i| m.entries[*i].checking || m.entries[*i].queued))
}

// ---------------------------------------------------------------------------
// When to check
// ---------------------------------------------------------------------------

/// What a check depends on: the service state, the profile, mode and selections.
fn route_stamp() -> String {
    data(|d| {
        let mut selections: Vec<String> = d.groups.iter().map(|g| format!("{}:{}", text(&g["tag"]), text(&g["selected"]))).collect();
        selections.sort();
        format!(
            "{}:{}:{}:{}:{}:{}",
            d.status(),
            d.remote(),
            text(d.dashboard.get("startedAt").unwrap_or(&Value::Null)),
            d.selected_profile_id(),
            text(d.dashboard.get("mode").unwrap_or(&Value::Null)).to_lowercase(),
            selections.join(",")
        )
    })
}

/// Called on dashboard projections: a new route drops the answers and checks
/// the shown services again once it settles.
pub fn sync() {
    let stamp = route_stamp();
    let changed = model(|m| {
        if m.stamp.as_deref() == Some(stamp.as_str()) {
            return false;
        }
        m.stamp = Some(stamp);
        m.generation += 1;
        m.queue.clear();
        for e in m.entries.iter_mut() {
            *e = Entry { checking: e.checking, ..Entry::default() };
        }
        true
    });
    if changed {
        let timer = Timer::default();
        timer.start(TimerMode::SingleShot, Duration::from_millis(SETTLE_MILLIS), || check_shown(Check::Auto));
        model(|m| m.settle = Some(timer));
        mark(Mark::DASH);
    }
}

/// Every service that is on screen: the card's, and the page's while it is open.
fn check_shown(kind: Check) {
    let (card, page) = model(|m| (m.card_shown, m.page_shown));
    let targets = if page { enabled() } else if card { card_targets() } else { Vec::new() };
    for index in targets {
        // The page checks on request; automatically only what has no answer.
        if page && !card_targets().contains(&index) && kind == Check::Auto && model(|m| m.entries[index].status.is_some()) {
            continue;
        }
        check_with(index, kind);
    }
}

/// The card came into view or left it: its checks run only while it is shown
/// and the app is in the foreground. `resumed`: the app just came back, so
/// answers older than the throttle are checked again once.
pub fn set_shown(shown: bool, resumed: bool) {
    let was = model(|m| std::mem::replace(&mut m.card_shown, shown));
    if was == shown {
        return;
    }
    if shown {
        let ticker = Timer::default();
        ticker.start(TimerMode::Repeated, Duration::from_secs(INTERVAL_SECS), || {
            for index in card_targets() {
                check_with(index, Check::Auto);
            }
        });
        model(|m| m.ticker = Some(ticker));
        for index in card_targets() {
            check_with(index, if resumed { Check::Resume } else { Check::Auto });
        }
    } else {
        // Leaving (or going to the background) also drops a pending
        // route-change check and the queue: the next showing checks for itself.
        model(|m| {
            m.ticker = None;
            m.settle = None;
        });
        drop_queue();
    }
}

/// The service status page opened or closed (or the app left / came back
/// while it is open). It checks what has no answer yet; coming back, what
/// went stale.
pub fn set_page_shown(shown: bool, resumed: bool) {
    let was = model(|m| std::mem::replace(&mut m.page_shown, shown));
    if was == shown {
        return;
    }
    if shown {
        for index in enabled() {
            let unanswered = model(|m| m.entries[index].status.is_none());
            if resumed {
                check_with(index, Check::Resume);
            } else if unanswered {
                check_with(index, Check::Auto);
            }
        }
    } else {
        drop_queue();
    }
}

fn drop_queue() {
    let (card, page) = model(|m| (m.card_shown, m.page_shown));
    if card || page {
        // Still on screen elsewhere: keep only what that shows.
        let keep = if page { enabled() } else { card_targets() };
        model(|m| {
            let dropped: Vec<usize> = m.queue.iter().copied().filter(|i| !keep.contains(i)).collect();
            m.queue.retain(|i| keep.contains(i));
            for i in dropped {
                m.entries[i].queued = false;
            }
        });
    } else {
        model(|m| {
            for i in m.queue.drain(..).collect::<Vec<_>>() {
                m.entries[i].queued = false;
            }
        });
    }
    mark(Mark::DASH);
}

/// Checks every enabled service at once (the page's bar, the card's button).
pub fn check_all(manual: bool) {
    let targets = if model(|m| m.page_shown) || manual { enabled() } else { card_targets() };
    for index in targets {
        check_with(index, if manual { Check::Manual } else { Check::Auto });
    }
}

/// A tap on one service.
pub fn check_id(id: &str) {
    if let Some(index) = index_of(id) {
        check_with(index, Check::Manual);
    }
}

#[derive(Clone, Copy, PartialEq)]
enum Check {
    /// The ticker, a route change or the card coming into view: answers
    /// younger than the interval are kept.
    Auto,
    /// The app came back to the foreground: anything past the throttle.
    Resume,
    /// A tap.
    Manual,
}

fn check_with(index: usize, kind: Check) {
    let (transitioning, remote) = data(|d| (d.transitioning(), d.remote()));
    if transitioning || remote || !crate::pages::foreground() {
        return;
    }
    let now = crate::format::now_ms();
    let queued = model(|m| {
        let on_screen = m.card_shown || m.page_shown;
        let e = &mut m.entries[index];
        if e.checking || e.queued || !on_screen && kind != Check::Manual {
            return false;
        }
        let fresh = e.status.is_some() && now - e.checked_at < INTERVAL_SECS as i64 * 1000 - 1000;
        if kind == Check::Auto && fresh || kind != Check::Manual && e.checked_at > 0 && now - e.checked_at < THROTTLE_MILLIS {
            return false;
        }
        e.queued = true;
        m.queue.push_back(index);
        true
    });
    if queued {
        mark(Mark::DASH);
        pump();
    }
}

/// Starts queued checks while fewer than PARALLEL run.
fn pump() {
    loop {
        if !crate::pages::foreground() {
            return;
        }
        let next = model(|m| {
            if m.running >= PARALLEL {
                return None;
            }
            let index = m.queue.pop_front()?;
            m.entries[index].queued = false;
            m.entries[index].checking = true;
            m.running += 1;
            m.serial += 1;
            Some((index, m.generation, format!("service:{}:{}:{}", TARGETS[index].0, crate::format::now_ms(), m.serial)))
        });
        let Some((index, generation, probe)) = next else { return };
        run(index, generation, probe);
    }
}

/// Opens a probe session, runs the service's rule (libbox retries a timeout
/// while its budget lasts) and closes it.
fn run(index: usize, generation: u64, probe: String) {
    let name = TARGETS[index].0;
    let session = probe.clone();
    call("openProbe", json!({ "id": probe }), move |opened| {
        if let Err(e) = opened {
            finish(index, generation, &session, Err(status_from_error(&e)));
            return;
        }
        let s2 = session.clone();
        call("serviceCheck", json!({ "id": session, "name": name, "timeoutMillis": TIMEOUT_MILLIS }), move |r| {
            let answer = match r {
                Ok(v) => Ok((Status::from_id(&text(&v["status"])), text(&v["region"]), long(&v["delay"]).unwrap_or(0))),
                Err(e) => Err(status_from_error(&e)),
            };
            finish(index, generation, &s2, answer);
        });
    });
}

fn finish(index: usize, generation: u64, probe: &str, answer: Result<(Status, String, i64), Status>) {
    call("closeProbe", json!({ "id": probe }), |_| {});
    let stale = model(|m| {
        m.running = m.running.saturating_sub(1);
        m.entries[index].checking = false;
        if m.generation != generation {
            return true;
        }
        let (status, region, delay) = match answer {
            Ok(a) => a,
            Err(s) => (s, String::new(), 0),
        };
        m.entries[index] = Entry { status: Some(status), region, delay: if status == Status::Available { delay } else { 0 }, checked_at: crate::format::now_ms(), checking: false, queued: false };
        false
    });
    mark(Mark::DASH);
    if stale {
        // The route moved while this ran: check the new one if it is shown.
        check_with(index, Check::Auto);
    }
    pump();
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn statuses_and_order() {
        assert_eq!(Status::from_id("available"), Status::Available);
        assert_eq!(Status::from_id("unsupported-region"), Status::UnsupportedRegion);
        assert_eq!(Status::from_id("whatever"), Status::Failed);
        assert_eq!(Status::Blocked.tone(), 2);
        assert_eq!(Status::Timeout.tone(), 3);
        assert_eq!(status_from_error("context deadline exceeded"), Status::Timeout);
        assert_eq!(status_from_error("connection refused"), Status::Failed);
        let order = ordered_from(&["bilibili".into(), "nope".into(), "google".into(), "bilibili".into()]);
        assert_eq!(order.len(), TARGETS.len());
        assert_eq!(TARGETS[order[0]].0, "bilibili");
        assert_eq!(TARGETS[order[1]].0, "google");
        assert_eq!(TARGETS[order[2]].0, "github");
        for (_, _, icon) in TARGETS {
            assert!(!crate::pages::service_icons::paths(icon).0.is_empty(), "{icon}");
        }
    }
}
