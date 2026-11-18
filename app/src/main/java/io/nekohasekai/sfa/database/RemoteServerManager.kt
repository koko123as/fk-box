package io.nekohasekai.sfa.database

@Suppress("RedundantSuspendModifier")
object RemoteServerManager {
    suspend fun nextOrder(): Long = ProfileManager.remoteServerDao().nextOrder() ?: 0

    suspend fun get(id: Long): RemoteServer? = ProfileManager.remoteServerDao().get(id)

    suspend fun create(server: RemoteServer): RemoteServer {
        server.userOrder = nextOrder()
        server.id = ProfileManager.remoteServerDao().insert(server)
        return server
    }

    suspend fun update(server: RemoteServer): Int {
        return ProfileManager.remoteServerDao().update(server)
    }

    suspend fun update(servers: List<RemoteServer>): Int {
        return ProfileManager.remoteServerDao().update(servers)
    }

    suspend fun delete(server: RemoteServer): Int {
        return ProfileManager.remoteServerDao().delete(server)
    }

    suspend fun list(): List<RemoteServer> = ProfileManager.remoteServerDao().list()
}
