package libbox

import "testing"

// sing-box still runs in the host's own service: the adapter hands the
// profile (with the default log level and an unbuilt tun stack dropped, as
// before) and the host's options through untouched, and stops it there.
func TestUIKernelSingBoxStartsInHostService(t *testing.T) {
	host := &fakeHost{}
	options := &OverrideOptions{}
	content := `{"outbounds":[{"type":"direct","tag":"direct"}]}`
	kernel := singBoxKernel{}
	if err := kernel.Start(host.kernelHost(), nil, content, nil, kernelOptions{host: options}); err != nil {
		t.Fatal(err)
	}
	if len(host.started) != 1 || host.started[0] != withDefaultSingBoxLogLevel(dropUnbuiltTunStack(content)) {
		t.Fatalf("sing-box started with %q", host.started)
	}
	if got, _ := host.options[0].host.(*OverrideOptions); got != options {
		t.Fatal("the host's start options were not passed through")
	}
	kernel.Stop(host.kernelHost(), nil)
	if host.stopped != 1 {
		t.Fatalf("sing-box stopped %d times, want 1", host.stopped)
	}
	if prepared, err := kernel.Prepare("not checked here"); prepared != nil || err != nil {
		t.Fatalf("Prepare = %v, %v", prepared, err)
	}
	kernel.DefaultNetworkChanged(nil)
}
