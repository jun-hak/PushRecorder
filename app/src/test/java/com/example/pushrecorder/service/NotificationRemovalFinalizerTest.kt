package com.example.pushrecorder.service

import android.service.notification.NotificationListenerService
import com.example.pushrecorder.appinfo.AppDisplayInfo
import com.example.pushrecorder.data.FakeNotificationDao
import com.example.pushrecorder.data.NotificationStatus
import com.example.pushrecorder.data.NotificationRepository
import com.example.pushrecorder.data.RemovalReason
import com.example.pushrecorder.data.notificationCapture
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationRemovalFinalizerTest {
    @Test
    fun finalize_withStoredNotification_preservesStoredIdentityAndClassifiesClick() {
        val storedNotification = notificationCapture(
            notificationKey = "stored-key",
            packageName = "com.example.chat",
            title = "Stored title",
            text = "Stored text",
            sourcePostTime = 1_000L,
            observedAt = 2_000L,
            flags = 4,
            hasActions = true,
            appLabel = "Stored Chat",
            appInfoResolved = false
        ).toEntity(status = NotificationStatus.POSTED)
        val command = NotificationRemovalCommand(
            capture = notificationCapture(
                notificationKey = "stored-key",
                packageName = "com.example.chat",
                title = "",
                text = "",
                sourcePostTime = 9_000L,
                observedAt = 3_000L,
                flags = 16,
                hasActions = false,
                appLabel = "Callback Chat",
                appInfoResolved = true
            ),
            systemReason = NotificationListenerService.REASON_CLICK
        )

        val removal = NotificationRemovalFinalizer.finalize(
            command = command,
            storedNotification = storedNotification,
            appSnapshot = appSnapshot(label = "Resolved Chat", isResolved = true),
            removedAt = 5_000L
        )

        assertEquals("Stored title", removal.capture.title)
        assertEquals("Stored text", removal.capture.text)
        assertEquals(1_000L, removal.capture.sourcePostTime)
        assertEquals(5_000L, removal.capture.observedAt)
        assertEquals(16, removal.capture.flags)
        assertTrue(removal.capture.hasActions)
        assertEquals("Stored Chat", removal.capture.appLabel)
        assertFalse(removal.capture.appInfoResolved)
        assertEquals(NotificationStatus.CLICKED, removal.status)
        assertEquals(RemovalReason.USER_CLICKED, removal.reason)
        assertEquals(4_000L, removal.timeToRemoval)
        assertEquals(5_000L, removal.removedAt)
    }

    @Test
    fun finalize_withoutStoredNotification_usesCallbackCaptureAndResolvedAppSnapshot() {
        val command = NotificationRemovalCommand(
            capture = notificationCapture(
                notificationKey = "direct-key",
                packageName = "com.example.direct",
                title = "Direct title",
                text = "Direct text",
                sourcePostTime = 2_000L,
                observedAt = 5_000L,
                flags = 64,
                hasActions = true,
                appLabel = "Callback Direct",
                appInfoResolved = false
            )
        )

        val removal = NotificationRemovalFinalizer.finalize(
            command = command,
            storedNotification = null,
            appSnapshot = appSnapshot(label = "Resolved Direct", isResolved = true),
            removedAt = 6_000L
        )

        assertEquals("Direct title", removal.capture.title)
        assertEquals("Direct text", removal.capture.text)
        assertEquals(2_000L, removal.capture.sourcePostTime)
        assertEquals(6_000L, removal.capture.observedAt)
        assertEquals(64, removal.capture.flags)
        assertTrue(removal.capture.hasActions)
        assertEquals("Resolved Direct", removal.capture.appLabel)
        assertTrue(removal.capture.appInfoResolved)
        assertEquals(NotificationStatus.REMOVED, removal.status)
        assertEquals(RemovalReason.AUTO_REMOVED, removal.reason)
        assertEquals(4_000L, removal.timeToRemoval)
        assertEquals(6_000L, removal.removedAt)
    }

    @Test
    fun finalize_clampsNegativeRemovalDurationToZero() {
        val removal = NotificationRemovalFinalizer.finalize(
            command = NotificationRemovalCommand(
                capture = notificationCapture(
                    notificationKey = "clock-key",
                    sourcePostTime = 10_000L
                )
            ),
            storedNotification = null,
            appSnapshot = appSnapshot(),
            removedAt = 8_000L
        )

        assertEquals(0L, removal.timeToRemoval)
        assertEquals(RemovalReason.UNKNOWN, removal.reason)
    }

    @Test
    fun syntheticStaleRemoval_usesSameStoredSourcePostTimeAsNormalRemovalFinalization() = runTest {
        val notificationDao = FakeNotificationDao()
        val repository = NotificationRepository(
            notificationDao = notificationDao,
            currentTimeMillis = { 30_000L }
        )
        val postedCapture = notificationCapture(
            notificationKey = "same-record-key",
            packageName = "com.example.same",
            title = "Same record title",
            text = "Same record body",
            sourcePostTime = 1_000L,
            observedAt = 8_000L,
            flags = 32,
            hasActions = true,
            appLabel = "Stored App",
            appInfoResolved = true
        )
        repository.recordPosted(postedCapture)
        val storedNotification = notificationDao.notifications.single()
        val removedAt = 9_000L
        val normalRemoval = NotificationRemovalFinalizer.finalize(
            command = NotificationRemovalCommand(
                capture = postedCapture.copy(
                    sourcePostTime = 7_000L,
                    observedAt = removedAt
                )
            ),
            storedNotification = storedNotification,
            appSnapshot = appSnapshot(label = "Resolved App", isResolved = true),
            removedAt = removedAt
        )

        repository.reconcileActiveNotifications(
            activeNotificationKeys = emptySet(),
            reconciledAt = removedAt
        )

        val syntheticRemoval = notificationDao.notifications.single { notification ->
            notification.notificationKey == "same-record-key" &&
                notification.status == NotificationStatus.REMOVED
        }
        assertEquals(normalRemoval.capture.sourcePostTime, syntheticRemoval.timestamp)
        assertEquals(normalRemoval.timeToRemoval, syntheticRemoval.timeToRemoval)
        assertEquals(8_000L, syntheticRemoval.timeToRemoval)
        assertEquals(removedAt, syntheticRemoval.observedAt)
        assertEquals(removedAt, syntheticRemoval.removedAt)
    }

    private fun appSnapshot(
        packageName: String = "com.example.source",
        label: String = "Resolved App",
        isResolved: Boolean = true
    ): AppDisplayInfo {
        return AppDisplayInfo(
            packageName = packageName,
            label = label,
            icon = null,
            isResolved = isResolved
        )
    }
}
