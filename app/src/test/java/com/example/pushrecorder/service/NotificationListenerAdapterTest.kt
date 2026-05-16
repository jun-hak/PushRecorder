package com.example.pushrecorder.service

import com.example.pushrecorder.data.notificationCapture
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NotificationListenerAdapterTest {
    private val enqueuedEvents = mutableListOf<NotificationProcessingCommand>()
    private val adapter = NotificationListenerAdapter(
        enqueueEvent = { event -> enqueuedEvents += event }
    )

    @Test
    fun foregroundCallbackPathDoesNotUseRunBlockingOrLaunchPerCallbackWork() {
        val source = readAdapterSource()

        assertFalse(
            "NotificationListenerAdapter callback path must not import or call runBlocking",
            source.contains("runBlocking")
        )
        assertFalse(
            "NotificationListenerAdapter should preserve callback order by enqueueing synchronously",
            source.contains("launch(")
        )
    }

    @Test
    fun onNotificationPosted_enqueuesPostedCommandSynchronously() {
        val capture = notificationCapture(
            notificationKey = "posted-key",
            packageName = "com.example.chat"
        )

        adapter.onNotificationPosted(capture)

        assertEquals(listOf(NotificationProcessingCommand.Posted(capture)), enqueuedEvents)
    }

    @Test
    fun onNotificationRemoved_enqueuesRemovedCommandWithSystemReasonSynchronously() {
        val capture = notificationCapture(
            notificationKey = "removed-key",
            packageName = "com.example.mail"
        )

        adapter.onNotificationRemoved(
            capture = capture,
            systemReason = 2
        )

        assertEquals(
            listOf(
                NotificationProcessingCommand.Removed(
                    NotificationRemovalCommand(
                        capture = capture,
                        systemReason = 2
                    )
                )
            ),
            enqueuedEvents
        )
    }

    @Test
    fun backToBackCallbacks_areEnqueuedInAndroidCallbackOrder() {
        val postedCapture = notificationCapture(notificationKey = "ordered-key")
        val removedCapture = notificationCapture(notificationKey = "ordered-key")

        adapter.onNotificationPosted(postedCapture)
        adapter.onNotificationRemoved(
            capture = removedCapture,
            systemReason = null
        )

        assertEquals(
            listOf(
                NotificationProcessingCommand.Posted(postedCapture),
                NotificationProcessingCommand.Removed(
                    NotificationRemovalCommand(capture = removedCapture)
                )
            ),
            enqueuedEvents
        )
    }

    private fun readAdapterSource(): String {
        val candidates = listOf(
            Path.of("src/main/java/com/example/pushrecorder/service/NotificationListenerAdapter.kt"),
            Path.of("app/src/main/java/com/example/pushrecorder/service/NotificationListenerAdapter.kt")
        )
        val sourcePath = candidates.firstOrNull(Files::exists)
            ?: error("NotificationListenerAdapter.kt was not found in known Gradle test working directories")

        return String(Files.readAllBytes(sourcePath))
    }
}
