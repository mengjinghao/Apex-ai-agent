package com.apex.agent.background

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.delay

class OneTimeBackgroundWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val taskType = inputData.getString(KEY_TASK_TYPE)
            when (taskType) {
                TASK_TYPE_CLEANUP -> executeCleanup()
                TASK_TYPE_SYNC -> executeSync()
                else -> Result.failure()
            }
        } catch (e: Exception) {
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    private suspend fun executeCleanup(): Result {
        delay(100)
        return Result.success()
    }

    private suspend fun executeSync(): Result {
        delay(100)
        return Result.success()
    }

    companion object {
        const val KEY_TASK_TYPE = "task_type"
        const val TASK_TYPE_CLEANUP = "cleanup"
        const val TASK_TYPE_SYNC = "sync"
    }
}
