//! Number, size and time formatting shared by the pages (BoxController.bytes,
//! StatusPanel.localDate, Records.recordTime, profileUpdateLabel).

use std::cell::Cell;
use std::time::{SystemTime, UNIX_EPOCH};

thread_local! {
    /// The device time zone's offset from UTC, from the Kotlin host.
    static OFFSET_MINUTES: Cell<i64> = const { Cell::new(0) };
}

pub fn set_utc_offset_minutes(minutes: i64) {
    OFFSET_MINUTES.with(|o| o.set(minutes));
}

pub fn now_ms() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}

/// 1024-based units, no decimals for bytes ("512 B", "1.5 MB").
/// A count on a dashboard figure: whole up to 9999, then 12.3k (dashboardCount).
pub fn count(value: i64) -> String {
    if value < 10_000 {
        value.max(0).to_string()
    } else if value < 1_000_000 {
        format!("{:.1}k", value as f64 / 1000.0)
    } else {
        format!("{:.1}M", value as f64 / 1_000_000.0)
    }
}

pub fn bytes(value: f64) -> String {
    const UNITS: [&str; 5] = ["B", "KB", "MB", "GB", "TB"];
    let mut size = value;
    let mut unit = 0;
    while size.abs() >= 1024.0 && unit < UNITS.len() - 1 {
        size /= 1024.0;
        unit += 1;
    }
    if unit == 0 {
        format!("{} {}", size as i64, UNITS[0])
    } else {
        format!("{:.1} {}", size, UNITS[unit])
    }
}

/// bytes() split into the figure and its unit.
pub fn bytes_parts(value: f64) -> (String, String) {
    let text = bytes(value);
    let mut parts = text.splitn(2, ' ');
    (parts.next().unwrap_or("0").to_string(), parts.next().unwrap_or("B").to_string())
}

fn civil(ms: i64) -> (i64, u32, u32, u32, u32, u32) {
    let local = ms / 1000 + OFFSET_MINUTES.with(|o| o.get()) * 60;
    let days = local.div_euclid(86_400);
    let secs = local.rem_euclid(86_400) as u32;
    // Howard Hinnant's days_from_civil inverse.
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    let year = if m <= 2 { y + 1 } else { y };
    (year, m, d, secs / 3600, secs / 60 % 60, secs % 60)
}

/// localDate: "yyyy-MM-dd HH:mm:ss" in local time, "—" when unset.
pub fn local_date(ms: i64) -> String {
    if ms <= 0 {
        return "—".into();
    }
    let (y, mo, d, h, mi, s) = civil(ms);
    format!("{y:04}-{mo:02}-{d:02} {h:02}:{mi:02}:{s:02}")
}

/// "yyyy-MM-dd" in local time.
pub fn date(ms: i64) -> String {
    let (y, mo, d, ..) = civil(ms);
    format!("{y:04}-{mo:02}-{d:02}")
}

/// recordTime: "HH:mm:ss", "—" when unset.
pub fn record_time(ms: i64) -> String {
    if ms <= 0 {
        return "—".into();
    }
    let (.., h, mi, s) = civil(ms);
    format!("{h:02}:{mi:02}:{s:02}")
}

/// RuntimeValue: HH:MM:SS, capped at 999:59:59.
pub fn runtime(started_at: i64, now: i64) -> String {
    let seconds = ((now - started_at) / 1000).clamp(0, 999 * 3600 + 59 * 60 + 59);
    format!("{:02}:{:02}:{:02}", seconds / 3600, seconds / 60 % 60, seconds % 60)
}

/// profileUpdateLabel: compact relative time.
pub fn update_label(ms: i64, now: i64, chinese: bool) -> String {
    if ms <= 0 {
        return if chinese { "未知".into() } else { "Unknown".into() };
    }
    let elapsed = now - ms;
    let days = elapsed / 86_400_000;
    for (count, en, zh) in [
        (days / 365, "y", "年"),
        (days / 30, "mo", "个月"),
        (days, "d", "天"),
        (elapsed / 3_600_000, "h", "小时"),
        (elapsed / 60_000, "m", "分钟"),
    ] {
        if count > 0 {
            return if chinese { format!("{count} {zh}前") } else { format!("{count}{en} ago") };
        }
    }
    if chinese { "刚刚".into() } else { "Just now".into() }
}

/// cleanErrorText: drops gRPC/Go plumbing so the reason reads first.
pub fn clean_error(text: &str) -> String {
    let mut value = text.trim().to_string();
    if let Some(index) = value.find(" recv: ") {
        if value[..index].chars().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == ' ' || c == '.') {
            value = value[index + 7..].to_string();
        }
    }
    while let Some(start) = value.find("rpc error: code = ") {
        let rest = &value[start + 18..];
        if let Some(desc) = rest.find(" desc = ") {
            if rest[..desc].chars().all(|c| c.is_ascii_alphanumeric()) {
                value = format!("{}{}", &value[..start], &rest[desc + 8..]);
                continue;
            }
        }
        break;
    }
    if let Some(stripped) = value.strip_prefix("start or reload service: ") {
        value = stripped.to_string();
    }
    let mut chars = value.chars();
    match chars.next() {
        Some(first) => first.to_uppercase().collect::<String>() + chars.as_str(),
        None => value,
    }
}

/// The first two code points of a group tag, upper-cased (GroupIconStack).
pub fn letters(tag: &str) -> String {
    tag.chars().take(2).collect::<String>().to_uppercase()
}

/// GroupMonogram: the first letter or digit, else the first character.
pub fn monogram(name: &str) -> String {
    name.chars()
        .find(|c| c.is_alphanumeric())
        .or_else(|| name.chars().next())
        .map(|c| c.to_uppercase().collect())
        .unwrap_or_default()
}

/// "1.5 KB/s".
pub fn rate(value: f64) -> String {
    format!("{}/s", bytes(value.max(0.0)))
}

/// "3 小时 12 分" style duration from milliseconds.
pub fn duration(ms: i64) -> String {
    if ms <= 0 {
        return String::new();
    }
    let total = ms / 1000;
    let (d, h, m, s) = (total / 86400, total % 86400 / 3600, total % 3600 / 60, total % 60);
    if d > 0 {
        format!("{d} 天 {h} 小时")
    } else if h > 0 {
        format!("{h} 小时 {m} 分")
    } else if m > 0 {
        format!("{m} 分 {s} 秒")
    } else {
        format!("{s} 秒")
    }
}

/// How long ago `then_ms` was updated, relative to `now_ms`.
pub fn ago(then_ms: i64, now_ms: i64) -> String {
    if then_ms <= 0 {
        return "从未更新".into();
    }
    let minutes = (now_ms - then_ms).max(0) / 60_000;
    if minutes < 1 {
        "刚刚更新".into()
    } else if minutes < 60 {
        format!("{minutes} 分钟前更新")
    } else if minutes < 60 * 24 {
        format!("{} 小时前更新", minutes / 60)
    } else {
        format!("{} 天前更新", minutes / 60 / 24)
    }
}

pub fn core_label(core: &str) -> &str {
    match core {
        "sing-box" | "singbox" => "sing-box",
        "xray" => "Xray",
        "mihomo" => "mihomo",
        "auto" | "" => "自动",
        other => other,
    }
}

pub fn status_text(status: &str) -> &'static str {
    match status {
        "Started" => "已连接",
        "Starting" => "正在启动",
        "Stopping" => "正在停止",
        _ => "未连接",
    }
}

/// Delay text and colour level (0 untested, 1 fast, 2 slow, 3 failed).
pub fn delay(ms: i64) -> (String, i32) {
    if ms <= 0 {
        (String::new(), 0)
    } else if ms >= 65_535 {
        ("超时".into(), 3)
    } else if ms < 300 {
        (format!("{ms} ms"), 1)
    } else if ms < 800 {
        (format!("{ms} ms"), 2)
    } else {
        (format!("{ms} ms"), 3)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sizes() {
        assert_eq!(bytes(0.0), "0 B");
        assert_eq!(bytes(1536.0), "1.5 KB");
        assert_eq!(bytes_parts(3.0 * 1024.0 * 1024.0), ("3.0".into(), "MB".into()));
    }

    #[test]
    fn times() {
        set_utc_offset_minutes(0);
        assert_eq!(local_date(0), "—");
        assert_eq!(local_date(86_400_000), "1970-01-02 00:00:00");
        assert_eq!(runtime(0, 3_723_000), "01:02:03");
        assert_eq!(update_label(1, 1 + 2 * 3_600_000, false), "2h ago");
    }

    #[test]
    fn errors() {
        assert_eq!(clean_error("rpc error: code = Unknown desc = start or reload service: bad config"), "Bad config");
    }
}
