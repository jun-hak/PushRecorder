package com.example.pushrecorder.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

internal class NotificationDurableEventEnqueuer(
    private val journalRepository: NotificationEventJournalRepository,
    private val enqueueCommand: (NotificationProcessingCommand) -> Boolean,
    private val statusRecorder: NotificationListenerEventStatusRecorder,
    private val logError: (String, Throwable?) -> Unit
) {
    fun enqueue(command: NotificationProcessingCommand): Boolean {
        val journaledCommand = runCatching {
            runBlocking(Dispatchers.IO) {
                journalRepository.journalIfRequired(command)
            }
        }.getOrElse { error ->
            val message = "Failed to persist event journal for ${command.eventName()}"
            statusRecorder.markError(message)
            logError(message, error)
            command
        }

        return enqueueCommand(journaledCommand)
    }

    fun replayPendingEvents() {
        val pendingEvents = runCatching {
            runBlocking(Dispatchers.IO) {
                journalRepository.pendingEvents()
            }
        }.getOrElse { error ->
            val message = "Failed to read pending notification event journal"
            statusRecorder.markError(message)
            logError(message, error)
            return
        }

        pendingEvents.forEach { event ->
            enqueueCommand(event)
        }
    }
}
