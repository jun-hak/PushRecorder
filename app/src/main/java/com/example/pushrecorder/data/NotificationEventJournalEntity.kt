package com.example.pushrecorder.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "notification_event_journal",
    indices = [
        Index(value = ["notificationKey"]),
        Index(value = ["createdAt"]),
        Index(value = ["nextAttemptAt", "id"])
    ]
)
data class NotificationEventJournalEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val eventType: String,
    val notificationKey: String,
    val packageName: String,
    val title: String,
    val text: String,
    val sourcePostTime: Long,
    val observedAt: Long,
    val flags: Int,
    val hasActions: Boolean,
    val appLabel: String,
    val appInfoResolved: Boolean,
    val systemReason: Int?,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "0")
    val retryCount: Int = 0,
    val lastAttemptAt: Long? = null,
    @ColumnInfo(defaultValue = "0")
    val nextAttemptAt: Long = 0L,
    val lastError: String? = null
) {
    companion object {
        const val TYPE_POSTED = "POSTED"
        const val TYPE_REMOVED = "REMOVED"
    }
}
