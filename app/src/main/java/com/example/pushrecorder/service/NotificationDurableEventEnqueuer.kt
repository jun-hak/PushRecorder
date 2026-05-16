package com.example.pushrecorder.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

internal class NotificationDurableEventEnqueuer(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val journalRepository: NotificationEventJournalRepository,
    private val enqueueCommand: (NotificationProcessingCommand) -> Boolean,
    private val statusRecorder: NotificationListenerEventStatusRecorder,
    private val logError: (String, Throwable?) -> Unit,
    private val maxQueuedEvents: Int = NotificationIngestionPolicy.JOURNAL_PERSISTENCE_QUEUE_CAPACITY,
    private val commands: Channel<NotificationProcessingCommand> = Channel(
        capacity = maxQueuedEvents,
        onBufferOverflow = NotificationIngestionPolicy.JOURNAL_PERSISTENCE_QUEUE_BUFFER_OVERFLOW
    )
) {
    private var journalJob: Job? = null
    private var replayJob: Job? = null
    private var scheduledReplayJob: Job? = null

    @Volatile
    private var isAcceptingEvents: Boolean = true

    @Synchronized
    fun start() {
        if (journalJob != null) {
            return
        }

        journalJob = scope.launch(dispatcher) {
            for (command in commands) {
                persistThenEnqueue(command)
            }
        }
    }

    fun enqueue(command: NotificationProcessingCommand): Boolean {
        if (!isAcceptingEvents) {
            reject(command, "Rejected ${command.eventName()} after journal persistence teardown")
            return false
        }

        if (journalJob?.isActive != true) {
            reject(command, "Rejected ${command.eventName()} because journal persistence queue is not running")
            return false
        }

        val result = commands.trySend(command)
        if (result.isFailure) {
            reject(
                command = command,
                message = "Rejected ${command.eventName()} because journal persistence queue is full " +
                    "($maxQueuedEvents events)",
                error = result.exceptionOrNull()
            )
            return false
        }

        return true
    }

    fun replayPendingEvents() {
        startReplay(delayMillis = 0L)
    }

    fun replayPendingEventsAfter(delayMillis: Long) {
        startReplay(delayMillis = delayMillis.coerceAtLeast(0L))
    }

    @Synchronized
    private fun startReplay(delayMillis: Long) {
        if (!isAcceptingEvents) {
            return
        }

        if (replayJob?.isActive == true) {
            return
        }

        if (delayMillis > 0L) {
            if (scheduledReplayJob?.isActive == true) {
                return
            }
            scheduledReplayJob = scope.launch(dispatcher) {
                delay(delayMillis)
                startReplay(delayMillis = 0L)
            }
            return
        }

        replayJob = scope.launch(dispatcher) {
            replayDuePendingEvents()
        }
    }

    @Synchronized
    fun closeAndDrain(onDrained: () -> Unit) {
        if (!isAcceptingEvents) {
            if (listOf(journalJob, replayJob, scheduledReplayJob).none { job -> job?.isActive == true }) {
                onDrained()
            }
            return
        }
        isAcceptingEvents = false
        commands.close()
        scheduledReplayJob?.cancel()
        val activeJobs = listOfNotNull(journalJob, replayJob)
            .filter { job -> job.isActive }
        if (activeJobs.isEmpty()) {
            onDrained()
            return
        }

        val remainingJobs = AtomicInteger(activeJobs.size)
        activeJobs.forEach { job ->
            job.invokeOnCompletion {
                if (remainingJobs.decrementAndGet() == 0) {
                    onDrained()
                }
            }
        }
    }

    private suspend fun persistThenEnqueue(command: NotificationProcessingCommand) {
        val journaledCommand = runCatching {
            journalRepository.journalIfRequired(command)
        }.getOrElse { error ->
            if (error is CancellationException) {
                throw error
            }
            val message = "Failed to persist event journal for ${command.eventName()}"
            statusRecorder.markError(message)
            logError(message, error)
            return
        }

        enqueueJournaledCommand(journaledCommand)
    }

    private suspend fun enqueueJournaledCommand(command: NotificationProcessingCommand) {
        if (enqueueCommand(command)) {
            return
        }

        // The processing queue rejected the command before any downstream work
        // ran. Already-journaled POSTED/REMOVED rows must stay pending exactly as
        // they are so startup replay can retry them; processing-failure metadata
        // is reserved for commands that were accepted by the queue and then
        // failed while being processed.
        if (command.eventJournalId == null) {
            logError(
                "Dropped non-journaled ${command.eventName()} after downstream queue rejection",
                null
            )
        }
    }

    private suspend fun replayDuePendingEvents() {
        val pendingEvents = runCatching {
            journalRepository.deleteExpiredPendingEvents()
            journalRepository.pendingEvents()
        }.getOrElse { error ->
            if (error is CancellationException) {
                throw error
            }
            val message = "Failed to read pending notification event journal"
            statusRecorder.markError(message)
            logError(message, error)
            return
        }

        pendingEvents.forEach { event ->
            enqueueJournaledCommand(event)
        }
    }

    private fun reject(
        command: NotificationProcessingCommand,
        message: String,
        error: Throwable? = null
    ) {
        scope.launch(dispatcher) {
            runCatching {
                statusRecorder.markError(message)
            }.onFailure { statusError ->
                logError("Failed to record journal persistence queue rejection", statusError)
            }
            logError(message, error)
        }
    }
}
