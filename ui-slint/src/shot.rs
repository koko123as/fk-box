//! Host-only screenshot harness (feature `shot`): the software renderer draws
//! the app at phone size from fixture data (a stand-in for BoxBridge), so the
//! pages can be checked visually without a device. Driven by examples/shot.rs:
//!
//!   cargo run --release --features shot --example shot -- OUT_DIR step...
//!
//! Steps: `shot:NAME`, `tab:Dashboard`, `push:KIND:ARG`, `form:KIND:ARG`, `pop`,
//! `dark`, `light`, `tick:N` (N seconds with rate samples), `scroll:DY`,
//! `tap:X,Y` (logical px), `size:W,H,SCALE`, `access` (open Access control),
//! `rss` (print VmRSS and the icon cache), `trim` (malloc_trim, then VmRSS),
//! `bg` / `fg` (the host's lifecycle event), `wait:N` (N real seconds),
//! `insets:T,B,L,R` (the host's insets, physical px), `nav` (print the insets
//! the UI uses and the scale factor).

use std::cell::{Cell, RefCell};
use std::collections::VecDeque;
use std::rc::Rc;
use std::time::Duration;

use serde_json::{json, Value};
use slint::platform::software_renderer::{MinimalSoftwareWindow, RepaintBufferType};
use slint::Rgb8Pixel;
use slint::platform::{Platform, WindowAdapter, WindowEvent};
use slint::{ComponentHandle, LogicalPosition, PhysicalSize};

use crate::{AppWindow, Nav, Theme};

thread_local! {
    static QUEUE: RefCell<VecDeque<(i64, String, String)>> = const { RefCell::new(VecDeque::new()) };
}

pub fn queue(id: i64, method: &str, args: &str) {
    QUEUE.with(|q| q.borrow_mut().push_back((id, method.to_string(), args.to_string())));
}

struct ShotPlatform {
    window: Rc<MinimalSoftwareWindow>,
    clock: Rc<Cell<Duration>>,
}

impl Platform for ShotPlatform {
    fn create_window_adapter(&self) -> Result<Rc<dyn WindowAdapter>, slint::PlatformError> {
        Ok(self.window.clone())
    }
    fn duration_since_start(&self) -> Duration {
        self.clock.get()
    }
}

fn now_ms() -> i64 {
    crate::format::now_ms()
}

/// BoxBridge's answers, enough for every page to show content.
fn answer(method: &str, args: &Value) -> Result<Value, String> {
    let now = now_ms();
    if std::env::var_os("SHOT_TRACE").is_some() && method != "appIcon" {
        eprintln!("call {method} {args}");
    }
    Ok(match method {
        "locale" => json!({ "language": std::env::var("SHOT_LANG").unwrap_or("zh".into()), "utcOffsetMinutes": 480, "fontScale": 1.0 }),
        "attach" => Value::Null,
        // The saved preferences are the values just set.
        "setPreferences" => args["values"].clone(),
        "ready" => json!({
            "version": "1.15.6", "coreVersion": "1.13.0", "xrayVersion": "25.10.15", "mihomoVersion": "1.19.15",
            "systemAccentColor": 0xff007aff_u32,
            "dashboard": dashboard(now),
            "profiles": profiles(now),
            "settings": {},
            "preferences": { "hiddenCards": [] },
            "lan": { "addresses": ["192.168.1.23", "fd00::23"], "running": false, "enabled": false, "bindings": [], "canToggle": true, "profileId": 1 },
            "tools": {},
            "diagnostics": { "stun": {}, "quality": {} },
        }),
        "egress" => json!({ "ip": "23.191.200.117", "country": "us", "organization": "Unredacted Inc", "asn": "AS396356", "type": "Hosting", "ipSource": "ident.me", "checkedAt": now }),
        "profileStats" => json!({ "groups": "6", "outbounds": "48", "rules": "1203" }),
        // Access control: a phone's worth of installed apps, each icon a
        // 48 dp PNG at 3x like the host renders (BoxActivity appIcon).
        "listApps" => Value::Array(
            (0..400)
                .map(|i| json!({ "package": format!("com.example.app{i:03}"), "name": format!("App {i:03}"), "system": false, "internet": true, "updatedAt": now - i as i64 * 60_000 }))
                .collect(),
        ),
        // Service status probes: GitHub answers after a real 160 ms, Google
        // fails as it does on a network that cannot reach it.
        "openProbe" | "closeProbe" => Value::Null,
        "probeReach" => {
            if args["url"].as_str().unwrap_or("").contains("github") {
                std::thread::sleep(Duration::from_millis(160));
                json!({ "status": 200, "latency": 160 })
            } else {
                return Err("dial tcp 142.250.72.4:443: connect: connection refused".into());
            }
        }
        // Service checks (libbox ServiceCheck): a spread of FlClash's answers.
        "serviceCheck" => {
            std::thread::sleep(Duration::from_millis(20));
            let (status, region, delay) = match args["name"].as_str().unwrap_or("") {
                "google" => ("available", "", 212),
                "github" => ("available", "", 160),
                "youtube" => ("available", "JP", 388),
                "chatgpt" => ("available", "SG", 431),
                "claude" => ("unsupported-region", "HK", 0),
                "gemini" => ("available", "US", 517),
                "netflix" => ("originals-only", "", 0),
                "disney-plus" => ("blocked", "", 0),
                "prime-video" => ("available", "JP", 690),
                "spotify" => ("timeout", "", 0),
                "tiktok" => ("available", "SG", 302),
                "discord" => ("restricted", "", 0),
                _ => ("available", "CN", 88),
            };
            json!({ "name": args["name"], "status": status, "region": region, "delay": delay, "checkedAt": now })
        }
        "appIcon" => Value::String(icon_png(args["package"].as_str().unwrap_or("app"))),
        "readQualityCache" => json!({ "quality": { "ip": "23.191.200.117", "type": "hosting", "risky": true, "sources": [] }, "checkedAt": now }),
        // Setters and visibility switches: nothing to answer.
        _ => Value::Null,
    })
}

/// A 144 px launcher icon PNG for `pkg`, written once to the temp dir.
fn icon_png(pkg: &str) -> String {
    let dir = std::env::temp_dir().join("fkui-shot-icons");
    std::fs::create_dir_all(&dir).ok();
    let path = dir.join(format!("{pkg}.png"));
    if !path.exists() {
        let size = 144u32;
        let seed = pkg.bytes().fold(7u32, |h, b| h.wrapping_mul(31).wrapping_add(b as u32));
        let mut pixels = Vec::with_capacity((size * size * 4) as usize);
        for y in 0..size {
            for x in 0..size {
                let inside = (x as i32 - 72).pow(2) + (y as i32 - 72).pow(2) < 70 * 70;
                pixels.extend_from_slice(&if inside { [(seed >> 4) as u8 ^ x as u8, (seed >> 12) as u8 ^ y as u8, (seed >> 20) as u8, 255] } else { [0, 0, 0, 0] });
            }
        }
        if let Ok(file) = std::fs::File::create(&path) {
            let mut encoder = png::Encoder::new(std::io::BufWriter::new(file), size, size);
            encoder.set_color(png::ColorType::Rgba);
            encoder.set_depth(png::BitDepth::Eight);
            if let Ok(mut writer) = encoder.write_header() {
                writer.write_image_data(&pixels).ok();
            }
        }
    }
    path.to_string_lossy().into_owned()
}

fn dashboard(now: i64) -> Value {
    json!({
        "status": "Started", "selectedProfileId": 1, "startedAt": now - 130_000, "trafficAvailable": true,
        "modes": ["Rule", "Global", "Direct"], "mode": "Rule", "systemProxyAvailable": false, "systemProxy": false,
        "remote": null, "remoteConnected": false, "deprecated": [],
    })
}

fn profiles(now: i64) -> Value {
    json!([
        { "id": 1, "name": "机场订阅 Premium", "remote": true, "url": "https://example.com/sub", "updatedAt": now - 3_600_000, "autoUpdate": true, "interval": 1440, "core": "auto", "resolvedCore": "sing-box",
          "upload": 3_221_225_472_i64, "download": 48_318_382_080_i64, "total": 214_748_364_800_i64, "expire": now / 1000 + 86_400 * 23, "intervalUserSet": false },
        { "id": 2, "name": "Local mihomo", "remote": false, "url": "", "updatedAt": 0, "autoUpdate": false, "interval": 1440, "core": "mihomo", "resolvedCore": "mihomo", "intervalUserSet": false },
        { "id": 3, "name": "Xray backup", "remote": false, "url": "", "updatedAt": 0, "autoUpdate": false, "interval": 1440, "core": "xray", "resolvedCore": "xray", "intervalUserSet": false },
    ])
}

fn groups() -> Value {
    let nodes = |prefix: &str, n: usize| -> Vec<Value> {
        (0..n)
            .map(|i| json!({ "tag": format!("{prefix} {:02}", i + 1), "type": if i % 3 == 0 { "VLESS" } else if i % 3 == 1 { "Shadowsocks" } else { "Trojan" }, "delay": if i % 5 == 4 { 0 } else { 80 + i * 37 }, "testedAt": 1 }))
            .collect()
    };
    json!([
        { "tag": "Proxy", "type": "Selector", "selectable": true, "selected": "香港 01", "expanded": true, "items": nodes("香港", 12) },
        { "tag": "Auto", "type": "URLTest", "selectable": false, "selected": "日本 02", "expanded": false, "items": nodes("日本", 6) },
        { "tag": "Streaming", "type": "Selector", "selectable": true, "selected": "美国 01", "expanded": false, "items": nodes("美国", 8) },
    ])
}

fn event(kind: &str, value: Value) {
    crate::app::on_event(&json!({ "v": { "type": kind, "value": value } }).to_string());
}

struct Harness {
    window: Rc<MinimalSoftwareWindow>,
    clock: Rc<Cell<Duration>>,
    app: AppWindow,
    _timer: Rc<slint::Timer>,
    width: u32,
    height: u32,
    tick: u64,
}

impl Harness {
    fn pump(&self) {
        for _ in 0..50 {
            slint::platform::update_timers_and_animations();
            let next = QUEUE.with(|q| q.borrow_mut().pop_front());
            match next {
                Some((id, method, args)) => {
                    let args: Value = serde_json::from_str(&args).unwrap_or(Value::Null);
                    let (ok, value) = match answer(&method, &args) {
                        Ok(v) => (true, v),
                        Err(e) => (false, Value::String(e)),
                    };
                    crate::app::on_reply(id, ok, &json!({ "v": value }).to_string());
                }
                None => {
                    if !self.window.has_active_animations() {
                        // One more turn for zero-delay timers scheduled by the replies.
                        slint::platform::update_timers_and_animations();
                        if QUEUE.with(|q| q.borrow().is_empty()) {
                            break;
                        }
                    }
                    self.advance(Duration::from_millis(16));
                }
            }
        }
    }

    fn advance(&self, d: Duration) {
        self.clock.set(self.clock.get() + d);
        slint::platform::update_timers_and_animations();
    }

    /// Lets animations finish (up to 2 s) so the frame is settled.
    fn settle(&self) {
        for _ in 0..125 {
            self.pump();
            if !self.window.has_active_animations() {
                break;
            }
            self.advance(Duration::from_millis(16));
        }
        self.pump();
    }

    fn second(&mut self) {
        self.tick += 1;
        let t = self.tick as f64;
        let up = (1.0 + (t * 0.7).sin()) * 40_000.0 + 2_000.0;
        let down = (1.0 + (t * 0.4).cos()) * 900_000.0 + 50_000.0;
        event("rates", json!({ "upload": up, "download": down, "uploadTotal": format!("{:.1} MB", t * 0.3), "downloadTotal": format!("{:.1} MB", t * 4.1), "memoryBytes": 19_700_000, "connections": 23 }));
        event("connectionStats", json!({ "active": 23, "recent": 148 + self.tick * 3, "dns": 14 }));
        event("memory", json!({ "total": 85_000_000, "rss": 85_000_000, "app": 65_300_000, "core": 19_700_000, "remote": false, "running": true, "heapInuse": 9_000_000, "heapIdle": 4_000_000, "stack": 1_000_000, "runtimeOther": 5_700_000, "goroutines": 120 }));
        for _ in 0..10 {
            self.advance(Duration::from_millis(100));
            self.pump();
        }
    }

    fn shot(&self, path: &str) {
        self.settle();
        self.window.request_redraw();
        let (w, h) = (self.width as usize, self.height as usize);
        let mut buffer = vec![Rgb8Pixel::new(0, 0, 0); w * h];
        self.window.draw_if_needed(|renderer| {
            renderer.render(&mut buffer, w);
        });
        let file = std::fs::File::create(path).expect("create png");
        let mut encoder = png::Encoder::new(std::io::BufWriter::new(file), w as u32, h as u32);
        encoder.set_color(png::ColorType::Rgb);
        encoder.set_depth(png::BitDepth::Eight);
        let mut writer = encoder.write_header().expect("png header");
        let bytes: Vec<u8> = buffer.iter().flat_map(|p| [p.r, p.g, p.b]).collect();
        writer.write_image_data(&bytes).expect("png data");
        eprintln!("wrote {path}");
    }

    fn pointer(&self, event: WindowEvent) {
        self.window.window().dispatch_event(event);
        self.pump();
    }
}

pub fn rss_kb() -> u64 {
    std::fs::read_to_string("/proc/self/status")
        .ok()
        .and_then(|s| s.lines().find(|l| l.starts_with("VmRSS:")).and_then(|l| l.split_whitespace().nth(1)).and_then(|v| v.parse().ok()))
        .unwrap_or(0)
}

pub fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let out = args.first().cloned().unwrap_or_else(|| ".".into());
    std::fs::create_dir_all(&out).ok();
    let window = MinimalSoftwareWindow::new(RepaintBufferType::NewBuffer);
    let clock = Rc::new(Cell::new(Duration::ZERO));
    slint::platform::set_platform(Box::new(ShotPlatform { window: window.clone(), clock: clock.clone() })).unwrap();
    crate::fonts::install();
    let rss0 = rss_kb();
    let (app, timer) = crate::app::start().expect("window");
    let mut h = Harness { window, clock, app, _timer: timer, width: 0, height: 0, tick: 0 };
    let resize = |h: &mut Harness, w: f32, hh: f32, scale: f32| {
        h.width = (w * scale).round() as u32;
        h.height = (hh * scale).round() as u32;
        h.window.window().dispatch_event(WindowEvent::ScaleFactorChanged { scale_factor: scale });
        h.window.set_size(PhysicalSize::new(h.width, h.height));
        h.window.window().dispatch_event(WindowEvent::Resized { size: slint::LogicalSize::new(w, hh) });
        let nav = h.app.global::<Nav>();
        nav.set_width(w * 1.0);
        nav.set_height(hh * 1.0);
    };
    // A 1080×2400 phone at 420 dpi: 411×914 dp, a 24 dp status bar and a 24 dp gesture bar.
    resize(&mut h, 411.0, 914.0, 2.625);
    h.app.show().unwrap();
    {
        let nav = h.app.global::<Nav>();
        nav.set_inset_top(24.0);
        nav.set_inset_bottom(24.0);
    }
    h.pump();
    event("groups", groups());
    h.pump();
    for _ in 0..3 {
        h.second();
    }
    eprintln!("rss: start {} kB, after startup {} kB", rss0, rss_kb());
    for step in args.iter().skip(1) {
        let (cmd, rest) = step.split_once(':').unwrap_or((step.as_str(), ""));
        match cmd {
            "shot" => h.shot(&format!("{out}/{rest}.png")),
            "tab" => {
                h.app.global::<Nav>().invoke_select_tab(rest.into());
                h.pump();
            }
            "push" | "form" => {
                let (kind, arg) = rest.split_once(':').unwrap_or((rest, ""));
                if cmd == "push" {
                    h.app.global::<Nav>().invoke_push(kind.into(), arg.into());
                } else {
                    h.app.global::<Nav>().invoke_form(kind.into(), arg.into());
                }
                h.settle();
            }
            "act" => {
                let (id, arg) = rest.split_once(':').unwrap_or((rest, ""));
                h.app.global::<Nav>().invoke_act(id.into(), arg.into());
                h.settle();
            }
            "pop" => {
                h.app.global::<Nav>().invoke_pop();
                h.settle();
            }
            "dark" | "light" => {
                h.app.global::<Theme>().set_mode(cmd.into());
                h.pump();
            }
            "tick" => {
                for _ in 0..rest.parse::<u32>().unwrap_or(1) {
                    h.second();
                }
            }
            "scroll" => {
                let dy: f32 = rest.parse().unwrap_or(-400.0);
                let pos = LogicalPosition::new(200.0, 450.0);
                h.pointer(WindowEvent::PointerMoved { position: pos });
                h.pointer(WindowEvent::PointerScrolled { position: pos, delta_x: 0.0, delta_y: dy });
                h.settle();
            }
            "tap" => {
                let (x, y) = rest.split_once(',').unwrap_or(("0", "0"));
                let pos = LogicalPosition::new(x.parse().unwrap_or(0.0), y.parse().unwrap_or(0.0));
                h.pointer(WindowEvent::PointerMoved { position: pos });
                h.pointer(WindowEvent::PointerPressed { position: pos, button: slint::platform::PointerEventButton::Left });
                h.advance(Duration::from_millis(50));
                h.pointer(WindowEvent::PointerReleased { position: pos, button: slint::platform::PointerEventButton::Left });
                h.pointer(WindowEvent::PointerExited);
                h.settle();
            }
            "size" => {
                let v: Vec<f32> = rest.split(',').filter_map(|s| s.parse().ok()).collect();
                if v.len() == 3 {
                    resize(&mut h, v[0], v[1], v[2]);
                    h.pump();
                }
            }
            // The host's lifecycle: `bg` sends the app to the background, `fg` brings it back.
            // Real time passing (the UI's throttles read the wall clock).
            "wait" => {
                std::thread::sleep(Duration::from_secs(rest.parse().unwrap_or(1)));
            }
            "bg" | "fg" => {
                event("foreground", json!(cmd == "fg"));
                h.pump();
            }
            // The host's insets in physical pixels (BoxActivity pushInsets).
            "insets" => {
                let v: Vec<f64> = rest.split(',').filter_map(|s| s.parse().ok()).collect();
                if v.len() == 4 {
                    event("insets", json!({ "top": v[0], "bottom": v[1], "left": v[2], "right": v[3] }));
                    h.pump();
                }
            }
            // The insets as the UI places them (logical px), and the scale factor.
            "nav" => {
                h.settle();
                let nav = h.app.global::<Nav>();
                eprintln!(
                    "nav ({rest}): scale {} insets top {} bottom {} left {} right {}",
                    h.app.window().scale_factor(),
                    nav.get_inset_top(),
                    nav.get_inset_bottom(),
                    nav.get_inset_left(),
                    nav.get_inset_right()
                );
            }
            "access" => {
                crate::pages::settings::open_access();
                h.settle();
            }
            "rss" => {
                let (loaded, pending) = crate::pages::settings::icon_stats();
                eprintln!("rss: {} kB ({rest}) icons {loaded} loaded, {pending} pending", rss_kb());
            }
            // Return freed heap to the OS, to tell retention from a leak.
            "trim" => {
                extern "C" {
                    fn malloc_trim(pad: usize) -> i32;
                }
                // SAFETY: glibc's malloc_trim only releases free heap pages.
                unsafe { malloc_trim(0) };
                eprintln!("rss: {} kB after malloc_trim", rss_kb());
            }
            other => eprintln!("unknown step {other}"),
        }
    }
}
