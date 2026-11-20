package libbox

import (
	"encoding/json"
	"errors"
	"io"
	"sync"
	"testing"
	"time"
)

type uiEndpointTestHarness struct {
	access   sync.Mutex
	sent     []string
	handlers map[int]*uiStatusHandler
}

func newUIEndpointTest() (*uiEndpointStatus, *uiEndpointTestHarness) {
	h := &uiEndpointTestHarness{handlers: map[int]*uiStatusHandler{}}
	s := newUIEndpointStatus(func(payload string) {
		h.access.Lock()
		h.sent = append(h.sent, payload)
		h.access.Unlock()
	})
	return s, h
}

func (h *uiEndpointTestHarness) count() int {
	h.access.Lock()
	defer h.access.Unlock()
	return len(h.sent)
}

func (h *uiEndpointTestHarness) last(t *testing.T) map[string]any {
	t.Helper()
	h.access.Lock()
	defer h.access.Unlock()
	if len(h.sent) == 0 {
		t.Fatal("nothing sent")
	}
	var value map[string]any
	if err := json.Unmarshal([]byte(h.sent[len(h.sent)-1]), &value); err != nil {
		t.Fatal(err)
	}
	return value
}

func uiEndpointWait(t *testing.T, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		if cond() {
			return
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatal("timed out")
}

func uiEndpointTestPeer(dns, host, stable string) *TailscalePeer {
	return &TailscalePeer{StableID: stable, HostName: host, DNSName: dns, OS: "linux", tailscaleIPs: []string{"100.64.0.2", "fd7a::2"}, sshHostKeys: []string{"ssh-ed25519 AAA"}, Online: true, ExitNodeOption: true, CanReceiveFiles: true, RxBytes: 5, TxBytes: 7}
}

func TestUIEndpointStatusEmptyPayload(t *testing.T) {
	s, _ := newUIEndpointTest()
	if got := s.payload(); got != `{"tailscale":[],"openconnect":[],"openvpn":[],"usbip":[]}` {
		t.Fatalf("payload %s", got)
	}
	s.setUSBIP(`[{"serverTag":"u","devices":[]}]`)
	s.setUSBIP(`not json`)
	if got := s.payload(); got != `{"tailscale":[],"openconnect":[],"openvpn":[],"usbip":[{"serverTag":"u","devices":[]}]}` {
		t.Fatalf("payload %s", got)
	}
}

func TestUIEndpointStatusUpdatesAndLookups(t *testing.T) {
	s, h := newUIEndpointTest()
	closers := map[int]*uiSTUNTestCloser{}
	var access sync.Mutex
	s.open = func(kind int, _ UIToolHost, handler *uiStatusHandler) (io.Closer, error) {
		access.Lock()
		defer access.Unlock()
		h.handlers[kind] = handler
		closers[kind] = &uiSTUNTestCloser{closed: make(chan struct{})}
		return closers[kind], nil
	}
	host := &uiSTUNTestHost{}
	s.subscribe(host)
	s.subscribe(host) // already subscribed: no second open
	uiEndpointWait(t, func() bool {
		access.Lock()
		defer access.Unlock()
		return len(h.handlers) == 3 && s.slots[0].session != nil && s.slots[1].session != nil && s.slots[2].session != nil
	})
	self := uiEndpointTestPeer("me.tail.ts.net", "me", "s0")
	peer := uiEndpointTestPeer("", "box", "s1")
	(&uiTailscaleStatusHandler{h.handlers[uiStatusTailscale]}).OnStatusUpdate(&TailscaleStatusUpdate{endpoints: []*TailscaleEndpointStatus{{
		EndpointTag: "ts", StateText: "Running", NetworkName: "net", Self: self, WaitingFileCount: 2, CanShareFiles: true,
		userGroups: []*TailscaleUserGroup{{UserID: 9, LoginName: "a@b", DisplayName: "A", peers: []*TailscalePeer{peer}}},
	}}})
	(&uiOpenConnectStatusHandler{h.handlers[uiStatusOpenConnect]}).OnStatusUpdate(&OpenConnectStatusUpdate{endpoints: []*OpenConnectEndpointStatus{{
		EndpointTag: "oc", State: "auth", AuthChallenge: &OpenConnectAuthChallenge{ID: "c1", Browser: &OpenConnectBrowserRequest{URL: "https://sso"},
			Form: &OpenConnectAuthForm{fields: []*OpenConnectAuthFormField{{SubmissionKey: "k", Kind: "select", options: []*OpenConnectAuthFormChoice{{Value: "v", Label: "L"}}}}}},
	}}})
	(&uiOpenVPNStatusHandler{h.handlers[uiStatusOpenVPN]}).OnStatusUpdate(&OpenVPNStatusUpdate{endpoints: []*OpenVPNEndpointStatus{{
		EndpointTag: "ov", State: "connected", TunnelInfo: &OpenVPNTunnelInfo{Server: "srv", ipv4: []string{"10.0.0.2"}, MTU: 1500, ConnectedSince: 3},
	}}})
	want := `{"tailscale":[{"endpointTag":"ts","stateText":"Running","authURL":"","networkName":"net",` +
		`"selfPeer":{"id":"me.tail.ts.net","stableID":"s0","hostName":"me","dnsName":"me.tail.ts.net","os":"linux","tailscaleIPs":["100.64.0.2","fd7a::2"],"sshHostKeys":["ssh-ed25519 AAA"],"online":true,"exitNode":false,"exitNodeOption":true,"active":false,"rxBytes":5,"txBytes":7,"canReceiveFiles":true},` +
		`"exitNode":null,"userGroups":[{"id":9,"loginName":"a@b","displayName":"A","peers":[{"id":"box","stableID":"s1","hostName":"box","dnsName":"","os":"linux","tailscaleIPs":["100.64.0.2","fd7a::2"],"sshHostKeys":["ssh-ed25519 AAA"],"online":true,"exitNode":false,"exitNodeOption":true,"active":false,"rxBytes":5,"txBytes":7,"canReceiveFiles":true}]}],` +
		`"canShareFiles":true,"waitingFileCount":2}],` +
		`"openconnect":[{"endpointTag":"oc","state":"auth","stateText":"","authChallenge":{"id":"c1","banner":"","message":"","error":"","form":{"fields":[{"submissionKey":"k","name":"","label":"","kind":"select","value":"","options":[{"value":"v","label":"L"}]}]},"browser":{"url":"https://sso"}},"error":"","tunnelInfo":null}],` +
		`"openvpn":[{"endpointTag":"ov","state":"connected","stateText":"","challenge":null,"error":"","tunnelInfo":{"server":"srv","network":"","cipher":"","ipv4":["10.0.0.2"],"ipv6":[],"dns":[],"mtu":1500,"connectedSince":3}}],"usbip":[]}`
	if got := s.payload(); got != want {
		t.Fatalf("payload\n%s\nwant\n%s", got, want)
	}
	if h.count() != 3 {
		t.Fatalf("sent %d", h.count())
	}
	if UITailscalePeerDisplayName(self) != "me" || UITailscalePeerDisplayName(peer) != "box" {
		t.Fatal("display name")
	}
	if s.tailscalePeer("ts", "me.tail.ts.net") != self || s.tailscalePeer("ts", "box") != peer || s.tailscalePeer("ts", "nope") != nil || s.tailscalePeer("x", "box") != nil {
		t.Fatal("peer lookup")
	}
	if c := s.openConnectChallenge("oc"); c == nil || c.ID != "c1" || s.openConnectChallenge("ov") != nil {
		t.Fatal("challenge lookup")
	}
	s.cancel()
	for kind, closer := range closers {
		select {
		case <-closer.closed:
		default:
			t.Fatalf("session %d not closed", kind)
		}
	}
	if got := s.payload(); got != `{"tailscale":[],"openconnect":[],"openvpn":[],"usbip":[]}` {
		t.Fatalf("after cancel %s", got)
	}
	// Late updates of the cancelled streams are ignored.
	(&uiOpenVPNStatusHandler{h.handlers[uiStatusOpenVPN]}).OnStatusUpdate(&OpenVPNStatusUpdate{endpoints: []*OpenVPNEndpointStatus{{EndpointTag: "late"}}})
	if len(h.last(t)["openvpn"].([]any)) != 0 {
		t.Fatal("stale update applied")
	}
}

func TestUIEndpointStatusErrorReported(t *testing.T) {
	s, h := newUIEndpointTest()
	s.open = func(kind int, _ UIToolHost, handler *uiStatusHandler) (io.Closer, error) {
		if kind != uiStatusOpenVPN {
			return nil, errors.New("silent")
		}
		h.access.Lock()
		h.handlers[kind] = handler
		h.access.Unlock()
		return &uiSTUNTestCloser{closed: make(chan struct{})}, nil
	}
	host := &uiSTUNTestHost{}
	s.subscribe(host)
	uiEndpointWait(t, func() bool {
		s.access.Lock()
		defer s.access.Unlock()
		return s.slots[uiStatusOpenVPN].session != nil && !s.slots[uiStatusTailscale].subscribed && !s.slots[uiStatusOpenConnect].subscribed
	})
	handler := &uiOpenVPNStatusHandler{h.handlers[uiStatusOpenVPN]}
	handler.OnStatusUpdate(&OpenVPNStatusUpdate{endpoints: []*OpenVPNEndpointStatus{{EndpointTag: "ov"}}})
	handler.OnError("stream broke")
	handler.OnError("again")
	if got := host.reported(); len(got) != 1 || got[0] != "stream broke" {
		t.Fatalf("errors %v", got)
	}
	if len(h.last(t)["openvpn"].([]any)) != 0 || s.slots[uiStatusOpenVPN].subscribed {
		t.Fatal("not cleared")
	}
}

func TestUIEndpointStatusOldServiceAndLateSession(t *testing.T) {
	s, _ := newUIEndpointTest()
	release := make(chan struct{})
	closer := &uiSTUNTestCloser{closed: make(chan struct{})}
	s.open = func(kind int, _ UIToolHost, _ *uiStatusHandler) (io.Closer, error) {
		if kind == uiStatusTailscale {
			<-release
			return closer, nil
		}
		return nil, nil // API version too old
	}
	s.subscribe(&uiSTUNTestHost{})
	uiEndpointWait(t, func() bool {
		s.access.Lock()
		defer s.access.Unlock()
		return !s.slots[uiStatusOpenConnect].subscribed && !s.slots[uiStatusOpenVPN].subscribed
	})
	s.cancel()
	close(release)
	select {
	case <-closer.closed:
	case <-time.After(5 * time.Second):
		t.Fatal("late session not closed")
	}
}
