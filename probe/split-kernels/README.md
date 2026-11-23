# Split-kernel probe (throwaway)

Evidence for [docs/architecture/split-kernels.md](../../docs/architecture/split-kernels.md).
Not part of the app build; run by `.github/workflows/probe-split-kernels.yml`
(`workflow_dispatch` only).

- `include/fk_kernel.h`: the C ABI every kernel library exports.
- `singbox/`, `mihomo/`, `xray/`: one Go module each, requiring only the
  unpatched upstream kernel at its released version, plus the cgo shim.
- `check_deps.py`: compares the shim's build list with the kernel's own go.mod.
- `harness/harness.c`: dlopens one library, starts it, fetches over SOCKS5,
  queries the kernel's own controller, stops and restarts it; `two` loads two
  kernels into one process (negative test).
- `configs/`: minimal configs (local proxy inbound + direct outbound).
