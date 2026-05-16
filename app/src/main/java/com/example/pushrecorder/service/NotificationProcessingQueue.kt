package com.example.pushrecorder.service

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
    private val onFailed: (NotificationProcessingCommand, Throwable) -> Unit,
    private val onRejected: (NotificationProcessingCommand, String, Throwable?) -> Unit,
    private val events: Channel<NotificationProcessingCommand> = Channel(Channel.UNLIMITED)
) {
    private val processingMutex = Mutex()
    private var processorJob: Job? = null

    @Volatile
    var isAcceptingEvents: Boolean = true
        private set

    fun start() {
        if (processorJob != null) {
            return
        }

        processorJob = scope.launch {
            for (event in events) {
                runCatching {
                    processingMutex.withLock {
                        processEvent(event)
                    }
                }.onSuccess {
                    onProcessed()
                }.onFailure { error ->
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
            reject(event, "Failed to enqueue ${event.eventName()}", result.exceptionOrNull())
            return false
        }

        onQueued()
        return true
    }

    fun closeAndDrain(onDrained: () -> Unit) {
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
}
