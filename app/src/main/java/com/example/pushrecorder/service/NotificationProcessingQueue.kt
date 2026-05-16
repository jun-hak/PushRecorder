package com.example.pushrecorder.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class NotificationProcessingQueue(
    private val scope: CoroutineScope,
    private val processEvent: suspend (NotificationProcessingCommand) -> Unit,
    private val onQueued: () -> Unit,
    private val onProcessed: () -> Unit,
    private val onFailed: suspend (NotificationProcessingCommand, Throwable) -> Unit,
    private val onRejected: (NotificationProcessingCommand, String, Throwable?) -> Unit,
    private val maxQueuedEvents: Int = NotificationIngestionPolicy.PROCESSING_QUEUE_CAPACITY,
    private val overflowPolicy: NotificationQueueOverflowPolicy =
        NotificationIngestionPolicy.PROCESSING_QUEUE_OVERFLOW_POLICY,
    private val events: Channel<NotificationProcessingCommand> = Channel(
        capacity = maxQueuedEvents,
        onBufferOverflow = NotificationIngestionPolicy.PROCESSING_QUEUE_BUFFER_OVERFLOW
    )
) {
    private val processingMutex = Mutex()
    private var processorJob: Job? = null

    @Volatile
    var isAcceptingEvents: Boolean = true
        private set

    @Synchronized
    fun start() {
        if (processorJob != null) {
            return
        }

        processorJob = scope.launch {
            for (event in events) {
                try {
                    processingMutex.withLock {
                        processEvent(event)
                    }
                    onProcessed()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    onFailed(event, error)
                }
            }
        }
    }

    fun enqueue(event: NotificationProcessingCommand): Boolean {
        if (!isAcceptingEvents) {
            reject(event, "Rejected ${event.eventName()} after listener teardown")
            return false
        }

        if (processorJob?.isActive != true) {
            reject(event, "Rejected ${event.eventName()} because listener queue is not running")
            return false
        }

        val result = events.trySend(event)
        if (result.isFailure) {
            reject(event, overflowMessage(event), result.exceptionOrNull())
            return false
        }

        onQueued()
        return true
    }

    @Synchronized
    fun closeAndDrain(onDrained: () -> Unit) {
        if (!isAcceptingEvents) {
            if (processorJob?.isActive != true) {
                onDrained()
            }
            return
        }
        isAcceptingEvents = false
        events.close()
        val job = processorJob
        if (job == null) {
            onDrained()
            return
        }

        job.invokeOnCompletion {
            onDrained()
        }
    }

    private fun reject(
        event: NotificationProcessingCommand,
        message: String,
        error: Throwable? = null
    ) {
        onRejected(event, message, error)
    }

    private fun overflowMessage(event: NotificationProcessingCommand): String {
        return when (overflowPolicy) {
            NotificationQueueOverflowPolicy.RejectNewestKeepDurableJournal -> {
                "Rejected ${event.eventName()} because listener queue is full " +
                    "($maxQueuedEvents events); durable journal will keep it pending"
            }
        }
    }
}
