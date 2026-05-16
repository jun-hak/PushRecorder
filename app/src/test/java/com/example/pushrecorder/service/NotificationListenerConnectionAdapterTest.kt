package com.example.pushrecorder.service

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationListenerConnectionAdapterTest {
    private val statusRecorder = FakeConnectionStatusRecorder()
    private val reconcileScheduler = FakeReconcileScheduler()
    private val adapter = NotificationListenerConnectionAdapter(
        statusRecorder = statusRecorder,
        reconcileScheduler = reconcileScheduler
    )

    @Test
    fun onListenerConnected_marksConnectedAndSchedulesActiveReconcile() {
        adapter.onListenerConnected()

        assertEquals(listOf("connected"), statusRecorder.events)
        assertEquals(listOf(0), reconcileScheduler.retryCounts)
    }

    @Test
    fun onListenerDisconnected_marksDisconnectedWithoutSchedulingReconcile() {
        adapter.onListenerDisconnected()

        assertEquals(listOf("disconnected"), statusRecorder.events)
        assertEquals(emptyList<Int>(), reconcileScheduler.retryCounts)
    }

    private class FakeConnectionStatusRecorder : NotificationListenerConnectionStatusRecorder {
        val events = mutableListOf<String>()

        override fun markConnected(at: Long) {
            events += "connected"
        }

        override fun markDisconnected(at: Long) {
            events += "disconnected"
        }
    }

    private class FakeReconcileScheduler : NotificationReconcileScheduler {
        val retryCounts = mutableListOf<Int>()

        override fun enqueueActiveReconcileOrRetry(retryCount: Int) {
            retryCounts += retryCount
        }
    }
}
