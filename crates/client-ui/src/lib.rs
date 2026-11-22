//! FK-box UI in Rust + Slint. The Go cores (libbox) and the Kotlin service and
//! bridge layer are unchanged; this library draws the pages (a code-level port
//! of the Jetpack Compose UI, compose/*) and drives them from BoxBridge events.
//! A platform shell (apps/android/native, libfkui.so) runs it through
//! [platform].

#![cfg_attr(not(target_os = "android"), allow(dead_code))]

slint::include_modules!();

mod app;
mod fonts;
mod format;
mod forms;
mod gesture;
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
#[cfg(all(not(target_os = "android"), feature = "shot"))]
pub mod shot;
mod theme;
mod theme_apply;

/// What a platform shell calls into the UI: register its host, start the
/// event loop, and hand over host replies and events, touches and fonts.
pub mod platform {
    pub use crate::app::{on_event, on_reply, run};
    #[cfg(target_os = "android")]
    pub use crate::fonts::install as install_fonts;
    pub use crate::gesture::{touch, Phase};
    pub use crate::guarded;
    pub use crate::host::set_host;
    pub use crate::i18n::tr;
}

/// Runs one host event, reply or deferred callback. A panic in it is logged
/// instead of unwinding into the event loop, where android-activity would
/// close the activity. Only the handler is skipped: the page state it touches
/// is held in RefCells that are released on unwind, and the next event or
/// refresh rebuilds what the handler would have shown.
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

/// Runs `handler` on the next event loop turn, under [`guarded`].
pub(crate) fn defer(kind: &'static str, handler: impl FnOnce() + 'static) {
    slint::Timer::single_shot(std::time::Duration::ZERO, move || guarded(kind, handler));
}
