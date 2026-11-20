package libbox

import "testing"

func TestUIDNSQueryCountSinceReset(t *testing.T) {
	resetUIDNSQueryCount()
	if got := ReadUIDNSQueryCount(); got != 0 {
		t.Fatalf("count after reset = %d", got)
	}
	uiOtherDNSQueries.Add(3)
	if got := ReadUIDNSQueryCount(); got != 3 {
		t.Fatalf("count = %d, want 3", got)
	}
	resetUIDNSQueryCount()
	if got := ReadUIDNSQueryCount(); got != 0 {
		t.Fatalf("count after second reset = %d", got)
	}
}
