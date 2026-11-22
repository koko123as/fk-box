//! FK-box platform-neutral client layer.
//!
//! - [`Host`]: the contract between the Slint UI (`fkbox-client-ui`) and the
//!   platform shell that runs it (`apps/android/native` on Android).
//! - [`storage::Store`]: the client store (service settings, profile
//!   metadata, remote control servers, interface preferences), one SQLite
//!   file whose location the platform shell passes in.
//! - [`legacy`]: the one-time import of the Android app's Room databases and
//!   SharedPreferences into the store.
//! - [`subscription`]: provider headers (`subscription-userinfo`,
//!   `profile-update-interval`, `profile-web-page-url`), the metadata they
//!   leave on a profile, and the automatic update policy.
//! - [`profile_array`]: naming and following the profiles of a JSON array of
//!   configs.
//! - [`content`]: the save flow of a profile's configuration (edits and
//!   subscription updates: revision check, atomic write, rollback), with the
//!   cores' own work behind the platform's [`content::Kernel`].
//! - [`api`]: the JSON call surface for a platform host (the Kotlin service
//!   layer on Android reaches it over JNI).
//!
//! Routing rules, backups, WebDAV and update checks follow in later phase-2
//! slices (docs/architecture/phase2.md).

pub mod api;
pub mod content;
pub mod legacy;
mod parcel;
pub mod preferences;
pub mod profile_array;
pub mod storage;
pub mod subscription;

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
