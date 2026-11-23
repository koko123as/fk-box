package io.nekohasekai.sfa.bridge

import io.nekohasekai.sfa.backend.tools.TailscalePeerData
import io.nekohasekai.sfa.backend.tools.TailscaleUserGroupData
import io.nekohasekai.sfa.backend.tools.TailscaleEndpointData
import io.nekohasekai.sfa.backend.tools.OpenConnectAuthFormChoiceData
import io.nekohasekai.sfa.backend.tools.OpenConnectAuthFormFieldData
import io.nekohasekai.sfa.backend.tools.OpenConnectAuthFormData
import io.nekohasekai.sfa.backend.tools.OpenConnectBrowserRequestData
import io.nekohasekai.sfa.backend.tools.OpenConnectAuthChallengeData
import io.nekohasekai.sfa.backend.tools.OpenConnectTunnelInfoData
import io.nekohasekai.sfa.backend.tools.OpenConnectEndpointData
import io.nekohasekai.sfa.backend.tools.OpenVPNChallengeData
import io.nekohasekai.sfa.backend.tools.OpenVPNTunnelInfoData
import io.nekohasekai.sfa.backend.tools.OpenVPNEndpointData
import io.nekohasekai.sfa.backend.usbip.UsbSharedInterfaceData
import io.nekohasekai.sfa.backend.usbip.UsbSharedDeviceData
import io.nekohasekai.sfa.backend.usbip.UsbipServerData

internal fun TailscalePeerData.toWire(): Map<String, Any?> = mapOf(
    "id" to id,
    "stableID" to stableID,
    "hostName" to hostName,
    "dnsName" to dnsName,
    "os" to os,
    "tailscaleIPs" to tailscaleIPs,
    "sshHostKeys" to sshHostKeys,
    "online" to online,
    "exitNode" to exitNode,
    "exitNodeOption" to exitNodeOption,
    "active" to active,
    "rxBytes" to rxBytes,
    "txBytes" to txBytes,
    "canReceiveFiles" to canReceiveFiles,
)

internal fun TailscaleUserGroupData.toWire(): Map<String, Any?> = mapOf(
    "id" to id,
    "loginName" to loginName,
    "displayName" to displayName,
    "peers" to peers.map { it.toWire() },
)

internal fun TailscaleEndpointData.toWire(): Map<String, Any?> = mapOf(
    "endpointTag" to endpointTag,
    "stateText" to stateText,
    "authURL" to authURL,
    "networkName" to networkName,
    "selfPeer" to selfPeer?.toWire(),
    "exitNode" to exitNode?.toWire(),
    "userGroups" to userGroups.map { it.toWire() },
    "canShareFiles" to canShareFiles,
    "waitingFileCount" to waitingFileCount,
)

internal fun OpenConnectAuthFormChoiceData.toWire(): Map<String, Any?> = mapOf(
    "value" to value,
    "label" to label,
)

internal fun OpenConnectAuthFormFieldData.toWire(): Map<String, Any?> = mapOf(
    "submissionKey" to submissionKey,
    "name" to name,
    "label" to label,
    "kind" to kind,
    "value" to value,
    "options" to options.map { it.toWire() },
)

internal fun OpenConnectAuthFormData.toWire(): Map<String, Any?> = mapOf(
    "fields" to fields.map { it.toWire() },
)

internal fun OpenConnectBrowserRequestData.toWire(): Map<String, Any?> = mapOf(
    "url" to url,
)

internal fun OpenConnectAuthChallengeData.toWire(): Map<String, Any?> = mapOf(
    "id" to id,
    "banner" to banner,
    "message" to message,
    "error" to error,
    "form" to form?.toWire(),
    "browser" to browser?.toWire(),
)

internal fun OpenConnectTunnelInfoData.toWire(): Map<String, Any?> = mapOf(
    "server" to server,
    "flavor" to flavor,
    "transport" to transport,
    "ipv4" to ipv4,
    "ipv6" to ipv6,
    "dns" to dns,
    "mtu" to mtu,
    "connectedSince" to connectedSince,
)

internal fun OpenConnectEndpointData.toWire(): Map<String, Any?> = mapOf(
    "endpointTag" to endpointTag,
    "state" to state,
    "stateText" to stateText,
    "authChallenge" to authChallenge?.toWire(),
    "error" to error,
    "tunnelInfo" to tunnelInfo?.toWire(),
)

internal fun OpenVPNChallengeData.toWire(): Map<String, Any?> = mapOf(
    "id" to id,
    "kind" to kind,
    "username" to username,
    "message" to message,
    "url" to url,
    "secretMessage" to secretMessage,
    "echo" to echo,
    "previousError" to previousError,
    "deadline" to deadline,
)

internal fun OpenVPNTunnelInfoData.toWire(): Map<String, Any?> = mapOf(
    "server" to server,
    "network" to network,
    "cipher" to cipher,
    "ipv4" to ipv4,
    "ipv6" to ipv6,
    "dns" to dns,
    "mtu" to mtu,
    "connectedSince" to connectedSince,
)

internal fun OpenVPNEndpointData.toWire(): Map<String, Any?> = mapOf(
    "endpointTag" to endpointTag,
    "state" to state,
    "stateText" to stateText,
    "challenge" to challenge?.toWire(),
    "error" to error,
    "tunnelInfo" to tunnelInfo?.toWire(),
)

internal fun UsbSharedInterfaceData.toWire(): Map<String, Any?> = mapOf(
    "interfaceClass" to interfaceClass,
    "interfaceSubClass" to interfaceSubClass,
    "interfaceProtocol" to interfaceProtocol,
)

internal fun UsbSharedDeviceData.toWire(): Map<String, Any?> = mapOf(
    "busId" to busId,
    "stableId" to stableId,
    "backend" to backend,
    "state" to state,
    "deviceId" to deviceId,
    "busNum" to busNum,
    "devNum" to devNum,
    "speed" to speed,
    "vendorId" to vendorId,
    "productId" to productId,
    "bcdDevice" to bcdDevice,
    "deviceClass" to deviceClass,
    "deviceSubClass" to deviceSubClass,
    "deviceProtocol" to deviceProtocol,
    "configurationValue" to configurationValue,
    "numConfigurations" to numConfigurations,
    "serial" to serial,
    "product" to product,
    "interfaces" to interfaces.map { it.toWire() },
)

internal fun UsbipServerData.toWire(): Map<String, Any?> = mapOf(
    "serverTag" to serverTag,
    "devices" to devices.map { it.toWire() },
)
