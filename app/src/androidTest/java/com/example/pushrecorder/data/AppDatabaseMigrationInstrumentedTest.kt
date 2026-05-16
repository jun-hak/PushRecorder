package com.example.pushrecorder.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationInstrumentedTest {
    private lateinit var context: Context
    private var database: AppDatabase? = null

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(TEST_DATABASE_NAME)
    }

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(TEST_DATABASE_NAME)
    }

    @Test
    fun allLegacyVersionsMigrateToVersion7AndPreserveRepresentativeData() {
        (1..5).forEach { version ->
            context.deleteDatabase(TEST_DATABASE_NAME)
            createLegacyDatabase(version)

            database = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DATABASE_NAME)
                .addMigrations(*AppDatabase.MIGRATIONS)
                .allowMainThreadQueries()
                .build()

            val migratedDatabase = database!!.openHelper.writableDatabase
            assertEquals(7, migratedDatabase.version)
            assertColumnExists(migratedDatabase, tableName = "notifications", columnName = "observedAt")
            assertColumnExists(migratedDatabase, tableName = "notifications", columnName = "appLabel")
            assertColumnExists(migratedDatabase, tableName = "notifications", columnName = "appInfoResolved")
            assertColumnExists(migratedDatabase, tableName = "notifications", columnName = "eventJournalId")
            assertColumnExists(migratedDatabase, tableName = "apps", columnName = "lastRemovedAt")
            assertColumnExists(migratedDatabase, tableName = "notification_event_journal", columnName = "createdAt")
            assertMigratedNotification(version, migratedDatabase)
            assertMigratedAppBackfill(migratedDatabase)

            database!!.close()
            database = null
        }
    }

    @Test
    fun legacyPostedRowsMigratedToVersion7AreTerminalizedForActiveStateAndRetention() = runBlocking {
        createVersion5DatabaseWithOnlyPostedNotification()

        database = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DATABASE_NAME)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()

        val notificationDao = database!!.notificationDao()
        val migratedDatabase = database!!.openHelper.writableDatabase

        assertEquals(7, migratedDatabase.version)
        assertEquals(emptyList<NotificationEntity>(), notificationDao.getActiveNotifications())
        assertMigratedPostedRowHasTerminalEvent(migratedDatabase)
        assertEquals(2, notificationDao.deleteOlderThan(cutoffTimestamp = 10_000L))
        assertEquals(0, countRows(migratedDatabase, tableName = "notifications"))
    }

    @Test
    fun expiredLegacyPostedRowsMigratedToVersion7AreRemovedByNinetyDayRetentionCleanup() = runBlocking {
        createVersion5DatabaseWithOnlyPostedNotification()

        database = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DATABASE_NAME)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()

        val notificationDao = database!!.notificationDao()
        val repository = NotificationRepository(
            notificationDao = notificationDao,
            currentTimeMillis = { LEGACY_POSTED_TIMESTAMP + NINETY_ONE_DAYS_MILLIS }
        )
        val migratedDatabase = database!!.openHelper.writableDatabase

        assertEquals(emptyList<NotificationEntity>(), notificationDao.getActiveNotifications())
        assertEquals(2, countRows(migratedDatabase, tableName = "notifications"))

        val deletedCount = repository.deleteExpiredNotifications()

        assertEquals(2, deletedCount)
        assertEquals(0, countRows(migratedDatabase, tableName = "notifications"))
    }

    @Test
    fun version6DatabaseMigratesToVersion7WithDurableEventJournalSchema() {
        createVersion6Database()

        database = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DATABASE_NAME)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()

        val migratedDatabase = database!!.openHelper.writableDatabase

        assertEquals(7, migratedDatabase.version)
        assertColumnExists(migratedDatabase, tableName = "notifications", columnName = "eventJournalId")
        assertColumnExists(migratedDatabase, tableName = "notification_event_journal", columnName = "eventType")
        assertColumnExists(migratedDatabase, tableName = "notification_event_journal", columnName = "createdAt")
        assertEquals(1, countRows(migratedDatabase, tableName = "notifications"))
        assertEquals(0, countRows(migratedDatabase, tableName = "notification_event_journal"))
    }

    private fun createLegacyDatabase(version: Int) {
        val databaseFile = context.getDatabasePath(TEST_DATABASE_NAME)
        databaseFile.parentFile?.mkdirs()

        SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { sqliteDatabase ->
            when (version) {
                1 -> createVersion1Schema(sqliteDatabase)
                2 -> createVersion2Schema(sqliteDatabase)
                3 -> createVersion3Schema(sqliteDatabase, uniqueNotificationKeyIndex = true)
                4 -> createVersion3Schema(sqliteDatabase, uniqueNotificationKeyIndex = false)
                5 -> {
                    createVersion3Schema(sqliteDatabase, uniqueNotificationKeyIndex = false)
                    createAppsSchema(sqliteDatabase)
                }
                else -> error("Unsupported test DB version: $version")
            }
            insertLegacyRows(sqliteDatabase, version)
            sqliteDatabase.version = version
        }
    }

    private fun createVersion5DatabaseWithOnlyPostedNotification() {
        val databaseFile = context.getDatabasePath(TEST_DATABASE_NAME)
        databaseFile.parentFile?.mkdirs()

        SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { sqliteDatabase ->
            createVersion3Schema(sqliteDatabase, uniqueNotificationKeyIndex = false)
            createAppsSchema(sqliteDatabase)
            sqliteDatabase.execSQL(
                """
                    INSERT INTO notifications (
                        id, packageName, title, text, timestamp, status, flags, hasActions,
                        removalReason, timeToRemoval, notificationKey, removedAt
                    )
                    VALUES (
                        1, 'com.example.legacy', 'old title', 'old body', $LEGACY_POSTED_TIMESTAMP, 'POSTED', 0, 0,
                        'UNKNOWN', 0, 'legacy-live-key', 0
                    )
                """
            )
            sqliteDatabase.version = 5
        }
    }

    private fun createVersion6Database() {
        val databaseFile = context.getDatabasePath(TEST_DATABASE_NAME)
        databaseFile.parentFile?.mkdirs()

        SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { sqliteDatabase ->
            createVersion6Schema(sqliteDatabase)
            sqliteDatabase.execSQL(
                """
                    INSERT INTO notifications (
                        id, notificationKey, packageName, title, text, timestamp, observedAt,
                        appLabel, appInfoResolved, status, flags, hasActions, removalReason,
                        timeToRemoval, removedAt
                    )
                    VALUES (
                        1, 'v6-key', 'com.example.v6', 'v6 title', 'v6 body', 1000, 1000,
                        'Version 6 App', 1, 'POSTED', 0, 0, 'UNKNOWN', 0, 0
                    )
                """
            )
            sqliteDatabase.version = 6
        }
    }

    private fun createVersion1Schema(database: SQLiteDatabase) {
        database.execSQL(
            """
                CREATE TABLE IF NOT EXISTS notifications (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    packageName TEXT NOT NULL,
                    title TEXT NOT NULL,
                    text TEXT NOT NULL,
                    timestamp INTEGER NOT NULL,
                    status TEXT NOT NULL
                )
            """
        )
    }

    private fun createVersion2Schema(database: SQLiteDatabase) {
        database.execSQL(
            """
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
            """
        )
    }

    private fun createVersion3Schema(
        database: SQLiteDatabase,
        uniqueNotificationKeyIndex: Boolean
    ) {
        database.execSQL(
            """
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
                    timeToRemoval INTEGER NOT NULL DEFAULT 0,
                    notificationKey TEXT NOT NULL DEFAULT '',
                    removedAt INTEGER NOT NULL DEFAULT 0
                )
            """
        )
        val uniquePrefix = if (uniqueNotificationKeyIndex) "UNIQUE " else ""
        database.execSQL(
            "CREATE ${uniquePrefix}INDEX IF NOT EXISTS index_notifications_notificationKey ON notifications(notificationKey)"
        )
        database.execSQL("CREATE INDEX IF NOT EXISTS index_notifications_packageName_timestamp ON notifications(packageName, timestamp)")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_notifications_status ON notifications(status)")
    }

    private fun createAppsSchema(database: SQLiteDatabase) {
        database.execSQL(
            """
                CREATE TABLE IF NOT EXISTS apps (
                    packageName TEXT NOT NULL PRIMARY KEY,
                    label TEXT NOT NULL,
                    isInstalled INTEGER NOT NULL,
                    firstSeenAt INTEGER NOT NULL,
                    lastSeenAt INTEGER NOT NULL,
                    lastInstalledAt INTEGER NOT NULL,
                    lastRemovedAt INTEGER NOT NULL
                )
            """
        )
    }

    private fun createVersion6Schema(database: SQLiteDatabase) {
        database.execSQL(
            """
                CREATE TABLE IF NOT EXISTS notifications (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    notificationKey TEXT NOT NULL DEFAULT '',
                    packageName TEXT NOT NULL,
                    title TEXT NOT NULL,
                    text TEXT NOT NULL,
                    timestamp INTEGER NOT NULL,
                    observedAt INTEGER NOT NULL DEFAULT 0,
                    appLabel TEXT NOT NULL DEFAULT '',
                    appInfoResolved INTEGER NOT NULL DEFAULT 0,
                    status TEXT NOT NULL,
                    flags INTEGER NOT NULL DEFAULT 0,
                    hasActions INTEGER NOT NULL DEFAULT 0,
                    removalReason TEXT NOT NULL DEFAULT 'UNKNOWN',
                    timeToRemoval INTEGER NOT NULL DEFAULT 0,
                    removedAt INTEGER NOT NULL DEFAULT 0
                )
            """
        )
        database.execSQL("CREATE INDEX IF NOT EXISTS index_notifications_notificationKey ON notifications(notificationKey)")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_notifications_packageName_observedAt ON notifications(packageName, observedAt)")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_notifications_observedAt ON notifications(observedAt)")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_notifications_status ON notifications(status)")
        createAppsSchema(database)
    }

    private fun insertLegacyRows(
        database: SQLiteDatabase,
        version: Int
    ) {
        if (version == 1) {
            database.execSQL(
                """
                    INSERT INTO notifications (id, packageName, title, text, timestamp, status)
                    VALUES (1, 'com.example.chat', 'hello', 'posted body', 1000, 'POSTED')
                """
            )
            database.execSQL(
                """
                    INSERT INTO notifications (id, packageName, title, text, timestamp, status)
                    VALUES (2, 'com.example.chat', 'hello', 'removed body', 1000, 'REMOVED')
                """
            )
            return
        }

        val notificationKey = if (version >= 3) "chat-key" else ""
        database.execSQL(
            """
                INSERT INTO notifications (
                    id, packageName, title, text, timestamp, status, flags, hasActions,
                    removalReason, timeToRemoval${if (version >= 3) ", notificationKey, removedAt" else ""}
                )
                VALUES (
                    1, 'com.example.chat', 'hello', 'posted body', 1000, 'POSTED', 16, 1,
                    'UNKNOWN', 0${if (version >= 3) ", '$notificationKey', 0" else ""}
                )
            """
        )
        database.execSQL(
            """
                INSERT INTO notifications (
                    id, packageName, title, text, timestamp, status, flags, hasActions,
                    removalReason, timeToRemoval${if (version >= 3) ", notificationKey, removedAt" else ""}
                )
                VALUES (
                    2, 'com.example.chat', 'hello', 'removed body', 1000, 'REMOVED', 16, 1,
                    'USER_DISMISSED', 2000${if (version >= 3) ", '${notificationKey}-removed', 3000" else ""}
                )
            """
        )
    }

    private fun assertMigratedNotification(
        sourceVersion: Int,
        database: SupportSQLiteDatabase
    ) {
        database.query(
            """
                SELECT notificationKey, observedAt, appLabel, appInfoResolved
                FROM notifications
                WHERE id = 1
            """
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            val expectedKey = if (sourceVersion >= 3) "chat-key" else "legacy-1"
            assertEquals(expectedKey, cursor.getString(cursor.getColumnIndexOrThrow("notificationKey")))
            assertEquals(1_000L, cursor.getLong(cursor.getColumnIndexOrThrow("observedAt")))
            assertEquals("com.example.chat", cursor.getString(cursor.getColumnIndexOrThrow("appLabel")))
            assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("appInfoResolved")))
        }
    }

    private fun assertMigratedAppBackfill(database: SupportSQLiteDatabase) {
        database.query(
            """
                SELECT label, isInstalled, firstSeenAt, lastSeenAt
                FROM apps
                WHERE packageName = 'com.example.chat'
            """
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("com.example.chat", cursor.getString(cursor.getColumnIndexOrThrow("label")))
            assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("isInstalled")))
            assertEquals(1_000L, cursor.getLong(cursor.getColumnIndexOrThrow("firstSeenAt")))
            assertTrue(cursor.getLong(cursor.getColumnIndexOrThrow("lastSeenAt")) >= 1_000L)
        }
    }

    private fun assertMigratedPostedRowHasTerminalEvent(database: SupportSQLiteDatabase) {
        database.query(
            """
                SELECT status, notificationKey, timestamp, observedAt, removedAt
                FROM notifications
                WHERE notificationKey = 'legacy-live-key'
                ORDER BY id ASC
            """
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("POSTED", cursor.getString(cursor.getColumnIndexOrThrow("status")))
            assertEquals(LEGACY_POSTED_TIMESTAMP, cursor.getLong(cursor.getColumnIndexOrThrow("observedAt")))
            assertEquals(0L, cursor.getLong(cursor.getColumnIndexOrThrow("removedAt")))

            assertTrue(cursor.moveToNext())
            assertEquals("REMOVED", cursor.getString(cursor.getColumnIndexOrThrow("status")))
            assertEquals(LEGACY_POSTED_TIMESTAMP, cursor.getLong(cursor.getColumnIndexOrThrow("timestamp")))
            assertEquals(LEGACY_POSTED_TIMESTAMP, cursor.getLong(cursor.getColumnIndexOrThrow("observedAt")))
            assertEquals(LEGACY_POSTED_TIMESTAMP, cursor.getLong(cursor.getColumnIndexOrThrow("removedAt")))
        }
    }

    private fun assertColumnExists(
        database: SupportSQLiteDatabase,
        tableName: String,
        columnName: String
    ) {
        database.query("PRAGMA table_info(`$tableName`)").use { cursor ->
            val nameColumnIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameColumnIndex) == columnName) {
                    return
                }
            }
        }

        error("Missing column $tableName.$columnName")
    }

    private fun countRows(
        database: SupportSQLiteDatabase,
        tableName: String
    ): Int {
        database.query("SELECT COUNT(*) FROM `$tableName`").use { cursor ->
            assertTrue(cursor.moveToFirst())
            return cursor.getInt(0)
        }
    }

    private companion object {
        private const val TEST_DATABASE_NAME = "migration-test.db"
        private const val LEGACY_POSTED_TIMESTAMP = 1_000L
        private const val NINETY_ONE_DAYS_MILLIS = 91L * 24L * 60L * 60L * 1_000L
    }
}
