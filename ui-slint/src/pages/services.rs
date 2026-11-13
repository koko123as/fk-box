//! Service status (FlClash's ServiceStatusCard and core/service_check.go,
//! GPL-3.0): Google and GitHub reached through the running core, or directly
//! while the service is stopped, over the dashboard's egress probe. Each
//! answer shows its latency or why it failed; a route change checks again
//! after it settles, the card re-checks every two minutes while it is shown,
//! and a tap checks again at once.

use std::cell::RefCell;
use std::time::{Duration, Instant};

use serde_json::{json, Value};
use slint::{Timer, TimerMode};

use crate::app::{call, data, long, text};
use crate::i18n::tr;
use crate::pages::{mark, Mark};

/// (id, label, url): FlClash's reachability targets. GitHub's home page is
/// over the probe's 128 KiB body limit, so its icon stands in for it.
pub const TARGETS: [(&str, &str, &str); 2] = [
    ("google", "Google", "https://www.google.com/generate_204"),
    ("github", "GitHub", "https://github.com/favicon.ico"),
];

/// The probe's own ceiling (UIEgressProbe.Get); FlClash waits 10 s.
const TIMEOUT_MILLIS: i64 = 8000;
/// probeSettleDuration: a route change is checked once it has settled.
const SETTLE_MILLIS: u64 = 1500;
/// probeThrottleDuration: an automatic check waits this long after the last.
const THROTTLE_MILLIS: i64 = 5000;
/// While the card is shown, answers older than this are checked again.
const INTERVAL_SECS: u64 = 120;

#[derive(Clone, Copy, PartialEq, Debug)]
pub enum Status {
    Pending,
    Available(i64),
    Restricted,
    Unavailable,
    Timeout,
    Failed,
}

/// service_check.go statusFromCode.
pub fn status_from_code(code: i64, delay: i64) -> Status {
    match code {
        200..=299 => Status::Available(delay),
        401 | 403 | 429 | 451 => Status::Restricted,
        _ => Status::Unavailable,
    }
}

/// A probe error: the deadline is a timeout, anything else a failure.
pub fn status_from_error(message: &str) -> Status {
    let lower = message.to_lowercase();
    if lower.contains("deadline") || lower.contains("timeout") || lower.contains("timed out") {
        Status::Timeout
    } else {
        Status::Failed
    }
}

#[derive(Clone, Copy)]
struct Entry {
    status: Status,
    checking: bool,
    checked_at: i64,
}

impl Default for Entry {
    fn default() -> Self {
        Entry { status: Status::Pending, checking: false, checked_at: 0 }
    }
}

#[derive(Default)]
struct Model {
    entries: [Entry; 2],
    stamp: Option<String>,
    generation: u64,
    serial: u64,
    shown: bool,
    settle: Option<Timer>,
    ticker: Option<Timer>,
}

thread_local! {
    static MODEL: RefCell<Model> = RefCell::new(Model::default());
}

fn model<R>(f: impl FnOnce(&mut Model) -> R) -> R {
    MODEL.with(|m| f(&mut m.borrow_mut()))
}

/// One row of the card: the label, the status text, its tone (0 idle,
/// 1 available, 2 restricted, 3 failed) and whether a check runs.
pub struct View {
    pub id: &'static str,
    pub label: &'static str,
    pub status: String,
    pub tone: i32,
    pub loading: bool,
}

pub fn view() -> Vec<View> {
    model(|m| {
        TARGETS
            .iter()
            .zip(m.entries.iter())
            .map(|((id, label, _), e)| {
                let (status, tone) = match e.status {
                    Status::Pending => (tr("Pending"), 0),
                    Status::Available(delay) => (format!("{delay} ms"), 1),
                    Status::Restricted => (tr("Restricted"), 2),
                    Status::Unavailable => (tr("Unavailable"), 3),
                    Status::Timeout => (tr("Timeout"), 3),
                    Status::Failed => (tr("Check failed"), 3),
                };
                View { id, label, status, tone, loading: e.checking && e.status == Status::Pending }
            })
            .collect()
    })
}

pub fn checking() -> bool {
    model(|m| m.entries.iter().any(|e| e.checking))
}

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
/// again once it settles.
pub fn sync() {
    let stamp = route_stamp();
    let changed = model(|m| {
        if m.stamp.as_deref() == Some(stamp.as_str()) {
            return false;
        }
        m.stamp = Some(stamp);
        m.generation += 1;
        m.entries = Default::default();
        true
    });
    if changed {
        let timer = Timer::default();
        timer.start(TimerMode::SingleShot, Duration::from_millis(SETTLE_MILLIS), || check_all(false));
        model(|m| m.settle = Some(timer));
        mark(Mark::DASH);
    }
}

/// The card came into view or left it: checks run only while it is shown.
pub fn set_shown(shown: bool) {
    let was = model(|m| std::mem::replace(&mut m.shown, shown));
    if was == shown {
        return;
    }
    if shown {
        let ticker = Timer::default();
        ticker.start(TimerMode::Repeated, Duration::from_secs(INTERVAL_SECS), || check_all(false));
        model(|m| m.ticker = Some(ticker));
        check_all(false);
    } else {
        model(|m| m.ticker = None);
    }
}

/// Checks every target; `manual` skips the throttle.
pub fn check_all(manual: bool) {
    for index in 0..TARGETS.len() {
        check(index, manual);
    }
}

pub fn check_id(id: &str) {
    if let Some(index) = TARGETS.iter().position(|t| t.0 == id) {
        check(index, true);
    }
}

fn check(index: usize, manual: bool) {
    let (transitioning, remote) = data(|d| (d.transitioning(), d.remote()));
    if transitioning || remote {
        return;
    }
    let now = crate::format::now_ms();
    let start = model(|m| {
        let e = &mut m.entries[index];
        if e.checking || !m.shown && !manual {
            return None;
        }
        let fresh = e.status != Status::Pending && now - e.checked_at < INTERVAL_SECS as i64 * 1000 - 1000;
        if !manual && (fresh || now - e.checked_at < THROTTLE_MILLIS) {
            return None;
        }
        e.checking = true;
        m.serial += 1;
        Some((m.generation, format!("service:{}:{}:{}", TARGETS[index].0, now, m.serial)))
    });
    let Some((generation, probe)) = start else { return };
    mark(Mark::DASH);
    run(index, generation, probe, true);
}

/// Opens a probe session, asks once (again after a timeout, as FlClash does
/// while the budget lasts) and closes it.
fn run(index: usize, generation: u64, probe: String, retry: bool) {
    let url = TARGETS[index].2;
    let session = probe.clone();
    call("openProbe", json!({ "id": probe }), move |opened| {
        if let Err(e) = opened {
            finish(index, generation, &session, status_from_error(&e));
            return;
        }
        let started = Instant::now();
        let s2 = session.clone();
        call("probeGet", json!({ "id": session, "url": url, "timeoutMillis": TIMEOUT_MILLIS }), move |r| {
            let delay = started.elapsed().as_millis() as i64;
            let status = match r {
                Ok(v) => status_from_code(long(&v["status"]).unwrap_or(0), delay.max(1)),
                Err(e) => status_from_error(&e),
            };
            if status == Status::Timeout && retry && current(generation) {
                call("closeProbe", json!({ "id": s2 }), |_| {});
                let again = format!("{s2}:retry");
                run(index, generation, again, false);
                return;
            }
            finish(index, generation, &s2, status);
        });
    });
}

fn current(generation: u64) -> bool {
    model(|m| m.generation == generation)
}

fn finish(index: usize, generation: u64, probe: &str, status: Status) {
    call("closeProbe", json!({ "id": probe }), |_| {});
    let stale = model(|m| {
        if m.generation != generation {
            m.entries[index].checking = false;
            return true;
        }
        m.entries[index] = Entry { status, checking: false, checked_at: crate::format::now_ms() };
        false
    });
    mark(Mark::DASH);
    if stale {
        // The route moved while this ran: check the new one.
        check(index, false);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn codes_and_errors() {
        assert_eq!(status_from_code(204, 120), Status::Available(120));
        assert_eq!(status_from_code(200, 9), Status::Available(9));
        assert_eq!(status_from_code(403, 9), Status::Restricted);
        assert_eq!(status_from_code(429, 9), Status::Restricted);
        assert_eq!(status_from_code(502, 9), Status::Unavailable);
        assert_eq!(status_from_error("context deadline exceeded"), Status::Timeout);
        assert_eq!(status_from_error("i/o timeout"), Status::Timeout);
        assert_eq!(status_from_error("connection refused"), Status::Failed);
    }
}
