package io.nekohasekai.sfa.bg

import android.content.Intent
import android.net.VpnService
import android.os.IBinder
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.TunOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class VPNService :
    VpnService(),
    PlatformInterfaceWrapper {
    private val service = BoxService(this, this)

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

    override fun autoDetectInterfaceControl(fd: Int) {
        protect(fd)
    }

    var systemProxyAvailable = false
    var systemProxyEnabled = false

    override fun openTun(options: TunOptions): Int {
        if (prepare(this) != null) error("android: missing vpn permission")

        val plan = VpnRouting.plan(options)
        val builder = VpnRouting.build(Builder(), plan)
        systemProxyAvailable = plan.bool("systemProxyAvailable")
        systemProxyEnabled = plan.bool("systemProxyEnabled")

        val pfd =
            builder.establish() ?: error("android: the application is not prepared or is revoked")
        // Switching cores opens a new TUN while the service runs: close the
        // previous descriptor now instead of leaving it to the finalizer.
        val previous = service.fileDescriptor
        service.fileDescriptor = pfd
        if (previous != null && previous !== pfd) {
            runCatching { previous.close() }
        }
        return pfd.fd
    }

    override fun sendNotification(notification: Notification) = service.sendNotification(notification)

    override fun cancelNotification(identifier: String, typeID: Int) = service.cancelNotification(identifier, typeID)
}
