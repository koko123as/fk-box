package fk

// The StartedService gRPC API (sing-box daemon/started_service.proto,
// copied unchanged into core/internal/daemonpb) on the app's command.sock
// while fk-core runs Xray or mihomo (docs/kernel-isolation.md section 5.1).
// The app's official libbox CommandClient reads the dashboard, groups,
// connections and logs from it exactly as it reads sing-box's.

import (
	"context"
	"errors"
	"net"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	daemonpb "github.com/koko123as/fk-box/core/internal/daemonpb"
	"github.com/koko123as/fk-box/core/kernel"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/status"
	"google.golang.org/protobuf/proto"
	"google.golang.org/protobuf/types/known/emptypb"
)

// ---- service status ----

type statusBroadcaster struct {
	access  sync.Mutex
	current *daemonpb.ServiceStatus
	subs    map[chan *daemonpb.ServiceStatus]struct{}
}

var serviceStatus = &statusBroadcaster{
	current: &daemonpb.ServiceStatus{Status: daemonpb.ServiceStatus_IDLE},
	subs:    map[chan *daemonpb.ServiceStatus]struct{}{},
}

func (b *statusBroadcaster) set(kind daemonpb.ServiceStatus_Type, message string) {
	b.access.Lock()
	defer b.access.Unlock()
	b.current = &daemonpb.ServiceStatus{Status: kind, ErrorMessage: message}
	for sub := range b.subs {
		select {
		case sub <- b.current:
		default:
		}
	}
}

func (b *statusBroadcaster) get() *daemonpb.ServiceStatus {
	b.access.Lock()
	defer b.access.Unlock()
	return b.current
}

func (b *statusBroadcaster) subscribe() (chan *daemonpb.ServiceStatus, *daemonpb.ServiceStatus) {
	b.access.Lock()
	defer b.access.Unlock()
	sub := make(chan *daemonpb.ServiceStatus, 8)
	b.subs[sub] = struct{}{}
	return sub, b.current
}

func (b *statusBroadcaster) unsubscribe(sub chan *daemonpb.ServiceStatus) {
	b.access.Lock()
	delete(b.subs, sub)
	b.access.Unlock()
}

// ---- change notification (kernel switched, selection changed, delays) ----

var (
	changeAccess  sync.Mutex
	serviceChange = make(chan struct{})
)

func notifyServiceChange() {
	changeAccess.Lock()
	close(serviceChange)
	serviceChange = make(chan struct{})
	changeAccess.Unlock()
}

func currentServiceChange() <-chan struct{} {
	changeAccess.Lock()
	defer changeAccess.Unlock()
	return serviceChange
}

// ---- service log ----

const serviceLogMaxLines = 3000

type logEntry struct {
	level   daemonpb.LogLevel
	message string
}

type logBroadcaster struct {
	access sync.Mutex
	lines  []logEntry
	subs   map[chan *logEntry]struct{}
}

var serviceLog = &logBroadcaster{subs: map[chan *logEntry]struct{}{}}

func (l *logBroadcaster) write(level daemonpb.LogLevel, message string) {
	l.access.Lock()
	defer l.access.Unlock()
	entry := logEntry{level: level, message: message}
	l.lines = append(l.lines, entry)
	if len(l.lines) > serviceLogMaxLines {
		l.lines = append([]logEntry(nil), l.lines[len(l.lines)-serviceLogMaxLines:]...)
	}
	for sub := range l.subs {
		select {
		case sub <- &entry:
		default:
		}
	}
}

func (l *logBroadcaster) clear() {
	l.access.Lock()
	defer l.access.Unlock()
	l.lines = nil
	for sub := range l.subs {
		select {
		case sub <- nil:
		default:
		}
	}
}

var (
	xrayLogLevel   = regexp.MustCompile(`^\S+ \S+ \[(Debug|Info|Warning|Error)\] `)
	mihomoLogLevel = regexp.MustCompile(`level=(\w+) msg="(.*)"$`)
)

func (l *logBroadcaster) writeXray(line string) {
	level := daemonpb.LogLevel_INFO
	if match := xrayLogLevel.FindStringSubmatch(line); match != nil {
		switch match[1] {
		case "Debug":
			level = daemonpb.LogLevel_DEBUG
		case "Warning":
			level = daemonpb.LogLevel_WARN
		case "Error":
			level = daemonpb.LogLevel_ERROR
		}
		line = line[len(match[0]):]
	}
	l.write(level, "[xray] "+line)
}

func (l *logBroadcaster) writeMihomo(line string) {
	level := daemonpb.LogLevel_INFO
	if match := mihomoLogLevel.FindStringSubmatch(line); match != nil {
		level = mihomoServiceLogLevel(match[1])
		line = strings.ReplaceAll(match[2], `\"`, `"`)
	}
	l.write(level, "[mihomo] "+line)
}

func mihomoServiceLogLevel(level string) daemonpb.LogLevel {
	switch strings.ToLower(level) {
	case "debug":
		return daemonpb.LogLevel_DEBUG
	case "warning", "warn":
		return daemonpb.LogLevel_WARN
	case "error":
		return daemonpb.LogLevel_ERROR
	case "silent":
		return daemonpb.LogLevel_PANIC
	}
	return daemonpb.LogLevel_INFO
}

func xrayServiceLogLevel(root map[string]any) daemonpb.LogLevel {
	logConfig, _ := root["log"].(map[string]any)
	level, _ := logConfig["loglevel"].(string)
	switch strings.ToLower(level) {
	case "debug":
		return daemonpb.LogLevel_DEBUG
	case "info":
		return daemonpb.LogLevel_INFO
	case "error":
		return daemonpb.LogLevel_ERROR
	case "none":
		return daemonpb.LogLevel_PANIC
	}
	return daemonpb.LogLevel_WARN
}

// ---- memory readings kept for OOM reports ----

var (
	lastKernelMemory      atomic.Int64
	lastMihomoConnections atomic.Pointer[kernel.MihomoConnections]
)

// ---- the server on command.sock ----

var (
	commandAccess   sync.Mutex
	commandServer   *grpc.Server
	commandListener net.Listener
)

func commandSocketPath() string {
	return filepath.Join(coreFilesPath(), "command.sock")
}

// ensureCommandServer listens on command.sock unless fk-core already does.
// The app closes libbox's own server first (the socket has one owner).
func ensureCommandServer() error {
	commandAccess.Lock()
	defer commandAccess.Unlock()
	if commandServer != nil {
		return nil
	}
	path := commandSocketPath()
	if err := os.Remove(path); err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	listener, err := net.ListenUnix("unix", &net.UnixAddr{Name: path, Net: "unix"})
	if err != nil {
		return err
	}
	_ = os.Chmod(path, 0o600)
	server := grpc.NewServer(grpc.UnaryInterceptor(unaryCommandAuth), grpc.StreamInterceptor(streamCommandAuth))
	daemonpb.RegisterStartedServiceServer(server, &startedService{})
	go func() { _ = server.Serve(listener) }()
	commandServer, commandListener = server, listener
	return nil
}

// CloseCommandServer stops serving command.sock (the app's libbox server
// takes it back for sing-box).
func CloseCommandServer() {
	commandAccess.Lock()
	server := commandServer
	commandServer, commandListener = nil, nil
	commandAccess.Unlock()
	if server != nil {
		server.Stop()
		_ = os.Remove(commandSocketPath())
	}
}

func checkCommandSecret(ctx context.Context) error {
	serviceAccess.RLock()
	secret := serviceSecret
	serviceAccess.RUnlock()
	if secret == "" {
		return nil
	}
	md, ok := metadata.FromIncomingContext(ctx)
	if !ok {
		return status.Error(codes.Unauthenticated, "missing metadata")
	}
	values := md.Get("x-command-secret")
	if len(values) == 0 || values[0] != secret {
		return status.Error(codes.Unauthenticated, "invalid authentication secret")
	}
	return nil
}

func unaryCommandAuth(ctx context.Context, req any, _ *grpc.UnaryServerInfo, handler grpc.UnaryHandler) (any, error) {
	if err := checkCommandSecret(ctx); err != nil {
		return nil, err
	}
	return handler(ctx, req)
}

func streamCommandAuth(srv any, ss grpc.ServerStream, _ *grpc.StreamServerInfo, handler grpc.StreamHandler) error {
	if err := checkCommandSecret(ss.Context()); err != nil {
		return err
	}
	return handler(srv, ss)
}

// ---- StartedService ----

type startedService struct {
	daemonpb.UnimplementedStartedServiceServer
}

var errNotRunning = status.Error(codes.FailedPrecondition, "no kernel is running")

func (*startedService) GetVersion(context.Context, *emptypb.Empty) (*daemonpb.Version, error) {
	return &daemonpb.Version{Version: "fk-core " + Version(), ApiVersion: 1}, nil
}

func (*startedService) GetStartedAt(context.Context, *emptypb.Empty) (*daemonpb.StartedAt, error) {
	if running := runningKernel(); running != nil {
		return &daemonpb.StartedAt{StartedAt: running.startedAt.UnixMilli()}, nil
	}
	return &daemonpb.StartedAt{}, nil
}

func (*startedService) SubscribeServiceStatus(_ *emptypb.Empty, server grpc.ServerStreamingServer[daemonpb.ServiceStatus]) error {
	sub, current := serviceStatus.subscribe()
	defer serviceStatus.unsubscribe(sub)
	if err := server.Send(current); err != nil {
		return err
	}
	for {
		select {
		case <-server.Context().Done():
			return server.Context().Err()
		case next := <-sub:
			if err := server.Send(next); err != nil {
				return err
			}
		}
	}
}

func (*startedService) SubscribeLog(_ *emptypb.Empty, server grpc.ServerStreamingServer[daemonpb.Log]) error {
	serviceLog.access.Lock()
	saved := append([]logEntry(nil), serviceLog.lines...)
	sub := make(chan *logEntry, 512)
	serviceLog.subs[sub] = struct{}{}
	serviceLog.access.Unlock()
	defer func() {
		serviceLog.access.Lock()
		delete(serviceLog.subs, sub)
		serviceLog.access.Unlock()
	}()
	first := &daemonpb.Log{Reset_: true}
	for _, entry := range saved {
		first.Messages = append(first.Messages, &daemonpb.Log_Message{Level: entry.level, Message: entry.message})
	}
	if err := server.Send(first); err != nil {
		return err
	}
	for {
		select {
		case <-server.Context().Done():
			return server.Context().Err()
		case entry := <-sub:
			message := &daemonpb.Log{}
			add := func(entry *logEntry) {
				if entry == nil {
					message.Messages = nil
					message.Reset_ = true
					return
				}
				message.Messages = append(message.Messages, &daemonpb.Log_Message{Level: entry.level, Message: entry.message})
			}
			add(entry)
		drain:
			for {
				select {
				case entry = <-sub:
					add(entry)
				default:
					break drain
				}
			}
			if err := server.Send(message); err != nil {
				return err
			}
		}
	}
}

func (*startedService) GetDefaultLogLevel(context.Context, *emptypb.Empty) (*daemonpb.DefaultLogLevel, error) {
	running := runningKernel()
	if running == nil {
		return nil, errNotRunning
	}
	return &daemonpb.DefaultLogLevel{Level: running.logLevel}, nil
}

func (*startedService) ClearLogs(context.Context, *emptypb.Empty) (*emptypb.Empty, error) {
	serviceLog.clear()
	return &emptypb.Empty{}, nil
}

// readServiceStatus reads the kernel's memory, connections and traffic
// totals.
func readServiceStatus(ctx context.Context, running *serviceKernel) *daemonpb.Status {
	result := &daemonpb.Status{}
	if running == nil {
		return result
	}
	process := running.process.Load()
	if process == nil {
		return result
	}
	rss, _ := kernel.Memory(process.Pid())
	lastKernelMemory.Store(rss)
	result.Memory = uint64(rss)
	switch running.core {
	case CoreMihomo:
		if connections, err := running.mihomo.Connections(ctx); err == nil {
			lastMihomoConnections.Store(connections)
			result.TrafficAvailable = true
			result.UplinkTotal = connections.UploadTotal
			result.DownlinkTotal = connections.DownloadTotal
			result.ConnectionsIn = int32(len(connections.Connections))
			result.ConnectionsOut = result.ConnectionsIn
		}
	case CoreXray:
		if goroutines, err := running.xray.Goroutines(ctx); err == nil {
			result.Goroutines = goroutines
		}
		// Only the TUN inbound is counted: delay tests and fk-core's own
		// requests through the local inbound stay out of the totals.
		if stats, err := running.xray.QueryStats(ctx, "inbound>>>"+xrayTunInboundTag+">>>traffic>>>", false); err == nil {
			result.TrafficAvailable = true
			for name, value := range stats {
				switch {
				case strings.HasSuffix(name, ">>>uplink"):
					result.UplinkTotal += value
				case strings.HasSuffix(name, ">>>downlink"):
					result.DownlinkTotal += value
				}
			}
		}
	}
	return result
}

func (*startedService) SubscribeStatus(request *daemonpb.SubscribeStatusRequest, server grpc.ServerStreamingServer[daemonpb.Status]) error {
	interval := time.Duration(request.Interval)
	if interval <= 0 {
		interval = time.Second
	}
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	var previous *daemonpb.Status
	var previousKernel *serviceKernel
	for {
		running := runningKernel()
		ctx, cancel := context.WithTimeout(server.Context(), 3*time.Second)
		current := readServiceStatus(ctx, running)
		cancel()
		if previous != nil && previousKernel == running {
			current.Uplink = max(current.UplinkTotal-previous.UplinkTotal, 0)
			current.Downlink = max(current.DownlinkTotal-previous.DownlinkTotal, 0)
		}
		previous, previousKernel = current, running
		if err := server.Send(current); err != nil {
			return err
		}
		select {
		case <-server.Context().Done():
			return server.Context().Err()
		case <-ticker.C:
		}
	}
}

// ---- groups ----

var (
	expandAccess sync.Mutex
	groupExpand  = map[string]bool{}
)

func groupExpanded(tag string) bool {
	expandAccess.Lock()
	defer expandAccess.Unlock()
	expanded, ok := groupExpand[tag]
	return !ok || expanded
}

// xrayAutoItem is the entry of a balancer group that follows its strategy;
// the other entries pin it (OverrideBalancerTarget).
const xrayAutoItem = "auto"

func readServiceGroups(ctx context.Context, running *serviceKernel) *daemonpb.Groups {
	groups := &daemonpb.Groups{}
	if running == nil {
		return groups
	}
	switch running.core {
	case CoreMihomo:
		proxies, err := running.mihomo.Proxies(ctx)
		if err != nil {
			return groups
		}
		mode, _ := running.mihomo.Mode(ctx)
		for _, name := range mihomoGroupOrder(proxies, mode) {
			proxy := proxies[name]
			if proxy.Hidden {
				continue
			}
			group := &daemonpb.Group{
				Tag:        name,
				Type:       strings.ToLower(proxy.Type),
				Selectable: strings.EqualFold(proxy.Type, "Selector"),
				Selected:   proxy.Now,
				IsExpand:   groupExpanded(name),
			}
			for _, member := range proxy.All {
				entry := &daemonpb.GroupItem{Tag: member, Type: strings.ToLower(proxies[member].Type)}
				if delay, at := proxies[member].LastDelay(); delay > 0 {
					entry.UrlTestDelay = int32(delay)
					entry.UrlTestTime = at.Unix()
				}
				group.Items = append(group.Items, entry)
			}
			if len(group.Items) > 0 {
				groups.Group = append(groups.Group, group)
			}
		}
	case CoreXray:
		layout := running.layout
		if layout == nil {
			return groups
		}
		running.delayAccess.Lock()
		delays := make(map[string]serviceDelay, len(running.delays))
		for tag, delay := range running.delays {
			delays[tag] = delay
		}
		running.delayAccess.Unlock()
		for _, balancer := range layout.balancers {
			group := &daemonpb.Group{Tag: balancer.tag, Type: "urltest", Selectable: true, Selected: xrayAutoItem, IsExpand: groupExpanded(balancer.tag)}
			auto := &daemonpb.GroupItem{Tag: xrayAutoItem, Type: balancer.strategy}
			if override, principle, err := running.xray.BalancerInfo(ctx, balancer.tag); err == nil {
				if override != "" {
					group.Selected = override
				} else if len(principle) > 0 {
					auto.Type = balancer.strategy + " → " + principle[0]
				}
			}
			group.Items = append(group.Items, auto)
			for _, tag := range balancer.members {
				entry := &daemonpb.GroupItem{Tag: tag, Type: layout.protocols[tag]}
				if delay, ok := delays[tag]; ok {
					entry.UrlTestDelay, entry.UrlTestTime = delay.delay, delay.at.Unix()
				}
				group.Items = append(group.Items, entry)
			}
			groups.Group = append(groups.Group, group)
		}
		if len(layout.balancers) == 0 && len(layout.outbounds) > 0 {
			// No balancer: the outbounds are listed for their delays.
			group := &daemonpb.Group{Tag: "outbounds", Type: "outbounds", Selected: layout.outbounds[0], IsExpand: groupExpanded("outbounds")}
			for _, tag := range layout.outbounds {
				entry := &daemonpb.GroupItem{Tag: tag, Type: layout.protocols[tag]}
				if delay, ok := delays[tag]; ok {
					entry.UrlTestDelay, entry.UrlTestTime = delay.delay, delay.at.Unix()
				}
				group.Items = append(group.Items, entry)
			}
			groups.Group = append(groups.Group, group)
		}
	}
	return groups
}

// mihomoGroupOrder lists mihomo's groups in profile order (GLOBAL's member
// order), then the rest by name; GLOBAL leads in global mode and trails
// otherwise.
func mihomoGroupOrder(proxies map[string]kernel.MihomoProxy, mode string) []string {
	var ordered []string
	seen := map[string]bool{"GLOBAL": true}
	if global, ok := proxies["GLOBAL"]; ok {
		for _, name := range global.All {
			if len(proxies[name].All) > 0 && !seen[name] {
				seen[name] = true
				ordered = append(ordered, name)
			}
		}
	}
	var rest []string
	for name, proxy := range proxies {
		if len(proxy.All) > 0 && !seen[name] {
			rest = append(rest, name)
		}
	}
	sort.Strings(rest)
	ordered = append(ordered, rest...)
	if _, ok := proxies["GLOBAL"]; ok {
		if strings.EqualFold(mode, "global") {
			ordered = append([]string{"GLOBAL"}, ordered...)
		} else {
			ordered = append(ordered, "GLOBAL")
		}
	}
	return ordered
}

func groupsWithoutTestTime(groups *daemonpb.Groups) *daemonpb.Groups {
	clone := proto.Clone(groups).(*daemonpb.Groups)
	for _, group := range clone.Group {
		for _, item := range group.Items {
			item.UrlTestTime = 0
		}
	}
	return clone
}

func (*startedService) SubscribeGroups(_ *emptypb.Empty, server grpc.ServerStreamingServer[daemonpb.Groups]) error {
	// Delays change without a notification (mihomo health checks), so the
	// list is also read again periodically and sent when it changed.
	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()
	var last *daemonpb.Groups
	for {
		change := currentServiceChange()
		ctx, cancel := context.WithTimeout(server.Context(), 5*time.Second)
		current := readServiceGroups(ctx, runningKernel())
		cancel()
		if last == nil || !proto.Equal(groupsWithoutTestTime(current), groupsWithoutTestTime(last)) {
			if err := server.Send(current); err != nil {
				return err
			}
			last = current
		}
		select {
		case <-server.Context().Done():
			return server.Context().Err()
		case <-change:
		case <-ticker.C:
		}
	}
}

func (*startedService) SubscribeOutbounds(_ *emptypb.Empty, server grpc.ServerStreamingServer[daemonpb.OutboundList]) error {
	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()
	var last *daemonpb.OutboundList
	for {
		change := currentServiceChange()
		ctx, cancel := context.WithTimeout(server.Context(), 5*time.Second)
		current := &daemonpb.OutboundList{}
		seen := map[string]bool{}
		for _, group := range readServiceGroups(ctx, runningKernel()).Group {
			for _, item := range group.Items {
				if item.Tag != xrayAutoItem && !seen[item.Tag] {
					seen[item.Tag] = true
					current.Outbounds = append(current.Outbounds, item)
				}
			}
		}
		cancel()
		if last == nil || !proto.Equal(current, last) {
			if err := server.Send(current); err != nil {
				return err
			}
			last = current
		}
		select {
		case <-server.Context().Done():
			return server.Context().Err()
		case <-change:
		case <-ticker.C:
		}
	}
}

func (*startedService) SetGroupExpand(_ context.Context, request *daemonpb.SetGroupExpandRequest) (*emptypb.Empty, error) {
	expandAccess.Lock()
	groupExpand[request.GroupTag] = request.IsExpand
	expandAccess.Unlock()
	notifyServiceChange()
	return &emptypb.Empty{}, nil
}

func (*startedService) SelectOutbound(ctx context.Context, request *daemonpb.SelectOutboundRequest) (*emptypb.Empty, error) {
	running := runningKernel()
	if running == nil {
		return nil, errNotRunning
	}
	switch running.core {
	case CoreMihomo:
		if err := running.mihomo.Select(ctx, request.GroupTag, request.OutboundTag); err != nil {
			return nil, status.Error(codes.InvalidArgument, err.Error())
		}
		// Connections through the group keep their old route otherwise.
		if connections, err := running.mihomo.Connections(ctx); err == nil {
			for _, connection := range connections.Connections {
				for _, chain := range connection.Chains {
					if chain == request.GroupTag {
						_ = running.mihomo.CloseConnection(ctx, connection.ID)
						break
					}
				}
			}
		}
	case CoreXray:
		target := request.OutboundTag
		if target == xrayAutoItem {
			target = ""
		}
		if err := running.xray.OverrideBalancer(ctx, request.GroupTag, target); err != nil {
			return nil, status.Error(codes.NotFound, err.Error())
		}
	}
	notifyServiceChange()
	return &emptypb.Empty{}, nil
}

func (*startedService) URLTest(_ context.Context, request *daemonpb.URLTestRequest) (*emptypb.Empty, error) {
	running := runningKernel()
	if running == nil {
		return nil, errNotRunning
	}
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		switch running.core {
		case CoreMihomo:
			proxies, err := running.mihomo.Proxies(ctx)
			if err != nil {
				return
			}
			if len(proxies[request.OutboundTag].All) > 0 {
				_, _ = running.mihomo.GroupDelay(ctx, request.OutboundTag, serviceTestURL, 5*time.Second)
			} else {
				_, _ = running.mihomo.Delay(ctx, request.OutboundTag, serviceTestURL, 5*time.Second)
			}
		case CoreXray:
			xrayURLTest(ctx, running, request.OutboundTag)
		}
		notifyServiceChange()
	}()
	return &emptypb.Empty{}, nil
}

// ---- clash mode (mihomo) ----

var mihomoModes = []string{"rule", "global", "direct"}

func (*startedService) GetClashModeStatus(ctx context.Context, _ *emptypb.Empty) (*daemonpb.ClashModeStatus, error) {
	running := runningKernel()
	if running == nil || running.core != CoreMihomo {
		return nil, status.Error(codes.NotFound, "clash mode not available")
	}
	mode, err := running.mihomo.Mode(ctx)
	if err != nil {
		return nil, status.Error(codes.Unavailable, err.Error())
	}
	return &daemonpb.ClashModeStatus{ModeList: mihomoModes, CurrentMode: strings.ToLower(mode)}, nil
}

func (*startedService) SubscribeClashMode(_ *emptypb.Empty, server grpc.ServerStreamingServer[daemonpb.ClashMode]) error {
	var last *daemonpb.ClashMode
	for {
		change := currentServiceChange()
		message := &daemonpb.ClashMode{}
		if running := runningKernel(); running != nil && running.core == CoreMihomo {
			ctx, cancel := context.WithTimeout(server.Context(), 3*time.Second)
			mode, err := running.mihomo.Mode(ctx)
			cancel()
			if err == nil {
				message.Mode = strings.ToLower(mode)
			}
		}
		if last == nil || last.Mode != message.Mode {
			if err := server.Send(message); err != nil {
				return err
			}
			last = message
		}
		select {
		case <-server.Context().Done():
			return server.Context().Err()
		case <-change:
		case <-time.After(10 * time.Second):
		}
	}
}

func (*startedService) SetClashMode(ctx context.Context, request *daemonpb.ClashMode) (*emptypb.Empty, error) {
	running := runningKernel()
	if running == nil || running.core != CoreMihomo {
		return nil, status.Error(codes.NotFound, "clash mode not available")
	}
	if err := running.mihomo.SetMode(ctx, request.Mode); err != nil {
		return nil, status.Error(codes.InvalidArgument, err.Error())
	}
	notifyServiceChange()
	return &emptypb.Empty{}, nil
}

// ---- connections (mihomo; Xray has no per-connection API) ----

func mihomoConnectionProto(c kernel.MihomoConnection) *daemonpb.Connection {
	ipVersion := int32(4)
	if strings.Contains(c.Metadata.SourceIP, ":") {
		ipVersion = 6
	}
	destination := c.Metadata.DestinationIP
	if destination == "" {
		destination = c.Metadata.Host
	}
	connection := &daemonpb.Connection{
		Id:            c.ID,
		Inbound:       c.Metadata.InboundName,
		InboundType:   strings.ToLower(c.Metadata.Type),
		IpVersion:     ipVersion,
		Network:       strings.ToLower(c.Metadata.Network),
		Source:        net.JoinHostPort(c.Metadata.SourceIP, c.Metadata.SourcePort),
		Destination:   net.JoinHostPort(destination, c.Metadata.DestinationPort),
		Domain:        firstNonEmpty(c.Metadata.Host, c.Metadata.SniffHost),
		User:          c.Metadata.InboundUser,
		CreatedAt:     c.Start.UnixMilli(),
		UplinkTotal:   c.Upload,
		DownlinkTotal: c.Download,
		Rule:          strings.TrimSpace(c.Rule + " " + c.RulePayload),
		ChainList:     c.Chains,
	}
	if len(c.Chains) > 0 {
		connection.Outbound = c.Chains[0]
		connection.FromOutbound = c.Chains[len(c.Chains)-1]
	}
	if c.Metadata.Process != "" || c.Metadata.ProcessPath != "" {
		connection.ProcessInfo = &daemonpb.ProcessInfo{ProcessPath: c.Metadata.ProcessPath, PackageNames: nonEmpty(c.Metadata.Process)}
		if c.Metadata.UID != nil {
			connection.ProcessInfo.UserId = *c.Metadata.UID
		}
	}
	return connection
}

func nonEmpty(value string) []string {
	if value == "" {
		return nil
	}
	return []string{value}
}

type connectionSnapshot struct {
	uplink, downlink int64
	connection       *daemonpb.Connection
}

func (*startedService) SubscribeConnections(request *daemonpb.SubscribeConnectionsRequest, server grpc.ServerStreamingServer[daemonpb.ConnectionEvents]) error {
	running := runningKernel()
	if running == nil {
		return errNotRunning
	}
	if running.core != CoreMihomo {
		return status.Error(codes.Unimplemented, "connection tracking not available")
	}
	interval := time.Duration(request.Interval)
	if interval <= 0 {
		interval = time.Second
	}
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	snapshots := map[string]*connectionSnapshot{}
	first := true
	change := currentServiceChange()
	for {
		ctx, cancel := context.WithTimeout(server.Context(), 5*time.Second)
		connections, err := running.mihomo.Connections(ctx)
		cancel()
		if err == nil {
			lastMihomoConnections.Store(connections)
			events := &daemonpb.ConnectionEvents{Reset_: first}
			seen := map[string]bool{}
			for _, c := range connections.Connections {
				seen[c.ID] = true
				snapshot, ok := snapshots[c.ID]
				if !ok {
					message := mihomoConnectionProto(c)
					snapshots[c.ID] = &connectionSnapshot{uplink: c.Upload, downlink: c.Download, connection: message}
					events.Events = append(events.Events, &daemonpb.ConnectionEvent{Type: daemonpb.ConnectionEventType_CONNECTION_EVENT_NEW, Id: c.ID, Connection: message})
					continue
				}
				up, down := c.Upload-snapshot.uplink, c.Download-snapshot.downlink
				if up > 0 || down > 0 {
					snapshot.uplink, snapshot.downlink = c.Upload, c.Download
					events.Events = append(events.Events, &daemonpb.ConnectionEvent{Type: daemonpb.ConnectionEventType_CONNECTION_EVENT_UPDATE, Id: c.ID, UplinkDelta: up, DownlinkDelta: down})
				}
			}
			now := time.Now().UnixMilli()
			for id, snapshot := range snapshots {
				if !seen[id] {
					delete(snapshots, id)
					closed := proto.Clone(snapshot.connection).(*daemonpb.Connection)
					closed.ClosedAt = now
					closed.UplinkTotal, closed.DownlinkTotal = snapshot.uplink, snapshot.downlink
					events.Events = append(events.Events, &daemonpb.ConnectionEvent{Type: daemonpb.ConnectionEventType_CONNECTION_EVENT_CLOSED, Id: id, ClosedAt: now, Connection: closed})
				}
			}
			if first || len(events.Events) > 0 {
				if err := server.Send(events); err != nil {
					return err
				}
				first = false
			}
		}
		select {
		case <-server.Context().Done():
			return server.Context().Err()
		case <-change:
			if runningKernel() != running {
				return status.Error(codes.Aborted, "kernel changed")
			}
			change = currentServiceChange()
		case <-ticker.C:
		}
	}
}

func (*startedService) CloseConnection(ctx context.Context, request *daemonpb.CloseConnectionRequest) (*emptypb.Empty, error) {
	running := runningKernel()
	if running == nil || running.core != CoreMihomo {
		return nil, status.Error(codes.Unimplemented, "connection tracking not available")
	}
	if err := running.mihomo.CloseConnection(ctx, request.Id); err != nil {
		return nil, status.Error(codes.NotFound, err.Error())
	}
	return &emptypb.Empty{}, nil
}

func (*startedService) CloseAllConnections(ctx context.Context, _ *emptypb.Empty) (*emptypb.Empty, error) {
	running := runningKernel()
	if running == nil || running.core != CoreMihomo {
		return nil, status.Error(codes.Unimplemented, "connection tracking not available")
	}
	if err := running.mihomo.CloseAllConnections(ctx); err != nil {
		return nil, status.Error(codes.Unavailable, err.Error())
	}
	return &emptypb.Empty{}, nil
}

func (*startedService) GetDeprecatedWarnings(context.Context, *emptypb.Empty) (*daemonpb.DeprecatedWarnings, error) {
	return &daemonpb.DeprecatedWarnings{}, nil
}

func (*startedService) SubscribeNotifications(_ *emptypb.Empty, server grpc.ServerStreamingServer[daemonpb.NotificationEvent]) error {
	<-server.Context().Done()
	return server.Context().Err()
}
