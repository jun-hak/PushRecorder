package com.example.pushrecorder.service

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationListenerStatusRepositoryInstrumentedTest {
    private lateinit var context: Context
    private lateinit var repository: NotificationListenerStatusRepository

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        repository = NotificationListenerStatusRepository(context)
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun connectedAndDisconnectedTransitionsTrackCapturingSeparatelyFromPermission() {
        repository.markConnected(at = 1_000L)

        val connected = repository.status.value
        assertTrue(connected.isServiceConnected)
        assertEquals(1_000L, connected.lastConnectedAt)
        assertEquals(connected.isListenerEnabled && connected.isServiceConnected, connected.isCapturing)

        repository.markDisconnected(at = 2_000L)

        val disconnected = repository.status.value
        assertFalse(disconnected.isServiceConnected)
        assertFalse(disconnected.isCapturing)
        assertEquals(2_000L, disconnected.lastDisconnectedAt)
    }

    @Test
    fun newRepositoryDoesNotRestoreStaleServiceConnection() {
        repository.markConnected(at = 1_000L)

        val restoredRepository = NotificationListenerStatusRepository(context)
        val restored = restoredRepository.status.value

        assertFalse(restored.isServiceConnected)
        assertFalse(restored.isCapturing)
        assertEquals(1_000L, restored.lastConnectedAt)
    }

    @Test
    fun eventCountersNeverGoNegativeAcrossFailuresAndStop() {
        repository.markEventQueued()
        repository.markEventFailed(message = "failed", at = 1_000L)
        repository.markEventProcessed()
        repository.markServiceStopped(at = 2_000L)

        val status = repository.status.value
        assertEquals(0, status.pendingEventCount)
        assertEquals(1L, status.totalQueuedEvents)
        assertEquals(1L, status.totalProcessedEvents)
        assertEquals(1L, status.totalFailedEvents)
        assertEquals("failed", status.lastErrorMessage)
    }

    @Test
    fun serviceStopDoesNotHidePendingEvents() {
        repository.markEventQueued()
        repository.markServiceStopped(at = 1_000L)

        val status = repository.status.value
        assertEquals(1, status.pendingEventCount)
        assertFalse(status.isServiceConnected)
    }

    private companion object {
        private const val PREFERENCES_NAME = "notification_listener_status"
    }
}
