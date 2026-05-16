package com.example.pushrecorder.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        NotificationEntity::class,
        AppRecordEntity::class,
        NotificationEventJournalEntity::class
    ],
    version = 7,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun notificationDao(): NotificationDao
    abstract fun appRecordDao(): AppRecordDao
    abstract fun notificationEventJournalDao(): NotificationEventJournalDao

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

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE notifications ADD COLUMN notificationKey TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE notifications ADD COLUMN removedAt INTEGER NOT NULL DEFAULT 0")
                database.execSQL("UPDATE notifications SET notificationKey = 'legacy-' || id")
                database.execSQL("UPDATE notifications SET removedAt = timestamp WHERE status != 'POSTED'")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_notifications_notificationKey ON notifications(notificationKey)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_notifications_packageName_timestamp ON notifications(packageName, timestamp)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_notifications_status ON notifications(status)")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("DROP INDEX IF EXISTS index_notifications_notificationKey")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_notifications_notificationKey ON notifications(notificationKey)")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS apps (
                        packageName TEXT NOT NULL PRIMARY KEY,
                        label TEXT NOT NULL,
                        isInstalled INTEGER NOT NULL,
                        firstSeenAt INTEGER NOT NULL,
                        lastSeenAt INTEGER NOT NULL,
                        lastInstalledAt INTEGER NOT NULL,
                        lastRemovedAt INTEGER NOT NULL
                    )
                """)
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE notifications ADD COLUMN observedAt INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE notifications ADD COLUMN appLabel TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE notifications ADD COLUMN appInfoResolved INTEGER NOT NULL DEFAULT 0")
                database.execSQL("UPDATE notifications SET observedAt = CASE WHEN removedAt > 0 THEN removedAt ELSE timestamp END")
                database.execSQL("UPDATE notifications SET appLabel = packageName WHERE appLabel = ''")
                database.execSQL("""
                    INSERT INTO notifications (
                        notificationKey,
                        packageName,
                        title,
                        text,
                        timestamp,
                        status,
                        flags,
                        hasActions,
                        removalReason,
                        timeToRemoval,
                        removedAt,
                        observedAt,
                        appLabel,
                        appInfoResolved
                    )
                    SELECT
                        notificationKey,
                        packageName,
                        title,
                        text,
                        timestamp,
                        'REMOVED',
                        flags,
                        hasActions,
                        'UNKNOWN',
                        0,
                        observedAt,
                        observedAt,
                        appLabel,
                        appInfoResolved
                    FROM notifications
                    WHERE status = 'POSTED'
                """)
                database.execSQL("DROP INDEX IF EXISTS index_notifications_packageName_timestamp")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_notifications_observedAt ON notifications(observedAt)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_notifications_packageName_observedAt ON notifications(packageName, observedAt)")
                database.execSQL("""
                    INSERT OR IGNORE INTO apps (
                        packageName,
                        label,
                        isInstalled,
                        firstSeenAt,
                        lastSeenAt,
                        lastInstalledAt,
                        lastRemovedAt
                    )
                    SELECT
                        packageName,
                        packageName,
                        0,
                        MIN(observedAt),
                        MAX(observedAt),
                        0,
                        0
                    FROM notifications
                    GROUP BY packageName
                """)
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE notifications ADD COLUMN eventJournalId INTEGER DEFAULT NULL")
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS notification_event_journal (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        eventType TEXT NOT NULL,
                        notificationKey TEXT NOT NULL,
                        packageName TEXT NOT NULL,
                        title TEXT NOT NULL,
                        text TEXT NOT NULL,
                        sourcePostTime INTEGER NOT NULL,
                        observedAt INTEGER NOT NULL,
                        flags INTEGER NOT NULL,
                        hasActions INTEGER NOT NULL,
                        appLabel TEXT NOT NULL,
                        appInfoResolved INTEGER NOT NULL,
                        systemReason INTEGER,
                        createdAt INTEGER NOT NULL
                    )
                """)
                database.execSQL("""
                    CREATE UNIQUE INDEX IF NOT EXISTS index_notifications_eventJournalId
                    ON notifications(eventJournalId)
                """)
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_notification_event_journal_notificationKey
                    ON notification_event_journal(notificationKey)
                """)
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_notification_event_journal_createdAt
                    ON notification_event_journal(createdAt)
                """)
            }
        }

        val MIGRATIONS = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7
        )

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "notification_database"
                )
                .addMigrations(*MIGRATIONS)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
