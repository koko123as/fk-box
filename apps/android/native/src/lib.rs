//! FK-box on Android: the GameActivity entry point (`android_main`), the JNI
//! links to the Kotlin host (src/jni_host.rs for UI calls, src/jni_store.rs
//! for the client store shared with the Kotlin service layer) and the
//! presentation controllers (navigation, rows, pages) that drive the Slint
//! component tree of `fkbox-client-ui`. Built into libfkui.so (BoxActivity's
//! `android.app.lib_name`, also loaded by `CoreStore` for the service).

#![cfg_attr(not(target_os = "android"), allow(dead_code))]
// Page state is closures over shared cells (`Rc<RefCell<(..)>>`, `Rc<dyn Fn>`);
// naming each one would not make the pages clearer.
#![allow(clippy::type_complexity)]

// The Slint component tree, translations, color schemes and fonts
// (fkbox-client-ui): the page controllers below fill its models.
pub use fkbox_client_ui::*;

// JNI links to the Kotlin host.
#[cfg(target_os = "android")]
mod jni_host;
pub mod jni_store;

// Presentation: navigation, rows and the page controllers.
mod app;
mod format;
mod forms;
mod gesture;
mod host;
#[cfg(test)]
mod ime_tests;
mod memory;
mod native_ime;
mod nav;
mod pages;
mod records;
mod rows;
#[cfg(all(not(target_os = "android"), feature = "shot"))]
pub mod shot;
mod store;
mod theme_apply;

/// What the shell's entry points call.
pub mod platform {
    pub use crate::app::{on_event, on_reply, run};
    #[cfg(target_os = "android")]
    pub use crate::fonts::install as install_fonts;
    pub use crate::gesture::{touch, Phase};
    pub use crate::guarded;
    pub use crate::host::set_host;
    pub use crate::i18n::tr;
    pub use crate::store::set_store_provider;
}

#[cfg(target_os = "android")]
use platform as ui;

/// Runs a host callback; a panic in it is logged instead of unwinding into
/// the event loop.
pub fn guarded(kind: &str, handler: impl FnOnce()) {
    if let Err(panic) = std::panic::catch_unwind(std::panic::AssertUnwindSafe(handler)) {
        let message = panic
            .downcast_ref::<&str>()
            .map(|s| s.to_string())
            .or_else(|| panic.downcast_ref::<String>().cloned())
            .unwrap_or_else(|| "unknown panic".into());
        #[cfg(target_os = "android")]
        log::error!("{kind} handler panicked: {message}");
        #[cfg(not(target_os = "android"))]
        eprintln!("{kind} handler panicked: {message}");
    }
}

pub(crate) fn defer(kind: &'static str, handler: impl FnOnce() + 'static) {
    slint::Timer::single_shot(std::time::Duration::ZERO, move || guarded(kind, handler));
}

#[cfg(target_os = "android")]
#[no_mangle]
fn android_main(android: i_slint_backend_android_activity::AndroidApp) {
    android_logger::init_once(android_logger::Config::default().with_tag("FKui").with_max_level(log::LevelFilter::Info));
    let files_dir = android.internal_data_path();
    install_panic_hook(files_dir.clone());
    if let Err(error) = jni_host::init(android.vm_as_ptr(), android.activity_as_ptr()) {
        log::error!("host init failed: {error}");
        return;
    }
    ui::set_host(Box::new(jni_host::AndroidHost));
    // The store Kotlin opens (CoreStore) before it answers the UI's `ready`.
    ui::set_store_provider(jni_store::store);
    let platform = i_slint_backend_android_activity::AndroidPlatform::new(android);
    if let Err(error) = slint::platform::set_platform(Box::new(platform)) {
        log::error!("set_platform failed: {error}");
        return;
    }
    // Decides each touch's axis before Slint's swipe handler sees it.
    i_slint_backend_android_activity::fkbox_set_touch_observer(|phase, x, y| {
        let phase = match phase {
            0 => ui::Phase::Down,
            1 => ui::Phase::Move,
            2 => ui::Phase::Up,
            _ => ui::Phase::Cancel,
        };
        ui::touch(phase, x, y);
    });
    ui::install_fonts(files_dir);
    if let Err(error) = ui::run() {
        log::error!("UI stopped: {error}");
    }
    jni_host::release();
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
