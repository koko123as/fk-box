# fkbox-client-core

Platform-neutral client layer of FK-box, shared by every platform shell.

Phase 1 (this layout change) holds only the `Host` trait: what the Slint UI in
`crates/client-ui` needs from the platform. `apps/android/native` implements it
over JNI (`BoxActivity.slintCall`) and registers it with the UI before the
event loop starts. No business logic lives here yet and nothing behaves
differently.

Phase 2 moves the platform-neutral business logic out of the Kotlin host
(`apps/android/app`) into this crate: subscriptions, profile storage, settings,
routing rules, update checks and WebDAV backup, with a migration of the
existing Room data. Phase 3 slims `apps/android/core` (libbox) down to the pure
kernel layer (the three cores and config conversion).
