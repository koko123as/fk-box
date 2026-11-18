# Slint UI manifest

Map of the 47 Jetpack Compose files under `app/src/main/java/io/nekohasekai/sfa/compose` on `main`
to their Rust + Slint replacements on `slint-ui` (`ui-slint/`). Native Android pieces that the
Compose UI hosted (QR camera, Ghostty terminal surface, OpenConnect WebView) are now Kotlin
Activities in `app/src/main/java/io/nekohasekai/sfa/bridge/NativePages.kt`, opened from Rust
through `BoxActivity.slintCall`.

The Compose sources themselves were deleted from `slint-ui` on 2026-11-13 (they remain on `main`);
`compose/` keeps only `MainActivity`, `ShareActivity` and `qr/`. The Simplified Chinese table
`ui-slint/i18n/zh/LC_MESSAGES/fkui.po` is now edited by hand (it was generated from `BoxStrings.kt`).

Status: **done** = ported feature-for-feature (not yet verified on device);
**partial** = ported, with the listed differences from the Compose original;
**kept** = still Kotlin by design (Activity entry points / camera decoding);
**todo** = not ported.

| # | Compose file (main) | Rust / Slint replacement | Status | Notes |
|---|---|---|---|---|
| 1 | BoxApp.kt | `ui/app.slint`, `ui/globals.slint`, `src/nav.rs`, `src/theme_apply.rs` | done | Frame, bottom bar / rail, routes, back handling, snackbar |
| 2 | BoxController.kt | `src/app.rs`, `src/host.rs`, `src/format.rs`, `ui/data.slint`; Kotlin `bridge/SlintHost.kt` | done | State projection + JNI dispatch (`slintCall` / `nativeReply`) |
| 3 | BoxStrings.kt | `src/i18n.rs` | done | 711 zh strings |
| 4 | MainActivity.kt | Kotlin `bridge/BoxActivity.kt` (GameActivity, `lib_name=fkui`) | kept | Entry Activity stays Kotlin |
| 5 | ShareActivity.kt | Kotlin `ShareActivity : BoxActivity` + `src/pages/tools.rs::open_share` (opened from `profiles.rs` intent handling) | done | ACTION_SEND / SEND_MULTIPLE → Taildrop share page |
| 6 | common/IpQuality.kt | `src/pages/egress.rs` | done | Egress IP / IP quality |
| 7 | pages/AppearancePage.kt | `src/pages/appearance.rs`, `ui/pages/appearance.slint` | partial | Font lists without rendered preview; text scale saves on slider release |
| 8 | pages/Configuration.kt | `src/pages/config.rs`, `src/forms.rs` | done | Profile/config forms |
| 9 | pages/DashboardComponents.kt | `ui/pages/dashboard.slint` | done | |
| 10 | pages/DashboardEditor.kt | `src/pages/dashboard.rs`, `ui/pages/dashboard.slint` | done | |
| 11 | pages/DashboardGrid.kt | `src/pages/dashboard.rs`, `ui/pages/dashboard.slint` | done | |
| 12 | pages/DashboardPage.kt | `src/pages/dashboard.rs`, `ui/pages/dashboard.slint` | done | All cards, memory, LAN IP, LAN proxy |
| 13 | pages/HomePage.kt | `ui/home.slint`, `ui/app.slint` | done | |
| 14 | pages/ProfileEditor.kt | `src/pages/profiles.rs`, `ui/pages/profiles.slint` | done | Code editor |
| 15 | pages/ProfilesPage.kt | `src/pages/profiles.rs`, `ui/pages/profiles.slint` | done | Grid, add menu/page, reorder, details, QR; scan → native `ScanActivity` |
| 16 | pages/ProxiesPage.kt | `src/pages/proxies.rs`, `ui/pages/proxies.slint` | done | |
| 17 | pages/RequestsPage.kt | `src/pages/requests.rs`, `src/records.rs`, `ui/pages/requests.slint` | partial | Logs follow the bottom instead of pausing; request body has no Format JSON toggle / paging |
| 18 | pages/ResourcesPage.kt | `src/pages/resources.rs` | done | Geo files |
| 19 | pages/RoutingPage.kt | `src/pages/routing.rs` | partial | Route chips stack vertically instead of flowing |
| 20 | pages/SettingsPages.kt | `src/pages/settings.rs` | partial | Access-control unsaved-changes dialog lacks Save; app rows rendered as grouped cards |
| 21 | pages/StatusPanel.kt | `src/pages/dashboard.rs`, `src/pages/egress.rs`, `ui/pages/dashboard.slint` | done | |
| 22 | pages/SubscriptionInfoView.kt | `src/pages/profiles.rs`, `src/rows.rs` (usage row), `ui/pages/rows.slint` | done | |
| 23 | pages/TerminalPage.kt | `src/pages/terminal.rs`; Kotlin `TerminalActivity` (Ghostty view) | partial | Terminal surface opens as a native Activity over Slint instead of inline; font list without rendered preview; font size is a number field + "increase" row instead of a −/+ stepper |
| 24 | pages/ToolPages.kt | `src/pages/tools.rs`, `ui/pages/tools.slint`; Kotlin `BrowserActivity` | partial | Peer online state is a flag icon; OpenConnect sign-in WebView is a native Activity |
| 25 | pages/ToolsPage.kt | `src/pages/tools.rs`, `ui/pages/tools.slint` | done | |
| 26 | qr/QRCodeSmartCrop.kt | Kotlin, used by `NativePages.kt` `ScanActivity` | kept | Camera frame decoding stays Kotlin |
| 27 | qr/ZxingQRCodeAnalyzer.kt | Kotlin, used by `NativePages.kt` `ScanActivity` | kept | Camera frame decoding stays Kotlin |
| 28 | theme/Color.kt | `src/theme.rs`, `ui/theme.slint` | done | MCU seed schemes |
| 29 | theme/Shape.kt | `ui/theme.slint` | done | |
| 30 | theme/Theme.kt | `src/theme.rs`, `src/theme_apply.rs`, `ui/theme.slint` | done | |
| 31 | theme/Type.kt | `ui/theme.slint`, `ui/fonts` | done | |
| 32 | ui/AppIcons.kt | `ui/icons.slint`, `ui/kit.slint` (Material icon font) + Kotlin `appIcon` dispatch | done | |
| 33 | ui/Card.kt | `ui/kit.slint`, `ui/pages/rows.slint` | done | |
| 34 | ui/Dialog.kt | `ui/app.slint`, `src/nav.rs` | done | |
| 35 | ui/Forms.kt | `src/forms.rs`, `ui/kit.slint`, `ui/pages/rows.slint` | done | |
| 36 | ui/Kit.kt | `ui/kit.slint`, `ui/pages/rows.slint` | done | |
| 37 | ui/Layout.kt | `ui/kit.slint`, `ui/globals.slint`, `src/rows.rs` | done | |
| 38 | ui/LineChart.kt | `ui/pages/dashboard.slint`, `src/pages/dashboard.rs` | done | |
| 39 | ui/Navigator.kt | `src/nav.rs`, `ui/routes.slint` | done | |
| 40 | ui/NullStatus.kt | `ui/kit.slint` | done | |
| 41 | ui/Popup.kt | `ui/app.slint`, `src/nav.rs` | done | Menus |
| 42 | ui/QrCode.kt | `src/pages/profiles.rs` (qrcodegen), `ui/pages/profiles.slint` | done | |
| 43 | ui/QuickEdit.kt | `src/forms.rs`, `ui/pages/rows.slint` | done | |
| 44 | ui/RecordFilter.kt | `src/records.rs` | done | |
| 45 | ui/Records.kt | `src/records.rs`, `ui/pages/requests.slint` | done | |
| 46 | ui/Scaffold.kt | `src/rows.rs`, `ui/kit.slint`, `ui/globals.slint` | done | Title bar, actions, foot bar |
| 47 | ui/Sortable.kt | `src/rows.rs` (sort rows), `src/pages/profiles.rs` | done | Reorder by drag handles / sort rows |

## Known non-1:1 items

1. Access control: the unsaved-changes dialog has no Save button (only discard / cancel).
2. Access control: app rows are drawn as grouped cards rather than flat list rows.
3. Appearance and terminal font lists show names only, without a rendered preview in each font.
4. Text scale is saved when the slider is released, not continuously.
5. Routing: route chips stack instead of wrapping in a flow row.
6. Logs auto-follow the bottom instead of pausing when scrolled up.
7. Peer online state is shown as a flag icon.
8. Request body view has no Format JSON toggle and no paging.
9. Terminal font size: number field plus an "Increase font size" row instead of the −/+ stepper.
10. Terminal surface, QR scanner and OpenConnect sign-in browser are separate native Activities over the Slint window instead of inline Compose views.

## Deliberate departures from the Compose UI (2026-11-13)

- Phones get a floating navigation bar (branch `ui/m3-rewrite`, Slint 1.19): a 72 dp pill
  16 dp from the sides and 12 dp above the gesture inset, an opaque surface-container with a
  level 2 shadow; home pages scroll their content under it and pad its end by `Nav.home-bottom`.
  The top app bar turns to the opaque surface-container role once content scrolls under it; menus,
  dialogs and the rows' foot bar are opaque tonal surfaces too (`ui/kit.slint` `Surface`). Nothing
  uses `backdrop-blur` (the earlier glass surfaces were removed for scrolling cost).
  The visual layer otherwise follows Material Design 3 (see flclash.md "Material Design 3 界面约定").
- Routing rule editor: the Apps field opens the installed-app list used by Access control
  (search, system apps toggle, selected first) instead of a package-name text box; hidden for
  Xray rules, which cannot match apps.
- Logs default to Warn and a level now shows itself and everything more severe; the choice is
  kept while the app runs.
- Most explanatory footers under rows were removed. About shows the version, update check, the
  three cores with their versions, source code and licenses; the "based on" and "predecessor"
  lines are gone (credits stay in README.md and the license list).

## Fonts and rendering (slint-ui only)

- Text uses the device's own fonts like Compose did: `bridge/SystemFonts.kt` asks Android's text
  stack (TextRunShaper, API 31+) which files it draws sans-serif, Chinese and monospace with and
  writes `files/fkui-fonts.json`; `src/fonts.rs` registers them memory-mapped as Slint's
  sans-serif / system-ui / monospace families. Below API 31 fontique's /system/fonts defaults apply.
- JetBrains Mono is no longer bundled (neither in `ui-slint/ui/fonts` nor `res/font`); code and
  terminal text use the system monospace face. Compose's `JetBrainsMono` now aliases
  `FontFamily.Monospace`.
- Slint 1.19.0 (2026-11-16; `slint`, `slint-build`, `i-slint-core`, `i-slint-common` pinned with `=`).
  The UI does not use 1.19's `backdrop-blur` (tools/verify_newui.py forbids it). Edition /
  project-file support is not adopted (the crate keeps the default edition).
- `ui-slint/vendor/i-slint-renderer-skia` (1.19.0 + FKBOX-PATCH.md): a 32 MB GPU resource cache and
  the purge hook. The font-data patch of the 1.18.1 copy is retired: upstream 1.19 builds typefaces
  from the font blob without copying it (`Data::new_with_owner`).
- `ui-slint/vendor/i-slint-backend-android-activity` (1.19.0 + FKBOX-PATCH.md, rebased by a
  three-way merge; the Java helper hunk re-applied by hand to 1.19's rewritten helper): text is edited
  in the app's own EditText (`src/native_ime.rs`, `bridge/NativeEditor.kt`), so the GameActivity
  `input_method_request` is empty; text that GameActivity's own input connection still reports is
  converted from Java UTF-16 indices (`fkbox_ime.rs`, host tests `src/ime_tests.rs`) instead of
  panicking; the IME action key is Return; the Java `SlintInputView` stays unfocusable unless
  `show_keyboard` asks for it; plus the touch observer and the Back release (table below).
  `BoxActivity.dispatchKeyEvent` orders the editable's selection and drops a key that still throws
  IndexOutOfBoundsException.
- `ui-slint/vendor/i-slint-core` (1.19.0 + FKBOX-PATCH.md): `SwipeGestureHandler` forwards the press
  at once instead of delaying it, so a fast flick reaches the home pages' `Flickable` and scrolls (upstream the
  held press was dropped when the pager locked out, and the list stayed still).
- Host screenshot harness: `cargo build --features shot --example shot` (software renderer,
  fixture data; never part of the Android build). `--features shot-skia` with `SHOT_RENDERER=skia`
  draws with the vendored Skia renderer on the CPU instead. Its software renderer measures wrapped Chinese
  text with one font, so wrapped CJK footers show one line there; the Skia renderer on Android
  measures with the same font fallback it draws with.

## Vendored Slint patches and upstream status (checked 2026-11-19)

Each vendored crate was diffed against its crates.io 1.19.0 source, which is unchanged apart from the
hunks below. Upstream is slint-ui/slint `master` at `1c002a9d8` (2026-11-18). 1.19.0 is the newest
release, so no newer release could replace a patch yet. Kinds:
**upstreamable** = a general Slint bug or gap, worth a PR;
**fixed upstream** = can be dropped on the next upgrade;
**FK-box-specific** = only makes sense with FK-box's own design.

| # | Crate / file | Patch | Upstream state | Kind |
|---|---|---|---|---|
| C1 | i-slint-core `items/input_items.rs` | `SwipeGestureHandler` forwards the press at once (`ForwardAndInterceptGrab`), not after 100 ms, so a fast flick reaches the home pages' `Flickable` | Not fixed on master. Issue #13120 (delayed press lost) is related but different. The FK-box form contradicts the upstream test `swipegesturehandler.slint` (`ta_pressed` stays false during the delay), so it can't go upstream as is. A replacement patch is drafted: a move past the drag threshold that the delaying item doesn't take replays the held press at once (`input.rs`), so the delay stays for taps. If upstream takes it, drop C1. | upstreamable (as the drafted fix) |
| S1 | i-slint-renderer-skia `opengl_surface.rs` | GrDirectContext resource budget 32 MB (Skia's default is 256 MB) | Not on master: no `set_resource_cache_limit` anywhere | FK-box-specific tuning. Upstreamable only as a setting (public API, discuss first) |
| S2 | i-slint-renderer-skia `lib.rs` | `fkbox_request_purge()`: the next frame clears the renderer caches, frees GPU resources and purges Skia's caches | Partly upstream already: `SkiaRenderer::free_gpu_resources()` is public, and the Android backend calls it on `LowMemory`. Slint also drops a destroyed item's cache entries (`free_graphics_resources`). There is no public way to clear the renderer caches, and the app can't reach the backend's renderer. | FK-box-specific. Possible feature request: "trim memory" API / map `onTrimMemory` |
| S0 | i-slint-renderer-skia `font_cache.rs` | (retired) per-face `Data::new_copy` of whole font files | Fixed in 1.19.0 (`Data::new_with_owner`) | fixed upstream (already dropped) |
| A1 | android-activity `androidwindowadapter.rs` | GameActivity `input_method_request` is empty (no GameTextInput state, no keyboard requests) | Upstream still sends Slint's UTF-8 byte offsets as GameTextInput's Java (UTF-16) indices, with an unordered selection, on master. Upstream's maintained IME path is NativeActivity + Java helper (#14216, handles, magnifier). | FK-box-specific (FK-box edits in its own EditText). The underlying GameActivity offset bug is upstreamable |
| A2 | android-activity `fkbox_ime.rs` + `InputEvent::TextEvent` | UTF-16 indices to char-boundary byte offsets, composing region taken out, positions after it moved back by its length (upstream panics slicing UTF-8 at UTF-16 indices and moves them the wrong way) | Same code on master | upstreamable (bug fix, GameActivity only) |
| A3 | android-activity `InputEvent::TextAction` | IME action key (Done) dispatched as Return so `accepted` runs | Master ignores `TextAction` (`Unhandled`) | upstreamable (small) |
| A4 | android-activity `java/SlintAndroidJavaHelper.java` | `SlintInputView` not focusable / not a text editor until `show_keyboard` | Master still adds a focusable full-window `SlintInputView` under GameActivity too (helper heavily rewritten after 1.19.0, so this hunk needs a manual re-apply next time) | upstreamable in spirit ("don't take focus under GameActivity"), FK-box-specific in form |
| A5 | android-activity `lib.rs` + touch dispatch | `fkbox_set_touch_observer`: FK-box sees each touch before Slint and locks the axis (`src/gesture.rs`) | No equivalent upstream. `SwipeGestureHandler` takes a drag with dx > 8 and dx > abs(dy)/2, so a diagonal scroll can go to the pager. The C1 replacement doesn't change that. | FK-box-specific |
| A6 | android-activity `InputEvent::KeyEvent` | A Back release whose press the UI took is `Handled`, so GameActivity doesn't finish the activity | Master's OnBackInvokedCallback path (API 34+) already counts press or release as handled. The KeyEvent path (below API 34, or without `enableOnBackInvokedCallback`) still reports each event alone. Open issue #14962 is about Back control. | upstreamable (align the KeyEvent path with the OnBackInvoked path) |

Next upgrade: master changed the Android backend after 1.19.0 (NativeActivity IME client tracking,
wgpu fallback, a much larger Java helper diff), so A1-A6 need a fresh three-way merge. None of those
changes fixes an FK-box hunk. `ui-slint/Cargo.toml`'s `[patch.crates-io]` comment still describes
retired patches (font data, UTF-16 state exchange, echo suppression, explicit keyboard requests).

## Layout checks (screenshot harness, 2026-11-13)

Checked at 411×914 dp: dashboard (light/dark), proxies, profiles, profile details, add profile,
tools, requests, resources, logs, terminal, remote control, appearance, access control, routing,
backup, general, service. Fixed: icons/switches/radios/chevrons that sat at the top of their rows
(a layout ignores a child's own `y`, now wrapped in kit `Mid`), empty-state icon and splash logo
not centred, custom ListItem rows (usage meter) squeezed to half width by the empty title
column, segmented buttons (equal widths, rounded ends), dashboard profile row spacing.

## Not verified

Everything above is compiled (host `cargo check` + CI Android build) but has not been run on a device.
