package ffi

import (
	"encoding/json"
	"fmt"
	"math/rand/v2"
	"net/netip"
	"os"
	"runtime"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	M "github.com/sagernet/sing/common/metadata"
)

var sampleDomains = []string{
	"www.google.com", "i.ytimg.com", "api.github.com", "raw.githubusercontent.com",
	"www.baidu.com", "img.alicdn.com", "s.taobao.com", "news.qq.com", "example.cn",
	"chat.openai.com", "claude.ai", "en.wikipedia.org", "cdn.discordapp.com",
	"some-unknown-host.example.org", "a.b.c.d.e.f.g.example.net", "api.weibo.com",
}

func sampleMetas(n int) []Meta {
	r := rand.New(rand.NewPCG(1, 2))
	metas := make([]Meta, n)
	for i := range metas {
		md := adapter.InboundContext{Network: "tcp", Source: M.ParseSocksaddr("172.19.0.1:40000")}
		switch i % 4 {
		case 0, 1:
			md.Destination = M.ParseSocksaddrHostPort(sampleDomains[r.IntN(len(sampleDomains))], 443)
		case 2:
			md.Destination = M.SocksaddrFromNetIP(netip.AddrPortFrom(netip.AddrFrom4([4]byte{byte(r.IntN(223) + 1), byte(r.IntN(256)), 1, 1}), 443))
		default:
			md.Destination = M.SocksaddrFromNetIP(netip.AddrPortFrom(netip.MustParseAddr("192.168.1.10"), 22))
		}
		FillMeta(&metas[i], &md, 0)
	}
	return metas
}

// goDecide is the same rule table in Go, for comparison only.
func goDecide(meta *Meta) Decision {
	if meta.Network == NetTCP && (meta.DstPort == 22 || meta.DstPort == 25) {
		return Decision{Action: ActionReject}
	}
	if meta.DomainLen > 0 {
		domain := strings.ToLower(string(meta.Domain[:meta.DomainLen]))
		for _, rule := range goRules {
			if domain == rule.suffix || strings.HasSuffix(domain, "."+rule.suffix) {
				return Decision{Outbound: rule.outbound}
			}
		}
	} else if meta.IPVersion == 4 && (meta.DstIP[0] == 10 || meta.DstIP[0] == 127 || meta.DstIP[0] == 192 && meta.DstIP[1] == 168 || meta.DstIP[0] == 169 && meta.DstIP[1] == 254 || meta.DstIP[0] == 172 && meta.DstIP[1] >= 16 && meta.DstIP[1] <= 31) {
		return Decision{Outbound: 0}
	}
	return Decision{Outbound: 1}
}

var goRules = func() []struct {
	suffix   string
	outbound uint32
} {
	direct := []string{"cn", "baidu.com", "qq.com", "taobao.com", "bilibili.com", "jd.com", "163.com", "weibo.com", "zhihu.com", "alipay.com"}
	proxy := []string{"google.com", "googleapis.com", "gstatic.com", "youtube.com", "ytimg.com", "github.com", "githubusercontent.com", "twitter.com", "x.com", "telegram.org", "openai.com", "chatgpt.com", "anthropic.com", "claude.ai", "wikipedia.org", "netflix.com", "spotify.com", "discord.com", "reddit.com", "instagram.com", "facebook.com", "whatsapp.net"}
	var rules []struct {
		suffix   string
		outbound uint32
	}
	for _, s := range direct {
		rules = append(rules, struct {
			suffix   string
			outbound uint32
		}{s, 0})
	}
	for _, s := range proxy {
		rules = append(rules, struct {
			suffix   string
			outbound uint32
		}{s, 1})
	}
	return rules
}()

func TestFFIRustDecideMatchesGo(t *testing.T) {
	if err := Init(); err != nil {
		t.Fatal(err)
	}
	metas := sampleMetas(4096)
	for i := range metas {
		got, err := Decide(&metas[i])
		if err != nil {
			t.Fatal(err)
		}
		want := goDecide(&metas[i])
		if got != want {
			t.Fatalf("meta %d (%q port %d): rust %+v, go %+v", i, string(metas[i].Domain[:metas[i].DomainLen]), metas[i].DstPort, got, want)
		}
	}
}

type latencyStats struct {
	Name       string  `json:"name"`
	Calls      int     `json:"calls"`
	P50ns      int64   `json:"p50_ns"`
	P90ns      int64   `json:"p90_ns"`
	P99ns      int64   `json:"p99_ns"`
	P999ns     int64   `json:"p999_ns"`
	MaxNs      int64   `json:"max_ns"`
	BatchAvg   float64 `json:"batch_avg_ns"`
	TimerNsP50 int64   `json:"timer_overhead_p50_ns"`
}

func percentile(sorted []int64, p float64) int64 {
	index := int(float64(len(sorted)-1) * p)
	return sorted[index]
}

func measure(name string, metas []Meta, calls int, fn func(*Meta)) latencyStats {
	for i := 0; i < 100000; i++ {
		fn(&metas[i%len(metas)])
	}
	// timer overhead alone
	timer := make([]int64, 100000)
	for i := range timer {
		start := time.Now()
		timer[i] = int64(time.Since(start))
	}
	slices.Sort(timer)
	samples := make([]int64, calls)
	for i := 0; i < calls; i++ {
		meta := &metas[i%len(metas)]
		start := time.Now()
		fn(meta)
		samples[i] = int64(time.Since(start))
	}
	slices.Sort(samples)
	batchCalls := calls * 4
	start := time.Now()
	for i := 0; i < batchCalls; i++ {
		fn(&metas[i%len(metas)])
	}
	batch := float64(time.Since(start).Nanoseconds()) / float64(batchCalls)
	return latencyStats{
		Name: name, Calls: calls,
		P50ns: percentile(samples, 0.50), P90ns: percentile(samples, 0.90),
		P99ns: percentile(samples, 0.99), P999ns: percentile(samples, 0.999),
		MaxNs: samples[len(samples)-1], BatchAvg: batch, TimerNsP50: percentile(timer, 0.5),
	}
}

// TestFFIDecideLatency measures one decide per call, cgo crossing included.
// Per-call numbers include the time.Now/time.Since pair (reported
// separately); batch_avg_ns is a tight loop without per-call timing.
func TestFFIDecideLatency(t *testing.T) {
	if err := Init(); err != nil {
		t.Fatal(err)
	}
	runtime.GC()
	metas := sampleMetas(4096)
	const calls = 1_000_000
	var sink Decision
	results := []latencyStats{
		measure("rust_decide_via_cgo", metas, calls, func(m *Meta) {
			d, _ := Decide(m)
			sink = d
		}),
		measure("cgo_noop_baseline", metas, calls, func(m *Meta) { sink = Noop(m) }),
		measure("go_native_same_rules", metas, calls, func(m *Meta) { sink = goDecide(m) }),
	}
	// End to end as fkbrain would call it: sing-box metadata -> Meta -> Rust.
	decider := Decider()
	mds := make([]adapter.InboundContext, 1024)
	for i := range mds {
		mds[i] = adapter.InboundContext{
			Network:     "tcp",
			Source:      M.ParseSocksaddr("172.19.0.1:40000"),
			Destination: M.ParseSocksaddrHostPort(sampleDomains[i%len(sampleDomains)], 443),
		}
	}
	index := 0
	results = append(results, measure("fkbrain_decider_fill_meta_plus_rust", metas, calls, func(*Meta) {
		decision := decider(&mds[index%len(mds)])
		index++
		sink.Outbound = uint32(decision.Index)
	}))
	_ = sink
	// Parallel throughput (GOMAXPROCS goroutines, no per-call timer).
	workers := runtime.GOMAXPROCS(0)
	perWorker := 1_000_000
	var wg sync.WaitGroup
	start := time.Now()
	for w := 0; w < workers; w++ {
		wg.Add(1)
		go func(offset int) {
			defer wg.Done()
			for i := 0; i < perWorker; i++ {
				_, _ = Decide(&metas[(offset+i)%len(metas)])
			}
		}(w * 97)
	}
	wg.Wait()
	elapsed := time.Since(start)
	parallel := map[string]any{
		"workers":              workers,
		"calls_total":          workers * perWorker,
		"wall_ns_per_call":     float64(elapsed.Nanoseconds()) / float64(workers*perWorker),
		"calls_per_second_all": float64(workers*perWorker) / elapsed.Seconds(),
	}
	report := map[string]any{
		"goos": runtime.GOOS, "goarch": runtime.GOARCH, "go": runtime.Version(), "cpus": runtime.NumCPU(),
		"meta_size_bytes": 304, "serial": results, "parallel": parallel,
	}
	content, _ := json.MarshalIndent(report, "", "  ")
	t.Logf("decide latency:\n%s", content)
	if path := os.Getenv("FK_LATENCY_OUT"); path != "" {
		if err := os.WriteFile(path, content, 0o644); err != nil {
			t.Fatal(err)
		}
	}
	for _, r := range results {
		fmt.Printf("LATENCY %-38s p50=%dns p90=%dns p99=%dns p99.9=%dns max=%dns batch_avg=%.1fns (timer p50 %dns)\n", r.Name, r.P50ns, r.P90ns, r.P99ns, r.P999ns, r.MaxNs, r.BatchAvg, r.TimerNsP50)
	}
	fmt.Printf("LATENCY parallel workers=%d wall_ns_per_call=%.1f\n", workers, parallel["wall_ns_per_call"])
}

func BenchmarkRustDecide(b *testing.B) {
	if err := Init(); err != nil {
		b.Fatal(err)
	}
	metas := sampleMetas(4096)
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		_, _ = Decide(&metas[i%len(metas)])
	}
}

func BenchmarkCgoNoop(b *testing.B) {
	metas := sampleMetas(4096)
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		_ = Noop(&metas[i%len(metas)])
	}
}

func BenchmarkGoDecide(b *testing.B) {
	metas := sampleMetas(4096)
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		_ = goDecide(&metas[i%len(metas)])
	}
}
