package libbox

// One interface for the three kernels fk-box runs, one at a time:
// core_kernel_singbox.go, core_kernel_xray.go and core_kernel_mihomo.go.
// StartOrReloadCoreService (core_service.go) drives them; each gets the host
// services through kernelHost (core_host.go) and never reads libbox globals
// or libbox types itself.

// coreKernel is one proxy kernel.
type coreKernel interface {
	// Name is the core id: CoreSingBox, CoreXray or CoreMihomo.
	Name() string
	// NeedsTun reports whether the kernel runs on fk-box's TUN (Xray,
	// mihomo: running.tunFd) instead of opening its own (sing-box, whose
	// profile has a tun inbound).
	NeedsTun() bool
	// Prepare checks a profile before anything is stopped and returns what
	// Start may reuse (mihomo: the parsed config), or nil.
	Prepare(content string) (any, error)
	// Start runs the profile. A kernel that NeedsTun gets running with its
	// tunFd set and fills in its own fields; sing-box gets nil.
	Start(host kernelHost, running *runningCore, content string, prepared any, options kernelOptions) error
	// Stop stops what Start started; running.tunFd is closed by the caller.
	Stop(host kernelHost, running *runningCore)
	// DefaultNetworkChanged runs when the underlying default network was
	// replaced while the kernel runs.
	DefaultNetworkChanged(running *runningCore)
}

var coreKernels = map[string]coreKernel{
	CoreSingBox: singBoxKernel{},
	CoreXray:    xrayKernel{},
	CoreMihomo:  mihomoKernel{},
}

// kernelFor returns the kernel of a core id ("" is sing-box), nil when unknown.
func kernelFor(core string) coreKernel {
	if core == "" {
		core = CoreSingBox
	}
	return coreKernels[core]
}
