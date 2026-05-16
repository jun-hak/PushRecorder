package com.example.pushrecorder

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertTrue
import org.junit.Test

class PushRecorderApplicationCleanupTest {
    @Test
    fun applicationStartupRequestsStorageCleanupThroughScheduler() {
        val source = readApplicationSource()

        assertTrue(
            "Application should inject the storage cleanup scheduler.",
            source.contains("lateinit var storageCleanupScheduler: NotificationStorageCleanupScheduler")
        )
        assertTrue(
            "Application should own an IO application scope for background cleanup.",
            source.contains("CoroutineScope(SupervisorJob() + Dispatchers.IO)")
        )
        assertTrue(
            "Application startup should request cleanup without waiting for listener restart.",
            source.contains("override fun onCreate()") &&
                source.contains("registerScheduledStorageCleanup()") &&
                source.contains("requestStorageCleanup(force = true)") &&
                source.contains("storageCleanupScheduler.requestCleanup(")
        )
        assertTrue(
            "Application should register the periodic WorkManager cleanup entry point.",
            source.contains("NotificationStorageCleanupWorker.enqueuePeriodic(this)")
        )
        assertTrue(
            "Startup cleanup should force one process-start request through the throttled scheduler.",
            source.contains("force = true") &&
                source.contains("force = force")
        )
        assertTrue(
            "Application should register an activity-start cleanup entry point.",
            source.contains("registerActivityLifecycleCallbacks(") &&
                source.contains("override fun onActivityStarted(activity: Activity)") &&
                source.contains("requestStorageCleanup()")
        )
        assertSourceOrder(
            source = source,
            first = "super.onCreate()",
            second = "registerStorageCleanupEntryPoint()",
            message = "Application should register cleanup during normal process startup."
        )
        assertSourceOrder(
            source = source,
            first = "registerStorageCleanupEntryPoint()",
            second = "registerScheduledStorageCleanup()",
            message = "Application should register scheduled cleanup during normal process startup."
        )
        assertSourceOrder(
            source = source,
            first = "registerScheduledStorageCleanup()",
            second = "requestStorageCleanup(force = true)",
            message = "Application should trigger cleanup during normal process startup."
        )
    }

    private fun readApplicationSource(): String {
        val candidates = listOf(
            Path.of("src/main/java/com/example/pushrecorder/PushRecorderApplication.kt"),
            Path.of("app/src/main/java/com/example/pushrecorder/PushRecorderApplication.kt")
        )
        val sourcePath = candidates.firstOrNull(Files::exists)
            ?: error("PushRecorderApplication.kt was not found from known Gradle test working directories")

        return String(Files.readAllBytes(sourcePath))
    }

    private fun assertSourceOrder(
        source: String,
        first: String,
        second: String,
        message: String
    ) {
        val firstIndex = source.indexOf(first)
        val secondIndex = source.indexOf(second)

        assertTrue("$message Missing source anchor: $first", firstIndex >= 0)
        assertTrue("$message Missing source anchor: $second", secondIndex >= 0)
        assertTrue(message, firstIndex < secondIndex)
    }
}
