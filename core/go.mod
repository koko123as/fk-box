module github.com/koko123as/fk-box/core

go 1.27

// fk-box's own Go module: the fk-core host process (docs/kernel-isolation.md).
// It must never depend on a kernel module (github.com/sagernet/sing-box,
// github.com/xtls/xray-core, github.com/metacubex/mihomo); CI checks that
// with `go list -deps` (tools/check_kernel_isolation.py).

require (
	github.com/dlclark/regexp2/v2 v2.11.0
	github.com/dop251/goja v0.0.0-20261102223426-a177d211bf60
	github.com/oschwald/maxminddb-golang v1.13.1
	github.com/stretchr/testify v1.9.0
	go.yaml.in/yaml/v3 v3.0.5
	golang.org/x/net v0.48.0
	google.golang.org/grpc v1.79.1
	google.golang.org/protobuf v1.36.12
)

require (
	github.com/davecgh/go-spew v1.1.1 // indirect
	github.com/go-sourcemap/sourcemap v2.1.3+incompatible // indirect
	github.com/google/pprof v0.0.0-20230207041349-798e818bf904 // indirect
	github.com/pmezard/go-difflib v1.0.0 // indirect
	golang.org/x/sys v0.39.0 // indirect
	golang.org/x/text v0.43.0 // indirect
	google.golang.org/genproto/googleapis/rpc v0.0.0-20251202230838-ff82c1b0f217 // indirect
	gopkg.in/yaml.v3 v3.0.1 // indirect
)
