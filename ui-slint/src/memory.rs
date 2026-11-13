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
        #[cfg(target_os = "android")]
        i_slint_renderer_skia::fkbox_request_purge();
        crate::app::with_window(|w| w.window().request_redraw());
    });
    // After the purge frame and the host's GC have run.
    slint::Timer::single_shot(Duration::from_millis(600), trim);
}

/// Gives the native heap's free pages back to the system: bionic's
/// M_PURGE_ALL (API 34) or M_PURGE (API 28), glibc's malloc_trim on a host.
pub fn trim() {
    #[cfg(target_os = "android")]
    {
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
    #[cfg(all(target_os = "linux", target_env = "gnu"))]
    {
        extern "C" {
            fn malloc_trim(pad: usize) -> std::os::raw::c_int;
        }
        // SAFETY: malloc_trim only releases free heap pages.
        unsafe {
            malloc_trim(0);
        }
    }
}
