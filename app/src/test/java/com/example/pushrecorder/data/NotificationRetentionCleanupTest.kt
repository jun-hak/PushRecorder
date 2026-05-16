package com.example.pushrecorder.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationRetentionCleanupTest {
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
}
