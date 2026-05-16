package com.example.pushrecorder.ui

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushRecorderViewModelCleanupSourceTest {
    @Test
    fun viewModelStartupCleanupUsesSharedSchedulerEntryPoint() {
        val source = readViewModelSource()

        assertTrue(
            "ViewModel should inject the shared storage cleanup scheduler.",
            source.contains("private val storageCleanupScheduler: NotificationStorageCleanupScheduler")
        )
        assertTrue(
            "ViewModel startup cleanup should run through the shared scheduler.",
            source.contains("storageCleanupScheduler.runCleanup(")
        )
        assertTrue(
            "ViewModel cleanup failure should report the shared cleanup failure state.",
            source.contains("listenerStatusRepository.markError(\"Failed to clean notification storage\")")
        )
        assertFalse(
            "ViewModel should not duplicate notification-only retention cleanup rules.",
            source.contains("notificationRepository.deleteExpiredNotifications()")
        )
    }

    private fun readViewModelSource(): String {
        val candidates = listOf(
            Path.of("src/main/java/com/example/pushrecorder/ui/PushRecorderViewModel.kt"),
            Path.of("app/src/main/java/com/example/pushrecorder/ui/PushRecorderViewModel.kt")
        )
        val sourcePath = candidates.firstOrNull(Files::isReadable)
            ?: error("PushRecorderViewModel.kt was not found from known Gradle test working directories")

        return String(Files.readAllBytes(sourcePath))
    }
}
