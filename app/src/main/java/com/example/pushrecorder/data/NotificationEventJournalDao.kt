package com.example.pushrecorder.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface NotificationEventJournalDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: NotificationEventJournalEntity): Long

    @Query("""
        SELECT *
        FROM notification_event_journal
        WHERE nextAttemptAt <= :now
        ORDER BY id ASC
        LIMIT :limit
    """)
    suspend fun pendingEvents(
        now: Long,
        limit: Int
    ): List<NotificationEventJournalEntity>

    @Query("DELETE FROM notification_event_journal WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("SELECT retryCount FROM notification_event_journal WHERE id = :id")
    suspend fun retryCountForId(id: Long): Int?

    @Query("""
        UPDATE notification_event_journal
        SET retryCount = :retryCount,
            lastAttemptAt = :lastAttemptAt,
            nextAttemptAt = :nextAttemptAt,
            lastError = :lastError
        WHERE id = :id
    """)
    suspend fun markAttemptFailed(
        id: Long,
        retryCount: Int,
        lastAttemptAt: Long,
        nextAttemptAt: Long,
        lastError: String?
    ): Int

    @Query("""
        DELETE FROM notification_event_journal
        WHERE id IN (
            SELECT expired.id
            FROM notification_event_journal AS expired
            WHERE expired.createdAt < :cutoffTimestamp
        )
    """)
    suspend fun deleteCreatedBefore(cutoffTimestamp: Long): Int

    @Query("""
        DELETE FROM notification_event_journal
        WHERE id IN (
            SELECT overflow.id
            FROM notification_event_journal AS overflow
            WHERE overflow.id NOT IN (
                SELECT retained.id
                FROM notification_event_journal AS retained
                ORDER BY retained.id DESC
                LIMIT :maxRows
            )
        )
    """)
    suspend fun trimToNewest(maxRows: Int): Int
}
