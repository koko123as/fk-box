package libbox

import (
	"encoding/json"
	"testing"

	tun "github.com/sagernet/sing-tun"
)

func TestDropUnbuiltTunStack(t *testing.T) {
	if tun.WithGVisor {
		t.Skip("built with gVisor")
	}
	in := `{"inbounds":[{"type":"tun","tag":"tun-in","stack":"mixed","mtu":9000},{"type":"mixed","tag":"mixed-in","listen_port":2412}],"outbounds":[{"type":"direct"}]}`
	var root struct {
		Inbounds []map[string]any `json:"inbounds"`
	}
	if err := json.Unmarshal([]byte(dropUnbuiltTunStack(in)), &root); err != nil {
		t.Fatal(err)
	}
	if _, ok := root.Inbounds[0]["stack"]; ok || root.Inbounds[0]["mtu"] != float64(9000) || root.Inbounds[1]["type"] != "mixed" {
		t.Fatalf("unexpected %v", root.Inbounds)
	}
	system := `{"inbounds":[{"type":"tun","stack":"system"}]}`
	if dropUnbuiltTunStack(system) != system || dropUnbuiltTunStack("not json") != "not json" {
		t.Fatal("must not touch other content")
	}
}
