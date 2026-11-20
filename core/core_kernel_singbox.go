package libbox

// sing-box as a kernel. This adapter hands the profile to the host's
// lifecycle, which starts box.Box itself through fkhost.StartSingBox: the
// CommandServer host on Android (core_singbox_host.go) and the standalone
// host on the desktop (core_standalone.go). Either way sing-box opens
// its own TUN from the profile's tun inbound and resets its own connections
// when the default interface changes.

type singBoxKernel struct{}

func (singBoxKernel) Name() string { return CoreSingBox }

func (singBoxKernel) NeedsTun() bool { return false }

// Prepare checks nothing ahead: sing-box checks the profile as it starts.
func (singBoxKernel) Prepare(string) (any, error) { return nil, nil }

func (singBoxKernel) Start(host kernelHost, _ *runningCore, content string, _ any, options kernelOptions) error {
	return host.Lifecycle.StartSingBox(withDefaultSingBoxLogLevel(dropUnbuiltTunStack(content)), options)
}

func (singBoxKernel) Stop(host kernelHost, _ *runningCore) {
	_ = host.Lifecycle.StopSingBox()
}

func (singBoxKernel) DefaultNetworkChanged(*runningCore) {}
