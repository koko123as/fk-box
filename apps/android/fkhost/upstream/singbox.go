// Package upstream is the only place fkhost's product code reaches sing-box,
// sing, sing-tun, mihomo and Xray (stage 7, docs/architecture/brain-upgrade.md).
// Every upstream symbol the host and its plugins use is re-exported here:
// types as aliases, constants as constants, functions as variables (generic
// ones as thin generic wrappers), package variables the code writes as
// setters. When an upgrade changes an upstream API, the build fails in this
// package first, at the one line that names the moved symbol, instead of in
// the host or a plugin. scripts/check_upstream_imports.py keeps product code
// (everything but tests, internal/fixture and e2e) importing only this
// package. Names: sing-box/sing/sing-tun keep their own names (collisions:
// NewError, NewTun, TunOptions, DialerConfig, SingBoxVersion); mihomo and
// Xray symbols carry a Mihomo or Xray prefix.
package upstream

import (
	"context"

	sb "github.com/sagernet/sing-box"
	sbadapter "github.com/sagernet/sing-box/adapter"
	sbadapteroutbound "github.com/sagernet/sing-box/adapter/outbound"
	sbcommondialer "github.com/sagernet/sing-box/common/dialer"
	sbcommonsniff "github.com/sagernet/sing-box/common/sniff"
	sbconstant "github.com/sagernet/sing-box/constant"
	sbdaemon "github.com/sagernet/sing-box/daemon"
	sbdns "github.com/sagernet/sing-box/dns"
	sbinclude "github.com/sagernet/sing-box/include"
	sblog "github.com/sagernet/sing-box/log"
	sboption "github.com/sagernet/sing-box/option"
	sbprotocoldns "github.com/sagernet/sing-box/protocol/dns"
	sbrouterule "github.com/sagernet/sing-box/route/rule"
	singcommonbuf "github.com/sagernet/sing/common/buf"
	singcommonbufio "github.com/sagernet/sing/common/bufio"
	singcommoncontrol "github.com/sagernet/sing/common/control"
	singcommonexceptions "github.com/sagernet/sing/common/exceptions"
	singcommonlogger "github.com/sagernet/sing/common/logger"
	singcommonmetadata "github.com/sagernet/sing/common/metadata"
	singcommonnetwork "github.com/sagernet/sing/common/network"
	singcommonxlist "github.com/sagernet/sing/common/x/list"
	singservice "github.com/sagernet/sing/service"
	tun "github.com/sagernet/sing-tun"
)

// github.com/sagernet/sing-box

type Box = sb.Box

var Context = sb.Context

// github.com/sagernet/sing-box/adapter

type AutoRedirectOptions = sbadapter.AutoRedirectOptions

type AutoRedirectSession = sbadapter.AutoRedirectSession

type BridgeOptions = sbadapter.BridgeOptions

type BridgeSession = sbadapter.BridgeSession

type ConnectionHandler = sbadapter.ConnectionHandler

type ConnectionManager = sbadapter.ConnectionManager

type ConnectionOwner = sbadapter.ConnectionOwner

type DNSQueryOptions = sbadapter.DNSQueryOptions

type DNSRouter = sbadapter.DNSRouter

type DNSTransport = sbadapter.DNSTransport

type FindConnectionOwnerRequest = sbadapter.FindConnectionOwnerRequest

type InboundContext = sbadapter.InboundContext

type InboundManager = sbadapter.InboundManager

type NeighborUpdateListener = sbadapter.NeighborUpdateListener

type NetworkInterface = sbadapter.NetworkInterface

type NetworkManager = sbadapter.NetworkManager

type Notification = sbadapter.Notification

type Outbound = sbadapter.Outbound

type OutboundManager = sbadapter.OutboundManager

type PacketConnectionHandler = sbadapter.PacketConnectionHandler

type PlatformInterface = sbadapter.PlatformInterface

type PlatformUser = sbadapter.PlatformUser

type Router = sbadapter.Router

type Scope = sbadapter.Scope

type ShellSession = sbadapter.ShellSession

type StartStage = sbadapter.StartStage

const StartStateInitialize = sbadapter.StartStateInitialize

type WIFIState = sbadapter.WIFIState

// github.com/sagernet/sing-box/adapter/outbound

type Adapter = sbadapteroutbound.Adapter

var NewAdapter = sbadapteroutbound.NewAdapter

var NewAdapterWithDialerOptions = sbadapteroutbound.NewAdapterWithDialerOptions

func Register[Options any](registry *sbadapteroutbound.Registry, outboundType string, constructor sbadapteroutbound.ConstructorFunc[Options]) {
	sbadapteroutbound.Register[Options](registry, outboundType, constructor)
}

type Registry = sbadapteroutbound.Registry

// github.com/sagernet/sing-box/common/dialer

var NewWithOptions = sbcommondialer.NewWithOptions

type DialerConfig = sbcommondialer.Options

type PacketDialerWithDestination = sbcommondialer.PacketDialerWithDestination

// github.com/sagernet/sing-box/common/sniff

var BitTorrent = sbcommonsniff.BitTorrent

var DTLSRecord = sbcommonsniff.DTLSRecord

var DomainNameQuery = sbcommonsniff.DomainNameQuery

var ErrNeedMoreData = sbcommonsniff.ErrNeedMoreData

var HTTPHost = sbcommonsniff.HTTPHost

var NTP = sbcommonsniff.NTP

type PacketSniffer = sbcommonsniff.PacketSniffer

var PeekPacket = sbcommonsniff.PeekPacket

var PeekStream = sbcommonsniff.PeekStream

var QUICClientHello = sbcommonsniff.QUICClientHello

var RDP = sbcommonsniff.RDP

var SSH = sbcommonsniff.SSH

var STUNMessage = sbcommonsniff.STUNMessage

var Skip = sbcommonsniff.Skip

var StreamDomainNameQuery = sbcommonsniff.StreamDomainNameQuery

type StreamSniffer = sbcommonsniff.StreamSniffer

var TLSClientHello = sbcommonsniff.TLSClientHello

var UDPTracker = sbcommonsniff.UDPTracker

var UTP = sbcommonsniff.UTP

// github.com/sagernet/sing-box/constant

const DNSTimeout = sbconstant.DNSTimeout

type InterfaceType = sbconstant.InterfaceType

const ProtocolBitTorrent = sbconstant.ProtocolBitTorrent

const ProtocolDNS = sbconstant.ProtocolDNS

const ProtocolDTLS = sbconstant.ProtocolDTLS

const ProtocolHTTP = sbconstant.ProtocolHTTP

const ProtocolNTP = sbconstant.ProtocolNTP

const ProtocolQUIC = sbconstant.ProtocolQUIC

const ProtocolRDP = sbconstant.ProtocolRDP

const ProtocolSSH = sbconstant.ProtocolSSH

const ProtocolSTUN = sbconstant.ProtocolSTUN

const ProtocolTLS = sbconstant.ProtocolTLS

const ReadPayloadTimeout = sbconstant.ReadPayloadTimeout

func SingBoxVersion() string {
	return sbconstant.Version
}

// github.com/sagernet/sing-box/daemon

var NewStartedService = sbdaemon.NewStartedService

type ServiceOptions = sbdaemon.ServiceOptions

type StartedService = sbdaemon.StartedService

// github.com/sagernet/sing-box/dns

var NewTransportAdapter = sbdns.NewTransportAdapter

func RegisterTransport[Options any](registry *sbdns.TransportRegistry, transportType string, constructor sbdns.TransportConstructorFunc[Options]) {
	sbdns.RegisterTransport[Options](registry, transportType, constructor)
}

type TransportAdapter = sbdns.TransportAdapter

type TransportRegistry = sbdns.TransportRegistry

// github.com/sagernet/sing-box/include

var CertificateProviderRegistry = sbinclude.CertificateProviderRegistry

var DNSTransportRegistry = sbinclude.DNSTransportRegistry

var EndpointRegistry = sbinclude.EndpointRegistry

var InboundRegistry = sbinclude.InboundRegistry

var OutboundRegistry = sbinclude.OutboundRegistry

var ServiceRegistry = sbinclude.ServiceRegistry

// github.com/sagernet/sing-box/log

type ContextLogger = sblog.ContextLogger

// github.com/sagernet/sing-box/option

type DialerOptions = sboption.DialerOptions

type TunPlatformOptions = sboption.TunPlatformOptions

// github.com/sagernet/sing-box/protocol/dns

var HandleStreamDNSRequest = sbprotocoldns.HandleStreamDNSRequest

var NewDNSPacketConnection = sbprotocoldns.NewDNSPacketConnection

// github.com/sagernet/sing-box/route/rule

var ErrDrop = sbrouterule.ErrDrop

var ErrReset = sbrouterule.ErrReset

type RejectedError = sbrouterule.RejectedError

// github.com/sagernet/sing-tun

type DefaultInterfaceMonitor = tun.DefaultInterfaceMonitor

type DefaultInterfaceUpdateCallback = tun.DefaultInterfaceUpdateCallback

var NewTun = tun.New

type TunOptions = tun.Options

type Tun = tun.Tun

// github.com/sagernet/sing/common/buf

var NewPacket = singcommonbuf.NewPacket

// github.com/sagernet/sing/common/bufio

var NewBindPacketConn = singcommonbufio.NewBindPacketConn

var NewCachedConn = singcommonbufio.NewCachedConn

var NewCachedPacketConn = singcommonbufio.NewCachedPacketConn

var NewInt64CounterConn = singcommonbufio.NewInt64CounterConn

var NewInt64CounterPacketConn = singcommonbufio.NewInt64CounterPacketConn

var NewUnbindPacketConn = singcommonbufio.NewUnbindPacketConn

var NewUnbindPacketConnWithAddr = singcommonbufio.NewUnbindPacketConnWithAddr

// github.com/sagernet/sing/common/control

type Interface = singcommoncontrol.Interface

// github.com/sagernet/sing/common/exceptions

var Cause = singcommonexceptions.Cause

var Errors = singcommonexceptions.Errors

var IsClosedOrCanceled = singcommonexceptions.IsClosedOrCanceled

var IsTimeout = singcommonexceptions.IsTimeout

var NewError = singcommonexceptions.New

// github.com/sagernet/sing/common/logger

type Logger = singcommonlogger.Logger

// github.com/sagernet/sing/common/metadata

var ParseSocksaddr = singcommonmetadata.ParseSocksaddr

var ParseSocksaddrHostPortStr = singcommonmetadata.ParseSocksaddrHostPortStr

type Socksaddr = singcommonmetadata.Socksaddr

var SocksaddrFrom = singcommonmetadata.SocksaddrFrom

var SocksaddrFromNetIP = singcommonmetadata.SocksaddrFromNetIP

// github.com/sagernet/sing/common/network

var AppendClose = singcommonnetwork.AppendClose

type CloseHandlerFunc = singcommonnetwork.CloseHandlerFunc

var CloseOnHandshakeFailure = singcommonnetwork.CloseOnHandshakeFailure

type Dialer = singcommonnetwork.Dialer

var NetworkName = singcommonnetwork.NetworkName

const NetworkTCP = singcommonnetwork.NetworkTCP

const NetworkUDP = singcommonnetwork.NetworkUDP

var NewPacketBuffer = singcommonnetwork.NewPacketBuffer

type PacketBuffer = singcommonnetwork.PacketBuffer

type PacketConn = singcommonnetwork.PacketConn

var PutPacketBuffer = singcommonnetwork.PutPacketBuffer

var ReleaseMultiPacketBuffer = singcommonnetwork.ReleaseMultiPacketBuffer

var ReportConnHandshakeSuccess = singcommonnetwork.ReportConnHandshakeSuccess

var ReportPacketConnHandshakeSuccess = singcommonnetwork.ReportPacketConnHandshakeSuccess

// github.com/sagernet/sing/common/x/list

type Element[T any] = singcommonxlist.Element[T]

type List[T any] = singcommonxlist.List[T]

// github.com/sagernet/sing/service

var ContextWithDefaultRegistry = singservice.ContextWithDefaultRegistry

func FromContext[T any](ctx context.Context) T {
	return singservice.FromContext[T](ctx)
}

func MustRegister[T any](ctx context.Context, service T) {
	singservice.MustRegister[T](ctx, service)
}
