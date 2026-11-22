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
//! - [`routing`]: the per-core routing rule model, its validation and its
//!   stored and wire forms (compiling a set for a core stays in the kernel
//!   layer).
//! - [`geo`]: the geo resource policy (which files a core needs, bundled
//!   copy versus installed file, automatic update schedule, user addresses).
//! - [`service_settings`]: the service settings snapshot the pages show,
//!   its validation and the write, with what the platform does around it.
//! - [`backup`]: `bbox-1` backups (packing, validation, restore with
//!   rollback).
//! - [`webdav`]: the WebDAV backup account and its upload/download flow
//!   (requests and password encryption by the platform).
//! - [`updates`]: the update check (GitHub release metadata, version codes).
//! - [`terminal`]: the terminal (Ghostty) settings.
//! - [`api`]: the JSON call surface for a platform host (the Kotlin service
//!   layer on Android reaches it over JNI).
//!
//! The interface controllers follow in a later phase-2 slice
//! (docs/architecture/phase2.md).

pub mod api;
pub mod backup;
mod codec;
pub mod content;
pub mod geo;
pub mod legacy;
mod parcel;
pub mod preferences;
pub mod profile_array;
pub mod routing;
pub mod service_settings;
pub mod storage;
pub mod subscription;
pub mod terminal;
pub mod updates;
pub mod webdav;

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
