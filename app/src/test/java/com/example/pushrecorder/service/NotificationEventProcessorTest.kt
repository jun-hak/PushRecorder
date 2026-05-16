package com.example.pushrecorder.service

import android.service.notification.NotificationListenerService
import com.example.pushrecorder.appinfo.AppRegistryRepository
import com.example.pushrecorder.appinfo.FakeAppInfoSource
import com.example.pushrecorder.appinfo.FakeAppRecordDao
import com.example.pushrecorder.data.AppRecordEntity
import com.example.pushrecorder.data.FakeNotificationDao
import com.example.pushrecorder.data.NotificationCapture
import com.example.pushrecorder.data.NotificationEntity
import com.example.pushrecorder.data.NotificationRepository
import com.example.pushrecorder.data.NotificationStatus
import com.example.pushrecorder.data.RemovalReason
import com.example.pushrecorder.data.notificationCapture
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NotificationEventProcessorTest {
    private val notificationDao = FakeNotificationDao()
    private val appRecordDao = FakeAppRecordDao()
    private val appInfoSource = FakeAppInfoSource()
    private val notificationRepository = NotificationRepository(notificationDao)
    private val appRegistryRepository = AppRegistryRepository(appRecordDao, appInfoSource)
    private val processor = NotificationEventProcessor(
        notificationRepository = notificationRepository,
        appRegistryRepository = appRegistryRepository
    )

    @Test
    fun processPostedCommand_recordsPostedNotificationAndObservedPackage() = runTest {
        appInfoSource.setResolved(
            packageName = "com.example.chat",
            label = "Chat App"
        )

        val result = processPosted(
            processor,
            notificationCapture(
                notificationKey = "chat-key",
                packageName = "com.example.chat",
                title = "New message",
                text = "Hello",
                sourcePostTime = 1_000L,
                observedAt = 2_000L,
                flags = 8,
                hasActions = true
            )
        )

        assertEquals(2_000L, result.observedAt)
        val notification = notificationDao.notifications.single()
        assertEquals("chat-key", notification.notificationKey)
        assertEquals("com.example.chat", notification.packageName)
        assertEquals("New message", notification.title)
        assertEquals("Hello", notification.text)
        assertEquals(1_000L, notification.timestamp)
        assertEquals(2_000L, notification.observedAt)
        assertEquals("Chat App", notification.appLabel)
        assertTrue(notification.appInfoResolved)
        assertEquals(NotificationStatus.POSTED, notification.status)
        assertEquals(8, notification.flags)
        assertTrue(notification.hasActions)

        val appRecord = appRecordDao.records.getValue("com.example.chat")
        assertEquals("Chat App", appRecord.label)
        assertTrue(appRecord.isInstalled)
        assertEquals(2_000L, appRecord.firstSeenAt)
        assertEquals(2_000L, appRecord.lastSeenAt)
        assertEquals(2_000L, appRecord.lastInstalledAt)
    }

    @Test
    fun processPostedCommand_repostForSameKeyAppendsRowAndKeepsOriginalSourcePostTime() = runTest {
        appInfoSource.setResolved(
            packageName = "com.example.mail",
            label = "Mail App"
        )

        processPosted(
            processor,
            notificationCapture(
                notificationKey = "same-key",
                packageName = "com.example.mail",
                title = "First title",
                sourcePostTime = 1_000L,
                observedAt = 2_000L
            )
        )
        processPosted(
            processor,
            notificationCapture(
                notificationKey = "same-key",
                packageName = "com.example.mail",
                title = "Updated title",
                sourcePostTime = 9_000L,
                observedAt = 3_000L
            )
        )

        assertEquals(2, notificationDao.notifications.size)
        assertEquals(listOf("First title", "Updated title"), notificationDao.notifications.map { it.title })
        assertEquals(listOf(1_000L, 1_000L), notificationDao.notifications.map { it.timestamp })
        assertEquals(listOf(2_000L, 3_000L), notificationDao.notifications.map { it.observedAt })
        assertTrue(notificationDao.notifications.all { notification ->
            notification.status == NotificationStatus.POSTED
        })
    }

    @Test
    fun processPostedCommand_withAlreadyRecordedJournalIdSkipsReplaySideEffects() = runTest {
        val processor = NotificationEventProcessor(
            notificationRepository = notificationRepository,
            appRegistryRepository = appRegistryRepository
        )
        notificationRepository.recordPosted(
            capture = notificationCapture(
                notificationKey = "journal-post",
                title = "already recorded"
            ),
            eventJournalId = 11L
        )

        val result = processPosted(
            processor,
            NotificationProcessingCommand.Posted(
                capture = notificationCapture(
                    notificationKey = "journal-post",
                    title = "replayed"
                ),
                eventJournalId = 11L
            )
        )

        assertEquals(2_000L, result.observedAt)
        assertEquals(1, notificationDao.notifications.size)
        assertEquals("already recorded", notificationDao.notifications.single().title)
        assertEquals(null, processor.activeSnapshot("journal-post"))
    }

    @Test
    fun processPostedCommand_whenDurableInsertIsRejected_doesNotAcceptActiveSnapshot() = runTest {
        val processor = NotificationEventProcessor(
            notificationRepository = notificationRepository,
            appRegistryRepository = appRegistryRepository
        )
        notificationDao.nextInsertResult = -1L

        try {
            processPosted(
                processor,
                NotificationProcessingCommand.Posted(
                    capture = notificationCapture(
                        notificationKey = "rejected-post",
                        title = "not durable"
                    ),
                    eventJournalId = 77L
                )
            )
            fail("Expected rejected Room insert to fail posted processing")
        } catch (error: IllegalStateException) {
            assertTrue(error.message?.contains("Failed to persist posted notification event") == true)
        }

        assertEquals(emptyList<NotificationEntity>(), notificationDao.notifications)
        assertNull(processor.activeSnapshot("rejected-post"))
    }

    @Test
    fun processPostedCommand_usesStoredAppLabelWhenPackageResolveFails() = runTest {
        appRecordDao.records["com.example.stored"] = AppRecordEntity(
            packageName = "com.example.stored",
            label = "Stored App",
            isInstalled = true,
            firstSeenAt = 100L,
            lastSeenAt = 500L,
            lastInstalledAt = 100L,
            lastRemovedAt = 0L
        )

        processPosted(
            processor,
            notificationCapture(
                notificationKey = "stored-key",
                packageName = "com.example.stored",
                observedAt = 1_000L
            )
        )

        val notification = notificationDao.notifications.single()
        assertEquals("Stored App", notification.appLabel)
        assertEquals(false, notification.appInfoResolved)

        val appRecord = appRecordDao.records.getValue("com.example.stored")
        assertEquals("Stored App", appRecord.label)
        assertTrue(appRecord.isInstalled)
        assertEquals(1_000L, appRecord.lastSeenAt)
    }

    @Test
    fun processRemovedCommand_withActiveSnapshot_appendsClickedRowAndClearsSnapshot() = runTest {
        val processor = NotificationEventProcessor(
            notificationRepository = notificationRepository,
            appRegistryRepository = appRegistryRepository,
            currentTimeMillis = { 5_000L }
        )
        appInfoSource.setResolved(
            packageName = "com.example.chat",
            label = "Chat App"
        )
        processPosted(
            processor,
            notificationCapture(
                notificationKey = "chat-key",
                packageName = "com.example.chat",
                title = "Stored title",
                text = "Stored text",
                sourcePostTime = 1_000L,
                observedAt = 2_000L,
                flags = 4,
                hasActions = true
            )
        )

        val result = processRemoved(
            processor,
            NotificationRemovalCommand(
                capture = notificationCapture(
                    notificationKey = "chat-key",
                    packageName = "com.example.chat",
                    title = "",
                    text = "",
                    sourcePostTime = 9_000L,
                    observedAt = 3_000L,
                    flags = 16,
                    hasActions = false
                ),
                systemReason = NotificationListenerService.REASON_CLICK
            )
        )

        assertEquals(5_000L, result.removedAt)
        assertEquals(2, notificationDao.notifications.size)
        val removedNotification = notificationDao.notifications.last()
        assertEquals("chat-key", removedNotification.notificationKey)
        assertEquals("com.example.chat", removedNotification.packageName)
        assertEquals("Stored title", removedNotification.title)
        assertEquals("Stored text", removedNotification.text)
        assertEquals(1_000L, removedNotification.timestamp)
        assertEquals(5_000L, removedNotification.observedAt)
        assertEquals("Chat App", removedNotification.appLabel)
        assertTrue(removedNotification.appInfoResolved)
        assertEquals(NotificationStatus.CLICKED, removedNotification.status)
        assertEquals(16, removedNotification.flags)
        assertTrue(removedNotification.hasActions)
        assertEquals(RemovalReason.USER_CLICKED, removedNotification.removalReason)
        assertEquals(4_000L, removedNotification.timeToRemoval)
        assertEquals(5_000L, removedNotification.removedAt)
        assertEquals(null, processor.activeSnapshot("chat-key"))
    }

    @Test
    fun processRemovedCommand_withoutActiveSnapshot_usesStoredActiveRowAndAppendsRemovedRow() = runTest {
        val processor = NotificationEventProcessor(
            notificationRepository = notificationRepository,
            appRegistryRepository = appRegistryRepository,
            currentTimeMillis = { 10_000L }
        )
        appInfoSource.setResolved(
            packageName = "com.example.mail",
            label = "Resolved Mail"
        )
        notificationRepository.recordPosted(
            notificationCapture(
                notificationKey = "mail-key",
                packageName = "com.example.mail",
                title = "Inbox",
                text = "Newsletter",
                sourcePostTime = 1_000L,
                observedAt = 2_000L,
                flags = 2,
                hasActions = false,
                appLabel = "Stored Mail",
                appInfoResolved = false
            )
        )

        val result = processRemoved(
            processor,
            NotificationRemovalCommand(
                capture = notificationCapture(
                    notificationKey = "mail-key",
                    packageName = "com.example.mail",
                    title = "",
                    text = "",
                    sourcePostTime = 8_000L,
                    observedAt = 9_000L,
                    flags = 32,
                    hasActions = true
                )
            )
        )

        assertEquals(10_000L, result.removedAt)
        assertEquals(2, notificationDao.notifications.size)
        val postedNotification = notificationDao.notifications.first()
        val removedNotification = notificationDao.notifications.last()
        assertEquals(NotificationStatus.POSTED, postedNotification.status)
        assertEquals("Inbox", removedNotification.title)
        assertEquals("Newsletter", removedNotification.text)
        assertEquals(1_000L, removedNotification.timestamp)
        assertEquals(10_000L, removedNotification.observedAt)
        assertEquals("Stored Mail", removedNotification.appLabel)
        assertFalse(removedNotification.appInfoResolved)
        assertEquals(NotificationStatus.REMOVED, removedNotification.status)
        assertEquals(32, removedNotification.flags)
        assertTrue(removedNotification.hasActions)
        assertEquals(RemovalReason.USER_DISMISSED, removedNotification.removalReason)
        assertEquals(9_000L, removedNotification.timeToRemoval)
        assertEquals(10_000L, removedNotification.removedAt)
        assertEquals(null, processor.activeSnapshot("mail-key"))
    }

    @Test
    fun processRemovedCommand_preservesFormerParameterBasedRemovalBehavior() = runTest {
        val processor = NotificationEventProcessor(
            notificationRepository = notificationRepository,
            appRegistryRepository = appRegistryRepository,
            currentTimeMillis = { 6_000L }
        )
        appInfoSource.setResolved(
            packageName = "com.example.direct",
            label = "Direct App"
        )
        val command = NotificationRemovalCommand(
            capture = notificationCapture(
                notificationKey = "direct-key",
                packageName = "com.example.direct",
                title = "Direct title",
                text = "Direct text",
                sourcePostTime = 2_000L,
                observedAt = 5_000L,
                flags = 64,
                hasActions = true
            ),
            systemReason = NotificationListenerService.REASON_CLICK
        )

        val result = processRemoved(processor, command)

        assertEquals(6_000L, result.removedAt)
        val removedNotification = notificationDao.notifications.single()
        assertEquals("direct-key", removedNotification.notificationKey)
        assertEquals("com.example.direct", removedNotification.packageName)
        assertEquals("Direct title", removedNotification.title)
        assertEquals("Direct text", removedNotification.text)
        assertEquals(2_000L, removedNotification.timestamp)
        assertEquals(6_000L, removedNotification.observedAt)
        assertEquals("Direct App", removedNotification.appLabel)
        assertTrue(removedNotification.appInfoResolved)
        assertEquals(NotificationStatus.CLICKED, removedNotification.status)
        assertEquals(64, removedNotification.flags)
        assertTrue(removedNotification.hasActions)
        assertEquals(RemovalReason.USER_CLICKED, removedNotification.removalReason)
        assertEquals(4_000L, removedNotification.timeToRemoval)
        assertEquals(6_000L, removedNotification.removedAt)
        assertEquals(null, processor.activeSnapshot("direct-key"))
    }

    @Test
    fun processRemovedCommand_skipsDuplicateTerminalAfterSyntheticReconcileRemoval() = runTest {
        val processor = NotificationEventProcessor(
            notificationRepository = notificationRepository,
            appRegistryRepository = appRegistryRepository,
            currentTimeMillis = { 6_000L }
        )
        notificationRepository.recordPosted(
            notificationCapture(
                notificationKey = "reconciled-key",
                packageName = "com.example.reconciled",
                title = "Posted before reconcile",
                sourcePostTime = 1_000L,
                observedAt = 2_000L
            )
        )
        notificationRepository.reconcileActiveNotifications(
            activeNotificationKeys = emptySet(),
            reconciledAt = 5_000L
        )

        val result = processRemoved(
            processor,
            NotificationRemovalCommand(
                capture = notificationCapture(
                    notificationKey = "reconciled-key",
                    packageName = "com.example.reconciled",
                    title = "Late actual removal",
                    observedAt = 6_000L
                ),
                systemReason = NotificationListenerService.REASON_CLICK
            )
        )

        assertEquals(6_000L, result.removedAt)
        assertEquals(
            listOf(NotificationStatus.POSTED, NotificationStatus.REMOVED),
            notificationDao.notifications.map(NotificationEntity::status)
        )
        val terminal = notificationDao.notifications.single { notification ->
            notification.status != NotificationStatus.POSTED
        }
        assertEquals(RemovalReason.UNKNOWN, terminal.removalReason)
        assertEquals(5_000L, terminal.removedAt)
    }

    @Test
    fun processRemovedCommand_withAlreadyRecordedJournalIdDoesNotAffectNewActiveLifecycle() = runTest {
        val processor = NotificationEventProcessor(
            notificationRepository = notificationRepository,
            appRegistryRepository = appRegistryRepository,
            currentTimeMillis = { 7_000L }
        )
        notificationRepository.recordPosted(
            capture = notificationCapture(
                notificationKey = "journal-remove",
                title = "old posted",
                observedAt = 1_000L
            )
        )
        notificationRepository.recordRemoved(
            capture = notificationCapture(
                notificationKey = "journal-remove",
                title = "old removed",
                observedAt = 2_000L
            ),
            status = NotificationStatus.REMOVED,
            removalReason = RemovalReason.USER_DISMISSED,
            timeToRemoval = 1_000L,
            removedAt = 2_000L,
            eventJournalId = 22L
        )
        notificationRepository.recordPosted(
            capture = notificationCapture(
                notificationKey = "journal-remove",
                title = "new posted",
                observedAt = 6_000L
            )
        )

        val result = processRemoved(
            processor,
            NotificationRemovalCommand(
                capture = notificationCapture(
                    notificationKey = "journal-remove",
                    title = "old removal replay",
                    observedAt = 2_000L
                ),
                systemReason = NotificationListenerService.REASON_CLICK
            ),
            eventJournalId = 22L
        )

        assertEquals(7_000L, result.removedAt)
        assertEquals(
            listOf("old posted", "old removed", "new posted"),
            notificationDao.notifications.map(NotificationEntity::title)
        )
        assertEquals("new posted", notificationRepository.getActiveNotificationByKey("journal-remove")?.title)
    }

    @Test
    fun processRemovedCommand_whenDurableInsertIsRejected_keepsActiveSnapshot() = runTest {
        val processor = NotificationEventProcessor(
            notificationRepository = notificationRepository,
            appRegistryRepository = appRegistryRepository,
            currentTimeMillis = { 7_000L }
        )
        processPosted(
            processor,
            notificationCapture(
                notificationKey = "rejected-remove",
                title = "active before failure",
                observedAt = 1_000L
            )
        )
        notificationDao.nextInsertResult = -1L

        try {
            processRemoved(
                processor,
                NotificationRemovalCommand(
                    capture = notificationCapture(
                        notificationKey = "rejected-remove",
                        title = "remove not durable",
                        observedAt = 6_000L
                    )
                )
            )
            fail("Expected rejected Room insert to fail removed processing")
        } catch (error: IllegalStateException) {
            assertTrue(error.message?.contains("Failed to persist removed notification event") == true)
        }

        assertEquals(1, notificationDao.notifications.size)
        assertEquals(NotificationStatus.POSTED, notificationDao.notifications.single().status)
        assertEquals("active before failure", processor.activeSnapshot("rejected-remove")?.title)
    }

    private suspend fun processPosted(
        processor: NotificationEventProcessor,
        capture: NotificationCapture
    ): NotificationProcessingResult.Posted {
        return processPosted(
            processor = processor,
            command = NotificationProcessingCommand.Posted(capture)
        )
    }

    private suspend fun processPosted(
        processor: NotificationEventProcessor,
        command: NotificationProcessingCommand.Posted
    ): NotificationProcessingResult.Posted {
        return processor.process(command)
            as NotificationProcessingResult.Posted
    }

    private suspend fun processRemoved(
        processor: NotificationEventProcessor,
        command: NotificationRemovalCommand,
        eventJournalId: Long? = null
    ): NotificationProcessingResult.Removed {
        return processor.process(
            NotificationProcessingCommand.Removed(
                command = command,
                eventJournalId = eventJournalId
            )
        )
            as NotificationProcessingResult.Removed
    }
}
