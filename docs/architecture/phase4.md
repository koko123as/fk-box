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
4. **4d** stream runtime (option B, chosen 2026-11-23): Kotlin keeps only the
   libbox command clients and forwards each callback raw (`bridge/BoxStreams.kt`);
   `crates/mobile-ffi/src/runtime.rs` holds the dashboard, groups, connection
   and log rules of the deleted `DashboardViewModel`, `GroupsViewModel`,
   `ConnectionsViewModel` and `LogViewModel`. A Rust gRPC client for the
   command server (option A) stays possible later.
5. **4e** host slimming: `BoxBridge`, the wire classes, the `database/`
   wrappers and `BoxService` business flow move to Rust, in steps:
   - **4e1** diagnostics: `crates/mobile-ffi/src/diagnostics.rs` runs STUN,
     network quality (with the metered-network confirmation) and Tailscale
     ping, and drops callbacks of cancelled or replaced runs; Kotlin's
     `bridge/BoxDiagnostics.kt` only starts and closes the libbox objects and
     forwards their callbacks raw. Losing the service or switching the remote
     target stops every run. `STUNTestViewModel`, `NetworkQualityViewModel`
     and `TailscalePingViewModel` are deleted.
   - **4e2** status subscriptions (Tailscale, OpenConnect, OpenVPN, USBIP).
     mobile-ffi `tool_status.rs` keeps the lists and checks commands that
     name a peer, exit node or USBIP server, adding the peer's details
     (`peerInfo`) for the host; `sessions.rs` plans the `toolStatus` target.
     Kotlin `bridge/BoxToolStatus.kt` opens the libbox subscriptions and
     forwards converted updates; the four status ViewModels and
     `SessionSlot` are gone. The Taildrop inbox stays for 4e3.
   - **4e3** `BoxBridge`, `database/` wrappers and the `BoxService` flow.
6. **4f** `apps/android/core` as its own Go module.
7. **4g** cleanup, docs, release APK.

4d and 4f change kernel communication and packaging; they need a device pass
on top of CI.
