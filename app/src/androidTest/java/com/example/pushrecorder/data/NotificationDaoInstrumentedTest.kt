package com.example.pushrecorder.data

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationDaoInstrumentedTest {
    private lateinit var database: AppDatabase
    private lateinit var notificationDao: NotificationDao
    private lateinit var journalDao: NotificationEventJournalDao

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        notificationDao = database.notificationDao()
        journalDao = database.notificationEventJournalDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun activeNotificationByKeyUsesLatestPostedUntilTerminalEvent() = runBlocking {
        val firstId = notificationDao.insert(notification(title = "first", observedAt = 1_000L))
        val secondId = notificationDao.insert(notification(title = "second", observedAt = 2_000L))

        assertTrue(firstId > 0L)
        assertTrue(secondId > firstId)
        assertEquals("second", notificationDao.getActiveNotificationByKey("shared-key")?.title)

        notificationDao.insert(
            notification(
                title = "second removed",
                status = NotificationStatus.REMOVED,
                observedAt = 3_000L,
                removedAt = 3_000L
            )
        )

        assertNull(notificationDao.getActiveNotificationByKey("shared-key"))
    }

    @Test
    fun activeNotificationByKeyTreatsClickedAsTerminalAndAllowsRepost() = runBlocking {
        notificationDao.insert(notification(title = "posted", observedAt = 1_000L))
        notificationDao.insert(
            notification(
                title = "clicked",
                status = NotificationStatus.CLICKED,
                observedAt = 2_000L,
                removedAt = 2_000L
            )
        )

        assertNull(notificationDao.getActiveNotificationByKey("shared-key"))

        notificationDao.insert(notification(title = "posted again", observedAt = 3_000L))

        assertEquals("posted again", notificationDao.getActiveNotificationByKey("shared-key")?.title)
    }

    @Test
    fun activeNotificationByKeyTreatsLaterTerminalInsertAsTerminalWhenClockMovesBackward() = runBlocking {
        notificationDao.insert(notification(title = "posted", observedAt = 5_000L))
        notificationDao.insert(
            notification(
                title = "removed with older clock",
                status = NotificationStatus.REMOVED,
                observedAt = 4_000L,
                removedAt = 4_000L
            )
        )

        assertNull(notificationDao.getActiveNotificationByKey("shared-key"))
        assertEquals(emptyList<NotificationEntity>(), notificationDao.getActiveNotifications())
    }

    @Test
    fun pagedSearchMatchesSnapshotFieldsAndOrdersByObservedAt() = runBlocking {
        notificationDao.insert(
            notification(
                notificationKey = "old-chat",
                packageName = "com.example.chat",
                appLabel = "Chatty",
                title = "Old message",
                text = "hello",
                observedAt = 1_000L
            )
        )
        notificationDao.insert(
            notification(
                notificationKey = "new-chat",
                packageName = "com.example.other",
                appLabel = "Chatty",
                title = "New message",
                text = "world",
                observedAt = 2_000L
            )
        )
        notificationDao.insert(
            notification(
                notificationKey = "bank",
                packageName = "com.example.bank",
                appLabel = "Bank",
                title = "Deposit",
                text = "salary",
                observedAt = 3_000L
            )
        )

        val results = loadNotifications(query = "Chatty")

        assertEquals(listOf("New message", "Old message"), results.map { it.title })
    }

    @Test
    fun pagedSearchTreatsPercentAsLiteralText() = runBlocking {
        notificationDao.insert(
            notification(
                notificationKey = "literal-percent",
                title = "Battery 100% charged",
                observedAt = 1_000L
            )
        )
        notificationDao.insert(
            notification(
                notificationKey = "wildcard-candidate",
                title = "Battery 1000 charged",
                observedAt = 2_000L
            )
        )

        val results = loadNotifications(query = NotificationSearchQuery.escapeLikeTerm("100%"))

        assertEquals(listOf("literal-percent"), results.map { it.notificationKey })
    }

    @Test
    fun pagedSearchTreatsUnderscoreAsLiteralText() = runBlocking {
        notificationDao.insert(
            notification(
                notificationKey = "literal-underscore",
                title = "chat_thread",
                observedAt = 1_000L
            )
        )
        notificationDao.insert(
            notification(
                notificationKey = "wildcard-candidate",
                title = "chat-thread",
                observedAt = 2_000L
            )
        )

        val results = loadNotifications(query = NotificationSearchQuery.escapeLikeTerm("chat_thread"))

        assertEquals(listOf("literal-underscore"), results.map { it.notificationKey })
    }

    @Test
    fun pagedNotificationGroupsCountsLogicalNotificationsNotLifecycleRows() = runBlocking {
        notificationDao.insert(
            notification(
                notificationKey = "chat-1",
                packageName = "com.example.chat",
                title = "first posted",
                observedAt = 1_000L
            )
        )
        notificationDao.insert(
            notification(
                notificationKey = "chat-1",
                packageName = "com.example.chat",
                title = "first removed",
                status = NotificationStatus.REMOVED,
                observedAt = 2_000L,
                removedAt = 2_000L
            )
        )
        notificationDao.insert(
            notification(
                notificationKey = "chat-2",
                packageName = "com.example.chat",
                title = "second posted",
                observedAt = 3_000L
            )
        )
        notificationDao.insert(
            notification(
                notificationKey = "bank-1",
                packageName = "com.example.bank",
                title = "bank posted",
                observedAt = 4_000L
            )
        )

        val groups = loadGroups(query = "")

        val chatGroup = groups.single { group ->
            group.latestNotification.packageName == "com.example.chat"
        }
        assertEquals(2, chatGroup.notificationCount)
        assertEquals("second posted", chatGroup.latestNotification.title)
    }

    @Test
    fun pagedNotificationGroupsUsesAppendOrderWhenObservedAtMovesBackward() = runBlocking {
        notificationDao.insert(
            notification(
                notificationKey = "chat-1",
                packageName = "com.example.chat",
                title = "first posted high clock",
                observedAt = 5_000L
            )
        )
        notificationDao.insert(
            notification(
                notificationKey = "chat-1",
                packageName = "com.example.chat",
                title = "first removed older clock",
                status = NotificationStatus.REMOVED,
                observedAt = 4_000L,
                removedAt = 4_000L
            )
        )
        notificationDao.insert(
            notification(
                notificationKey = "chat-2",
                packageName = "com.example.chat",
                title = "second posted latest append",
                observedAt = 3_000L
            )
        )

        val groups = loadGroups(query = "")

        assertEquals(1, groups.size)
        assertEquals(2, groups.single().notificationCount)
        assertEquals("second posted latest append", groups.single().latestNotification.title)
    }

    @Test
    fun pagedNotificationGroupsSortsGroupsByAppendOrderWhenObservedAtMovesBackward() = runBlocking {
        notificationDao.insert(
            notification(
                notificationKey = "bank-1",
                packageName = "com.example.bank",
                title = "bank high clock",
                observedAt = 5_000L
            )
        )
        notificationDao.insert(
            notification(
                notificationKey = "chat-1",
                packageName = "com.example.chat",
                title = "chat latest append older clock",
                observedAt = 1_000L
            )
        )

        val groups = loadGroups(query = "")

        assertEquals(
            listOf("com.example.chat", "com.example.bank"),
            groups.map { group -> group.latestNotification.packageName }
        )
    }

    @Test
    fun insertIgnoresExplicitPrimaryKeyConflict() = runBlocking {
        val first = notification(
            notificationKey = "first",
            title = "first"
        ).copy(id = 7L)
        val conflicting = notification(
            notificationKey = "conflicting",
            title = "conflicting"
        ).copy(id = 7L)

        assertEquals(7L, notificationDao.insert(first))
        assertEquals(-1L, notificationDao.insert(conflicting))

        assertEquals(listOf("first"), loadNotifications(query = "").map { notification -> notification.title })
    }

    @Test
    fun insertIgnoresDuplicateEventJournalId() = runBlocking {
        val first = notification(
            notificationKey = "first",
            title = "first"
        ).copy(eventJournalId = 42L)
        val conflicting = notification(
            notificationKey = "conflicting",
            title = "conflicting"
        ).copy(eventJournalId = 42L)

        assertTrue(notificationDao.insert(first) > 0L)
        assertEquals(-1L, notificationDao.insert(conflicting))

        assertEquals(listOf("first"), loadNotifications(query = "").map { notification -> notification.title })
    }

    @Test
    fun pagedNotificationsRetrievesNextPageAcrossPageSizeBoundary() = runBlocking {
        (1..55).forEach { index ->
            notificationDao.insert(
                notification(
                    notificationKey = "boundary-$index",
                    title = "Notification $index",
                    observedAt = index.toLong()
                )
            )
        }

        val source = notificationDao.getPagedNotifications(query = "")
        val firstPage = source.loadPage(
            PagingSource.LoadParams.Refresh(
                key = null,
                loadSize = 50,
                placeholdersEnabled = false
            )
        )
        val secondPage = source.loadPage(
            PagingSource.LoadParams.Append(
                key = requireNotNull(firstPage.nextKey),
                loadSize = 50,
                placeholdersEnabled = false
            )
        )

        assertEquals(50, firstPage.data.size)
        assertEquals("Notification 55", firstPage.data.first().title)
        assertEquals("Notification 6", firstPage.data.last().title)
        assertEquals(
            listOf("Notification 5", "Notification 4", "Notification 3", "Notification 2", "Notification 1"),
            secondPage.data.map { it.title }
        )
    }

    @Test
    fun pagedNotificationsByPackageRetrievesOnlyMatchingPackageAtBoundary() = runBlocking {
        (1..52).forEach { index ->
            notificationDao.insert(
                notification(
                    notificationKey = "chat-$index",
                    packageName = "com.example.chat",
                    title = "Chat $index",
                    observedAt = index.toLong()
                )
            )
        }
        notificationDao.insert(
            notification(
                notificationKey = "bank",
                packageName = "com.example.bank",
                title = "Bank newest",
                observedAt = 1_000L
            )
        )

        val source = notificationDao.getPagedNotificationsByPackage(
            packageName = "com.example.chat",
            query = ""
        )
        val firstPage = source.loadPage(
            PagingSource.LoadParams.Refresh(
                key = null,
                loadSize = 50,
                placeholdersEnabled = false
            )
        )
        val secondPage = source.loadPage(
            PagingSource.LoadParams.Append(
                key = requireNotNull(firstPage.nextKey),
                loadSize = 50,
                placeholdersEnabled = false
            )
        )

        assertEquals(50, firstPage.data.size)
        assertEquals("Chat 52", firstPage.data.first().title)
        assertTrue(firstPage.data.all { it.packageName == "com.example.chat" })
        assertEquals(listOf("Chat 2", "Chat 1"), secondPage.data.map { it.title })
        assertTrue(secondPage.data.all { it.packageName == "com.example.chat" })
    }

    @Test
    fun pagedNotificationGroupsRetrievesNextPageAcrossGroupBoundary() = runBlocking {
        (1..35).forEach { index ->
            notificationDao.insert(
                notification(
                    notificationKey = "group-$index",
                    packageName = "com.example.app$index",
                    title = "Group $index",
                    observedAt = index.toLong()
                )
            )
        }

        val source = notificationDao.getPagedNotificationGroups(query = "")
        val firstPage = source.loadPage(
            PagingSource.LoadParams.Refresh(
                key = null,
                loadSize = 30,
                placeholdersEnabled = false
            )
        )
        val secondPage = source.loadPage(
            PagingSource.LoadParams.Append(
                key = requireNotNull(firstPage.nextKey),
                loadSize = 30,
                placeholdersEnabled = false
            )
        )

        assertEquals(30, firstPage.data.size)
        assertEquals("Group 35", firstPage.data.first().latestNotification.title)
        assertEquals("Group 6", firstPage.data.last().latestNotification.title)
        assertEquals(
            listOf("Group 5", "Group 4", "Group 3", "Group 2", "Group 1"),
            secondPage.data.map { it.latestNotification.title }
        )
    }

    @Test
    fun deleteOlderThanPreservesActiveRowOlderThanCutoff() = runBlocking {
        notificationDao.insert(
            notification(
                notificationKey = "old-observed",
                timestamp = 9_999L,
                observedAt = 1_000L
            )
        )
        notificationDao.insert(
            notification(
                notificationKey = "old-terminal",
                timestamp = 1_000L,
                observedAt = 2_000L
            )
        )
        notificationDao.insert(
            notification(
                notificationKey = "old-terminal",
                timestamp = 1_000L,
                status = NotificationStatus.REMOVED,
                observedAt = 3_000L,
                removedAt = 3_000L
            )
        )
        notificationDao.insert(
            notification(
                notificationKey = "new-observed",
                timestamp = 1_000L,
                observedAt = 9_999L
            )
        )

        val deleted = notificationDao.deleteOlderThan(cutoffTimestamp = 5_000L)
        val remaining = notificationDao.getActiveNotifications()

        assertEquals(2, deleted)
        assertEquals(listOf("new-observed", "old-observed"), remaining.map { it.notificationKey }.sorted())
    }

    @Test
    fun deleteOlderThanPrunesExpiredRemovedRowWithoutTreatingItAsActive() = runBlocking {
        notificationDao.insert(
            notification(
                notificationKey = "expired-removed-only",
                title = "expired removed only",
                status = NotificationStatus.REMOVED,
                observedAt = 3_000L,
                removedAt = 3_000L
            )
        )
        notificationDao.insert(
            notification(
                notificationKey = "expired-active-posted",
                title = "expired active posted",
                observedAt = 4_000L
            )
        )

        val deleted = notificationDao.deleteOlderThan(cutoffTimestamp = 5_000L)

        assertEquals(1, deleted)
        assertEquals(
            listOf("expired active posted"),
            loadNotifications(query = "").map { notification -> notification.title }
        )
        assertEquals(
            listOf("expired active posted"),
            notificationDao.getActiveNotifications().map { notification -> notification.title }
        )
    }

    @Test
    fun deleteOlderThanRetainsRowsAtCutoffAndDeletesRowsBeforeCutoff() = runBlocking {
        notificationDao.insert(
            notification(
                notificationKey = "before-cutoff",
                title = "before cutoff posted",
                observedAt = 4_998L
            )
        )
        notificationDao.insert(
            notification(
                notificationKey = "before-cutoff",
                title = "before cutoff removed",
                status = NotificationStatus.REMOVED,
                observedAt = 4_999L,
                removedAt = 4_999L
            )
        )
        notificationDao.insert(
            notification(
                notificationKey = "at-cutoff",
                title = "at cutoff posted",
                observedAt = 5_000L
            )
        )
        notificationDao.insert(
            notification(
                notificationKey = "at-cutoff",
                title = "at cutoff removed",
                status = NotificationStatus.REMOVED,
                observedAt = 5_000L,
                removedAt = 5_000L
            )
        )

        val deleted = notificationDao.deleteOlderThan(cutoffTimestamp = 5_000L)

        assertEquals(2, deleted)
        assertEquals(
            listOf("at cutoff removed", "at cutoff posted"),
            loadNotifications(query = "").map { notification -> notification.title }
        )
        assertEquals(emptyList<NotificationEntity>(), notificationDao.getActiveNotifications())
    }

    @Test
    fun deleteOlderThanDoesNotPreservePostedClosedByLaterTerminalInsertWithOlderObservedAt() = runBlocking {
        notificationDao.insert(
            notification(
                notificationKey = "closed-by-insert-order",
                title = "posted",
                observedAt = 4_000L
            )
        )
        notificationDao.insert(
            notification(
                notificationKey = "closed-by-insert-order",
                title = "removed with older clock",
                status = NotificationStatus.REMOVED,
                observedAt = 3_000L,
                removedAt = 3_000L
            )
        )

        val deleted = notificationDao.deleteOlderThan(cutoffTimestamp = 5_000L)

        assertEquals(2, deleted)
        assertEquals(emptyList<NotificationEntity>(), notificationDao.getActiveNotifications())
    }

    @Test
    fun deleteOlderThanPreservesOlderClockTerminalWhenPostIsInsideRetention() = runBlocking {
        notificationDao.insert(
            notification(
                notificationKey = "retained-post-closed",
                title = "posted inside retention",
                observedAt = 6_000L
            )
        )
        notificationDao.insert(
            notification(
                notificationKey = "retained-post-closed",
                title = "removed outside retention by clock",
                status = NotificationStatus.REMOVED,
                observedAt = 4_000L,
                removedAt = 4_000L
            )
        )

        val deleted = notificationDao.deleteOlderThan(cutoffTimestamp = 5_000L)

        assertEquals(0, deleted)
        assertEquals(emptyList<NotificationEntity>(), notificationDao.getActiveNotifications())
        assertEquals(
            listOf(NotificationStatus.POSTED, NotificationStatus.REMOVED),
            loadNotifications(query = "").map { notification -> notification.status }
        )
    }

    @Test
    fun deleteOlderThanDeletesExpiredRowsWhenNotificationKeyIsReusedInsideRetention() = runBlocking {
        notificationDao.insert(
            notification(
                notificationKey = "reused-key",
                title = "old posted",
                observedAt = 3_000L
            )
        )
        notificationDao.insert(
            notification(
                notificationKey = "reused-key",
                title = "old removed",
                status = NotificationStatus.REMOVED,
                observedAt = 4_000L,
                removedAt = 4_000L
            )
        )
        notificationDao.insert(
            notification(
                notificationKey = "reused-key",
                title = "new posted",
                observedAt = 6_000L
            )
        )

        val deleted = notificationDao.deleteOlderThan(cutoffTimestamp = 5_000L)

        assertEquals(2, deleted)
        assertEquals(
            listOf("new posted"),
            loadNotifications(query = "").map { notification -> notification.title }
        )
        assertEquals("new posted", notificationDao.getActiveNotificationByKey("reused-key")?.title)
    }

    @Test
    fun deleteOlderThanPreservesActiveRepostOlderThanCutoffWhileDeletingExpiredHistory() = runBlocking {
        notificationDao.insert(
            notification(
                notificationKey = "reposted-key",
                title = "expired posted",
                observedAt = 1_000L
            )
        )
        notificationDao.insert(
            notification(
                notificationKey = "reposted-key",
                title = "expired removed",
                status = NotificationStatus.REMOVED,
                observedAt = 2_000L,
                removedAt = 2_000L
            )
        )
        notificationDao.insert(
            notification(
                notificationKey = "reposted-key",
                title = "active repost older than cutoff",
                observedAt = 3_000L
            )
        )

        val deleted = notificationDao.deleteOlderThan(cutoffTimestamp = 5_000L)

        assertEquals(2, deleted)
        assertEquals(
            listOf("active repost older than cutoff"),
            loadNotifications(query = "").map { notification -> notification.title }
        )
        assertEquals(
            "active repost older than cutoff",
            notificationDao.getActiveNotificationByKey("reposted-key")?.title
        )
    }

    @Test
    fun deleteOlderThanDoesNotKeepExpiredLifecycleBecauseOlderLifecycleWasRetained() = runBlocking {
        notificationDao.insert(
            notification(
                notificationKey = "multi-lifecycle-key",
                title = "retained posted",
                observedAt = 6_000L
            )
        )
        notificationDao.insert(
            notification(
                notificationKey = "multi-lifecycle-key",
                title = "retained removed",
                status = NotificationStatus.REMOVED,
                observedAt = 7_000L,
                removedAt = 7_000L
            )
        )
        notificationDao.insert(
            notification(
                notificationKey = "multi-lifecycle-key",
                title = "expired posted",
                observedAt = 3_000L
            )
        )
        notificationDao.insert(
            notification(
                notificationKey = "multi-lifecycle-key",
                title = "expired removed",
                status = NotificationStatus.REMOVED,
                observedAt = 4_000L,
                removedAt = 4_000L
            )
        )

        val deleted = notificationDao.deleteOlderThan(cutoffTimestamp = 5_000L)

        assertEquals(2, deleted)
        assertEquals(
            listOf("retained removed", "retained posted"),
            loadNotifications(query = "").map { notification -> notification.title }
        )
        assertEquals(emptyList<NotificationEntity>(), notificationDao.getActiveNotifications())
    }

    @Test
    fun trimToNewestDeletesOldestInactiveRowsAndPreservesActiveRows() = runBlocking {
        notificationDao.insert(
            notification(
                notificationKey = "old-active",
                title = "old active",
                observedAt = 1_000L
            )
        )
        notificationDao.insert(
            notification(
                notificationKey = "old-inactive",
                title = "old inactive posted",
                observedAt = 2_000L
            )
        )
        notificationDao.insert(
            notification(
                notificationKey = "old-inactive",
                title = "old inactive removed",
                status = NotificationStatus.REMOVED,
                observedAt = 3_000L,
                removedAt = 3_000L
            )
        )
        notificationDao.insert(
            notification(
                notificationKey = "new-inactive",
                title = "new inactive removed",
                status = NotificationStatus.REMOVED,
                observedAt = 4_000L,
                removedAt = 4_000L
            )
        )

        val deleted = notificationDao.trimToNewest(maxRows = 2)

        assertEquals(1, deleted)
        assertEquals(
            listOf("new inactive removed", "old inactive removed", "old active"),
            loadNotifications(query = "").map { notification -> notification.title }
        )
        assertEquals("old active", notificationDao.getActiveNotificationByKey("old-active")?.title)
    }

    @Test
    fun trimToNewestPrunesOverflowRemovedRowWithoutTreatingItAsActive() = runBlocking {
        notificationDao.insert(
            notification(
                notificationKey = "old-active",
                title = "old active",
                observedAt = 1L
            )
        )
        notificationDao.insert(
            notification(
                notificationKey = "overflow-removed-only",
                title = "overflow removed only",
                status = NotificationStatus.REMOVED,
                observedAt = 2L,
                removedAt = 2L
            )
        )
        notificationDao.insert(
            notification(
                notificationKey = "retained-removed-only",
                title = "retained removed only",
                status = NotificationStatus.REMOVED,
                observedAt = 3L,
                removedAt = 3L
            )
        )

        val deleted = notificationDao.trimToNewest(maxRows = 1)

        assertEquals(1, deleted)
        assertEquals(
            listOf("retained removed only", "old active"),
            loadNotifications(query = "").map { notification -> notification.title }
        )
        assertEquals(
            listOf("old active"),
            notificationDao.getActiveNotifications().map { notification -> notification.title }
        )
    }

    @Test
    fun trimToNewestUsesAppendOrderInsteadOfObservedAtWhenChoosingOverflowRows() = runBlocking {
        notificationDao.insert(
            notification(
                notificationKey = "old-high-clock-1",
                title = "old high clock 1",
                status = NotificationStatus.REMOVED,
                observedAt = 10_000L,
                removedAt = 10_000L
            )
        )
        notificationDao.insert(
            notification(
                notificationKey = "old-high-clock-2",
                title = "old high clock 2",
                status = NotificationStatus.REMOVED,
                observedAt = 9_000L,
                removedAt = 9_000L
            )
        )
        notificationDao.insert(
            notification(
                notificationKey = "latest-append-1",
                title = "latest append 1",
                status = NotificationStatus.REMOVED,
                observedAt = 100L,
                removedAt = 100L
            )
        )
        notificationDao.insert(
            notification(
                notificationKey = "latest-append-2",
                title = "latest append 2",
                status = NotificationStatus.REMOVED,
                observedAt = 50L,
                removedAt = 50L
            )
        )

        val deleted = notificationDao.trimToNewest(maxRows = 2)

        assertEquals(2, deleted)
        assertEquals(
            listOf("latest append 1", "latest append 2"),
            loadNotifications(query = "").map { notification -> notification.title }
        )
    }

    @Test
    fun trimToNewestDoesNotDeleteWhenRowCountEqualsThreshold() = runBlocking {
        (1..3).forEach { index ->
            notificationDao.insert(
                notification(
                    notificationKey = "row-$index",
                    title = "row $index",
                    status = NotificationStatus.REMOVED,
                    observedAt = index.toLong(),
                    removedAt = index.toLong()
                )
            )
        }

        val deleted = notificationDao.trimToNewest(maxRows = 3)

        assertEquals(0, deleted)
        assertEquals(
            listOf("row 3", "row 2", "row 1"),
            loadNotifications(query = "").map { notification -> notification.title }
        )
    }

    @Test
    fun trimToNewestPreservesOverflowPostedRowForRetainedTerminal() = runBlocking {
        notificationDao.insert(
            notification(
                notificationKey = "closed-row-cap",
                title = "posted partner",
                observedAt = 1L
            )
        )
        notificationDao.insert(
            notification(
                notificationKey = "unrelated-overflow",
                title = "unrelated overflow",
                status = NotificationStatus.REMOVED,
                observedAt = 2L,
                removedAt = 2L
            )
        )
        notificationDao.insert(
            notification(
                notificationKey = "closed-row-cap",
                title = "retained terminal",
                status = NotificationStatus.REMOVED,
                removalReason = RemovalReason.USER_DISMISSED,
                observedAt = 3L,
                removedAt = 3L
            )
        )

        val deleted = notificationDao.trimToNewest(maxRows = 1)

        assertEquals(1, deleted)
        assertEquals(
            listOf("retained terminal", "posted partner"),
            loadNotifications(query = "").map { notification -> notification.title }
        )
        assertEquals(emptyList<NotificationEntity>(), notificationDao.getActiveNotifications())
    }

    @Test
    fun trimToNewestPrunesOverflowPostedLifecycleWhenKeyIsReusedInsideRowCap() = runBlocking {
        notificationDao.insert(
            notification(
                notificationKey = "reused-row-cap",
                title = "old posted",
                observedAt = 1L
            )
        )
        notificationDao.insert(
            notification(
                notificationKey = "reused-row-cap",
                title = "old removed",
                status = NotificationStatus.REMOVED,
                removalReason = RemovalReason.USER_DISMISSED,
                observedAt = 2L,
                removedAt = 2L
            )
        )
        notificationDao.insert(
            notification(
                notificationKey = "reused-row-cap",
                title = "active repost",
                observedAt = 3L
            )
        )
        notificationDao.insert(
            notification(
                notificationKey = "newest-terminal",
                title = "newest terminal",
                status = NotificationStatus.REMOVED,
                observedAt = 4L,
                removedAt = 4L
            )
        )

        val deleted = notificationDao.trimToNewest(maxRows = 2)

        assertEquals(2, deleted)
        assertEquals(
            listOf("newest terminal", "active repost"),
            loadNotifications(query = "").map { notification -> notification.title }
        )
        assertEquals("active repost", notificationDao.getActiveNotificationByKey("reused-row-cap")?.title)
    }

    @Test
    fun repositoryCleanupKeepsRetainedLifecyclePartnerAfterTimeAndRowCapCleanup() = runBlocking {
        val repository = NotificationRepository(
            notificationDao = notificationDao,
            currentTimeMillis = { 10_000L },
            retentionPolicy = NotificationRetentionPolicy(
                maxAgeMillis = 5_000L,
                maxRows = 2,
                maxEvents = null
            )
        )
        notificationDao.insert(
            notification(
                notificationKey = "expired-lifecycle",
                title = "expired posted",
                observedAt = 1_000L
            )
        )
        notificationDao.insert(
            notification(
                notificationKey = "expired-lifecycle",
                title = "expired removed",
                status = NotificationStatus.REMOVED,
                observedAt = 2_000L,
                removedAt = 2_000L
            )
        )
        notificationDao.insert(
            notification(
                notificationKey = "cross-boundary",
                title = "posted before cutoff",
                observedAt = 4_000L
            )
        )
        notificationDao.insert(
            notification(
                notificationKey = "cross-boundary",
                title = "removed after cutoff",
                status = NotificationStatus.REMOVED,
                observedAt = 6_000L,
                removedAt = 6_000L
            )
        )
        notificationDao.insert(
            notification(
                notificationKey = "newest-unrelated",
                title = "newest unrelated",
                status = NotificationStatus.REMOVED,
                observedAt = 7_000L,
                removedAt = 7_000L
            )
        )

        val deleted = repository.deleteExpiredNotifications()

        assertEquals(2, deleted)
        assertEquals(
            listOf("newest unrelated", "removed after cutoff", "posted before cutoff"),
            loadNotifications(query = "").map { notification -> notification.title }
        )
        assertEquals(emptyList<NotificationEntity>(), notificationDao.getActiveNotifications())
    }

    @Test
    fun repositoryCleanupUsesMaxEventsLimitAndPreservesActiveRows() = runBlocking {
        val repository = NotificationRepository(
            notificationDao = notificationDao,
            currentTimeMillis = { 10_000L },
            retentionPolicy = NotificationRetentionPolicy(
                maxAgeMillis = null,
                maxRows = null,
                maxEvents = 2
            )
        )
        notificationDao.insert(
            notification(
                notificationKey = "old-active",
                title = "old active",
                observedAt = 1L
            )
        )
        listOf("new-1", "new-2", "new-3").forEachIndexed { index, key ->
            notificationDao.insert(
                notification(
                    notificationKey = key,
                    title = key,
                    status = NotificationStatus.REMOVED,
                    observedAt = 2L + index,
                    removedAt = 2L + index
                )
            )
        }

        val deleted = repository.deleteExpiredNotifications()

        assertEquals(1, deleted)
        assertEquals(
            listOf("new-3", "new-2", "old active"),
            loadNotifications(query = "").map { notification -> notification.title }
        )
        assertEquals("old active", notificationDao.getActiveNotificationByKey("old-active")?.title)
    }

    @Test
    fun repositoryCleanupPrunesRestartReconciledLifecycleAfterTerminalAgesOut() = runBlocking {
        var now = 10_000L
        val repository = NotificationRepository(
            notificationDao = notificationDao,
            currentTimeMillis = { now },
            retentionPolicy = NotificationRetentionPolicy(
                maxAgeMillis = 5_000L,
                maxRows = null,
                maxEvents = null
            )
        )
        notificationDao.insert(
            notification(
                notificationKey = "restart-active",
                title = "posted before restart",
                observedAt = 1_000L,
                timestamp = 1_000L
            )
        )

        assertEquals(0, repository.deleteExpiredNotifications())
        assertEquals(
            "posted before restart",
            notificationDao.getActiveNotificationByKey("restart-active")?.title
        )

        repository.reconcileActiveNotifications(
            activeNotificationKeys = emptySet(),
            reconciledAt = now,
            activeSnapshotCapturedAt = now
        )
        assertEquals(0, repository.deleteExpiredNotifications())
        assertNull(notificationDao.getActiveNotificationByKey("restart-active"))
        assertEquals(
            listOf(NotificationStatus.REMOVED, NotificationStatus.POSTED),
            loadNotifications(query = "").map { notification -> notification.status }
        )

        now = 16_000L

        assertEquals(2, repository.deleteExpiredNotifications())
        assertEquals(emptyList<NotificationEntity>(), loadNotifications(query = ""))
    }

    @Test
    fun journalCleanupDeletesOldestExpiredAndOverflowEvents() = runBlocking {
        journalDao.insert(journalEvent(notificationKey = "expired", createdAt = 1_000L))
        journalDao.insert(journalEvent(notificationKey = "old-retained", createdAt = 2_000L))
        journalDao.insert(journalEvent(notificationKey = "newer", createdAt = 3_000L))
        journalDao.insert(journalEvent(notificationKey = "newest", createdAt = 4_000L))

        val expiredDeleted = journalDao.deleteCreatedBefore(cutoffTimestamp = 2_000L)
        val overflowDeleted = journalDao.trimToNewest(maxRows = 2)
        val pending = journalDao.pendingEvents(now = 10_000L, limit = 10)

        assertEquals(1, expiredDeleted)
        assertEquals(1, overflowDeleted)
        assertEquals(listOf("newer", "newest"), pending.map { event -> event.notificationKey })
    }

    @Test
    fun syntheticRemovalSkipsWhenNewerPostedArrivedAfterSnapshot() = runBlocking {
        val staleId = notificationDao.insert(
            notification(
                notificationKey = "reposted-key",
                title = "stale posted",
                observedAt = 1_000L
            )
        )
        val staleSnapshot = requireNotNull(notificationDao.getActiveNotificationByKey("reposted-key"))
        assertEquals(staleId, staleSnapshot.id)

        notificationDao.insert(
            notification(
                notificationKey = "reposted-key",
                title = "fresh repost",
                observedAt = 1_500L
            )
        )

        val inserted = notificationDao.insertSyntheticRemovalIfStillActive(
            notification = staleSnapshot,
            removedAt = 2_000L
        )

        assertFalse(inserted)
        assertEquals("fresh repost", notificationDao.getActiveNotificationByKey("reposted-key")?.title)
        assertEquals(
            listOf(NotificationStatus.POSTED, NotificationStatus.POSTED),
            loadNotifications(query = "").map { notification -> notification.status }
        )
    }

    private suspend fun <T : Any> PagingSource<Int, T>.loadPage(
        params: PagingSource.LoadParams<Int>
    ): PagingSource.LoadResult.Page<Int, T> {
        return when (val loadResult = load(params)) {
            is PagingSource.LoadResult.Page -> loadResult
            is PagingSource.LoadResult.Error -> throw loadResult.throwable
            is PagingSource.LoadResult.Invalid -> error("Paging source became invalid")
        }
    }

    private suspend fun loadNotifications(query: String): List<NotificationEntity> {
        val loadResult = notificationDao.getPagedNotifications(query).loadPage(
            PagingSource.LoadParams.Refresh(
                key = null,
                loadSize = 20,
                placeholdersEnabled = false
            )
        )

        return loadResult.data
    }

    private suspend fun loadGroups(query: String): List<NotificationGroupSummary> {
        val loadResult = notificationDao.getPagedNotificationGroups(query).loadPage(
            PagingSource.LoadParams.Refresh(
                key = null,
                loadSize = 20,
                placeholdersEnabled = false
            )
        )

        return loadResult.data
    }

    private fun notification(
        notificationKey: String = "shared-key",
        packageName: String = "com.example.chat",
        title: String = "title",
        text: String = "text",
        observedAt: Long = 1_000L,
        timestamp: Long = observedAt,
        appLabel: String = packageName,
        status: NotificationStatus = NotificationStatus.POSTED,
        removalReason: RemovalReason = RemovalReason.UNKNOWN,
        removedAt: Long = 0L
    ): NotificationEntity {
        return NotificationEntity(
            notificationKey = notificationKey,
            packageName = packageName,
            title = title,
            text = text,
            timestamp = timestamp,
            observedAt = observedAt,
            appLabel = appLabel,
            appInfoResolved = appLabel != packageName,
            status = status,
            flags = 0,
            hasActions = false,
            removalReason = removalReason,
            timeToRemoval = (removedAt - timestamp).coerceAtLeast(0L),
            removedAt = removedAt
        )
    }

    private fun journalEvent(
        notificationKey: String,
        createdAt: Long
    ): NotificationEventJournalEntity {
        return NotificationEventJournalEntity(
            eventType = NotificationEventJournalEntity.TYPE_POSTED,
            notificationKey = notificationKey,
            packageName = "com.example.chat",
            title = "title",
            text = "text",
            sourcePostTime = createdAt,
            observedAt = createdAt,
            flags = 0,
            hasActions = false,
            appLabel = "Chat",
            appInfoResolved = true,
            systemReason = null,
            createdAt = createdAt,
            nextAttemptAt = createdAt
        )
    }
}
