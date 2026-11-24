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
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Scheduled geo updates (FlClash's geo-auto-update / geo-update-interval).
 * Whether they run, how often and which files are due is client-core's
 * policy (geo::schedule, geo::due via [GeoPolicy]); this is the WorkManager
 * entry. Each run updates the installed files whose last successful check is
 * older than the interval (a file no core has needed yet is not fetched); a
 * failed download keeps the installed file and retries later.
 */
object UpdateGeoWork {
    private const val WORK_NAME = "UpdateGeoResources"
    private const val TAG = "UpdateGeoWork"

    fun reconfigure() {
        runCatching {
            val manager = WorkManager.getInstance(Application.application)
            val schedule = GeoPolicy.schedule()
            if (!schedule.enabled) {
                manager.cancelUniqueWork(WORK_NAME)
                return
            }
            val hours = schedule.intervalHours.coerceAtLeast(1).toLong()
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
            if (!GeoPolicy.schedule().enabled) return Result.success()
            var failed = false
            for (name in GeoResources.due()) {
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
