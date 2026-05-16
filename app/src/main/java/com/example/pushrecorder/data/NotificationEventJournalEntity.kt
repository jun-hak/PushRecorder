package com.example.pushrecorder.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "notification_event_journal",
    indices = [
        Index(value = ["notificationKey"]),
        Index(value = ["createdAt"])
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
    val createdAt: Long
) {
    companion object {
        const val TYPE_POSTED = "POSTED"
        const val TYPE_REMOVED = "REMOVED"
    }
}
