# core/

fk-box's own Go module (`github.com/koko123as/fk-box/core`): the host process
`fk-core` of [docs/kernel-isolation.md](../docs/kernel-isolation.md). It never
imports a kernel (sing-box, Xray, mihomo); `tools/check_kernel_isolation.py`
fails CI when `go list -deps` finds one.

| Path | What it is |
| --- | --- |
| `cmd/fk-core` | the executable, shipped in the APK as `libfkcore.so` and run from `nativeLibraryDir` |
| `protocol` | JSON-lines framing in both directions (requests, events, reverse calls) and descriptor passing (SCM_RIGHTS) |
| `rpc` | calls Go functions and object methods by name with JSON arguments (what gomobile bound into libbox.aar before) |
| `fk` | the kernel-independent fk-box code ported from [legacy/core](../legacy/core/README.md) (profile store, profile checks and conversion, routing, geo data, subscriptions, WebDAV, backups, UI protocol methods, log stream, LAN proxy, direct egress probe); `fk.Register` lists what fk-core answers by name |
| `internal/clashconvert` | mihomo's share-link converter, vendored (see below) |
| `tools/xrayshare` | its own module: the official libXray share-link converter as `libxrayshare.so` (decision D2) |

Built by [tools/build_kernels.sh](../tools/build_kernels.sh) together with the
official kernels; tested by the `kernels` job of
[newui.yml](../.github/workflows/newui.yml).

The multi-kernel libbox overlay this module replaces lives in
[legacy/core](../legacy/core/README.md) until the migration's last phase
removes it.

## Vendored code

`internal/clashconvert` is a copy of mihomo's `common/convert` package
(github.com/metacubex/mihomo, tag `v1.19.34`, path `common/convert`,
GPL-3.0), so fk-core converts share links to mihomo proxies exactly as mihomo
does without importing it:

| File | Origin | Edits |
| --- | --- | --- |
| `base64.go` | `common/convert/base64.go` | package name |
| `converter.go` | `common/convert/converter.go` | package name; `log.Warnln` -> `logWarnln` |
| `util.go` | `common/convert/util.go` | package name; `common/utils.NewUUIDV4`, `metacubex/randv2`, `metacubex/http`, `sing-shadowsocks/shadowimpl` -> stdlib and `local.go` |
| `v.go` | `common/convert/v.go` | package name |
| `converter_test.go` | `common/convert/converter_test.go` | package path; the `adapter.ParseProxy` checks dropped |
| `local.go` | fk-box | the replacements above (`fetchShadowsocksMethod` repeats sing-shadowsocks v0.2.13's method and key checks) |

`tools/bump_core.py` does not update it; compare it with the new tag when
mihomo is bumped.
