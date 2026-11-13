package io.nekohasekai.sfa.bridge

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import androidx.activity.enableEdgeToEdge
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.bg.BoxService
import io.nekohasekai.sfa.bg.ServiceConnection
import io.nekohasekai.sfa.bg.ServiceNotification
import com.google.androidgamesdk.GameActivity
import kotlinx.coroutines.CancellationException
import io.nekohasekai.sfa.constant.Action
import io.nekohasekai.sfa.constant.Alert
import io.nekohasekai.sfa.constant.ServiceMode
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.ktx.hasPermission
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream

open class BoxActivity : GameActivity(), ServiceConnection.Callback {
    val serviceStatus = MutableStateFlow(Status.Stopped)
    private val connection = ServiceConnection(this, this)
    private var bridge: BoxBridge? = null
    private var notificationPermissionRequested = false
    private var localNetworkPermissionRequested = false
    private var startPending = false
    private var documentRequest: CompletableDeferred<Uri?>? = null
    private val pendingIntents = ArrayDeque<String>()
    private val shares = java.util.concurrent.ConcurrentHashMap<String, Intent>()
    private var launching = false
    private var startGeneration = 0
    private val startResult = ServiceStartResult()

    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (startPending && (granted || !Settings.dynamicNotification)) resumeStart() else {
            startPending = false
            onServiceAlert(Alert.RequestNotificationPermission, null)
        }
    }
    private val localNetworkPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (startPending) resumeStart()
    }
    private val backgroundLocationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && startPending) resumeStart() else {
            startPending = false
            failStart(getString(R.string.location_permission_description))
        }
    }
    private val locationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if ((grants[Manifest.permission.ACCESS_FINE_LOCATION] == true || hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) && startPending) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                backgroundLocationPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            } else {
                resumeStart()
            }
        } else {
            startPending = false
            failStart(getString(R.string.location_permission_description))
        }
    }
    private val vpnPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (startPending && result.resultCode == RESULT_OK) {
            launchService()
        } else {
            startPending = false
            onServiceAlert(Alert.RequestVPNPermission, null)
        }
    }
    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        documentRequest?.complete(uri)
        documentRequest = null
    }
    private val createDocument = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        documentRequest?.complete(if (result.resultCode == RESULT_OK) result.data?.data else null)
        documentRequest = null
    }

    /** The Rust + Slint UI (libfkui.so) draws on GameActivity's surface; it reaches BoxBridge through [slintCall]. */
    private fun attachUi() {
        bridge = BoxBridge(this)
    }

    /** Called by the Slint UI from its own thread; answers through [SlintHost.nativeReply]. */
    @Suppress("unused")
    fun slintCall(id: Long, method: String, args: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            val facade = bridge
            if (facade == null) {
                SlintHost.nativeReply(id, false, SlintHost.wire("Bridge is not ready"))
                return@launch
            }
            if (method == "attach") {
                facade.listen { event -> SlintHost.nativeEvent(SlintHost.wire(event)) }
                SlintHost.nativeReply(id, true, SlintHost.wire(null))
                return@launch
            }
            // Calls the activity answers itself (the Compose UI used the context directly).
            when (method) {
                "locale" -> {
                    val locale = resources.configuration.locales[0]
                    val offset = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000
                    SlintHost.nativeReply(id, true, SlintHost.wire(mapOf("language" to locale.language, "utcOffsetMinutes" to offset, "fontScale" to resources.configuration.fontScale)))
                    return@launch
                }
                "copyText" -> {
                    val text = org.json.JSONObject(args).optString("text")
                    getSystemService(android.content.ClipboardManager::class.java)
                        ?.setPrimaryClip(android.content.ClipData.newPlainText(null, text))
                    SlintHost.nativeReply(id, true, SlintHost.wire(null))
                    return@launch
                }
                "clipboardText" -> {
                    val clip = getSystemService(android.content.ClipboardManager::class.java)?.primaryClip
                    val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this@BoxActivity)?.toString()
                    SlintHost.nativeReply(id, true, SlintHost.wire(text))
                    return@launch
                }
                "appIcon" -> {
                    // PackageIcon: the app's icon as a PNG in the cache, read by the Slint UI.
                    val pkg = org.json.JSONObject(args).optString("package")
                    val path = withContext(Dispatchers.IO) {
                        runCatching {
                            val dir = java.io.File(cacheDir, "slint-icons").apply { mkdirs() }
                            val file = java.io.File(dir, "$pkg.png")
                            if (!file.exists()) {
                                val drawable = packageManager.getApplicationIcon(pkg)
                                val size = (48 * resources.displayMetrics.density).toInt().coerceAtLeast(48)
                                val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
                                val canvas = android.graphics.Canvas(bitmap)
                                drawable.setBounds(0, 0, size, size)
                                drawable.draw(canvas)
                                file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                            }
                            file.absolutePath
                        }.getOrNull()
                    }
                    SlintHost.nativeReply(id, true, SlintHost.wire(path))
                    return@launch
                }
                "exit" -> {
                    SlintHost.nativeReply(id, true, SlintHost.wire(null))
                    finish()
                    return@launch
                }
            }
            try {
                val result = facade.call { slintDispatch(method, org.json.JSONObject(args)) }
                SlintHost.nativeReply(id, true, SlintHost.wire(result))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SlintHost.nativeReply(id, false, SlintHost.wire(e.message ?: e.toString()))
            }
        }
    }

    private fun detachUi() {
        bridge?.cancel()
        bridge?.close()
        bridge = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        savedInstanceState?.let { state ->
            notificationPermissionRequested = state.getBoolean("box.notificationPermission")
            localNetworkPermissionRequested = state.getBoolean("box.localNetworkPermission")
            startPending = state.getBoolean("box.startPending")
            state.getStringArrayList("box.intents")?.let { pendingIntents.addAll(it) }
            state.getBundle("box.shares")?.let { bundle ->
                for (key in bundle.keySet()) {
                    androidx.core.os.BundleCompat.getParcelable(bundle, key, Intent::class.java)?.let { shares[key] = it }
                }
            }
        }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        attachUi()
        lifecycleScope.launch {
            Settings.dataStore.initialize()
            connection.reconnect()
            if (savedInstanceState == null) handleIntent(intent)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("box.notificationPermission", notificationPermissionRequested)
        outState.putBoolean("box.localNetworkPermission", localNetworkPermissionRequested)
        outState.putBoolean("box.startPending", startPending)
        outState.putStringArrayList("box.intents", ArrayList(pendingIntents))
        outState.putBundle("box.shares", Bundle().apply { shares.forEach { (key, value) -> putParcelable(key, value) } })
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        bridge?.refreshSystemAppearance()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val value = if (intent.action in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) {
            val token = java.util.UUID.randomUUID().toString()
            shares[token] = Intent(intent)
            "bbox-share:" + token
        } else {
            val uri = intent.data ?: return
            if (intent.action == Action.OPEN_URL) {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }.onFailure { bridge?.reportError(it.message.orEmpty()) }
                return
            }
            uri.toString()
        }
        pendingIntents.addLast(value)
        bridge?.notifyIntent()
    }

    fun takeIntent(): String? = pendingIntents.removeFirstOrNull()

    fun discardShare(token: String) { shares.remove(token) }

    fun openShare(token: String): List<io.nekohasekai.sfa.backend.tools.TaildropSendFile> {
        val shared = shares[token] ?: error("Shared content no longer exists")
        val uris = if (shared.action == Intent.ACTION_SEND_MULTIPLE) {
            IntentCompat.getParcelableArrayListExtra(shared, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else listOfNotNull(IntentCompat.getParcelableExtra(shared, Intent.EXTRA_STREAM, Uri::class.java))
        val files = if (uris.isNotEmpty()) {
            io.nekohasekai.sfa.backend.tools.TaildropSendManager.openURIs(uris)
        } else {
            val text = shared.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }
                ?: error("No shared content")
            listOf(io.nekohasekai.sfa.backend.tools.TaildropSendManager.createTextFile("shared.txt", text))
        }
        shares.remove(token)
        return files
    }

    fun reconnect() = connection.reconnect()

    private fun resumeStart() {
        startPending = false
        startService()
    }

    @SuppressLint("NewApi")
    fun startService() {
        if (launching || startPending) return
        if (serviceStatus.value == Status.Started || serviceStatus.value == Status.Stopping || serviceStatus.value == Status.Starting) return
        startPending = true
        launching = true
        val generation = startGeneration
        lifecycleScope.launch {
            runCatching {
                Application.application.setupLibbox()
                Settings.dataStore.initialize()
                require(Settings.selectedProfile != -1L) { getString(R.string.error_empty_configuration) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !ServiceNotification.checkPermission()) {
                    if (!notificationPermissionRequested) {
                        notificationPermissionRequested = true
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        launching = false
                        return@launch
                    }
                    if (Settings.dynamicNotification) {
                        startPending = false
                        onServiceAlert(Alert.RequestNotificationPermission, null)
                        launching = false
                        return@launch
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN &&
                    !hasPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) && !localNetworkPermissionRequested
                ) {
                    localNetworkPermissionRequested = true
                    localNetworkPermissionLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
                    launching = false
                    return@launch
                }
                withContext(Dispatchers.IO) { Settings.rebuildServiceMode() }
                if (!startPending || generation != startGeneration) { launching = false; return@launch }
                if (Settings.serviceMode == ServiceMode.VPN) {
                    val permission = VpnService.prepare(this@BoxActivity)
                    if (permission != null) {
                        vpnPermissionLauncher.launch(permission)
                        launching = false
                        return@launch
                    }
                }
                launchService()
            }.onFailure {
                startPending = false
                failStart(it.message ?: it.toString())
            }
            launching = false
        }
    }

    private fun launchService() {
        if (!startPending) return
        startPending = false
        serviceStatus.value = Status.Starting
        runCatching {
            ContextCompat.startForegroundService(this, Intent(this, Settings.serviceClass()))
            Settings.startedByUser = true
        }.onFailure { serviceStatus.value = Status.Stopped; failStart(it.message ?: it.toString()) }
    }

    fun stopService() {
        startGeneration++
        startPending = false
        startResult.failed(IllegalStateException("Service start cancelled"))
        BoxService.stop()
    }

    suspend fun applyProfile(restart: Boolean = false, recover: Boolean = false) {
        if (serviceStatus.value != Status.Started && !recover) return
        if (Settings.selectedProfile == -1L) {
            stopService()
            withTimeout(15000) { serviceStatus.first { it == Status.Stopped } }
            return
        }
        if (!restart && !recover && serviceStatus.value == Status.Started && Settings.detectServiceMode() == Settings.serviceMode) {
            withContext(Dispatchers.IO) { Libbox.newStandaloneCommandClient().serviceReload() }
        } else {
            stopService()
            withTimeout(15000) { serviceStatus.first { it == Status.Stopped } }
            try {
                startResult.awaitStarted { startService() }
            } catch (error: Exception) {
                // Finish the failed attempt before the caller restores its files.
                withContext(NonCancellable) {
                    stopService()
                    runCatching { withTimeout(15000) { serviceStatus.first { it == Status.Stopped } } }
                        .exceptionOrNull()?.let(error::addSuppressed)
                }
                throw error
            }
        }
    }

    suspend fun pickDocument(saveName: String? = null): Uri? {
        check(documentRequest == null) { "A document picker is already open" }
        val request = CompletableDeferred<Uri?>()
        documentRequest = request
        try {
            if (saveName == null) openDocument.launch(arrayOf("*/*")) else createDocument.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = java.net.URLConnection.guessContentTypeFromName(saveName) ?: "application/octet-stream"
                putExtra(Intent.EXTRA_TITLE, saveName)
            })
            return request.await()
        } finally {
            if (documentRequest === request) documentRequest = null
        }
    }

    fun readDocument(uri: Uri, maxBytes: Int = 16 * 1024 * 1024): Pair<String, ByteArray> {
        require(uri.scheme == "content" || uri.scheme == "file") { "Unsupported document URL" }
        val name = if (uri.scheme == "content") {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } else uri.lastPathSegment
        val bytes = contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= maxBytes) { "Document exceeds ${maxBytes / 1024 / 1024} MiB" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: error("Unable to read document")
        return (name ?: "Profile.json") to bytes
    }

    override fun onServiceStatusChanged(status: Status) {
        serviceStatus.value = status
        if (status == Status.Started) startResult.started()
    }

    override fun onServiceAlert(type: Alert, message: String?) {
        lifecycleScope.launch { handleServiceAlert(type, message) }
    }

    private fun handleServiceAlert(type: Alert, message: String?) {
        if (type == Alert.RequestLocationPermission) {
            startPending = true
            locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            return
        }
        startPending = false
        val description = message ?: getString(
            when (type) {
                Alert.RequestVPNPermission -> R.string.error_missing_vpn_permission
                Alert.RequestNotificationPermission -> R.string.notification_permission_required_description
                Alert.EmptyConfiguration -> R.string.error_empty_configuration
                Alert.StartCommandServer -> R.string.error_start_command_server
                Alert.CreateService -> R.string.error_create_service
                else -> R.string.error_start_service
            },
        )
        failStart(description)
    }

    private fun failStart(description: String) {
        startPending = false
        startResult.failed(IllegalStateException(description))
        bridge?.reportError(description)
    }

    override fun onDestroy() {
        documentRequest?.cancel()
        startResult.failed(IllegalStateException("Activity closed during service start"))
        connection.disconnect()
        detachUi()
        super.onDestroy()
    }
}
