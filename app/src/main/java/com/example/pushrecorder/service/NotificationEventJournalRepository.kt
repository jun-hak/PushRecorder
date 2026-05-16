package com.example.pushrecorder.service

import com.example.pushrecorder.data.NotificationCapture
import com.example.pushrecorder.data.NotificationEventJournalDao
import com.example.pushrecorder.data.NotificationEventJournalEntity
import com.example.pushrecorder.data.NotificationEventJournalRetentionPolicy
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationEventJournalRepository internal constructor(
    private val journalDao: NotificationEventJournalDao,
    private val currentTimeMillis: () -> Long,
    private val retentionPolicy: NotificationEventJournalRetentionPolicy =
        NotificationEventJournalRetentionPolicy.Default
) {
    @Inject
    constructor(journalDao: NotificationEventJournalDao) : this(
        journalDao = journalDao,
        currentTimeMillis = System::currentTimeMillis,
        retentionPolicy = NotificationEventJournalRetentionPolicy.Default
    )

    suspend fun journalIfRequired(
        command: NotificationProcessingCommand
    ): NotificationProcessingCommand {
        if (command.eventJournalId != null || command is NotificationProcessingCommand.Reconcile) {
            return command
        }

        val journalId = journalDao.insert(command.toJournalEntity(createdAt = currentTimeMillis()))
        if (journalId <= 0L) {
            error("Failed to persist notification event journal for ${command.eventName()}")
        }

        return command.withEventJournalId(journalId)
    }

    suspend fun pendingEvents(): List<NotificationProcessingCommand> {
        return journalDao.pendingEvents(
            now = currentTimeMillis(),
            limit = retentionPolicy.replayBatchSize
        ).mapNotNull { entity ->
            entity.toProcessingCommand()
        }
    }

    suspend fun acknowledge(command: NotificationProcessingCommand) {
        val journalId = command.eventJournalId ?: return
        journalDao.deleteById(journalId)
    }

    suspend fun markProcessingFailed(
        command: NotificationProcessingCommand,
        error: Throwable
    ): Long? {
        val journalId = command.eventJournalId ?: return null
        val nextRetryCount = (journalDao.retryCountForId(journalId) ?: return null) + 1
        val now = currentTimeMillis()
        val nextAttemptAt = retentionPolicy.nextAttemptTimestamp(
            retryCount = nextRetryCount,
            now = now
        )
        journalDao.markAttemptFailed(
            id = journalId,
            retryCount = nextRetryCount,
            lastAttemptAt = now,
            nextAttemptAt = nextAttemptAt,
            lastError = error.message?.take(MAX_LAST_ERROR_LENGTH)
        )
        return (nextAttemptAt - now).coerceAtLeast(0L)
    }

    suspend fun deleteExpiredPendingEvents(): Int {
        val ageDeleted = journalDao.deleteCreatedBefore(
            retentionPolicy.cutoffTimestamp(currentTimeMillis())
        )
        val countDeleted = journalDao.trimToNewest(retentionPolicy.maxPendingRows)
        return ageDeleted + countDeleted
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
            createdAt = createdAt,
            nextAttemptAt = createdAt
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

    private companion object {
        const val MAX_LAST_ERROR_LENGTH = 512
    }
}
