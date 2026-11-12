# ui-slint — FK-box UI in Rust + Slint

Branch `ui/rust-slint`. The Go cores (sing-box / Xray / mihomo via libbox) and the
Kotlin service and bridge layer are reused unchanged; only the UI is rewritten.

- `BoxActivity` extends `GameActivity` (androidx.games 4.4.0) and loads `libfkui.so`
  (`android.app.lib_name` = `fkui`).
- Rust → Kotlin: `BoxActivity.slintCall(id, method, argsJson)` → `BoxBridge.slintDispatch`.
- Kotlin → Rust: `SlintHost.nativeReply(id, ok, json)` and `SlintHost.nativeEvent(json)`
  (BoxBridge events: dashboard, profiles, groups, rates, logs, error).
- Build: `app:preBuild` runs `cargo ndk -t arm64-v8a -P 24 build --release`
  (needs Rust stable, `aarch64-linux-android`, cargo-ndk, `ANDROID_NDK_HOME`).
  `-PskipRustUi=true` skips it. Only arm64-v8a is built.

Phase 1 pages: Home (start/stop, profile, rates, uptime, mode), Proxies (groups,
select, URL test), Profiles (select, update, delete, add subscription URL), Logs.
The Compose sources stay in the tree as reference until every page is ported.
