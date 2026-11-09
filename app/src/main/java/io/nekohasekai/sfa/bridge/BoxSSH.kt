package io.nekohasekai.sfa.bridge

import io.nekohasekai.sfa.backend.tools.TailscaleStatusViewModel
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.terminal.DEFAULT_SSH_TERMINAL_TYPE
import io.nekohasekai.sfa.terminal.TailscaleSSHPresentedSession
import io.nekohasekai.sfa.terminal.TailscaleSSHSessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.Closeable

/** Compose owns presentation; the process-wide Ghostty store owns SSH. */
internal class BoxSSH(scope: CoroutineScope, private val tailscale: TailscaleStatusViewModel,
    private val emit: (String, Any?) -> Unit) : Closeable {
    private val store = TailscaleSSHSessionStore
    private val watch = scope.launch {
        store.state.collectLatest { state ->
            emit("terminal", snapshot())
            coroutineScope {
                state.sessions.forEach { session ->
                    launch { session.phase.collect { emit("terminal", snapshot()) } }
                    launch { session.banner.collect { emit("terminal", snapshot()) } }
                    launch { session.terminalSession.title.collect { emit("terminal", snapshot()) } }
                    launch { session.terminalSession.workingDirectory.collect { emit("terminal", snapshot()) } }
                    launch { session.terminalSession.progress.collect { emit("terminal", snapshot()) } }
                }
            }
        }
    }

    fun snapshot(): Map<String, Any?> = mapOf(
        "activeId" to store.state.value.activeSessionId,
        "sessions" to store.state.value.sessions.map { session ->
            mapOf("id" to session.id, "name" to (session.terminalSession.title.value ?: session.presentedSession.peerHostName),
                "host" to session.presentedSession.peerHostName, "username" to session.presentedSession.username,
                "phase" to session.phase.value.name, "banner" to session.banner.value,
                "directory" to session.terminalSession.workingDirectory.value,
                "progress" to session.terminalSession.progress.value?.let { mapOf("state" to it.state.name, "percent" to it.percent) })
        },
    )

    private fun required(value: String?, key: String) = value ?: error("Missing terminal argument: $key")

    private fun sshPeer(endpoint: String?, peerID: String?) =
        (tailscale.peer(required(endpoint, "endpoint"), required(peerID, "peer")) ?: error("Peer no longer exists")).also { peer ->
            require(peer.sshHostKeys.isNotEmpty()) { "Peer has no authenticated SSH host keys" }
        }

    fun options(endpoint: String?, peerID: String?): Map<String, Any?> {
        val peer = sshPeer(endpoint, peerID)
        return mapOf(
            "username" to (Settings.tailscaleSSHRememberedUsernames[peer.stableID] ?: "root"),
            "terminalType" to (Settings.tailscaleSSHRememberedTerminalTypes[peer.stableID] ?: DEFAULT_SSH_TERMINAL_TYPE),
            "remember" to Settings.tailscaleSSHQuickConnectPeers.contains(peer.stableID),
        )
    }

    fun start(endpoint: String?, peerID: String?, usernameArgument: String?, terminalTypeArgument: String?, remember: Boolean): Map<String, Any?> {
        val endpointTag = required(endpoint, "endpoint")
        val peer = sshPeer(endpoint, peerID)
        val username = required(usernameArgument, "username").trim()
        val terminalType = terminalTypeArgument.orEmpty().trim().ifEmpty { DEFAULT_SSH_TERMINAL_TYPE }
        require(username.isNotEmpty() && username.length <= 256 && username.none { it.isISOControl() }) { "Invalid SSH username" }
        require(terminalType.length <= 128 && terminalType.matches(Regex("[A-Za-z0-9._+-]+"))) { "Invalid terminal type" }
        val address = peer.tailscaleIPs.firstOrNull() ?: error("Peer has no address")
        Settings.tailscaleSSHRememberedUsernames = Settings.tailscaleSSHRememberedUsernames.toMutableMap().apply { put(peer.stableID, username) }
        Settings.tailscaleSSHRememberedTerminalTypes = Settings.tailscaleSSHRememberedTerminalTypes.toMutableMap().apply { put(peer.stableID, terminalType) }
        Settings.tailscaleSSHQuickConnectPeers = Settings.tailscaleSSHQuickConnectPeers.toMutableSet().apply {
            if (remember) add(peer.stableID) else remove(peer.stableID)
        }
        store.addSession(TailscaleSSHPresentedSession(endpointTag, peer.hostName, address, username, terminalType, peer.sshHostKeys))
        return snapshot()
    }

    fun select(idArgument: String?): Map<String, Any?> {
        val id = required(idArgument, "id")
        require(store.state.value.sessions.any { it.id == id }) { "Session no longer exists" }
        store.switchSession(id)
        return snapshot()
    }

    fun duplicate(): Map<String, Any?> {
        store.duplicateCurrentSession()
        return snapshot()
    }

    fun closeSession(id: String?): Map<String, Any?> {
        store.removeSession(required(id, "id"))
        return snapshot()
    }

    override fun close() { watch.cancel() }
}
