//! The UI's link to the platform host ([`fkbox_mobile_ffi::NativeHost`]). The
//! platform shell registers its host before the event loop starts
//! (apps/android/native: the JNI link to BoxActivity); every call the pages
//! make goes through [invoke].

use std::sync::OnceLock;

use fkbox_mobile_ffi::NativeHost as Host;

static HOST: OnceLock<Box<dyn Host>> = OnceLock::new();

/// Installs the platform host. The first one stays for the process: on
/// Android it reaches whichever activity is attached at the time of the call.
pub fn set_host(host: Box<dyn Host>) {
    let _ = HOST.set(host);
}

pub fn invoke(id: i64, method: &str, args: &str) -> Result<(), String> {
    match HOST.get() {
        Some(host) => host.invoke(id, method, args),
        None => unattached(id, method, args),
    }
}

/// On Android the shell registers its host before the UI runs; without one
/// the call fails as it does when no activity is attached.
#[cfg(target_os = "android")]
fn unattached(_id: i64, _method: &str, _args: &str) -> Result<(), String> {
    Err(crate::i18n::tr("The interface is not connected to the app"))
}

/// Off Android there is no Kotlin host; calls fail and the window shows the error.
#[cfg(all(not(target_os = "android"), not(feature = "shot")))]
fn unattached(_id: i64, _method: &str, _args: &str) -> Result<(), String> {
    Err("No Android host".into())
}

/// The screenshot harness answers the calls itself (src/shot.rs).
#[cfg(all(not(target_os = "android"), feature = "shot"))]
fn unattached(id: i64, method: &str, args: &str) -> Result<(), String> {
    crate::shot::queue(id, method, args);
    Ok(())
}
