//! FK-box UI in Rust + Slint. The Go cores (libbox) and the Kotlin service and
//! bridge layer are unchanged; this library draws the pages (a code-level port
//! of the Jetpack Compose UI, compose/*) and drives them from BoxBridge events.

#![cfg_attr(not(target_os = "android"), allow(dead_code))]

slint::include_modules!();

mod app;
mod format;
mod host;
mod i18n;
mod nav;
mod pages;
mod theme;
mod theme_apply;

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

/// Desktop type-check entry; the host build has no Kotlin bridge.
#[cfg(not(target_os = "android"))]
pub fn run_without_host() -> Result<(), slint::PlatformError> {
    app::run()
}
