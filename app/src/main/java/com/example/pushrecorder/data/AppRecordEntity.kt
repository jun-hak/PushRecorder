package com.example.pushrecorder.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "apps")
data class AppRecordEntity(
    @PrimaryKey
    val packageName: String,
    val label: String,
    val isInstalled: Boolean,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val lastInstalledAt: Long,
    val lastRemovedAt: Long
)
