package com.example.pushrecorder.service

import com.example.pushrecorder.appinfo.AppDisplayInfo
import com.example.pushrecorder.data.NotificationCapture
import com.example.pushrecorder.data.NotificationEntity
import com.example.pushrecorder.data.NotificationStatus
import com.example.pushrecorder.data.RemovalReason

internal object NotificationRemovalFinalizer {
    fun finalize(
        command: NotificationRemovalCommand,
        storedNotification: NotificationEntity?,
        appSnapshot: AppDisplayInfo,
        removedAt: Long
    ): FinalizedNotificationRemoval {
        val postedAt = storedNotification?.timestamp ?: command.sourcePostTime
        val timeToRemoval = (removedAt - postedAt).coerceAtLeast(0L)
        val reason = NotificationRemovalClassifier.classify(
            systemReason = command.systemReason,
            timeToRemoval = timeToRemoval
        )

        return FinalizedNotificationRemoval(
            capture = command.capture.copy(
                title = command.title.ifBlank { storedNotification?.title.orEmpty() },
                text = command.text.ifBlank { storedNotification?.text.orEmpty() },
                sourcePostTime = postedAt,
                observedAt = removedAt,
                hasActions = command.hasActions || storedNotification?.hasActions == true,
                appLabel = storedNotification?.appLabel ?: appSnapshot.label,
                appInfoResolved = storedNotification?.appInfoResolved ?: appSnapshot.isResolved
            ),
            status = when (reason) {
                RemovalReason.USER_CLICKED -> NotificationStatus.CLICKED
                else -> NotificationStatus.REMOVED
            },
            reason = reason,
            timeToRemoval = timeToRemoval,
            removedAt = removedAt
        )
    }
}

internal data class FinalizedNotificationRemoval(
    val capture: NotificationCapture,
    val status: NotificationStatus,
    val reason: RemovalReason,
    val timeToRemoval: Long,
    val removedAt: Long
)
