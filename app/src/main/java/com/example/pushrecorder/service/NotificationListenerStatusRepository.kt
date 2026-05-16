package com.example.pushrecorder.service

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class NotificationListenerStatus(
    val isListenerEnabled: Boolean = false,
    val isServiceConnected: Boolean = false,
    val lastServiceStartedAt: Long = 0L,
    val lastServiceStoppedAt: Long = 0L,
    val lastConnectedAt: Long = 0L,
    val lastDisconnectedAt: Long = 0L,
    val lastCapturedAt: Long = 0L,
    val lastPostedAt: Long = 0L,
    val lastRemovedAt: Long = 0L,
    val lastReconciledAt: Long = 0L,
    val lastInstalledAppSyncAt: Long = 0L,
    val lastInstalledAppSyncCount: Int = 0,
    val pendingEventCount: Int = 0,
    val totalQueuedEvents: Long = 0L,
    val totalProcessedEvents: Long = 0L,
    val totalFailedEvents: Long = 0L,
    val lastErrorAt: Long = 0L,
    val lastErrorMessage: String? = null
) {
    val isCapturing: Boolean
        get() = isListenerEnabled && isServiceConnected
}

@Singleton
class NotificationListenerStatusRepository @Inject constructor(
    @ApplicationContext private val context: Context
) : NotificationListenerEventStatusRecorder,
    NotificationListenerConnectionStatusRecorder,
    NotificationReconcileStatusRecorder {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val _status = MutableStateFlow(loadStatus().copy(isListenerEnabled = isListenerEnabled()))

    val status: StateFlow<NotificationListenerStatus> = _status.asStateFlow()

    fun refresh(): NotificationListenerStatus {
        return updateStatus { current ->
            current.copy(isListenerEnabled = isListenerEnabled())
        }
    }

    fun markServiceStarted(at: Long = System.currentTimeMillis()) {
        updateStatus { current ->
            current.copy(
                lastServiceStartedAt = at,
                isServiceConnected = current.isServiceConnected,
                isListenerEnabled = isListenerEnabled()
            )
        }
    }

    fun markServiceStopped(at: Long = System.currentTimeMillis()) {
        updateStatus { current ->
            current.copy(
                lastServiceStoppedAt = at,
                isServiceConnected = false,
                isListenerEnabled = isListenerEnabled()
            )
        }
    }

    override fun markConnected(at: Long) {
        updateStatus { current ->
            current.copy(
                lastConnectedAt = at,
                isServiceConnected = true,
                isListenerEnabled = isListenerEnabled()
            )
        }
    }

    override fun markDisconnected(at: Long) {
        updateStatus { current ->
            current.copy(
                lastDisconnectedAt = at,
                isServiceConnected = false,
                isListenerEnabled = isListenerEnabled()
            )
        }
    }

    fun markPosted() {
        markPosted(System.currentTimeMillis())
    }

    override fun markPosted(at: Long) {
        updateStatus { current ->
            current.copy(
                lastCapturedAt = at,
                lastPostedAt = at
            )
        }
    }

    fun markRemoved() {
        markRemoved(System.currentTimeMillis())
    }

    override fun markRemoved(at: Long) {
        updateStatus { current ->
            current.copy(
                lastCapturedAt = at,
                lastRemovedAt = at
            )
        }
    }

    override fun markReconciled(at: Long) {
        updateStatus { current ->
            current.copy(lastReconciledAt = at)
        }
    }

    fun markReconciled() {
        markReconciled(System.currentTimeMillis())
    }

    fun markInstalledAppSync(
        appCount: Int,
        at: Long = System.currentTimeMillis()
    ) {
        updateStatus { current ->
            current.copy(
                lastInstalledAppSyncAt = at,
                lastInstalledAppSyncCount = appCount
            )
        }
    }

    override fun markEventQueued() {
        updateStatus { current ->
            current.copy(
                pendingEventCount = current.pendingEventCount + 1,
                totalQueuedEvents = current.totalQueuedEvents + 1
            )
        }
    }

    override fun markEventProcessed() {
        updateStatus { current ->
            current.copy(
                pendingEventCount = (current.pendingEventCount - 1).coerceAtLeast(0),
                totalProcessedEvents = current.totalProcessedEvents + 1
            )
        }
    }

    fun markEventFailed(message: String) {
        markEventFailed(message = message, at = System.currentTimeMillis())
    }

    override fun markEventFailed(
        message: String,
        at: Long
    ) {
        updateStatus { current ->
            current.copy(
                pendingEventCount = (current.pendingEventCount - 1).coerceAtLeast(0),
                totalFailedEvents = current.totalFailedEvents + 1,
                lastErrorAt = at,
                lastErrorMessage = message
            )
        }
    }

    override fun markError(message: String) {
        markError(message = message, at = System.currentTimeMillis())
    }

    override fun markError(
        message: String,
        at: Long
    ) {
        updateStatus { current ->
            current.copy(
                totalFailedEvents = current.totalFailedEvents + 1,
                lastErrorAt = at,
                lastErrorMessage = message
            )
        }
    }

    private fun loadStatus(): NotificationListenerStatus {
        return NotificationListenerStatus(
            isServiceConnected = false,
            lastServiceStartedAt = preferences.getLong(KEY_LAST_SERVICE_STARTED_AT, 0L),
            lastServiceStoppedAt = preferences.getLong(KEY_LAST_SERVICE_STOPPED_AT, 0L),
            lastConnectedAt = preferences.getLong(KEY_LAST_CONNECTED_AT, 0L),
            lastDisconnectedAt = preferences.getLong(KEY_LAST_DISCONNECTED_AT, 0L),
            lastCapturedAt = preferences.getLong(KEY_LAST_CAPTURED_AT, 0L),
            lastPostedAt = preferences.getLong(KEY_LAST_POSTED_AT, 0L),
            lastRemovedAt = preferences.getLong(KEY_LAST_REMOVED_AT, 0L),
            lastReconciledAt = preferences.getLong(KEY_LAST_RECONCILED_AT, 0L),
            lastInstalledAppSyncAt = preferences.getLong(KEY_LAST_INSTALLED_APP_SYNC_AT, 0L),
            lastInstalledAppSyncCount = preferences.getInt(KEY_LAST_INSTALLED_APP_SYNC_COUNT, 0),
            pendingEventCount = preferences.getInt(KEY_PENDING_EVENT_COUNT, 0),
            totalQueuedEvents = preferences.getLong(KEY_TOTAL_QUEUED_EVENTS, 0L),
            totalProcessedEvents = preferences.getLong(KEY_TOTAL_PROCESSED_EVENTS, 0L),
            totalFailedEvents = preferences.getLong(KEY_TOTAL_FAILED_EVENTS, 0L),
            lastErrorAt = preferences.getLong(KEY_LAST_ERROR_AT, 0L),
            lastErrorMessage = preferences.getString(KEY_LAST_ERROR_MESSAGE, null)
        )
    }

    @Synchronized
    private fun updateStatus(
        transform: (NotificationListenerStatus) -> NotificationListenerStatus
    ): NotificationListenerStatus {
        val next = transform(_status.value)
        preferences.edit {
            putLong(KEY_LAST_SERVICE_STARTED_AT, next.lastServiceStartedAt)
            putLong(KEY_LAST_SERVICE_STOPPED_AT, next.lastServiceStoppedAt)
            putLong(KEY_LAST_CONNECTED_AT, next.lastConnectedAt)
            putLong(KEY_LAST_DISCONNECTED_AT, next.lastDisconnectedAt)
            putLong(KEY_LAST_CAPTURED_AT, next.lastCapturedAt)
            putLong(KEY_LAST_POSTED_AT, next.lastPostedAt)
            putLong(KEY_LAST_REMOVED_AT, next.lastRemovedAt)
            putLong(KEY_LAST_RECONCILED_AT, next.lastReconciledAt)
            putLong(KEY_LAST_INSTALLED_APP_SYNC_AT, next.lastInstalledAppSyncAt)
            putInt(KEY_LAST_INSTALLED_APP_SYNC_COUNT, next.lastInstalledAppSyncCount)
            putInt(KEY_PENDING_EVENT_COUNT, next.pendingEventCount)
            putLong(KEY_TOTAL_QUEUED_EVENTS, next.totalQueuedEvents)
            putLong(KEY_TOTAL_PROCESSED_EVENTS, next.totalProcessedEvents)
            putLong(KEY_TOTAL_FAILED_EVENTS, next.totalFailedEvents)
            putLong(KEY_LAST_ERROR_AT, next.lastErrorAt)
            putString(KEY_LAST_ERROR_MESSAGE, next.lastErrorMessage)
        }
        _status.value = next
        return next
    }

    private fun isListenerEnabled(): Boolean {
        return NotificationManagerCompat
            .getEnabledListenerPackages(context)
            .contains(context.packageName)
    }

    private companion object {
        private const val PREFERENCES_NAME = "notification_listener_status"
        private const val KEY_LAST_SERVICE_STARTED_AT = "lastServiceStartedAt"
        private const val KEY_LAST_SERVICE_STOPPED_AT = "lastServiceStoppedAt"
        private const val KEY_LAST_CONNECTED_AT = "lastConnectedAt"
        private const val KEY_LAST_DISCONNECTED_AT = "lastDisconnectedAt"
        private const val KEY_LAST_CAPTURED_AT = "lastCapturedAt"
        private const val KEY_LAST_POSTED_AT = "lastPostedAt"
        private const val KEY_LAST_REMOVED_AT = "lastRemovedAt"
        private const val KEY_LAST_RECONCILED_AT = "lastReconciledAt"
        private const val KEY_LAST_INSTALLED_APP_SYNC_AT = "lastInstalledAppSyncAt"
        private const val KEY_LAST_INSTALLED_APP_SYNC_COUNT = "lastInstalledAppSyncCount"
        private const val KEY_PENDING_EVENT_COUNT = "pendingEventCount"
        private const val KEY_TOTAL_QUEUED_EVENTS = "totalQueuedEvents"
        private const val KEY_TOTAL_PROCESSED_EVENTS = "totalProcessedEvents"
        private const val KEY_TOTAL_FAILED_EVENTS = "totalFailedEvents"
        private const val KEY_LAST_ERROR_AT = "lastErrorAt"
        private const val KEY_LAST_ERROR_MESSAGE = "lastErrorMessage"
    }
}
