package com.example.pushrecorder.service

import com.example.pushrecorder.data.NotificationEventJournalDao
import com.example.pushrecorder.data.NotificationEventJournalEntity
import com.example.pushrecorder.data.notificationCapture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationDurableEventEnqueuerTest {
    @Test
    fun postedCallback_onlyDispatchesJournalWorkAndDoesNotStartRoomInsertInline() = runTest {
        val journalDao = BlockingInsertJournalDao()
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
        val adapter = NotificationListenerAdapter(
            enqueueEvent = { command -> enqueuer.enqueue(command) }
        )

        enqueuer.start()

        adapter.onNotificationPosted(notificationCapture(notificationKey = "posted-dispatch-only"))

        assertFalse(
            "Posted callback should return after dispatching to the journal queue; Room insert starts only when the worker runs.",
            journalDao.insertStarted.isCompleted
        )
        assertEquals(emptyList<NotificationProcessingCommand>(), queuedEvents)

        runCurrent()

        assertTrue(journalDao.insertStarted.isCompleted)
        assertEquals(emptyList<NotificationProcessingCommand>(), queuedEvents)

        journalDao.releaseInsert.complete(1L)
        runCurrent()
    }

    @Test
    fun listenerPostedCallback_returnsBeforeJournalInsertCompletes() = runTest {
        val journalDao = BlockingInsertJournalDao()
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
        val adapter = NotificationListenerAdapter(
            enqueueEvent = { command -> enqueuer.enqueue(command) }
        )

        enqueuer.start()

        adapter.onNotificationPosted(notificationCapture(notificationKey = "posted-callback"))

        assertFalse(journalDao.insertStarted.isCompleted)
        assertEquals(emptyList<NotificationProcessingCommand>(), queuedEvents)

        runCurrent()
        assertTrue(journalDao.insertStarted.isCompleted)
        assertEquals(emptyList<NotificationProcessingCommand>(), queuedEvents)

        journalDao.releaseInsert.complete(1L)
        runCurrent()

        assertEquals(listOf("posted-callback"), queuedEvents.map { event -> event.notificationKey })
    }

    @Test
    fun listenerRemovedCallback_returnsBeforeJournalInsertCompletes() = runTest {
        val journalDao = BlockingInsertJournalDao()
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
        val adapter = NotificationListenerAdapter(
            enqueueEvent = { command -> enqueuer.enqueue(command) }
        )

        enqueuer.start()

        adapter.onNotificationRemoved(
            capture = notificationCapture(notificationKey = "removed-callback"),
            systemReason = 2
        )

        assertFalse(journalDao.insertStarted.isCompleted)
        assertEquals(emptyList<NotificationProcessingCommand>(), queuedEvents)

        runCurrent()
        assertTrue(journalDao.insertStarted.isCompleted)
        assertEquals(emptyList<NotificationProcessingCommand>(), queuedEvents)

        journalDao.releaseInsert.complete(1L)
        runCurrent()

        val queuedEvent = queuedEvents.single()
        assertEquals("removed-callback", queuedEvent.notificationKey)
        assertTrue(queuedEvent is NotificationProcessingCommand.Removed)
    }

    @Test
    fun listenerCallback_whenJournalInsertThrows_recordsFailureWithoutCrashingCallback() = runTest {
        val journalDao = ThrowingInsertJournalDao()
        val journalRepository = NotificationEventJournalRepository(
            journalDao = journalDao,
            currentTimeMillis = { 1_000L }
        )
        val queuedEvents = mutableListOf<NotificationProcessingCommand>()
        val statusRecorder = RecordingStatusRecorder()
        val loggedErrors = mutableListOf<String>()
        val enqueuer = NotificationDurableEventEnqueuer(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            journalRepository = journalRepository,
            enqueueCommand = { command ->
                queuedEvents += command
                true
            },
            statusRecorder = statusRecorder,
            logError = { message, _ -> loggedErrors += message }
        )
        val adapter = NotificationListenerAdapter(
            enqueueEvent = { command -> enqueuer.enqueue(command) }
        )

        enqueuer.start()

        adapter.onNotificationPosted(notificationCapture(notificationKey = "failed-callback"))
        runCurrent()

        assertEquals(emptyList<NotificationProcessingCommand>(), queuedEvents)
        assertEquals(
            listOf("Failed to persist event journal for posted notification"),
            statusRecorder.errors
        )
        assertEquals(statusRecorder.errors, loggedErrors)
    }

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

        enqueuer.start()

        assertTrue(enqueuer.enqueue(
            NotificationProcessingCommand.Posted(
                notificationCapture(notificationKey = "posted-key")
            )
        ))
        assertEquals(emptyList<String>(), journalDao.events.map { event -> event.notificationKey })
        assertEquals(emptyList<NotificationProcessingCommand>(), queuedEvents)

        runCurrent()

        assertEquals(listOf(1L), queuedEvents.map { event -> event.eventJournalId })
        assertEquals(listOf("posted-key"), journalDao.events.map { event -> event.notificationKey })
    }

    @Test
    fun enqueue_persistsRemovedEventBeforeQueueingIt() = runTest {
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

        enqueuer.start()

        assertTrue(
            enqueuer.enqueue(
                NotificationProcessingCommand.Removed(
                    NotificationRemovalCommand(
                        capture = notificationCapture(notificationKey = "removed-key"),
                        systemReason = 2
                    )
                )
            )
        )
        assertEquals(emptyList<String>(), journalDao.events.map { event -> event.notificationKey })
        assertEquals(emptyList<NotificationProcessingCommand>(), queuedEvents)

        runCurrent()

        assertEquals(listOf(1L), queuedEvents.map { event -> event.eventJournalId })
        assertEquals(listOf("removed-key"), journalDao.events.map { event -> event.notificationKey })
        assertEquals(
            listOf(NotificationEventJournalEntity.TYPE_REMOVED),
            journalDao.events.map { event -> event.eventType }
        )
        assertEquals(listOf(2), journalDao.events.map { event -> event.systemReason })
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

        enqueuer.start()
        enqueuer.replayPendingEvents()
        runCurrent()

        assertEquals(listOf("first", "second"), queuedEvents.map { event -> event.notificationKey })
        assertEquals(listOf(1L, 2L), queuedEvents.map { event -> event.eventJournalId })
        assertEquals(2, journalDao.events.size)
    }

    @Test
    fun replayPendingEvents_whenReplayAlreadyRunning_doesNotStartConcurrentReplay() = runTest {
        val journalDao = BlockingPendingJournalDao()
        val journalRepository = NotificationEventJournalRepository(
            journalDao = journalDao,
            currentTimeMillis = { 1_000L }
        )
        val enqueuer = durableEnqueuer(
            journalRepository = journalRepository,
            enqueueCommand = { true }
        )

        enqueuer.start()
        enqueuer.replayPendingEvents()
        enqueuer.replayPendingEvents()
        runCurrent()

        assertEquals(1, journalDao.pendingEventsCallCount)

        journalDao.releasePending.complete(emptyList())
        runCurrent()
    }

    @Test
    fun replayPendingEventsAfter_waitsForBackoffBeforeReadingDueRows() = runTest {
        val journalDao = FakeNotificationEventJournalDao()
        val journalRepository = NotificationEventJournalRepository(
            journalDao = journalDao,
            currentTimeMillis = { 2_000L }
        )
        journalDao.events += NotificationEventJournalEntity(
            id = 1L,
            eventType = NotificationEventJournalEntity.TYPE_POSTED,
            notificationKey = "retry-after-backoff",
            packageName = "com.example",
            title = "title",
            text = "text",
            sourcePostTime = 1_000L,
            observedAt = 1_000L,
            flags = 0,
            hasActions = false,
            appLabel = "Example",
            appInfoResolved = false,
            systemReason = null,
            createdAt = 1_000L,
            retryCount = 1,
            lastAttemptAt = 1_000L,
            nextAttemptAt = 2_000L,
            lastError = "database locked"
        )
        val queuedEvents = mutableListOf<NotificationProcessingCommand>()
        val enqueuer = durableEnqueuer(
            journalRepository = journalRepository,
            enqueueCommand = { command ->
                queuedEvents += command
                true
            }
        )

        enqueuer.start()
        enqueuer.replayPendingEventsAfter(delayMillis = 1_000L)
        runCurrent()

        assertEquals(0, journalDao.pendingEventsCallCount)
        assertEquals(emptyList<NotificationProcessingCommand>(), queuedEvents)

        advanceTimeBy(999L)
        runCurrent()
        assertEquals(0, journalDao.pendingEventsCallCount)

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(1, journalDao.pendingEventsCallCount)
        assertEquals(listOf("retry-after-backoff"), queuedEvents.map { event -> event.notificationKey })
    }

    @Test
    fun enqueue_whenProcessingQueueRejectsOverflow_keepsEventJournaledAndDueForReplay() = runTest {
        val journalDao = FakeNotificationEventJournalDao()
        val journalRepository = NotificationEventJournalRepository(
            journalDao = journalDao,
            currentTimeMillis = { 1_000L }
        )
        val attemptedQueueEvents = mutableListOf<NotificationProcessingCommand>()
        val enqueuer = durableEnqueuer(
            journalRepository = journalRepository,
            enqueueCommand = { command ->
                attemptedQueueEvents += command
                false
            }
        )

        enqueuer.start()
        val accepted = enqueuer.enqueue(
            NotificationProcessingCommand.Posted(
                notificationCapture(notificationKey = "overflow-key")
            )
        )
        runCurrent()

        assertTrue(accepted)
        assertEquals(listOf("overflow-key"), journalDao.events.map { event -> event.notificationKey })
        assertEquals(listOf(1L), attemptedQueueEvents.map { event -> event.eventJournalId })
        val pendingEvent = journalDao.events.single()
        assertEquals(0, pendingEvent.retryCount)
        assertNull(pendingEvent.lastAttemptAt)
        assertEquals(1_000L, pendingEvent.nextAttemptAt)
        assertNull(pendingEvent.lastError)
    }

    @Test
    fun replayPendingEvents_whenProcessingQueueRejectsEvent_keepsJournalRowDueForRetry() = runTest {
        val journalDao = FakeNotificationEventJournalDao()
        val journalRepository = NotificationEventJournalRepository(
            journalDao = journalDao,
            currentTimeMillis = { 1_000L }
        )
        journalRepository.journalIfRequired(
            NotificationProcessingCommand.Posted(
                notificationCapture(notificationKey = "pending-key")
            )
        )
        val attemptedQueueEvents = mutableListOf<NotificationProcessingCommand>()
        val enqueuer = durableEnqueuer(
            journalRepository = journalRepository,
            enqueueCommand = { command ->
                attemptedQueueEvents += command
                false
            }
        )

        enqueuer.start()
        enqueuer.replayPendingEvents()
        runCurrent()

        assertEquals(listOf("pending-key"), attemptedQueueEvents.map { event -> event.notificationKey })
        val pendingEvent = journalDao.events.single()
        assertEquals("pending-key", pendingEvent.notificationKey)
        assertEquals(0, pendingEvent.retryCount)
        assertNull(pendingEvent.lastAttemptAt)
        assertEquals(1_000L, pendingEvent.nextAttemptAt)
        assertNull(pendingEvent.lastError)
    }

    @Test
    fun enqueue_whenJournalInsertFails_doesNotQueueUnjournaledForegroundEvent() = runTest {
        val journalDao = FakeNotificationEventJournalDao().apply {
            nextInsertResult = -1L
        }
        val journalRepository = NotificationEventJournalRepository(
            journalDao = journalDao,
            currentTimeMillis = { 1_000L }
        )
        val queuedEvents = mutableListOf<NotificationProcessingCommand>()
        val statusRecorder = RecordingStatusRecorder()
        val loggedErrors = mutableListOf<String>()
        val enqueuer = NotificationDurableEventEnqueuer(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            journalRepository = journalRepository,
            enqueueCommand = { command ->
                queuedEvents += command
                true
            },
            statusRecorder = statusRecorder,
            logError = { message, _ -> loggedErrors += message }
        )

        enqueuer.start()
        val accepted = enqueuer.enqueue(
            NotificationProcessingCommand.Posted(
                notificationCapture(notificationKey = "failed-journal")
            )
        )
        runCurrent()

        assertTrue(accepted)
        assertEquals(emptyList<NotificationProcessingCommand>(), queuedEvents)
        assertEquals(emptyList<String>(), journalDao.events.map { event -> event.notificationKey })
        assertEquals(
            listOf("Failed to persist event journal for posted notification"),
            statusRecorder.errors
        )
        assertEquals(statusRecorder.errors, loggedErrors)
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

        enqueuer.start()
        enqueuer.enqueue(
            NotificationProcessingCommand.Reconcile(
                activeCaptures = emptyList(),
                snapshotCapturedAt = 1_000L
            )
        )
        runCurrent()

        assertEquals(1, queuedEvents.size)
        assertTrue(queuedEvents.single() is NotificationProcessingCommand.Reconcile)
        assertEquals(0, journalDao.events.size)
    }

    @Test
    fun enqueueBeforeStart_isRejectedWithoutJournalWrite() = runTest {
        val journalDao = FakeNotificationEventJournalDao()
        val journalRepository = NotificationEventJournalRepository(
            journalDao = journalDao,
            currentTimeMillis = { 1_000L }
        )
        val statusRecorder = RecordingStatusRecorder()
        val enqueuer = durableEnqueuer(
            journalRepository = journalRepository,
            statusRecorder = statusRecorder,
            enqueueCommand = { true }
        )

        val accepted = enqueuer.enqueue(
            NotificationProcessingCommand.Posted(
                notificationCapture(notificationKey = "before-start")
            )
        )

        assertFalse(accepted)
        assertEquals(
            "Rejected callback events should not synchronously persist status.",
            emptyList<String>(),
            statusRecorder.errors
        )
        runCurrent()

        assertEquals(emptyList<String>(), journalDao.events.map { event -> event.notificationKey })
        assertEquals(
            listOf("Rejected posted notification because journal persistence queue is not running"),
            statusRecorder.errors
        )
    }

    @Test
    fun enqueueBeforeStart_whenStatusPersistenceThrows_doesNotCrashCallback() = runTest {
        val journalDao = FakeNotificationEventJournalDao()
        val journalRepository = NotificationEventJournalRepository(
            journalDao = journalDao,
            currentTimeMillis = { 1_000L }
        )
        val loggedErrors = mutableListOf<String>()
        val enqueuer = NotificationDurableEventEnqueuer(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            journalRepository = journalRepository,
            enqueueCommand = { true },
            statusRecorder = ThrowingStatusRecorder(),
            logError = { message, _ -> loggedErrors += message }
        )

        val accepted = enqueuer.enqueue(
            NotificationProcessingCommand.Posted(
                notificationCapture(notificationKey = "before-start")
            )
        )

        assertFalse(accepted)
        assertEquals(emptyList<String>(), loggedErrors)
        runCurrent()

        assertEquals(emptyList<String>(), journalDao.events.map { event -> event.notificationKey })
        assertEquals(
            listOf(
                "Failed to record journal persistence queue rejection",
                "Rejected posted notification because journal persistence queue is not running"
            ),
            loggedErrors
        )
    }

    @Test
    fun closeAndDrain_persistsAcceptedEventsBeforeRejectingNewCommands() = runTest {
        val journalDao = FakeNotificationEventJournalDao()
        val journalRepository = NotificationEventJournalRepository(
            journalDao = journalDao,
            currentTimeMillis = { 1_000L }
        )
        val queuedEvents = mutableListOf<NotificationProcessingCommand>()
        val statusRecorder = RecordingStatusRecorder()
        val enqueuer = durableEnqueuer(
            journalRepository = journalRepository,
            statusRecorder = statusRecorder,
            enqueueCommand = { command ->
                queuedEvents += command
                true
            }
        )
        var drainedCount = 0
        enqueuer.start()

        assertTrue(
            enqueuer.enqueue(
                NotificationProcessingCommand.Posted(
                    notificationCapture(notificationKey = "before-close")
                )
            )
        )
        enqueuer.closeAndDrain {
            drainedCount += 1
        }
        runCurrent()

        assertEquals(1, drainedCount)
        assertEquals(listOf("before-close"), journalDao.events.map { event -> event.notificationKey })
        assertEquals(listOf(1L), queuedEvents.map { event -> event.eventJournalId })

        assertFalse(
            enqueuer.enqueue(
                NotificationProcessingCommand.Posted(
                    notificationCapture(notificationKey = "after-close")
                )
            )
        )
        assertEquals(listOf("before-close"), journalDao.events.map { event -> event.notificationKey })
    }

    private fun TestScope.durableEnqueuer(
        journalRepository: NotificationEventJournalRepository,
        statusRecorder: RecordingStatusRecorder = RecordingStatusRecorder(),
        enqueueCommand: (NotificationProcessingCommand) -> Boolean
    ): NotificationDurableEventEnqueuer {
        return NotificationDurableEventEnqueuer(
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
            journalRepository = journalRepository,
            enqueueCommand = enqueueCommand,
            statusRecorder = statusRecorder,
            logError = { _, _ -> }
        )
    }

    private class RecordingStatusRecorder : NotificationListenerEventStatusRecorder {
        val errors = mutableListOf<String>()

        override fun markPosted(at: Long) = Unit

        override fun markRemoved(at: Long) = Unit

        override fun markEventQueued() = Unit

        override fun markEventProcessed() = Unit

        override fun markEventFailed(message: String, at: Long) = Unit

        override fun markError(message: String, at: Long) {
            errors += message
        }

        override fun markError(message: String) {
            errors += message
        }
    }

    private class ThrowingStatusRecorder : NotificationListenerEventStatusRecorder {
        override fun markPosted(at: Long) = Unit

        override fun markRemoved(at: Long) = Unit

        override fun markEventQueued() = Unit

        override fun markEventProcessed() = Unit

        override fun markEventFailed(message: String, at: Long) = Unit

        override fun markError(message: String, at: Long) {
            throw IllegalStateException("status persistence failed")
        }

        override fun markError(message: String) {
            throw IllegalStateException("status persistence failed")
        }
    }

    private class BlockingInsertJournalDao : NotificationEventJournalDao {
        val insertStarted = CompletableDeferred<Unit>()
        val releaseInsert = CompletableDeferred<Long>()

        override suspend fun insert(event: NotificationEventJournalEntity): Long {
            insertStarted.complete(Unit)
            return releaseInsert.await()
        }

        override suspend fun pendingEvents(now: Long, limit: Int): List<NotificationEventJournalEntity> {
            return emptyList()
        }

        override suspend fun deleteById(id: Long): Int {
            return 0
        }

        override suspend fun retryCountForId(id: Long): Int? {
            return null
        }

        override suspend fun markAttemptFailed(
            id: Long,
            retryCount: Int,
            lastAttemptAt: Long,
            nextAttemptAt: Long,
            lastError: String?
        ): Int {
            return 0
        }

        override suspend fun deleteCreatedBefore(cutoffTimestamp: Long): Int {
            return 0
        }

        override suspend fun trimToNewest(maxRows: Int): Int {
            return 0
        }
    }

    private class ThrowingInsertJournalDao : NotificationEventJournalDao {
        override suspend fun insert(event: NotificationEventJournalEntity): Long {
            throw IllegalStateException("room write failed")
        }

        override suspend fun pendingEvents(now: Long, limit: Int): List<NotificationEventJournalEntity> {
            return emptyList()
        }

        override suspend fun deleteById(id: Long): Int {
            return 0
        }

        override suspend fun retryCountForId(id: Long): Int? {
            return null
        }

        override suspend fun markAttemptFailed(
            id: Long,
            retryCount: Int,
            lastAttemptAt: Long,
            nextAttemptAt: Long,
            lastError: String?
        ): Int {
            return 0
        }

        override suspend fun deleteCreatedBefore(cutoffTimestamp: Long): Int {
            return 0
        }

        override suspend fun trimToNewest(maxRows: Int): Int {
            return 0
        }
    }

    private class BlockingPendingJournalDao : NotificationEventJournalDao {
        val releasePending = CompletableDeferred<List<NotificationEventJournalEntity>>()
        var pendingEventsCallCount = 0
            private set

        override suspend fun insert(event: NotificationEventJournalEntity): Long {
            return 1L
        }

        override suspend fun pendingEvents(
            now: Long,
            limit: Int
        ): List<NotificationEventJournalEntity> {
            pendingEventsCallCount += 1
            return releasePending.await()
        }

        override suspend fun deleteById(id: Long): Int {
            return 0
        }

        override suspend fun retryCountForId(id: Long): Int? {
            return null
        }

        override suspend fun markAttemptFailed(
            id: Long,
            retryCount: Int,
            lastAttemptAt: Long,
            nextAttemptAt: Long,
            lastError: String?
        ): Int {
            return 0
        }

        override suspend fun deleteCreatedBefore(cutoffTimestamp: Long): Int {
            return 0
        }

        override suspend fun trimToNewest(maxRows: Int): Int {
            return 0
        }
    }
}
