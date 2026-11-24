# Single kernel + Rust brain on Android (stage 6)

Stage 6a moves the Android app from the three-core `libbox.aar` (sing-box +
Xray + mihomo, each a full kernel) to the single-kernel path built in stages
0–5: one sing-box kernel (the Go host, [brain-host.md](brain-host.md)) driven
by the Rust brain ([brain-ffi.md](brain-ffi.md), [brain-config.md](brain-config.md),
[brain-dns.md](brain-dns.md), [brain-groups.md](brain-groups.md)).
Stage 6b removes the old kernels' code that 6a leaves unused.

## Packaging

- `apps/android/fkhost/mobile` is the gomobile face of the host (Java package
  `io.fkbox.mobile`). `.github/actions/build-fkhost` builds
  `libfkbox_brain_ffi.a` for `aarch64-linux-android` (cargo-ndk, API 24, NDK
  28.0.13004108), links it into the host by cgo (`CGO_LDFLAGS=-L…`,
  `brainffi` adds `-llog` on Android) and binds
  `apps/android/app/libs/fkhost.aar` (`jni/arm64-v8a/libfkhost.so`) with
  sagernet gomobile v0.1.13. **arm64-v8a only.** The AAR is cached on the
  host module, the brain, client-core, the Cargo lock, the pins of
  `version.properties` and the action.
- `apps/android/app/build.gradle.kts` depends on `libs/fkhost.aar` instead of
  `libs/libbox.aar`; `scripts/verify_release.py` requires `libfkhost.so` and
  `libfkui.so` in the APK and refuses a packaged `libbox.so`.
- Workflows on the new kernel: Build APK on push, Build Debug APK, Build
  Release APK (dispatched on stacked branches with `publish_release=false`),
  Android regression and smoke. *Core and source validation*
  (`newui.yml`) runs the source guards and, since 6b, the kept sing-tun
  patch's test (below) instead of the deleted three-core sources.

### Build tags

`with_quic,with_wireguard,with_utls,with_gvisor` — what the stage 2 mapping
actually emits:

| tag | needed for |
| --- | --- |
| `with_quic` | Hysteria, Hysteria2, TUIC outbounds (sing-quic) |
| `with_utls` | uTLS client fingerprints and REALITY |
| `with_wireguard` | the WireGuard endpoint |
| `with_gvisor` | the TUN gVisor/mixed stack |

Not built: `with_clash_api` (replaced by `fkb_brain_groups` /
`fkb_brain_group_select` / `fkb_brain_group_test`), `with_tailscale`
(endpoint refused in stage 2), `with_naive_outbound` (needs cronet),
`with_embedded_tor` (tor would be a subprocess or a large embedded library),
`with_acme`, `with_dhcp`, `with_v2ray_api`, `with_ccm`/`with_ocm`. A sing-box
JSON profile naming a `naive` or `tor` outbound passes the brain's check but
fails when the kernel starts ("not included in this build").

## Process and data

- `libfkhost.so` (the Go runtime) is loaded when the service starts, never at
  app start (`bg/FkHost.kt`); the UI asks for groups, stats and logs only once
  it is loaded.
- Files the kernel keeps (all under the app's `files/` directory, kept across
  upgrades, no migration needed):
  - `files/fkbox/fakeip.json` — fake-ip allocations (profile option
    `dns.fake_ip_store`).
  - `files/fkbox/groups/<profile id>.json` — manual group selections per
    profile (`group_store`).
  - `files/fkbox/host/` — the host's working directory.
  - Geo data stays where libbox kept it (`GeoResources.dataDir()`), passed as
    `geosite` / `geoip` / `mmdb` options and loaded only when a rule uses it.
- The brain imports the profile itself at every start and reload: profiles
  are stored as written (Clash/mihomo YAML, sing-box JSON, share links,
  Base64 link lists) and checked on save by mobile-ffi `brain.check` (import
  plus configuration generation, as the service would start it).

## Service

- **VpnService only.** `bg/VPNService.kt` opens the TUN for the host and
  closes the previous descriptor before a new one (TUNSETIFF busy otherwise).
  `bg/AndroidPlatform.kt` implements `io.fkbox.mobile.Platform`: protect,
  the TUN descriptor, the connection owner and the network state, called from
  kernel goroutines. No CommandServer, no local listener, no subprocess.
- A running service reloads in place (`BoxService.reloadRunning`, formerly the
  command client's `serviceReload`).

## Decisions (2026-11-24)

| Feature | Stage 6 | Data |
| --- | --- | --- |
| HTTP capture, MITM, HTTP rewriting | not on the new path; removed at runtime; the Requests page no longer offers the captured feed, the profile editor has no HTTP rewriting entry, the MITM certificate code is removed. May be redone in Rust later. | capture files, settings, Keystore CA and `experimental.http_rewrite` in profiles are left untouched |
| root auto-redirect (libsu root process) | not wired (conflicts with "no subprocess"); code kept (`RootServer` AIDL answers "unavailable"); service mode is VpnService only | settings kept |
| Tailscale (with Taildrop and Tailscale SSH), OpenVPN, OpenConnect | entries removed: they need the endpoints stage 2 refuses; the share target (`ShareActivity`, only Taildrop) and the sign-in browser page are removed | saved endpoints and sessions left untouched |
| Terminal | entry removed: it was the Tailscale SSH client (libbox `TailscaleSSHHandler`), i.e. it needs the refused Tailscale endpoint and the deleted libbox | Ghostty terminal settings kept |
| USB/IP | **re-checked, stays removed**: it is not a standalone Android feature but libbox's USB provider API (`Libbox.newUSBDeviceDescriptor`, `CommandClient.provideUSBDevices`, `USBIPServerStatus` streams) serving the kernel daemon's USB/IP server over the libbox command server (a local socket). The new host has no command server and no USB/IP service; bringing it back is new kernel work, not a restore | saved USB/IP data left untouched |
| Remote control, network quality, STUN test | entries removed (libbox command client and diagnostics services) | saved remote servers kept |
| LAN proxy (sing-box inbound on the LAN) | refused (no local listener) | profile content kept |
| sing-box binary profiles (`.bpf`, libbox decoder) | not read | — |

Wherever a choice could lose user data, the data is kept and only the entry
point is removed.

## Stage 6b: what was deleted

- The three-core libbox Go adapter (`apps/android/core/`: sing-box `experimental/libbox`
  additions, Xray and mihomo kernel start-up, Go rules/DNS/groups glue, Clash API
  use, HTTP capture/rewrite runtime `httpdebug`), its CI (`setup-core`,
  `build-libbox`) and patches/scripts: `libbox-no-legacy.patch`,
  `patch_multicore.py`, `patch_httpdebug.py`, `patch_dns_count.py`,
  `verify_multicore.py`, `verify_httpdebug.py`. `core_profile.go` (with its
  obfs parameter name bug) went with it.
- The kernel switch: Settings › Core shows the one kernel's version; the
  per-core switches and the per-profile core menu are gone. Their stored values
  (`core_*_enabled`, the profile's `core` column) are kept, so a downgrade
  still reads them. The Traffic routing page (app rules libbox compiled per
  core) is hidden; saved rules are kept.

### Migration of old profiles

`client-core` `config::brain::singbox::strip_retired` removes
`experimental.http_rewrite`, `experimental.http_debug` and
`experimental.clash_api` from what the brain imports (and `experimental`
when nothing else is left), with an `IgnoredSettings` notice. Only the
imported copy changes: the saved profile keeps them (no data loss; a
downgrade still runs them).

### sing-tun patch

`apps/android/core/sing-tun-android-ingress.patch` is the only patch left and
is kept on purpose. sing-tun's `redirect_ingress_discard_android.go:73` (at
the pinned `v0.9.7-0.20261023115453-611d83b7a57b`) checks the value size of
netd's ingress-discard BPF map and only accepts the 8-byte (two-interface)
AOSP layout; vendor builds with the 4-byte (one-interface) value make
auto-redirect fail. The patch accepts 4 and 8 bytes and keeps the interface
ownership check and the restore of the original value. Auto-redirect needs
root and is not wired on the single-kernel path, so `fkhost.aar` is built
without it; *Core and source validation* applies it to a scratch copy of the
module pinned by fkhost (`scripts/patch_sing_tun.py`, the replace is never
committed) and runs `TestAndroidIngressDiscard*`, so it stays ready when
auto-redirect returns. The script runs `git apply` inside that copy with
repository discovery stopped there: run from `apps/android/fkhost`, git would
take the patch paths relative to the fk-box top level and skip them.

## Not verified (stage 6a)

Nothing here ran on a real device: no real VpnService, no JNI call through
gomobile, no measured APK size or memory beyond what CI reports. The x86_64
emulators of *Android regression and smoke* run the arm64 APK through ARM
translation, which cannot run Go's start-up (MRS on ID registers), so the
native suite is an environment skip there, as it was for libbox.

Real-device checklist (PR body): upgrade from the libbox build keeps profiles,
settings and selections; the VPN starts at once; cold-start memory; downgrade
to the libbox build still opens the kept data.
