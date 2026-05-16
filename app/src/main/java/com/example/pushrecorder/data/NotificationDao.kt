package com.example.pushrecorder.data

import androidx.room.*
import androidx.paging.PagingSource

@Dao
interface NotificationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(notification: NotificationEntity): Long

    @Query("""
        SELECT *
        FROM notifications
        WHERE :query = ''
            OR packageName LIKE '%' || :query || '%' ESCAPE '\'
            OR appLabel LIKE '%' || :query || '%' ESCAPE '\'
            OR title LIKE '%' || :query || '%' ESCAPE '\'
            OR text LIKE '%' || :query || '%' ESCAPE '\'
        ORDER BY observedAt DESC, id DESC
    """)
    fun getPagedNotifications(query: String): PagingSource<Int, NotificationEntity>

    @Query("""
        WITH filtered AS (
            SELECT *
            FROM notifications
            WHERE :query = ''
                OR packageName LIKE '%' || :query || '%' ESCAPE '\'
                OR appLabel LIKE '%' || :query || '%' ESCAPE '\'
                OR title LIKE '%' || :query || '%' ESCAPE '\'
                OR text LIKE '%' || :query || '%' ESCAPE '\'
        ),
        logical AS (
            SELECT candidate.*
            FROM filtered AS candidate
            WHERE NOT EXISTS (
                SELECT 1
                FROM filtered AS newer
                WHERE newer.notificationKey = candidate.notificationKey
                    AND newer.id > candidate.id
            )
        ),
        grouped AS (
            SELECT packageName,
                COUNT(*) AS notificationCount
            FROM logical
            GROUP BY packageName
        )
        SELECT candidate.*, grouped.notificationCount
        FROM logical AS candidate
        INNER JOIN grouped ON candidate.packageName = grouped.packageName
        WHERE NOT EXISTS (
                SELECT 1
                FROM logical AS newer
                WHERE newer.packageName = candidate.packageName
                    AND newer.id > candidate.id
            )
        ORDER BY candidate.id DESC, candidate.observedAt DESC
    """)
    fun getPagedNotificationGroups(query: String): PagingSource<Int, NotificationGroupSummary>

    @Query("""
        SELECT *
        FROM notifications
        WHERE packageName = :packageName
            AND (
                :query = ''
                OR packageName LIKE '%' || :query || '%' ESCAPE '\'
                OR appLabel LIKE '%' || :query || '%' ESCAPE '\'
                OR title LIKE '%' || :query || '%' ESCAPE '\'
                OR text LIKE '%' || :query || '%' ESCAPE '\'
            )
        ORDER BY observedAt DESC, id DESC
    """)
    fun getPagedNotificationsByPackage(
        packageName: String,
        query: String
    ): PagingSource<Int, NotificationEntity>

    @Query("""
        SELECT *
        FROM notifications AS posted
        WHERE posted.notificationKey = :notificationKey
            AND posted.status = 'POSTED'
            AND NOT EXISTS (
                SELECT 1
                FROM notifications AS terminal
                WHERE terminal.notificationKey = posted.notificationKey
                    AND terminal.status != 'POSTED'
                    AND terminal.id > posted.id
            )
            AND NOT EXISTS (
                SELECT 1
                FROM notifications AS newerPosted
                WHERE newerPosted.notificationKey = posted.notificationKey
                    AND newerPosted.status = 'POSTED'
                    AND newerPosted.id > posted.id
            )
        ORDER BY posted.observedAt DESC, posted.id DESC
        LIMIT 1
    """)
    suspend fun getActiveNotificationByKey(notificationKey: String): NotificationEntity?

    @Query("""
        SELECT *
        FROM notifications AS posted
        WHERE posted.status = 'POSTED'
            AND NOT EXISTS (
                SELECT 1
                FROM notifications AS terminal
                WHERE terminal.notificationKey = posted.notificationKey
                    AND terminal.status != 'POSTED'
                    AND terminal.id > posted.id
            )
            AND NOT EXISTS (
                SELECT 1
                FROM notifications AS newerPosted
                WHERE newerPosted.notificationKey = posted.notificationKey
                    AND newerPosted.status = 'POSTED'
                    AND newerPosted.id > posted.id
            )
        ORDER BY posted.notificationKey ASC, posted.observedAt DESC, posted.id DESC
    """)
    suspend fun getActiveNotifications(): List<NotificationEntity>

    @Query("""
        SELECT *
        FROM notifications AS posted
        WHERE posted.packageName = :packageName
            AND posted.status = 'POSTED'
            AND NOT EXISTS (
                SELECT 1
                FROM notifications AS terminal
                WHERE terminal.notificationKey = posted.notificationKey
                    AND terminal.status != 'POSTED'
                    AND terminal.id > posted.id
            )
            AND NOT EXISTS (
                SELECT 1
                FROM notifications AS newerPosted
                WHERE newerPosted.notificationKey = posted.notificationKey
                    AND newerPosted.status = 'POSTED'
                    AND newerPosted.id > posted.id
            )
        ORDER BY posted.notificationKey ASC, posted.observedAt DESC, posted.id DESC
    """)
    suspend fun getActiveNotificationsByPackage(packageName: String): List<NotificationEntity>

    @Query("""
        DELETE FROM notifications
        WHERE id IN (
            SELECT stale.id
            FROM notifications AS stale
            WHERE stale.observedAt < :cutoffTimestamp
                AND NOT (
                    stale.status != 'POSTED'
                    AND EXISTS (
                        SELECT 1
                        FROM notifications AS retainedPosted
                        WHERE retainedPosted.notificationKey = stale.notificationKey
                            AND retainedPosted.status = 'POSTED'
                            AND retainedPosted.observedAt >= :cutoffTimestamp
                            AND retainedPosted.id < stale.id
                            AND NOT EXISTS (
                                SELECT 1
                                FROM notifications AS interveningPosted
                                WHERE interveningPosted.notificationKey = stale.notificationKey
                                    AND interveningPosted.status = 'POSTED'
                                    AND interveningPosted.id > retainedPosted.id
                                    AND interveningPosted.id < stale.id
                            )
                            AND NOT EXISTS (
                                SELECT 1
                                FROM notifications AS interveningTerminal
                                WHERE interveningTerminal.notificationKey = stale.notificationKey
                                    AND interveningTerminal.status != 'POSTED'
                                    AND interveningTerminal.id > retainedPosted.id
                                    AND interveningTerminal.id < stale.id
                        )
                    )
                )
                AND NOT (
                    stale.status = 'POSTED'
                    AND EXISTS (
                        SELECT 1
                        FROM notifications AS retainedTerminal
                        WHERE retainedTerminal.notificationKey = stale.notificationKey
                            AND retainedTerminal.status != 'POSTED'
                            AND retainedTerminal.observedAt >= :cutoffTimestamp
                            AND retainedTerminal.id > stale.id
                            AND NOT EXISTS (
                                SELECT 1
                                FROM notifications AS interveningPosted
                                WHERE interveningPosted.notificationKey = stale.notificationKey
                                    AND interveningPosted.status = 'POSTED'
                                    AND interveningPosted.id > stale.id
                                    AND interveningPosted.id < retainedTerminal.id
                            )
                            AND NOT EXISTS (
                                SELECT 1
                                FROM notifications AS interveningTerminal
                                WHERE interveningTerminal.notificationKey = stale.notificationKey
                                    AND interveningTerminal.status != 'POSTED'
                                    AND interveningTerminal.id > stale.id
                                    AND interveningTerminal.id < retainedTerminal.id
                            )
                    )
                )
                AND NOT EXISTS (
                    SELECT 1
                    FROM notifications AS activePosted
                    WHERE activePosted.id = stale.id
                        AND activePosted.status = 'POSTED'
                        AND NOT EXISTS (
                            SELECT 1
                            FROM notifications AS terminal
                            WHERE terminal.notificationKey = activePosted.notificationKey
                                AND terminal.status != 'POSTED'
                                AND terminal.id > activePosted.id
                        )
                        AND NOT EXISTS (
                            SELECT 1
                            FROM notifications AS newerPosted
                            WHERE newerPosted.notificationKey = activePosted.notificationKey
                                AND newerPosted.status = 'POSTED'
                                AND newerPosted.id > activePosted.id
                        )
                )
        )
    """)
    suspend fun deleteOlderThan(cutoffTimestamp: Long): Int

    @Query("""
        DELETE FROM notifications
        WHERE id IN (
            SELECT overflow.id
            FROM notifications AS overflow
            WHERE overflow.id NOT IN (
                SELECT retained.id
                FROM notifications AS retained
                ORDER BY retained.id DESC
                LIMIT :maxRows
            )
                AND NOT EXISTS (
                    SELECT 1
                    FROM notifications AS activePosted
                    WHERE activePosted.id = overflow.id
                        AND activePosted.status = 'POSTED'
                        AND NOT EXISTS (
                            SELECT 1
                            FROM notifications AS terminal
                            WHERE terminal.notificationKey = activePosted.notificationKey
                                AND terminal.status != 'POSTED'
                                AND terminal.id > activePosted.id
                        )
                        AND NOT EXISTS (
                            SELECT 1
                            FROM notifications AS newerPosted
                            WHERE newerPosted.notificationKey = activePosted.notificationKey
                                AND newerPosted.status = 'POSTED'
                                AND newerPosted.id > activePosted.id
                        )
                )
                AND NOT (
                    overflow.status != 'POSTED'
                    AND EXISTS (
                        SELECT 1
                        FROM notifications AS retainedPosted
                        WHERE retainedPosted.notificationKey = overflow.notificationKey
                            AND retainedPosted.status = 'POSTED'
                            AND retainedPosted.id IN (
                                SELECT retained.id
                                FROM notifications AS retained
                                ORDER BY retained.id DESC
                                LIMIT :maxRows
                            )
                            AND retainedPosted.id < overflow.id
                            AND NOT EXISTS (
                                SELECT 1
                                FROM notifications AS interveningPosted
                                WHERE interveningPosted.notificationKey = overflow.notificationKey
                                    AND interveningPosted.status = 'POSTED'
                                    AND interveningPosted.id > retainedPosted.id
                                    AND interveningPosted.id < overflow.id
                            )
                            AND NOT EXISTS (
                                SELECT 1
                                FROM notifications AS interveningTerminal
                                WHERE interveningTerminal.notificationKey = overflow.notificationKey
                                    AND interveningTerminal.status != 'POSTED'
                                    AND interveningTerminal.id > retainedPosted.id
                                    AND interveningTerminal.id < overflow.id
                            )
                    )
                )
                AND NOT (
                    overflow.status = 'POSTED'
                    AND EXISTS (
                        SELECT 1
                        FROM notifications AS retainedTerminal
                        WHERE retainedTerminal.notificationKey = overflow.notificationKey
                            AND retainedTerminal.status != 'POSTED'
                            AND retainedTerminal.id IN (
                                SELECT retained.id
                                FROM notifications AS retained
                                ORDER BY retained.id DESC
                                LIMIT :maxRows
                            )
                            AND retainedTerminal.id > overflow.id
                            AND NOT EXISTS (
                                SELECT 1
                                FROM notifications AS interveningPosted
                                WHERE interveningPosted.notificationKey = overflow.notificationKey
                                    AND interveningPosted.status = 'POSTED'
                                    AND interveningPosted.id > overflow.id
                                    AND interveningPosted.id < retainedTerminal.id
                            )
                            AND NOT EXISTS (
                                SELECT 1
                                FROM notifications AS interveningTerminal
                                WHERE interveningTerminal.notificationKey = overflow.notificationKey
                                    AND interveningTerminal.status != 'POSTED'
                                    AND interveningTerminal.id > overflow.id
                                    AND interveningTerminal.id < retainedTerminal.id
                            )
                    )
                )
        )
    """)
    suspend fun trimToNewest(maxRows: Int): Int

    @Query("""
        SELECT EXISTS(
            SELECT 1
            FROM notifications AS terminal
            WHERE terminal.notificationKey = :notificationKey
                AND terminal.status != 'POSTED'
                AND terminal.id > :id
        )
    """)
    suspend fun hasTerminalEventAfter(
        notificationKey: String,
        id: Long
    ): Boolean

    @Query("""
        SELECT EXISTS(
            SELECT 1
            FROM notifications AS newerPosted
            WHERE newerPosted.notificationKey = :notificationKey
                AND newerPosted.status = 'POSTED'
                AND newerPosted.id > :id
        )
    """)
    suspend fun hasPostedEventAfter(
        notificationKey: String,
        id: Long
    ): Boolean

    @Query("""
        SELECT EXISTS(
            SELECT 1
            FROM notifications
            WHERE eventJournalId = :eventJournalId
        )
    """)
    suspend fun hasEventJournalId(eventJournalId: Long): Boolean

    @Query("""
        SELECT EXISTS(
            SELECT 1
            FROM notifications AS terminal
            WHERE terminal.notificationKey = :notificationKey
                AND terminal.status != 'POSTED'
                AND terminal.id > COALESCE(
                    (
                        SELECT MAX(posted.id)
                        FROM notifications AS posted
                        WHERE posted.notificationKey = :notificationKey
                            AND posted.status = 'POSTED'
                    ),
                    0
                )
        )
    """)
    suspend fun hasTerminalEventForLatestLifecycle(notificationKey: String): Boolean

    @Transaction
    suspend fun insertSyntheticRemovalIfStillActive(
        notification: NotificationEntity,
        removedAt: Long
    ): Boolean {
        val hasLaterLifecycleEvent = hasTerminalEventAfter(
            notificationKey = notification.notificationKey,
            id = notification.id
        ) || hasPostedEventAfter(
            notificationKey = notification.notificationKey,
            id = notification.id
        )
        if (hasLaterLifecycleEvent) {
            return false
        }

        return insert(
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
