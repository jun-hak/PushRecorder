package com.example.pushrecorder.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AppRecordDao {
    @Query("SELECT * FROM apps WHERE packageName = :packageName LIMIT 1")
    suspend fun getAppRecord(packageName: String): AppRecordEntity?

    @Query("SELECT * FROM apps WHERE isInstalled = 1")
    suspend fun getInstalledAppRecords(): List<AppRecordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: AppRecordEntity)
}
