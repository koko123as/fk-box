//! Off Android: host unit tests and the screenshot harness. There is no host
//! process yet; the harness answers calls from fixtures (src/shot.rs).

/// No platform widgets: menus, dialogs and text editing are drawn in Slint.
pub const NATIVE_UI: bool = false;

/// The client name the hello handshake sends. Off Android: host unit tests and the screenshot harness.
pub const CLIENT: &str = "desktop";

/// Without a host, calls fail and the window shows the error.
#[cfg(not(feature = "shot"))]
pub fn invoke(_id: i64, _method: &str, _args: &str) -> Result<(), String> {
    Err("No host".into())
}

/// The screenshot harness answers the calls itself (src/shot.rs).
#[cfg(feature = "shot")]
pub fn invoke(id: i64, method: &str, args: &str) -> Result<(), String> {
    crate::shot::queue(id, method, args);
    Ok(())
}

pub fn log_info(message: &str) {
    eprintln!("{message}");
}

pub fn log_error(message: &str) {
    eprintln!("{message}");
}

/// The software renderer the harness uses keeps no GPU caches.
pub fn purge_renderer() {}

/// glibc's malloc_trim; nothing elsewhere.
pub fn trim_heap() {
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
