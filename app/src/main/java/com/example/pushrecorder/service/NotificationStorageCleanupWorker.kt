package com.example.pushrecorder.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker.Result
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

class NotificationStorageCleanupWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val scheduler = EntryPointAccessors.fromApplication(
            applicationContext,
            NotificationStorageCleanupWorkerEntryPoint::class.java
        ).notificationStorageCleanupScheduler()

        return executeScheduledStorageCleanup(
            scheduler = scheduler,
            logFailure = { error ->
                Log.e(TAG, "Failed to clean notification storage from WorkManager", error)
            }
        )
    }

    companion object {
        internal const val UNIQUE_WORK_NAME = "notification-storage-cleanup"
        internal val PERIODIC_WORK_CONFIG = NotificationStorageCleanupWorkConfig(
            uniqueWorkName = UNIQUE_WORK_NAME,
            repeatIntervalMillis = NotificationStorageCleanupScheduler.DEFAULT_CLEANUP_INTERVAL_MILLIS,
            repeatIntervalUnit = TimeUnit.MILLISECONDS,
            existingWorkPolicy = ExistingPeriodicWorkPolicy.KEEP
        )

        fun enqueuePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<NotificationStorageCleanupWorker>(
                PERIODIC_WORK_CONFIG.repeatIntervalMillis,
                PERIODIC_WORK_CONFIG.repeatIntervalUnit
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_CONFIG.uniqueWorkName,
                PERIODIC_WORK_CONFIG.existingWorkPolicy,
                request
            )
        }

        private const val TAG = "StorageCleanupWorker"
    }
}

internal suspend fun executeScheduledStorageCleanup(
    scheduler: NotificationStorageCleanupScheduler,
    logFailure: (Throwable) -> Unit
): Result {
    var failure: Throwable? = null
    scheduler.runCleanup(
        force = true,
        onFailure = { error ->
            failure = error
            logFailure(error)
        }
    )

    return if (failure == null) Result.success() else Result.retry()
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface NotificationStorageCleanupWorkerEntryPoint {
    fun notificationStorageCleanupScheduler(): NotificationStorageCleanupScheduler
}

internal data class NotificationStorageCleanupWorkConfig(
    val uniqueWorkName: String,
    val repeatIntervalMillis: Long,
    val repeatIntervalUnit: TimeUnit,
    val existingWorkPolicy: ExistingPeriodicWorkPolicy
)
