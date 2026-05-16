package com.example.pushrecorder.service

import android.util.Log
import com.example.pushrecorder.data.NotificationCapture
import com.example.pushrecorder.data.NotificationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class NotificationReconciler(
    private val notificationRepository: NotificationRepository,
    private val notificationEventProcessor: NotificationEventProcessor,
    private val statusRecorder: NotificationReconcileStatusRecorder,
    private val readActiveNotifications: () -> List<NotificationCapture>?,
    private val enqueueReconcile: (NotificationReconcileEvent) -> Unit,
    private val scope: CoroutineScope,
    private val isAcceptingEvents: () -> Boolean,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val retryDelayMillis: Long = RECONCILE_RETRY_DELAY_MILLIS,
    private val maxRetries: Int = MAX_RECONCILE_RETRIES
) : NotificationReconcileScheduler {
    override fun enqueueActiveReconcileOrRetry(retryCount: Int) {
        val snapshotCapturedAt = currentTimeMillis()
        when (val activeNotifications = activeNotificationLookup()) {
            is ActiveNotificationLookupResult.Success -> {
                enqueueSuccessfulLookup(
                    activeCaptures = activeNotifications.activeNotifications,
                    snapshotCapturedAt = snapshotCapturedAt
                )
            }
            ActiveNotificationLookupResult.Retry -> {
                retryLookupOrReportFailure(retryCount)
            }
        }
    }

    private fun activeNotificationLookup(): ActiveNotificationLookupResult<NotificationCapture> {
        return ActiveNotificationLookup.read(
            readActiveNotifications = readActiveNotifications,
            onNull = {
                statusRecorder.markError("Active notifications lookup returned null")
                logError("Active notifications lookup returned null")
            },
            onError = { error ->
                statusRecorder.markError("Failed to read active notifications")
                logError("Failed to read active notifications", error)
            }
        )
    }

    suspend fun processReconcile(event: NotificationReconcileEvent) {
        val activeKeys = event.activeCaptures.mapTo(mutableSetOf()) { capture ->
            capture.notificationKey
        }
        val reconciledAt = currentTimeMillis()

        retainActiveSnapshots(
            activeKeys = activeKeys,
            targetPackageName = event.targetPackageName
        )
        notificationRepository.reconcileActiveNotifications(
            activeNotificationKeys = activeKeys,
            reconciledAt = reconciledAt,
            targetPackageName = event.targetPackageName,
            activeSnapshotCapturedAt = event.snapshotCapturedAt
        )
        event.activeCaptures.forEach { capture ->
            refreshActiveSnapshot(capture)
        }
        statusRecorder.markReconciled(reconciledAt)
    }

    private fun retainActiveSnapshots(
        activeKeys: Set<String>,
        targetPackageName: String?
    ) {
        if (targetPackageName == null) {
            notificationEventProcessor.retainActiveSnapshots(activeKeys)
        } else {
            notificationEventProcessor.retainActiveSnapshotsForPackage(
                packageName = targetPackageName,
                notificationKeys = activeKeys
            )
        }
    }

    private fun enqueueSuccessfulLookup(
        activeCaptures: List<NotificationCapture>,
        snapshotCapturedAt: Long
    ) {
        enqueueReconcile(
            NotificationReconcileEvent(
                activeCaptures = activeCaptures,
                snapshotCapturedAt = snapshotCapturedAt
            )
        )
    }

    private fun retryLookupOrReportFailure(retryCount: Int) {
        if (retryCount >= maxRetries) {
            statusRecorder.markError("Active notification lookup failed repeatedly")
            logError("Skipping active notification reconcile after repeated lookup failures")
            return
        }

        scheduleActiveReconcileRetry(retryCount)
    }

    private fun scheduleActiveReconcileRetry(retryCount: Int) {
        scope.launch {
            delay(retryDelayMillis * (retryCount + 1))
            if (isAcceptingEvents()) {
                enqueueActiveReconcileOrRetry(retryCount + 1)
            }
        }
    }

    private suspend fun refreshActiveSnapshot(capture: NotificationCapture) {
        val activeSnapshot = notificationEventProcessor.activeSnapshot(capture.notificationKey)
        if (activeSnapshot != null) {
            notificationEventProcessor.putActiveSnapshot(
                activeSnapshot.copy(
                    observedAt = capture.observedAt,
                    flags = capture.flags,
                    hasActions = activeSnapshot.hasActions || capture.hasActions
                )
            )
            return
        }

        val activeNotification = notificationRepository.getActiveNotificationByKey(capture.notificationKey)
        if (activeNotification == null) {
            val result = notificationEventProcessor.process(NotificationProcessingCommand.Posted(capture))
                as NotificationProcessingResult.Posted
            statusRecorder.markPosted(result.observedAt)
            return
        }

        notificationEventProcessor.putActiveSnapshot(
            capture.copy(
                title = activeNotification.title,
                text = activeNotification.text,
                sourcePostTime = activeNotification.timestamp,
                appLabel = activeNotification.appLabel,
                appInfoResolved = activeNotification.appInfoResolved,
                hasActions = activeNotification.hasActions || capture.hasActions
            )
        )
    }

    private companion object {
        private const val TAG = "NotificationListener"
        private const val MAX_RECONCILE_RETRIES = 4
        private const val RECONCILE_RETRY_DELAY_MILLIS = 1_000L

        private fun logError(message: String, error: Throwable? = null) {
            runCatching {
                if (error == null) {
                    Log.e(TAG, message)
                } else {
                    Log.e(TAG, message, error)
                }
            }
        }
    }
}

internal interface NotificationReconcileStatusRecorder {
    fun markPosted(at: Long)

    fun markReconciled(at: Long)

    fun markError(message: String)
}
