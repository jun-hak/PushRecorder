package com.example.pushrecorder.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [NotificationEntity::class], version = 2)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun notificationDao(): NotificationDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 기존 테이블 백업
                database.execSQL("CREATE TABLE IF NOT EXISTS notifications_backup AS SELECT * FROM notifications")
                
                // 기존 테이블 삭제
                database.execSQL("DROP TABLE notifications")
                
                // 새로운 스키마로 테이블 생성
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS notifications (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        packageName TEXT NOT NULL,
                        title TEXT NOT NULL,
                        text TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        flags INTEGER NOT NULL DEFAULT 0,
                        hasActions INTEGER NOT NULL DEFAULT 0,
                        removalReason TEXT NOT NULL DEFAULT 'UNKNOWN',
                        timeToRemoval INTEGER NOT NULL DEFAULT 0
                    )
                """)
                
                // 데이터 복원
                database.execSQL("""
                    INSERT INTO notifications (id, packageName, title, text, timestamp, status, flags, hasActions, removalReason, timeToRemoval)
                    SELECT id, packageName, title, text, timestamp, status, 0, 0, 'UNKNOWN', 0
                    FROM notifications_backup
                """)
                
                // 백업 테이블 삭제
                database.execSQL("DROP TABLE notifications_backup")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "notification_database"
                )
                .addMigrations(MIGRATION_1_2)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
} 