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
     `SessionSlot` are gone.
   - **4e3a** the Taildrop inbox: mobile-ffi `taildrop.rs` keeps which
     endpoint the page shows, opens and closes the host subscription with
     the tool status target (forgetting the endpoint when the session
     changes or its service goes away), holds the files and checks the file
     commands. `TaildropViewModel` and `StreamSubscription` are gone; the
     outbound list follows the `toolStatus` target too.
   - **4e3b** `BoxBridge` checks and the `BoxService` start decisions:
     mobile-ffi `bridge.rs` runs before a command reaches the host (required
     arguments and their defaults, what a remote session or a starting
     service rules out, the LAN proxy port and URL checks, whether a setting
     or routing change restarts the running service, the geo reschedule,
     web profile links answered without the host) and combines the pages
     that keep the traffic, connection and log streams open into one
     `pages.visible` host call; it also turns libbox's line lists (routing
     targets, preview warnings) into arrays. mobile-ffi `service.rs`
     (`service.*` in `api.rs`) shapes the routing status and decides its
     notice, the geo data notice and the per-app package lists for
     `bg/BoxService.kt`. Kotlin keeps the Android calls only.
   - **4e3c** `database/` wrappers (awaiting a decision).
   - **4i** the `bg/` service host, in the shape of Clash-of-Rust's
     `TunnelService`, without touching the kernel:
     - **4i1** the start and stop state machine: mobile-ffi `lifecycle.rs`
       (`lifecycle.*` in `api.rs`) answers what a start request does in each
       service state (start, restart once stopped, or only repeat
       `startForeground`), when a stop goes ahead and what follows it, the
       empty-configuration check, how a failed step is alerted, whether a
       failed reload falls back to the last started configuration, the Wi-Fi
       permission, the alert text a page shows, and the foreground
       notification's title, text and traffic line (and whether it shows).
       `bg/BoxService.kt`, `bg/ServiceNotification.kt` and `BoxActivity` keep
       the state, the libbox calls and the Android notification calls.
6. **4f** `apps/android/core` as its own Go module.
7. **4g** cleanup, docs, release APK.

4d and 4f change kernel communication and packaging; they need a device pass
on top of CI.
