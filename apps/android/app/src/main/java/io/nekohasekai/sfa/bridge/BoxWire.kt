package io.nekohasekai.sfa.bridge

import io.nekohasekai.libbox.Connection as LibboxConnection
import io.nekohasekai.sfa.ktx.toList
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OutboundGroup

/** A libbox proxy group as client-core `groups.rs` reads it. */
internal fun OutboundGroup.toWire(): Map<String, Any?> = mapOf(
    "tag" to tag,
    "type" to Libbox.proxyDisplayType(type),
    "selectable" to selectable,
    "selected" to selected,
    "expanded" to isExpand,
    "items" to buildList {
        val iterator = items
        while (iterator.hasNext()) {
            val item = iterator.next()
            add(mapOf("tag" to item.tag, "type" to Libbox.proxyDisplayType(item.type), "delay" to item.urlTestDelay, "testedAt" to item.urlTestTime))
        }
    },
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
