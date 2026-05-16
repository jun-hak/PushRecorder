package com.example.pushrecorder.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveNotificationLookupTest {
    @Test
    fun nullLookup_returnsRetryAndReportsNull() {
        var nullReported = false

        val result = ActiveNotificationLookup.read<String>(
            readActiveNotifications = { null },
            onNull = { nullReported = true }
        )

        assertSame(ActiveNotificationLookupResult.Retry, result)
        assertTrue(nullReported)
    }

    @Test
    fun exceptionLookup_returnsRetryAndReportsError() {
        val failure = IllegalStateException("boom")
        var reportedError: Throwable? = null

        val result = ActiveNotificationLookup.read<String>(
            readActiveNotifications = { throw failure },
            onError = { error -> reportedError = error }
        )

        assertSame(ActiveNotificationLookupResult.Retry, result)
        assertSame(failure, reportedError)
    }

    @Test
    fun emptyLookup_isSuccessfulEmptyActiveList() {
        val result = ActiveNotificationLookup.read(
            readActiveNotifications = { emptyList<String>() }
        )

        assertTrue(result is ActiveNotificationLookupResult.Success)
        assertEquals(emptyList<String>(), (result as ActiveNotificationLookupResult.Success).activeNotifications)
    }

    @Test
    fun populatedLookup_isSuccessfulActiveList() {
        val result = ActiveNotificationLookup.read(
            readActiveNotifications = { listOf("one", "two") }
        )

        assertTrue(result is ActiveNotificationLookupResult.Success)
        assertEquals(listOf("one", "two"), (result as ActiveNotificationLookupResult.Success).activeNotifications)
    }
}
