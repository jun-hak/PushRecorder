package com.example.pushrecorder.data

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationRetentionPolicyTest {
    @Test
    fun cutoffTimestamp_returnsNinetyDaysBeforeNow() {
        val now = 200L * 24L * 60L * 60L * 1_000L
        val expectedCutoff = 110L * 24L * 60L * 60L * 1_000L

        assertEquals(expectedCutoff, NotificationRetentionPolicy.cutoffTimestamp(now))
    }
}
