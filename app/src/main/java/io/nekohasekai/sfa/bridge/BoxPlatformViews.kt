package io.nekohasekai.sfa.bridge

import android.content.Context
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformViewFactory
import io.flutter.plugin.platform.PlatformView as FlutterPlatformView

/** "io.koko123as.flbox/openconnect": the OpenConnect SSO WebView. */
internal class BoxBrowserViewFactory(private val bridge: BoxBridge) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {
    private val browsers = BoxBrowserFactory(bridge)

    override fun create(context: Context, viewId: Int, args: Any?): FlutterPlatformView {
        val view = browsers.create(context, viewId, args)
        return object : FlutterPlatformView {
            override fun getView() = view.getView()
            override fun dispose() = view.dispose()
        }
    }
}

/**
 * "io.koko123as.flbox/ghostty": the Ghostty terminal surface. Each view talks to
 * its Flutter page on "io.koko123as.flbox/terminal/<id>": the page drives the
 * surface ("update", "key") and answers the surface's prompts.
 */
internal class BoxTerminalViewFactory(private val messenger: BinaryMessenger) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {
    private val terminals = BoxTerminalFactory()

    override fun create(context: Context, viewId: Int, args: Any?): FlutterPlatformView {
        val channel = MethodChannel(messenger, "io.koko123as.flbox/terminal/$viewId")
        val surface = terminals.create(context, viewId, args, object : TerminalChannel {
            override fun request(method: String, value: Any?, respond: (Any?) -> Unit) {
                channel.invokeMethod(method, value, object : MethodChannel.Result {
                    override fun success(result: Any?) = respond(result)
                    override fun error(code: String, message: String?, details: Any?) = respond(null)
                    override fun notImplemented() = respond(null)
                })
            }

            override fun notify(method: String, value: Any?) = channel.invokeMethod(method, value)
        })
        channel.setMethodCallHandler { call, result ->
            try {
                surface.invoke(call.method, call.arguments)
                result.success(null)
            } catch (e: Exception) {
                result.error("terminal", e.message, null)
            }
        }
        return object : FlutterPlatformView {
            override fun getView() = surface.getView()
            override fun dispose() {
                channel.setMethodCallHandler(null)
                surface.dispose()
            }
        }
    }
}
