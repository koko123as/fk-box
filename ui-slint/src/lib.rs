//! FK-box UI in Rust + Slint. The Go cores (libbox) and the Kotlin service and
//! bridge layer are unchanged; this library draws the pages (a code-level port
//! of the Jetpack Compose UI, compose/*) and drives them from BoxBridge events.

#![cfg_attr(not(target_os = "android"), allow(dead_code))]

slint::include_modules!();

mod app;
mod forms;
mod fonts;
mod format;
mod host;
mod i18n;
#[cfg(test)]
mod ime_tests;
mod memory;
mod native_ime;
mod nav;
mod pages;
mod records;
mod rows;
mod theme;
mod theme_apply;
#[cfg(all(not(target_os = "android"), feature = "shot"))]
pub mod shot;

#[cfg(target_os = "android")]
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
    fonts::install(files_dir);
    if let Err(error) = app::run() {
        log::error!("UI stopped: {error}");
    }
}

/// Every UI panic goes to logcat (tag FKui) with its backtrace and to
/// `files/fkui-panic.txt`, the latest one, which the crash report export adds
/// as plain text (CrashReportManager.createZipArchive). Panics that a guard
/// catches are written too, so the file may describe one the app survived.
#[cfg(target_os = "android")]
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

/// Desktop type-check entry; the host build has no Kotlin bridge.
#[cfg(not(target_os = "android"))]
pub fn run_without_host() -> Result<(), slint::PlatformError> {
    app::run()
}
