package com.example.pushrecorder.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationCaptureTest {
    @Test
    fun toEntity_preservesCaptureSnapshotFields() {
        val capture = notificationCapture(
            notificationKey = "capture-key",
            packageName = "com.example.capture",
            title = "Captured title",
            text = "Captured text",
            sourcePostTime = 123L,
            observedAt = 456L,
            flags = 7,
            hasActions = true,
            appLabel = "Captured App",
            appInfoResolved = true
        )

        val entity = capture.toEntity(
            status = NotificationStatus.CLICKED,
            removalReason = RemovalReason.USER_CLICKED,
            timeToRemoval = 333L,
            removedAt = 789L
        )

        assertEquals("capture-key", entity.notificationKey)
        assertEquals("com.example.capture", entity.packageName)
        assertEquals("Captured title", entity.title)
        assertEquals("Captured text", entity.text)
        assertEquals(123L, entity.timestamp)
        assertEquals(456L, entity.observedAt)
        assertEquals("Captured App", entity.appLabel)
        assertTrue(entity.appInfoResolved)
        assertEquals(NotificationStatus.CLICKED, entity.status)
        assertEquals(7, entity.flags)
        assertTrue(entity.hasActions)
        assertEquals(RemovalReason.USER_CLICKED, entity.removalReason)
        assertEquals(333L, entity.timeToRemoval)
        assertEquals(789L, entity.removedAt)
    }
}
