package com.apex.agent.background

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import java.util.concurrent.TimeUnit

class BackgroundTaskManager(context: Context) {
    private val workManager = WorkManager.getInstance(context)

    fun schedulePeriodicTask(
        tag: String,
        repeatInterval: Long = 15,
        timeUnit: TimeUnit = TimeUnit.MINUTES,
        constraints: Constraints = Constraints.Builder().build()
    ) {
        val periodicWorkRequest = PeriodicWorkRequestBuilder<PeriodicBackgroundWorker>(
            repeatInterval, timeUnit
        )
            .setConstraints(constraints)
            .addTag(tag)
            .build()

        workManager.enqueueUniquePeriodicWork(
            tag,
            ExistingPeriodicWorkPolicy.KEEP,
            periodicWorkRequest
        )
    }

    fun scheduleOneTimeTask(
        tag: String,
        initialDelay: Long = 0,
        timeUnit: TimeUnit = TimeUnit.SECONDS,
        constraints: Constraints = Constraints.Builder().build(),
        inputData: Data = Data.EMPTY
    ) {
        val oneTimeWorkRequest = OneTimeWorkRequestBuilder<OneTimeBackgroundWorker>()
            .setConstraints(constraints)
            .setInitialDelay(initialDelay, timeUnit)
            .setInputData(inputData)
            .addTag(tag)
            .build()

        workManager.enqueueUniqueWork(
            tag,
            ExistingWorkPolicy.KEEP,
            oneTimeWorkRequest
        )
    }

    fun cancelTask(tag: String) {
        workManager.cancelAllWorkByTag(tag)
    }

    fun cancelAllTasks() {
        workManager.cancelAllWork()
    }

    suspend fun getTaskStatus(tag: String): WorkInfo? {
        return workManager.getWorkInfosByTag(tag).await().firstOrNull()
    }
}
