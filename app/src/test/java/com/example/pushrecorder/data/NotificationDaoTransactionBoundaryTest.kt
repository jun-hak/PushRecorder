package com.example.pushrecorder.data

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationDaoTransactionBoundaryTest {
    @Test
    fun syntheticStaleRemovalInsertStaysInsideDaoTransactionBoundary() {
        val daoSource = readSource("NotificationDao.kt")
        val repositorySource = readSource("NotificationRepository.kt")

        assertTrue(
            "Synthetic stale-removal insertion must remain protected by the DAO @Transaction boundary.",
            daoSource.contains("@Transaction\n    suspend fun insertSyntheticRemovalIfStillActive(")
        )
        assertTrue(
            "The DAO transaction should keep the terminal-event guard and synthetic removal insert together.",
            daoSource.contains("hasTerminalEventAfter(") &&
                daoSource.contains("NotificationStatus.REMOVED") &&
                daoSource.contains("removalReason = RemovalReason.UNKNOWN")
        )
        assertTrue(
            "Repository reconcile should delegate synthetic stale removals through the DAO transaction method.",
            repositorySource.contains("notificationDao.insertSyntheticRemovalIfStillActive(")
        )
    }

    private fun readSource(fileName: String): String {
        val candidates = listOf(
            Path.of("src/main/java/com/example/pushrecorder/data/$fileName"),
            Path.of("app/src/main/java/com/example/pushrecorder/data/$fileName")
        )
        val sourcePath = candidates.firstOrNull(Files::exists)
            ?: error("$fileName was not found in known Gradle test working directories")

        return String(Files.readAllBytes(sourcePath))
    }
}
