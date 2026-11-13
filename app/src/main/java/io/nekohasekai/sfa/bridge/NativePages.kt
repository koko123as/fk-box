package io.nekohasekai.sfa.bridge

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sfa.compose.qr.ZxingQRCodeAnalyzer
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

/**
 * The native pages the Slint UI opens over itself through JNI (BoxActivity.slintCall):
 * the QR scanner (CameraX + ZXing, formerly the Compose ScanPage), the Ghostty
 * terminal surface (formerly GhosttySurface in TerminalPage) and the OpenConnect
 * sign-in browser (formerly AuthBrowser). Each one answers its pending call id
 * itself, with null when it is closed without a result.
 */
internal object NativePages {
    /** The bridge of the running BoxActivity; the pages run in its process and task. */
    @Volatile var bridge: WeakReference<BoxBridge>? = null

    /** The open sign-in browser, closed when its challenge completes. */
    @Volatile var browser: WeakReference<Activity>? = null

    const val EXTRA_CALL = "call"

    fun launch(activity: Activity, type: Class<out Activity>, call: Long, extras: Bundle) {
        activity.startActivity(Intent(activity, type).putExtras(extras).putExtra(EXTRA_CALL, call))
    }
}

/** Base: replies once, with null if the page closes first; a bar with a title, back and actions. */
internal abstract class NativePage : ComponentActivity() {
    private var call = 0L
    private var replied = false
    protected val bridge: BoxBridge? get() = NativePages.bridge?.get()
    protected lateinit var bar: LinearLayout
    protected lateinit var content: FrameLayout
    protected val density: Float get() = resources.displayMetrics.density
    protected val dark: Boolean get() = intent.getBooleanExtra("dark", (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES)
    protected val foreground: Int get() = if (dark) Color.WHITE else Color.BLACK
    protected val background: Int get() = if (dark) Color.BLACK else Color.WHITE

    protected fun reply(ok: Boolean, value: Any?) {
        if (replied) return
        replied = true
        SlintHost.nativeReply(call, ok, SlintHost.wire(value))
    }

    protected fun dp(value: Int) = (value * density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        call = intent.getLongExtra(NativePages.EXTRA_CALL, 0L)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(background)
        }
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        bar.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = "←"
            textSize = 22f
            setTextColor(foreground)
            contentDescription = getString(android.R.string.cancel)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(56), dp(48)))
        bar.addView(TextView(this).apply {
            text = intent.getStringExtra("title") ?: ""
            textSize = 20f
            setTextColor(foreground)
            setPadding(dp(8), 0, 0, 0)
            maxLines = 1
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)))
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)
    }

    protected fun barAction(label: String, onClick: () -> Unit): Button = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
        text = label
        setTextColor(foreground)
        setOnClickListener { onClick() }
        bar.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
    }

    protected fun message(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(foreground)
        gravity = Gravity.CENTER
        setPadding(dp(24), dp(24), dp(24), dp(24))
    }

    override fun onDestroy() {
        if (isFinishing) reply(true, null)
        super.onDestroy()
    }
}

/** ScanPage: frames go to BoxQrScanner (scanProfile) until a profile or a plain text decodes. */
internal class ScanActivity : NativePage() {
    private val session = (System.nanoTime() / 1000).toString()
    private var camera: Camera? = null
    private var torch = false
    private var busy = false
    private var complete = false
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else showPermission()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        intent.putExtra("title", intent.getStringExtra("title") ?: "Scan QR code")
        super.onCreate(savedInstanceState)
        barAction(intent.getStringExtra("flash") ?: "Flash") {
            camera?.let {
                torch = !torch
                it.cameraControl.enableTorch(torch)
            }
        }
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { visibility = View.GONE; max = 100 }
        status = TextView(this).apply { setTextColor(foreground) }
        footer.addView(progress)
        footer.addView(status)
        (content.parent as LinearLayout).addView(footer)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera() else permission.launch(Manifest.permission.CAMERA)
    }

    private fun showPermission() {
        content.removeAllViews()
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        column.addView(message(intent.getStringExtra("camera") ?: "Camera"))
        column.addView(Button(this).apply {
            text = intent.getStringExtra("retry") ?: "Retry"
            setOnClickListener { permission.launch(Manifest.permission.CAMERA) }
        })
        content.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun startCamera() {
        content.removeAllViews()
        val view = PreviewView(this)
        content.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(Executors.newSingleThreadExecutor(), ZxingQRCodeAnalyzer(
                    onSuccess = { text -> if (text.isNotEmpty()) view.post { scan(text) } },
                    onFailure = { failure -> view.post { status.text = failure.message } },
                ))
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (failure: Exception) {
                content.removeAllViews()
                content.addView(message(failure.message ?: failure.toString()))
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun scan(text: String) {
        if (busy || complete) return
        val bridge = bridge ?: return
        busy = true
        lifecycleScope.launch {
            try {
                val result = bridge.scanProfile(session, text)
                when {
                    result["profile"] is Map<*, *> -> finishWith(mapOf("profile" to result["profile"]))
                    result["text"] is String -> finishWith(mapOf("text" to result["text"]))
                    else -> {
                        val decoded = (result["decoded"] as? Number)?.toInt() ?: 0
                        val total = (result["total"] as? Number)?.toInt() ?: 0
                        if (total > 0) {
                            progress.visibility = View.VISIBLE
                            progress.progress = decoded * 100 / total
                            status.text = "${intent.getStringExtra("scanning") ?: "Scanning configuration"} $decoded / $total"
                        }
                    }
                }
            } catch (failure: Exception) {
                status.text = failure.message ?: failure.toString()
            } finally {
                busy = false
            }
        }
    }

    private fun finishWith(value: Map<String, Any?>) {
        complete = true
        reply(true, value)
        finish()
    }

    override fun onDestroy() {
        if (isFinishing) bridge?.closeProfileScan(session)
        super.onDestroy()
    }
}

/** GhosttySurface: the session's terminal view and its extra keys. */
internal class TerminalActivity : NativePage() {
    private var surface: TerminalSurface? = null
    private val keyButtons = mutableMapOf<String, Button>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preview = intent.getBooleanExtra("preview", false)
        val sessionId = intent.getStringExtra("id") ?: if (preview) "" else return finish()
        val terminal = TerminalSurface(this, 0, mapOf("id" to sessionId, "dark" to dark, "preview" to preview), object : TerminalChannel {
            override fun request(method: String, value: Any?, respond: (Any?) -> Unit) {
                val text = intent.getBundleExtra("texts")
                fun t(key: String) = text?.getString(key) ?: key
                when (method) {
                    "unsafePaste" -> AlertDialog.Builder(this@TerminalActivity).setTitle(t("Paste multiple lines?")).setMessage(t("Pasted text may execute commands in this terminal."))
                        .setPositiveButton(t("Paste")) { _, _ -> respond(true) }.setNegativeButton(t("Cancel")) { _, _ -> respond(false) }.setOnCancelListener { respond(false) }.show()
                    "clipboardRead" -> AlertDialog.Builder(this@TerminalActivity).setTitle(t("Allow terminal to read clipboard?"))
                        .setPositiveButton(t("Allow")) { _, _ -> respond(true) }.setNegativeButton(t("Cancel")) { _, _ -> respond(false) }.setOnCancelListener { respond(false) }.show()
                    "link" -> AlertDialog.Builder(this@TerminalActivity).setTitle(t("Link")).setMessage(value?.toString())
                        .setPositiveButton(t("Open")) { _, _ -> respond("open") }.setNeutralButton(t("Copy")) { _, _ -> respond("copy") }
                        .setNegativeButton(t("Cancel")) { _, _ -> respond(null) }.setOnCancelListener { respond(null) }.show()
                    else -> respond(null)
                }
            }

            override fun notify(method: String, value: Any?) {
                if (method != "modifiers") return
                val states = value as? Map<*, *> ?: return
                for ((key, button) in keyButtons) {
                    val state = states[key.lowercase()] as? String ?: continue
                    button.alpha = if (state == "ARMED" || state == "LOCKED") 1f else 0.7f
                    button.text = key + if (state == "LOCKED") " •" else ""
                }
            }
        })
        surface = terminal
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(terminal.getView(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val keys = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val labels = intent.getBundleExtra("keys")
        for (key in listOf("Esc", "Tab", "Ctrl", "Alt", "↑", "↓", "←", "→", "Home", "End", "PgUp", "PgDn", "Paste", "Keyboard")) {
            val button = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                text = labels?.getString(key) ?: key
                setTextColor(foreground)
                isAllCaps = false
                alpha = if (key == "Ctrl" || key == "Alt") 0.7f else 1f
                setOnClickListener {
                    try {
                        terminal.invoke("key", mapOf("key" to key))
                    } catch (failure: Exception) {
                        bridge?.reportError(failure.message.orEmpty())
                    }
                }
            }
            if (key == "Ctrl" || key == "Alt") keyButtons[key] = button
            keys.addView(button)
        }
        if (!preview) column.addView(HorizontalScrollView(this).apply { addView(keys) })
        content.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    override fun onDestroy() {
        surface?.dispose()
        surface = null
        super.onDestroy()
    }
}

/** AuthBrowser: the OpenConnect sign-in WebView; closes itself when the challenge completes. */
internal class BrowserActivity : NativePage() {
    private var view: PlatformView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bridge = bridge ?: return finish()
        val page = BoxBrowserFactory(bridge).create(this, 0, mapOf("endpoint" to intent.getStringExtra("endpoint"), "challenge" to intent.getStringExtra("challenge")))
        view = page
        content.addView(page.getView(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        NativePages.browser = WeakReference(this)
    }

    override fun onDestroy() {
        if (NativePages.browser?.get() === this) NativePages.browser = null
        view?.dispose()
        view = null
        super.onDestroy()
    }
}
