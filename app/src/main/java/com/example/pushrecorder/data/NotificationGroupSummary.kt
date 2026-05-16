package com.example.pushrecorder.data

import androidx.room.Embedded

data class NotificationGroupSummary(
    @Embedded val latestNotification: NotificationEntity,
    val notificationCount: Int
)
