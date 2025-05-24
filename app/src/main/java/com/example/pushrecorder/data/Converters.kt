package com.example.pushrecorder.data

import androidx.room.TypeConverter
import java.util.Date

class Converters {
    @TypeConverter
    fun fromTimestamp(value: Long?): Date? {
        return value?.let { Date(it) }
    }

    @TypeConverter
    fun dateToTimestamp(date: Date?): Long? {
        return date?.time
    }

    @TypeConverter
    fun fromNotificationStatus(status: NotificationStatus): String {
        return status.name
    }

    @TypeConverter
    fun toNotificationStatus(status: String): NotificationStatus {
        return NotificationStatus.valueOf(status)
    }
} 