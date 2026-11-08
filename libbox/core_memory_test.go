package libbox

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"
	"runtime/debug"
	"testing"

	mihomoRouter "github.com/metacubex/mihomo/component/geodata/router"
	"google.golang.org/protobuf/proto"
)

func TestCoreMemoryTHPMode(t *testing.T) {
	write := func(root string, files map[string]string) {
		for name, content := range files {
			path := filepath.Join(root, name)
			if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
				t.Fatal(err)
			}
			if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
				t.Fatal(err)
			}
		}
	}
	cases := []struct {
		files map[string]string
		want  bool
	}{
		{map[string]string{"enabled": "[always] madvise never\n"}, true},
		{map[string]string{"enabled": "always [madvise] never\n"}, false},
		{map[string]string{"enabled": "always madvise [never]\n"}, false},
		{map[string]string{"enabled": "always madvise [never]\n", "hugepages-64kB/enabled": "[always] inherit madvise never\n"}, true},
		{map[string]string{"enabled": "always [madvise] never\n", "hugepages-64kB/enabled": "always [inherit] madvise never\n"}, false},
		{map[string]string{"enabled": "[always] madvise never\n", "hugepages-64kB/enabled": "always [inherit] madvise never\n"}, true},
		{map[string]string{}, false},
	}
	for index, test := range cases {
		root := t.TempDir()
		write(root, test.files)
		if got := thpAlwaysOn(root); got != test.want {
			t.Errorf("case %d: thpAlwaysOn = %v, want %v", index, got, test.want)
		}
	}
	t.Logf("THP disabled for this process: %v (host %q)", coreTHPDisabled.Load(), thpMode(filepath.Join(thpSysfs, "enabled")))
}

func TestUIRuntimeMemorySmaps(t *testing.T) {
	path := filepath.Join(t.TempDir(), "smaps")
	content := "" +
		"4000000000-4000400000 rw-p 00000000 00:00 0                          [anon: Go: heap]\n" +
		"Size:               4096 kB\n" +
		"Rss:                3000 kB\n" +
		"AnonHugePages:      2048 kB\n" +
		"7f0000000000-7f0000100000 rw-p 00000000 00:00 0                      [anon:dalvik-main space]\n" +
		"Rss:                 900 kB\n" +
		"AnonHugePages:       512 kB\n" +
		"7f1000000000-7f1000010000 rw-p 00000000 00:00 0                      [anon: Go: page summary]\n" +
		"Rss:                  64 kB\n"
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
	resident, hugePages := readGoResidentMemory(path)
	if resident != 3064*1024 || hugePages != 2048*1024 {
		t.Fatalf("resident %d, huge %d", resident, hugePages)
	}
	if resident, _ := readGoResidentMemory(filepath.Join(t.TempDir(), "missing")); resident != 0 {
		t.Fatal("missing smaps read as non-zero")
	}
	memory := ReadUIRuntimeMemory()
	t.Logf("live: total %d MiB, Go resident %d MiB, huge %d MiB", memory.Total>>20, memory.Resident>>20, memory.HugePages>>20)
}

// largeGeoIPList builds a GeoIP list shaped like meta-rules-dat's geoip.dat:
// many countries with many small CIDR entries.
func largeGeoIPList(t *testing.T, countries, cidrs int) []byte {
	t.Helper()
	list := &mihomoRouter.GeoIPList{}
	for c := 0; c < countries; c++ {
		entry := &mihomoRouter.GeoIP{CountryCode: "C" + string(rune('A'+c%26)) + string(rune('A'+c/26%26))}
		for i := 0; i < cidrs; i++ {
			entry.Cidr = append(entry.Cidr, &mihomoRouter.CIDR{Ip: []byte{byte(c), byte(i >> 16), byte(i >> 8), byte(i)}, Prefix: 24})
		}
		list.Entry = append(list.Entry, entry)
	}
	data, err := proto.Marshal(list)
	if err != nil {
		t.Fatal(err)
	}
	return data
}

func heapAllocated() uint64 {
	var stats runtime.MemStats
	runtime.ReadMemStats(&stats)
	return stats.TotalAlloc
}

func residentKB() int64 {
	content, _ := os.ReadFile("/proc/self/status")
	for _, line := range splitLines(string(content)) {
		if len(line) > 6 && line[:6] == "VmRSS:" {
			value, _ := smapsField([]byte(line), "VmRSS:")
			return value >> 10
		}
	}
	return 0
}

func splitLines(text string) []string {
	var lines []string
	start := 0
	for i := 0; i < len(text); i++ {
		if text[i] == '\n' {
			lines = append(lines, text[start:i])
			start = i + 1
		}
	}
	return lines
}

// TestCoreGeoUpdateMemory checks that updating a large .dat file no longer
// allocates on the order of the decoded list: the old path read the body
// into memory, decoded every entry and read the installed file back.
func TestCoreGeoUpdateMemory(t *testing.T) {
	data := largeGeoIPList(t, 250, 4000) // ~9 MiB, ~1M CIDR objects
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write(data)
	}))
	defer server.Close()
	dir := t.TempDir()
	debug.FreeOSMemory()
	before, rssBefore := heapAllocated(), residentKB()
	result, err := updateGeoForTest(dir, GeoResourceGeoIP, server.URL)
	if err != nil || result.Skipped {
		t.Fatalf("update: %+v, %v", result, err)
	}
	allocated := heapAllocated() - before
	rssAfter := residentKB()
	// Same content again: hashed from disk, skipped.
	before = heapAllocated()
	again, err := updateGeoForTest(dir, GeoResourceGeoIP, server.URL)
	if err != nil || !again.Skipped {
		t.Fatalf("second update: %+v, %v", again, err)
	}
	allocatedAgain := heapAllocated() - before

	// The previous implementation, for comparison.
	debug.FreeOSMemory()
	before = heapAllocated()
	var list mihomoRouter.GeoIPList
	if err := proto.Unmarshal(append([]byte(nil), data...), &list); err != nil {
		t.Fatal(err)
	}
	current, _ := os.ReadFile(filepath.Join(dir, "geoip.dat"))
	oldAllocated := heapAllocated() - before
	runtime.KeepAlive(current)
	runtime.KeepAlive(&list)
	list = mihomoRouter.GeoIPList{}
	debug.FreeOSMemory()

	t.Logf("file %.1f MiB: new path allocated %.1f MiB (unchanged re-check %.1f MiB), old path %.1f MiB; VmRSS %d -> %d -> %d KiB after FreeOSMemory",
		float64(len(data))/(1<<20), float64(allocated)/(1<<20), float64(allocatedAgain)/(1<<20), float64(oldAllocated)/(1<<20),
		rssBefore, rssAfter, residentKB())
	if allocated > uint64(len(data))/2 {
		t.Fatalf("update allocated %d bytes for a %d byte file", allocated, len(data))
	}
	if oldAllocated < 4*allocated {
		t.Fatalf("expected the old path (%d) to allocate far more than the new one (%d)", oldAllocated, allocated)
	}
}
