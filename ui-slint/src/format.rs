//! Text formatting for the pages. Pure functions, unit-tested on the host.

pub fn bytes(value: f64) -> String {
    if !value.is_finite() || value <= 0.0 {
        return "0 B".into();
    }
    const UNITS: [&str; 5] = ["B", "KB", "MB", "GB", "TB"];
    let mut v = value;
    let mut unit = 0;
    while v >= 1024.0 && unit < UNITS.len() - 1 {
        v /= 1024.0;
        unit += 1;
    }
    if unit == 0 || v >= 100.0 {
        format!("{:.0} {}", v, UNITS[unit])
    } else {
        format!("{:.1} {}", v, UNITS[unit])
    }
}

pub fn rate(value: f64) -> String {
    format!("{}/s", bytes(value))
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

/// How long ago `then_ms` was, relative to `now_ms`.
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

/// Calendar date (UTC) for Unix seconds, as YYYY-MM-DD.
pub fn date(unix_seconds: i64) -> String {
    let days = unix_seconds.div_euclid(86_400);
    // Howard Hinnant's civil_from_days.
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = if m <= 2 { y + 1 } else { y };
    format!("{y:04}-{m:02}-{d:02}")
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
    fn formats() {
        assert_eq!(bytes(0.0), "0 B");
        assert_eq!(bytes(1536.0), "1.5 KB");
        assert_eq!(bytes(200.0 * 1024.0 * 1024.0), "200 MB");
        assert_eq!(rate(2048.0), "2.0 KB/s");
        assert_eq!(date(0), "1970-01-01");
        assert_eq!(date(1_798_675_200), "2026-12-31");
        assert_eq!(duration(3_723_000), "1 小时 2 分");
        assert_eq!(ago(0, 1), "从未更新");
        assert_eq!(ago(0 + 1, 2 * 3_600_000), "1 小时前更新");
        assert_eq!(delay(0).1, 0);
        assert_eq!(delay(120), ("120 ms".into(), 1));
        assert_eq!(delay(65_535).1, 3);
    }
}
