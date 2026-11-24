package io.nekohasekai.sfa.bg

import android.app.Service
import android.content.Intent

/**
 * The proxy-only service mode. The single-core kernel always runs a TUN
 * inbound (the brain's generated configuration), so this mode is never
 * selected (Settings.detectServiceMode); a start here fails with an alert.
 */
class ProxyService : Service() {
    private val service = BoxService(this)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = service.onStartCommand()

    override fun onBind(intent: Intent) = service.onBind()

    override fun onDestroy() = service.onDestroy()
}
