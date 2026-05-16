package com.example.pushrecorder.capture

import com.example.pushrecorder.appinfo.AppRegistryRepository
import com.example.pushrecorder.appinfo.FakeAppInfoSource
import com.example.pushrecorder.appinfo.FakeAppRecordDao
import com.example.pushrecorder.data.AppRecordEntity
import com.example.pushrecorder.data.FakeNotificationDao
import com.example.pushrecorder.data.NotificationRepository
import com.example.pushrecorder.data.NotificationStatus
import com.example.pushrecorder.data.notificationCapture
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationCaptureProcessorTest {
    private val notificationDao = FakeNotificationDao()
    private val appRecordDao = FakeAppRecordDao()
    private val appInfoSource = FakeAppInfoSource()
    private val notificationRepository = NotificationRepository(notificationDao)
    private val appRegistryRepository = AppRegistryRepository(appRecordDao, appInfoSource)
    private val activeSnapshots = ActiveNotificationSnapshotStore()
    private val processor = NotificationCaptureProcessor(
        notificationRepository = notificationRepository,
        appRegistryRepository = appRegistryRepository,
        activeSnapshots = activeSnapshots
    )

    @Test
    fun processAccepted_recordsPostedNotificationAndObservedPackage() = runTest {
        appInfoSource.setResolved(
            packageName = "com.example.chat",
            label = "Chat App"
        )

        val result = processor.processAccepted(
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
        assertEquals(result.capture, activeSnapshots.get("chat-key"))

        val appRecord = appRecordDao.records.getValue("com.example.chat")
        assertEquals("Chat App", appRecord.label)
        assertTrue(appRecord.isInstalled)
        assertEquals(2_000L, appRecord.firstSeenAt)
        assertEquals(2_000L, appRecord.lastSeenAt)
        assertEquals(2_000L, appRecord.lastInstalledAt)
    }

    @Test
    fun processAccepted_repostForSameKeyAppendsRowAndKeepsOriginalSourcePostTime() = runTest {
        appInfoSource.setResolved(
            packageName = "com.example.mail",
            label = "Mail App"
        )

        processor.processAccepted(
            notificationCapture(
                notificationKey = "same-key",
                packageName = "com.example.mail",
                title = "First title",
                sourcePostTime = 1_000L,
                observedAt = 2_000L
            )
        )
        val result = processor.processAccepted(
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
        assertEquals(1_000L, result.capture.sourcePostTime)
        assertEquals(result.capture, activeSnapshots.get("same-key"))
    }

    @Test
    fun processAccepted_usesStoredAppLabelWhenPackageResolveFails() = runTest {
        appRecordDao.records["com.example.stored"] = AppRecordEntity(
            packageName = "com.example.stored",
            label = "Stored App",
            isInstalled = true,
            firstSeenAt = 100L,
            lastSeenAt = 500L,
            lastInstalledAt = 100L,
            lastRemovedAt = 0L
        )

        processor.processAccepted(
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
}
