package com.example.pushrecorder.data

object NotificationRetentionPolicy {
    private const val RETENTION_DAYS = 90L
    private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L

    fun cutoffTimestamp(now: Long = System.currentTimeMillis()): Long {
        return now - RETENTION_DAYS * MILLIS_PER_DAY
    }
}
