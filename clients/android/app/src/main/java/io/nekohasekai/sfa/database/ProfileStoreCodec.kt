package io.nekohasekai.sfa.database

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.Date

/**
 * The rows of the Go profile store (core/core_profile_store.go) as JSON:
 * [Profile] with every [TypedProfile] field spelled out (no Parcel blob), and
 * [RemoteServer]. A missing or mistyped field is an error, never a default.
 */
internal object ProfileStoreCodec {
    fun encode(profile: Profile): JsonObject = buildJsonObject {
        put("id", profile.id)
        put("userOrder", profile.userOrder)
        put("name", profile.name)
        put("icon", profile.icon)
        val typed = profile.typed
        putJsonObject("typed") {
            put("path", typed.path)
            put("type", typed.type.ordinal)
            put("remoteURL", typed.remoteURL)
            put("lastUpdated", typed.lastUpdated.time)
            put("autoUpdate", typed.autoUpdate)
            put("autoUpdateInterval", typed.autoUpdateInterval)
            put("core", typed.core)
            put("upload", typed.upload)
            put("download", typed.download)
            put("total", typed.total)
            put("expire", typed.expire)
            put("webPageUrl", typed.webPageUrl)
            put("intervalUserSet", typed.intervalUserSet)
        }
    }

    fun encode(server: RemoteServer): JsonObject = buildJsonObject {
        put("id", server.id)
        put("userOrder", server.userOrder)
        put("name", server.name)
        put("url", server.url)
        put("secret", server.secret)
    }

    private fun JsonObject.field(name: String): JsonElement = this[name] ?: throw IllegalStateException("Profile store row has no $name")

    private fun JsonObject.string(name: String): String {
        val value = field(name).jsonPrimitive
        check(value.isString) { "Profile store field $name is not a string" }
        return value.content
    }

    fun decodeProfile(element: JsonElement): Profile {
        val row = element.jsonObject
        val typedRow = row.field("typed").jsonObject
        val typed = TypedProfile().apply {
            path = typedRow.string("path")
            type = TypedProfile.Type.valueOf(typedRow.field("type").jsonPrimitive.int)
            remoteURL = typedRow.string("remoteURL")
            lastUpdated = Date(typedRow.field("lastUpdated").jsonPrimitive.long)
            autoUpdate = typedRow.field("autoUpdate").jsonPrimitive.boolean
            autoUpdateInterval = typedRow.field("autoUpdateInterval").jsonPrimitive.int
            core = typedRow.string("core")
            upload = typedRow.field("upload").jsonPrimitive.long
            download = typedRow.field("download").jsonPrimitive.long
            total = typedRow.field("total").jsonPrimitive.long
            expire = typedRow.field("expire").jsonPrimitive.long
            webPageUrl = typedRow.string("webPageUrl")
            intervalUserSet = typedRow.field("intervalUserSet").jsonPrimitive.boolean
        }
        val icon = row.field("icon")
        return Profile(
            id = row.field("id").jsonPrimitive.long,
            userOrder = row.field("userOrder").jsonPrimitive.long,
            name = row.string("name"),
            icon = if (icon is JsonNull) null else row.string("icon"),
            typed = typed,
        )
    }

    fun decodeServer(element: JsonElement): RemoteServer {
        val row = element.jsonObject
        return RemoteServer(
            id = row.field("id").jsonPrimitive.long,
            userOrder = row.field("userOrder").jsonPrimitive.long,
            name = row.string("name"),
            url = row.string("url"),
            secret = row.string("secret"),
        )
    }

    fun parse(text: String): JsonElement = Json.parseToJsonElement(text)

    fun decodeProfiles(text: String): List<Profile> = parse(text).jsonArray.map(::decodeProfile)

    fun decodeServers(text: String): List<RemoteServer> = parse(text).jsonArray.map(::decodeServer)

    fun encodeProfiles(profiles: List<Profile>): String = JsonArray(profiles.map(::encode)).toString()

    fun encodeServers(servers: List<RemoteServer>): String = JsonArray(servers.map(::encode)).toString()

    /** The Room data handed to UIProfileStoreStageImport. */
    fun importDataset(profileSeq: Long, serverSeq: Long, profiles: List<Profile>, servers: List<RemoteServer>): String = buildJsonObject {
        put("profileSeq", profileSeq)
        put("serverSeq", serverSeq)
        put("profiles", JsonArray(profiles.map(::encode)))
        put("servers", JsonArray(servers.map(::encode)))
    }.toString()

    /**
     * Why the store's read-back of an import is not exactly [profiles] and
     * [servers] (any order), or null when every row and field matches.
     */
    fun importMismatch(readBack: String, profiles: List<Profile>, servers: List<RemoteServer>): String? {
        val back = parse(readBack).jsonObject
        val storedProfiles = back.field("profiles").jsonArray.map(::decodeProfile)
        val storedServers = back.field("servers").jsonArray.map(::decodeServer)
        if (storedProfiles.size != profiles.size) return "profile count ${storedProfiles.size} != ${profiles.size}"
        if (storedServers.size != servers.size) return "server count ${storedServers.size} != ${servers.size}"
        val profileRows = storedProfiles.associate { it.id to encode(it) }
        if (profileRows.size != storedProfiles.size) return "duplicate profile ids"
        for (profile in profiles) {
            if (profileRows[profile.id] != encode(profile)) return "profile ${profile.id} differs"
        }
        val serverRows = storedServers.associate { it.id to encode(it) }
        if (serverRows.size != storedServers.size) return "duplicate server ids"
        for (server in servers) {
            if (serverRows[server.id] != encode(server)) return "server ${server.id} differs"
        }
        // Room lists by userOrder; the store breaks ties by id, which Room leaves unspecified.
        if (storedProfiles.map { it.userOrder } != profiles.map { it.userOrder }.sorted()) return "profile order differs"
        return null
    }

    fun encodeIds(ids: List<Long>): String = JsonArray(ids.map { JsonPrimitive(it) }).toString()
}
