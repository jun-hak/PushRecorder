package com.example.pushrecorder.data

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationDaoIndexContractTest {
    @Test
    fun notificationStorageEntitiesDeclareRequiredIngestionAndLifecycleIndexes() {
        val entitySource = readSource("NotificationEntity.kt").normalizedWhitespace()
        val journalEntitySource = readSource("NotificationEventJournalEntity.kt").normalizedWhitespace()

        requiredNotificationIndexes.forEach { requiredIndex ->
            val indexDeclaration = requiredIndex.entityDeclaration()
            assertTrue(
                "NotificationEntity should declare $indexDeclaration.",
                entitySource.contains(indexDeclaration)
            )
        }
        requiredNotificationEventJournalIndexes.forEach { requiredIndex ->
            val indexDeclaration = requiredIndex.entityDeclaration()
            assertTrue(
                "NotificationEventJournalEntity should declare $indexDeclaration.",
                journalEntitySource.contains(indexDeclaration)
            )
        }
    }

    @Test
    fun exportedSchemaDeclaresRequiredNotificationStorageIndexes() {
        val schemaSource = readSchema("11.json").normalizedWhitespace()

        (requiredNotificationIndexes + requiredNotificationEventJournalIndexes).forEach { requiredIndex ->
            assertTrue(
                "Room schema should declare ${requiredIndex.databaseName}.",
                schemaSource.contains(requiredIndex.schemaDeclaration())
            )
        }
    }

    @Test
    fun migrationsCreateRequiredNotificationStorageIndexesForExistingInstalls() {
        val databaseSource = readSource("AppDatabase.kt").normalizedWhitespace()

        (requiredNotificationIndexes + requiredNotificationEventJournalIndexes).forEach { requiredIndex ->
            assertTrue(
                "AppDatabase migrations should create ${requiredIndex.databaseName} for existing installs.",
                databaseSource.contains(requiredIndex.migrationDeclaration())
            )
        }
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

    private fun readSchema(fileName: String): String {
        val candidates = listOf(
            Path.of("schemas/com.example.pushrecorder.data.AppDatabase/$fileName"),
            Path.of("app/schemas/com.example.pushrecorder.data.AppDatabase/$fileName")
        )
        val schemaPath = candidates.firstOrNull(Files::exists)
            ?: error("$fileName was not found in known Gradle test working directories")

        return String(Files.readAllBytes(schemaPath))
    }

    private fun String.normalizedWhitespace(): String =
        replace(Regex("\\s+"), " ")

    private data class RequiredIndex(
        val tableName: String,
        val columns: List<String>,
        val unique: Boolean = false
    ) {
        val databaseName: String = "index_${tableName}_${columns.joinToString("_")}"

        fun entityDeclaration(): String {
            val value = columns.joinToString(", ") { "\"$it\"" }
            val uniqueSuffix = if (unique) ", unique = true" else ""
            return "Index(value = [$value]$uniqueSuffix)"
        }

        fun migrationDeclaration(): String {
            val uniquePrefix = if (unique) "UNIQUE " else ""
            return "CREATE ${uniquePrefix}INDEX IF NOT EXISTS $databaseName ON $tableName(${columns.joinToString(", ")})"
        }

        fun schemaDeclaration(): String {
            val uniqueValue = if (unique) "true" else "false"
            val columnNames = columns.joinToString(", ") { "\"$it\"" }
            return "\"name\": \"$databaseName\", \"unique\": $uniqueValue, \"columnNames\": [ $columnNames ]"
        }
    }

    private companion object {
        val requiredNotificationIndexes = listOf(
            RequiredIndex(tableName = "notifications", columns = listOf("notificationKey", "id")),
            RequiredIndex(tableName = "notifications", columns = listOf("notificationKey", "status", "id")),
            RequiredIndex(tableName = "notifications", columns = listOf("packageName", "observedAt", "id")),
            RequiredIndex(tableName = "notifications", columns = listOf("packageName", "id")),
            RequiredIndex(tableName = "notifications", columns = listOf("packageName", "status", "notificationKey", "id")),
            RequiredIndex(tableName = "notifications", columns = listOf("status", "notificationKey", "id")),
            RequiredIndex(tableName = "notifications", columns = listOf("eventJournalId"), unique = true)
        )

        val requiredNotificationEventJournalIndexes = listOf(
            RequiredIndex(tableName = "notification_event_journal", columns = listOf("notificationKey")),
            RequiredIndex(tableName = "notification_event_journal", columns = listOf("createdAt")),
            RequiredIndex(tableName = "notification_event_journal", columns = listOf("nextAttemptAt", "id"))
        )
    }
}
