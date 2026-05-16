package com.example.pushrecorder.data

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationRepositoryTest {
    private val notificationDao = FakeNotificationDao()
    private val repository = NotificationRepository(notificationDao)

    @Test
    fun recordPosted_appendsEveryPostForSameNotificationKey() = runTest {
        repository.recordPosted(
            notificationCapture(
                notificationKey = "same-key",
                title = "First",
                observedAt = 1_000L
            )
        )
        repository.recordPosted(
            notificationCapture(
                notificationKey = "same-key",
                title = "Second",
                observedAt = 2_000L
            )
        )

        assertEquals(2, notificationDao.notifications.size)
        assertEquals(listOf("First", "Second"), notificationDao.notifications.map { it.title })
        assertTrue(notificationDao.notifications.all { it.status == NotificationStatus.POSTED })
    }

    @Test
    fun recordPosted_withSameEventJournalIdIsIdempotentForReplayAfterAckCrash() = runTest {
        repository.recordPosted(
            capture = notificationCapture(
                notificationKey = "journal-key",
                title = "first processing"
            ),
            eventJournalId = 7L
        )
        repository.recordPosted(
            capture = notificationCapture(
                notificationKey = "journal-key",
                title = "replayed processing"
            ),
            eventJournalId = 7L
        )

        assertEquals(1, notificationDao.notifications.size)
        assertEquals("first processing", notificationDao.notifications.single().title)
    }

    @Test
    fun recordPosted_truncatesOversizedTitleAndTextBeforePersistence() = runTest {
        val oversizedTitle = "t".repeat(NotificationStorageLimits.MAX_STORED_TITLE_LENGTH + 50)
        val oversizedText = "x".repeat(NotificationStorageLimits.MAX_STORED_TEXT_LENGTH + 50)

        notificationDao.beforeInsert = { notification ->
            assertEquals(NotificationStorageLimits.MAX_STORED_TITLE_LENGTH, notification.title.length)
            assertEquals(NotificationStorageLimits.MAX_STORED_TEXT_LENGTH, notification.text.length)
            assertEquals(oversizedTitle.take(NotificationStorageLimits.MAX_STORED_TITLE_LENGTH), notification.title)
            assertEquals(oversizedText.take(NotificationStorageLimits.MAX_STORED_TEXT_LENGTH), notification.text)
        }

        repository.recordPosted(
            notificationCapture(
                notificationKey = "oversized-posted",
                title = oversizedTitle,
                text = oversizedText
            )
        )

        val notification = notificationDao.notifications.single()
        assertEquals(oversizedTitle.take(NotificationStorageLimits.MAX_STORED_TITLE_LENGTH), notification.title)
        assertEquals(oversizedText.take(NotificationStorageLimits.MAX_STORED_TEXT_LENGTH), notification.text)
    }

    @Test
    fun recordRemoved_appendsTerminalEventWithoutUpdatingPostedRow() = runTest {
        repository.recordPosted(
            notificationCapture(
                notificationKey = "remove-key",
                title = "Posted",
                observedAt = 1_000L,
                appLabel = "Original App"
            )
        )

        repository.recordRemoved(
            capture = notificationCapture(
                notificationKey = "remove-key",
                title = "Removed",
                observedAt = 2_000L,
                appLabel = "Original App"
            ),
            status = NotificationStatus.REMOVED,
            removalReason = RemovalReason.USER_DISMISSED,
            timeToRemoval = 1_000L,
            removedAt = 2_000L
        )

        assertEquals(2, notificationDao.notifications.size)
        assertEquals(NotificationStatus.POSTED, notificationDao.notifications[0].status)
        assertEquals(NotificationStatus.REMOVED, notificationDao.notifications[1].status)
        assertEquals("Posted", notificationDao.notifications[0].title)
        assertEquals("Removed", notificationDao.notifications[1].title)
    }

    @Test
    fun recordRemoved_withSameEventJournalIdIsIdempotentForReplayAfterAckCrash() = runTest {
        val capture = notificationCapture(
            notificationKey = "remove-journal-key",
            title = "removed"
        )

        repository.recordRemoved(
            capture = capture,
            status = NotificationStatus.REMOVED,
            removalReason = RemovalReason.USER_DISMISSED,
            timeToRemoval = 1_000L,
            removedAt = 2_000L,
            eventJournalId = 99L
        )
        repository.recordRemoved(
            capture = capture.copy(title = "removed replayed"),
            status = NotificationStatus.REMOVED,
            removalReason = RemovalReason.USER_DISMISSED,
            timeToRemoval = 1_000L,
            removedAt = 2_000L,
            eventJournalId = 99L
        )

        assertEquals(1, notificationDao.notifications.size)
        assertEquals("removed", notificationDao.notifications.single().title)
    }

    @Test
    fun recordRemoved_truncatesOversizedTitleAndTextBeforePersistence() = runTest {
        val oversizedTitle = "r".repeat(NotificationStorageLimits.MAX_STORED_TITLE_LENGTH + 50)
        val oversizedText = "body".repeat(NotificationStorageLimits.MAX_STORED_TEXT_LENGTH)

        notificationDao.beforeInsert = { notification ->
            assertEquals(NotificationStorageLimits.MAX_STORED_TITLE_LENGTH, notification.title.length)
            assertEquals(NotificationStorageLimits.MAX_STORED_TEXT_LENGTH, notification.text.length)
            assertEquals(oversizedTitle.take(NotificationStorageLimits.MAX_STORED_TITLE_LENGTH), notification.title)
            assertEquals(oversizedText.take(NotificationStorageLimits.MAX_STORED_TEXT_LENGTH), notification.text)
        }

        repository.recordRemoved(
            capture = notificationCapture(
                notificationKey = "oversized-removed",
                title = oversizedTitle,
                text = oversizedText
            ),
            status = NotificationStatus.REMOVED,
            removalReason = RemovalReason.USER_DISMISSED,
            timeToRemoval = 1_000L,
            removedAt = 2_000L
        )

        val notification = notificationDao.notifications.single()
        assertEquals(oversizedTitle.take(NotificationStorageLimits.MAX_STORED_TITLE_LENGTH), notification.title)
        assertEquals(oversizedText.take(NotificationStorageLimits.MAX_STORED_TEXT_LENGTH), notification.text)
    }

    @Test
    fun reconcileActiveNotifications_appendsRemovedEventForMissingActiveKey() = runTest {
        repository.recordPosted(
            notificationCapture(
                notificationKey = "missing-key",
                title = "Still in db",
                text = "Original body",
                observedAt = 1_000L,
                appLabel = "Snapshot App",
                appInfoResolved = true
            )
        )
        repository.recordPosted(
            notificationCapture(
                notificationKey = "active-key",
                title = "Still active",
                observedAt = 1_500L
            )
        )

        repository.reconcileActiveNotifications(
            activeNotificationKeys = setOf("active-key"),
            reconciledAt = 3_000L
        )

        val staleRemoval = notificationDao.notifications.single {
            it.notificationKey == "missing-key" && it.status == NotificationStatus.REMOVED
        }
        assertEquals("Still in db", staleRemoval.title)
        assertEquals("Original body", staleRemoval.text)
        assertEquals("Snapshot App", staleRemoval.appLabel)
        assertTrue(staleRemoval.appInfoResolved)
        assertEquals(RemovalReason.UNKNOWN, staleRemoval.removalReason)
        assertEquals(2_000L, staleRemoval.timeToRemoval)
        assertEquals(3_000L, staleRemoval.observedAt)
        assertEquals(3_000L, staleRemoval.removedAt)

        val activeRows = notificationDao.getActiveNotifications()
        assertEquals(listOf("active-key"), activeRows.map { it.notificationKey })
    }

    @Test
    fun reconcileActiveNotifications_usesOriginalPostTimestampForSyntheticRemovalDuration() = runTest {
        repository.recordPosted(
            notificationCapture(
                notificationKey = "delayed-observation-key",
                sourcePostTime = 1_000L,
                observedAt = 8_000L
            )
        )

        repository.reconcileActiveNotifications(
            activeNotificationKeys = emptySet(),
            reconciledAt = 9_000L
        )

        val staleRemoval = notificationDao.notifications.single { notification ->
            notification.notificationKey == "delayed-observation-key" &&
                notification.status == NotificationStatus.REMOVED
        }
        assertEquals(8_000L, staleRemoval.timeToRemoval)
        assertEquals(1_000L, staleRemoval.timestamp)
        assertEquals(9_000L, staleRemoval.observedAt)
        assertEquals(9_000L, staleRemoval.removedAt)
    }

    @Test
    fun reconcileActiveNotifications_isIdempotentAfterSyntheticRemoval() = runTest {
        repository.recordPosted(
            notificationCapture(
                notificationKey = "stale-key",
                observedAt = 1_000L
            )
        )

        repository.reconcileActiveNotifications(
            activeNotificationKeys = emptySet(),
            reconciledAt = 2_000L
        )
        repository.reconcileActiveNotifications(
            activeNotificationKeys = emptySet(),
            reconciledAt = 3_000L
        )

        val terminalRows = notificationDao.notifications.filter { notification ->
            notification.notificationKey == "stale-key" &&
                notification.status != NotificationStatus.POSTED
        }
        assertEquals(1, terminalRows.size)
        assertEquals(2_000L, terminalRows.single().removedAt)
    }

    @Test
    fun reconcileActiveNotifications_emptyKeysWithTargetPackageOnlyRemovesThatPackageRows() = runTest {
        repository.recordPosted(
            notificationCapture(
                notificationKey = "target-stale-key",
                packageName = "com.example.target",
                observedAt = 1_000L
            )
        )
        repository.recordPosted(
            notificationCapture(
                notificationKey = "other-stale-key",
                packageName = "com.example.other",
                observedAt = 1_500L
            )
        )

        repository.reconcileActiveNotifications(
            activeNotificationKeys = emptySet(),
            reconciledAt = 3_000L,
            targetPackageName = "com.example.target"
        )

        val targetRows = notificationDao.notifications.filter { notification ->
            notification.notificationKey == "target-stale-key"
        }
        assertEquals(
            listOf(NotificationStatus.POSTED, NotificationStatus.REMOVED),
            targetRows.map { notification -> notification.status }
        )
        assertEquals(3_000L, targetRows.single { it.status == NotificationStatus.REMOVED }.removedAt)
        assertEquals(listOf("com.example.target"), notificationDao.activeNotificationsByPackageQueries)
        assertEquals(0, notificationDao.activeNotificationsQueryCount)

        val otherRows = notificationDao.notifications.filter { notification ->
            notification.notificationKey == "other-stale-key"
        }
        assertEquals(listOf(NotificationStatus.POSTED), otherRows.map { notification -> notification.status })
        assertEquals(listOf("other-stale-key"), notificationDao.getActiveNotifications().map { it.notificationKey })
    }

    @Test
    fun reconcileActiveNotifications_concurrentCallsInsertSingleSyntheticRemoval() = runTest {
        repository.recordPosted(
            notificationCapture(
                notificationKey = "concurrent-stale-key",
                observedAt = 1_000L
            )
        )

        val jobs = List(20) { index ->
            launch {
                repository.reconcileActiveNotifications(
                    activeNotificationKeys = emptySet(),
                    reconciledAt = 2_000L + index
                )
            }
        }
        jobs.joinAll()

        val terminalRows = notificationDao.notifications.filter { notification ->
            notification.notificationKey == "concurrent-stale-key" &&
                notification.status != NotificationStatus.POSTED
        }
        assertEquals(1, terminalRows.size)
    }

    @Test
    fun reconcileActiveNotifications_skipsWhenTerminalArrivedAfterActiveSnapshot() = runTest {
        val posted = notificationCapture(
            notificationKey = "race-key",
            observedAt = 1_000L
        ).toEntity(NotificationStatus.POSTED)
        notificationDao.insert(posted)
        notificationDao.insert(
            notificationCapture(
                notificationKey = "race-key",
                observedAt = 1_500L
            ).toEntity(
                status = NotificationStatus.CLICKED,
                removalReason = RemovalReason.USER_CLICKED,
                removedAt = 1_500L
            )
        )

        repository.reconcileActiveNotifications(
            activeNotificationKeys = emptySet(),
            reconciledAt = 2_000L
        )

        val terminalRows = notificationDao.notifications.filter { notification ->
            notification.notificationKey == "race-key" &&
                notification.status != NotificationStatus.POSTED
        }
        assertEquals(listOf(NotificationStatus.CLICKED), terminalRows.map { it.status })
    }

    @Test
    fun deleteExpiredNotifications_preservesActiveRowsOlderThanCutoff() = runTest {
        notificationDao.insert(
            notificationCapture(notificationKey = "active-old", observedAt = 10L).toEntity(NotificationStatus.POSTED)
        )
        notificationDao.insert(
            notificationCapture(notificationKey = "terminal-old", observedAt = 20L).toEntity(NotificationStatus.POSTED)
        )
        notificationDao.insert(
            notificationCapture(notificationKey = "terminal-old", observedAt = 30L).toEntity(
                status = NotificationStatus.REMOVED,
                removalReason = RemovalReason.UNKNOWN,
                removedAt = 30L
            )
        )
        notificationDao.insert(
            notificationCapture(notificationKey = "new", observedAt = Long.MAX_VALUE).toEntity(NotificationStatus.POSTED)
        )

        val cutoffBeforeDelete = NotificationRetentionPolicy.cutoffTimestamp()
        repository.deleteExpiredNotifications()
        val cutoffAfterDelete = NotificationRetentionPolicy.cutoffTimestamp()

        val actualCutoff = notificationDao.lastDeleteCutoffTimestamp
        checkNotNull(actualCutoff)
        assertTrue(actualCutoff in cutoffBeforeDelete..cutoffAfterDelete)
        assertEquals(
            listOf("active-old", "new"),
            notificationDao.notifications.map { it.notificationKey }
        )
    }

    @Test
    fun deleteExpiredNotifications_preservesRowsInsideNinetyDayRetentionWindow() = runTest {
        val dayMillis = 24L * 60L * 60L * 1_000L
        val now = 200L * dayMillis
        val cutoff = 110L * dayMillis
        val repository = NotificationRepository(
            notificationDao = notificationDao,
            currentTimeMillis = { now }
        )

        notificationDao.insert(
            notificationCapture(notificationKey = "expired", observedAt = cutoff - 2L)
                .toEntity(NotificationStatus.POSTED)
        )
        notificationDao.insert(
            notificationCapture(notificationKey = "expired", observedAt = cutoff - 1L)
                .toEntity(
                    status = NotificationStatus.REMOVED,
                    removalReason = RemovalReason.UNKNOWN,
                    removedAt = cutoff - 1L
                )
        )
        notificationDao.insert(
            notificationCapture(notificationKey = "boundary", observedAt = cutoff)
                .toEntity(NotificationStatus.POSTED)
        )
        notificationDao.insert(
            notificationCapture(notificationKey = "inside", observedAt = cutoff + 1L)
                .toEntity(NotificationStatus.POSTED)
        )
        notificationDao.insert(
            notificationCapture(notificationKey = "inside", observedAt = cutoff + 2L)
                .toEntity(
                    status = NotificationStatus.REMOVED,
                    removalReason = RemovalReason.UNKNOWN,
                    removedAt = cutoff + 2L
                )
        )

        val deleted = repository.deleteExpiredNotifications()

        assertEquals(2, deleted)
        assertEquals(cutoff, notificationDao.lastDeleteCutoffTimestamp)
        assertEquals(
            listOf("boundary", "inside", "inside"),
            notificationDao.notifications.map { notification -> notification.notificationKey }
        )
    }
}
