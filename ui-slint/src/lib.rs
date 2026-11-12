//! FK-box UI in Rust + Slint. The Go cores (libbox) and the Kotlin service and
//! bridge layer are unchanged; this library only draws the pages.

slint::include_modules!();

mod app;
mod format;
mod host;

#[cfg(target_os = "android")]
#[no_mangle]
fn android_main(android: i_slint_backend_android_activity::AndroidApp) {
    android_logger::init_once(android_logger::Config::default().with_tag("FKui").with_max_level(log::LevelFilter::Info));
    if let Err(error) = host::init(android.vm_as_ptr(), android.activity_as_ptr()) {
        log::error!("host init failed: {error}");
        return;
    }
    let platform = i_slint_backend_android_activity::AndroidPlatform::new(android);
    if let Err(error) = slint::platform::set_platform(Box::new(platform)) {
        log::error!("set_platform failed: {error}");
        return;
    }
    if let Err(error) = app::run() {
        log::error!("UI stopped: {error}");
    }
}

/// Desktop preview for development: `cargo run --example preview` is not wired;
/// the host build only type-checks the UI and runs the formatting tests.
#[cfg(not(target_os = "android"))]
pub fn run_without_host() -> Result<(), slint::PlatformError> {
    app::run()
}
