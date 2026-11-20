//! The one place the UI depends on the platform it runs on. Pages and app
//! state call these functions and never test the target OS; each platform
//! provides the same set:
//!
//! - `invoke(id, method, args)`: send a protocol method (protocol/methods.json)
//!   to the host; the answer comes back through `app::on_reply`, host events
//!   through `app::on_event`.
//! - `log_info`, `log_error`: the platform's log.
//! - `purge_renderer`: drop the renderer's GPU caches on the next frame.
//! - `trim_heap`: give the native heap's free pages back to the system.
//! - `NATIVE_UI`: menus, dialogs and text editing use the platform's widgets.
//!
//! Android is the only client today (`android/`). Off Android the crate builds
//! for host unit tests and the screenshot harness (`desktop.rs`).

#[cfg(target_os = "android")]
mod android;
#[cfg(target_os = "android")]
pub use android::{invoke, NATIVE_UI, log_error, log_info, purge_renderer, trim_heap};

#[cfg(not(target_os = "android"))]
mod desktop;
#[cfg(not(target_os = "android"))]
pub use desktop::{invoke, NATIVE_UI, log_error, log_info, purge_renderer, trim_heap};
