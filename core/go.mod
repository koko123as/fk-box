module github.com/koko123as/fk-box/core

go 1.27

// fk-box's own Go module: the fk-core host process (docs/kernel-isolation.md).
// It must never depend on a kernel module (github.com/sagernet/sing-box,
// github.com/xtls/xray-core, github.com/metacubex/mihomo); CI checks that
// with `go list -deps` (tools/check_kernel_isolation.py).
