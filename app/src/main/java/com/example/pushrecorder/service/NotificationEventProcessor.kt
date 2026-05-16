package com.example.pushrecorder.service

import android.util.Log
import com.example.pushrecorder.appinfo.AppDisplayInfo
import com.example.pushrecorder.appinfo.AppRegistryRepository
import com.example.pushrecorder.capture.ActiveNotificationSnapshotStore
import com.example.pushrecorder.capture.NotificationCaptureProcessor
import com.example.pushrecorder.data.NotificationCapture
import com.example.pushrecorder.data.NotificationEntity
import com.example.pushrecorder.data.NotificationRepository
import com.example.pushrecorder.data.NotificationStatus
import javax.inject.Inject

class NotificationEventProcessor @Inject constructor(
    private val notificationRepository: NotificationRepository,
    private val appRegistryRepository: AppRegistryRepository,
    private val notificationCaptureProcessor: NotificationCaptureProcessor,
    private val activeSnapshots: ActiveNotificationSnapshotStore
) : NotificationEventProcessing {
    private var currentTimeMillis: () -> Long = System::currentTimeMillis

    constructor(
        notificationRepository: NotificationRepository,
        appRegistryRepository: AppRegistryRepository
    ) : this(
        notificationRepository = notificationRepository,
        appRegistryRepository = appRegistryRepository,
        activeSnapshots = ActiveNotificationSnapshotStore(),
        currentTimeMillis = System::currentTimeMillis
    )

    constructor(
        notificationRepository: NotificationRepository,
        appRegistryRepository: AppRegistryRepository,
        activeSnapshots: ActiveNotificationSnapshotStore = ActiveNotificationSnapshotStore(),
        currentTimeMillis: () -> Long
    ) : this(
        notificationRepository = notificationRepository,
        appRegistryRepository = appRegistryRepository,
        notificationCaptureProcessor = NotificationCaptureProcessor(
            notificationRepository = notificationRepository,
            appRegistryRepository = appRegistryRepository,
            activeSnapshots = activeSnapshots
        ),
        activeSnapshots = activeSnapshots
    ) {
        this.currentTimeMillis = currentTimeMillis
    }

    override suspend fun process(
        command: NotificationProcessingCommand
    ): NotificationProcessingResult {
        return when (command) {
            is NotificationProcessingCommand.Posted -> processPosted(command)
            is NotificationProcessingCommand.Removed -> processRemoved(command)
            is NotificationProcessingCommand.Reconcile -> error("Reconcile commands are processed by NotificationReconciler")
        }
    }

    private suspend fun processPosted(
        command: NotificationProcessingCommand.Posted
    ): NotificationProcessingResult.Posted {
        if (command.wasAlreadyRecorded()) {
            return NotificationProcessingResult.Posted(observedAt = command.capture.observedAt)
        }

        val result = notificationCaptureProcessor.processAccepted(
            capture = command.capture,
            eventJournalId = command.eventJournalId
        )
        val normalizedCapture = result.capture
        logDebug(
            TAG,
            "Notification Posted - Package: ${normalizedCapture.packageName}, " +
                "Title: ${normalizedCapture.title}, Text: ${normalizedCapture.text}"
        )

        return NotificationProcessingResult.Posted(observedAt = result.observedAt)
    }

    private suspend fun processRemoved(
        processingCommand: NotificationProcessingCommand.Removed
    ): NotificationProcessingResult.Removed {
        val command = processingCommand.command
        val removedAt = currentTimeMillis()
        if (processingCommand.wasAlreadyRecorded()) {
            return NotificationProcessingResult.Removed(removedAt = removedAt)
        }

        val storedNotification = activeNotificationFor(command)

        if (storedNotification == null) {
            if (notificationRepository.hasTerminalEventForLatestLifecycle(command.notificationKey)) {
                logWarning(
                    TAG,
                    "Skipping duplicate terminal removal after reconcile: ${command.notificationKey}"
                )
                removeActiveSnapshot(command.notificationKey)
                return NotificationProcessingResult.Removed(removedAt = removedAt)
            }
            logWarning(
                TAG,
                "Recording removal without original posted snapshot: ${command.notificationKey}"
            )
        }

        val removal = finalizedRemoval(
            command = command,
            storedNotification = storedNotification,
            removedAt = removedAt
        )

        logDebug(
            TAG,
            "Notification Removed - Package: ${command.packageName}, " +
                "Title: ${removal.capture.title}, Text: ${removal.capture.text}"
        )

        notificationRepository.recordRemoved(
            capture = removal.capture,
            status = removal.status,
            removalReason = removal.reason,
            timeToRemoval = removal.timeToRemoval,
            removedAt = removal.removedAt,
            eventJournalId = processingCommand.eventJournalId
        )
        removeActiveSnapshot(command.notificationKey)

        return NotificationProcessingResult.Removed(removedAt = removedAt)
    }

    fun activeSnapshot(notificationKey: String): NotificationCapture? {
        return activeSnapshots.get(notificationKey)
    }

    fun putActiveSnapshot(capture: NotificationCapture) {
        activeSnapshots.put(capture)
    }

    fun removeActiveSnapshot(notificationKey: String) {
        activeSnapshots.remove(notificationKey)
    }

    fun retainActiveSnapshots(notificationKeys: Set<String>) {
        activeSnapshots.retain(notificationKeys)
    }

    fun retainActiveSnapshotsForPackage(
        packageName: String,
        notificationKeys: Set<String>
    ) {
        activeSnapshots.retainForPackage(
            packageName = packageName,
            notificationKeys = notificationKeys
        )
    }

    fun clearActiveSnapshots() {
        activeSnapshots.clear()
    }

    private suspend fun NotificationProcessingCommand.wasAlreadyRecorded(): Boolean {
        val journalId = eventJournalId ?: return false
        return notificationRepository.hasRecordedEventJournalId(journalId)
    }

    private suspend fun packageSnapshot(packageName: String): AppDisplayInfo {
        return runCatching {
            appRegistryRepository.packageSnapshot(packageName)
        }.getOrElse { error ->
            logError(TAG, "Failed to snapshot app info: $packageName", error)
            AppDisplayInfo.fallback(packageName)
        }
    }

    private suspend fun activeNotificationFor(
        command: NotificationRemovalCommand
    ): NotificationEntity? = activeSnapshots.get(command.notificationKey)
        ?.toEntity(status = NotificationStatus.POSTED)
        ?: notificationRepository.getActiveNotificationByKey(command.notificationKey)

    private suspend fun finalizedRemoval(
        command: NotificationRemovalCommand,
        storedNotification: NotificationEntity?,
        removedAt: Long
    ): FinalizedNotificationRemoval {
        val appSnapshot = packageSnapshot(command.packageName)

        return NotificationRemovalFinalizer.finalize(
            command = command,
            storedNotification = storedNotification,
            appSnapshot = appSnapshot,
            removedAt = removedAt
        )
    }

    private companion object {
        private const val TAG = "NotificationEventProcessor"

        private fun logDebug(tag: String, message: String) {
            runCatching {
                Log.d(tag, message)
            }
        }

        private fun logWarning(tag: String, message: String) {
            runCatching {
                Log.w(tag, message)
            }
        }

        private fun logError(tag: String, message: String, error: Throwable) {
            runCatching {
                Log.e(tag, message, error)
            }
        }
    }
}
