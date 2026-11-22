package io.nekohasekai.sfa.database

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** The remote control servers, kept in the client store ([CoreStore]). */
@Suppress("RedundantSuspendModifier")
object RemoteServerManager {
    suspend fun nextOrder(): Long = CoreStore.call("servers.nextOrder").jsonPrimitive.long

    suspend fun get(id: Long): RemoteServer? {
        val value = CoreStore.call("servers.get") { put("id", id) }
        return if (value is JsonNull) null else CoreStore.decode(RemoteServer.serializer(), value)
    }

    suspend fun create(server: RemoteServer): RemoteServer {
        server.userOrder = nextOrder()
        server.id = CoreStore.call("servers.insert") { put("server", CoreStore.encode(RemoteServer.serializer(), server)) }.jsonPrimitive.long
        return server
    }

    suspend fun update(server: RemoteServer): Int =
        CoreStore.call("servers.update") { put("server", CoreStore.encode(RemoteServer.serializer(), server)) }.jsonPrimitive.long.toInt()

    suspend fun update(servers: List<RemoteServer>): Int = servers.sumOf { update(it) }

    suspend fun delete(server: RemoteServer): Int = CoreStore.call("servers.delete") { put("id", server.id) }.jsonPrimitive.long.toInt()

    suspend fun list(): List<RemoteServer> = CoreStore.decode(ListSerializer(RemoteServer.serializer()), CoreStore.call("servers.list"))
}
