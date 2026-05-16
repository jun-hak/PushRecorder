package com.example.pushrecorder.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class FakeNotificationDaoTest {
    @Test
    fun insert_ignoresExplicitPrimaryKeyConflictLikeRoomDao() = runTest {
        val dao = FakeNotificationDao()
        val first = notificationCapture(
            notificationKey = "first",
            title = "first"
        ).toEntity(NotificationStatus.POSTED).copy(id = 7L)
        val conflicting = notificationCapture(
            notificationKey = "conflicting",
            title = "conflicting"
        ).toEntity(NotificationStatus.POSTED).copy(id = 7L)

        assertEquals(7L, dao.insert(first))
        assertEquals(-1L, dao.insert(conflicting))

        assertEquals(listOf("first"), dao.notifications.map(NotificationEntity::title))
    }

    @Test
    fun insert_ignoresDuplicateEventJournalIdLikeRoomDao() = runTest {
        val dao = FakeNotificationDao()
        val first = notificationCapture(
            notificationKey = "first",
            title = "first"
        ).toEntity(
            status = NotificationStatus.POSTED,
            eventJournalId = 42L
        )
        val duplicateJournalEvent = notificationCapture(
            notificationKey = "duplicate",
            title = "duplicate"
        ).toEntity(
            status = NotificationStatus.POSTED,
            eventJournalId = 42L
        )

        assertEquals(1L, dao.insert(first))
        assertEquals(-1L, dao.insert(duplicateJournalEvent))

        assertEquals(listOf("first"), dao.notifications.map(NotificationEntity::title))
    }
}
