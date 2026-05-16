package com.example.pushrecorder.service

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationListenerServiceBoundaryTest {
    @Test
    fun serviceSourceOnlyWiresAndroidCallbacksAndDelegatesNotificationProcessing() {
        val source = readServiceSource()

        requiredBoundaryWiring.forEach { wiring ->
            assertTrue(
                "NotificationListenerService should keep Android callbacks wired through $wiring",
                source.contains(wiring)
            )
        }

        forbiddenProcessingHelpers.forEach { helper ->
            assertFalse(
                "NotificationListenerService should not directly invoke $helper; route through boundary collaborators.",
                source.contains(helper)
            )
        }
    }

    @Test
    fun notificationCallbacksStayThinAndOnlyDelegateToCaptureBoundary() {
        val source = readServiceSource()

        assertCallbackBody(
            source = source,
            signature = "override fun onNotificationPosted(sbn: StatusBarNotification)",
            expectedDelegation = "listenerAdapter.onNotificationPosted(sbn.toNotificationCapture())"
        )
        assertCallbackBody(
            source = source,
            signature = "override fun onNotificationRemoved(sbn: StatusBarNotification)",
            expectedDelegation = "listenerAdapter.onNotificationRemoved(sbn.toNotificationCapture(), systemReason = null)"
        )
        assertCallbackBody(
            source = source,
            signature = "override fun onNotificationRemoved( sbn: StatusBarNotification, rankingMap: AndroidNotificationListenerService.RankingMap, reason: Int )",
            expectedDelegation = "listenerAdapter.onNotificationRemoved(sbn.toNotificationCapture(), systemReason = reason)"
        )
    }

    @Test
    fun serviceShutdownDoesNotClearSingletonActiveSnapshots() {
        val source = readServiceSource()

        assertFalse(
            "An old listener instance must not clear singleton active snapshots populated by a newer instance",
            source.contains("clearActiveSnapshots()")
        )
    }

    private fun readServiceSource(): String {
        val candidates = listOf(
            Path.of("src/main/java/com/example/pushrecorder/service/NotificationListenerService.kt"),
            Path.of("app/src/main/java/com/example/pushrecorder/service/NotificationListenerService.kt")
        )
        val sourcePath = candidates.firstOrNull(Files::exists)
            ?: error("NotificationListenerService.kt was not found in known Gradle test working directories")

        return String(Files.readAllBytes(sourcePath))
    }

    private fun assertCallbackBody(
        source: String,
        signature: String,
        expectedDelegation: String
    ) {
        val normalizedSource = source.replace(Regex("\\s+"), " ")
        val normalizedSignature = signature.replace(Regex("\\s+"), " ")
        val startIndex = normalizedSource.indexOf(normalizedSignature)
        assertTrue(
            "NotificationListenerService should declare callback $signature",
            startIndex >= 0
        )
        val bodyStart = normalizedSource.indexOf("{", startIndex)
        val bodyEnd = normalizedSource.indexOf("}", bodyStart)
        val body = normalizedSource.substring(bodyStart + 1, bodyEnd).trim()

        assertEquals(
            "NotificationListenerService callback $signature should stay a single boundary delegation",
            expectedDelegation,
            body
        )
    }

    private companion object {
        private val requiredBoundaryWiring = listOf(
            "class NotificationListenerService : AndroidNotificationListenerService()",
            "notificationEventProcessor: NotificationEventProcessor",
            "private val listenerAdapter: NotificationListenerAdapter by lazy",
            "NotificationListenerAdapter(",
            "NotificationReconciler(",
            "listenerAdapter.onNotificationPosted(sbn.toNotificationCapture())",
            "listenerAdapter.onNotificationRemoved(sbn.toNotificationCapture(), systemReason = null)",
            "listenerAdapter.onNotificationRemoved(sbn.toNotificationCapture(), systemReason = reason)",
            "listenerConnectionAdapter.onListenerConnected()",
            "listenerConnectionAdapter.onListenerDisconnected()"
        )

        private val forbiddenProcessingHelpers = listOf(
            "NotificationContentExtractor",
            "NotificationRemovalClassifier",
            "ActiveNotificationLookup",
            ".processPosted(",
            ".processRemoved(",
            ".recordPosted(",
            ".recordRemoved(",
            "runBlocking("
        )
    }
}
