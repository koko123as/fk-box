package io.nekohasekai.sfa.bg

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.database.Settings
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Scheduled geo updates (FlClash's geo-auto-update / geo-update-interval). Each
 * run updates the files whose last successful check is older than the
 * interval; a failed download keeps the installed file and retries later.
 */
object UpdateGeoWork {
    private const val WORK_NAME = "UpdateGeoResources"
    private const val TAG = "UpdateGeoWork"

    fun reconfigure() {
        runCatching {
            val manager = WorkManager.getInstance(Application.application)
            if (!Settings.geoAutoUpdate) {
                manager.cancelUniqueWork(WORK_NAME)
                return
            }
            val hours = Settings.geoUpdateInterval.coerceAtLeast(1).toLong()
            manager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequest.Builder(Task::class.java, hours, TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                    .build(),
            )
        }.onFailure { Log.e(TAG, "reconfigure", it) }
    }

    class Task(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result {
            if (!Settings.geoAutoUpdate) return Result.success()
            Application.application.setupLibbox()
            GeoResources.ensureBundled(applicationContext)
            var failed = false
            for (name in GeoResources.due(Settings.geoUpdateInterval)) {
                try {
                    GeoResources.update(name)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "update $name: ${e.message}")
                    failed = true
                }
            }
            return if (failed) Result.retry() else Result.success()
        }
    }
}
