//! FK-box platform-neutral client layer.
//!
//! Phase 1 holds only [`Host`], the contract between the Slint UI
//! (`fkbox-client-ui`) and the platform shell that runs it
//! (`apps/android/native` on Android). Subscriptions, storage, settings,
//! routing rules, update checks and WebDAV move here in phase 2 (README.md).

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
