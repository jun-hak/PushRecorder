package com.example.pushrecorder.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationRepository internal constructor(
    private val notificationDao: NotificationDao,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val retentionPolicy: NotificationRetentionPolicy = NotificationRetentionPolicy.Default
) {
    @Inject
    constructor(
        notificationDao: NotificationDao,
        retentionPolicy: NotificationRetentionPolicy
    ) : this(
        notificationDao = notificationDao,
        currentTimeMillis = System::currentTimeMillis,
        retentionPolicy = retentionPolicy
    )

    fun pagedNotifications(query: String): Flow<PagingData<NotificationEntity>> {
        val searchQuery = NotificationSearchQuery.fromUserInput(query)
        return Pager(config = NOTIFICATION_PAGE_CONFIG) {
            notificationDao.getPagedNotifications(searchQuery)
        }.flow
    }

    fun pagedNotificationGroups(query: String): Flow<PagingData<NotificationGroupSummary>> {
        val searchQuery = NotificationSearchQuery.fromUserInput(query)
        return Pager(config = GROUP_PAGE_CONFIG) {
            notificationDao.getPagedNotificationGroups(searchQuery)
        }.flow
    }

    fun pagedNotificationsByPackage(
        packageName: String,
        query: String
    ): Flow<PagingData<NotificationEntity>> {
        val searchQuery = NotificationSearchQuery.fromUserInput(query)
        return Pager(config = NOTIFICATION_PAGE_CONFIG) {
            notificationDao.getPagedNotificationsByPackage(
                packageName = packageName,
                query = searchQuery
            )
        }.flow
    }

    fun observeNotificationDetail(
        notificationKey: String,
        selectedId: Long
    ): Flow<NotificationEntity?> {
        return notificationDao.observeLatestNotificationForDetail(
            notificationKey = notificationKey,
            selectedId = selectedId
        )
    }

    suspend fun getActiveNotificationByKey(notificationKey: String): NotificationEntity? {
        return notificationDao.getActiveNotificationByKey(notificationKey)
    }

    suspend fun recordPosted(
        capture: NotificationCapture,
        eventJournalId: Long? = null
    ) {
        val insertedId = notificationDao.insert(
            capture.toEntity(
                status = NotificationStatus.POSTED,
                eventJournalId = eventJournalId
            )
        )
        if (insertedId <= 0L && eventJournalId != null && notificationDao.hasEventJournalId(eventJournalId)) {
            return
        }
        check(insertedId > 0L) {
            "Failed to persist posted notification event for ${capture.notificationKey}"
        }
    }

    suspend fun recordRemoved(
        capture: NotificationCapture,
        status: NotificationStatus,
        removalReason: RemovalReason,
        timeToRemoval: Long,
        removedAt: Long,
        eventJournalId: Long? = null
    ) {
        val insertedId = notificationDao.insert(
            capture.toEntity(
                status = status,
                removalReason = removalReason,
                timeToRemoval = timeToRemoval,
                removedAt = removedAt,
                eventJournalId = eventJournalId
            )
        )
        if (insertedId <= 0L && eventJournalId != null && notificationDao.hasEventJournalId(eventJournalId)) {
            return
        }
        check(insertedId > 0L) {
            "Failed to persist removed notification event for ${capture.notificationKey}"
        }
    }

    suspend fun hasTerminalEventForLatestLifecycle(notificationKey: String): Boolean {
        return notificationDao.hasTerminalEventForLatestLifecycle(notificationKey)
    }

    suspend fun hasRecordedEventJournalId(eventJournalId: Long): Boolean {
        return notificationDao.hasEventJournalId(eventJournalId)
    }

    suspend fun deleteExpiredNotifications(): Int {
        val ageDeleted = retentionPolicy.cutoffTimestamp(currentTimeMillis())
            ?.let { cutoffTimestamp -> notificationDao.deleteOlderThan(cutoffTimestamp) }
            ?: 0
        val countDeleted = retentionPolicy.strictestCountLimit
            ?.let { maxRows -> notificationDao.trimToNewest(maxRows) }
            ?: 0
        return ageDeleted + countDeleted
    }

    suspend fun reconcileActiveNotifications(
        activeNotificationKeys: Set<String>,
        reconciledAt: Long,
        targetPackageName: String? = null,
        activeSnapshotCapturedAt: Long = Long.MAX_VALUE
    ) {
        val activeRows = activeRowsForReconcile(
            targetPackageName = targetPackageName,
            activeSnapshotCapturedAt = activeSnapshotCapturedAt
        )
        activeRows
            .filter { notification ->
                notification.notificationKey !in activeNotificationKeys
            }
            .forEach { notification ->
                markStaleNotificationRemoved(
                    notification = notification,
                    removedAt = reconciledAt
                )
            }
    }

    private suspend fun activeRowsForReconcile(
        targetPackageName: String?,
        activeSnapshotCapturedAt: Long
    ): List<NotificationEntity> {
        val activeRows = if (targetPackageName == null) {
            notificationDao.getActiveNotifications()
        } else {
            notificationDao.getActiveNotificationsByPackage(targetPackageName)
        }

        return activeRows
            .filter { notification ->
                notification.observedAt <= activeSnapshotCapturedAt
            }
    }

    private suspend fun markStaleNotificationRemoved(
        notification: NotificationEntity,
        removedAt: Long
    ) {
        notificationDao.insertSyntheticRemovalIfStillActive(
            notification = notification,
            removedAt = removedAt
        )
    }

    private companion object {
        private val NOTIFICATION_PAGE_CONFIG = PagingConfig(
            pageSize = 50,
            prefetchDistance = 20,
            enablePlaceholders = false
        )

        private val GROUP_PAGE_CONFIG = PagingConfig(
            pageSize = 30,
            prefetchDistance = 10,
            enablePlaceholders = false
        )
    }
}
