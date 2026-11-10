package io.github.kopolot.hcsync.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

object SyncScheduler {
    const val PERIODIC_WORK = "daily_sync"
    const val MANUAL_WORK = "sync_now"

    /** Preferred daily run time, so yesterday is complete before the bot reads the sheet in the morning. */
    private val PREFERRED_TIME: LocalTime = LocalTime.of(5, 30)

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(24, TimeUnit.HOURS)
            .setConstraints(network)
            .setInitialDelay(delayUntil(PREFERRED_TIME))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .setInputData(workDataOf(SyncWorker.KEY_TRIGGER to SyncTrigger.PERIODIC.name))
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun syncNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .setInputData(workDataOf(SyncWorker.KEY_TRIGGER to SyncTrigger.MANUAL.name))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(MANUAL_WORK, ExistingWorkPolicy.KEEP, request)
    }

    private fun delayUntil(time: LocalTime): Duration {
        val now = ZonedDateTime.now()
        var next = now.with(time)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return Duration.between(now, next)
    }
}
