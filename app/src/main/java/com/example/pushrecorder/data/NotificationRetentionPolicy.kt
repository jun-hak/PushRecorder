package com.example.pushrecorder.data

/**
 * Retention settings for historical notification event rows in the append-only
 * POSTED/REMOVED `notifications` event log.
 */
data class NotificationRetentionPolicy(
    val maxAgeMillis: Long? = DEFAULT_RETENTION_MILLIS,
    val maxRows: Int? = DEFAULT_MAX_ROWS,
    val maxEvents: Int? = null
) {
    init {
        require(maxAgeMillis == null || maxAgeMillis > 0L) {
            "maxAgeMillis must be positive when configured."
        }
        require(maxRows == null || maxRows > 0) {
            "maxRows must be positive when configured."
        }
        require(maxEvents == null || maxEvents > 0) {
            "maxEvents must be positive when configured."
        }
        require(maxAgeMillis != null || maxRows != null || maxEvents != null) {
            "At least one retention limit must be configured."
        }
    }

    fun cutoffTimestamp(now: Long = System.currentTimeMillis()): Long? {
        return maxAgeMillis?.let { ageMillis -> now - ageMillis }
    }

    val hasCountLimit: Boolean
        get() = maxRows != null || maxEvents != null

    val strictestCountLimit: Int?
        get() = listOfNotNull(maxRows, maxEvents).minOrNull()

    val maxHistoricalEventRows: Int?
        get() = maxRows

    companion object {
        const val DEFAULT_HISTORICAL_EVENT_RETENTION_DAYS = 90L
        private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
        const val DEFAULT_MAX_HISTORICAL_EVENT_ROWS = 200_000
        const val DEFAULT_RETENTION_DAYS = DEFAULT_HISTORICAL_EVENT_RETENTION_DAYS
        const val DEFAULT_MAX_ROWS = DEFAULT_MAX_HISTORICAL_EVENT_ROWS
        val DEFAULT_RETENTION_MILLIS = DEFAULT_HISTORICAL_EVENT_RETENTION_DAYS * MILLIS_PER_DAY
        val Default = NotificationRetentionPolicy()

        fun cutoffTimestamp(now: Long = System.currentTimeMillis()): Long {
            return Default.cutoffTimestamp(now) ?: error("Default retention policy must have a time limit.")
        }
    }
}
