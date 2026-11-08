package io.nekohasekai.sfa

import android.app.Application
import android.app.NotificationManager
import android.content.ClipboardManager
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
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.sfa.bg.AppChangeReceiver
import io.nekohasekai.sfa.bg.CrashReportManager
import io.nekohasekai.sfa.bg.GeoResources
import io.nekohasekai.sfa.bg.OOMReportManager
import io.nekohasekai.sfa.bg.PowerReportManager
import io.nekohasekai.sfa.bg.UpdateGeoWork
import io.nekohasekai.sfa.bg.UpdateProfileWork
import io.nekohasekai.sfa.backend.tools.TaildropFiles
import io.nekohasekai.sfa.constant.Bugs
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.utils.AppLifecycleObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.util.Locale
import io.nekohasekai.sfa.Application as BoxApplication

class Application : Application() {
    private val setupAccess = Mutex()
    private val workingDirAccess = Any()
    @Volatile private var workingDir: File? = null
    @Volatile private var setupCompleted = false
    private val localeAccess = Any()
    @Volatile private var localeApplied = false

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        application = this
        // The ported FlClash host plugins (com.follow.clash) read the application here.
        com.follow.clash.common.GlobalState.init(this)
    }

    override fun onCreate() {
        super.onCreate()
        AppLifecycleObserver.register(this)
        // JVM crash capture is armed before anything else; its files are
        // resolved off the main thread below.
        CrashReportManager.installHandler()

//        Seq.setContext(this)
        // Loading libbox (dlopen, relocations, Go runtime and package init)
        // and the working directory setup (external storage, report archives)
        // run on IO instead of the main thread. The locale is set first, so
        // it is in place before setupLibbox() and every caller that waits on it.
        @Suppress("OPT_IN_USAGE")
        GlobalScope.launch(Dispatchers.IO) {
            applyLocale()
            filesDir.mkdirs()
            cacheDir.mkdirs()
            installWorkingDir()
            Settings.dataStore.initialize()
            WorkManager.getInstance(this@Application).cancelUniqueWork("AutoUpdate")
            runCatching {
                setupLibbox()
            }.onFailure {
                Log.w("Application", "setup libbox: ${it.message}")
            }
            UpdateProfileWork.reconfigureUpdater()
            UpdateGeoWork.reconfigure()
            // Bundled geo data for Xray/mihomo, extracted off the start path.
            if (setupCompleted) runCatching { GeoResources.ensureBundled(this@Application) }
            TaildropFiles.cleanCache()
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
     * the window's render targets, including the Liquid Glass layers, itself
     * when the UI is hidden.
     */
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (!setupCompleted || level < TRIM_MEMORY_RUNNING_LOW) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastTrim < 10_000L) return
        lastTrim = now
        @Suppress("OPT_IN_USAGE")
        GlobalScope.launch(Dispatchers.IO) { runCatching { Libbox.collectUIRuntimeMemory() } }
    }

    @Volatile private var lastTrim = 0L

    /** Sets the libbox locale once; the first libbox call that loads the library. */
    private fun applyLocale() {
        if (localeApplied) return
        synchronized(localeAccess) {
            if (localeApplied) return
            runCatching {
                Libbox.setLocale(Locale.getDefault().toLanguageTag())
            }.onFailure {
                Log.d("Application", "set locale: ${it.message}")
            }
            localeApplied = true
        }
    }

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

    suspend fun setupLibbox() = setupAccess.withLock {
        if (setupCompleted) return@withLock
        applyLocale()
        val dir = installWorkingDir() ?: error("android: external storage unavailable")
        Libbox.setup(createSetupOptions(filesDir, dir, cacheDir))
        setupCompleted = true
    }

    fun reloadSetupOptions() {
        val dir = getExternalFilesDir(null) ?: return
        Libbox.reloadSetupOptions(createSetupOptions(filesDir, dir, cacheDir))
    }

    private fun createSetupOptions(baseDir: File, workingDir: File, tempDir: File): SetupOptions = SetupOptions().also {
        it.basePath = baseDir.path
        it.workingPath = workingDir.path
        it.tempPath = tempDir.path
        it.fixAndroidStack = Bugs.fixAndroidStack
        it.logMaxLines = 3000
        it.debug = BuildConfig.DEBUG
        it.crashReportSource = "Application"
        it.appVersion = BuildConfig.VERSION_CODE.toString()
        it.appMarketingVersion = BuildConfig.VERSION_NAME
        it.oomKillerEnabled = Settings.oomKillerEnabled
        it.oomKillerDisabled = Settings.oomKillerDisabled
        it.oomMemoryLimit = Settings.oomMemoryLimitMB.toLong() * 1024L * 1024L
        it.powerReportEnabled = Settings.powerReportEnabled
        it.platformMetadata = JSONObject().apply {
            put("os", "Android " + Build.VERSION.RELEASE)
            put("sdk", Build.VERSION.SDK_INT)
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
        }.toString()
    }

    companion object {
        lateinit var application: BoxApplication
        val notification by lazy { application.getSystemService<NotificationManager>()!! }
        val connectivity by lazy { application.getSystemService<ConnectivityManager>()!! }
        val packageManager by lazy { application.packageManager }
        val powerManager by lazy { application.getSystemService<PowerManager>()!! }
        val notificationManager by lazy { application.getSystemService<NotificationManager>()!! }
        val wifiManager by lazy { application.getSystemService<WifiManager>()!! }
        val clipboard by lazy { application.getSystemService<ClipboardManager>()!! }
    }
}
