package com.example.pushrecorder.service

import com.example.pushrecorder.data.notificationCapture
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationDurableEventEnqueuerTest {
    @Test
    fun enqueue_persistsPostedEventBeforeQueueingIt() = runTest {
        val journalDao = FakeNotificationEventJournalDao()
        val journalRepository = NotificationEventJournalRepository(
            journalDao = journalDao,
            currentTimeMillis = { 1_000L }
        )
        val queuedEvents = mutableListOf<NotificationProcessingCommand>()
        val enqueuer = durableEnqueuer(
            journalRepository = journalRepository,
            enqueueCommand = { command ->
                queuedEvents += command
                true
            }
        )

        enqueuer.enqueue(
            NotificationProcessingCommand.Posted(
                notificationCapture(notificationKey = "posted-key")
            )
        )

        assertEquals(listOf(1L), queuedEvents.map { event -> event.eventJournalId })
        assertEquals(listOf("posted-key"), journalDao.events.map { event -> event.notificationKey })
    }

    @Test
    fun replayPendingEvents_queuesExistingJournalEventsWithoutDuplicatingJournalRows() = runTest {
        val journalDao = FakeNotificationEventJournalDao()
        val journalRepository = NotificationEventJournalRepository(
            journalDao = journalDao,
            currentTimeMillis = { 1_000L }
        )
        journalRepository.journalIfRequired(
            NotificationProcessingCommand.Posted(
                notificationCapture(notificationKey = "first")
            )
        )
        journalRepository.journalIfRequired(
            NotificationProcessingCommand.Posted(
                notificationCapture(notificationKey = "second")
            )
        )
        val queuedEvents = mutableListOf<NotificationProcessingCommand>()
        val enqueuer = durableEnqueuer(
            journalRepository = journalRepository,
            enqueueCommand = { command ->
                queuedEvents += command
                true
            }
        )

        enqueuer.replayPendingEvents()

        assertEquals(listOf("first", "second"), queuedEvents.map { event -> event.notificationKey })
        assertEquals(listOf(1L, 2L), queuedEvents.map { event -> event.eventJournalId })
        assertEquals(2, journalDao.events.size)
    }

    @Test
    fun enqueue_doesNotJournalReconcileEvents() = runTest {
        val journalDao = FakeNotificationEventJournalDao()
        val journalRepository = NotificationEventJournalRepository(
            journalDao = journalDao,
            currentTimeMillis = { 1_000L }
        )
        val queuedEvents = mutableListOf<NotificationProcessingCommand>()
        val enqueuer = durableEnqueuer(
            journalRepository = journalRepository,
            enqueueCommand = { command ->
                queuedEvents += command
                true
            }
        )

        enqueuer.enqueue(
            NotificationProcessingCommand.Reconcile(
                activeCaptures = emptyList(),
                snapshotCapturedAt = 1_000L
            )
        )

        assertEquals(1, queuedEvents.size)
        assertTrue(queuedEvents.single() is NotificationProcessingCommand.Reconcile)
        assertEquals(0, journalDao.events.size)
    }

    private fun durableEnqueuer(
        journalRepository: NotificationEventJournalRepository,
        enqueueCommand: (NotificationProcessingCommand) -> Boolean
    ): NotificationDurableEventEnqueuer {
        return NotificationDurableEventEnqueuer(
            journalRepository = journalRepository,
            enqueueCommand = enqueueCommand,
            statusRecorder = RecordingStatusRecorder(),
            logError = { _, _ -> }
        )
    }

    private class RecordingStatusRecorder : NotificationListenerEventStatusRecorder {
        override fun markPosted(at: Long) = Unit

        override fun markRemoved(at: Long) = Unit

        override fun markEventQueued() = Unit

        override fun markEventProcessed() = Unit

        override fun markEventFailed(message: String, at: Long) = Unit

        override fun markError(message: String, at: Long) = Unit

        override fun markError(message: String) = Unit
    }
}
