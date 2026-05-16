package com.example.pushrecorder.data

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationDaoQueryPlanInstrumentedTest {
    private lateinit var database: AppDatabase
    private lateinit var sqliteDatabase: SupportSQLiteDatabase

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        sqliteDatabase = database.openHelper.writableDatabase
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun activeNotificationByKeyPlanUsesLifecycleIndexes() {
        val plan = explainQueryPlan(
            """
                SELECT *
                FROM notifications AS posted
                WHERE posted.notificationKey = ?
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
            """,
            "shared-key"
        )

        assertUsesIndex(plan, "index_notifications_status_notificationKey_id")
        assertUsesIndex(plan, "index_notifications_notificationKey_id")
    }

    @Test
    fun activeNotificationsByPackagePlanUsesPackageLifecycleIndex() {
        val plan = explainQueryPlan(
            """
                SELECT *
                FROM notifications AS posted
                WHERE posted.packageName = ?
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
            """,
            "com.example.chat"
        )

        assertUsesIndex(plan, "index_notifications_packageName_status_notificationKey_id")
        assertUsesIndex(plan, "index_notifications_notificationKey_id")
        assertUsesIndex(plan, "index_notifications_status_notificationKey_id")
    }

    @Test
    fun notificationsByPackagePagingPlanUsesPackageObservedAtIndex() {
        val plan = explainQueryPlan(
            """
                SELECT *
                FROM notifications
                WHERE packageName = ?
                    AND (
                        ? = ''
                        OR packageName LIKE '%' || ? || '%' ESCAPE '\'
                        OR appLabel LIKE '%' || ? || '%' ESCAPE '\'
                        OR title LIKE '%' || ? || '%' ESCAPE '\'
                        OR text LIKE '%' || ? || '%' ESCAPE '\'
                    )
                ORDER BY observedAt DESC, id DESC
            """,
            "com.example.chat",
            "",
            "",
            "",
            "",
            ""
        )

        assertUsesIndex(plan, "index_notifications_packageName_observedAt_id")
    }

    private fun explainQueryPlan(sql: String, vararg bindArgs: Any): List<String> {
        val normalizedSql = sql.trimIndent()
        return sqliteDatabase.query("EXPLAIN QUERY PLAN $normalizedSql", bindArgs).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.getString(DETAIL_COLUMN_INDEX))
                }
            }
        }
    }

    private fun assertUsesIndex(plan: List<String>, indexName: String) {
        assertTrue(
            "Expected query plan to use $indexName, but plan was:\n${plan.joinToString(separator = "\n")}",
            plan.any { detail -> detail.contains(indexName) }
        )
    }

    private companion object {
        const val DETAIL_COLUMN_INDEX = 3
    }
}
