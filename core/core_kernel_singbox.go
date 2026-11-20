package libbox

// sing-box as a kernel. This adapter hands the profile to the host's
// lifecycle: on Android that is libbox's own service (daemon.StartedService,
// whose gRPC streams the app reads), in the standalone host (desktop,
// core_standalone.go) fk-box starts box.Box itself. Either way sing-box opens
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
