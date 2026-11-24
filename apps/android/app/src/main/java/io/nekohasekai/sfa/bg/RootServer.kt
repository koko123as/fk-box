package io.nekohasekai.sfa.bg

import android.content.Intent
import android.content.pm.PackageInfo
import android.os.IBinder
import android.os.RemoteCallbackList
import com.topjohnwu.superuser.ipc.RootService
import io.nekohasekai.sfa.vendor.PrivilegedServiceUtils
import java.io.File
import java.io.IOException

/**
 * The root process (libsu RootService) for the root package query.
 *
 * Single-core build (stage 6, docs/architecture/brain-android.md): the
 * service runs only as a VpnService. Root auto-redirect, the root bridge,
 * root shell sessions and the neighbour table were libbox services; their
 * wiring is removed, not their interface: IRootService keeps every method
 * and its transaction number, and these answer that the feature is not
 * available (the user's settings stay as they were).
 */
class RootServer : RootService() {

    private val neighborCallbacks = RemoteCallbackList<INeighborTableCallback>()

    private val binder = object : IRootService.Stub() {
        override fun destroy() {
            stopSelf()
        }

        override fun getInstalledPackages(flags: Int, userId: Int): ParceledListSlice<PackageInfo> {
            val allPackages = PrivilegedServiceUtils.getInstalledPackages(flags, userId)
            return ParceledListSlice(allPackages)
        }

        // The neighbour table was read through libbox; callbacks are kept but never called.
        override fun registerNeighborTableCallback(callback: INeighborTableCallback?) {
            if (callback == null) return
            neighborCallbacks.register(callback)
        }

        override fun unregisterNeighborTableCallback(callback: INeighborTableCallback?) {
            if (callback == null) return
            neighborCallbacks.unregister(callback)
        }

        override fun openShellSession(
            user: String?,
            command: String?,
            env: Array<out String>?,
            term: String?,
            rows: Int,
            cols: Int,
        ): IRootShellSession = throw IllegalStateException(UNAVAILABLE)

        override fun openBridge(
            bridgeName: String?,
            mtu: Int,
            inet4Port: String?,
            inet6Port: String?,
            ruleIndex: Int,
            routeTable: Int,
        ): IBridgeSession = throw IllegalStateException(UNAVAILABLE)

        override fun startAutoRedirect(options: ByteArray?, handler: IAutoRedirectHandler?): IAutoRedirectSession =
            throw IllegalStateException(UNAVAILABLE)

        override fun lookupSFTPServer(): String {
            val termuxPrefix = File(UserResolver.TERMUX_PREFIX)
            for (name in arrayOf("libexec/sftp-server", "lib/openssh/sftp-server")) {
                val candidate = File(termuxPrefix, name)
                if (candidate.canExecute()) return candidate.absolutePath
            }
            throw IOException("sftp-server not found, install openssh in Termux")
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        neighborCallbacks.kill()
        super.onDestroy()
    }

    private companion object {
        const val UNAVAILABLE = "Not available in the single-core build (VpnService mode only)"
    }
}

internal fun parseEnvArray(sshEnv: Array<out String>?): MutableMap<String, String> {
    val env = mutableMapOf<String, String>()
    sshEnv?.forEach { entry ->
        val idx = entry.indexOf('=')
        if (idx > 0) env[entry.substring(0, idx)] = entry.substring(idx + 1)
    }
    return env
}

internal fun buildBasicEnvironment(
    sshEnv: Array<out String>?,
    shell: String,
    home: String,
    term: String?,
): Array<String> {
    val env = parseEnvArray(sshEnv)
    env["HOME"] = home
    env["PATH"] = "/system/bin:/system/xbin:/vendor/bin"
    env["SHELL"] = shell
    env["TMPDIR"] = "/data/local/tmp"
    if (!term.isNullOrEmpty()) {
        env["TERM"] = term
    }
    addAndroidSystemEnvironment(env)
    return env.map { (k, v) -> "$k=$v" }.toTypedArray()
}

internal fun addAndroidSystemEnvironment(env: MutableMap<String, String>) {
    val androidVars = arrayOf(
        "ANDROID_ASSETS", "ANDROID_DATA", "ANDROID_ROOT", "ANDROID_STORAGE",
        "EXTERNAL_STORAGE", "ASEC_MOUNTPOINT", "LOOP_MOUNTPOINT",
        "ANDROID_RUNTIME_ROOT", "ANDROID_ART_ROOT",
        "ANDROID_I18N_ROOT", "ANDROID_TZDATA_ROOT",
        "BOOTCLASSPATH", "DEX2OATBOOTCLASSPATH", "SYSTEMSERVERCLASSPATH",
    )
    for (name in androidVars) {
        val value = System.getenv(name)
        if (value != null) {
            env[name] = value
        }
    }
}
