module github.com/koko123as/fk-box/core

go 1.27

// fk-box's own Go module: the fk-core host process (docs/kernel-isolation.md).
// It must never depend on a kernel module (github.com/sagernet/sing-box,
// github.com/xtls/xray-core, github.com/metacubex/mihomo); CI checks that
// with `go list -deps` (tools/check_kernel_isolation.py).

require (
	github.com/oschwald/maxminddb-golang v1.13.1
	github.com/stretchr/testify v1.9.0
	go.yaml.in/yaml/v3 v3.0.5
	google.golang.org/protobuf v1.36.12
)

require (
	github.com/davecgh/go-spew v1.1.1 // indirect
	github.com/pmezard/go-difflib v1.0.0 // indirect
	golang.org/x/sys v0.21.0 // indirect
	gopkg.in/yaml.v3 v3.0.1 // indirect
)
