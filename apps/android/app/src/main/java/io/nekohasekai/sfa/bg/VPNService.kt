package io.nekohasekai.sfa.bg

import android.content.Intent
import android.net.VpnService
import android.os.IBinder
import io.fkbox.mobile.TunOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class VPNService : VpnService() {
    private val service = BoxService(this)

    /** The kernel's view of this service (protect, TUN, owner, networks). */
    internal val platform = AndroidPlatform(this)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = service.onStartCommand()

    override fun onBind(intent: Intent): IBinder {
        val binder = super.onBind(intent)
        if (binder != null) {
            return binder
        }
        return service.onBind()
    }

    override fun onDestroy() {
        service.onDestroy()
    }

    override fun onRevoke() {
        runBlocking {
            withContext(Dispatchers.Main) {
                service.onRevoke()
            }
        }
    }

    var systemProxyAvailable = false
    var systemProxyEnabled = false

    /**
     * The kernel's TUN (fkhost asks through AndroidPlatform.openTun). The
     * previous descriptor (the kill switch's TUN, or the interface of the
     * configuration a reload replaces) is closed before the new interface is
     * established, so the kernel never holds two attachments.
     */
    fun openTun(options: TunOptions): Int {
        if (prepare(this) != null) error("android: missing vpn permission")
        val plan = VpnRouting.plan(options)
        val builder = VpnRouting.build(Builder(), plan)
        systemProxyAvailable = plan.bool("systemProxyAvailable")
        systemProxyEnabled = plan.bool("systemProxyEnabled")
        closeTun()
        val pfd = builder.establish() ?: error("android: the application is not prepared or is revoked")
        service.fileDescriptor = pfd
        return pfd.fd
    }

    /** Closes the current TUN descriptor, if any. */
    fun closeTun() {
        val previous = service.fileDescriptor ?: return
        service.fileDescriptor = null
        runCatching { previous.close() }
    }

    /**
     * The kill switch's TUN: every route, and nothing reads it, so traffic is
     * dropped until the kernel opens its own TUN (which replaces this one) or
     * the service stops.
     */
    fun openBlockingTun() {
        if (prepare(this) != null) error("android: missing vpn permission")
        val builder = Builder()
            .setSession("fk-box kill switch")
            .setMtu(1500)
            .addAddress("172.19.0.1", 30)
            .addRoute("0.0.0.0", 0)
            .addAddress("fdfe:dcba:9876::1", 126)
            .addRoute("::", 0)
            .addDnsServer("172.19.0.2")
        closeTun()
        service.fileDescriptor = builder.establish() ?: error("android: the application is not prepared or is revoked")
    }
}
