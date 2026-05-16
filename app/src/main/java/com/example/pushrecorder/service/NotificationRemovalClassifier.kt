package com.example.pushrecorder.service

import android.service.notification.NotificationListenerService
import com.example.pushrecorder.data.RemovalReason

object NotificationRemovalClassifier {
    private const val AUTO_REMOVAL_THRESHOLD = 5_000L

    fun classify(
        systemReason: Int?,
        timeToRemoval: Long
    ): RemovalReason {
        systemReason?.let { reason ->
            return when (reason) {
                NotificationListenerService.REASON_CLICK -> RemovalReason.USER_CLICKED
                NotificationListenerService.REASON_CANCEL,
                NotificationListenerService.REASON_CANCEL_ALL -> RemovalReason.USER_DISMISSED
                NotificationListenerService.REASON_LISTENER_CANCEL,
                NotificationListenerService.REASON_LISTENER_CANCEL_ALL,
                NotificationListenerService.REASON_APP_CANCEL,
                NotificationListenerService.REASON_APP_CANCEL_ALL,
                NotificationListenerService.REASON_ASSISTANT_CANCEL,
                NotificationListenerService.REASON_CHANNEL_BANNED,
                NotificationListenerService.REASON_CHANNEL_REMOVED,
                NotificationListenerService.REASON_CLEAR_DATA,
                NotificationListenerService.REASON_ERROR,
                NotificationListenerService.REASON_GROUP_OPTIMIZATION,
                NotificationListenerService.REASON_GROUP_SUMMARY_CANCELED,
                NotificationListenerService.REASON_LOCKDOWN,
                NotificationListenerService.REASON_PACKAGE_BANNED,
                NotificationListenerService.REASON_PACKAGE_CHANGED,
                NotificationListenerService.REASON_PACKAGE_SUSPENDED,
                NotificationListenerService.REASON_PROFILE_TURNED_OFF,
                NotificationListenerService.REASON_SNOOZED,
                NotificationListenerService.REASON_TIMEOUT,
                NotificationListenerService.REASON_UNAUTOBUNDLED,
                NotificationListenerService.REASON_USER_STOPPED -> RemovalReason.AUTO_REMOVED
                else -> RemovalReason.UNKNOWN
            }
        }

        if (timeToRemoval <= 0L) {
            return RemovalReason.UNKNOWN
        }

        return when {
            timeToRemoval <= AUTO_REMOVAL_THRESHOLD -> RemovalReason.AUTO_REMOVED
            else -> RemovalReason.USER_DISMISSED
        }
    }
}
