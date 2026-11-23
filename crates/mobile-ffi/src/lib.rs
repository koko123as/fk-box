//! FK-box mobile session, shared by the mobile platform shells
//! (`apps/android/native` today). It sits between the platform-neutral
//! business layer (`fkbox-client-core`) and a shell's FFI glue, and holds no
//! JNI, Slint or Android SDK code:
//!
//! - [`NativeHost`]: what the UI needs from the platform, one asynchronous
//!   call into the host app.
//! - [`session`]: the process's client store and its one-time imports of the
//!   earlier Android storage.
//! - [`api`]: the JSON call surface the platform host calls (method + JSON
//!   args, answered with JSON).
//! - [`sessions`]: which libbox command subscriptions run, and to which
//!   target (the host keeps the clients).
//! - [`runtime`]: what the UI makes of the forwarded libbox command streams
//!   (dashboard, groups, connections, logs; phase 4d).
//! - [`diagnostics`]: the STUN, network quality and Tailscale ping runs
//!   (phase 4e).
//! - [`tool_status`]: the Tailscale, OpenConnect, OpenVPN and USBIP status
//!   lists, and the checks of commands that name their entries (phase 4e2).
//! - [`taildrop`]: the Taildrop inbox (phase 4e3a).
//! - [`bridge`]: the host command checks, argument defaults and stream page
//!   visibility the Kotlin `BoxBridge` kept (phase 4e3b).
//! - [`service`]: the routing status, geo notice and per-app package
//!   decisions of the Kotlin `BoxService` start (phase 4e3b).
//! - [`lifecycle`]: the `BoxService` start and stop state machine, alerts
//!   and foreground notification content (phase 4i1).
//! - [`tun`]: what the VPN interface is built with (phase 4i2).
//!
//! Layout follows docs/architecture/phase4.md.

pub mod api;
pub mod bridge;
pub mod diagnostics;
pub mod lifecycle;
pub mod runtime;
pub mod service;
pub mod taildrop;
pub mod tool_status;
pub mod tun;
pub mod session;
pub mod sessions;

/// What the UI needs from the platform: one asynchronous call into the host
/// app. The UI sends `method` with JSON `args` under the request `id`; the
/// host answers later through the UI's reply entry point with the same `id`
/// (on Android, `SlintHost.nativeReply`), and pushes unsolicited events
/// through its event entry point (`SlintHost.nativeEvent`).
///
/// `invoke` only delivers the call. An `Err` means it could not be delivered
/// (no host attached, the host threw); the message is shown to the user.
pub trait NativeHost: Send + Sync {
    fn invoke(&self, id: i64, method: &str, args: &str) -> Result<(), String>;
}
