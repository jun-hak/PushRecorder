package com.example.pushrecorder.service

import android.service.notification.NotificationListenerService
import com.example.pushrecorder.data.NotificationEventJournalDao
import com.example.pushrecorder.data.NotificationEventJournalEntity
import com.example.pushrecorder.data.notificationCapture
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationEventJournalRepositoryTest {
    private val journalDao = FakeNotificationEventJournalDao()
    private val repository = NotificationEventJournalRepository(
        journalDao = journalDao,
        currentTimeMillis = { 9_000L }
    )

    @Test
    fun journalIfRequired_persistsPostedSnapshotAndReturnsCommandWithJournalId() = runTest {
        val command = NotificationProcessingCommand.Posted(
            notificationCapture(
                notificationKey = "posted-key",
                packageName = "com.example.chat",
                title = "hello",
                text = "body",
                sourcePostTime = 1_000L,
                observedAt = 2_000L,
                flags = 4,
                hasActions = true,
                appLabel = "Chat",
                appInfoResolved = true
            )
        )

        val journaled = repository.journalIfRequired(command)

        assertEquals(1L, journaled.eventJournalId)
        val saved = journalDao.events.single()
        assertEquals(NotificationEventJournalEntity.TYPE_POSTED, saved.eventType)
        assertEquals("posted-key", saved.notificationKey)
        assertEquals("com.example.chat", saved.packageName)
        assertEquals("hello", saved.title)
        assertEquals("body", saved.text)
        assertEquals(1_000L, saved.sourcePostTime)
        assertEquals(2_000L, saved.observedAt)
        assertEquals(4, saved.flags)
        assertTrue(saved.hasActions)
        assertEquals("Chat", saved.appLabel)
        assertTrue(saved.appInfoResolved)
        assertNull(saved.systemReason)
        assertEquals(9_000L, saved.createdAt)
    }

    @Test
    fun pendingEvents_replaysPostedAndRemovedEventsInJournalOrder() = runTest {
        val posted = repository.journalIfRequired(
            NotificationProcessingCommand.Posted(
                notificationCapture(notificationKey = "posted-key")
            )
        )
        val removed = repository.journalIfRequired(
            NotificationProcessingCommand.Removed(
                NotificationRemovalCommand(
                    capture = notificationCapture(notificationKey = "removed-key"),
                    systemReason = NotificationListenerService.REASON_CLICK
                )
            )
        )

        val pendingEvents = repository.pendingEvents()

        assertEquals(listOf(posted.eventJournalId, removed.eventJournalId), pendingEvents.map { it.eventJournalId })
        assertTrue(pendingEvents[0] is NotificationProcessingCommand.Posted)
        val removedCommand = pendingEvents[1] as NotificationProcessingCommand.Removed
        assertEquals("removed-key", removedCommand.notificationKey)
        assertEquals(NotificationListenerService.REASON_CLICK, removedCommand.command.systemReason)
    }

    @Test
    fun journalIfRequired_doesNotPersistReconcileOrAlreadyJournaledEvents() = runTest {
        val reconcile = NotificationProcessingCommand.Reconcile(
            activeCaptures = emptyList(),
            snapshotCapturedAt = 1_000L
        )
        val alreadyJournaled = NotificationProcessingCommand.Posted(
            capture = notificationCapture(notificationKey = "already"),
            eventJournalId = 123L
        )

        assertEquals(reconcile, repository.journalIfRequired(reconcile))
        assertEquals(alreadyJournaled, repository.journalIfRequired(alreadyJournaled))
        assertEquals(emptyList<NotificationEventJournalEntity>(), journalDao.events)
    }

    @Test
    fun acknowledge_deletesJournaledEvent() = runTest {
        val journaled = repository.journalIfRequired(
            NotificationProcessingCommand.Posted(
                notificationCapture(notificationKey = "posted-key")
            )
        )

        repository.acknowledge(journaled)

        assertEquals(emptyList<NotificationEventJournalEntity>(), journalDao.events)
    }
}

class FakeNotificationEventJournalDao : NotificationEventJournalDao {
    private var nextId = 1L

    val events = mutableListOf<NotificationEventJournalEntity>()

    override suspend fun insert(event: NotificationEventJournalEntity): Long {
        if (event.id != 0L && events.any { saved -> saved.id == event.id }) {
            return -1L
        }
        val savedEvent = if (event.id == 0L) {
            event.copy(id = nextId++)
        } else {
            nextId = maxOf(nextId, event.id + 1)
            event
        }
        events += savedEvent
        return savedEvent.id
    }

    override suspend fun pendingEvents(): List<NotificationEventJournalEntity> {
        return events.sortedBy(NotificationEventJournalEntity::id)
    }

    override suspend fun deleteById(id: Long): Int {
        val beforeSize = events.size
        events.removeAll { event -> event.id == id }
        return beforeSize - events.size
    }
}
