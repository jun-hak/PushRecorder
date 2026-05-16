package com.example.pushrecorder.service

import android.service.notification.NotificationListenerService as AndroidNotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.pushrecorder.appinfo.AppRegistryRepository
import com.example.pushrecorder.data.NotificationRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

@AndroidEntryPoint
class NotificationListenerService : AndroidNotificationListenerService() {
    companion object {
        private const val TAG = "NotificationListener"
    }

    @Inject
    lateinit var notificationRepository: NotificationRepository

    @Inject
    lateinit var appRegistryRepository: AppRegistryRepository

    @Inject
    lateinit var listenerStatusRepository: NotificationListenerStatusRepository

    @Inject
    lateinit var notificationEventProcessor: NotificationEventProcessor

    @Inject
    lateinit var notificationEventJournalRepository: NotificationEventJournalRepository

    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(serviceJob + Dispatchers.IO)
    private val shutdownCompleted = AtomicBoolean(false)
    private val eventQueue: NotificationProcessingQueue by lazy {
        NotificationProcessingQueue(
            scope = scope,
            processEvent = { event -> processQueuedEvent(event) },
            onQueued = listenerStatusRepository::markEventQueued,
            onProcessed = listenerStatusRepository::markEventProcessed,
            onFailed = { event, error ->
                val message = "Failed to process ${event.eventName()}"
                listenerStatusRepository.markEventFailed(message)
                Log.e(TAG, "Failed to process notification event: $event", error)
            },
            onRejected = { event, message, error ->
                listenerStatusRepository.markError(message)
                Log.e(TAG, "Rejected notification event: $event", error)
            }
        )
    }
    private val durableEventEnqueuer: NotificationDurableEventEnqueuer by lazy {
        NotificationDurableEventEnqueuer(
            journalRepository = notificationEventJournalRepository,
            enqueueCommand = { event -> eventQueue.enqueue(event) },
            statusRecorder = listenerStatusRepository,
            logError = { message, error ->
                Log.e(TAG, message, error)
            }
        )
    }
    private val listenerAdapter: NotificationListenerAdapter by lazy {
        NotificationListenerAdapter(
            enqueueEvent = { event -> enqueue(event) }
        )
    }
    private val notificationReconciler: NotificationReconciler by lazy {
        NotificationReconciler(
            notificationRepository = notificationRepository,
            notificationEventProcessor = notificationEventProcessor,
            statusRecorder = listenerStatusRepository,
            readActiveNotifications = {
                activeNotifications?.map { notification ->
                    notification.toNotificationCapture()
                }
            },
            enqueueReconcile = { event ->
                enqueue(event)
            },
            scope = scope,
            isAcceptingEvents = { eventQueue.isAcceptingEvents }
        )
    }
    private val listenerConnectionAdapter: NotificationListenerConnectionAdapter by lazy {
        NotificationListenerConnectionAdapter(
            statusRecorder = listenerStatusRepository,
            reconcileScheduler = notificationReconciler
        )
    }

    override fun onCreate() {
        super.onCreate()
        listenerStatusRepository.markServiceStarted()
        eventQueue.start()
        durableEventEnqueuer.replayPendingEvents()
        cleanupOldNotifications()
        syncInstalledApps()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        listenerAdapter.onNotificationPosted(sbn.toNotificationCapture())
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        listenerAdapter.onNotificationRemoved(sbn.toNotificationCapture(), systemReason = null)
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification,
        rankingMap: AndroidNotificationListenerService.RankingMap,
        reason: Int
    ) {
        listenerAdapter.onNotificationRemoved(sbn.toNotificationCapture(), systemReason = reason)
    }

    override fun onDestroy() {
        listenerStatusRepository.markServiceStopped()
        eventQueue.closeAndDrain {
            completeShutdownIfDrained()
        }
        super.onDestroy()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        listenerConnectionAdapter.onListenerConnected()
    }

    override fun onListenerDisconnected() {
        listenerConnectionAdapter.onListenerDisconnected()
        super.onListenerDisconnected()
    }

    private fun completeShutdownIfDrained() {
        if (shutdownCompleted.compareAndSet(false, true)) {
            serviceJob.cancel()
        }
    }

    private suspend fun processEvent(event: NotificationProcessingCommand) {
        when (event) {
            is NotificationProcessingCommand.Posted,
            is NotificationProcessingCommand.Removed -> processForegroundEvent(event)
            is NotificationProcessingCommand.Reconcile -> notificationReconciler.processReconcile(event)
        }
    }

    private suspend fun processQueuedEvent(event: NotificationProcessingCommand) {
        processEvent(event)
        notificationEventJournalRepository.acknowledge(event)
    }

    private suspend fun processForegroundEvent(event: NotificationProcessingCommand) {
        when (val result = notificationEventProcessor.process(event)) {
            is NotificationProcessingResult.Posted -> {
                listenerStatusRepository.markPosted(result.observedAt)
            }
            is NotificationProcessingResult.Removed -> {
                listenerStatusRepository.markRemoved(result.removedAt)
            }
        }
    }

    private fun enqueue(event: NotificationProcessingCommand) {
        durableEventEnqueuer.enqueue(event)
    }

    private fun cleanupOldNotifications() {
        scope.launch {
            runCatching {
                notificationRepository.deleteExpiredNotifications()
            }.onFailure { error ->
                listenerStatusRepository.markError("Failed to delete expired notifications")
                Log.e(TAG, "Failed to delete expired notifications", error)
            }
        }
    }

    private fun syncInstalledApps() {
        scope.launch {
            runCatching {
                appRegistryRepository.syncInstalledApps()
            }.onSuccess { syncedCount ->
                listenerStatusRepository.markInstalledAppSync(syncedCount)
            }.onFailure { error ->
                listenerStatusRepository.markError("Failed to sync installed apps")
                Log.e(TAG, "Failed to sync installed apps", error)
            }
        }
    }

}
