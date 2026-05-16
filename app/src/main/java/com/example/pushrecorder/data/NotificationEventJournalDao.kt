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
        ORDER BY id ASC
    """)
    suspend fun pendingEvents(): List<NotificationEventJournalEntity>

    @Query("DELETE FROM notification_event_journal WHERE id = :id")
    suspend fun deleteById(id: Long): Int
}
