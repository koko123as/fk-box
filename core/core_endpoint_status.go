package libbox

import (
	"encoding/json"
	"io"
	"sync"
)

// Endpoint status (Tailscale, OpenConnect, OpenVPN): a port of the old Kotlin
// TailscaleStatusViewModel, OpenConnectStatusViewModel and
// OpenVPNStatusViewModel. Go owns the three status subscriptions, keeps the
// latest endpoints and sends the "tools" event. The USBIP part of that event
// stays with the Kotlin USBIP view model, which hands its wire JSON in through
// UIToolsSetUSBIP. Kotlin code that reads the endpoints (exit-node checks,
// ping, SSH, Taildrop, OpenConnect browser sign-in) uses the UITailscale* and
// UIOpenConnect* lookups below.

const (
	uiStatusTailscale = iota
	uiStatusOpenConnect
	uiStatusOpenVPN
	uiStatusCount
)

// Status streams before this API version do not exist (OpenConnect, OpenVPN).
const uiStatusMinAPIVersion = 3

type uiStatusSlot struct {
	subscribed bool
	generation int64
	session    io.Closer
}

type uiEndpointStatus struct {
	access      sync.Mutex
	tailscale   []*TailscaleEndpointStatus
	openConnect []*OpenConnectEndpointStatus
	openVPN     []*OpenVPNEndpointStatus
	usbip       json.RawMessage
	slots       [uiStatusCount]uiStatusSlot
	last        string

	emit func(payload string)
	open func(kind int, host UIToolHost, handler *uiStatusHandler) (io.Closer, error)
}

func newUIEndpointStatus(emit func(string)) *uiEndpointStatus {
	return &uiEndpointStatus{emit: emit, open: uiOpenEndpointStatus}
}

func uiOpenEndpointStatus(kind int, host UIToolHost, handler *uiStatusHandler) (io.Closer, error) {
	client, err := host.CommandClient()
	if err != nil {
		return nil, err
	}
	if kind != uiStatusTailscale {
		version, err := client.GetAPIVersion()
		if err != nil {
			return nil, err
		}
		if version < uiStatusMinAPIVersion {
			return nil, nil
		}
	}
	switch kind {
	case uiStatusTailscale:
		session, err := client.SubscribeTailscaleStatus(&uiTailscaleStatusHandler{handler})
		if err != nil {
			return nil, err
		}
		return session, nil
	case uiStatusOpenConnect:
		session, err := client.SubscribeOpenConnectStatus(&uiOpenConnectStatusHandler{handler})
		if err != nil {
			return nil, err
		}
		return session, nil
	default:
		session, err := client.SubscribeOpenVPNStatus(&uiOpenVPNStatusHandler{handler})
		if err != nil {
			return nil, err
		}
		return session, nil
	}
}

func (s *uiEndpointStatus) clearLocked(kind int) {
	switch kind {
	case uiStatusTailscale:
		s.tailscale = nil
	case uiStatusOpenConnect:
		s.openConnect = nil
	default:
		s.openVPN = nil
	}
}

func (s *uiEndpointStatus) changedLocked() {
	payload := s.payloadLocked()
	if payload == s.last {
		return
	}
	s.last = payload
	if s.emit != nil {
		s.emit(payload)
	}
}

// subscribe opens every status stream that is not open yet.
func (s *uiEndpointStatus) subscribe(host UIToolHost) {
	for kind := 0; kind < uiStatusCount; kind++ {
		s.subscribeKind(kind, host)
	}
}

func (s *uiEndpointStatus) subscribeKind(kind int, host UIToolHost) {
	s.access.Lock()
	slot := &s.slots[kind]
	if slot.subscribed {
		s.access.Unlock()
		return
	}
	slot.subscribed = true
	slot.generation++
	generation := slot.generation
	s.access.Unlock()
	handler := &uiStatusHandler{status: s, kind: kind, generation: generation, host: host}
	go func() {
		session, err := s.open(kind, host, handler)
		s.access.Lock()
		if err != nil || session == nil {
			// Open failures and old services were never reported.
			if generation == slot.generation {
				slot.subscribed = false
				s.clearLocked(kind)
				s.changedLocked()
			}
			s.access.Unlock()
			return
		}
		if generation == slot.generation && slot.subscribed {
			slot.session = session
			s.access.Unlock()
			return
		}
		// Cancelled, replaced or failed while it was opening.
		s.access.Unlock()
		uiCloseQuietly(session)
	}()
}

// cancel closes every stream and drops their endpoints.
func (s *uiEndpointStatus) cancel() {
	var sessions []io.Closer
	s.access.Lock()
	for kind := 0; kind < uiStatusCount; kind++ {
		slot := &s.slots[kind]
		slot.generation++
		slot.subscribed = false
		if slot.session != nil {
			sessions = append(sessions, slot.session)
			slot.session = nil
		}
		s.clearLocked(kind)
	}
	s.changedLocked()
	s.access.Unlock()
	for _, session := range sessions {
		uiCloseQuietly(session)
	}
}

func (s *uiEndpointStatus) setUSBIP(content string) {
	if !json.Valid([]byte(content)) {
		return
	}
	s.access.Lock()
	s.usbip = json.RawMessage(content)
	s.changedLocked()
	s.access.Unlock()
}

type uiStatusHandler struct {
	status     *uiEndpointStatus
	kind       int
	generation int64
	host       UIToolHost
}

func (h *uiStatusHandler) currentLocked() bool {
	slot := &h.status.slots[h.kind]
	return h.generation == slot.generation && slot.subscribed
}

func (h *uiStatusHandler) apply(update func(s *uiEndpointStatus)) {
	s := h.status
	s.access.Lock()
	defer s.access.Unlock()
	if !h.currentLocked() {
		return
	}
	update(s)
	s.changedLocked()
}

func (h *uiStatusHandler) OnError(message string) {
	s := h.status
	s.access.Lock()
	if !h.currentLocked() {
		s.access.Unlock()
		return
	}
	slot := &s.slots[h.kind]
	slot.subscribed = false
	slot.session = nil
	s.clearLocked(h.kind)
	s.changedLocked()
	s.access.Unlock()
	if h.host != nil {
		h.host.ReportError(message)
	}
}

type uiTailscaleStatusHandler struct{ *uiStatusHandler }

func (h *uiTailscaleStatusHandler) OnStatusUpdate(status *TailscaleStatusUpdate) {
	var endpoints []*TailscaleEndpointStatus
	if status != nil {
		endpoints = status.endpoints
	}
	h.apply(func(s *uiEndpointStatus) { s.tailscale = endpoints })
}

type uiOpenConnectStatusHandler struct{ *uiStatusHandler }

func (h *uiOpenConnectStatusHandler) OnStatusUpdate(status *OpenConnectStatusUpdate) {
	var endpoints []*OpenConnectEndpointStatus
	if status != nil {
		endpoints = status.endpoints
	}
	h.apply(func(s *uiEndpointStatus) { s.openConnect = endpoints })
}

type uiOpenVPNStatusHandler struct{ *uiStatusHandler }

func (h *uiOpenVPNStatusHandler) OnStatusUpdate(status *OpenVPNStatusUpdate) {
	var endpoints []*OpenVPNEndpointStatus
	if status != nil {
		endpoints = status.endpoints
	}
	h.apply(func(s *uiEndpointStatus) { s.openVPN = endpoints })
}

// Wire shapes: the old Kotlin toWire() maps (BoxToolWire.kt).

func uiStrings(values []string) []string {
	if values == nil {
		return []string{}
	}
	return values
}

// uiTailscalePeerID is the old TailscalePeerData.id.
func uiTailscalePeerID(peer *TailscalePeer) string {
	if peer.DNSName != "" {
		return peer.DNSName
	}
	return peer.HostName
}

type uiTailscalePeerWire struct {
	ID              string   `json:"id"`
	StableID        string   `json:"stableID"`
	HostName        string   `json:"hostName"`
	DNSName         string   `json:"dnsName"`
	OS              string   `json:"os"`
	TailscaleIPs    []string `json:"tailscaleIPs"`
	SSHHostKeys     []string `json:"sshHostKeys"`
	Online          bool     `json:"online"`
	ExitNode        bool     `json:"exitNode"`
	ExitNodeOption  bool     `json:"exitNodeOption"`
	Active          bool     `json:"active"`
	RxBytes         int64    `json:"rxBytes"`
	TxBytes         int64    `json:"txBytes"`
	CanReceiveFiles bool     `json:"canReceiveFiles"`
}

func uiTailscalePeerToWire(peer *TailscalePeer) *uiTailscalePeerWire {
	if peer == nil {
		return nil
	}
	return &uiTailscalePeerWire{
		ID: uiTailscalePeerID(peer), StableID: peer.StableID, HostName: peer.HostName, DNSName: peer.DNSName, OS: peer.OS,
		TailscaleIPs: uiStrings(peer.tailscaleIPs), SSHHostKeys: uiStrings(peer.sshHostKeys),
		Online: peer.Online, ExitNode: peer.ExitNode, ExitNodeOption: peer.ExitNodeOption, Active: peer.Active,
		RxBytes: peer.RxBytes, TxBytes: peer.TxBytes, CanReceiveFiles: peer.CanReceiveFiles,
	}
}

type uiTailscaleUserGroupWire struct {
	ID          int64                  `json:"id"`
	LoginName   string                 `json:"loginName"`
	DisplayName string                 `json:"displayName"`
	Peers       []*uiTailscalePeerWire `json:"peers"`
}

type uiTailscaleEndpointWire struct {
	EndpointTag      string                      `json:"endpointTag"`
	StateText        string                      `json:"stateText"`
	AuthURL          string                      `json:"authURL"`
	NetworkName      string                      `json:"networkName"`
	SelfPeer         *uiTailscalePeerWire        `json:"selfPeer"`
	ExitNode         *uiTailscalePeerWire        `json:"exitNode"`
	UserGroups       []*uiTailscaleUserGroupWire `json:"userGroups"`
	CanShareFiles    bool                        `json:"canShareFiles"`
	WaitingFileCount int32                       `json:"waitingFileCount"`
}

func uiTailscaleEndpointToWire(endpoint *TailscaleEndpointStatus) *uiTailscaleEndpointWire {
	groups := make([]*uiTailscaleUserGroupWire, 0, len(endpoint.userGroups))
	for _, group := range endpoint.userGroups {
		peers := make([]*uiTailscalePeerWire, 0, len(group.peers))
		for _, peer := range group.peers {
			peers = append(peers, uiTailscalePeerToWire(peer))
		}
		groups = append(groups, &uiTailscaleUserGroupWire{ID: group.UserID, LoginName: group.LoginName, DisplayName: group.DisplayName, Peers: peers})
	}
	return &uiTailscaleEndpointWire{
		EndpointTag: endpoint.EndpointTag, StateText: endpoint.StateText, AuthURL: endpoint.AuthURL, NetworkName: endpoint.NetworkName,
		SelfPeer: uiTailscalePeerToWire(endpoint.Self), ExitNode: uiTailscalePeerToWire(endpoint.ExitNode), UserGroups: groups,
		CanShareFiles: endpoint.CanShareFiles, WaitingFileCount: endpoint.WaitingFileCount,
	}
}

type uiOpenConnectChoiceWire struct {
	Value string `json:"value"`
	Label string `json:"label"`
}

type uiOpenConnectFieldWire struct {
	SubmissionKey string                     `json:"submissionKey"`
	Name          string                     `json:"name"`
	Label         string                     `json:"label"`
	Kind          string                     `json:"kind"`
	Value         string                     `json:"value"`
	Options       []*uiOpenConnectChoiceWire `json:"options"`
}

type uiOpenConnectFormWire struct {
	Fields []*uiOpenConnectFieldWire `json:"fields"`
}

type uiOpenConnectBrowserWire struct {
	URL string `json:"url"`
}

type uiOpenConnectChallengeWire struct {
	ID      string                    `json:"id"`
	Banner  string                    `json:"banner"`
	Message string                    `json:"message"`
	Error   string                    `json:"error"`
	Form    *uiOpenConnectFormWire    `json:"form"`
	Browser *uiOpenConnectBrowserWire `json:"browser"`
}

type uiOpenConnectTunnelWire struct {
	Server         string   `json:"server"`
	Flavor         string   `json:"flavor"`
	Transport      string   `json:"transport"`
	IPv4           []string `json:"ipv4"`
	IPv6           []string `json:"ipv6"`
	DNS            []string `json:"dns"`
	MTU            int32    `json:"mtu"`
	ConnectedSince int64    `json:"connectedSince"`
}

type uiOpenConnectEndpointWire struct {
	EndpointTag   string                      `json:"endpointTag"`
	State         string                      `json:"state"`
	StateText     string                      `json:"stateText"`
	AuthChallenge *uiOpenConnectChallengeWire `json:"authChallenge"`
	Error         string                      `json:"error"`
	TunnelInfo    *uiOpenConnectTunnelWire    `json:"tunnelInfo"`
}

func uiOpenConnectEndpointToWire(endpoint *OpenConnectEndpointStatus) *uiOpenConnectEndpointWire {
	result := &uiOpenConnectEndpointWire{EndpointTag: endpoint.EndpointTag, State: endpoint.State, StateText: endpoint.StateText, Error: endpoint.Error}
	if challenge := endpoint.AuthChallenge; challenge != nil {
		wire := &uiOpenConnectChallengeWire{ID: challenge.ID, Banner: challenge.Banner, Message: challenge.Message, Error: challenge.Error}
		if challenge.Form != nil {
			fields := make([]*uiOpenConnectFieldWire, 0, len(challenge.Form.fields))
			for _, field := range challenge.Form.fields {
				options := make([]*uiOpenConnectChoiceWire, 0, len(field.options))
				for _, option := range field.options {
					options = append(options, &uiOpenConnectChoiceWire{Value: option.Value, Label: option.Label})
				}
				fields = append(fields, &uiOpenConnectFieldWire{SubmissionKey: field.SubmissionKey, Name: field.Name, Label: field.Label, Kind: field.Kind, Value: field.Value, Options: options})
			}
			wire.Form = &uiOpenConnectFormWire{Fields: fields}
		}
		if challenge.Browser != nil {
			wire.Browser = &uiOpenConnectBrowserWire{URL: challenge.Browser.URL}
		}
		result.AuthChallenge = wire
	}
	if info := endpoint.TunnelInfo; info != nil {
		result.TunnelInfo = &uiOpenConnectTunnelWire{
			Server: info.Server, Flavor: info.Flavor, Transport: info.Transport,
			IPv4: uiStrings(info.ipv4), IPv6: uiStrings(info.ipv6), DNS: uiStrings(info.dns),
			MTU: info.MTU, ConnectedSince: info.ConnectedSince,
		}
	}
	return result
}

type uiOpenVPNChallengeWire struct {
	ID            string `json:"id"`
	Kind          string `json:"kind"`
	Username      string `json:"username"`
	Message       string `json:"message"`
	URL           string `json:"url"`
	SecretMessage string `json:"secretMessage"`
	Echo          bool   `json:"echo"`
	PreviousError string `json:"previousError"`
	Deadline      int64  `json:"deadline"`
}

type uiOpenVPNTunnelWire struct {
	Server         string   `json:"server"`
	Network        string   `json:"network"`
	Cipher         string   `json:"cipher"`
	IPv4           []string `json:"ipv4"`
	IPv6           []string `json:"ipv6"`
	DNS            []string `json:"dns"`
	MTU            int32    `json:"mtu"`
	ConnectedSince int64    `json:"connectedSince"`
}

type uiOpenVPNEndpointWire struct {
	EndpointTag string                  `json:"endpointTag"`
	State       string                  `json:"state"`
	StateText   string                  `json:"stateText"`
	Challenge   *uiOpenVPNChallengeWire `json:"challenge"`
	Error       string                  `json:"error"`
	TunnelInfo  *uiOpenVPNTunnelWire    `json:"tunnelInfo"`
}

func uiOpenVPNEndpointToWire(endpoint *OpenVPNEndpointStatus) *uiOpenVPNEndpointWire {
	result := &uiOpenVPNEndpointWire{EndpointTag: endpoint.EndpointTag, State: endpoint.State, StateText: endpoint.StateText, Error: endpoint.Error}
	if c := endpoint.Challenge; c != nil {
		result.Challenge = &uiOpenVPNChallengeWire{
			ID: c.ID, Kind: c.Kind, Username: c.Username, Message: c.Message, URL: c.URL,
			SecretMessage: c.SecretMessage, Echo: c.Echo, PreviousError: c.PreviousError, Deadline: c.Deadline,
		}
	}
	if info := endpoint.TunnelInfo; info != nil {
		result.TunnelInfo = &uiOpenVPNTunnelWire{
			Server: info.Server, Network: info.Network, Cipher: info.Cipher,
			IPv4: uiStrings(info.ipv4), IPv6: uiStrings(info.ipv6), DNS: uiStrings(info.dns),
			MTU: info.MTU, ConnectedSince: info.ConnectedSince,
		}
	}
	return result
}

func (s *uiEndpointStatus) payloadLocked() string {
	tailscale := make([]*uiTailscaleEndpointWire, 0, len(s.tailscale))
	for _, endpoint := range s.tailscale {
		if endpoint != nil {
			tailscale = append(tailscale, uiTailscaleEndpointToWire(endpoint))
		}
	}
	openConnect := make([]*uiOpenConnectEndpointWire, 0, len(s.openConnect))
	for _, endpoint := range s.openConnect {
		if endpoint != nil {
			openConnect = append(openConnect, uiOpenConnectEndpointToWire(endpoint))
		}
	}
	openVPN := make([]*uiOpenVPNEndpointWire, 0, len(s.openVPN))
	for _, endpoint := range s.openVPN {
		if endpoint != nil {
			openVPN = append(openVPN, uiOpenVPNEndpointToWire(endpoint))
		}
	}
	usbip := s.usbip
	if usbip == nil {
		usbip = json.RawMessage("[]")
	}
	content, _ := json.Marshal(struct {
		Tailscale   []*uiTailscaleEndpointWire   `json:"tailscale"`
		OpenConnect []*uiOpenConnectEndpointWire `json:"openconnect"`
		OpenVPN     []*uiOpenVPNEndpointWire     `json:"openvpn"`
		USBIP       json.RawMessage              `json:"usbip"`
	}{tailscale, openConnect, openVPN, usbip})
	return string(content)
}

func (s *uiEndpointStatus) payload() string {
	s.access.Lock()
	defer s.access.Unlock()
	return s.payloadLocked()
}

// Lookups (the old view model endpoint()/peer() readers).

func (s *uiEndpointStatus) tailscaleEndpoint(tag string) *TailscaleEndpointStatus {
	s.access.Lock()
	defer s.access.Unlock()
	for _, endpoint := range s.tailscale {
		if endpoint != nil && endpoint.EndpointTag == tag {
			return endpoint
		}
	}
	return nil
}

func (s *uiEndpointStatus) tailscalePeer(tag, peerID string) *TailscalePeer {
	endpoint := s.tailscaleEndpoint(tag)
	if endpoint == nil {
		return nil
	}
	if endpoint.Self != nil && uiTailscalePeerID(endpoint.Self) == peerID {
		return endpoint.Self
	}
	for _, group := range endpoint.userGroups {
		for _, peer := range group.peers {
			if peer != nil && uiTailscalePeerID(peer) == peerID {
				return peer
			}
		}
	}
	return nil
}

func (s *uiEndpointStatus) openConnectChallenge(tag string) *OpenConnectAuthChallenge {
	s.access.Lock()
	defer s.access.Unlock()
	for _, endpoint := range s.openConnect {
		if endpoint != nil && endpoint.EndpointTag == tag {
			return endpoint.AuthChallenge
		}
	}
	return nil
}

var uiEndpoints = newUIEndpointStatus(func(payload string) { _ = uiEmitRaw("tools", payload) })

// UIEndpointStatusSubscribe opens the Tailscale, OpenConnect and OpenVPN status
// streams that are not open yet.
func UIEndpointStatusSubscribe(host UIToolHost) { uiEndpoints.subscribe(host) }

// UIEndpointStatusCancel closes the status streams and drops their endpoints.
func UIEndpointStatusCancel() { uiEndpoints.cancel() }

// UIToolsSetUSBIP hands in the USBIP servers part of the "tools" payload (JSON array).
func UIToolsSetUSBIP(content string) { uiEndpoints.setUSBIP(content) }

// UIToolsPayload answers the current "tools" event payload.
func UIToolsPayload() string { return uiEndpoints.payload() }

// UITailscaleHasEndpoint reports whether the Tailscale endpoint is known.
func UITailscaleHasEndpoint(tag string) bool { return uiEndpoints.tailscaleEndpoint(tag) != nil }

// UITailscaleIsExitNodeOption reports whether a peer of the endpoint with this
// stable ID can be used as exit node.
func UITailscaleIsExitNodeOption(tag string, stableID string) bool {
	endpoint := uiEndpoints.tailscaleEndpoint(tag)
	if endpoint == nil {
		return false
	}
	for _, group := range endpoint.userGroups {
		for _, peer := range group.peers {
			if peer != nil && peer.StableID == stableID && peer.ExitNodeOption {
				return true
			}
		}
	}
	return false
}

// UITailscalePeer finds a peer (or the endpoint's own node) by its UI id
// (DNS name, else host name); nil when gone.
func UITailscalePeer(tag string, peerID string) *TailscalePeer {
	return uiEndpoints.tailscalePeer(tag, peerID)
}

// UITailscalePeerDisplayName is the old TailscalePeerData.displayName.
func UITailscalePeerDisplayName(peer *TailscalePeer) string {
	if peer == nil {
		return ""
	}
	name := peer.DNSName
	for i := 0; i < len(name); i++ {
		if name[i] == '.' {
			name = name[:i]
			break
		}
	}
	if name == "" {
		return peer.HostName
	}
	return name
}

// UIOpenConnectChallenge answers the endpoint's pending sign-in challenge; nil when none.
func UIOpenConnectChallenge(tag string) *OpenConnectAuthChallenge {
	return uiEndpoints.openConnectChallenge(tag)
}
