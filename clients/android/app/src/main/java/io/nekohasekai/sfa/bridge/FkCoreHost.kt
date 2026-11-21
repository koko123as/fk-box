package io.nekohasekai.sfa.bridge

import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.database.Settings

/**
 * The reverse calls fk-core makes into the app (docs/kernel-isolation.md
 * section 8): the app's settings, and sing-box configuration checks that only
 * the official libbox can answer (fk-core never links a kernel).
 */
object FkCoreHost {
    fun install() {
        FkCoreClient.handle("settings.getBool") { args -> Settings.dataStore.getBoolean(args.getString(0), args.getBoolean(1)) }
        FkCoreClient.handle("settings.getLong") { args -> Settings.dataStore.getLong(args.getString(0), args.getLong(1)) }
        FkCoreClient.handle("settings.getString") { args ->
            val default = args.getString(1)
            Settings.dataStore.getString(args.getString(0), default) ?: default
        }
        FkCoreClient.handle("singbox.checkConfig") { args ->
            Libbox.checkConfig(args.getString(0))
            null
        }
        FkCoreClient.handle("singbox.formatConfig") { args -> Libbox.formatConfig(args.getString(0)).value }
        FkCoreClient.handle("singbox.hasTunInbound") { args -> Libbox.hasTunInbound(args.getString(0)) }
    }
}
