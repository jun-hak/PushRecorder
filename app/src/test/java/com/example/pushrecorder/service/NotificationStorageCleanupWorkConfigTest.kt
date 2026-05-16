package com.example.pushrecorder.service

import androidx.work.ExistingPeriodicWorkPolicy
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationStorageCleanupWorkConfigTest {
    @Test
    fun periodicCleanupUsesUniqueKeepWorkWithSchedulerInterval() {
        val config = NotificationStorageCleanupWorker.PERIODIC_WORK_CONFIG

        assertEquals("notification-storage-cleanup", config.uniqueWorkName)
        assertEquals(
            NotificationStorageCleanupScheduler.DEFAULT_CLEANUP_INTERVAL_MILLIS,
            config.repeatIntervalMillis
        )
        assertEquals(TimeUnit.MILLISECONDS, config.repeatIntervalUnit)
        assertEquals(ExistingPeriodicWorkPolicy.KEEP, config.existingWorkPolicy)
    }

    @Test
    fun periodicCleanupIntervalIsAboveWorkManagerMinimum() {
        val config = NotificationStorageCleanupWorker.PERIODIC_WORK_CONFIG

        assertTrue(
            "Periodic cleanup must be configured as valid WorkManager periodic work.",
            config.repeatIntervalUnit.toMillis(config.repeatIntervalMillis) >=
                TimeUnit.MINUTES.toMillis(15)
        )
    }
}
