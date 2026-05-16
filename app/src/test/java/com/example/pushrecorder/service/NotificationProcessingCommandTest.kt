package com.example.pushrecorder.service

import com.example.pushrecorder.data.notificationCapture
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationProcessingCommandTest {
    @Test
    fun postedCommandRepresentsExistingPostedProcessingInput() {
        val capture = notificationCapture(
            notificationKey = "posted-key",
            packageName = "com.example.chat",
            title = "Posted title",
            text = "Posted body",
            sourcePostTime = 1_000L,
            observedAt = 2_000L,
            flags = 8,
            hasActions = true,
            appLabel = "Chat App",
            appInfoResolved = true
        )

        val command = NotificationProcessingCommand.Posted(capture)

        assertEquals("posted-key", command.notificationKey)
        assertEquals("posted notification", command.eventName())
        assertEquals(capture, command.capture)
    }

    @Test
    fun removedCommandRepresentsExistingRemovedProcessingInputs() {
        val capture = notificationCapture(
            notificationKey = "removed-key",
            packageName = "com.example.mail",
            title = "",
            text = "",
            sourcePostTime = 3_000L,
            observedAt = 4_000L,
            flags = 16,
            hasActions = false,
            appLabel = "Mail App",
            appInfoResolved = false
        )
        val removalCommand = NotificationRemovalCommand(
            capture = capture,
            systemReason = 2
        )

        val command = NotificationProcessingCommand.Removed(removalCommand)

        assertEquals("removed-key", command.notificationKey)
        assertEquals("removed notification", command.eventName())
        assertEquals(removalCommand, command.command)
        assertEquals(capture, command.command.capture)
        assertEquals(2, command.command.systemReason)

        val commandWithoutSystemReason = NotificationProcessingCommand.Removed(
            NotificationRemovalCommand(capture = capture)
        )
        assertEquals(null, commandWithoutSystemReason.command.systemReason)
    }

    @Test
    fun reconcileCommandRepresentsExistingActiveLookupProcessingInput() {
        val activeCapture = notificationCapture(
            notificationKey = "active-key",
            packageName = "com.example.active",
            title = "Active title",
            text = "Active body",
            sourcePostTime = 5_000L,
            observedAt = 6_000L,
            flags = 32,
            hasActions = true,
            appLabel = "Active App",
            appInfoResolved = true
        )
        val secondActiveCapture = notificationCapture(
            notificationKey = "second-active-key",
            packageName = "com.example.second",
            title = "Second title",
            text = "Second body",
            sourcePostTime = 7_000L,
            observedAt = 8_000L,
            flags = 64,
            hasActions = false,
            appLabel = "Second App",
            appInfoResolved = false
        )

        val command = NotificationProcessingCommand.Reconcile(
            activeCaptures = listOf(activeCapture, secondActiveCapture)
        )

        assertEquals(NotificationProcessingCommand.RECONCILE_KEY, command.notificationKey)
        assertEquals("active notification reconcile", command.eventName())
        assertEquals(listOf(activeCapture, secondActiveCapture), command.activeCaptures)
    }
}
