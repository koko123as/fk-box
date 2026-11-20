# core/

fk-box's own Go module (`github.com/koko123as/fk-box/core`): the host process
`fk-core` of [docs/kernel-isolation.md](../docs/kernel-isolation.md). It never
imports a kernel (sing-box, Xray, mihomo); `tools/check_kernel_isolation.py`
fails CI when `go list -deps` finds one.

| Path | What it is |
| --- | --- |
| `cmd/fk-core` | the executable, shipped in the APK as `libfkcore.so` and run from `nativeLibraryDir` |
| `protocol` | JSON-lines framing in both directions (requests, events, reverse calls) and descriptor passing (SCM_RIGHTS) |
| `tools/xrayshare` | its own module: the official libXray share-link converter as `libxrayshare.so` (decision D2) |

Built by [tools/build_kernels.sh](../tools/build_kernels.sh) together with the
official kernels; tested by the `kernels` job of
[newui.yml](../.github/workflows/newui.yml).

The multi-kernel libbox overlay this module replaces lives in
[legacy/core](../legacy/core/README.md) until the migration's last phase
removes it.
