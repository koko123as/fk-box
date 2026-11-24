package io.nekohasekai.sfa.bg

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.MutableLiveData
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.MainActivity
import io.nekohasekai.sfa.constant.Action
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Settings
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

class SystemNotifications(private val status: MutableLiveData<Status>, private val service: Service) : BroadcastReceiver() {
    companion object {
        private const val notificationId = 1
        private const val notificationChannel = "service"
        val flags = PendingIntent.FLAG_IMMUTABLE

        fun checkPermission(): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                return true
            }
            return Application.notification.areNotificationsEnabled()
        }
    }

    // The traffic line reads the kernel's counters once a second while the
    // screen is on (formerly a libbox status stream).
    @Volatile private var poller: Job? = null
    private var receiverRegistered = false

    @OptIn(DelicateCoroutinesApi::class)
    private fun connect() {
        if (poller?.isActive == true) return
        poller = GlobalScope.launch(Dispatchers.IO) {
            while (isActive) {
                FkHost.stats()?.let { updateStatus(it.uplinkRate, it.downlinkRate) }
                delay(1000)
            }
        }
    }

    private fun disconnect() {
        poller?.cancel()
        poller = null
    }

    private val notificationBuilder by lazy {
        NotificationCompat.Builder(service, notificationChannel).setShowWhen(false).setOngoing(true)
            .setContentTitle("sing-box").setOnlyAlertOnce(true)
            .setSmallIcon(R.drawable.ic_menu)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(
                PendingIntent.getActivity(
                    service,
                    0,
                    Intent(
                        service,
                        MainActivity::class.java,
                    ).setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                    flags,
                ),
            )
            .setPriority(NotificationCompat.PRIORITY_LOW).apply {
                addAction(
                    NotificationCompat.Action.Builder(
                        0,
                        service.getText(R.string.stop),
                        PendingIntent.getBroadcast(
                            service,
                            0,
                            Intent(Action.SERVICE_CLOSE).setPackage(service.packageName),
                            flags,
                        ),
                    ).build(),
                )
            }
    }

    // The last traffic text posted; notify() is skipped while it repeats
    // (an idle "0 B/s" line would otherwise be re-posted every second).
    @Volatile private var lastContent: String? = null

    /** The notification for [profileName] while [phase] (`starting`, `started`); mobile-ffi service_state.rs words it. */
    fun show(profileName: String, phase: String) {
        show(CoreBridge.call("serviceState.notification") { put("profile", profileName); put("phase", phase) })
    }

    /** A notification as mobile-ffi service_state.rs answers it: `title` and the `text` string resource. */
    fun show(content: JsonObject) {
        val title = content.text("title")
        val text = when (content.textOrNull("text")) {
            "status_started" -> R.string.status_started
            "kill_switch_blocked" -> R.string.kill_switch_blocked
            else -> R.string.status_starting
        }
        lastContent = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Application.notification.createNotificationChannel(
                NotificationChannel(
                    notificationChannel,
                    "Service Notifications",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        service.startForeground(
            notificationId,
            notificationBuilder
                .setContentTitle(title)
                .setContentText(service.getString(text)).build(),
        )
    }

    /**
     * Retitles the running notification for [profileName] after a reload switched
     * profiles, keeping the traffic line it shows (or the started text before one).
     */
    fun retitle(profileName: String) {
        val title = CoreBridge.call("serviceState.notification") { put("profile", profileName); put("phase", "started") }.text("title")
        val text = lastContent ?: service.getString(R.string.status_started)
        Application.notificationManager.notify(
            notificationId,
            notificationBuilder.setContentTitle(title).setContentText(text).build(),
        )
    }

    suspend fun start() {
        val shown = CoreBridge.call("serviceState.trafficShown") {
            put("enabled", Settings.dynamicNotification)
            put("permitted", checkPermission())
        }.bool("shown")
        if (shown) {
            connect()
            withContext(Dispatchers.Main) {
                registerReceiver()
            }
        }
    }

    private fun registerReceiver() {
        service.registerReceiver(
            this,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
        )
        receiverRegistered = true
    }

    private fun updateStatus(uplink: Long, downlink: Long) {
        val line = CoreBridge.call("serviceState.trafficText") {
            put("uplink", FkHost.formatBytes(uplink))
            put("downlink", FkHost.formatBytes(downlink))
            put("last", lastContent)
        }
        if (!line.bool("post")) return
        val content = line.text("text")
        lastContent = content
        Application.notificationManager.notify(
            notificationId,
            notificationBuilder.setContentText(content).build(),
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SCREEN_ON -> {
                connect()
            }

            Intent.ACTION_SCREEN_OFF -> {
                disconnect()
            }
        }
    }

    fun close() {
        lastContent = null
        disconnect()
        ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (receiverRegistered) {
            service.unregisterReceiver(this)
            receiverRegistered = false
        }
    }
}
