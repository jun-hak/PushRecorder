package com.example.pushrecorder.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "notifications",
    indices = [
        Index(value = ["notificationKey"]),
        Index(value = ["packageName", "observedAt"]),
        Index(value = ["observedAt"]),
        Index(value = ["status"]),
        Index(value = ["eventJournalId"], unique = true)
    ]
)
data class NotificationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(defaultValue = "''")
    val notificationKey: String,
    val packageName: String,
    val title: String,
    val text: String,
    val timestamp: Long,
    @ColumnInfo(defaultValue = "0")
    val observedAt: Long,
    @ColumnInfo(defaultValue = "''")
    val appLabel: String,
    @ColumnInfo(defaultValue = "0")
    val appInfoResolved: Boolean,
    val status: NotificationStatus,
    @ColumnInfo(defaultValue = "0")
    val flags: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val hasActions: Boolean = false,
    @ColumnInfo(defaultValue = "'UNKNOWN'")
    val removalReason: RemovalReason = RemovalReason.UNKNOWN,
    @ColumnInfo(defaultValue = "0")
    val timeToRemoval: Long = 0, // milliseconds
    @ColumnInfo(defaultValue = "0")
    val removedAt: Long = 0,
    val eventJournalId: Long? = null
)

enum class NotificationStatus {
    POSTED,
    REMOVED,
    CLICKED
}

enum class RemovalReason {
    UNKNOWN,
    USER_CLICKED,
    USER_DISMISSED,
    AUTO_REMOVED
}
