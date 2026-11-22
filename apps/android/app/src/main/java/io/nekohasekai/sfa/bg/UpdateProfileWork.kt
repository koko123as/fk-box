package io.nekohasekai.sfa.bg

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.database.CoreSubscriptions
import io.nekohasekai.sfa.database.ProfileContent
import io.nekohasekai.sfa.database.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

class UpdateProfileWork {
    companion object {
        private const val WORK_NAME = "UpdateProfile"
        private const val SCREEN_ON_WORK_NAME = "UpdateProfileOnScreenOn"
        private const val TAG = "UpdateProfileWork"

        suspend fun reconfigureUpdater() {
            runCatching {
                reconfigureUpdater0()
            }.onFailure {
                Log.e(TAG, "reconfigureUpdater", it)
            }
        }

        /** The schedule itself is client-core's policy (`subscription::schedule`); WorkManager runs it. */
        private suspend fun reconfigureUpdater0() {
            val schedule = CoreSubscriptions.schedule()
            if (schedule == null) {
                WorkManager.getInstance(Application.application).cancelUniqueWork(WORK_NAME)
                return
            }
            WorkManager.getInstance(Application.application).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequest.Builder(UpdateTask::class.java, schedule.intervalMinutes, TimeUnit.MINUTES)
                    .apply {
                        if (schedule.initialDelaySeconds > 0) setInitialDelay(schedule.initialDelaySeconds, TimeUnit.SECONDS)
                        setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
                    }
                    .build(),
            )
        }

        /**
         * Runs the automatic updates that are due (`subscription::due`), one at a time under
         * [ProfileContent.mutex]. Each is saved by client-core, which writes only the profile's
         * update time and subscription columns, so a reorder or edit made meanwhile is kept.
         */
        internal suspend fun updateProfiles(context: Context): Boolean {
            val due = CoreSubscriptions.due()
            if (due.isEmpty()) return true
            var success = true
            var intervalChanged = false
            for (id in due) {
                try {
                    ProfileContent.mutex.withLock {
                        // Re-checked under the lock: an edit may have turned it off or updated it.
                        if (!CoreSubscriptions.isDue(id)) return@withLock
                        // Applying a changed selected profile reloads the core and drops
                        // every connection; with the screen off that is a silent outage.
                        if (deferUntilScreenOn(id)) return@withLock
                        val service = BackgroundProfileService(context)
                        val result = try {
                            ProfileContent.replaceLocked(id, subscription = true, applySelected = service::apply)
                        } finally {
                            service.close()
                        }
                        if (result.intervalChanged) intervalChanged = true
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "update profile $id", e)
                    success = false
                }
            }
            // A provider's profile-update-interval changed a schedule.
            if (intervalChanged) reconfigureUpdater()
            return success
        }

        private fun deferUntilScreenOn(profileId: Long): Boolean {
            if (profileId != Settings.selectedProfile) return false
            if (BoxService.dashboardConfig.value == null) return false
            if (Application.powerManager.isInteractive) return false
            ScreenOnUpdate.arm()
            return true
        }
    }

    /** Runs the update once the screen turns on again, while the process is alive. */
    private object ScreenOnUpdate : BroadcastReceiver() {
        private var armed = false

        @Synchronized
        fun arm() {
            if (armed) return
            ContextCompat.registerReceiver(
                Application.application,
                this,
                IntentFilter(Intent.ACTION_SCREEN_ON),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            armed = true
        }

        @Synchronized
        override fun onReceive(context: Context, intent: Intent) {
            if (!armed) return
            runCatching { Application.application.unregisterReceiver(this) }
            armed = false
            WorkManager.getInstance(Application.application).enqueueUniqueWork(
                SCREEN_ON_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequest.Builder(UpdateTask::class.java).build(),
            )
        }
    }

    class UpdateTask(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result = if (updateProfiles(applicationContext)) Result.success() else Result.retry()
    }
}
