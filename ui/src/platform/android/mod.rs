//! The Android platform: the GameActivity entry point, the JNI link to the
//! Kotlin host (BoxActivity), and the Android-only native services.

mod host;

pub use host::invoke;

/// Menus, dialogs, messages and text editing use Android's own widgets (NativeUi.kt, NativeEditor.kt).
pub const NATIVE_UI: bool = true;

/// The client name the hello handshake sends. The Android client.
pub const CLIENT: &str = "android";

/// The platform features this client has (protocol/methods.json "capabilities"),
/// declared in the hello handshake. Android has every one of them today.
pub const CAPABILITIES: &[&str] = &[
    "vpn_service",
    "battery_optimization",
    "per_app_proxy",
    "file_picker",
    "share_sheet",
    "qr_scanner",
    "ca_install",
    "crash_reports",
    "usbip",
    "taildrop",
];

#[no_mangle]
fn android_main(android: i_slint_backend_android_activity::AndroidApp) {
    android_logger::init_once(android_logger::Config::default().with_tag("FKui").with_max_level(log::LevelFilter::Info));
    let files_dir = android.internal_data_path();
    install_panic_hook(files_dir.clone());
    if let Err(error) = host::init(android.vm_as_ptr(), android.activity_as_ptr()) {
        log::error!("host init failed: {error}");
        return;
    }
    let platform = i_slint_backend_android_activity::AndroidPlatform::new(android);
    if let Err(error) = slint::platform::set_platform(Box::new(platform)) {
        log::error!("set_platform failed: {error}");
        return;
    }
    // Decides each touch's axis before Slint's swipe handler sees it.
    i_slint_backend_android_activity::fkbox_set_touch_observer(|phase, x, y| {
        let phase = match phase {
            0 => crate::gesture::Phase::Down,
            1 => crate::gesture::Phase::Move,
            2 => crate::gesture::Phase::Up,
            _ => crate::gesture::Phase::Cancel,
        };
        crate::gesture::touch(phase, x, y);
    });
    install_fonts(files_dir);
    if let Err(error) = crate::app::run() {
        log::error!("UI stopped: {error}");
    }
    host::release();
}

/// Every UI panic goes to logcat (tag FKui) with its backtrace and to
/// `files/fkui-panic.txt`, the latest one, which the crash report export adds
/// as plain text (CrashReportManager.createZipArchive). Panics that a guard
/// catches are written too, so the file may describe one the app survived.
fn install_panic_hook(files_dir: Option<std::path::PathBuf>) {
    let path = files_dir.map(|d| d.join("fkui-panic.txt"));
    std::panic::set_hook(Box::new(move |info| {
        let thread = std::thread::current().name().unwrap_or("unnamed").to_string();
        let seconds = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0);
        let backtrace = std::backtrace::Backtrace::force_capture();
        let text = format!("FKui panic (unix time {seconds}) on thread '{thread}':\n{info}\n\nbacktrace:\n{backtrace}\n");
        log::error!("{text}");
        if let Some(path) = &path {
            let _ = std::fs::write(path, &text);
        }
    }));
}

/// The system fonts SystemFonts.kt chose, from the spec it wrote to files/fkui-fonts.json.
fn install_fonts(files_dir: Option<std::path::PathBuf>) {
    match files_dir.map(|d| d.join("fkui-fonts.json")).and_then(|p| crate::fonts::read_spec(&p)) {
        Some(spec) => crate::fonts::apply(&spec),
        None => log_info("system fonts: no spec, using fontique defaults"),
    }
}

pub fn log_info(message: &str) {
    log::info!("{message}");
}

pub fn log_error(message: &str) {
    log::error!("{message}");
}

/// Asks the (vendored) Skia renderer to drop its GPU caches on the next frame.
pub fn purge_renderer() {
    i_slint_renderer_skia::fkbox_request_purge();
}

/// Gives the native heap's free pages back: bionic's M_PURGE_ALL (API 34) or
/// M_PURGE (API 28).
pub fn trim_heap() {
    extern "C" {
        fn mallopt(param: std::os::raw::c_int, value: std::os::raw::c_int) -> std::os::raw::c_int;
    }
    const M_PURGE: std::os::raw::c_int = -101;
    const M_PURGE_ALL: std::os::raw::c_int = -104;
    // SAFETY: mallopt with a purge parameter only releases free pages; an
    // allocator that does not know it returns 0.
    unsafe {
        if mallopt(M_PURGE_ALL, 0) == 0 {
            mallopt(M_PURGE, 0);
        }
    }
}
