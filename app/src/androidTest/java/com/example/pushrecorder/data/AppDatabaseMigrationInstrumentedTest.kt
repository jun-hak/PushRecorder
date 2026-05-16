package com.example.pushrecorder.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationInstrumentedTest {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory()
    )

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
    fun oldestLegacySchemaRunsAllMigrationsAndValidatesFinalRoomSchema() {
        createLegacyDatabase(version = 1)

        migrationHelper.runMigrationsAndValidate(
            TEST_DATABASE_NAME,
            11,
            true,
            *AppDatabase.MIGRATIONS
        ).use { migratedDatabase ->
            assertEquals(11, migratedDatabase.version)
            assertColumnExists(migratedDatabase, tableName = "notifications", columnName = "eventJournalId")
            assertColumnExists(migratedDatabase, tableName = "notification_event_journal", columnName = "retryCount")
            assertNotificationStorageIndexDefinitions(migratedDatabase)
            assertMigratedNotification(sourceVersion = 1, migratedDatabase)
        }
    }

    @Test
    fun exportedVersion10SchemaRunsAllMigrationsAndValidatesFinalRoomSchema() {
        migrationHelper.createDatabase(TEST_DATABASE_NAME, 10).use { oldDatabase ->
            oldDatabase.execSQL(
                """
                    INSERT INTO notification_event_journal (
                        eventType, notificationKey, packageName, title, text, sourcePostTime,
                        observedAt, flags, hasActions, appLabel, appInfoResolved, systemReason, createdAt
                    )
                    VALUES (
                        'POSTED', 'helper-key', 'com.example.helper', 'helper title',
                        'helper body', 4000, 4100, 64, 1, 'Helper App', 1, NULL, 4200
                    )
                """
            )
        }

        migrationHelper.runMigrationsAndValidate(
            TEST_DATABASE_NAME,
            11,
            true,
            *AppDatabase.MIGRATIONS
        ).use { migratedDatabase ->
            assertEquals(11, migratedDatabase.version)
            assertColumnExists(migratedDatabase, tableName = "notification_event_journal", columnName = "retryCount")
            assertColumnExists(migratedDatabase, tableName = "notification_event_journal", columnName = "nextAttemptAt")
            assertNotificationStorageIndexDefinitions(migratedDatabase)
            assertEquals(1, countRows(migratedDatabase, tableName = "notification_event_journal"))
        }
    }

    @Test
    fun allLegacyVersionsMigrateToVersion11AndPreserveRepresentativeData() {
        (1..5).forEach { version ->
            context.deleteDatabase(TEST_DATABASE_NAME)
            createLegacyDatabase(version)

            database = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DATABASE_NAME)
                .addMigrations(*AppDatabase.MIGRATIONS)
                .allowMainThreadQueries()
                .build()

            val migratedDatabase = database!!.openHelper.writableDatabase
            assertEquals(11, migratedDatabase.version)
            assertColumnExists(migratedDatabase, tableName = "notifications", columnName = "observedAt")
            assertColumnExists(migratedDatabase, tableName = "notifications", columnName = "appLabel")
            assertColumnExists(migratedDatabase, tableName = "notifications", columnName = "appInfoResolved")
            assertColumnExists(migratedDatabase, tableName = "notifications", columnName = "eventJournalId")
            assertColumnExists(migratedDatabase, tableName = "apps", columnName = "lastRemovedAt")
            assertColumnExists(migratedDatabase, tableName = "notification_event_journal", columnName = "createdAt")
            assertPendingJournalRetryColumns(migratedDatabase)
            assertNotificationStorageIndexDefinitions(migratedDatabase)
            assertMigratedNotification(version, migratedDatabase)
            assertMigratedAppBackfill(migratedDatabase)

            database!!.close()
            database = null
        }
    }

    @Test
    fun legacyPostedRowsMigratedToVersion11AreTerminalizedForActiveStateAndRetention() = runBlocking {
        createVersion5DatabaseWithOnlyPostedNotification()

        database = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DATABASE_NAME)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()

        val notificationDao = database!!.notificationDao()
        val migratedDatabase = database!!.openHelper.writableDatabase

        assertEquals(11, migratedDatabase.version)
        assertNotificationStorageIndexDefinitions(migratedDatabase)
        assertEquals(emptyList<NotificationEntity>(), notificationDao.getActiveNotifications())
        assertMigratedPostedRowHasTerminalEvent(migratedDatabase)
        assertEquals(2, notificationDao.deleteOlderThan(cutoffTimestamp = 10_000L))
        assertEquals(0, countRows(migratedDatabase, tableName = "notifications"))
    }

    @Test
    fun expiredLegacyPostedRowsMigratedToVersion11AreRemovedByNinetyDayRetentionCleanup() = runBlocking {
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
    fun version6DatabaseMigratesToVersion11WithDurableEventJournalSchemaAndLifecycleIndexes() {
        createVersion6Database()

        database = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DATABASE_NAME)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()

        val migratedDatabase = database!!.openHelper.writableDatabase

        assertEquals(11, migratedDatabase.version)
        assertColumnExists(migratedDatabase, tableName = "notifications", columnName = "eventJournalId")
        assertColumnExists(migratedDatabase, tableName = "notification_event_journal", columnName = "eventType")
        assertColumnExists(migratedDatabase, tableName = "notification_event_journal", columnName = "createdAt")
        assertPendingJournalRetryColumns(migratedDatabase)
        assertNotificationStorageIndexDefinitions(migratedDatabase)
        assertEquals(1, countRows(migratedDatabase, tableName = "notifications"))
        assertEquals(0, countRows(migratedDatabase, tableName = "notification_event_journal"))
    }

    @Test
    fun version7And8DatabasesMigrateToVersion11ByPreservingRowsAndCreatingMissingIndexes() {
        listOf(7, 8).forEach { version ->
            context.deleteDatabase(TEST_DATABASE_NAME)
            createDatabaseWithHardeningVersion(version)

            database = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DATABASE_NAME)
                .addMigrations(*AppDatabase.MIGRATIONS)
                .allowMainThreadQueries()
                .build()

            val migratedDatabase = database!!.openHelper.writableDatabase

            assertEquals(11, migratedDatabase.version)
            assertNotificationStorageIndexDefinitions(migratedDatabase)
            assertPendingJournalRetryDefaults(migratedDatabase)
            assertEquals(2, countRows(migratedDatabase, tableName = "notifications"))
            assertEquals(1, countRows(migratedDatabase, tableName = "notification_event_journal"))
            assertMigratedHardeningVersionRows(version, migratedDatabase)

            database!!.close()
            database = null
        }
    }

    @Test
    fun version9DatabaseMigratesToVersion11ByPreservingNotificationRowsAndCreatingIndexes() {
        createVersion9DatabaseWithoutHardeningIndexes()

        database = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DATABASE_NAME)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()

        val migratedDatabase = database!!.openHelper.writableDatabase

        assertEquals(11, migratedDatabase.version)
        assertNotificationStorageIndexDefinitions(migratedDatabase)
        assertPendingJournalRetryColumns(migratedDatabase)
        assertEquals(2, countRows(migratedDatabase, tableName = "notifications"))
        assertMigratedVersion9NotificationRows(migratedDatabase)
    }

    @Test
    fun version10DatabaseMigratesToVersion11ByAddingPendingJournalRetryMetadata() {
        createVersion10DatabaseWithPendingJournal()

        database = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DATABASE_NAME)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()

        val migratedDatabase = database!!.openHelper.writableDatabase

        assertEquals(11, migratedDatabase.version)
        assertNotificationStorageIndexDefinitions(migratedDatabase)
        assertPendingJournalRetryDefaults(migratedDatabase)
        assertEquals(1, countRows(migratedDatabase, tableName = "notification_event_journal"))
    }

    @Test
    fun everyMigrationEntryPointCreatesPerformanceCriticalNotificationStorageIndexes() {
        (1..10).forEach { sourceVersion ->
            context.deleteDatabase(TEST_DATABASE_NAME)
            createDatabaseAtVersion(sourceVersion)

            database = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DATABASE_NAME)
                .addMigrations(*AppDatabase.MIGRATIONS)
                .allowMainThreadQueries()
                .build()

            val migratedDatabase = database!!.openHelper.writableDatabase

            assertEquals(11, migratedDatabase.version)
            assertNotificationStorageIndexDefinitions(migratedDatabase)

            database!!.close()
            database = null
        }
    }

    private fun createDatabaseAtVersion(version: Int) {
        when (version) {
            in 1..5 -> createLegacyDatabase(version)
            6 -> createVersion6Database()
            7, 8 -> createDatabaseWithHardeningVersion(version)
            9 -> createVersion9DatabaseWithoutHardeningIndexes()
            10 -> createVersion10DatabaseWithPendingJournal()
            else -> error("Unsupported migration source version: $version")
        }
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

    private fun createDatabaseWithHardeningVersion(version: Int) {
        val databaseFile = context.getDatabasePath(TEST_DATABASE_NAME)
        databaseFile.parentFile?.mkdirs()

        SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { sqliteDatabase ->
            when (version) {
                7 -> createVersion7Schema(sqliteDatabase)
                8 -> createVersion8Schema(sqliteDatabase)
                else -> error("Unsupported hardening test DB version: $version")
            }
            sqliteDatabase.execSQL(
                """
                    INSERT INTO notification_event_journal (
                        id, eventType, notificationKey, packageName, title, text, sourcePostTime,
                        observedAt, flags, hasActions, appLabel, appInfoResolved, systemReason, createdAt
                    )
                    VALUES (
                        77, 'POSTED', 'v$version-key', 'com.example.v$version', 'v$version title',
                        'journal body', 3000, 3100, 32, 1, 'Version $version App', 1, NULL, 3200
                    )
                """
            )
            sqliteDatabase.execSQL(
                """
                    INSERT INTO notifications (
                        id, notificationKey, packageName, title, text, timestamp, observedAt,
                        appLabel, appInfoResolved, status, flags, hasActions, removalReason,
                        timeToRemoval, removedAt, eventJournalId
                    )
                    VALUES (
                        1, 'v$version-key', 'com.example.v$version', 'v$version title', 'v$version body',
                        3000, 3100, 'Version $version App', 1, 'POSTED', 32, 1, 'UNKNOWN', 0, 0, 77
                    ),
                    (
                        2, 'v$version-key', 'com.example.v$version', 'v$version title', 'v$version removed body',
                        3000, 3600, 'Version $version App', 1, 'REMOVED', 32, 1,
                        'AUTO_REMOVED', 600, 3600, NULL
                    )
                """
            )
            sqliteDatabase.version = version
        }
    }

    private fun createVersion9DatabaseWithoutHardeningIndexes() {
        val databaseFile = context.getDatabasePath(TEST_DATABASE_NAME)
        databaseFile.parentFile?.mkdirs()

        SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { sqliteDatabase ->
            createVersion9SchemaWithoutHardeningIndexes(sqliteDatabase)
            sqliteDatabase.execSQL(
                """
                    INSERT INTO notifications (
                        id, notificationKey, packageName, title, text, timestamp, observedAt,
                        appLabel, appInfoResolved, status, flags, hasActions, removalReason,
                        timeToRemoval, removedAt, eventJournalId
                    )
                    VALUES (
                        1, 'v9-key', 'com.example.v9', 'v9 title', 'v9 body', 2000, 2100,
                        'Version 9 App', 1, 'POSTED', 16, 1, 'UNKNOWN', 0, 0, NULL
                    ),
                    (
                        2, 'v9-key', 'com.example.v9', 'v9 title', 'v9 removed body', 2000, 2500,
                        'Version 9 App', 1, 'REMOVED', 16, 1, 'USER_DISMISSED', 500, 2500, 42
                    )
                """
            )
            sqliteDatabase.version = 9
        }
    }

    private fun createVersion10DatabaseWithPendingJournal() {
        val databaseFile = context.getDatabasePath(TEST_DATABASE_NAME)
        databaseFile.parentFile?.mkdirs()

        SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { sqliteDatabase ->
            createVersion9SchemaWithoutHardeningIndexes(sqliteDatabase)
            sqliteDatabase.execSQL("CREATE INDEX IF NOT EXISTS index_notifications_notificationKey_id ON notifications(notificationKey, id)")
            sqliteDatabase.execSQL("CREATE INDEX IF NOT EXISTS index_notifications_notificationKey_status_id ON notifications(notificationKey, status, id)")
            sqliteDatabase.execSQL("CREATE INDEX IF NOT EXISTS index_notifications_packageName_observedAt_id ON notifications(packageName, observedAt, id)")
            sqliteDatabase.execSQL("CREATE INDEX IF NOT EXISTS index_notifications_packageName_id ON notifications(packageName, id)")
            sqliteDatabase.execSQL("CREATE INDEX IF NOT EXISTS index_notifications_packageName_status_notificationKey_id ON notifications(packageName, status, notificationKey, id)")
            sqliteDatabase.execSQL("CREATE INDEX IF NOT EXISTS index_notifications_status_notificationKey_id ON notifications(status, notificationKey, id)")
            sqliteDatabase.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_notifications_eventJournalId ON notifications(eventJournalId)")
            sqliteDatabase.execSQL(
                """
                    INSERT INTO notification_event_journal (
                        id, eventType, notificationKey, packageName, title, text, sourcePostTime,
                        observedAt, flags, hasActions, appLabel, appInfoResolved, systemReason, createdAt
                    )
                    VALUES (
                        77, 'POSTED', 'v10-key', 'com.example.v10', 'v10 title',
                        'journal body', 3000, 3100, 32, 1, 'Version 10 App', 1, NULL, 3200
                    )
                """
            )
            sqliteDatabase.version = 10
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

    private fun createVersion7Schema(database: SQLiteDatabase) {
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
                    removedAt INTEGER NOT NULL DEFAULT 0,
                    eventJournalId INTEGER DEFAULT NULL
                )
            """
        )
        database.execSQL("CREATE INDEX IF NOT EXISTS index_notifications_notificationKey ON notifications(notificationKey)")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_notifications_packageName_observedAt ON notifications(packageName, observedAt)")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_notifications_observedAt ON notifications(observedAt)")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_notifications_status ON notifications(status)")
        database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_notifications_eventJournalId ON notifications(eventJournalId)")
        createNotificationEventJournalSchema(database)
        createAppsSchema(database)
    }

    private fun createVersion8Schema(database: SQLiteDatabase) {
        createVersion7Schema(database)
        database.execSQL("CREATE INDEX IF NOT EXISTS index_notifications_notificationKey_id ON notifications(notificationKey, id)")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_notifications_notificationKey_status_id ON notifications(notificationKey, status, id)")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_notifications_packageName_id ON notifications(packageName, id)")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_notifications_status_notificationKey_id ON notifications(status, notificationKey, id)")
    }

    private fun createVersion9SchemaWithoutHardeningIndexes(database: SQLiteDatabase) {
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
                    removedAt INTEGER NOT NULL DEFAULT 0,
                    eventJournalId INTEGER DEFAULT NULL
                )
            """
        )
        database.execSQL("CREATE INDEX IF NOT EXISTS index_notifications_notificationKey ON notifications(notificationKey)")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_notifications_packageName_observedAt ON notifications(packageName, observedAt)")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_notifications_observedAt ON notifications(observedAt)")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_notifications_status ON notifications(status)")
        createNotificationEventJournalSchema(database)
        createAppsSchema(database)
    }

    private fun createNotificationEventJournalSchema(database: SQLiteDatabase) {
        database.execSQL(
            """
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
            """
        )
        database.execSQL("CREATE INDEX IF NOT EXISTS index_notification_event_journal_notificationKey ON notification_event_journal(notificationKey)")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_notification_event_journal_createdAt ON notification_event_journal(createdAt)")
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

    private fun assertMigratedHardeningVersionRows(
        sourceVersion: Int,
        database: SupportSQLiteDatabase
    ) {
        database.query(
            """
                SELECT notificationKey, packageName, title, text, observedAt, status, flags,
                    hasActions, removalReason, timeToRemoval, removedAt, eventJournalId
                FROM notifications
                WHERE id IN (1, 2)
                ORDER BY id
            """
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("v$sourceVersion-key", cursor.getString(cursor.getColumnIndexOrThrow("notificationKey")))
            assertEquals("com.example.v$sourceVersion", cursor.getString(cursor.getColumnIndexOrThrow("packageName")))
            assertEquals("v$sourceVersion title", cursor.getString(cursor.getColumnIndexOrThrow("title")))
            assertEquals("v$sourceVersion body", cursor.getString(cursor.getColumnIndexOrThrow("text")))
            assertEquals(3_100L, cursor.getLong(cursor.getColumnIndexOrThrow("observedAt")))
            assertEquals("POSTED", cursor.getString(cursor.getColumnIndexOrThrow("status")))
            assertEquals(32, cursor.getInt(cursor.getColumnIndexOrThrow("flags")))
            assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("hasActions")))
            assertEquals("UNKNOWN", cursor.getString(cursor.getColumnIndexOrThrow("removalReason")))
            assertEquals(0L, cursor.getLong(cursor.getColumnIndexOrThrow("timeToRemoval")))
            assertEquals(0L, cursor.getLong(cursor.getColumnIndexOrThrow("removedAt")))
            assertEquals(77L, cursor.getLong(cursor.getColumnIndexOrThrow("eventJournalId")))

            assertTrue(cursor.moveToNext())
            assertEquals("v$sourceVersion-key", cursor.getString(cursor.getColumnIndexOrThrow("notificationKey")))
            assertEquals("com.example.v$sourceVersion", cursor.getString(cursor.getColumnIndexOrThrow("packageName")))
            assertEquals("v$sourceVersion title", cursor.getString(cursor.getColumnIndexOrThrow("title")))
            assertEquals("v$sourceVersion removed body", cursor.getString(cursor.getColumnIndexOrThrow("text")))
            assertEquals(3_600L, cursor.getLong(cursor.getColumnIndexOrThrow("observedAt")))
            assertEquals("REMOVED", cursor.getString(cursor.getColumnIndexOrThrow("status")))
            assertEquals(32, cursor.getInt(cursor.getColumnIndexOrThrow("flags")))
            assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("hasActions")))
            assertEquals("AUTO_REMOVED", cursor.getString(cursor.getColumnIndexOrThrow("removalReason")))
            assertEquals(600L, cursor.getLong(cursor.getColumnIndexOrThrow("timeToRemoval")))
            assertEquals(3_600L, cursor.getLong(cursor.getColumnIndexOrThrow("removedAt")))
            assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("eventJournalId")))
        }

        database.query(
            """
                SELECT eventType, notificationKey, packageName, title, text, sourcePostTime,
                    observedAt, flags, hasActions, appLabel, appInfoResolved, systemReason, createdAt
                FROM notification_event_journal
                WHERE id = 77
            """
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("POSTED", cursor.getString(cursor.getColumnIndexOrThrow("eventType")))
            assertEquals("v$sourceVersion-key", cursor.getString(cursor.getColumnIndexOrThrow("notificationKey")))
            assertEquals("com.example.v$sourceVersion", cursor.getString(cursor.getColumnIndexOrThrow("packageName")))
            assertEquals("v$sourceVersion title", cursor.getString(cursor.getColumnIndexOrThrow("title")))
            assertEquals("journal body", cursor.getString(cursor.getColumnIndexOrThrow("text")))
            assertEquals(3_000L, cursor.getLong(cursor.getColumnIndexOrThrow("sourcePostTime")))
            assertEquals(3_100L, cursor.getLong(cursor.getColumnIndexOrThrow("observedAt")))
            assertEquals(32, cursor.getInt(cursor.getColumnIndexOrThrow("flags")))
            assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("hasActions")))
            assertEquals("Version $sourceVersion App", cursor.getString(cursor.getColumnIndexOrThrow("appLabel")))
            assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("appInfoResolved")))
            assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("systemReason")))
            assertEquals(3_200L, cursor.getLong(cursor.getColumnIndexOrThrow("createdAt")))
        }
    }

    private fun assertMigratedVersion9NotificationRows(database: SupportSQLiteDatabase) {
        database.query(
            """
                SELECT notificationKey, packageName, title, text, observedAt, status, flags,
                    hasActions, removalReason, timeToRemoval, removedAt, eventJournalId
                FROM notifications
                WHERE id IN (1, 2)
                ORDER BY id
            """
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("v9-key", cursor.getString(cursor.getColumnIndexOrThrow("notificationKey")))
            assertEquals("com.example.v9", cursor.getString(cursor.getColumnIndexOrThrow("packageName")))
            assertEquals("v9 title", cursor.getString(cursor.getColumnIndexOrThrow("title")))
            assertEquals("v9 body", cursor.getString(cursor.getColumnIndexOrThrow("text")))
            assertEquals(2_100L, cursor.getLong(cursor.getColumnIndexOrThrow("observedAt")))
            assertEquals("POSTED", cursor.getString(cursor.getColumnIndexOrThrow("status")))
            assertEquals(16, cursor.getInt(cursor.getColumnIndexOrThrow("flags")))
            assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("hasActions")))
            assertEquals("UNKNOWN", cursor.getString(cursor.getColumnIndexOrThrow("removalReason")))
            assertEquals(0L, cursor.getLong(cursor.getColumnIndexOrThrow("timeToRemoval")))
            assertEquals(0L, cursor.getLong(cursor.getColumnIndexOrThrow("removedAt")))
            assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("eventJournalId")))

            assertTrue(cursor.moveToNext())
            assertEquals("v9-key", cursor.getString(cursor.getColumnIndexOrThrow("notificationKey")))
            assertEquals("com.example.v9", cursor.getString(cursor.getColumnIndexOrThrow("packageName")))
            assertEquals("v9 title", cursor.getString(cursor.getColumnIndexOrThrow("title")))
            assertEquals("v9 removed body", cursor.getString(cursor.getColumnIndexOrThrow("text")))
            assertEquals(2_500L, cursor.getLong(cursor.getColumnIndexOrThrow("observedAt")))
            assertEquals("REMOVED", cursor.getString(cursor.getColumnIndexOrThrow("status")))
            assertEquals(16, cursor.getInt(cursor.getColumnIndexOrThrow("flags")))
            assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("hasActions")))
            assertEquals("USER_DISMISSED", cursor.getString(cursor.getColumnIndexOrThrow("removalReason")))
            assertEquals(500L, cursor.getLong(cursor.getColumnIndexOrThrow("timeToRemoval")))
            assertEquals(2_500L, cursor.getLong(cursor.getColumnIndexOrThrow("removedAt")))
            assertEquals(42L, cursor.getLong(cursor.getColumnIndexOrThrow("eventJournalId")))
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

    private fun assertNotificationStorageIndexDefinitions(database: SupportSQLiteDatabase) {
        requiredNotificationStorageIndexes.forEach { requiredIndex ->
            assertIndexDefinition(
                database = database,
                tableName = requiredIndex.tableName,
                indexName = requiredIndex.databaseName,
                columns = requiredIndex.columns,
                unique = requiredIndex.unique
            )
        }
    }

    private fun assertPendingJournalRetryColumns(database: SupportSQLiteDatabase) {
        assertColumnExists(database, tableName = "notification_event_journal", columnName = "retryCount")
        assertColumnExists(database, tableName = "notification_event_journal", columnName = "lastAttemptAt")
        assertColumnExists(database, tableName = "notification_event_journal", columnName = "nextAttemptAt")
        assertColumnExists(database, tableName = "notification_event_journal", columnName = "lastError")
    }

    private fun assertPendingJournalRetryDefaults(database: SupportSQLiteDatabase) {
        database.query(
            """
                SELECT retryCount, lastAttemptAt, nextAttemptAt, lastError, createdAt
                FROM notification_event_journal
                WHERE id = 77
            """
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("retryCount")))
            assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("lastAttemptAt")))
            assertEquals(
                cursor.getLong(cursor.getColumnIndexOrThrow("createdAt")),
                cursor.getLong(cursor.getColumnIndexOrThrow("nextAttemptAt"))
            )
            assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("lastError")))
        }
    }

    private fun assertIndexDefinition(
        database: SupportSQLiteDatabase,
        tableName: String,
        indexName: String,
        columns: List<String>,
        unique: Boolean = false
    ) {
        database.query("PRAGMA index_list(`$tableName`)").use { cursor ->
            val nameColumnIndex = cursor.getColumnIndexOrThrow("name")
            val uniqueColumnIndex = cursor.getColumnIndexOrThrow("unique")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameColumnIndex) == indexName) {
                    assertEquals(if (unique) 1 else 0, cursor.getInt(uniqueColumnIndex))
                    assertEquals(columns, indexColumns(database, indexName))
                    return
                }
            }
        }

        error("Missing index $tableName.$indexName")
    }

    private fun indexColumns(
        database: SupportSQLiteDatabase,
        indexName: String
    ): List<String> {
        val columns = mutableListOf<String>()
        database.query("PRAGMA index_info(`$indexName`)").use { cursor ->
            val nameColumnIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                columns += cursor.getString(nameColumnIndex)
            }
        }
        return columns
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

    private data class RequiredIndex(
        val tableName: String,
        val columns: List<String>,
        val unique: Boolean = false
    ) {
        val databaseName: String = "index_${tableName}_${columns.joinToString("_")}"
    }

    private companion object {
        private const val TEST_DATABASE_NAME = "migration-test.db"
        private const val LEGACY_POSTED_TIMESTAMP = 1_000L
        private const val NINETY_ONE_DAYS_MILLIS = 91L * 24L * 60L * 60L * 1_000L

        private val requiredNotificationStorageIndexes = listOf(
            RequiredIndex(tableName = "notifications", columns = listOf("notificationKey")),
            RequiredIndex(tableName = "notifications", columns = listOf("notificationKey", "id")),
            RequiredIndex(tableName = "notifications", columns = listOf("notificationKey", "status", "id")),
            RequiredIndex(tableName = "notifications", columns = listOf("packageName", "observedAt")),
            RequiredIndex(tableName = "notifications", columns = listOf("packageName", "observedAt", "id")),
            RequiredIndex(tableName = "notifications", columns = listOf("packageName", "id")),
            RequiredIndex(tableName = "notifications", columns = listOf("packageName", "status", "notificationKey", "id")),
            RequiredIndex(tableName = "notifications", columns = listOf("observedAt")),
            RequiredIndex(tableName = "notifications", columns = listOf("status")),
            RequiredIndex(tableName = "notifications", columns = listOf("status", "notificationKey", "id")),
            RequiredIndex(tableName = "notifications", columns = listOf("eventJournalId"), unique = true),
            RequiredIndex(tableName = "notification_event_journal", columns = listOf("notificationKey")),
            RequiredIndex(tableName = "notification_event_journal", columns = listOf("createdAt")),
            RequiredIndex(tableName = "notification_event_journal", columns = listOf("nextAttemptAt", "id"))
        )
    }
}
