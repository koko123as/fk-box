package libbox

// B-box UI bindings for existing Go runtime statistics and sing-box routing.
// This adds no listener, routing rule, outbound or Clash implementation.
import (
	"bufio"
	"bytes"
	"os"
	"runtime"
	"runtime/debug"
	"strconv"
)

type UIRuntimeMemory struct {
	Total        int64
	HeapInuse    int64
	HeapIdle     int64
	StackInuse   int64
	RuntimeOther int64
	Goroutines   int32
	// Resident is what the kernel actually keeps resident for the Go
	// runtime's own mappings ("[anon: Go: …]" in smaps, Go 1.25+), 0 when the
	// kernel does not name anonymous mappings, or when THP cannot refill
	// released memory (THP off, "madvise"/"never", or disabled by init), in
	// which case smaps is not walked. It can exceed Total when the
	// kernel refills memory Go released (transparent huge pages).
	Resident int64
	// HugePages is the part of Resident backed by transparent huge pages.
	HugePages int64
}

func ReadUIRuntimeMemory() *UIRuntimeMemory {
	var stats runtime.MemStats
	runtime.ReadMemStats(&stats)
	total := stats.Sys - stats.HeapReleased
	heapIdle := stats.HeapIdle - stats.HeapReleased
	// Walking smaps costs milliseconds and takes mmap_lock (stalling page
	// faults in every thread), so it runs only when THP refill is possible.
	var resident, hugePages int64
	if coreTHPRefill.Load() {
		resident, hugePages = readGoResidentMemory("/proc/self/smaps")
	}
	return &UIRuntimeMemory{
		Total: int64(total), HeapInuse: int64(stats.HeapInuse), HeapIdle: int64(heapIdle),
		StackInuse: int64(stats.StackInuse), Goroutines: int32(runtime.NumGoroutine()),
		RuntimeOther: int64(total - stats.HeapInuse - heapIdle - stats.StackInuse),
		Resident:     resident, HugePages: hugePages,
	}
}

func CollectUIRuntimeMemory() { debug.FreeOSMemory() }

var goMappingName = []byte("[anon: Go:")

// readGoResidentMemory sums Rss and AnonHugePages over the mappings the Go
// runtime named (decoratemappings), in bytes.
func readGoResidentMemory(path string) (int64, int64) {
	file, err := os.Open(path)
	if err != nil {
		return 0, 0
	}
	defer file.Close()
	scanner := bufio.NewScanner(file)
	scanner.Buffer(make([]byte, 0, 4096), 64*1024)
	var resident, hugePages int64
	inGo := false
	for scanner.Scan() {
		line := scanner.Bytes()
		if len(line) == 0 {
			continue
		}
		// A mapping header starts with its hex range; field lines start
		// with a capitalized name.
		if first := line[0]; (first >= '0' && first <= '9') || (first >= 'a' && first <= 'f') {
			inGo = bytes.Contains(line, goMappingName)
			continue
		}
		if !inGo {
			continue
		}
		if value, ok := smapsField(line, "Rss:"); ok {
			resident += value
		} else if value, ok := smapsField(line, "AnonHugePages:"); ok {
			hugePages += value
		}
	}
	return resident, hugePages
}

func smapsField(line []byte, name string) (int64, bool) {
	if !bytes.HasPrefix(line, []byte(name)) {
		return 0, false
	}
	fields := bytes.Fields(line[len(name):])
	if len(fields) == 0 {
		return 0, false
	}
	value, err := strconv.ParseInt(string(fields[0]), 10, 64)
	if err != nil {
		return 0, false
	}
	return value * 1024, true
}
