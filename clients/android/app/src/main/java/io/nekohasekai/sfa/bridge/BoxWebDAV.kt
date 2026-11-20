package io.nekohasekai.sfa.bridge

import android.content.SharedPreferences
import io.nekohasekai.sfa.fkcore.FkLib
import io.nekohasekai.sfa.utils.SecretBox
import org.json.JSONObject
import java.net.URI

internal class BoxWebDAV(activity: BoxActivity) {
    private val preferences: SharedPreferences = activity.getSharedPreferences("bbox_webdav", 0)

    /**
     * The account with its password in plain text, for requests and backups.
     * The stored copy keeps the password encrypted with a Keystore key
     * (SecretBox); an account saved before that is encrypted on first read.
     */
    fun snapshot(): Map<String, Any?>? {
        val stored = preferences.getString("value", null)?.let { jsonObject(JSONObject(it)) } ?: return null
        val password = stored["password"] as? String ?: return stored
        if (!SecretBox.isEncrypted(password)) {
            runCatching { save(stored) }.onFailure { android.util.Log.w("BoxWebDAV", "encrypt stored password", it) }
            return stored
        }
        return stored + ("password" to SecretBox.decrypt(password))
    }

    fun save(value: Map<String, Any?>?) {
        if (value != null) validate(value)
        val stored = value?.let { it + ("password" to SecretBox.encrypt(it["password"] as String)) }
        check(preferences.edit().putString("value", stored?.let { JSONObject(it).toString() }).commit()) { "Unable to save WebDAV account" }
    }

    // The requests and their status rules are libbox's (core_webdav.go); the
    // account and the addresses, resolved with java.net.URI, stay here.
    fun check(): Boolean {
        val props = snapshot() ?: error("Configure a WebDAV account first")
        FkLib.uiWebDAVCheck(url(props, ""), props["user"] as String, props["password"] as String)
        return true
    }

    fun upload(content: ByteArray) {
        val props = snapshot() ?: error("Configure a WebDAV account first")
        require(content.size <= BoxBackups.MAX_BYTES) { "Backup exceeds 64 MiB" }
        val directory = url(props, "B-box/")
        FkLib.uiWebDAVUpload(directory, url(props, "B-box/" + props.getValue("fileName")),
            props["user"] as String, props["password"] as String, content)
    }

    fun download(): ByteArray {
        val props = snapshot() ?: error("Configure a WebDAV account first")
        return FkLib.uiWebDAVDownload(url(props, "B-box/" + props.getValue("fileName")),
            props["user"] as String, props["password"] as String) ?: ByteArray(0)
    }

    private fun url(props: Map<String, Any?>, path: String): String {
        validate(props)
        val root = URI((props["uri"] as String).trimEnd('/') + "/")
        return root.resolve(URI(null, null, path, null, null)).toASCIIString()
    }

    companion object {
        fun validate(value: Map<String, Any?>) {
            require(value.keys == setOf("uri", "user", "password", "fileName")) { "Invalid WebDAV account" }
            val address = value["uri"] as? String ?: error("WebDAV address is required")
            val uri = URI(address)
            require(address.length <= 4096 && uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() &&
                (uri.port == -1 || uri.port in 1..65535) && uri.userInfo == null && uri.rawQuery == null && uri.fragment == null) {
                "Enter a WebDAV HTTP or HTTPS directory address"
            }
            require((value["user"] as? String)?.length?.let { it <= 4096 } == true &&
                (value["password"] as? String)?.length?.let { it <= 4096 } == true) { "Invalid WebDAV credentials" }
            val file = value["fileName"] as? String ?: error("A backup file name is required")
            require(file.length in 1..128 && file !in setOf(".", "..") && file.none { it == '/' || it == '\\' || it.code < 32 }) { "Invalid backup file name" }
        }
    }
}
