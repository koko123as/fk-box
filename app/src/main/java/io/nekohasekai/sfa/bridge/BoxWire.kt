package io.nekohasekai.sfa.bridge

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
