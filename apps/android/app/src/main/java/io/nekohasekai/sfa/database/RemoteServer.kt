package io.nekohasekai.sfa.database

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** A remote control server, kept in the client store ([RemoteServerManager]); the form saves it through client-core directly. */
@Serializable
@Parcelize
class RemoteServer(
    var id: Long = 0L,
    var userOrder: Long = 0L,
    var name: String = "",
    var url: String = "",
    var secret: String = "",
) : Parcelable {
    /** Its name, else its host and port (client-core `remote_servers::display_name`). */
    val displayName: String
        get() = CoreStore.call("servers.displayName") { put("name", name); put("url", url) }.jsonPrimitive.content

    companion object {
        /** The address passed to libbox, with a scheme (client-core `remote_servers::connect_url`). */
        fun connectURL(urlString: String): String =
            CoreStore.call("servers.connectUrl") { put("url", urlString) }.jsonPrimitive.content
    }
}
