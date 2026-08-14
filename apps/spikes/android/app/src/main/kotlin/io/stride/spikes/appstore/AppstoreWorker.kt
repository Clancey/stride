package io.stride.spikes.appstore

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * The heartbeat that makes the app store a *background* service rather than a button.
 *
 * `WorkManager` rather than `AlarmManager` or a self-scheduling timer because it is the only
 * mechanism that survives Doze, app-standby buckets, and process death on the Android 8/9 the
 * console runs, without asking for a battery-optimisation exemption we would rather not need.
 *
 * The worker does almost nothing itself: it starts [StrideAppstoreService] and returns. All the
 * policy, safety gating, and state live in one place there, and a worker that finished early while
 * an install was still in flight would be a second, competing lifecycle.
 */
class AppstoreWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        StrideAppstoreService.check(applicationContext)
        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "stride-appstore-check"

        /** Six hours: often enough that a fix lands the same day, rare enough to be invisible. */
        private const val INTERVAL_HOURS = 6L

        /**
         * Registers the periodic check. Safe to call repeatedly — [ExistingPeriodicWorkPolicy.KEEP]
         * means boot, app launch, and a manual check do not each reset the interval, which would
         * otherwise mean a frequently-rebooted console never actually reaches a scheduled run.
         */
        fun ensureScheduled(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<AppstoreWorker>(
                INTERVAL_HOURS, TimeUnit.HOURS,
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}
