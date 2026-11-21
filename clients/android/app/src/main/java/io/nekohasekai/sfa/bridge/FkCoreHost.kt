package io.nekohasekai.sfa.bridge

import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.bg.BoxService
import io.nekohasekai.sfa.database.Settings

/**
 * The reverse calls fk-core makes into the app (docs/kernel-isolation.md
 * section 8): the app's settings, sing-box configuration checks that only
 * the official libbox can answer (fk-core never links a kernel), and the
 * VPN TUN and service state for the kernels fk-core runs.
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
        // The TUN for Xray or mihomo (fk-core core_service.go); the descriptor
        // travels back with the reply.
        FkCoreClient.handle("vpn.openTun") { args ->
            val service = BoxService.current ?: error("the VPN service is not running")
            FkCoreClient.WithDescriptor(null, service.openTunForFkCore(args.getJSONObject(0)))
        }
        FkCoreClient.handle("service.fatal") { args ->
            BoxService.current?.fkCoreFatal(args.optString(0))
            null
        }
    }
}
