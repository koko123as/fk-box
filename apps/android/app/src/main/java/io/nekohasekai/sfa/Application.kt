package io.nekohasekai.sfa

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.content.getSystemService
import androidx.work.WorkManager
import io.nekohasekai.sfa.bg.AppChangeReceiver
import io.nekohasekai.sfa.bg.CrashReportManager
import io.nekohasekai.sfa.bg.FkHost
import io.nekohasekai.sfa.bg.OOMReportManager
import io.nekohasekai.sfa.bg.PowerReportManager
import io.nekohasekai.sfa.bg.UpdateGeoWork
import io.nekohasekai.sfa.bg.UpdateProfileWork
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.utils.AppLifecycleObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import io.nekohasekai.sfa.Application as BoxApplication

class Application : Application() {
    private val setupAccess = Mutex()
    private val workingDirAccess = Any()
    @Volatile private var workingDir: File? = null
    @Volatile private var setupCompleted = false

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        application = this
    }

    override fun onCreate() {
        super.onCreate()
        AppLifecycleObserver.register(this)
        // JVM crash capture is armed before anything else; its files are
        // resolved off the main thread below.
        CrashReportManager.installHandler()

        // The working directory setup (external storage, report archives)
        // runs on IO instead of the main thread. The kernel (libfkhost.so, the
        // Go runtime) is not loaded here: the service loads it when it starts
        // (FkHost.setup), so the UI alone never carries it.
        @Suppress("OPT_IN_USAGE")
        GlobalScope.launch(Dispatchers.IO) {
            filesDir.mkdirs()
            cacheDir.mkdirs()
            installWorkingDir()
            Settings.dataStore.initialize()
            WorkManager.getInstance(this@Application).cancelUniqueWork("AutoUpdate")
            setupCompleted = true
            UpdateProfileWork.reconfigureUpdater()
            UpdateGeoWork.reconfigure()
        }

        registerReceiver(
            AppChangeReceiver(),
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            },
        )
    }

    /**
     * UI, VPN service and the Go cores share this process. When the UI goes
     * to the background or the system runs low, hand Go's idle heap back to
     * the kernel (what Dashboard → Memory → Release memory does). HWUI frees
     * the window's render targets itself when the UI is hidden.
     */
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (!FkHost.loaded || level < TRIM_MEMORY_RUNNING_LOW) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastTrim < 10_000L) return
        lastTrim = now
        @Suppress("OPT_IN_USAGE")
        GlobalScope.launch(Dispatchers.IO) { runCatching { FkHost.freeMemory() } }
    }

    @Volatile private var lastTrim = 0L

    /** Idempotent and thread-safe; callers that read reports call it first. */
    fun installWorkingDir(): File? {
        workingDir?.let { return it }
        synchronized(workingDirAccess) {
            workingDir?.let { return it }
            val dir = getExternalFilesDir(null) ?: return null
            dir.mkdirs()
            CrashReportManager.install(dir, filesDir)
            OOMReportManager.install(dir)
            PowerReportManager.install(dir)
            workingDir = dir
            return dir
        }
    }

    /**
     * Prepares the kernel for the service: the working directory (reports)
     * and libfkhost.so (FkHost.setup). Called by the service when it starts.
     */
    suspend fun setupHost() = setupAccess.withLock {
        installWorkingDir() ?: error("android: external storage unavailable")
        FkHost.setup()
    }

    companion object {
        lateinit var application: BoxApplication
        val notification by lazy { application.getSystemService<NotificationManager>()!! }
        val connectivity by lazy { application.getSystemService<ConnectivityManager>()!! }
        val packageManager by lazy { application.packageManager }
        val powerManager by lazy { application.getSystemService<PowerManager>()!! }
        val notificationManager by lazy { application.getSystemService<NotificationManager>()!! }
        val wifiManager by lazy { application.getSystemService<WifiManager>()!! }
    }
}
