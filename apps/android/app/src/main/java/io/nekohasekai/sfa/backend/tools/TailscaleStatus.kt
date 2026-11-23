package io.nekohasekai.sfa.backend.tools

import io.nekohasekai.libbox.TailscaleStatusUpdate

data class TailscalePeerData(
    val id: String,
    val stableID: String,
    val hostName: String,
    val dnsName: String,
    val os: String,
    val tailscaleIPs: List<String>,
    val sshHostKeys: List<String>,
    val online: Boolean,
    val exitNode: Boolean,
    val exitNodeOption: Boolean,
    val active: Boolean,
    val rxBytes: Long,
    val txBytes: Long,
    val canReceiveFiles: Boolean,
) {
    val displayName: String get() = dnsName.substringBefore(".").ifEmpty { hostName }
}

data class TailscaleUserGroupData(
    val id: Long,
    val loginName: String,
    val displayName: String,
    val peers: List<TailscalePeerData>,
)

data class TailscaleEndpointData(
    val endpointTag: String,
    val stateText: String,
    val authURL: String,
    val networkName: String,
    val selfPeer: TailscalePeerData?,
    val exitNode: TailscalePeerData?,
    val userGroups: List<TailscaleUserGroupData>,
    val canShareFiles: Boolean,
    val waitingFileCount: Int,
)

// Conversion of the libbox status updates to plain values; the subscriptions
// are in bridge/BoxToolStatus.kt and the lists in mobile-ffi tool_status.rs.

internal fun convertUpdate(status: TailscaleStatusUpdate): List<TailscaleEndpointData> {
    val endpoints = mutableListOf<TailscaleEndpointData>()
    val iterator = status.endpoints()
    while (iterator.hasNext()) {
        endpoints.add(convertEndpoint(iterator.next()))
    }
    return endpoints
}

internal fun convertEndpoint(
    endpoint: io.nekohasekai.libbox.TailscaleEndpointStatus,
): TailscaleEndpointData {
    val userGroups = mutableListOf<TailscaleUserGroupData>()
    val groupIterator = endpoint.userGroups()
    while (groupIterator.hasNext()) {
        userGroups.add(convertUserGroup(groupIterator.next()))
    }
    val self = endpoint.getSelf()
    val exitNode = endpoint.exitNode
    return TailscaleEndpointData(
        endpointTag = endpoint.endpointTag,
        stateText = endpoint.stateText,
        authURL = endpoint.authURL,
        networkName = endpoint.networkName,
        selfPeer = if (self != null) convertPeer(self) else null,
        exitNode = if (exitNode != null) convertPeer(exitNode) else null,
        userGroups = userGroups,
        canShareFiles = endpoint.canShareFiles,
        waitingFileCount = endpoint.waitingFileCount,
    )
}

internal fun convertUserGroup(
    group: io.nekohasekai.libbox.TailscaleUserGroup,
): TailscaleUserGroupData {
    val peers = mutableListOf<TailscalePeerData>()
    val peerIterator = group.peers()
    while (peerIterator.hasNext()) {
        peers.add(convertPeer(peerIterator.next()))
    }
    return TailscaleUserGroupData(
        id = group.userID,
        loginName = group.loginName,
        displayName = group.displayName,
        peers = peers,
    )
}

internal fun convertPeer(peer: io.nekohasekai.libbox.TailscalePeer): TailscalePeerData {
    val ips = mutableListOf<String>()
    val ipIterator = peer.tailscaleIPs()
    while (ipIterator.hasNext()) {
        ips.add(ipIterator.next())
    }
    val sshKeys = mutableListOf<String>()
    val keyIterator = peer.sshHostKeys()
    while (keyIterator.hasNext()) {
        sshKeys.add(keyIterator.next())
    }
    val dnsName = peer.getDNSName()
    return TailscalePeerData(
        id = if (dnsName.isNotEmpty()) dnsName else peer.hostName,
        stableID = peer.stableID,
        hostName = peer.hostName,
        dnsName = dnsName,
        os = peer.getOS(),
        tailscaleIPs = ips,
        sshHostKeys = sshKeys,
        online = peer.online,
        exitNode = peer.exitNode,
        exitNodeOption = peer.exitNodeOption,
        active = peer.active,
        rxBytes = peer.rxBytes,
        txBytes = peer.txBytes,
        canReceiveFiles = peer.canReceiveFiles,
    )
}
