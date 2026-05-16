package com.example.pushrecorder.data

data class NotificationEventJournalRetentionPolicy(
    val maxPendingAgeMillis: Long = DEFAULT_PENDING_RETENTION_MILLIS,
    val maxPendingRows: Int = DEFAULT_MAX_PENDING_ROWS,
    val replayBatchSize: Int = DEFAULT_REPLAY_BATCH_SIZE,
    val retryBaseDelayMillis: Long = DEFAULT_RETRY_BASE_DELAY_MILLIS,
    val retryMaxDelayMillis: Long = DEFAULT_RETRY_MAX_DELAY_MILLIS
) {
    init {
        require(maxPendingAgeMillis > 0L) {
            "maxPendingAgeMillis must be positive."
        }
        require(maxPendingRows > 0) {
            "maxPendingRows must be positive."
        }
        require(replayBatchSize > 0) {
            "replayBatchSize must be positive."
        }
        require(retryBaseDelayMillis > 0L) {
            "retryBaseDelayMillis must be positive."
        }
        require(retryMaxDelayMillis >= retryBaseDelayMillis) {
            "retryMaxDelayMillis must be greater than or equal to retryBaseDelayMillis."
        }
    }

    fun cutoffTimestamp(now: Long = System.currentTimeMillis()): Long {
        return now - maxPendingAgeMillis
    }

    fun nextAttemptTimestamp(
        retryCount: Int,
        now: Long = System.currentTimeMillis()
    ): Long {
        require(retryCount > 0) {
            "retryCount must be positive when scheduling a retry."
        }

        val multiplier = 1L shl minOf(retryCount - 1, MAX_BACKOFF_SHIFT)
        val delayMillis = retryBaseDelayMillis.saturatingMultiply(multiplier)
            .coerceAtMost(retryMaxDelayMillis)
        return now.saturatingAdd(delayMillis)
    }

    companion object {
        const val DEFAULT_PENDING_RETENTION_DAYS = 7L
        private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
        const val DEFAULT_MAX_PENDING_ROWS = 50_000
        const val DEFAULT_REPLAY_BATCH_SIZE = 2_000
        const val DEFAULT_RETRY_BASE_DELAY_MILLIS = 1_000L
        const val DEFAULT_RETRY_MAX_DELAY_MILLIS = 5L * 60L * 1000L
        const val DEFAULT_PENDING_RETENTION_MILLIS = DEFAULT_PENDING_RETENTION_DAYS * MILLIS_PER_DAY
        val Default = NotificationEventJournalRetentionPolicy()

        private const val MAX_BACKOFF_SHIFT = 20
    }
}

private fun Long.saturatingMultiply(other: Long): Long {
    if (this == 0L || other == 0L) return 0L
    return if (this > Long.MAX_VALUE / other) Long.MAX_VALUE else this * other
}

private fun Long.saturatingAdd(other: Long): Long {
    return if (Long.MAX_VALUE - this < other) Long.MAX_VALUE else this + other
}
