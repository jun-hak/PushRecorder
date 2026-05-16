package com.example.pushrecorder.service

import android.service.notification.NotificationListenerService
import com.example.pushrecorder.data.RemovalReason
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationRemovalClassifierTest {
    @Test
    fun reasonClick_mapsToUserClicked() {
        assertEquals(
            RemovalReason.USER_CLICKED,
            NotificationRemovalClassifier.classify(
                systemReason = NotificationListenerService.REASON_CLICK,
                timeToRemoval = 100L
            )
        )
    }

    @Test
    fun cancelReasons_mapToUserDismissed() {
        val cancelReasons = listOf(
            NotificationListenerService.REASON_CANCEL,
            NotificationListenerService.REASON_CANCEL_ALL
        )

        cancelReasons.forEach { reason ->
            assertEquals(
                RemovalReason.USER_DISMISSED,
                NotificationRemovalClassifier.classify(
                    systemReason = reason,
                    timeToRemoval = 100L
                )
            )
        }
    }

    @Test
    fun systemReasons_mapToAutoRemoved() {
        val systemReasons = listOf(
            NotificationListenerService.REASON_APP_CANCEL,
            NotificationListenerService.REASON_APP_CANCEL_ALL,
            NotificationListenerService.REASON_TIMEOUT
        )

        systemReasons.forEach { reason ->
            assertEquals(
                RemovalReason.AUTO_REMOVED,
                NotificationRemovalClassifier.classify(
                    systemReason = reason,
                    timeToRemoval = 100L
                )
            )
        }
    }

    @Test
    fun missingReason_usesTimeFallbacks() {
        assertEquals(
            RemovalReason.UNKNOWN,
            NotificationRemovalClassifier.classify(systemReason = null, timeToRemoval = 0L)
        )
        assertEquals(
            RemovalReason.AUTO_REMOVED,
            NotificationRemovalClassifier.classify(systemReason = null, timeToRemoval = 5_000L)
        )
        assertEquals(
            RemovalReason.USER_DISMISSED,
            NotificationRemovalClassifier.classify(systemReason = null, timeToRemoval = 5_001L)
        )
    }
}
