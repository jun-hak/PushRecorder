package com.example.pushrecorder.data

import androidx.paging.PagingSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationSearchQueryFilteringTest {
    private val notificationDao = FakeNotificationDao()

    @Test
    fun searchQuery_escapesPercentForSqlLike() {
        assertEquals("100\\%", NotificationSearchQuery.escapeLikeTerm("100%"))
    }

    @Test
    fun searchQuery_escapesUnderscoreForSqlLike() {
        assertEquals("chat\\_thread", NotificationSearchQuery.escapeLikeTerm("chat_thread"))
    }

    @Test
    fun searchQuery_fromUserInputPreservesOrdinaryContainsTerm() {
        assertEquals("chat", NotificationSearchQuery.fromUserInput("chat"))
    }

    @Test
    fun searchQuery_fromUserInputEscapesLikeWildcards() {
        assertEquals("100\\% chat\\_thread", NotificationSearchQuery.fromUserInput("100% chat_thread"))
    }

    @Test
    fun pagedNotifications_matchesPackageLabelTitleAndText() = runTest {
        notificationDao.insert(notification(notificationKey = "package", packageName = "com.chat.source"))
        notificationDao.insert(notification(notificationKey = "label", appLabel = "Chat App"))
        notificationDao.insert(notification(notificationKey = "title", title = "Chat received"))
        notificationDao.insert(notification(notificationKey = "text", text = "open chat thread"))
        notificationDao.insert(notification(notificationKey = "miss", title = "Deposit", text = "salary"))

        val results = notificationDao.getPagedNotifications("chat").loadRefreshPage()

        assertEquals(
            listOf("text", "title", "label", "package"),
            results.map { notification -> notification.notificationKey }
        )
    }

    @Test
    fun pagedNotifications_emptyQueryReturnsAllRowsInObservedOrder() = runTest {
        notificationDao.insert(notification(notificationKey = "old", observedAt = 1_000L))
        notificationDao.insert(notification(notificationKey = "middle", observedAt = 2_000L))
        notificationDao.insert(notification(notificationKey = "new", observedAt = 3_000L))

        val results = notificationDao.getPagedNotifications("").loadRefreshPage()

        assertEquals(
            listOf("new", "middle", "old"),
            results.map { notification -> notification.notificationKey }
        )
    }

    @Test
    fun pagedNotifications_fromUserInputPreservesOrdinaryContainsSearch() = runTest {
        notificationDao.insert(notification(notificationKey = "contains", appLabel = "Chatty"))
        notificationDao.insert(notification(notificationKey = "miss", appLabel = "Bank"))

        val results = notificationDao
            .getPagedNotifications(NotificationSearchQuery.fromUserInput("hat"))
            .loadRefreshPage()

        assertEquals(
            listOf("contains"),
            results.map { notification -> notification.notificationKey }
        )
    }

    @Test
    fun pagedNotifications_treatsPercentAsLiteralSearchText() = runTest {
        notificationDao.insert(notification(notificationKey = "literal-percent", title = "Battery 100% charged"))
        notificationDao.insert(notification(notificationKey = "wildcard-candidate", title = "Battery 1000 charged"))
        notificationDao.insert(notification(notificationKey = "unrelated", title = "Deposit"))

        val results = notificationDao
            .getPagedNotifications(NotificationSearchQuery.fromUserInput("100%"))
            .loadRefreshPage()

        assertEquals(
            listOf("literal-percent"),
            results.map { notification -> notification.notificationKey }
        )
    }

    @Test
    fun pagedNotifications_treatsUnderscoreAsLiteralSearchText() = runTest {
        notificationDao.insert(notification(notificationKey = "literal-underscore", title = "chat_thread"))
        notificationDao.insert(notification(notificationKey = "wildcard-candidate", title = "chat-thread"))
        notificationDao.insert(notification(notificationKey = "unrelated", title = "Deposit"))

        val results = notificationDao
            .getPagedNotifications(NotificationSearchQuery.fromUserInput("chat_thread"))
            .loadRefreshPage()

        assertEquals(
            listOf("literal-underscore"),
            results.map { notification -> notification.notificationKey }
        )
    }

    @Test
    fun pagedNotificationsByPackage_appliesPackageAndQueryFiltersTogether() = runTest {
        notificationDao.insert(
            notification(
                notificationKey = "matching-package-and-query",
                packageName = "com.example.chat",
                title = "Thread update"
            )
        )
        notificationDao.insert(
            notification(
                notificationKey = "matching-package-only",
                packageName = "com.example.chat",
                title = "Deposit"
            )
        )
        notificationDao.insert(
            notification(
                notificationKey = "matching-query-only",
                packageName = "com.example.bank",
                title = "Thread update"
            )
        )

        val results = notificationDao
            .getPagedNotificationsByPackage(
                packageName = "com.example.chat",
                query = "thread"
            )
            .loadRefreshPage()

        assertEquals(
            listOf("matching-package-and-query"),
            results.map { notification -> notification.notificationKey }
        )
        assertTrue(results.all { notification -> notification.packageName == "com.example.chat" })
    }

    @Test
    fun pagedNotificationGroups_filtersBeforeSelectingLatestLogicalNotification() = runTest {
        notificationDao.insert(
            notification(
                notificationKey = "chat-1",
                packageName = "com.example.chat",
                title = "Chat posted",
                observedAt = 1_000L
            )
        )
        notificationDao.insert(
            notification(
                notificationKey = "chat-1",
                packageName = "com.example.chat",
                title = "Chat removed",
                status = NotificationStatus.REMOVED,
                observedAt = 2_000L,
                removedAt = 2_000L
            )
        )
        notificationDao.insert(
            notification(
                notificationKey = "chat-2",
                packageName = "com.example.chat",
                title = "Chat second",
                observedAt = 3_000L
            )
        )
        notificationDao.insert(
            notification(
                notificationKey = "bank-1",
                packageName = "com.example.bank",
                title = "Deposit",
                observedAt = 4_000L
            )
        )

        val groups = notificationDao.getPagedNotificationGroups("chat").loadRefreshPage()

        assertEquals(1, groups.size)
        assertEquals("com.example.chat", groups.single().latestNotification.packageName)
        assertEquals("Chat second", groups.single().latestNotification.title)
        assertEquals(2, groups.single().notificationCount)
    }

    @Test
    fun pagedNotificationGroups_usesAppendOrderWhenObservedAtMovesBackward() = runTest {
        notificationDao.insert(
            notification(
                notificationKey = "chat-1",
                packageName = "com.example.chat",
                title = "Chat first posted high clock",
                observedAt = 5_000L
            )
        )
        notificationDao.insert(
            notification(
                notificationKey = "chat-1",
                packageName = "com.example.chat",
                title = "Chat first removed older clock",
                status = NotificationStatus.REMOVED,
                observedAt = 4_000L,
                removedAt = 4_000L
            )
        )
        notificationDao.insert(
            notification(
                notificationKey = "chat-2",
                packageName = "com.example.chat",
                title = "Chat second posted latest append",
                observedAt = 3_000L
            )
        )

        val groups = notificationDao.getPagedNotificationGroups("").loadRefreshPage()

        assertEquals(1, groups.size)
        assertEquals(2, groups.single().notificationCount)
        assertEquals("Chat second posted latest append", groups.single().latestNotification.title)
    }

    @Test
    fun pagedNotificationGroups_sortsGroupsByAppendOrderWhenObservedAtMovesBackward() = runTest {
        notificationDao.insert(
            notification(
                notificationKey = "bank-1",
                packageName = "com.example.bank",
                title = "Bank high clock",
                observedAt = 5_000L
            )
        )
        notificationDao.insert(
            notification(
                notificationKey = "chat-1",
                packageName = "com.example.chat",
                title = "Chat latest append older clock",
                observedAt = 1_000L
            )
        )

        val groups = notificationDao.getPagedNotificationGroups("").loadRefreshPage()

        assertEquals(
            listOf("com.example.chat", "com.example.bank"),
            groups.map { group -> group.latestNotification.packageName }
        )
    }

    private suspend fun <T : Any> PagingSource<Int, T>.loadRefreshPage(): List<T> {
        val loadResult = load(
            PagingSource.LoadParams.Refresh(
                key = null,
                loadSize = 20,
                placeholdersEnabled = false
            )
        )

        return when (loadResult) {
            is PagingSource.LoadResult.Page -> loadResult.data
            is PagingSource.LoadResult.Error -> throw loadResult.throwable
            is PagingSource.LoadResult.Invalid -> error("Paging source became invalid")
        }
    }

    private fun notification(
        notificationKey: String,
        packageName: String = "com.example.source",
        title: String = "title",
        text: String = "text",
        observedAt: Long = notificationDao.notifications.size.toLong() + 1L,
        appLabel: String = packageName,
        status: NotificationStatus = NotificationStatus.POSTED,
        removedAt: Long = 0L
    ): NotificationEntity {
        return NotificationEntity(
            notificationKey = notificationKey,
            packageName = packageName,
            title = title,
            text = text,
            timestamp = observedAt,
            observedAt = observedAt,
            appLabel = appLabel,
            appInfoResolved = appLabel != packageName,
            status = status,
            flags = 0,
            hasActions = false,
            removalReason = RemovalReason.UNKNOWN,
            timeToRemoval = (removedAt - observedAt).coerceAtLeast(0L),
            removedAt = removedAt
        )
    }
}
