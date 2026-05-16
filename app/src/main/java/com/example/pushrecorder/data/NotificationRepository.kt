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
    private val currentTimeMillis: () -> Long
) {
    @Inject
    constructor(notificationDao: NotificationDao) : this(
        notificationDao = notificationDao,
        currentTimeMillis = System::currentTimeMillis
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

    suspend fun getActiveNotificationByKey(notificationKey: String): NotificationEntity? {
        return notificationDao.getActiveNotificationByKey(notificationKey)
    }

    suspend fun recordPosted(
        capture: NotificationCapture,
        eventJournalId: Long? = null
    ) {
        notificationDao.insert(
            capture.toEntity(
                status = NotificationStatus.POSTED,
                eventJournalId = eventJournalId
            )
        )
    }

    suspend fun recordRemoved(
        capture: NotificationCapture,
        status: NotificationStatus,
        removalReason: RemovalReason,
        timeToRemoval: Long,
        removedAt: Long,
        eventJournalId: Long? = null
    ) {
        notificationDao.insert(
            capture.toEntity(
                status = status,
                removalReason = removalReason,
                timeToRemoval = timeToRemoval,
                removedAt = removedAt,
                eventJournalId = eventJournalId
            )
        )
    }

    suspend fun hasTerminalEventForLatestLifecycle(notificationKey: String): Boolean {
        return notificationDao.hasTerminalEventForLatestLifecycle(notificationKey)
    }

    suspend fun hasRecordedEventJournalId(eventJournalId: Long): Boolean {
        return notificationDao.hasEventJournalId(eventJournalId)
    }

    suspend fun deleteExpiredNotifications(): Int {
        return notificationDao.deleteOlderThan(
            NotificationRetentionPolicy.cutoffTimestamp(currentTimeMillis())
        )
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
        return notificationDao.getActiveNotifications()
            .filter { notification ->
                (targetPackageName == null || notification.packageName == targetPackageName) &&
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
