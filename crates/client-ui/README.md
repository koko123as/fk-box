# client-ui — FK-box UI in Rust + Slint

Crate `fkbox-client-ui` (formerly `ui-slint/`). The Go cores (sing-box / Xray /
mihomo via libbox) and the Kotlin service and bridge layer are reused unchanged;
only the UI is rewritten. The Android shell around it (`android_main`, the JNI
link, logcat) is `apps/android/native`, which builds `libfkui.so`; the UI reaches
the platform through the `Host` trait of `crates/client-core`.

- `BoxActivity` extends `GameActivity` (androidx.games 4.4.0) and loads `libfkui.so`
  (`android.app.lib_name` = `fkui`).
- Rust → Kotlin: `BoxActivity.slintCall(id, method, argsJson)` → `BoxBridge.slintDispatch`.
- Kotlin → Rust: `SlintHost.nativeReply(id, ok, json)` and `SlintHost.nativeEvent(json)`
  (BoxBridge events: dashboard, profiles, groups, rates, logs, error).
- Build: `app:preBuild` runs `cargo ndk -t arm64-v8a -P 24 build --release` in `apps/android/native`
  (needs Rust stable, `aarch64-linux-android`, cargo-ndk, `ANDROID_NDK_HOME`).
  `-PskipRustUi=true` skips it. Only arm64-v8a is built.

Phase 1 pages: Home (start/stop, profile, rates, uptime, mode), Proxies (groups,
select, URL test), Profiles (select, update, delete, add subscription URL), Logs.
The Compose sources stay in the tree as reference until every page is ported.

Slint 1.19 (`backdrop-blur` for the glass surfaces); the Skia renderer and the
Android backend are vendored with FK-box patches (`vendor/*/FKBOX-PATCH.md`,
`[patch.crates-io]` in the root Cargo.toml). Host screenshots: `cargo run -p fkbox-client-ui --features shot
--example shot -- OUT step...` (software renderer), or `--features shot-skia` with
`SHOT_RENDERER=skia` to draw with Skia as on Android (the blur shows only there).

Slint 1.19's prebuilt Skia binaries need NDK r30 or newer. The Android build
gives cargo-ndk `FKUI_NDK_HOME` (NDK 30.0.16248370 in CI, set by the
build-libbox action) while the core keeps NDK 28; with an older NDK libfkui.so
links but leaves `std::__ndk1::__hash_memory` unresolved and the app closes at
launch. `scripts/verify_release.py` rejects such a library.
