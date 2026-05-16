package com.example.pushrecorder.data

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationEventJournalRetentionPolicyTest {
    @Test
    fun defaultPolicyBoundsPendingJournalReplayAndRetention() {
        val policy = NotificationEventJournalRetentionPolicy.Default

        assertEquals(7L, NotificationEventJournalRetentionPolicy.DEFAULT_PENDING_RETENTION_DAYS)
        assertEquals(50_000, policy.maxPendingRows)
        assertEquals(2_000, policy.replayBatchSize)
        assertEquals(1_000L, policy.retryBaseDelayMillis)
        assertEquals(5L * 60L * 1_000L, policy.retryMaxDelayMillis)
    }

    @Test
    fun cutoffTimestampReturnsPendingJournalRetentionBoundary() {
        val policy = NotificationEventJournalRetentionPolicy(maxPendingAgeMillis = 5_000L)

        assertEquals(15_000L, policy.cutoffTimestamp(now = 20_000L))
    }

    @Test
    fun nextAttemptTimestampUsesCappedExponentialBackoff() {
        val policy = NotificationEventJournalRetentionPolicy(
            retryBaseDelayMillis = 1_000L,
            retryMaxDelayMillis = 8_000L
        )

        assertEquals(11_000L, policy.nextAttemptTimestamp(retryCount = 1, now = 10_000L))
        assertEquals(12_000L, policy.nextAttemptTimestamp(retryCount = 2, now = 10_000L))
        assertEquals(14_000L, policy.nextAttemptTimestamp(retryCount = 3, now = 10_000L))
        assertEquals(18_000L, policy.nextAttemptTimestamp(retryCount = 99, now = 10_000L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun maxPendingRowsMustBePositive() {
        NotificationEventJournalRetentionPolicy(maxPendingRows = 0)
    }
}
