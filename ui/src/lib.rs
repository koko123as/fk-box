//! FK-box UI in Rust + Slint. The Go cores (libbox) and the Kotlin service and
//! bridge layer are unchanged; this library draws the pages (a code-level port
//! of the Jetpack Compose UI, compose/*) and drives them from BoxBridge events.
//!
//! Everything platform-specific lives in [`platform`]: pages and app state
//! never test the target OS. The methods and events the UI exchanges with its
//! host are listed in protocol/methods.json.

#![cfg_attr(not(target_os = "android"), allow(dead_code))]

slint::include_modules!();

mod app;
mod forms;
mod fonts;
mod gesture;
mod format;
mod i18n;
#[cfg(test)]
mod ime_tests;
mod memory;
mod native_ime;
mod nav;
mod pages;
mod platform;
mod protocol_gen;
mod records;
mod rows;
mod theme;
mod theme_apply;
#[cfg(all(not(target_os = "android"), feature = "shot"))]
pub mod shot;

/// Runs one host event, reply or deferred callback. A panic in it is logged
/// instead of unwinding into the event loop, where android-activity would
/// close the activity. Only the handler is skipped: the page state it touches
/// is held in RefCells that are released on unwind, and the next event or
/// refresh rebuilds what the handler would have shown.
pub(crate) fn guarded(kind: &str, handler: impl FnOnce()) {
    if let Err(panic) = std::panic::catch_unwind(std::panic::AssertUnwindSafe(handler)) {
        let message = panic
            .downcast_ref::<&str>()
            .map(|s| s.to_string())
            .or_else(|| panic.downcast_ref::<String>().cloned())
            .unwrap_or_else(|| "unknown panic".into());
        platform::log_error(&format!("{kind} handler panicked: {message}"));
    }
}

/// Runs `handler` on the next event loop turn, under [`guarded`].
pub(crate) fn defer(kind: &'static str, handler: impl FnOnce() + 'static) {
    slint::Timer::single_shot(std::time::Duration::ZERO, move || guarded(kind, handler));
}
