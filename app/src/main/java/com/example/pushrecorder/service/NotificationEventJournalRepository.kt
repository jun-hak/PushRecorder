package com.example.pushrecorder.service

import com.example.pushrecorder.data.NotificationCapture
import com.example.pushrecorder.data.NotificationEventJournalDao
import com.example.pushrecorder.data.NotificationEventJournalEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationEventJournalRepository internal constructor(
    private val journalDao: NotificationEventJournalDao,
    private val currentTimeMillis: () -> Long
) {
    @Inject
    constructor(journalDao: NotificationEventJournalDao) : this(
        journalDao = journalDao,
        currentTimeMillis = System::currentTimeMillis
    )

    suspend fun journalIfRequired(
        command: NotificationProcessingCommand
    ): NotificationProcessingCommand {
        if (command.eventJournalId != null || command is NotificationProcessingCommand.Reconcile) {
            return command
        }

        val journalId = journalDao.insert(command.toJournalEntity(createdAt = currentTimeMillis()))
        if (journalId <= 0L) {
            return command
        }

        return command.withEventJournalId(journalId)
    }

    suspend fun pendingEvents(): List<NotificationProcessingCommand> {
        return journalDao.pendingEvents().mapNotNull { entity ->
            entity.toProcessingCommand()
        }
    }

    suspend fun acknowledge(command: NotificationProcessingCommand) {
        val journalId = command.eventJournalId ?: return
        journalDao.deleteById(journalId)
    }

    private fun NotificationProcessingCommand.toJournalEntity(
        createdAt: Long
    ): NotificationEventJournalEntity {
        return when (this) {
            is NotificationProcessingCommand.Posted -> capture.toJournalEntity(
                eventType = NotificationEventJournalEntity.TYPE_POSTED,
                systemReason = null,
                createdAt = createdAt
            )
            is NotificationProcessingCommand.Removed -> command.capture.toJournalEntity(
                eventType = NotificationEventJournalEntity.TYPE_REMOVED,
                systemReason = command.systemReason,
                createdAt = createdAt
            )
            is NotificationProcessingCommand.Reconcile -> error("Reconcile events are not durable journal events")
        }
    }

    private fun NotificationCapture.toJournalEntity(
        eventType: String,
        systemReason: Int?,
        createdAt: Long
    ): NotificationEventJournalEntity {
        return NotificationEventJournalEntity(
            eventType = eventType,
            notificationKey = notificationKey,
            packageName = packageName,
            title = title,
            text = text,
            sourcePostTime = sourcePostTime,
            observedAt = observedAt,
            flags = flags,
            hasActions = hasActions,
            appLabel = appLabel,
            appInfoResolved = appInfoResolved,
            systemReason = systemReason,
            createdAt = createdAt
        )
    }

    private fun NotificationEventJournalEntity.toProcessingCommand(): NotificationProcessingCommand? {
        val capture = NotificationCapture(
            notificationKey = notificationKey,
            packageName = packageName,
            title = title,
            text = text,
            sourcePostTime = sourcePostTime,
            observedAt = observedAt,
            flags = flags,
            hasActions = hasActions,
            appLabel = appLabel,
            appInfoResolved = appInfoResolved
        )

        return when (eventType) {
            NotificationEventJournalEntity.TYPE_POSTED -> NotificationProcessingCommand.Posted(
                capture = capture,
                eventJournalId = id
            )
            NotificationEventJournalEntity.TYPE_REMOVED -> NotificationProcessingCommand.Removed(
                command = NotificationRemovalCommand(
                    capture = capture,
                    systemReason = systemReason
                ),
                eventJournalId = id
            )
            else -> null
        }
    }
}
