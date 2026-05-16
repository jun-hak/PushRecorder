package com.example.pushrecorder.service

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationStorageCleanupWorkerSourceTest {
    @Test
    fun workerRegistersUniquePeriodicCleanupAndUsesSchedulerEntryPoint() {
        val source = readWorkerSource()

        assertTrue(
            "Worker should use WorkManager's CoroutineWorker entry point.",
            source.contains("class NotificationStorageCleanupWorker(") &&
                source.contains(": CoroutineWorker(appContext, workerParams)")
        )
        assertTrue(
            "Worker should retrieve the existing cleanup scheduler through a Hilt entry point.",
            source.contains("EntryPointAccessors.fromApplication(") &&
                source.contains("NotificationStorageCleanupWorkerEntryPoint::class.java") &&
                source.contains("fun notificationStorageCleanupScheduler(): NotificationStorageCleanupScheduler")
        )
        assertTrue(
            "Worker should run cleanup synchronously for WorkManager success/retry semantics.",
            source.contains("override suspend fun doWork(): Result") &&
                source.contains("executeScheduledStorageCleanup(") &&
                source.contains("scheduler = scheduler") &&
                source.contains("scheduler.runCleanup(") &&
                source.contains("force = true") &&
                source.contains("Result.retry()")
        )
        assertTrue(
            "Worker should register bounded periodic cleanup as unique work.",
            source.contains("UNIQUE_WORK_NAME = \"notification-storage-cleanup\"") &&
                source.contains("PeriodicWorkRequestBuilder<NotificationStorageCleanupWorker>(") &&
                source.contains("NotificationStorageCleanupScheduler.DEFAULT_CLEANUP_INTERVAL_MILLIS") &&
                source.contains("enqueueUniquePeriodicWork(") &&
                source.contains("ExistingPeriodicWorkPolicy.KEEP")
        )
    }

    private fun readWorkerSource(): String {
        val candidates = listOf(
            Path.of("src/main/java/com/example/pushrecorder/service/NotificationStorageCleanupWorker.kt"),
            Path.of("app/src/main/java/com/example/pushrecorder/service/NotificationStorageCleanupWorker.kt")
        )
        val sourcePath = candidates.firstOrNull(Files::isReadable)
            ?: error("NotificationStorageCleanupWorker.kt was not found from known Gradle test working directories")

        return String(Files.readAllBytes(sourcePath))
    }
}
