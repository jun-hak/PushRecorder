package com.example.pushrecorder.service

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationStorageCleanupSchedulingDocTest {
    @Test
    fun retentionPolicyDocumentsCleanupSchedulingDecisionAndFallbackTradeoff() {
        val doc = String(Files.readAllBytes(findRetentionPolicyPath()))

        assertTrue(
            "Retention policy should document the chosen cleanup scheduler.",
            doc.contains("`NotificationStorageCleanupScheduler` runs notification and journal cleanup")
        )
        assertTrue(
            "Retention policy should document the WorkManager entry point.",
            doc.contains("`NotificationStorageCleanupWorker` registers unique periodic WorkManager work") &&
                doc.contains("named `notification-storage-cleanup`")
        )
        assertTrue(
            "Retention policy should document shared cleanup policy for WorkManager and lifecycle callbacks.",
            doc.contains("uses the same `NotificationStorageCleanupScheduler`") &&
                doc.contains("one throttling/coalescing policy")
        )
        assertTrue(
            "Retention policy should document the remaining WorkManager timing tradeoff.",
            doc.contains("WorkManager timing is opportunistic") &&
                doc.contains("Android may defer periodic work")
        )
        assertTrue(
            "Retention policy should document cleanup without relying on manual restarts.",
            doc.contains("both deterministic in-process recovery points and a platform") &&
                doc.contains("scheduled fallback")
        )
        assertTrue(
            "Retention policy should keep the cleanup decision tied to existing listener behavior.",
            doc.contains("foreground/listener triggers remain important for prompt cleanup during active recording sessions")
        )
        assertTrue(
            "Retention policy should document cleanup on application startup.",
            doc.contains("It runs on app process startup,") &&
                doc.contains("app foreground entry, service") &&
                doc.contains("after queued event processing") &&
                doc.contains("periodic WorkManager execution")
        )
        assertTrue(
            "Retention policy should document the connectedDebugAndroidTest attempt.",
            doc.contains("`connectedDebugAndroidTest` was attempted") &&
                doc.contains("GRADLE_USER_HOME=\$PWD/.gradle-user-home ./gradlew --no-daemon connectedDebugAndroidTest")
        )
        assertTrue(
            "Retention policy should document why connectedDebugAndroidTest could not run.",
            doc.contains("did not reach device or emulator execution") &&
                doc.contains("UnknownHostException:") &&
                doc.contains("services.gradle.org")
        )
        assertTrue(
            "Retention policy should document that migration and instrumented tests still require device verification.",
            doc.contains("migration and instrumented test changes") &&
                doc.contains("still require a connected-device `connectedDebugAndroidTest` run")
        )
    }

    private fun findRetentionPolicyPath(): Path {
        val candidates = listOf(
            Path.of("docs/notification-storage-retention-policy.md"),
            Path.of("../docs/notification-storage-retention-policy.md")
        )
        return candidates.firstOrNull(Files::isReadable)
            ?: error("docs/notification-storage-retention-policy.md was not found or readable from known Gradle test working directories")
    }
}
