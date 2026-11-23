# Phase 4: Clash-of-Rust layout, thinnest Kotlin host

Goal: the repository follows the layering of
[Fu021/Clash-of-Rust](https://github.com/Fu021/Clash-of-Rust) (structure only,
no code reuse), and the Kotlin host keeps only what must be Android code: its
native pages and the system capabilities.

## Target layout

| Layer | Role |
|---|---|
| `crates/client-core` | Platform-neutral business model, grouped by domain: `config/`, `connection/`, `controller/`, `geodata/`, `rules/`, `storage/`, `subscription/`, `update/`, `tools/`, plus the `api` call surface. |
| `crates/client-runtime` | Talks to the kernel directly (libbox command server): status, groups, connections, logs, clash mode, remote monitor, driven by `controller::sessions`. |
| `crates/mobile-ffi` | Platform-neutral mobile session: the host contract (`NativeHost`: request/notify into the platform), command dispatch, session orchestration. No JNI, Slint or Android SDK. |
| `crates/client-ui` | Slint only: `ui/` split into `components/`, `icons/`, `models/`, `pages/`, `theme/`, `mobile/android/`; Rust keeps fonts and model types. |
| `apps/android/native` | Android cdylib: JNI, presentation/page controllers, session worker. |
| `apps/android/app` | Kotlin host: Activity, VpnService/foreground service, notifications, tiles, permissions, app list and icons, files, Keystore, Shizuku, WorkManager, and the native pages (terminal, QR scanner, OpenConnect WebView, editor). |
| `apps/android/core` | Go kernel bridge as its own module (`go.mod`), using the upstream exported API instead of overlaying the sing-box tree. |

## Slices

1. **4a** layout: client-core domain directories (old paths re-exported),
   `[workspace.package]`/`[workspace.dependencies]`, client-ui `ui/` layers.
   Files move; behaviour does not change.
2. **4b** `mobile-ffi`: host contract, `api` dispatch and session orchestration.
3. **4c** page logic out of client-ui into `apps/android/native` (shared parts
   into `mobile-ffi`); client-ui keeps Slint and models.
4. **4d** `client-runtime`: Rust connects to the libbox command server; the
   Kotlin `backend/` view models go.
5. **4e** host slimming: `BoxBridge`, the wire classes, the `database/`
   wrappers and `BoxService` business flow move to Rust.
6. **4f** `apps/android/core` as its own Go module.
7. **4g** cleanup, docs, release APK.

4d and 4f change kernel communication and packaging; they need a device pass
on top of CI.
