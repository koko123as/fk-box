package io.nekohasekai.sfa.database

@Suppress("RedundantSuspendModifier")
object RemoteServerManager {
    suspend fun nextOrder(): Long = ProfileManager.nextServerOrder()

    suspend fun get(id: Long): RemoteServer? = ProfileManager.getServer(id)

    suspend fun create(server: RemoteServer): RemoteServer {
        server.userOrder = nextOrder()
        server.id = ProfileManager.insertServer(server)
        return server
    }

    suspend fun update(server: RemoteServer): Int {
        return ProfileManager.updateServers(listOf(server))
    }

    suspend fun update(servers: List<RemoteServer>): Int {
        return ProfileManager.updateServers(servers)
    }

    suspend fun delete(server: RemoteServer): Int {
        return ProfileManager.deleteServer(server)
    }

    suspend fun list(): List<RemoteServer> = ProfileManager.listServers()
}
