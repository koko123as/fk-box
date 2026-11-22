//! FK-box platform-neutral client layer.
//!
//! - [`Host`]: the contract between the Slint UI (`fkbox-client-ui`) and the
//!   platform shell that runs it (`apps/android/native` on Android).
//! - [`storage::Store`]: the client store (service settings, profile
//!   metadata, remote control servers, interface preferences), one SQLite
//!   file whose location the platform shell passes in.
//! - [`legacy`]: the one-time import of the Android app's Room databases and
//!   SharedPreferences into the store.
//! - [`api`]: the store's JSON call surface for a platform host (the Kotlin
//!   service layer on Android reaches the store through it over JNI).
//!
//! Subscriptions, routing rules, backups, WebDAV and update checks follow in
//! later phase-2 slices (docs/architecture/phase2.md).

pub mod api;
pub mod legacy;
mod parcel;
pub mod preferences;
pub mod storage;

/// What the UI needs from the platform: one asynchronous call into the host
/// app. The UI sends `method` with JSON `args` under the request `id`; the
/// host answers later through the UI's reply entry point with the same `id`
/// (on Android, `SlintHost.nativeReply`), and pushes unsolicited events
/// through its event entry point (`SlintHost.nativeEvent`).
///
/// `invoke` only delivers the call. An `Err` means it could not be delivered
/// (no host attached, the host threw); the message is shown to the user.
pub trait Host: Send + Sync {
    fn invoke(&self, id: i64, method: &str, args: &str) -> Result<(), String>;
}
