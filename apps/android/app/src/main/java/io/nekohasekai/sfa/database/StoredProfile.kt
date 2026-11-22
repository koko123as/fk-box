package io.nekohasekai.sfa.database

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Date

/** A profile as the client store exchanges it (crates/client-core storage::Profile). */
@Serializable
internal data class StoredProfile(
    val id: Long = 0L,
    val userOrder: Long = 0L,
    val name: String = "",
    val icon: String? = null,
    val path: String = "",
    val type: Int = 0,
    @SerialName("remoteURL") val remoteUrl: String = "",
    val lastUpdated: Long = 0L,
    val autoUpdate: Boolean = false,
    val autoUpdateInterval: Int = TypedProfile.DEFAULT_INTERVAL,
    val core: String = TypedProfile.CORE_AUTO,
    val upload: Long = 0L,
    val download: Long = 0L,
    val total: Long = 0L,
    val expire: Long = 0L,
    val webPageUrl: String = "",
    val intervalUserSet: Boolean = false,
    val resolvedCore: String = "",
    val resolvedStamp: String = "",
) {
    fun toProfile(): Profile = Profile(id, userOrder, name, icon, TypedProfile().also {
        it.path = path
        it.type = TypedProfile.Type.valueOf(type)
        it.remoteURL = remoteUrl
        it.lastUpdated = Date(lastUpdated)
        it.autoUpdate = autoUpdate
        it.autoUpdateInterval = autoUpdateInterval
        it.core = core.takeIf { value -> value in TypedProfile.CORES } ?: TypedProfile.CORE_AUTO
        it.upload = upload
        it.download = download
        it.total = total
        it.expire = expire
        it.webPageUrl = webPageUrl
        it.intervalUserSet = intervalUserSet
    }).also {
        it.storedOrder = userOrder
        it.resolvedCore = resolvedCore
        it.resolvedStamp = resolvedStamp
    }

    companion object {
        fun of(profile: Profile): StoredProfile = with(profile.typed) {
            StoredProfile(
                id = profile.id, userOrder = profile.userOrder, name = profile.name, icon = profile.icon,
                path = path, type = type.ordinal, remoteUrl = remoteURL, lastUpdated = lastUpdated.time,
                autoUpdate = autoUpdate, autoUpdateInterval = autoUpdateInterval, core = core,
                upload = upload, download = download, total = total, expire = expire,
                webPageUrl = webPageUrl, intervalUserSet = intervalUserSet,
                resolvedCore = profile.resolvedCore, resolvedStamp = profile.resolvedStamp,
            )
        }
    }
}
