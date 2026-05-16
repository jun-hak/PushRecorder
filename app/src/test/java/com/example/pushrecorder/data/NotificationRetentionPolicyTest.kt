package com.example.pushrecorder.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationRetentionPolicyTest {
    @Test
    fun cutoffTimestamp_returnsNinetyDaysBeforeNow() {
        val now = 200L * 24L * 60L * 60L * 1_000L
        val expectedCutoff = 110L * 24L * 60L * 60L * 1_000L

        assertEquals(expectedCutoff, NotificationRetentionPolicy.cutoffTimestamp(now))
    }

    @Test
    fun defaultPolicy_supportsTimeBasedAndMaxRowCleanupLimits() {
        val policy = NotificationRetentionPolicy.Default

        assertEquals(90L, NotificationRetentionPolicy.DEFAULT_HISTORICAL_EVENT_RETENTION_DAYS)
        assertEquals(90L, NotificationRetentionPolicy.DEFAULT_RETENTION_DAYS)
        assertEquals(200_000, NotificationRetentionPolicy.DEFAULT_MAX_HISTORICAL_EVENT_ROWS)
        assertEquals(200_000, policy.maxRows)
        assertEquals(200_000, policy.maxHistoricalEventRows)
        assertNull(policy.maxEvents)
        assertTrue(policy.hasCountLimit)
        assertEquals(200_000, policy.strictestCountLimit)
    }

    @Test
    fun customPolicy_supportsMaxEventLimitWithoutTimeWindow() {
        val policy = NotificationRetentionPolicy(
            maxAgeMillis = null,
            maxRows = null,
            maxEvents = 20_000
        )

        assertNull(policy.cutoffTimestamp(now = 1_000L))
        assertEquals(20_000, policy.maxEvents)
        assertTrue(policy.hasCountLimit)
        assertEquals(20_000, policy.strictestCountLimit)
    }

    @Test
    fun strictestCountLimit_prefersLowerConfiguredCountBound() {
        val policy = NotificationRetentionPolicy(
            maxAgeMillis = null,
            maxRows = 200_000,
            maxEvents = 50_000
        )

        assertEquals(50_000, policy.strictestCountLimit)
    }

    @Test
    fun customPolicy_canDisableCountLimitWhenTimeLimitIsConfigured() {
        val policy = NotificationRetentionPolicy(
            maxAgeMillis = 5_000L,
            maxRows = null,
            maxEvents = null
        )

        assertEquals(5_000L, policy.cutoffTimestamp(now = 10_000L))
        assertFalse(policy.hasCountLimit)
        assertNull(policy.strictestCountLimit)
    }

    @Test(expected = IllegalArgumentException::class)
    fun policyRequiresAtLeastOneLimit() {
        NotificationRetentionPolicy(
            maxAgeMillis = null,
            maxRows = null,
            maxEvents = null
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun maxRowsMustBePositiveWhenConfigured() {
        NotificationRetentionPolicy(maxRows = 0)
    }
}
