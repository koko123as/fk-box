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
| 20 | pages/SettingsPages.kt | `src/pages/settings.rs`, `ui/pages/settings.slint` | partial | Access-control unsaved-changes dialog lacks Save; app rows rendered as grouped cards |
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

- Phones get a floating navigation bar (FlClash's mobile layout): a rounded bar 16 px from the
  sides and 12 px above the gesture inset; home pages pad their content by `Nav.home-bottom`.
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
- `ui-slint/vendor/i-slint-renderer-skia` (1.18.1 + FKBOX-PATCH.md): font data shared without
  copies and a 32 MB GPU resource cache.
- `ui-slint/vendor/i-slint-backend-android-activity` (1.18.1 + FKBOX-PATCH.md): the GameActivity
  IME path exchanges text state with GameTextInput in UTF-16 with an ordered selection (upstream
  sent UTF-8 byte offsets, which crashed GameTextInput's backspace with Chinese text and panicked
  on composing regions), does not echo the IME's own state back (each echo restarted the IME and
  broke Chinese composition), sets the EditorInfo per field and shows the keyboard explicitly.
  Host tests: `src/ime_tests.rs`. `BoxActivity.dispatchKeyEvent` orders the editable's selection
  and drops a key that still throws IndexOutOfBoundsException.
- Host screenshot harness: `cargo build --features shot --example shot` (software renderer,
  fixture data; never part of the Android build). Its software renderer measures wrapped Chinese
  text with one font, so wrapped CJK footers show one line there; the Skia renderer on Android
  measures with the same font fallback it draws with.

## Layout checks (screenshot harness, 2026-11-13)

Checked at 411×914 dp: dashboard (light/dark), proxies, profiles, profile details, add profile,
tools, requests, resources, logs, terminal, remote control, appearance, access control, routing,
backup, general, service. Fixed: icons/switches/radios/chevrons that sat at the top of their rows
(a layout ignores a child's own `y`, now wrapped in kit `Mid`), empty-state icon and splash logo
not centred, custom ListItem rows (usage meter) squeezed to half width by the empty title
column, segmented buttons (equal widths, rounded ends), dashboard profile row spacing.

## Not verified

Everything above is compiled (host `cargo check` + CI Android build) but has not been run on a device.
