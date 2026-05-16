package com.example.pushrecorder.service

import com.example.pushrecorder.data.notificationCapture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationProcessingQueueTest {
    @Test
    fun defaultIngestionBoundary_usesBoundedCapacityAndRejectNewestOverflowPolicy() {
        assertEquals(2_048, NotificationIngestionPolicy.JOURNAL_PERSISTENCE_QUEUE_CAPACITY)
        assertTrue(NotificationIngestionPolicy.JOURNAL_PERSISTENCE_QUEUE_CAPACITY != Channel.UNLIMITED)
        assertEquals(
            BufferOverflow.SUSPEND,
            NotificationIngestionPolicy.JOURNAL_PERSISTENCE_QUEUE_BUFFER_OVERFLOW
        )
        assertEquals(2_048, NotificationIngestionPolicy.PROCESSING_QUEUE_CAPACITY)
        assertTrue(NotificationIngestionPolicy.PROCESSING_QUEUE_CAPACITY != Channel.UNLIMITED)
        assertEquals(
            BufferOverflow.SUSPEND,
            NotificationIngestionPolicy.PROCESSING_QUEUE_BUFFER_OVERFLOW
        )
        assertEquals(
            NotificationQueueOverflowPolicy.RejectNewestKeepDurableJournal,
            NotificationIngestionPolicy.PROCESSING_QUEUE_OVERFLOW_POLICY
        )
    }

    @Test
    fun enqueueBeforeStart_isRejectedWithoutMarkingQueued() = runTest {
        val status = RecordingQueueStatus()
        val queue = processingQueue(status = status)

        val accepted = queue.enqueue(postedCommand("before-start"))

        assertFalse(accepted)
        assertEquals(0, status.queuedCount)
        assertEquals(
            listOf("Rejected posted notification because listener queue is not running"),
            status.rejectedMessages
        )
    }

    @Test
    fun enqueue_processesEventsInFifoOrder() = runTest {
        val status = RecordingQueueStatus()
        val processedKeys = mutableListOf<String>()
        val queue = processingQueue(
            status = status,
            processEvent = { event -> processedKeys += event.notificationKey }
        )
        queue.start()

        assertTrue(queue.enqueue(postedCommand("first")))
        assertTrue(queue.enqueue(removedCommand("second")))
        runCurrent()

        assertEquals(listOf("first", "second"), processedKeys)
        assertEquals(2, status.queuedCount)
        assertEquals(2, status.processedCount)
        assertEquals(emptyList<String>(), status.failedKeys)
    }

    @Test
    fun failedEvent_marksFailureAndContinuesWithNextQueuedEvent() = runTest {
        val status = RecordingQueueStatus()
        val processedKeys = mutableListOf<String>()
        val queue = processingQueue(
            status = status,
            processEvent = { event ->
                if (event.notificationKey == "bad") {
                    error("boom")
                }
                processedKeys += event.notificationKey
            }
        )
        queue.start()

        assertTrue(queue.enqueue(postedCommand("bad")))
        assertTrue(queue.enqueue(postedCommand("good")))
        runCurrent()

        assertEquals(listOf("good"), processedKeys)
        assertEquals(2, status.queuedCount)
        assertEquals(1, status.processedCount)
        assertEquals(listOf("bad"), status.failedKeys)
    }

    @Test
    fun cancellation_doesNotMarkEventFailed() = runTest {
        val status = RecordingQueueStatus()
        val queue = processingQueue(
            status = status,
            processEvent = {
                throw CancellationException("service shutting down")
            }
        )
        queue.start()

        assertTrue(queue.enqueue(postedCommand("cancelled")))
        runCurrent()

        assertEquals(1, status.queuedCount)
        assertEquals(0, status.processedCount)
        assertEquals(emptyList<String>(), status.failedKeys)
        assertFalse(queue.enqueue(postedCommand("after-cancel")))
    }

    @Test
    fun start_calledRepeatedlyKeepsSingleProcessor() = runTest {
        val status = RecordingQueueStatus()
        val processedKeys = mutableListOf<String>()
        val queue = processingQueue(
            status = status,
            processEvent = { event -> processedKeys += event.notificationKey }
        )

        queue.start()
        queue.start()
        assertTrue(queue.enqueue(postedCommand("single")))
        runCurrent()

        assertEquals(listOf("single"), processedKeys)
        assertEquals(1, status.processedCount)
    }

    @Test
    fun enqueue_whenQueueIsFull_rejectsNewestEventAndDoesNotMarkQueued() = runTest {
        val status = RecordingQueueStatus()
        val processedKeys = mutableListOf<String>()
        val queue = processingQueue(
            status = status,
            maxQueuedEvents = 1,
            processEvent = { event -> processedKeys += event.notificationKey }
        )
        queue.start()

        assertTrue(queue.enqueue(postedCommand("accepted")))
        assertFalse(queue.enqueue(postedCommand("overflow")))
        runCurrent()

        assertEquals(listOf("accepted"), processedKeys)
        assertEquals(1, status.queuedCount)
        assertEquals(1, status.processedCount)
        assertEquals(
            listOf(
                "Rejected posted notification because listener queue is full " +
                    "(1 events); durable journal will keep it pending"
            ),
            status.rejectedMessages
        )
    }

    @Test
    fun enqueue_whenProcessorIsBackedUp_rejectsBeyondBoundedCapacity() = runTest {
        val status = RecordingQueueStatus()
        val firstEventStarted = CompletableDeferred<Unit>()
        val releaseProcessor = CompletableDeferred<Unit>()
        val processedKeys = mutableListOf<String>()
        val queue = processingQueue(
            status = status,
            maxQueuedEvents = 3,
            maxRejectedMessagesToRecord = 1,
            processEvent = { event ->
                firstEventStarted.complete(Unit)
                releaseProcessor.await()
                processedKeys += event.notificationKey
            }
        )
        queue.start()

        assertTrue(queue.enqueue(postedCommand("in-flight")))
        runCurrent()
        firstEventStarted.await()

        val acceptedWhileBackedUp = (1..5).count { index ->
            queue.enqueue(postedCommand("backlog-$index"))
        }

        assertEquals(3, acceptedWhileBackedUp)
        assertEquals(4, status.queuedCount)
        assertEquals(2, status.rejectedCount)
        assertEquals(
            listOf(
                "Rejected posted notification because listener queue is full " +
                    "(3 events); durable journal will keep it pending"
            ),
            status.rejectedMessages
        )

        releaseProcessor.complete(Unit)
        queue.closeAndDrain {}
        runCurrent()

        assertEquals(
            listOf("in-flight", "backlog-1", "backlog-2", "backlog-3"),
            processedKeys
        )
        assertEquals(4, status.processedCount)
    }

    @Test
    fun enqueue_tenThousandEventBurstIsBoundedByQueueCapacityAndDoesNotCrash() = runTest {
        val status = RecordingQueueStatus()
        val queue = processingQueue(
            status = status,
            maxRejectedMessagesToRecord = 1
        )
        queue.start()

        val acceptedCount = (1..10_000).count { index ->
            queue.enqueue(postedCommand("burst-$index"))
        }

        assertEquals(NotificationIngestionPolicy.PROCESSING_QUEUE_CAPACITY, acceptedCount)
        assertEquals(NotificationIngestionPolicy.PROCESSING_QUEUE_CAPACITY, status.queuedCount)
        assertEquals(
            10_000 - NotificationIngestionPolicy.PROCESSING_QUEUE_CAPACITY,
            status.rejectedCount
        )
        assertEquals(
            listOf(
                "Rejected posted notification because listener queue is full " +
                    "(${NotificationIngestionPolicy.PROCESSING_QUEUE_CAPACITY} events); " +
                    "durable journal will keep it pending"
            ),
            status.rejectedMessages
        )

        queue.closeAndDrain {}
        runCurrent()
        assertEquals(NotificationIngestionPolicy.PROCESSING_QUEUE_CAPACITY, status.processedCount)
    }

    @Test
    fun enqueue_tenThousandEventBurstWhileProcessorIsBlockedRejectsNewestOnly() = runTest {
        val status = RecordingQueueStatus()
        val firstEventStarted = CompletableDeferred<Unit>()
        val releaseProcessor = CompletableDeferred<Unit>()
        val processedKeys = mutableListOf<String>()
        val queue = processingQueue(
            status = status,
            maxQueuedEvents = 4,
            maxRejectedMessagesToRecord = 2,
            processEvent = { event ->
                if (event.notificationKey == "in-flight") {
                    firstEventStarted.complete(Unit)
                    releaseProcessor.await()
                }
                processedKeys += event.notificationKey
            }
        )
        queue.start()

        assertTrue(queue.enqueue(postedCommand("in-flight", eventJournalId = 1L)))
        runCurrent()
        firstEventStarted.await()

        val acceptedKeys = mutableListOf<String>()
        val rejectedKeys = mutableListOf<String>()
        (1..10_000).forEach { index ->
            val key = "burst-$index"
            val accepted = queue.enqueue(postedCommand(key, eventJournalId = index.toLong() + 1L))
            if (accepted) {
                acceptedKeys += key
            } else {
                rejectedKeys += key
            }
        }

        assertEquals(listOf("burst-1", "burst-2", "burst-3", "burst-4"), acceptedKeys)
        assertEquals(9_996, rejectedKeys.size)
        assertEquals("burst-5", rejectedKeys.first())
        assertEquals("burst-10000", rejectedKeys.last())
        assertEquals(5, status.queuedCount)
        assertEquals(9_996, status.rejectedCount)
        assertEquals(
            listOf(
                "Rejected posted notification because listener queue is full " +
                    "(4 events); durable journal will keep it pending",
                "Rejected posted notification because listener queue is full " +
                    "(4 events); durable journal will keep it pending"
            ),
            status.rejectedMessages
        )
        assertEquals(emptyList<String>(), processedKeys)

        releaseProcessor.complete(Unit)
        queue.closeAndDrain {}
        runCurrent()

        assertEquals(listOf("in-flight") + acceptedKeys, processedKeys)
        assertEquals(5, status.processedCount)
    }

    @Test
    fun closeAndDrain_processesQueuedEventsThenRejectsNewEvents() = runTest {
        val status = RecordingQueueStatus()
        val processedKeys = mutableListOf<String>()
        var drainedCount = 0
        val queue = processingQueue(
            status = status,
            processEvent = { event -> processedKeys += event.notificationKey }
        )
        queue.start()

        assertTrue(queue.enqueue(postedCommand("queued-before-close")))
        queue.closeAndDrain {
            drainedCount += 1
        }
        runCurrent()

        assertEquals(listOf("queued-before-close"), processedKeys)
        assertEquals(1, drainedCount)
        assertFalse(queue.enqueue(postedCommand("after-close")))
        assertEquals(
            listOf("Rejected posted notification after listener teardown"),
            status.rejectedMessages
        )
    }

    private fun TestScope.processingQueue(
        status: RecordingQueueStatus,
        maxQueuedEvents: Int = NotificationIngestionPolicy.PROCESSING_QUEUE_CAPACITY,
        maxRejectedMessagesToRecord: Int = Int.MAX_VALUE,
        processEvent: suspend (NotificationProcessingCommand) -> Unit = {}
    ): NotificationProcessingQueue {
        return NotificationProcessingQueue(
            scope = backgroundScope,
            processEvent = processEvent,
            onQueued = { status.queuedCount += 1 },
            onProcessed = { status.processedCount += 1 },
            onFailed = { event, _ -> status.failedKeys += event.notificationKey },
            onRejected = { _, message, _ ->
                status.rejectedCount += 1
                if (status.rejectedMessages.size < maxRejectedMessagesToRecord) {
                    status.rejectedMessages += message
                }
            },
            maxQueuedEvents = maxQueuedEvents
        )
    }

    private fun postedCommand(
        notificationKey: String,
        eventJournalId: Long? = null
    ): NotificationProcessingCommand.Posted {
        return NotificationProcessingCommand.Posted(
            capture = notificationCapture(notificationKey = notificationKey),
            eventJournalId = eventJournalId
        )
    }

    private fun removedCommand(notificationKey: String): NotificationProcessingCommand.Removed {
        return NotificationProcessingCommand.Removed(
            NotificationRemovalCommand(
                capture = notificationCapture(notificationKey = notificationKey)
            )
        )
    }

    private class RecordingQueueStatus {
        var queuedCount = 0
        var processedCount = 0
        var rejectedCount = 0
        val failedKeys = mutableListOf<String>()
        val rejectedMessages = mutableListOf<String>()
    }
}
