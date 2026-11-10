package io.github.kopolot.hcsync.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.kopolot.hcsync.App

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val trigger = inputData.getString(KEY_TRIGGER)?.let { runCatching { SyncTrigger.valueOf(it) }.getOrNull() }
            ?: SyncTrigger.PERIODIC
        val repo = (applicationContext as App).container.syncRepository
        return when (val outcome = repo.sync(trigger)) {
            is SyncOutcome.Success -> Result.success(workDataOf(KEY_MESSAGE to outcome.message))
            is SyncOutcome.Failure -> Result.failure(workDataOf(KEY_MESSAGE to outcome.message))
            is SyncOutcome.Retry ->
                if (runAttemptCount < MAX_ATTEMPTS) Result.retry()
                else Result.failure(workDataOf(KEY_MESSAGE to outcome.message))
        }
    }

    companion object {
        const val KEY_TRIGGER = "trigger"
        const val KEY_MESSAGE = "message"
        const val MAX_ATTEMPTS = 5
    }
}
