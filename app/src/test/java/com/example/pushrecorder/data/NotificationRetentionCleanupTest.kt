package com.example.pushrecorder.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationRetentionCleanupTest {
    @Test
    fun deleteExpiredNotifications_deletesExpiredInactiveEventRows() = runTest {
        val dayMillis = 24L * 60L * 60L * 1_000L
        val now = 200L * dayMillis
        val cutoff = 110L * dayMillis
        val notificationDao = FakeNotificationDao()
        val repository = NotificationRepository(
            notificationDao = notificationDao,
            currentTimeMillis = { now }
        )

        notificationDao.insert(
            notificationCapture(
                notificationKey = "expired-inactive",
                title = "expired posted",
                observedAt = cutoff - 2L
            ).toEntity(NotificationStatus.POSTED)
        )
        notificationDao.insert(
            notificationCapture(
                notificationKey = "expired-inactive",
                title = "expired removed",
                observedAt = cutoff - 1L
            ).toEntity(
                status = NotificationStatus.REMOVED,
                removalReason = RemovalReason.UNKNOWN,
                removedAt = cutoff - 1L
            )
        )
        notificationDao.insert(
            notificationCapture(
                notificationKey = "retained-inactive",
                title = "retained posted",
                observedAt = cutoff + 1L
            ).toEntity(NotificationStatus.POSTED)
        )
        notificationDao.insert(
            notificationCapture(
                notificationKey = "retained-inactive",
                title = "retained removed",
                observedAt = cutoff + 2L
            ).toEntity(
                status = NotificationStatus.REMOVED,
                removalReason = RemovalReason.UNKNOWN,
                removedAt = cutoff + 2L
            )
        )

        val deletedCount = repository.deleteExpiredNotifications()

        assertEquals(2, deletedCount)
        assertEquals(cutoff, notificationDao.lastDeleteCutoffTimestamp)
        assertEquals(
            listOf("retained posted", "retained removed"),
            notificationDao.notifications.map(NotificationEntity::title)
        )
    }

    @Test
    fun deleteExpiredNotifications_preservesExpiredStillActivePostedRow() = runTest {
        val dayMillis = 24L * 60L * 60L * 1_000L
        val now = 200L * dayMillis
        val cutoff = 110L * dayMillis
        val notificationDao = FakeNotificationDao()
        val repository = NotificationRepository(
            notificationDao = notificationDao,
            currentTimeMillis = { now }
        )

        notificationDao.insert(
            notificationCapture(
                notificationKey = "expired-active",
                title = "expired but active",
                observedAt = cutoff - 1L
            ).toEntity(NotificationStatus.POSTED)
        )
        notificationDao.insert(
            notificationCapture(
                notificationKey = "expired-inactive",
                title = "expired inactive posted",
                observedAt = cutoff - 3L
            ).toEntity(NotificationStatus.POSTED)
        )
        notificationDao.insert(
            notificationCapture(
                notificationKey = "expired-inactive",
                title = "expired inactive removed",
                observedAt = cutoff - 2L
            ).toEntity(
                status = NotificationStatus.REMOVED,
                removalReason = RemovalReason.UNKNOWN,
                removedAt = cutoff - 2L
            )
        )

        val deletedCount = repository.deleteExpiredNotifications()

        assertEquals(2, deletedCount)
        assertEquals(cutoff, notificationDao.lastDeleteCutoffTimestamp)
        assertEquals(
            listOf("expired but active"),
            notificationDao.notifications.map(NotificationEntity::title)
        )
        assertEquals(
            listOf("expired but active"),
            notificationDao.getActiveNotifications().map(NotificationEntity::title)
        )
    }

    @Test
    fun deleteExpiredNotifications_prunesExpiredRemovedRowWithoutTreatingItAsActive() = runTest {
        val dayMillis = 24L * 60L * 60L * 1_000L
        val now = 200L * dayMillis
        val cutoff = 110L * dayMillis
        val notificationDao = FakeNotificationDao()
        val repository = NotificationRepository(
            notificationDao = notificationDao,
            currentTimeMillis = { now }
        )

        notificationDao.insert(
            notificationCapture(
                notificationKey = "expired-removed-only",
                title = "expired removed only",
                observedAt = cutoff - 2L
            ).toEntity(
                status = NotificationStatus.REMOVED,
                removalReason = RemovalReason.UNKNOWN,
                removedAt = cutoff - 2L
            )
        )
        notificationDao.insert(
            notificationCapture(
                notificationKey = "expired-active-posted",
                title = "expired active posted",
                observedAt = cutoff - 1L
            ).toEntity(NotificationStatus.POSTED)
        )

        val deletedCount = repository.deleteExpiredNotifications()

        assertEquals(1, deletedCount)
        assertEquals(cutoff, notificationDao.lastDeleteCutoffTimestamp)
        assertEquals(
            listOf("expired active posted"),
            notificationDao.notifications.map(NotificationEntity::title)
        )
        assertEquals(
            listOf("expired active posted"),
            notificationDao.getActiveNotifications().map(NotificationEntity::title)
        )
    }

    @Test
    fun deleteExpiredNotifications_prunesRestartReconciledLifecycleAfterTerminalAgesOut() = runTest {
        var now = 10_000L
        val notificationDao = FakeNotificationDao()
        val repository = NotificationRepository(
            notificationDao = notificationDao,
            currentTimeMillis = { now },
            retentionPolicy = NotificationRetentionPolicy(
                maxAgeMillis = 5_000L,
                maxRows = null,
                maxEvents = null
            )
        )

        notificationDao.insert(
            notificationCapture(
                notificationKey = "restart-active",
                title = "posted before restart",
                observedAt = 1_000L,
                sourcePostTime = 1_000L
            ).toEntity(NotificationStatus.POSTED)
        )

        assertEquals(0, repository.deleteExpiredNotifications())
        assertEquals(
            listOf("posted before restart"),
            notificationDao.getActiveNotifications().map(NotificationEntity::title)
        )

        repository.reconcileActiveNotifications(
            activeNotificationKeys = emptySet(),
            reconciledAt = now,
            activeSnapshotCapturedAt = now
        )
        assertEquals(0, repository.deleteExpiredNotifications())
        assertEquals(
            listOf(NotificationStatus.POSTED, NotificationStatus.REMOVED),
            notificationDao.notifications.map(NotificationEntity::status)
        )
        assertEquals(emptyList<NotificationEntity>(), notificationDao.getActiveNotifications())

        now = 16_000L

        assertEquals(2, repository.deleteExpiredNotifications())
        assertEquals(emptyList<NotificationEntity>(), notificationDao.notifications)
    }

    @Test
    fun deleteExpiredNotifications_retainsRowsAtCutoffAndDeletesRowsBeforeCutoff() = runTest {
        val dayMillis = 24L * 60L * 60L * 1_000L
        val now = 200L * dayMillis
        val cutoff = 110L * dayMillis
        val notificationDao = FakeNotificationDao()
        val repository = NotificationRepository(
            notificationDao = notificationDao,
            currentTimeMillis = { now }
        )

        notificationDao.insert(
            notificationCapture(
                notificationKey = "before-cutoff",
                title = "before cutoff posted",
                observedAt = cutoff - 1L
            ).toEntity(NotificationStatus.POSTED)
        )
        notificationDao.insert(
            notificationCapture(
                notificationKey = "before-cutoff",
                title = "before cutoff removed",
                observedAt = cutoff - 1L
            ).toEntity(
                status = NotificationStatus.REMOVED,
                removalReason = RemovalReason.UNKNOWN,
                removedAt = cutoff - 1L
            )
        )
        notificationDao.insert(
            notificationCapture(
                notificationKey = "at-cutoff",
                title = "at cutoff posted",
                observedAt = cutoff
            ).toEntity(NotificationStatus.POSTED)
        )
        notificationDao.insert(
            notificationCapture(
                notificationKey = "at-cutoff",
                title = "at cutoff removed",
                observedAt = cutoff
            ).toEntity(
                status = NotificationStatus.REMOVED,
                removalReason = RemovalReason.UNKNOWN,
                removedAt = cutoff
            )
        )

        val deletedCount = repository.deleteExpiredNotifications()

        assertEquals(2, deletedCount)
        assertEquals(cutoff, notificationDao.lastDeleteCutoffTimestamp)
        assertEquals(
            listOf("at cutoff posted", "at cutoff removed"),
            notificationDao.notifications.map(NotificationEntity::title)
        )
    }

    @Test
    fun deleteExpiredNotifications_preservesOlderClockTerminalForRetainedPost() = runTest {
        val dayMillis = 24L * 60L * 60L * 1_000L
        val now = 200L * dayMillis
        val cutoff = 110L * dayMillis
        val notificationDao = FakeNotificationDao()
        val repository = NotificationRepository(
            notificationDao = notificationDao,
            currentTimeMillis = { now }
        )

        notificationDao.insert(
            notificationCapture(
                notificationKey = "clock-rollback",
                title = "posted",
                observedAt = cutoff + 10L
            ).toEntity(NotificationStatus.POSTED)
        )
        notificationDao.insert(
            notificationCapture(
                notificationKey = "clock-rollback",
                title = "removed with older clock",
                observedAt = cutoff - 10L
            ).toEntity(
                status = NotificationStatus.REMOVED,
                removalReason = RemovalReason.UNKNOWN,
                removedAt = cutoff - 10L
            )
        )

        val deletedCount = repository.deleteExpiredNotifications()

        assertEquals(0, deletedCount)
        assertEquals(cutoff, notificationDao.lastDeleteCutoffTimestamp)
        assertEquals(
            listOf(NotificationStatus.POSTED, NotificationStatus.REMOVED),
            notificationDao.notifications.map(NotificationEntity::status)
        )
        assertEquals(emptyList<NotificationEntity>(), notificationDao.getActiveNotifications())
    }

    @Test
    fun deleteExpiredNotifications_preservesExpiredPostedRowForRetainedTerminal() = runTest {
        val dayMillis = 24L * 60L * 60L * 1_000L
        val now = 200L * dayMillis
        val cutoff = 110L * dayMillis
        val notificationDao = FakeNotificationDao()
        val repository = NotificationRepository(
            notificationDao = notificationDao,
            currentTimeMillis = { now }
        )

        notificationDao.insert(
            notificationCapture(
                notificationKey = "cross-cutoff-lifecycle",
                title = "posted before cutoff",
                observedAt = cutoff - 10L
            ).toEntity(NotificationStatus.POSTED)
        )
        notificationDao.insert(
            notificationCapture(
                notificationKey = "cross-cutoff-lifecycle",
                title = "removed after cutoff",
                observedAt = cutoff + 10L
            ).toEntity(
                status = NotificationStatus.REMOVED,
                removalReason = RemovalReason.UNKNOWN,
                removedAt = cutoff + 10L
            )
        )
        notificationDao.insert(
            notificationCapture(
                notificationKey = "expired-complete-lifecycle",
                title = "expired posted",
                observedAt = cutoff - 30L
            ).toEntity(NotificationStatus.POSTED)
        )
        notificationDao.insert(
            notificationCapture(
                notificationKey = "expired-complete-lifecycle",
                title = "expired removed",
                observedAt = cutoff - 20L
            ).toEntity(
                status = NotificationStatus.REMOVED,
                removalReason = RemovalReason.UNKNOWN,
                removedAt = cutoff - 20L
            )
        )

        val deletedCount = repository.deleteExpiredNotifications()

        assertEquals(2, deletedCount)
        assertEquals(cutoff, notificationDao.lastDeleteCutoffTimestamp)
        assertEquals(
            listOf("posted before cutoff", "removed after cutoff"),
            notificationDao.notifications.map(NotificationEntity::title)
        )
        assertEquals(emptyList<NotificationEntity>(), notificationDao.getActiveNotifications())
    }

    @Test
    fun deleteExpiredNotifications_removesLifecycleRowsOutsideNinetyDayRetentionWindow() = runTest {
        val dayMillis = 24L * 60L * 60L * 1_000L
        val now = 200L * dayMillis
        val cutoff = 110L * dayMillis
        val notificationDao = FakeNotificationDao()
        val repository = NotificationRepository(
            notificationDao = notificationDao,
            currentTimeMillis = { now }
        )

        notificationDao.insert(
            notificationCapture(
                notificationKey = "expired-row",
                observedAt = cutoff - 2L
            ).toEntity(NotificationStatus.POSTED)
        )
        notificationDao.insert(
            notificationCapture(
                notificationKey = "expired-row",
                observedAt = cutoff - 1L
            ).toEntity(
                status = NotificationStatus.REMOVED,
                removalReason = RemovalReason.UNKNOWN,
                removedAt = cutoff - 1L
            )
        )
        notificationDao.insert(
            notificationCapture(
                notificationKey = "boundary-row",
                observedAt = cutoff
            ).toEntity(NotificationStatus.POSTED)
        )
        notificationDao.insert(
            notificationCapture(
                notificationKey = "retained-row",
                observedAt = cutoff + 1L
            ).toEntity(NotificationStatus.POSTED)
        )
        notificationDao.insert(
            notificationCapture(
                notificationKey = "retained-row",
                observedAt = cutoff + 2L
            ).toEntity(
                status = NotificationStatus.REMOVED,
                removalReason = RemovalReason.UNKNOWN,
                removedAt = cutoff + 2L
            )
        )

        val deletedCount = repository.deleteExpiredNotifications()

        assertEquals(2, deletedCount)
        assertEquals(cutoff, notificationDao.lastDeleteCutoffTimestamp)
        assertEquals(
            listOf("boundary-row", "retained-row", "retained-row"),
            notificationDao.notifications.map(NotificationEntity::notificationKey)
        )
    }

    @Test
    fun deleteExpiredNotifications_removesExpiredRowsWhenNotificationKeyIsReusedInsideRetention() = runTest {
        val dayMillis = 24L * 60L * 60L * 1_000L
        val now = 200L * dayMillis
        val cutoff = 110L * dayMillis
        val notificationDao = FakeNotificationDao()
        val repository = NotificationRepository(
            notificationDao = notificationDao,
            currentTimeMillis = { now }
        )

        notificationDao.insert(
            notificationCapture(
                notificationKey = "reused-key",
                title = "old posted",
                observedAt = cutoff - 20L
            ).toEntity(NotificationStatus.POSTED)
        )
        notificationDao.insert(
            notificationCapture(
                notificationKey = "reused-key",
                title = "old removed",
                observedAt = cutoff - 10L
            ).toEntity(
                status = NotificationStatus.REMOVED,
                removalReason = RemovalReason.UNKNOWN,
                removedAt = cutoff - 10L
            )
        )
        notificationDao.insert(
            notificationCapture(
                notificationKey = "reused-key",
                title = "new posted",
                observedAt = cutoff + 10L
            ).toEntity(NotificationStatus.POSTED)
        )

        val deletedCount = repository.deleteExpiredNotifications()

        assertEquals(2, deletedCount)
        assertEquals(cutoff, notificationDao.lastDeleteCutoffTimestamp)
        assertEquals(
            listOf("new posted"),
            notificationDao.notifications.map(NotificationEntity::title)
        )
        assertEquals(
            listOf("new posted"),
            notificationDao.getActiveNotifications().map(NotificationEntity::title)
        )
    }

    @Test
    fun deleteExpiredNotifications_doesNotKeepExpiredLifecycleBecauseOlderLifecycleWasRetained() = runTest {
        val dayMillis = 24L * 60L * 60L * 1_000L
        val now = 200L * dayMillis
        val cutoff = 110L * dayMillis
        val notificationDao = FakeNotificationDao()
        val repository = NotificationRepository(
            notificationDao = notificationDao,
            currentTimeMillis = { now }
        )

        notificationDao.insert(
            notificationCapture(
                notificationKey = "multi-lifecycle-key",
                title = "retained posted",
                observedAt = cutoff + 40L
            ).toEntity(NotificationStatus.POSTED)
        )
        notificationDao.insert(
            notificationCapture(
                notificationKey = "multi-lifecycle-key",
                title = "retained removed",
                observedAt = cutoff + 50L
            ).toEntity(
                status = NotificationStatus.REMOVED,
                removalReason = RemovalReason.UNKNOWN,
                removedAt = cutoff + 50L
            )
        )
        notificationDao.insert(
            notificationCapture(
                notificationKey = "multi-lifecycle-key",
                title = "expired posted",
                observedAt = cutoff - 20L
            ).toEntity(NotificationStatus.POSTED)
        )
        notificationDao.insert(
            notificationCapture(
                notificationKey = "multi-lifecycle-key",
                title = "expired removed",
                observedAt = cutoff - 10L
            ).toEntity(
                status = NotificationStatus.REMOVED,
                removalReason = RemovalReason.UNKNOWN,
                removedAt = cutoff - 10L
            )
        )

        val deletedCount = repository.deleteExpiredNotifications()

        assertEquals(2, deletedCount)
        assertEquals(
            listOf("retained posted", "retained removed"),
            notificationDao.notifications.map(NotificationEntity::title)
        )
        assertEquals(emptyList<NotificationEntity>(), notificationDao.getActiveNotifications())
    }

    @Test
    fun deleteExpiredNotifications_preservesOverflowPostedRowForRetainedTerminal() = runTest {
        val notificationDao = FakeNotificationDao()
        val repository = NotificationRepository(
            notificationDao = notificationDao,
            retentionPolicy = NotificationRetentionPolicy(
                maxAgeMillis = null,
                maxRows = 1,
                maxEvents = null
            )
        )

        notificationDao.insert(
            notificationCapture(
                notificationKey = "closed-row-cap",
                title = "posted partner",
                observedAt = 1L
            ).toEntity(NotificationStatus.POSTED)
        )
        notificationDao.insert(
            notificationCapture(
                notificationKey = "unrelated-overflow",
                title = "unrelated overflow",
                observedAt = 2L
            ).toEntity(
                status = NotificationStatus.REMOVED,
                removalReason = RemovalReason.UNKNOWN,
                removedAt = 2L
            )
        )
        notificationDao.insert(
            notificationCapture(
                notificationKey = "closed-row-cap",
                title = "retained terminal",
                observedAt = 3L
            ).toEntity(
                status = NotificationStatus.REMOVED,
                removalReason = RemovalReason.USER_DISMISSED,
                removedAt = 3L
            )
        )

        val deletedCount = repository.deleteExpiredNotifications()

        assertEquals(1, deletedCount)
        assertEquals(1, notificationDao.lastTrimMaxRows)
        assertEquals(
            listOf("posted partner", "retained terminal"),
            notificationDao.notifications.map(NotificationEntity::title)
        )
        assertEquals(emptyList<NotificationEntity>(), notificationDao.getActiveNotifications())
    }

    @Test
    fun deleteExpiredNotifications_prunesOverflowPostedLifecycleWhenKeyIsReusedInsideRowCap() = runTest {
        val notificationDao = FakeNotificationDao()
        val repository = NotificationRepository(
            notificationDao = notificationDao,
            retentionPolicy = NotificationRetentionPolicy(
                maxAgeMillis = null,
                maxRows = 2,
                maxEvents = null
            )
        )

        notificationDao.insert(
            notificationCapture(
                notificationKey = "reused-row-cap",
                title = "old posted",
                observedAt = 1L
            ).toEntity(NotificationStatus.POSTED)
        )
        notificationDao.insert(
            notificationCapture(
                notificationKey = "reused-row-cap",
                title = "old removed",
                observedAt = 2L
            ).toEntity(
                status = NotificationStatus.REMOVED,
                removalReason = RemovalReason.USER_DISMISSED,
                removedAt = 2L
            )
        )
        notificationDao.insert(
            notificationCapture(
                notificationKey = "reused-row-cap",
                title = "active repost",
                observedAt = 3L
            ).toEntity(NotificationStatus.POSTED)
        )
        notificationDao.insert(
            notificationCapture(
                notificationKey = "newest-terminal",
                title = "newest terminal",
                observedAt = 4L
            ).toEntity(
                status = NotificationStatus.REMOVED,
                removalReason = RemovalReason.UNKNOWN,
                removedAt = 4L
            )
        )

        val deletedCount = repository.deleteExpiredNotifications()

        assertEquals(2, deletedCount)
        assertEquals(
            listOf("active repost", "newest terminal"),
            notificationDao.notifications.map(NotificationEntity::title)
        )
        assertEquals(
            listOf("active repost"),
            notificationDao.getActiveNotifications().map(NotificationEntity::title)
        )
    }

    @Test
    fun deleteExpiredNotifications_skipsTimeCleanupWhenPolicyOnlyHasCountLimit() = runTest {
        val notificationDao = FakeNotificationDao()
        val repository = NotificationRepository(
            notificationDao = notificationDao,
            currentTimeMillis = { 10_000L },
            retentionPolicy = NotificationRetentionPolicy(
                maxAgeMillis = null,
                maxRows = 100,
                maxEvents = null
            )
        )
        notificationDao.insert(
            notificationCapture(
                notificationKey = "retained-by-count-only-policy",
                observedAt = 1L
            ).toEntity(NotificationStatus.POSTED)
        )

        val deletedCount = repository.deleteExpiredNotifications()

        assertEquals(0, deletedCount)
        assertNull(notificationDao.lastDeleteCutoffTimestamp)
        assertEquals(100, notificationDao.lastTrimMaxRows)
        assertEquals(
            listOf("retained-by-count-only-policy"),
            notificationDao.notifications.map(NotificationEntity::notificationKey)
        )
    }

    @Test
    fun deleteExpiredNotifications_trimsOldestInactiveRowsToConfiguredCountLimit() = runTest {
        val notificationDao = FakeNotificationDao()
        val repository = NotificationRepository(
            notificationDao = notificationDao,
            currentTimeMillis = { 10_000L },
            retentionPolicy = NotificationRetentionPolicy(
                maxAgeMillis = null,
                maxRows = 3,
                maxEvents = null
            )
        )

        listOf("old-1", "old-2", "new-1", "new-2", "new-3").forEachIndexed { index, key ->
            notificationDao.insert(
                notificationCapture(
                    notificationKey = key,
                    observedAt = index.toLong()
                ).toEntity(
                    status = NotificationStatus.REMOVED,
                    removalReason = RemovalReason.UNKNOWN,
                    removedAt = index.toLong()
                )
            )
        }

        val deletedCount = repository.deleteExpiredNotifications()

        assertEquals(2, deletedCount)
        assertNull(notificationDao.lastDeleteCutoffTimestamp)
        assertEquals(3, notificationDao.lastTrimMaxRows)
        assertEquals(
            listOf("new-1", "new-2", "new-3"),
            notificationDao.notifications.map(NotificationEntity::notificationKey)
        )
    }

    @Test
    fun deleteExpiredNotifications_doesNotTrimWhenRowCountEqualsConfiguredCountLimit() = runTest {
        val notificationDao = FakeNotificationDao()
        val repository = NotificationRepository(
            notificationDao = notificationDao,
            currentTimeMillis = { 10_000L },
            retentionPolicy = NotificationRetentionPolicy(
                maxAgeMillis = null,
                maxRows = 3,
                maxEvents = null
            )
        )

        listOf("row-1", "row-2", "row-3").forEachIndexed { index, key ->
            notificationDao.insert(
                notificationCapture(
                    notificationKey = key,
                    observedAt = index.toLong()
                ).toEntity(
                    status = NotificationStatus.REMOVED,
                    removalReason = RemovalReason.UNKNOWN,
                    removedAt = index.toLong()
                )
            )
        }

        val deletedCount = repository.deleteExpiredNotifications()

        assertEquals(0, deletedCount)
        assertEquals(3, notificationDao.lastTrimMaxRows)
        assertEquals(
            listOf("row-1", "row-2", "row-3"),
            notificationDao.notifications.map(NotificationEntity::notificationKey)
        )
    }

    @Test
    fun deleteExpiredNotifications_usesMaxEventsAsCountLimitWhenMaxRowsIsNotConfigured() = runTest {
        val notificationDao = FakeNotificationDao()
        val repository = NotificationRepository(
            notificationDao = notificationDao,
            currentTimeMillis = { 10_000L },
            retentionPolicy = NotificationRetentionPolicy(
                maxAgeMillis = null,
                maxRows = null,
                maxEvents = 2
            )
        )

        listOf("event-1", "event-2", "event-3").forEachIndexed { index, key ->
            notificationDao.insert(
                notificationCapture(
                    notificationKey = key,
                    observedAt = index.toLong()
                ).toEntity(
                    status = NotificationStatus.REMOVED,
                    removalReason = RemovalReason.UNKNOWN,
                    removedAt = index.toLong()
                )
            )
        }

        val deletedCount = repository.deleteExpiredNotifications()

        assertEquals(1, deletedCount)
        assertEquals(2, notificationDao.lastTrimMaxRows)
        assertEquals(
            listOf("event-2", "event-3"),
            notificationDao.notifications.map(NotificationEntity::notificationKey)
        )
    }

    @Test
    fun deleteExpiredNotifications_usesStrictestConfiguredCountLimit() = runTest {
        val notificationDao = FakeNotificationDao()
        val repository = NotificationRepository(
            notificationDao = notificationDao,
            currentTimeMillis = { 10_000L },
            retentionPolicy = NotificationRetentionPolicy(
                maxAgeMillis = null,
                maxRows = 4,
                maxEvents = 2
            )
        )

        listOf("row-1", "row-2", "row-3", "row-4").forEachIndexed { index, key ->
            notificationDao.insert(
                notificationCapture(
                    notificationKey = key,
                    observedAt = index.toLong()
                ).toEntity(
                    status = NotificationStatus.REMOVED,
                    removalReason = RemovalReason.UNKNOWN,
                    removedAt = index.toLong()
                )
            )
        }

        val deletedCount = repository.deleteExpiredNotifications()

        assertEquals(2, deletedCount)
        assertEquals(2, notificationDao.lastTrimMaxRows)
        assertEquals(
            listOf("row-3", "row-4"),
            notificationDao.notifications.map(NotificationEntity::notificationKey)
        )
    }

    @Test
    fun deleteExpiredNotifications_preservesActiveRowsOutsideConfiguredCountLimit() = runTest {
        val notificationDao = FakeNotificationDao()
        val repository = NotificationRepository(
            notificationDao = notificationDao,
            currentTimeMillis = { 10_000L },
            retentionPolicy = NotificationRetentionPolicy(
                maxAgeMillis = null,
                maxRows = 2,
                maxEvents = null
            )
        )

        notificationDao.insert(
            notificationCapture(
                notificationKey = "old-active",
                title = "old active",
                observedAt = 1L
            ).toEntity(NotificationStatus.POSTED)
        )
        listOf("new-1", "new-2", "new-3").forEachIndexed { index, key ->
            notificationDao.insert(
                notificationCapture(
                    notificationKey = key,
                    title = key,
                    observedAt = 2L + index
                ).toEntity(
                    status = NotificationStatus.REMOVED,
                    removalReason = RemovalReason.UNKNOWN,
                    removedAt = 2L + index
                )
            )
        }

        val deletedCount = repository.deleteExpiredNotifications()

        assertEquals(1, deletedCount)
        assertEquals(2, notificationDao.lastTrimMaxRows)
        assertEquals(
            listOf("old active", "new-2", "new-3"),
            notificationDao.notifications.map(NotificationEntity::title)
        )
    }

    @Test
    fun deleteExpiredNotifications_prunesOverflowRemovedRowWithoutTreatingItAsActive() = runTest {
        val notificationDao = FakeNotificationDao()
        val repository = NotificationRepository(
            notificationDao = notificationDao,
            currentTimeMillis = { 10_000L },
            retentionPolicy = NotificationRetentionPolicy(
                maxAgeMillis = null,
                maxRows = 1,
                maxEvents = null
            )
        )

        notificationDao.insert(
            notificationCapture(
                notificationKey = "old-active",
                title = "old active",
                observedAt = 1L
            ).toEntity(NotificationStatus.POSTED)
        )
        notificationDao.insert(
            notificationCapture(
                notificationKey = "overflow-removed-only",
                title = "overflow removed only",
                observedAt = 2L
            ).toEntity(
                status = NotificationStatus.REMOVED,
                removalReason = RemovalReason.UNKNOWN,
                removedAt = 2L
            )
        )
        notificationDao.insert(
            notificationCapture(
                notificationKey = "retained-removed-only",
                title = "retained removed only",
                observedAt = 3L
            ).toEntity(
                status = NotificationStatus.REMOVED,
                removalReason = RemovalReason.UNKNOWN,
                removedAt = 3L
            )
        )

        val deletedCount = repository.deleteExpiredNotifications()

        assertEquals(1, deletedCount)
        assertEquals(1, notificationDao.lastTrimMaxRows)
        assertEquals(
            listOf("old active", "retained removed only"),
            notificationDao.notifications.map(NotificationEntity::title)
        )
        assertEquals(
            listOf("old active"),
            notificationDao.getActiveNotifications().map(NotificationEntity::title)
        )
    }

    @Test
    fun deleteExpiredNotifications_preservesActiveRowsOutsideMaxEventLimit() = runTest {
        val notificationDao = FakeNotificationDao()
        val repository = NotificationRepository(
            notificationDao = notificationDao,
            currentTimeMillis = { 10_000L },
            retentionPolicy = NotificationRetentionPolicy(
                maxAgeMillis = null,
                maxRows = null,
                maxEvents = 2
            )
        )

        notificationDao.insert(
            notificationCapture(
                notificationKey = "old-active",
                title = "old active",
                observedAt = 1L
            ).toEntity(NotificationStatus.POSTED)
        )
        listOf("new-1", "new-2", "new-3").forEachIndexed { index, key ->
            notificationDao.insert(
                notificationCapture(
                    notificationKey = key,
                    title = key,
                    observedAt = 2L + index
                ).toEntity(
                    status = NotificationStatus.REMOVED,
                    removalReason = RemovalReason.UNKNOWN,
                    removedAt = 2L + index
                )
            )
        }

        val deletedCount = repository.deleteExpiredNotifications()

        assertEquals(1, deletedCount)
        assertEquals(2, notificationDao.lastTrimMaxRows)
        assertEquals(
            listOf("old active", "new-2", "new-3"),
            notificationDao.notifications.map(NotificationEntity::title)
        )
        assertEquals(
            listOf("old active"),
            notificationDao.getActiveNotifications().map(NotificationEntity::title)
        )
    }

    @Test
    fun deleteExpiredNotifications_preservesPostedRowForTerminalRetainedByCountLimit() = runTest {
        val notificationDao = FakeNotificationDao()
        val repository = NotificationRepository(
            notificationDao = notificationDao,
            currentTimeMillis = { 10_000L },
            retentionPolicy = NotificationRetentionPolicy(
                maxAgeMillis = null,
                maxRows = 2,
                maxEvents = null
            )
        )

        notificationDao.insert(
            notificationCapture(
                notificationKey = "count-retained-lifecycle",
                title = "posted outside count limit",
                observedAt = 1L
            ).toEntity(NotificationStatus.POSTED)
        )
        notificationDao.insert(
            notificationCapture(
                notificationKey = "count-retained-lifecycle",
                title = "removed inside count limit",
                observedAt = 2L
            ).toEntity(
                status = NotificationStatus.REMOVED,
                removalReason = RemovalReason.UNKNOWN,
                removedAt = 2L
            )
        )
        notificationDao.insert(
            notificationCapture(
                notificationKey = "unrelated-new-row",
                title = "unrelated new row",
                observedAt = 3L
            ).toEntity(
                status = NotificationStatus.REMOVED,
                removalReason = RemovalReason.UNKNOWN,
                removedAt = 3L
            )
        )

        val deletedCount = repository.deleteExpiredNotifications()

        assertEquals(0, deletedCount)
        assertEquals(2, notificationDao.lastTrimMaxRows)
        assertEquals(
            listOf("posted outside count limit", "removed inside count limit", "unrelated new row"),
            notificationDao.notifications.map(NotificationEntity::title)
        )
        assertEquals(emptyList<NotificationEntity>(), notificationDao.getActiveNotifications())
    }
}
