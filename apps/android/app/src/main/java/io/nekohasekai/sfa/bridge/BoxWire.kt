package io.nekohasekai.sfa.bridge

import io.nekohasekai.libbox.Connection as LibboxConnection
import io.nekohasekai.sfa.ktx.toList
import io.nekohasekai.sfa.backend.model.Group
import io.nekohasekai.sfa.backend.dashboard.DashboardUiState
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.utils.RemoteControlManager

internal fun DashboardUiState.toWire(): Map<String, Any?> = mapOf(
    "status" to serviceStatus.name,
    "selectedProfileId" to Settings.selectedProfile,
    "startedAt" to (RemoteControlManager.startedAt.value ?: serviceStartTime),
    "trafficAvailable" to trafficVisible,
    "modes" to if (clashModeVisible) clashModes else emptyList<String>(),
    "mode" to selectedClashMode,
    "systemProxyAvailable" to (systemProxyVisible && RemoteControlManager.remoteServer.value == null),
    "systemProxy" to systemProxyEnabled,
    "remote" to RemoteControlManager.remoteServer.value?.displayName,
    "remoteConnected" to RemoteControlManager.isConnected.value,
    "deprecated" to deprecatedNotes.map { mapOf("message" to it.message, "url" to it.migrationLink) },
)

internal fun Group.toWire(): Map<String, Any?> = mapOf(
    "tag" to tag,
    "type" to displayType,
    "selectable" to selectable,
    "selected" to selected,
    "expanded" to isExpand,
    "items" to items.map { mapOf("tag" to it.tag, "type" to it.displayType, "delay" to it.urlTestDelay, "testedAt" to it.urlTestTime) },
)

/**
 * A libbox connection as client-core `connections.rs` keeps it (the rows the
 * connections page lists, `outboundType` included for its DNS filter).
 */
internal fun LibboxConnection.toWire(): Map<String, Any?> = mapOf(
    "id" to id, "inbound" to inbound,
    "network" to network, "source" to source, "destination" to destination,
    "domain" to domain, "displayDestination" to displayDestination(),
    "protocol" to protocol, "user" to user, "createdAt" to createdAt,
    "closedAt" to closedAt.takeIf { it > 0 }, "upload" to uplink, "download" to downlink,
    "uploadTotal" to uplinkTotal, "downloadTotal" to downlinkTotal,
    "rule" to rule, "outbound" to outbound, "outboundType" to outboundType,
    "chain" to chain().toList(), "process" to processInfo?.let {
        mapOf("id" to it.processID, "path" to (it.processPath ?: ""), "user" to (it.userName ?: ""),
            "packages" to (it.packageNames()?.toList() ?: emptyList<String>()))
    },
)
