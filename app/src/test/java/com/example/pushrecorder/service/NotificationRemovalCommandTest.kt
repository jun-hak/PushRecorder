package com.example.pushrecorder.service

import com.example.pushrecorder.data.notificationCapture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationRemovalCommandTest {
    @Test
    fun command_mapsCaptureAndSystemReasonFromRemovalCallback() {
        val capture = notificationCapture(
            notificationKey = "removed-key",
            packageName = "com.example.chat",
            title = "Title",
            text = "Body",
            sourcePostTime = 1_000L,
            observedAt = 2_000L,
            flags = 16,
            hasActions = true
        )

        val command = NotificationRemovalCommand(
            capture = capture,
            systemReason = 2
        )

        assertEquals(capture, command.capture)
        assertEquals(2, command.systemReason)
        assertEquals("removed-key", command.notificationKey)
        assertEquals("com.example.chat", command.packageName)
        assertEquals("Title", command.title)
        assertEquals("Body", command.text)
        assertEquals(1_000L, command.sourcePostTime)
        assertEquals(16, command.flags)
        assertEquals(true, command.hasActions)
    }

    @Test
    fun command_defaultsSystemReasonToNullForLegacyRemovalCallback() {
        val command = NotificationRemovalCommand(
            capture = notificationCapture(notificationKey = "legacy-removed-key")
        )

        assertNull(command.systemReason)
        assertEquals("legacy-removed-key", command.notificationKey)
    }

    @Test
    fun command_keepsRequiredCaptureValuesUnchanged() {
        val capture = notificationCapture(
            notificationKey = "required-key",
            packageName = "com.example.required",
            title = "",
            text = "",
            sourcePostTime = 9_000L,
            observedAt = 10_000L,
            flags = 32,
            hasActions = false
        )

        val command = NotificationRemovalCommand(capture = capture)

        assertEquals("required-key", command.capture.notificationKey)
        assertEquals("com.example.required", command.capture.packageName)
        assertEquals("", command.capture.title)
        assertEquals("", command.capture.text)
        assertEquals(9_000L, command.capture.sourcePostTime)
        assertEquals(10_000L, command.capture.observedAt)
        assertEquals(32, command.capture.flags)
        assertEquals(false, command.capture.hasActions)
    }
}
