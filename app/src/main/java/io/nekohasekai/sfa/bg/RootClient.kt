package io.nekohasekai.sfa.bg

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageInfo
import android.os.Build
import android.os.IBinder
import android.os.RemoteException
import androidx.core.content.ContextCompat
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlin.coroutines.resume

object RootClient {
    init {
        Shell.enableVerboseLogging = BuildConfig.DEBUG
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                .setTimeout(10),
        )
    }

    private val _rootAvailable = MutableStateFlow<Boolean?>(null)
    val rootAvailable: StateFlow<Boolean?> = _rootAvailable

    @Volatile private var service: IRootService? = null
    @Volatile private var connection: ServiceConnection? = null
    private val connectionMutex = Mutex()
    private const val BIND_TIMEOUT_MS = 20_000L

    suspend fun checkRootAvailable(): Boolean {
        Shell.getCachedShell()?.close()
        return suspendCancellableCoroutine { continuation ->
            Shell.getShell { shell ->
                val available = shell.isRoot
                _rootAvailable.value = available
                continuation.resume(available)
            }
        }
    }

    // The bind in progress, shared by concurrent callers.
    private var pending: CompletableDeferred<IRootService>? = null

    /**
     * Binds the root service, or returns the bound one. The lock only guards
     * which bind is in progress; callers wait for it outside the lock and give
     * up after [BIND_TIMEOUT_MS], so a root manager that never answers (a
     * pending grant prompt, a hung su) cannot block every later caller.
     */
    suspend fun bindService(): IRootService {
        val deferred = connectionMutex.withLock {
            service?.let { return it }
            pending?.let { return@withLock it }
            if (Shell.isAppGrantedRoot() == false) {
                throw IOException("permission denied")
            }
            CompletableDeferred<IRootService>().also { deferred ->
                pending = deferred
                // Settled either way: later callers use the bound service or bind again.
                deferred.invokeOnCompletion {
                    if (pending === deferred) {
                        pending = null
                        pendingConnection = null
                    }
                }
                startBind(deferred)
            }
        }
        return try {
            withTimeout(BIND_TIMEOUT_MS) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            abandon(deferred)
            throw IOException("root service did not connect within ${BIND_TIMEOUT_MS / 1000} s")
        } catch (e: Exception) {
            if (e !is kotlinx.coroutines.CancellationException) abandon(deferred)
            throw e
        }
    }

    private suspend fun abandon(deferred: CompletableDeferred<IRootService>) {
        connectionMutex.withLock {
            val conn = pendingConnection
            if (pending === deferred) {
                pending = null
                pendingConnection = null
                deferred.cancel()
                if (conn != null && service == null) {
                    withContext(Dispatchers.Main) { runCatching { RootService.unbind(conn) } }
                }
            }
        }
    }

    private var pendingConnection: ServiceConnection? = null

    private fun startBind(deferred: CompletableDeferred<IRootService>) {
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val svc = IRootService.Stub.asInterface(binder)
                service = svc
                connection = this
                deferred.complete(svc)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
                connection = null
            }
        }
        pendingConnection = conn
        ContextCompat.getMainExecutor(Application.application).execute {
            val task = try {
                RootService.bindOrTask(
                    Intent(Application.application, RootServer::class.java),
                    ContextCompat.getMainExecutor(Application.application),
                    conn,
                )
            } catch (e: Exception) {
                deferred.completeExceptionally(e)
                return@execute
            }
            // null: already connected, onServiceConnected will fire.
            if (task != null) {
                Shell.EXECUTOR.execute {
                    try {
                        val shell = Shell.getShell()
                        if (shell.isRoot) {
                            shell.execTask(task)
                        } else {
                            deferred.completeExceptionally(IOException("permission denied"))
                        }
                    } catch (e: Exception) {
                        deferred.completeExceptionally(e)
                    }
                }
            }
        }
    }

    suspend fun getInstalledPackages(flags: Int): List<PackageInfo> {
        val userId = android.os.Process.myUserHandle().hashCode()
        val svc = bindService()
        return try {
            val slice = svc.getInstalledPackages(flags, userId)

            @Suppress("UNCHECKED_CAST")
            val list = slice.list as List<PackageInfo>
            list
        } catch (e: RemoteException) {
            throw e.rethrowAsRuntime()
        }
    }

    suspend fun registerNeighborTableCallback(callback: INeighborTableCallback) {
        val svc = bindService()
        try {
            svc.registerNeighborTableCallback(callback)
        } catch (e: RemoteException) {
            throw e.rethrowAsRuntime()
        }
    }

    suspend fun lookupSFTPServer(): String {
        val svc = bindService()
        try {
            return svc.lookupSFTPServer()
        } catch (e: RemoteException) {
            throw e.rethrowAsRuntime()
        }
    }

    suspend fun openShellSession(
        user: String,
        command: String?,
        env: Array<String>,
        term: String?,
        rows: Int,
        cols: Int,
    ): IRootShellSession {
        val svc = bindService()
        try {
            return svc.openShellSession(user, command, env, term, rows, cols)
        } catch (e: RemoteException) {
            throw e.rethrowAsRuntime()
        }
    }

    suspend fun openBridge(
        bridgeName: String,
        mtu: Int,
        inet4Port: String,
        inet6Port: String,
        ruleIndex: Int,
        routeTable: Int,
    ): IBridgeSession {
        val svc = bindService()
        try {
            return svc.openBridge(bridgeName, mtu, inet4Port, inet6Port, ruleIndex, routeTable)
        } catch (e: RemoteException) {
            throw e.rethrowAsRuntime()
        }
    }

    suspend fun startAutoRedirect(options: ByteArray, handler: IAutoRedirectHandler): IAutoRedirectSession {
        val svc = bindService()
        try {
            return svc.startAutoRedirect(options, handler)
        } catch (e: RemoteException) {
            throw e.rethrowAsRuntime()
        }
    }

    suspend fun unregisterNeighborTableCallback(callback: INeighborTableCallback) {
        try {
            service?.unregisterNeighborTableCallback(callback)
        } catch (e: RemoteException) {
            throw e.rethrowAsRuntime()
        }
    }

    private fun RemoteException.rethrowAsRuntime(): RuntimeException = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        rethrowFromSystemServer()
    } else {
        RuntimeException(this)
    }
}
