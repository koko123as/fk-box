package io.nekohasekai.sfa.backend.tools

import io.nekohasekai.libbox.OpenVPNStatusUpdate

data class OpenVPNChallengeData(
    val id: String,
    val kind: String,
    val username: String,
    val message: String,
    val url: String,
    val secretMessage: String,
    val echo: Boolean,
    val previousError: String,
    val deadline: Long,
)

data class OpenVPNTunnelInfoData(
    val server: String,
    val network: String,
    val cipher: String,
    val ipv4: List<String>,
    val ipv6: List<String>,
    val dns: List<String>,
    val mtu: Int,
    val connectedSince: Long,
)

data class OpenVPNEndpointData(
    val endpointTag: String,
    val state: String,
    val stateText: String,
    val challenge: OpenVPNChallengeData?,
    val error: String,
    val tunnelInfo: OpenVPNTunnelInfoData?,
)

// Conversion of the libbox status updates to plain values; the subscriptions
// are in bridge/BoxToolStatus.kt and the lists in mobile-ffi tool_status.rs.

internal fun convertUpdate(status: OpenVPNStatusUpdate): List<OpenVPNEndpointData> {
    val endpoints = mutableListOf<OpenVPNEndpointData>()
    val iterator = status.endpoints()
    while (iterator.hasNext()) {
        endpoints.add(convertEndpoint(iterator.next()))
    }
    return endpoints
}

internal fun convertEndpoint(
    endpoint: io.nekohasekai.libbox.OpenVPNEndpointStatus,
): OpenVPNEndpointData {
    val challenge = endpoint.challenge
    val tunnelInfo = endpoint.tunnelInfo
    return OpenVPNEndpointData(
        endpointTag = endpoint.endpointTag,
        state = endpoint.state,
        stateText = endpoint.stateText,
        challenge = if (challenge != null) convertChallenge(challenge) else null,
        error = endpoint.error,
        tunnelInfo = if (tunnelInfo != null) convertTunnelInfo(tunnelInfo) else null,
    )
}

internal fun convertChallenge(
    challenge: io.nekohasekai.libbox.OpenVPNChallenge,
): OpenVPNChallengeData = OpenVPNChallengeData(
    id = challenge.id,
    kind = challenge.kind,
    username = challenge.username,
    message = challenge.message,
    url = challenge.url,
    secretMessage = challenge.secretMessage,
    echo = challenge.echo,
    previousError = challenge.previousError,
    deadline = challenge.deadline,
)

internal fun convertTunnelInfo(
    info: io.nekohasekai.libbox.OpenVPNTunnelInfo,
): OpenVPNTunnelInfoData = OpenVPNTunnelInfoData(
    server = info.server,
    network = info.network,
    cipher = info.cipher,
    ipv4 = info.iPv4().toList(),
    ipv6 = info.iPv6().toList(),
    dns = info.dns().toList(),
    mtu = info.mtu,
    connectedSince = info.connectedSince,
)

private fun io.nekohasekai.libbox.StringIterator.toList(): List<String> {
    val values = mutableListOf<String>()
    while (hasNext()) {
        values.add(next())
    }
    return values
}
