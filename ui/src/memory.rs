//! Returning memory once a page that held a lot of it (the app lists: an icon
//! per row, a row per installed app) has closed, without waiting for the app
//! to go to the background. In the background the system destroys the window
//! surface (and with it every GPU texture and glyph atlas the renderer held),
//! the runtime collects and trims the Java heap and the native allocator
//! returns freed pages. [release] does the same steps while the app stays in
//! the foreground:
//!
//! 1. the page's models and images are already dropped by its owner;
//! 2. the renderer's caches and the GPU context's resources are released on
//!    the next frame (vendored Skia renderer, `fkbox_request_purge`);
//! 3. the host collects the Java heap (`releaseApps` does, after it dropped
//!    its package snapshot; labels and icons opened each app's resources);
//! 4. shortly after, the native allocator gives freed pages back ([trim]).

use std::time::Duration;

use slint::ComponentHandle;

/// Asks for the release described above; the caller has dropped its data.
pub fn release() {
    // After the closing route's projection, so the purge frame draws the page
    // underneath and nothing of the closed one is cached again.
    slint::Timer::single_shot(Duration::from_millis(50), || {
        crate::platform::purge_renderer();
        crate::app::with_window(|w| w.window().request_redraw());
    });
    // After the purge frame and the host's GC have run.
    slint::Timer::single_shot(Duration::from_millis(600), trim);
}

thread_local! {
    static IDLE_RELEASE: slint::Timer = slint::Timer::default();
}

/// [release] once no route has closed for a while (1.5 s): every page the
/// user leaves (a rule list, a log, a form) gave its models and images back,
/// and the renderer's caches and the allocator's free pages follow while the
/// user is looking at the page underneath, not during the way back.
pub fn release_when_idle() {
    IDLE_RELEASE.with(|t| t.start(slint::TimerMode::SingleShot, Duration::from_millis(1500), release));
}

/// Gives the native heap's free pages back to the system (platform::trim_heap:
/// bionic's M_PURGE_ALL or M_PURGE on Android, glibc's malloc_trim on a host).
pub fn trim() {
    crate::platform::trim_heap();
}
