package io.nekohasekai.sfa.vendor

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.bg.IShizukuService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku

object ShizukuPrivilegedServiceClient {

    private const val BIND_TIMEOUT_MS = 20_000L

    private val serviceMutex = Mutex()
    @Volatile private var service: IShizukuService? = null
    @Volatile private var connection: ServiceConnection? = null

    // The bind in progress, shared by concurrent callers, and its connection.
    private var pending: CompletableDeferred<IShizukuService>? = null
    private var pendingConnection: ServiceConnection? = null

    private val args = Shizuku.UserServiceArgs(
        ComponentName(Application.application, ShizukuPrivilegedService::class.java),
    )
        .tag("sfa-privileged")
        .processNameSuffix("privileged")
        .version(BuildConfig.VERSION_CODE)
        .debuggable(BuildConfig.DEBUG)

    /**
     * The bound privileged service. The lock only guards which bind is in
     * progress; callers wait for it outside the lock and give up after
     * [BIND_TIMEOUT_MS], so a Shizuku that never answers cannot block every
     * later caller.
     */
    suspend fun getService(): IShizukuService {
        val deferred = serviceMutex.withLock {
            service?.let { return it }
            pending ?: CompletableDeferred<IShizukuService>().also {
                pending = it
                withContext(Dispatchers.Main) { startBind(it) }
            }
        }
        return try {
            withTimeout(BIND_TIMEOUT_MS) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            abandon(deferred)
            throw IllegalStateException("Shizuku service did not connect within ${BIND_TIMEOUT_MS / 1000} s")
        } catch (e: Exception) {
            if (e !is CancellationException) abandon(deferred)
            throw e
        }
    }

    private fun startBind(deferred: CompletableDeferred<IShizukuService>) {
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val svc = if (binder != null && binder.pingBinder()) {
                    IShizukuService.Stub.asInterface(binder)
                } else {
                    null
                }
                if (svc == null) {
                    // complete* are no-ops once the bind has an outcome, so a
                    // reconnect after success cannot resume a caller twice.
                    deferred.completeExceptionally(IllegalStateException("Invalid Shizuku service binder"))
                    return
                }
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
        try {
            Shizuku.bindUserService(args, conn)
        } catch (e: Throwable) {
            deferred.completeExceptionally(e)
        }
        deferred.invokeOnCompletion { pendingCleared(deferred) }
    }

    private fun pendingCleared(deferred: CompletableDeferred<IShizukuService>) {
        // Settled either way: a later caller uses the bound service or, after a
        // failure or disconnect, starts a new bind.
        if (pending === deferred) {
            pending = null
            pendingConnection = null
        }
    }

    private suspend fun abandon(deferred: CompletableDeferred<IShizukuService>) {
        serviceMutex.withLock {
            if (pending !== deferred) return
            pending = null
            deferred.cancel()
            val conn = pendingConnection
            pendingConnection = null
            if (conn != null && service == null) {
                withContext(Dispatchers.Main) {
                    try {
                        Shizuku.unbindUserService(args, conn, false)
                    } catch (_: Throwable) {
                        // Ignore
                    }
                }
            }
        }
    }

    fun reset() {
        val conn = connection ?: return
        service = null
        connection = null
        pending = null
        pendingConnection = null
        try {
            Shizuku.unbindUserService(args, conn, false)
        } catch (_: Throwable) {
            // Ignore
        }
    }
}
