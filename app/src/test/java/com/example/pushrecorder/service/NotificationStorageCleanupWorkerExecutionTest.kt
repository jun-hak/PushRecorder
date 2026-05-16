package com.example.pushrecorder.service

import androidx.work.ListenableWorker.Result
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationStorageCleanupWorkerExecutionTest {
    @Test
    fun scheduledCleanupExecutionForcesExpectedCleanupPathAndReturnsSuccess() = runTest {
        val calls = mutableListOf<String>()
        val loggedFailures = mutableListOf<Throwable>()
        val scheduler = cleanupScheduler(
            deleteExpiredNotifications = {
                calls += "notifications"
                2
            },
            deleteExpiredPendingEvents = {
                calls += "journal"
                1
            }
        )

        val result = executeScheduledStorageCleanup(
            scheduler = scheduler,
            logFailure = { error -> loggedFailures += error }
        )

        assertEquals(listOf("notifications", "journal"), calls)
        assertTrue(loggedFailures.isEmpty())
        assertResultType(Result.success(), result)
    }

    @Test
    fun scheduledCleanupExecutionReturnsRetryWhenCleanupReportsFailure() = runTest {
        val loggedFailures = mutableListOf<String>()
        val scheduler = cleanupScheduler(
            deleteExpiredNotifications = {
                error("database busy")
            }
        )

        val result = executeScheduledStorageCleanup(
            scheduler = scheduler,
            logFailure = { error -> loggedFailures += error.message.orEmpty() }
        )

        assertEquals(listOf("database busy"), loggedFailures)
        assertResultType(Result.retry(), result)
    }

    private fun cleanupScheduler(
        deleteExpiredNotifications: suspend () -> Int = { 0 },
        deleteExpiredPendingEvents: suspend () -> Int = { 0 }
    ): NotificationStorageCleanupScheduler {
        return NotificationStorageCleanupScheduler(
            currentTimeMillis = { 1_000L },
            cleanupIntervalMillis = 5_000L,
            deleteExpiredNotifications = deleteExpiredNotifications,
            deleteExpiredPendingEvents = deleteExpiredPendingEvents
        )
    }

    private fun assertResultType(
        expected: Result,
        actual: Result
    ) {
        assertEquals(expected.javaClass, actual.javaClass)
    }
}
