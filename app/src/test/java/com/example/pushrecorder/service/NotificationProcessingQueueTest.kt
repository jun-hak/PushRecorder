package com.example.pushrecorder.service

import com.example.pushrecorder.data.notificationCapture
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
        processEvent: suspend (NotificationProcessingCommand) -> Unit = {}
    ): NotificationProcessingQueue {
        return NotificationProcessingQueue(
            scope = backgroundScope,
            processEvent = processEvent,
            onQueued = { status.queuedCount += 1 },
            onProcessed = { status.processedCount += 1 },
            onFailed = { event, _ -> status.failedKeys += event.notificationKey },
            onRejected = { _, message, _ -> status.rejectedMessages += message }
        )
    }

    private fun postedCommand(notificationKey: String): NotificationProcessingCommand.Posted {
        return NotificationProcessingCommand.Posted(
            notificationCapture(notificationKey = notificationKey)
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
        val failedKeys = mutableListOf<String>()
        val rejectedMessages = mutableListOf<String>()
    }
}
