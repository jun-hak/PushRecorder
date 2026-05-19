package com.example.pushrecorder.data

import androidx.paging.PagingSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class FakeNotificationDao : NotificationDao {
    private var nextId = 1L
    private val syntheticRemovalMutex = Mutex()

    val notifications = mutableListOf<NotificationEntity>()
    var nextInsertResult: Long? = null
    var beforeInsert: suspend (NotificationEntity) -> Unit = {}
    var lastDeleteCutoffTimestamp: Long? = null
    var lastTrimMaxRows: Int? = null
    var activeNotificationsQueryCount = 0
        private set
    val activeNotificationsByPackageQueries = mutableListOf<String>()

    override suspend fun insert(notification: NotificationEntity): Long {
        beforeInsert(notification)
        nextInsertResult?.let { result ->
            nextInsertResult = null
            return result
        }
        if (notification.id != 0L && notifications.any { saved -> saved.id == notification.id }) {
            return -1L
        }
        if (
            notification.eventJournalId != null &&
            notifications.any { saved -> saved.eventJournalId == notification.eventJournalId }
        ) {
            return -1L
        }
        val savedNotification = if (notification.id == 0L) {
            notification.copy(id = nextId++)
        } else {
            nextId = maxOf(nextId, notification.id + 1)
            notification
        }
        notifications += savedNotification
        return savedNotification.id
    }

    override fun getPagedNotifications(query: String): PagingSource<Int, NotificationEntity> {
        return ListPagingSource(searchNotifications(query.toLiteralSearchTerm()))
    }

    override fun getPagedNotificationGroups(query: String): PagingSource<Int, NotificationGroupSummary> {
        val filtered = searchNotifications(query.toLiteralSearchTerm())
        val logicalNotifications = filtered
            .groupBy(NotificationEntity::notificationKey)
            .mapNotNull { (_, rows) ->
                rows.maxByOrNull { notification -> notification.id }
            }
        val groups = logicalNotifications
            .groupBy(NotificationEntity::packageName)
            .map { (_, rows) ->
                val latest = rows.maxBy { notification -> notification.id }
                NotificationGroupSummary(
                    latestNotification = latest,
                    notificationCount = rows.size
                )
            }
            .sortedWith(notificationGroupOrdering())
        return ListPagingSource(groups)
    }

    override fun getPagedNotificationsByPackage(
        packageName: String,
        query: String
    ): PagingSource<Int, NotificationEntity> {
        return ListPagingSource(
            searchNotifications(query.toLiteralSearchTerm())
                .filter { notification -> notification.packageName == packageName }
        )
    }

    override fun observeLatestNotificationForDetail(
        notificationKey: String,
        selectedId: Long
    ): Flow<NotificationEntity?> {
        return flow {
            emit(
                notifications
                    .filter { notification ->
                        notification.notificationKey == notificationKey &&
                            notification.id >= selectedId
                    }
                    .maxByOrNull { notification -> notification.id }
            )
        }
    }

    override suspend fun getActiveNotificationByKey(notificationKey: String): NotificationEntity? {
        return activeNotifications()
            .filter { notification -> notification.notificationKey == notificationKey }
            .maxWithOrNull(compareBy<NotificationEntity> { it.observedAt }.thenBy { it.id })
    }

    override suspend fun getActiveNotifications(): List<NotificationEntity> {
        activeNotificationsQueryCount += 1
        return activeNotifications()
    }

    override suspend fun getActiveNotificationsByPackage(packageName: String): List<NotificationEntity> {
        activeNotificationsByPackageQueries += packageName
        return activeNotifications()
            .filter { notification -> notification.packageName == packageName }
    }

    override suspend fun deleteOlderThan(cutoffTimestamp: Long): Int {
        lastDeleteCutoffTimestamp = cutoffTimestamp
        val activeIds = activeNotifications().mapTo(mutableSetOf()) { notification ->
            notification.id
        }
        val beforeSize = notifications.size
        notifications.removeAll { notification ->
            notification.observedAt < cutoffTimestamp &&
                notification.id !in activeIds &&
                !notification.shouldPreserveAsTerminalForRetainedPosted(cutoffTimestamp) &&
                !notification.shouldPreserveAsPostedForRetainedTerminal(cutoffTimestamp)
        }
        return beforeSize - notifications.size
    }

    override suspend fun trimToNewest(maxRows: Int): Int {
        lastTrimMaxRows = maxRows
        val retainedNewestIds = notifications
            .sortedByDescending(NotificationEntity::id)
            .take(maxRows)
            .mapTo(mutableSetOf()) { notification -> notification.id }
        val activeIds = activeNotifications().mapTo(mutableSetOf()) { notification ->
            notification.id
        }
        val beforeSize = notifications.size
        notifications.removeAll { notification ->
            notification.id !in retainedNewestIds &&
                notification.id !in activeIds &&
                !notification.shouldPreserveAsTerminalForRetainedPosted(retainedNewestIds) &&
                !notification.shouldPreserveAsPostedForRetainedTerminal(retainedNewestIds)
        }
        return beforeSize - notifications.size
    }

    override suspend fun hasTerminalEventAfter(
        notificationKey: String,
        id: Long
    ): Boolean {
        return notifications.any { notification ->
            notification.notificationKey == notificationKey &&
                notification.status != NotificationStatus.POSTED &&
                notification.id > id
        }
    }

    override suspend fun hasPostedEventAfter(
        notificationKey: String,
        id: Long
    ): Boolean {
        return notifications.any { notification ->
            notification.notificationKey == notificationKey &&
                notification.status == NotificationStatus.POSTED &&
                notification.id > id
        }
    }

    override suspend fun hasEventJournalId(eventJournalId: Long): Boolean {
        return notifications.any { notification ->
            notification.eventJournalId == eventJournalId
        }
    }

    override suspend fun hasTerminalEventForLatestLifecycle(notificationKey: String): Boolean {
        val latestPostedId = notifications
            .filter { notification ->
                notification.notificationKey == notificationKey &&
                    notification.status == NotificationStatus.POSTED
            }
            .maxOfOrNull(NotificationEntity::id) ?: 0L

        return notifications.any { notification ->
            notification.notificationKey == notificationKey &&
                notification.status != NotificationStatus.POSTED &&
                notification.id > latestPostedId
        }
    }

    override suspend fun insertSyntheticRemovalIfStillActive(
        notification: NotificationEntity,
        removedAt: Long
    ): Boolean {
        return syntheticRemovalMutex.withLock {
            val hasLaterLifecycleEvent = hasTerminalEventAfter(
                notificationKey = notification.notificationKey,
                id = notification.id
            ) || hasPostedEventAfter(
                notificationKey = notification.notificationKey,
                id = notification.id
            )
            if (hasLaterLifecycleEvent) {
                false
            } else {
                insert(
                    NotificationEntity(
                        notificationKey = notification.notificationKey,
                        packageName = notification.packageName,
                        title = notification.title,
                        text = notification.text,
                        timestamp = notification.timestamp,
                        observedAt = removedAt,
                        appLabel = notification.appLabel,
                        appInfoResolved = notification.appInfoResolved,
                        status = NotificationStatus.REMOVED,
                        flags = notification.flags,
                        hasActions = notification.hasActions,
                        removalReason = RemovalReason.UNKNOWN,
                        timeToRemoval = (removedAt - notification.timestamp).coerceAtLeast(0L),
                        removedAt = removedAt
                    )
                ) > 0L
            }
        }
    }

    private fun activeNotifications(): List<NotificationEntity> {
        return notifications
            .groupBy(NotificationEntity::notificationKey)
            .mapNotNull { (_, rows) ->
                rows.maxByOrNull { notification -> notification.id }
            }
            .filter { notification -> notification.status == NotificationStatus.POSTED }
    }

    private fun NotificationEntity.shouldPreserveAsTerminalForRetainedPosted(
        cutoffTimestamp: Long
    ): Boolean {
        if (status == NotificationStatus.POSTED) {
            return false
        }

        return notifications.any { notification ->
            notification.notificationKey == notificationKey &&
                notification.status == NotificationStatus.POSTED &&
                notification.observedAt >= cutoffTimestamp &&
                notification.id < id &&
                hasNoInterveningLifecycleEvent(
                    notificationKey = notificationKey,
                    startExclusive = notification.id,
                    endExclusive = id
                )
        }
    }

    private fun NotificationEntity.shouldPreserveAsPostedForRetainedTerminal(
        cutoffTimestamp: Long
    ): Boolean {
        if (status != NotificationStatus.POSTED) {
            return false
        }

        return notifications.any { notification ->
            notification.notificationKey == notificationKey &&
                notification.status != NotificationStatus.POSTED &&
                notification.observedAt >= cutoffTimestamp &&
                notification.id > id &&
                hasNoInterveningLifecycleEvent(
                    notificationKey = notificationKey,
                    startExclusive = id,
                    endExclusive = notification.id
                )
        }
    }

    private fun NotificationEntity.shouldPreserveAsTerminalForRetainedPosted(
        retainedNewestIds: Set<Long>
    ): Boolean {
        if (status == NotificationStatus.POSTED) {
            return false
        }

        return notifications.any { notification ->
            notification.notificationKey == notificationKey &&
                notification.status == NotificationStatus.POSTED &&
                notification.id in retainedNewestIds &&
                notification.id < id &&
                hasNoInterveningLifecycleEvent(
                    notificationKey = notificationKey,
                    startExclusive = notification.id,
                    endExclusive = id
                )
        }
    }

    private fun NotificationEntity.shouldPreserveAsPostedForRetainedTerminal(
        retainedNewestIds: Set<Long>
    ): Boolean {
        if (status != NotificationStatus.POSTED) {
            return false
        }

        return notifications.any { notification ->
            notification.notificationKey == notificationKey &&
                notification.status != NotificationStatus.POSTED &&
                notification.id in retainedNewestIds &&
                notification.id > id &&
                hasNoInterveningLifecycleEvent(
                    notificationKey = notificationKey,
                    startExclusive = id,
                    endExclusive = notification.id
                )
        }
    }

    private fun hasNoInterveningLifecycleEvent(
        notificationKey: String,
        startExclusive: Long,
        endExclusive: Long
    ): Boolean {
        return notifications.none { notification ->
            notification.notificationKey == notificationKey &&
                notification.id > startExclusive &&
                notification.id < endExclusive
        }
    }

    private fun searchNotifications(query: String): List<NotificationEntity> {
        return notifications
            .filter { notification -> notification.matchesQuery(query) }
            .sortedWith(notificationOrdering())
    }

    private fun NotificationEntity.matchesQuery(query: String): Boolean {
        return query.isEmpty() ||
            packageName.contains(query, ignoreCase = true) ||
            appLabel.contains(query, ignoreCase = true) ||
            title.contains(query, ignoreCase = true) ||
            text.contains(query, ignoreCase = true)
    }

    private fun String.toLiteralSearchTerm(): String {
        return buildString(length) {
            var index = 0
            while (index < this@toLiteralSearchTerm.length) {
                val character = this@toLiteralSearchTerm[index]
                if (
                    character == NotificationSearchQuery.LIKE_ESCAPE &&
                    index + 1 < this@toLiteralSearchTerm.length
                ) {
                    index += 1
                    append(this@toLiteralSearchTerm[index])
                } else {
                    append(character)
                }
                index += 1
            }
        }
    }

    private fun notificationOrdering(): Comparator<NotificationEntity> {
        return compareByDescending<NotificationEntity> { notification -> notification.observedAt }
            .thenByDescending { notification -> notification.id }
    }

    private fun notificationGroupOrdering(): Comparator<NotificationGroupSummary> {
        return compareByDescending<NotificationGroupSummary> { group -> group.latestNotification.id }
            .thenByDescending { group -> group.latestNotification.observedAt }
    }
}

private class ListPagingSource<T : Any>(
    private val items: List<T>
) : PagingSource<Int, T>() {
    override fun getRefreshKey(state: androidx.paging.PagingState<Int, T>): Int? {
        return null
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, T> {
        val startIndex = params.key ?: 0
        val endIndex = (startIndex + params.loadSize).coerceAtMost(items.size)
        val page = if (startIndex >= items.size) {
            emptyList()
        } else {
            items.subList(startIndex, endIndex)
        }

        return LoadResult.Page(
            data = page,
            prevKey = if (startIndex == 0) null else (startIndex - params.loadSize).coerceAtLeast(0),
            nextKey = if (endIndex >= items.size) null else endIndex
        )
    }
}
