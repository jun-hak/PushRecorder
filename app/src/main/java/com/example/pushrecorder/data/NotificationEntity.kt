package com.example.pushrecorder.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Date

@Entity(tableName = "notifications")
data class NotificationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val packageName: String,
    val title: String,
    val text: String,
    val timestamp: Date,
    val status: NotificationStatus
)

enum class NotificationStatus {
    POSTED,
    REMOVED,
    CLICKED
} 