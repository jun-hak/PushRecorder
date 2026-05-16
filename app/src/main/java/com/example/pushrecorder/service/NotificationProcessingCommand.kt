package com.example.pushrecorder.service

import com.example.pushrecorder.data.NotificationCapture

sealed interface NotificationProcessingCommand {
    val notificationKey: String
    val eventJournalId: Long?

    data class Posted(
        val capture: NotificationCapture,
        override val eventJournalId: Long? = null
    ) : NotificationProcessingCommand {
        override val notificationKey: String = capture.notificationKey
    }

    data class Removed(
        val command: NotificationRemovalCommand,
        override val eventJournalId: Long? = null
    ) : NotificationProcessingCommand {
        override val notificationKey: String = command.notificationKey
    }

    data class Reconcile(
        val activeCaptures: List<NotificationCapture>,
        val targetPackageName: String? = null,
        val snapshotCapturedAt: Long = Long.MAX_VALUE
    ) : NotificationProcessingCommand {
        override val notificationKey: String = RECONCILE_KEY
        override val eventJournalId: Long? = null
    }

    companion object {
        const val RECONCILE_KEY = "reconcile"
    }
}

internal fun NotificationProcessingCommand.withEventJournalId(
    eventJournalId: Long
): NotificationProcessingCommand {
    return when (this) {
        is NotificationProcessingCommand.Posted -> copy(eventJournalId = eventJournalId)
        is NotificationProcessingCommand.Removed -> copy(eventJournalId = eventJournalId)
        is NotificationProcessingCommand.Reconcile -> this
    }
}

sealed interface NotificationProcessingResult {
    data class Posted(
        val observedAt: Long
    ) : NotificationProcessingResult

    data class Removed(
        val removedAt: Long
    ) : NotificationProcessingResult
}

typealias NotificationReconcileEvent = NotificationProcessingCommand.Reconcile

internal fun NotificationProcessingCommand.eventName(): String {
    return when (this) {
        is NotificationProcessingCommand.Posted -> "posted notification"
        is NotificationProcessingCommand.Removed -> "removed notification"
        is NotificationProcessingCommand.Reconcile -> "active notification reconcile"
    }
}
