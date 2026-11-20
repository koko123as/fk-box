package libbox

import (
	"testing"

	xrayCommonLog "github.com/xtls/xray-core/common/log"
)

// Xray's process-global hooks go through the host of the kernel started
// last: its dialer controller protects sockets there, its console log is
// written to that host's service log with the matching level.
func TestUIKernelXrayHooksUseHost(t *testing.T) {
	host := &fakeHost{}
	useFakeKernelHost(t, host)
	if err := protectKernelSocket(fakeRawConn{fd: 42}); err != nil {
		t.Fatal(err)
	}
	if len(host.protected) != 1 || host.protected[0] != 42 {
		t.Fatalf("protected %v, want [42]", host.protected)
	}
	xrayLogHandler{}.Handle(&xrayCommonLog.GeneralMessage{Severity: xrayCommonLog.Severity_Warning, Content: "slow"})
	xrayLogHandler{}.Handle(&xrayCommonLog.GeneralMessage{Severity: xrayCommonLog.Severity_Error, Content: "failed"})
	if len(host.logs) != 2 || host.logs[0].level != hostLogWarn || host.logs[1].level != hostLogError {
		t.Fatalf("xray log lines %+v", host.logs)
	}
	for _, line := range host.logs {
		if len(line.message) < 7 || line.message[:7] != "[xray] " {
			t.Fatalf("xray log line without its prefix: %q", line.message)
		}
	}
}

// Before any kernel started nothing is protected and nothing is logged.
func TestUIKernelXrayHooksWithoutHost(t *testing.T) {
	previous := kernelHostInUse.Load()
	kernelHostInUse.Store(nil)
	t.Cleanup(func() { kernelHostInUse.Store(previous) })
	if err := protectKernelSocket(fakeRawConn{fd: 1}); err != nil {
		t.Fatal(err)
	}
	xrayLogHandler{}.Handle(&xrayCommonLog.GeneralMessage{Severity: xrayCommonLog.Severity_Info, Content: "dropped"})
}

func TestUIKernelXrayPrepareChecksProfile(t *testing.T) {
	if _, err := (xrayKernel{}).Prepare("{"); err == nil {
		t.Fatal("broken Xray JSON was accepted")
	}
	// A network change with no tracker (tracking unavailable) is a no-op,
	// and with one closes its (here: no) connections.
	xrayKernel{}.DefaultNetworkChanged(&runningCore{core: CoreXray})
	xrayKernel{}.DefaultNetworkChanged(&runningCore{core: CoreXray, xrayTrack: newXrayTracker()})
}
