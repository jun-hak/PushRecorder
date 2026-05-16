package com.example.pushrecorder.service

import com.example.pushrecorder.data.NotificationEventJournalEntity
import com.example.pushrecorder.data.NotificationEventJournalRetentionPolicy
import com.example.pushrecorder.data.notificationCapture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationPendingJournalRowsTest {
    @Test
    fun replayPendingRows_whenProcessingSucceeds_acknowledgesJournalRow() = runTest {
        val harness = pendingJournalHarness()
        harness.journalPosted("success-key")
        harness.start()

        harness.replayPendingEvents()
        runCurrent()

        assertEquals(listOf("success-key"), harness.processedKeys)
        assertEquals(emptyList<String>(), harness.pendingKeys())
    }

    @Test
    fun replayPendingRows_whenProcessingFails_keepsRowAndSchedulesRetry() = runTest {
        val harness = pendingJournalHarness(failingKeys = setOf("failure-key"))
        harness.journalPosted("failure-key")
        harness.start()

        harness.replayPendingEvents()
        runCurrent()

        assertEquals(emptyList<String>(), harness.processedKeys)
        assertEquals(listOf("failure-key"), harness.failedKeys)
        assertEquals(listOf("failure-key"), harness.pendingKeys())
        val pending = harness.pendingRows().single()
        assertEquals(1, pending.retryCount)
        assertEquals(1_000L, pending.lastAttemptAt)
        assertEquals(2_000L, pending.nextAttemptAt)
        assertEquals("forced failure", pending.lastError)
    }

    @Test
    fun replayPendingPostedAndRemovedRows_whenProcessingFails_marksEachRowForRetryWithoutAck() = runTest {
        val harness = pendingJournalHarness(
            failingKeys = setOf("posted-failure", "removed-failure")
        )
        harness.journalPosted("posted-failure")
        harness.journalRemoved("removed-failure")
        harness.start()

        harness.replayPendingEvents()
        runCurrent()

        assertEquals(emptyList<String>(), harness.processedKeys)
        assertEquals(listOf("posted-failure", "removed-failure"), harness.failedKeys)
        assertEquals(listOf("posted-failure", "removed-failure"), harness.pendingKeys())
        assertEquals(0, harness.deleteByIdCallCount)
        assertEquals(2, harness.markAttemptFailedCallCount)
        harness.pendingRows().forEach { pending ->
            assertEquals(1, pending.retryCount)
            assertEquals(1_000L, pending.lastAttemptAt)
            assertEquals(2_000L, pending.nextAttemptAt)
            assertEquals("forced failure", pending.lastError)
        }
    }

    @Test
    fun retryPendingRows_areSkippedUntilNextAttemptThenAcknowledgedOnSuccess() = runTest {
        val harness = pendingJournalHarness(failingKeys = setOf("retry-key"))
        harness.journalPosted("retry-key")
        harness.start()

        harness.replayPendingEvents()
        runCurrent()
        harness.clearFailures()

        harness.replayPendingEvents()
        runCurrent()

        assertEquals(emptyList<String>(), harness.processedKeys)
        assertEquals(listOf("retry-key"), harness.pendingKeys())

        harness.advanceJournalClockTo(2_000L)
        harness.replayPendingEvents()
        runCurrent()

        assertEquals(listOf("retry-key"), harness.processedKeys)
        assertEquals(emptyList<String>(), harness.pendingKeys())
    }

    @Test
    fun persistedRetryRows_whenDueReplaySuccessfully_areProcessedAndAcknowledged() = runTest {
        val harness = pendingJournalHarness(currentTimeMillis = 5_000L)
        harness.addPendingRows(
            journalEntity(
                id = 41L,
                notificationKey = "persisted-retry-posted",
                createdAt = 1_000L,
                retryCount = 2,
                lastAttemptAt = 3_000L,
                nextAttemptAt = 5_000L,
                lastError = "database locked"
            ),
            journalEntity(
                id = 42L,
                eventType = NotificationEventJournalEntity.TYPE_REMOVED,
                notificationKey = "persisted-retry-removed",
                createdAt = 1_000L,
                retryCount = 1,
                lastAttemptAt = 4_000L,
                nextAttemptAt = 4_500L,
                lastError = "queue worker failed"
            )
        )
        harness.start()

        harness.replayPendingEvents()
        runCurrent()

        assertEquals(
            listOf("persisted-retry-posted", "persisted-retry-removed"),
            harness.processedKeys
        )
        assertEquals(listOf(41L, 42L), harness.processedJournalIds)
        assertEquals(emptyList<String>(), harness.pendingKeys())
        assertEquals(2, harness.deleteByIdCallCount)
        assertEquals(0, harness.markAttemptFailedCallCount)
        assertEquals(2, harness.queuedCount)
        assertEquals(2, harness.processedCount)
    }

    @Test
    fun replayPendingRows_whenProcessingQueueIsFull_keepsRejectedRowPendingWithoutAckOrRetry() = runTest {
        val firstEventStarted = CompletableDeferred<Unit>()
        val releaseProcessor = CompletableDeferred<Unit>()
        val harness = pendingJournalHarness(
            maxQueuedEvents = 1,
            processEvent = { command ->
                firstEventStarted.complete(Unit)
                releaseProcessor.await()
                command.notificationKey
            }
        )
        harness.journalPosted("in-flight")
        harness.journalPosted("backlog")
        harness.journalPosted("queue-full")
        harness.start()

        harness.replayPendingEvents()
        runCurrent()
        firstEventStarted.await()

        assertEquals(emptyList<String>(), harness.processedKeys)
        assertEquals(listOf("in-flight", "backlog", "queue-full"), harness.pendingKeys())
        assertEquals(0, harness.deleteByIdCallCount)
        assertEquals(0, harness.markAttemptFailedCallCount)
        assertEquals(2, harness.queuedCount)
        assertEquals(0, harness.processedCount)
        assertEquals(0, harness.failedKeys.size)
        assertEquals(
            listOf(
                "Rejected posted notification because listener queue is full " +
                    "(1 events); durable journal will keep it pending"
            ),
            harness.rejectedMessages
        )
        harness.pendingRows().forEach { pending ->
            assertEquals(0, pending.retryCount)
            assertEquals(pending.createdAt, pending.nextAttemptAt)
            assertNull(pending.lastAttemptAt)
            assertNull(pending.lastError)
        }

        releaseProcessor.complete(Unit)
        harness.closeAndDrain()
        runCurrent()
    }

    @Test
    fun replayPendingRows_appliesRetentionBeforeQueueingRows() = runTest {
        val harness = pendingJournalHarness(
            currentTimeMillis = 100_000L,
            policy = NotificationEventJournalRetentionPolicy(
                maxPendingAgeMillis = 10_000L,
                maxPendingRows = 2,
                replayBatchSize = 10,
                retryBaseDelayMillis = 1_000L,
                retryMaxDelayMillis = 60_000L
            )
        )
        harness.addPendingRows(
            journalEntity(id = 1L, notificationKey = "expired", createdAt = 89_999L),
            journalEntity(id = 2L, notificationKey = "old-retained", createdAt = 90_000L),
            journalEntity(id = 3L, notificationKey = "newer", createdAt = 95_000L),
            journalEntity(id = 4L, notificationKey = "newest", createdAt = 99_000L)
        )
        harness.start()

        harness.replayPendingEvents()
        runCurrent()

        assertEquals(listOf("newer", "newest"), harness.processedKeys)
        assertEquals(emptyList<String>(), harness.pendingKeys())
    }

    private fun TestScope.pendingJournalHarness(
        currentTimeMillis: Long = 1_000L,
        failingKeys: Set<String> = emptySet(),
        policy: NotificationEventJournalRetentionPolicy = NotificationEventJournalRetentionPolicy(
            maxPendingAgeMillis = 10_000L,
            maxPendingRows = 100,
            replayBatchSize = 100,
            retryBaseDelayMillis = 1_000L,
            retryMaxDelayMillis = 60_000L
        ),
        maxQueuedEvents: Int = NotificationIngestionPolicy.PROCESSING_QUEUE_CAPACITY,
        processEvent: (suspend (NotificationProcessingCommand) -> String)? = null
    ): PendingJournalHarness {
        return PendingJournalHarness(
            scope = this,
            initialCurrentTimeMillis = currentTimeMillis,
            initialFailingKeys = failingKeys,
            policy = policy,
            maxQueuedEvents = maxQueuedEvents,
            processEventOverride = processEvent
        )
    }

    private class PendingJournalHarness(
        scope: TestScope,
        initialCurrentTimeMillis: Long,
        initialFailingKeys: Set<String>,
        policy: NotificationEventJournalRetentionPolicy,
        maxQueuedEvents: Int,
        private val processEventOverride: (suspend (NotificationProcessingCommand) -> String)?
    ) {
        private val journalDao = FakeNotificationEventJournalDao()
        private var currentTimeMillis = initialCurrentTimeMillis
        private val failingKeys = initialFailingKeys.toMutableSet()
        private val journalRepository = NotificationEventJournalRepository(
            journalDao = journalDao,
            currentTimeMillis = { currentTimeMillis },
            retentionPolicy = policy
        )
        private val queue = NotificationProcessingQueue(
            scope = scope.backgroundScope,
            processEvent = { command -> processEvent(command) },
            onQueued = { queuedCount += 1 },
            onProcessed = { processedCount += 1 },
            onFailed = { command, error ->
                failedKeys += command.notificationKey
                journalRepository.markProcessingFailed(command, error)
            },
            onRejected = { _, message, _ -> rejectedMessages += message },
            maxQueuedEvents = maxQueuedEvents
        )
        private val enqueuer = NotificationDurableEventEnqueuer(
            scope = scope.backgroundScope,
            dispatcher = StandardTestDispatcher(scope.testScheduler),
            journalRepository = journalRepository,
            enqueueCommand = { command -> queue.enqueue(command) },
            statusRecorder = NoOpStatusRecorder,
            logError = { message, error -> throw AssertionError(message, error) }
        )

        val processedKeys = mutableListOf<String>()
        val processedJournalIds = mutableListOf<Long?>()
        val failedKeys = mutableListOf<String>()
        val rejectedMessages = mutableListOf<String>()
        var queuedCount = 0
        var processedCount = 0
        val deleteByIdCallCount: Int
            get() = journalDao.deleteByIdCallCount
        val markAttemptFailedCallCount: Int
            get() = journalDao.markAttemptFailedCallCount

        fun start() {
            queue.start()
            enqueuer.start()
        }

        suspend fun journalPosted(notificationKey: String) {
            journalRepository.journalIfRequired(
                NotificationProcessingCommand.Posted(
                    notificationCapture(notificationKey = notificationKey)
                )
            )
        }

        suspend fun journalRemoved(notificationKey: String) {
            journalRepository.journalIfRequired(
                NotificationProcessingCommand.Removed(
                    NotificationRemovalCommand(
                        capture = notificationCapture(notificationKey = notificationKey)
                    )
                )
            )
        }

        fun addPendingRows(vararg rows: NotificationEventJournalEntity) {
            journalDao.events += rows
        }

        fun replayPendingEvents() {
            enqueuer.replayPendingEvents()
        }

        fun closeAndDrain() {
            enqueuer.closeAndDrain {}
            queue.closeAndDrain {}
        }

        fun clearFailures() {
            failingKeys.clear()
        }

        fun advanceJournalClockTo(timeMillis: Long) {
            currentTimeMillis = timeMillis
        }

        fun pendingKeys(): List<String> {
            return pendingRows().map(NotificationEventJournalEntity::notificationKey)
        }

        fun pendingRows(): List<NotificationEventJournalEntity> {
            return journalDao.events.toList()
        }

        private suspend fun processEvent(command: NotificationProcessingCommand) {
            if (command.notificationKey in failingKeys) {
                error("forced failure")
            }
            processedKeys += processEventOverride?.invoke(command) ?: command.notificationKey
            processedJournalIds += command.eventJournalId
            journalRepository.acknowledge(command)
        }
    }

    private object NoOpStatusRecorder : NotificationListenerEventStatusRecorder {
        override fun markPosted(at: Long) = Unit
        override fun markRemoved(at: Long) = Unit
        override fun markEventQueued() = Unit
        override fun markEventProcessed() = Unit
        override fun markEventFailed(message: String, at: Long) = Unit
        override fun markError(message: String, at: Long) = Unit
        override fun markError(message: String) = Unit
    }
}

private fun journalEntity(
    id: Long,
    eventType: String = NotificationEventJournalEntity.TYPE_POSTED,
    notificationKey: String,
    createdAt: Long,
    retryCount: Int = 0,
    lastAttemptAt: Long? = null,
    nextAttemptAt: Long = createdAt,
    lastError: String? = null
): NotificationEventJournalEntity {
    return NotificationEventJournalEntity(
        id = id,
        eventType = eventType,
        notificationKey = notificationKey,
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
        createdAt = createdAt,
        retryCount = retryCount,
        lastAttemptAt = lastAttemptAt,
        nextAttemptAt = nextAttemptAt,
        lastError = lastError
    )
}
