package com.example.pushrecorder.capture

import com.example.pushrecorder.appinfo.AppDisplayInfo
import com.example.pushrecorder.appinfo.AppRegistryRepository
import com.example.pushrecorder.data.NotificationCapture
import com.example.pushrecorder.data.NotificationRepository
import javax.inject.Inject

class NotificationCaptureProcessor @Inject constructor(
    private val notificationRepository: NotificationRepository,
    private val appRegistryRepository: AppRegistryRepository,
    private val activeSnapshots: ActiveNotificationSnapshotStore
) {
    suspend fun processAccepted(
        capture: NotificationCapture,
        eventJournalId: Long? = null
    ): AcceptedNotificationCaptureResult {
        val appSnapshot = packageSnapshot(capture.packageName)
        val existingCapture = activeSnapshots.get(capture.notificationKey)
        val normalizedCapture = capture.copy(
            sourcePostTime = existingCapture?.sourcePostTime ?: capture.sourcePostTime,
            appLabel = appSnapshot.label,
            appInfoResolved = appSnapshot.isResolved
        )

        activeSnapshots.put(normalizedCapture)
        notificationRepository.recordPosted(
            capture = normalizedCapture,
            eventJournalId = eventJournalId
        )
        appRegistryRepository.recordObservedPackage(
            packageName = normalizedCapture.packageName,
            observedAt = normalizedCapture.observedAt
        )

        return AcceptedNotificationCaptureResult(
            observedAt = normalizedCapture.observedAt,
            capture = normalizedCapture
        )
    }

    private suspend fun packageSnapshot(packageName: String): AppDisplayInfo {
        return runCatching {
            appRegistryRepository.packageSnapshot(packageName)
        }.getOrElse {
            AppDisplayInfo.fallback(packageName)
        }
    }
}

data class AcceptedNotificationCaptureResult(
    val observedAt: Long,
    val capture: NotificationCapture
)
