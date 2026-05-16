package com.example.pushrecorder.service

import com.example.pushrecorder.data.FakeNotificationDao
import com.example.pushrecorder.data.NotificationEventJournalRetentionPolicy
import com.example.pushrecorder.data.NotificationRepository
import com.example.pushrecorder.data.NotificationRetentionPolicy
import com.example.pushrecorder.data.NotificationStatus
import com.example.pushrecorder.data.RemovalReason
import com.example.pushrecorder.data.notificationCapture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationStorageCleanupSchedulerTest {
    @Test
    fun requestCleanup_runsNotificationAndJournalCleanup() = runTest {
        val calls = mutableListOf<String>()
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

        assertTrue(scheduler.requestCleanup(backgroundScope))
        runCurrent()

        assertEquals(listOf("notifications", "journal"), calls)
    }

    @Test
    fun requestCleanup_throttlesIngestionAdjacentRequestsUntilIntervalPasses() = runTest {
        var now = 1_000L
        var cleanupCount = 0
        val scheduler = cleanupScheduler(
            currentTimeMillis = { now },
            cleanupIntervalMillis = 5_000L,
            deleteExpiredNotifications = {
                cleanupCount += 1
                0
            }
        )

        assertTrue(scheduler.requestCleanup(backgroundScope))
        runCurrent()

        now = 5_999L
        assertFalse(scheduler.requestCleanup(backgroundScope))
        runCurrent()

        now = 6_000L
        assertTrue(scheduler.requestCleanup(backgroundScope))
        runCurrent()

        assertEquals(2, cleanupCount)
    }

    @Test
    fun requestCleanup_coalescesWhileCleanupIsRunning() = runTest {
        val releaseCleanup = CompletableDeferred<Unit>()
        var cleanupCount = 0
        val scheduler = cleanupScheduler(
            deleteExpiredNotifications = {
                cleanupCount += 1
                releaseCleanup.await()
                0
            }
        )

        assertTrue(scheduler.requestCleanup(backgroundScope, force = true))
        runCurrent()

        assertFalse(scheduler.requestCleanup(backgroundScope, force = true))

        releaseCleanup.complete(Unit)
        runCurrent()

        assertEquals(1, cleanupCount)
    }

    @Test
    fun requestCleanup_reportsFailureAndAllowsFutureCleanupAfterInterval() = runTest {
        var now = 1_000L
        var shouldFail = true
        var cleanupCount = 0
        val failures = mutableListOf<String>()
        val scheduler = cleanupScheduler(
            currentTimeMillis = { now },
            cleanupIntervalMillis = 5_000L,
            deleteExpiredNotifications = {
                cleanupCount += 1
                if (shouldFail) error("database busy")
                0
            }
        )

        assertTrue(
            scheduler.requestCleanup(
                scope = backgroundScope,
                onFailure = { error -> failures += error.message.orEmpty() }
            )
        )
        runCurrent()

        shouldFail = false
        now = 6_000L
        assertTrue(scheduler.requestCleanup(backgroundScope))
        runCurrent()

        assertEquals(listOf("database busy"), failures)
        assertEquals(2, cleanupCount)
    }

    @Test
    fun runCleanup_continuesJournalCleanupWhenNotificationCleanupFails() = runTest {
        val calls = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val scheduler = cleanupScheduler(
            deleteExpiredNotifications = {
                calls += "notifications"
                error("notification cleanup failed")
            },
            deleteExpiredPendingEvents = {
                calls += "journal"
                1
            }
        )

        assertTrue(
            scheduler.runCleanup(
                onFailure = { error -> failures += error.message.orEmpty() }
            )
        )

        assertEquals(listOf("notifications", "journal"), calls)
        assertEquals(listOf("notification cleanup failed"), failures)
    }

    @Test
    fun runCleanup_reportsEachCleanupFailureAndAllowsFutureCleanup() = runTest {
        var now = 1_000L
        var notificationCleanupCount = 0
        var journalCleanupCount = 0
        val failures = mutableListOf<String>()
        val scheduler = cleanupScheduler(
            currentTimeMillis = { now },
            cleanupIntervalMillis = 5_000L,
            deleteExpiredNotifications = {
                notificationCleanupCount += 1
                error("notification cleanup failed")
            },
            deleteExpiredPendingEvents = {
                journalCleanupCount += 1
                error("journal cleanup failed")
            }
        )

        assertTrue(
            scheduler.runCleanup(
                onFailure = { error -> failures += error.message.orEmpty() }
            )
        )

        now = 6_000L
        assertTrue(scheduler.runCleanup())

        assertEquals(2, notificationCleanupCount)
        assertEquals(2, journalCleanupCount)
        assertEquals(
            listOf(
                "notification cleanup failed",
                "journal cleanup failed"
            ),
            failures
        )
    }

    @Test
    fun runCleanup_awaitsNotificationAndJournalCleanupForWorkerEntryPoint() = runTest {
        val calls = mutableListOf<String>()
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

        assertTrue(scheduler.runCleanup())

        assertEquals(listOf("notifications", "journal"), calls)
    }

    @Test
    fun runCleanup_forceBypassesThrottleForScheduledCleanup() = runTest {
        var now = 1_000L
        var cleanupCount = 0
        val scheduler = cleanupScheduler(
            currentTimeMillis = { now },
            cleanupIntervalMillis = 5_000L,
            deleteExpiredNotifications = {
                cleanupCount += 1
                0
            }
        )

        assertTrue(scheduler.runCleanup())

        now = 1_001L
        assertFalse(scheduler.runCleanup())
        assertTrue(scheduler.runCleanup(force = true))

        assertEquals(2, cleanupCount)
    }

    @Test
    fun runCleanup_coalescesWithLaunchedCleanup() = runTest {
        val releaseCleanup = CompletableDeferred<Unit>()
        val scheduler = cleanupScheduler(
            deleteExpiredNotifications = {
                releaseCleanup.await()
                0
            }
        )

        assertTrue(scheduler.requestCleanup(backgroundScope, force = true))
        runCurrent()

        assertFalse(scheduler.runCleanup(force = true))

        releaseCleanup.complete(Unit)
        runCurrent()
    }

    @Test
    fun requestCleanup_cleansStaleStorageAcrossContinuousRuntimeWithoutSchedulerRecreation() = runTest {
        var now = 10_000L
        val notificationDao = FakeNotificationDao()
        val notificationRepository = NotificationRepository(
            notificationDao = notificationDao,
            currentTimeMillis = { now },
            retentionPolicy = NotificationRetentionPolicy(
                maxAgeMillis = 5_000L,
                maxRows = null,
                maxEvents = null
            )
        )
        val journalDao = FakeNotificationEventJournalDao()
        val journalRepository = NotificationEventJournalRepository(
            journalDao = journalDao,
            currentTimeMillis = { now },
            retentionPolicy = NotificationEventJournalRetentionPolicy(
                maxPendingAgeMillis = 5_000L,
                maxPendingRows = 10,
                replayBatchSize = 10,
                retryBaseDelayMillis = 1_000L,
                retryMaxDelayMillis = 60_000L
            )
        )
        val scheduler = NotificationStorageCleanupScheduler(
            currentTimeMillis = { now },
            cleanupIntervalMillis = 1_000L,
            deleteExpiredNotifications = notificationRepository::deleteExpiredNotifications,
            deleteExpiredPendingEvents = journalRepository::deleteExpiredPendingEvents
        )

        notificationDao.insertClosedLifecycle(
            notificationKey = "expired-before-startup-cleanup",
            observedAt = 4_000L
        )
        now = 4_000L
        journalRepository.journalPosted(
            notificationKey = "expired-journal-before-startup-cleanup",
            observedAt = 4_000L
        )
        now = 10_000L

        assertTrue(scheduler.requestCleanup(backgroundScope, force = true))
        runCurrent()

        assertEquals(emptyList<String>(), notificationDao.notifications.map { it.notificationKey })
        assertEquals(emptyList<String>(), journalDao.events.map { it.notificationKey })

        notificationDao.insertClosedLifecycle(
            notificationKey = "expired-during-same-runtime",
            observedAt = 4_500L
        )
        now = 4_500L
        journalRepository.journalPosted(
            notificationKey = "expired-journal-during-same-runtime",
            observedAt = 4_500L
        )

        now = 10_999L
        assertFalse(scheduler.requestCleanup(backgroundScope))
        runCurrent()
        assertEquals(
            listOf("expired-during-same-runtime", "expired-during-same-runtime"),
            notificationDao.notifications.map { it.notificationKey }
        )
        assertEquals(
            listOf("expired-journal-during-same-runtime"),
            journalDao.events.map { it.notificationKey }
        )

        now = 11_000L
        assertTrue(scheduler.requestCleanup(backgroundScope))
        runCurrent()

        assertEquals(emptyList<String>(), notificationDao.notifications.map { it.notificationKey })
        assertEquals(emptyList<String>(), journalDao.events.map { it.notificationKey })
    }

    private fun cleanupScheduler(
        currentTimeMillis: () -> Long = { 1_000L },
        cleanupIntervalMillis: Long = 5_000L,
        deleteExpiredNotifications: suspend () -> Int = { 0 },
        deleteExpiredPendingEvents: suspend () -> Int = { 0 }
    ): NotificationStorageCleanupScheduler {
        return NotificationStorageCleanupScheduler(
            currentTimeMillis = currentTimeMillis,
            cleanupIntervalMillis = cleanupIntervalMillis,
            deleteExpiredNotifications = deleteExpiredNotifications,
            deleteExpiredPendingEvents = deleteExpiredPendingEvents
        )
    }

    private suspend fun FakeNotificationDao.insertClosedLifecycle(
        notificationKey: String,
        observedAt: Long
    ) {
        insert(
            notificationCapture(
                notificationKey = notificationKey,
                observedAt = observedAt,
                sourcePostTime = observedAt
            ).toEntity(NotificationStatus.POSTED)
        )
        insert(
            notificationCapture(
                notificationKey = notificationKey,
                observedAt = observedAt + 1L,
                sourcePostTime = observedAt
            ).toEntity(
                status = NotificationStatus.REMOVED,
                removalReason = RemovalReason.UNKNOWN,
                removedAt = observedAt + 1L
            )
        )
    }

    private suspend fun NotificationEventJournalRepository.journalPosted(
        notificationKey: String,
        observedAt: Long
    ) {
        journalIfRequired(
            NotificationProcessingCommand.Posted(
                notificationCapture(
                    notificationKey = notificationKey,
                    observedAt = observedAt,
                    sourcePostTime = observedAt
                )
            )
        )
    }
}
