package io.nekohasekai.sfa.flutter

import android.content.Context
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.View
import io.github.sagernet.libghostty.GhosttyExtraKeysState
import io.github.sagernet.libghostty.GhosttyTerminalView
import io.github.sagernet.libghostty.GhosttyTerminalSession
import io.github.sagernet.libghostty.GhosttyUiHandler
import io.github.sagernet.libghostty.extras.GhosttyFontStore
import io.github.sagernet.libghostty.extras.GhosttyThemeStore
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.terminal.GhosttyCustomConfig
import io.nekohasekai.sfa.terminal.TailscaleSSHSessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The per-view message link that replaced the terminal's MethodChannel: the
 * surface asks the UI ([request]) and the UI drives the surface ([TerminalSurface.invoke]).
 */
interface TerminalChannel {
    /** Asks the UI; [respond] receives its answer or null. */
    fun request(method: String, value: Any?, respond: (Any?) -> Unit)
    fun notify(method: String, value: Any?)
}

/** Only the terminal surface is native. Navigation, settings and prompts are Compose. */
internal class BoxTerminalFactory {
    fun create(context: Context, viewId: Int, args: Any?, channel: TerminalChannel): TerminalSurface = TerminalSurface(context, viewId, args, channel)
}

internal class TerminalSurface(context: Context, id: Int, args: Any?, private val channel: TerminalChannel) : PlatformView {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val view = GhosttyTerminalView(context)
    private val modifiers = GhosttyExtraKeysState()
    private val pending = mutableSetOf<(Any?) -> Unit>()
    private var disposed = false
    private var sessionId: String? = null
    private var dark = false
    private val preview = (args as? Map<*, *>)?.get("preview") == true
    private val previewSession = if (preview) GhosttyTerminalSession(context).apply {
        feedOutput("\u001b[32muser@b-box\u001b[0m ~\r\n\u001b[36m❯\u001b[0m echo Ghostty\r\nGhostty\r\n\r\n\u001b[1mBold\u001b[0m  \u001b[3mItalic\u001b[0m  \u001b[4mUnderline\u001b[0m\r\n\u001b[31mRed  \u001b[32mGreen  \u001b[34mBlue\u001b[0m\r\n".toByteArray())
    } else null

    init {
        view.extraKeysState = modifiers
        view.showImeOnTap = !preview
        view.uiHandler = object : GhosttyUiHandler {
            override fun confirmUnsafePaste(context: Context, paste: () -> Unit) {
                request("unsafePaste", null) { if (it == true) paste() }
            }
            override fun requestClipboardRead(context: Context, respond: (Boolean) -> Unit) {
                request("clipboardRead", null) { respond(it == true) }
            }
            override fun showLinkMenu(context: Context, url: String, openLink: () -> Unit, copyUrl: () -> Unit) {
                request("link", url) { when (it) { "open" -> openLink(); "copy" -> copyUrl() } }
            }
        }
        view.listener = object : GhosttyTerminalView.Listener {
            override fun onFontSizeChanged(view: GhosttyTerminalView, fontSizeSp: Float) {
                Settings.tailscaleSSHFontSize = fontSizeSp.toInt()
            }
            override fun onFinishedSessionKey(view: GhosttyTerminalView) {
                sessionId?.let(TailscaleSSHSessionStore::removeSession)
            }
        }
        update(args as? Map<*, *> ?: emptyMap<String, Any?>())
        scope.launch { modifiers.ctrl.collect { publishModifiers() } }
        scope.launch { modifiers.alt.collect { publishModifiers() } }
        scope.launch {
            TailscaleSSHSessionStore.state.collect { state ->
                if (!preview && state.sessions.none { it.id == sessionId }) view.session = null
            }
        }
        if (!preview) view.post { if (!disposed) view.showIme() }
    }

    /** Former MethodChannel handler for operations sent from the UI. */
    fun invoke(method: String, arguments: Any?) {
        val values = arguments as? Map<*, *> ?: emptyMap<String, Any?>()
        when (method) {
            "update" -> update(values)
            "key" -> {
                when (values["key"]) {
                    "Ctrl" -> modifiers.toggleCtrl()
                    "Alt" -> modifiers.toggleAlt()
                    "Paste" -> view.pasteFromClipboard()
                    "Keyboard" -> view.showIme()
                    else -> view.sendKey(keyCodes[values["key"]] ?: error("Unknown terminal key"))
                }
            }
            else -> error("Unknown terminal view operation")
        }
    }

    private fun publishModifiers() = channel.notify("modifiers", mapOf("ctrl" to modifiers.ctrl.value.name, "alt" to modifiers.alt.value.name))

    private fun update(args: Map<*, *>) {
        sessionId = args["id"] as? String
        dark = args["dark"] == true
        val session = previewSession ?: TailscaleSSHSessionStore.state.value.sessions.firstOrNull { it.id == sessionId }?.terminalSession
        view.session = session
        val name = if (dark) Settings.tailscaleSSHDarkTheme else Settings.tailscaleSSHLightTheme
        val config = if (name.isEmpty()) GhosttyCustomConfig.parse(if (dark) Settings.tailscaleSSHDarkConfig else Settings.tailscaleSSHLightConfig) else null
        val theme = config?.theme ?: GhosttyThemeStore.loadThemeOrDefault(view.context, name, dark)
        theme?.let { session?.setTheme(it) }
        session?.setColorScheme(dark)
        view.typeface = if (Settings.tailscaleSSHFontFollowTheme) {
            config?.fontFamily?.takeIf { it.isNotBlank() }?.let { Typeface.create(it, Typeface.NORMAL) } ?: Typeface.MONOSPACE
        } else GhosttyFontStore.resolveTypeface(Settings.tailscaleSSHFontFamily, Settings.tailscaleSSHCustomFontPath) ?: Typeface.MONOSPACE
        view.fontSizeSp = Settings.tailscaleSSHFontSize.toFloat()
    }

    private fun request(method: String, value: Any?, respond: (Any?) -> Unit) {
        if (disposed) { respond(null); return }
        lateinit var finish: (Any?) -> Unit
        finish = { response -> if (pending.remove(finish)) respond(response) }
        pending.add(finish)
        channel.request(method, value) { finish(it) }
    }

    override fun getView(): View = view
    override fun dispose() {
        disposed = true
        pending.toList().forEach { it(null) }
        view.session = null
        previewSession?.close()
        view.listener = null
        view.clearFocus()
        scope.cancel()
    }

    companion object {
        private val keyCodes = mapOf("Esc" to KeyEvent.KEYCODE_ESCAPE, "Tab" to KeyEvent.KEYCODE_TAB,
            "↑" to KeyEvent.KEYCODE_DPAD_UP, "↓" to KeyEvent.KEYCODE_DPAD_DOWN,
            "←" to KeyEvent.KEYCODE_DPAD_LEFT, "→" to KeyEvent.KEYCODE_DPAD_RIGHT,
            "Home" to KeyEvent.KEYCODE_MOVE_HOME, "End" to KeyEvent.KEYCODE_MOVE_END,
            "PgUp" to KeyEvent.KEYCODE_PAGE_UP, "PgDn" to KeyEvent.KEYCODE_PAGE_DOWN)
    }
}
