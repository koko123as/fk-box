// Xray symbols used by the xray plugin (plugin/xray*.go).
package upstream

import (
	xraycommonnet "github.com/xtls/xray-core/common/net"
	xraycore "github.com/xtls/xray-core/core"
	xrayinfraconfserial "github.com/xtls/xray-core/infra/conf/serial"
	xraytransportinternet "github.com/xtls/xray-core/transport/internet"

	// Registers every Xray protocol and transport (was in plugin/xray.go).
	_ "github.com/xtls/xray-core/main/distro/all"
)

// github.com/xtls/xray-core/common/net

type XrayAddress = xraycommonnet.Address

type XrayDestination = xraycommonnet.Destination

var XrayDomainAddress = xraycommonnet.DomainAddress

var XrayIPAddress = xraycommonnet.IPAddress

type XrayNetwork = xraycommonnet.Network

const XrayNetwork_TCP = xraycommonnet.Network_TCP

const XrayNetwork_UDP = xraycommonnet.Network_UDP

type XrayPacketConnWrapper = xraycommonnet.PacketConnWrapper

type XrayPort = xraycommonnet.Port

// github.com/xtls/xray-core/core

var XrayDial = xraycore.Dial

var XrayDialUDP = xraycore.DialUDP

type XrayInstance = xraycore.Instance

var XrayNew = xraycore.New

// github.com/xtls/xray-core/infra/conf/serial

var XrayLoadJSONConfig = xrayinfraconfserial.LoadJSONConfig

// github.com/xtls/xray-core/transport/internet

type XraySocketConfig = xraytransportinternet.SocketConfig

var XrayUseAlternativeSystemDialer = xraytransportinternet.UseAlternativeSystemDialer
