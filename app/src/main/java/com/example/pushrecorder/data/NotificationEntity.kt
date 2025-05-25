package com.example.pushrecorder.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "notifications")
data class NotificationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val packageName: String,
    val title: String,
    val text: String,
    val timestamp: Long,
    val status: NotificationStatus,
    val flags: Int = 0,
    val hasActions: Boolean = false,
    val removalReason: RemovalReason = RemovalReason.UNKNOWN,
    val timeToRemoval: Long = 0 // milliseconds
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