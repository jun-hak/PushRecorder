package com.example.pushrecorder.service

internal class NotificationListenerConnectionAdapter(
    private val statusRecorder: NotificationListenerConnectionStatusRecorder,
    private val reconcileScheduler: NotificationReconcileScheduler
) {
    fun onListenerConnected() {
        statusRecorder.markConnected()
        reconcileScheduler.enqueueActiveReconcileOrRetry()
    }

    fun onListenerDisconnected() {
        statusRecorder.markDisconnected()
    }
}

internal interface NotificationListenerConnectionStatusRecorder {
    fun markConnected(at: Long = System.currentTimeMillis())

    fun markDisconnected(at: Long = System.currentTimeMillis())
}

internal interface NotificationReconcileScheduler {
    fun enqueueActiveReconcileOrRetry(retryCount: Int = 0)
}
