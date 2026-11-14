//! Host-only screenshot harness (feature `shot`): the software renderer draws
//! the app at phone size from fixture data (a stand-in for BoxBridge), so the
//! pages can be checked visually without a device. Driven by examples/shot.rs:
//!
//!   cargo run --release --features shot --example shot -- OUT_DIR step...
//!
//! Steps: `shot:NAME`, `tab:Dashboard`, `push:KIND:ARG`, `form:KIND:ARG`, `pop`,
//! `dark`, `light`, `tick:N` (N seconds with rate samples), `scroll:DY`,
//! `tap:X,Y` (logical px), `size:W,H,SCALE`, `access` (open Access control),
//! `routing` (open Traffic routing), `type:TEXT` (text into the focused field),
//! `key:backspace|enter|left|end`,
//! `rss` (print VmRSS and the icon cache), `trim` (malloc_trim, then VmRSS),
//! `bg` / `fg` (the host's lifecycle event), `wait:N` (N real seconds),
//! `insets:T,B,L,R` (the host's insets, physical px), `nav` (print the insets
//! the UI uses and the scale factor), `fling:COUNT,DY` (COUNT fast drags of DY
//! px with icons arriving on reused rows and a frame drawn every 16 ms).

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
    /// While a fling runs, icon answers wait here and arrive a few per frame,
    /// out of order, as the host's IO thread answers them on a phone.
    static ICONS_LATER: RefCell<Option<VecDeque<(i64, String, String)>>> = const { RefCell::new(None) };
}

pub fn queue(id: i64, method: &str, args: &str) {
    let deferred = method == "appIcon"
        && ICONS_LATER.with(|l| match l.borrow_mut().as_mut() {
            Some(later) => {
                later.push_back((id, method.to_string(), args.to_string()));
                true
            }
            None => false,
        });
    if !deferred {
        QUEUE.with(|q| q.borrow_mut().push_back((id, method.to_string(), args.to_string())));
    }
}

fn answer_call(id: i64, method: &str, args: &str) {
    let args: Value = serde_json::from_str(args).unwrap_or(Value::Null);
    let (ok, value) = match answer(method, &args) {
        Ok(v) => (true, v),
        Err(e) => (false, Value::String(e)),
    };
    crate::app::on_reply(id, ok, &json!({ "v": value }).to_string());
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
                .map(|i| {
                    // Names and packages of every length (some wrap, as on a
                    // phone), so rows measure different heights.
                    let package = match i % 7 {
                        0 => format!("com.example.app{i:03}"),
                        3 => format!("com.example.verylongvendorname.subsidiary.product.flavor.internal.app{i:03}"),
                        5 => format!("cn.example.a{i:03}"),
                        _ => format!("org.example.tools.app{i:03}"),
                    };
                    let name = if i % 11 == 4 { format!("应用 {i:03} 一个名字很长很长的应用程序用来测试换行和省略") } else { format!("App {i:03}") };
                    json!({ "package": package, "name": name, "system": false, "internet": true, "updatedAt": now - i as i64 * 60_000 })
                })
                .collect(),
        ),
        "openProbe" | "closeProbe" => Value::Null,
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
        // Some apps have no icon to give (the host answers null).
        "appIcon" => {
            let pkg = args["package"].as_str().unwrap_or("app");
            if pkg.ends_with('6') {
                Value::Null
            } else {
                Value::String(icon_png(pkg))
            }
        }
        "appLabels" => Value::Object(
            args["packages"].as_array().into_iter().flatten().filter_map(Value::as_str).map(|p| (p.to_string(), json!(format!("App {}", p.rsplit('.').next().unwrap_or(p).trim_start_matches("app"))))).collect(),
        ),
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
        { "id": 1, "name": "机场订阅 Premium", "remote": true, "url": "https://edge-node.fbjfbj001.de5.net/custom_config?token=8f3a2c91d7e64b0aa1c5&flag=clash.meta&target=singbox", "updatedAt": now - 3_600_000, "autoUpdate": true, "interval": 1440, "core": "auto", "resolvedCore": "sing-box",
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
                Some((id, method, args)) => answer_call(id, &method, &args),
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

    /// Draws a frame the way the device does each vsync (layout, the rows'
    /// repeaters and images are all evaluated), into a scratch buffer.
    fn frame(&self, buffer: &mut Vec<Rgb8Pixel>) {
        let (w, h) = (self.width as usize, self.height as usize);
        if buffer.len() != w * h {
            *buffer = vec![Rgb8Pixel::new(0, 0, 0); w * h];
        }
        self.window.request_redraw();
        self.window.draw_if_needed(|renderer| {
            renderer.render(buffer, w);
        });
    }

    /// One 16 ms frame of a fling: timers and animations advance, up to
    /// `icons` icon answers arrive (newest and oldest alternately), the
    /// replies they cause run and a frame is drawn.
    fn fling_frame(&self, icons: usize, serial: &mut u64, buffer: &mut Vec<Rgb8Pixel>) {
        self.advance(Duration::from_millis(16));
        for _ in 0..icons {
            *serial += 1;
            let next = ICONS_LATER.with(|l| {
                let mut l = l.borrow_mut();
                let later = l.as_mut()?;
                if *serial % 2 == 0 { later.pop_back() } else { later.pop_front() }
            });
            if let Some((id, method, args)) = next {
                answer_call(id, &method, &args);
            }
        }
        while let Some((id, method, args)) = QUEUE.with(|q| q.borrow_mut().pop_front()) {
            answer_call(id, &method, &args);
        }
        slint::platform::update_timers_and_animations();
        self.frame(buffer);
    }

    /// `fling:COUNT,DY`: COUNT fast drags of DY logical px each (negative
    /// scrolls down), each released with speed so the list keeps flinging,
    /// with wheel bursts in between, icons arriving all the while and a
    /// frame drawn every 16 ms. Returns the frames drawn.
    fn fling(&self, count: u32, dy: f32) -> u32 {
        ICONS_LATER.with(|l| {
            let mut l = l.borrow_mut();
            if l.is_none() {
                *l = Some(VecDeque::new());
            }
        });
        let mut buffer = Vec::new();
        let mut serial = 0u64;
        let mut frames = 0u32;
        let x = 200.0;
        for n in 0..count {
            // A drag: down, 6 moves of dy/6 over 48 ms, up, then the fling runs.
            let start = if dy < 0.0 { 760.0 } else { 160.0 };
            let mut y = start;
            self.window.window().dispatch_event(WindowEvent::PointerMoved { position: LogicalPosition::new(x, y) });
            self.window.window().dispatch_event(WindowEvent::PointerPressed { position: LogicalPosition::new(x, y), button: slint::platform::PointerEventButton::Left });
            for _ in 0..6 {
                y += dy.clamp(-600.0, 600.0) / 6.0;
                self.advance(Duration::from_millis(8));
                self.window.window().dispatch_event(WindowEvent::PointerMoved { position: LogicalPosition::new(x, y) });
                self.fling_frame(1, &mut serial, &mut buffer);
                frames += 1;
            }
            self.window.window().dispatch_event(WindowEvent::PointerReleased { position: LogicalPosition::new(x, y), button: slint::platform::PointerEventButton::Left });
            self.window.window().dispatch_event(WindowEvent::PointerExited);
            // The kinetic scroll, with icons landing on the reused rows.
            for _ in 0..12 {
                self.fling_frame(2, &mut serial, &mut buffer);
                frames += 1;
            }
            // Every third drag, a burst of wheel steps as well.
            if n % 3 == 2 {
                let pos = LogicalPosition::new(x, 450.0);
                for _ in 0..4 {
                    self.window.window().dispatch_event(WindowEvent::PointerScrolled { position: pos, delta_x: 0.0, delta_y: dy / 2.0 });
                    self.fling_frame(3, &mut serial, &mut buffer);
                    frames += 1;
                }
            }
        }
        // The rest of the answers, then back to answering at once.
        let rest = ICONS_LATER.with(|l| l.borrow_mut().take()).unwrap_or_default();
        for (id, method, args) in rest {
            answer_call(id, &method, &args);
        }
        self.settle();
        self.frame(&mut buffer);
        frames
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
    run(std::env::args().skip(1).collect());
}

/// Runs the steps in `args` (the first is the output directory).
pub fn run(args: Vec<String>) {
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
            "fling" => {
                let (count, dy) = rest.split_once(',').unwrap_or((rest, "-500"));
                let frames = h.fling(count.parse().unwrap_or(40), dy.parse().unwrap_or(-500.0));
                let (loaded, pending) = crate::pages::settings::icon_stats();
                eprintln!("fling {rest}: {frames} frames, icons {loaded} loaded, {pending} pending");
            }
            "access" => {
                crate::pages::settings::open_access();
                h.settle();
            }
            "routing" => {
                crate::pages::routing::open();
                h.settle();
            }
            // Text typed into the focused field (as a committed IME text), and keys.
            "type" => {
                let text: slint::SharedString = rest.into();
                h.window.window().dispatch_event(WindowEvent::KeyPressed { text: text.clone() });
                h.window.window().dispatch_event(WindowEvent::KeyReleased { text });
                h.settle();
            }
            "key" => {
                let key = match rest {
                    "backspace" => slint::platform::Key::Backspace,
                    "enter" => slint::platform::Key::Return,
                    "left" => slint::platform::Key::LeftArrow,
                    _ => slint::platform::Key::End,
                };
                let text: slint::SharedString = key.into();
                h.window.window().dispatch_event(WindowEvent::KeyPressed { text: text.clone() });
                h.window.window().dispatch_event(WindowEvent::KeyReleased { text });
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

#[cfg(test)]
mod tests {
    /// Access control with a long list of apps: thousands of px of fast drags
    /// and flings down and back up, icon answers landing on rows the list
    /// reuses, a frame drawn every 16 ms; then closed (the memory release
    /// runs) and opened again. A panic anywhere here closes the activity on a
    /// phone (android-activity finishes it when android_main unwinds).
    #[test]
    fn access_control_survives_rapid_scrolling() {
        let out = std::env::temp_dir().join("fkui-shot-test");
        let steps = ["access", "fling:40,-520", "fling:25,600", "fling:30,-600", "pop", "wait:1", "access", "fling:10,-500", "pop"];
        let mut args = vec![out.to_string_lossy().into_owned()];
        args.extend(steps.iter().map(|s| s.to_string()));
        super::run(args);
    }
}
