package com.example.pushrecorder.service

import com.example.pushrecorder.appinfo.AppRegistryRepository
import com.example.pushrecorder.appinfo.FakeAppInfoSource
import com.example.pushrecorder.appinfo.FakeAppRecordDao
import com.example.pushrecorder.data.FakeNotificationDao
import com.example.pushrecorder.data.NotificationCapture
import com.example.pushrecorder.data.NotificationRepository
import com.example.pushrecorder.data.NotificationStatus
import com.example.pushrecorder.data.RemovalReason
import com.example.pushrecorder.data.notificationCapture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationReconcilerTest {
    private val notificationDao = FakeNotificationDao()
    private val appRecordDao = FakeAppRecordDao()
    private val appInfoSource = FakeAppInfoSource()
    private val notificationRepository = NotificationRepository(notificationDao)
    private val appRegistryRepository = AppRegistryRepository(appRecordDao, appInfoSource)
    private val eventProcessor = NotificationEventProcessor(
        notificationRepository = notificationRepository,
        appRegistryRepository = appRegistryRepository
    )
    private val statusRecorder = FakeReconcileStatusRecorder()

    @Test
    fun enqueueActiveReconcileOrRetry_withActiveLookupDelegatesSameReconcileEventToListenerQueue() = runTest {
        val activeCapture = notificationCapture(
            notificationKey = "active-key",
            packageName = "com.example.chat"
        )
        val enqueuedEvents = mutableListOf<NotificationReconcileEvent>()
        val reconciler = reconciler(
            scope = this,
            readActiveNotifications = { listOf(activeCapture) },
            enqueueReconcile = { event ->
                enqueuedEvents += event
            }
        )

        reconciler.enqueueActiveReconcileOrRetry()

        assertEquals(
            listOf(
                NotificationReconcileEvent(
                    activeCaptures = listOf(activeCapture),
                    snapshotCapturedAt = 1_000L
                )
            ),
            enqueuedEvents
        )
        assertEquals(emptyList<String>(), statusRecorder.events)
    }

    @Test
    fun enqueueActiveReconcileOrRetry_withEmptyLookupDelegatesEmptyReconcileEventToListenerQueue() = runTest {
        val enqueuedEvents = mutableListOf<NotificationReconcileEvent>()
        val reconciler = reconciler(
            scope = this,
            readActiveNotifications = { emptyList() },
            enqueueReconcile = { event ->
                enqueuedEvents += event
            }
        )

        reconciler.enqueueActiveReconcileOrRetry(retryCount = 4)

        assertEquals(
            listOf(
                NotificationReconcileEvent(
                    activeCaptures = emptyList(),
                    snapshotCapturedAt = 1_000L
                )
            ),
            enqueuedEvents
        )
        assertEquals(emptyList<String>(), statusRecorder.events)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun enqueueActiveReconcileOrRetry_withEmptyLookupDoesNotRetryAndPreservesSuccessfulEmptyResult() = runTest {
        notificationRepository.recordPosted(
            notificationCapture(
                notificationKey = "stored-active-key",
                packageName = "com.example.chat",
                title = "Stored title",
                sourcePostTime = 1_000L,
                observedAt = 2_000L
            )
        )
        var lookupCount = 0
        val enqueuedEvents = mutableListOf<NotificationReconcileEvent>()
        val reconciler = reconciler(
            scope = this,
            readActiveNotifications = {
                lookupCount += 1
                emptyList()
            },
            enqueueReconcile = { event ->
                enqueuedEvents += event
            }
        )

        reconciler.enqueueActiveReconcileOrRetry()
        advanceUntilIdle()

        assertEquals(1, lookupCount)
        assertEquals(
            listOf(
                NotificationReconcileEvent(
                    activeCaptures = emptyList(),
                    snapshotCapturedAt = 1_000L
                )
            ),
            enqueuedEvents
        )
        assertEquals(1, notificationDao.notifications.size)
        assertEquals(NotificationStatus.POSTED, notificationDao.notifications.single().status)
        assertEquals("stored-active-key", notificationDao.notifications.single().notificationKey)
        assertEquals(emptyList<String>(), statusRecorder.events)
    }

    @Test
    fun processReconcile_fromSuccessfulEmptyLookupAppendsTerminalEventsForAllStaleActiveRows() = runTest {
        processPosted(
            notificationCapture(
                notificationKey = "first-stale-key",
                packageName = "com.example.chat",
                title = "First stored title",
                text = "First stored body",
                sourcePostTime = 1_000L,
                observedAt = 2_000L,
                flags = 2,
                hasActions = true,
                appLabel = "Chat",
                appInfoResolved = true
            )
        )
        processPosted(
            notificationCapture(
                notificationKey = "second-stale-key",
                packageName = "com.example.chat",
                title = "Second stored title",
                text = "Second stored body",
                sourcePostTime = 3_000L,
                observedAt = 4_000L,
                flags = 4,
                hasActions = false,
                appLabel = "Chat",
                appInfoResolved = true
            )
        )
        val enqueuedEvents = mutableListOf<NotificationReconcileEvent>()
        val reconciler = reconciler(
            scope = this,
            readActiveNotifications = { emptyList() },
            enqueueReconcile = { event ->
                enqueuedEvents += event
            },
            currentTimeMillis = { 9_000L }
        )

        reconciler.enqueueActiveReconcileOrRetry()
        enqueuedEvents.single().let { event ->
            reconciler.processReconcile(event)
        }

        assertEquals(
            listOf(
                NotificationReconcileEvent(
                    activeCaptures = emptyList(),
                    snapshotCapturedAt = 9_000L
                )
            ),
            enqueuedEvents
        )
        assertEquals(
            listOf(
                NotificationStatus.POSTED,
                NotificationStatus.POSTED,
                NotificationStatus.REMOVED,
                NotificationStatus.REMOVED
            ),
            notificationDao.notifications.map { notification -> notification.status }
        )
        val staleRemovals = notificationDao.notifications
            .filter { notification -> notification.status == NotificationStatus.REMOVED }
            .sortedBy { notification -> notification.notificationKey }
        assertEquals(listOf("first-stale-key", "second-stale-key"), staleRemovals.map { it.notificationKey })
        assertEquals(listOf("First stored title", "Second stored title"), staleRemovals.map { it.title })
        assertEquals(listOf(RemovalReason.UNKNOWN, RemovalReason.UNKNOWN), staleRemovals.map { it.removalReason })
        assertEquals(listOf(9_000L, 9_000L), staleRemovals.map { it.observedAt })
        assertEquals(listOf(9_000L, 9_000L), staleRemovals.map { it.removedAt })
        assertEquals(listOf(8_000L, 6_000L), staleRemovals.map { it.timeToRemoval })
        assertEquals(null, eventProcessor.activeSnapshot("first-stale-key"))
        assertEquals(null, eventProcessor.activeSnapshot("second-stale-key"))
        assertEquals(listOf("reconciled:9000"), statusRecorder.events)
    }

    @Test
    fun processReconcile_withEmptyLookupAndTargetPackageDoesNotTerminateOtherApps() = runTest {
        processPosted(
            notificationCapture(
                notificationKey = "target-stale-key",
                packageName = "com.example.chat",
                title = "Chat title",
                sourcePostTime = 1_000L,
                observedAt = 2_000L
            )
        )
        processPosted(
            notificationCapture(
                notificationKey = "other-stale-key",
                packageName = "com.example.mail",
                title = "Mail title",
                sourcePostTime = 3_000L,
                observedAt = 4_000L
            )
        )
        val reconciler = reconciler(
            scope = this,
            currentTimeMillis = { 9_000L }
        )

        reconciler.processReconcile(
            NotificationReconcileEvent(
                activeCaptures = emptyList(),
                targetPackageName = "com.example.chat"
            )
        )

        val targetRows = notificationDao.notifications.filter { notification ->
            notification.notificationKey == "target-stale-key"
        }
        assertEquals(
            listOf(NotificationStatus.POSTED, NotificationStatus.REMOVED),
            targetRows.map { notification -> notification.status }
        )
        assertEquals(9_000L, targetRows.single { it.status == NotificationStatus.REMOVED }.removedAt)

        val otherRows = notificationDao.notifications.filter { notification ->
            notification.notificationKey == "other-stale-key"
        }
        assertEquals(listOf(NotificationStatus.POSTED), otherRows.map { notification -> notification.status })
        assertEquals(null, eventProcessor.activeSnapshot("target-stale-key"))
        assertEquals("other-stale-key", eventProcessor.activeSnapshot("other-stale-key")?.notificationKey)
        assertEquals(listOf("reconciled:9000"), statusRecorder.events)
    }

    @Test
    fun enqueueActiveReconcileOrRetry_withNullLookupDoesNotDestructivelyReconcileStoredActiveNotifications() = runTest {
        notificationRepository.recordPosted(
            notificationCapture(
                notificationKey = "stored-active-key",
                packageName = "com.example.chat",
                title = "Stored title",
                sourcePostTime = 1_000L,
                observedAt = 2_000L
            )
        )
        val enqueuedEvents = mutableListOf<NotificationReconcileEvent>()
        val reconciler = reconciler(
            scope = this,
            readActiveNotifications = { null },
            enqueueReconcile = { event ->
                enqueuedEvents += event
            }
        )

        reconciler.enqueueActiveReconcileOrRetry(retryCount = 4)

        assertEquals(emptyList<NotificationReconcileEvent>(), enqueuedEvents)
        assertEquals(1, notificationDao.notifications.size)
        assertEquals(NotificationStatus.POSTED, notificationDao.notifications.single().status)
        assertEquals("stored-active-key", notificationDao.notifications.single().notificationKey)
        assertEquals(
            listOf(
                "error:Active notifications lookup returned null",
                "error:Active notification lookup failed repeatedly"
            ),
            statusRecorder.events
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun enqueueActiveReconcileOrRetry_withNullLookupRetriesWithoutEnqueuingEmptyReconcile() = runTest {
        notificationRepository.recordPosted(
            notificationCapture(
                notificationKey = "stored-active-key",
                packageName = "com.example.chat",
                title = "Stored title",
                sourcePostTime = 1_000L,
                observedAt = 2_000L
            )
        )
        val activeCapture = notificationCapture(
            notificationKey = "stored-active-key",
            packageName = "com.example.chat",
            observedAt = 3_000L
        )
        var lookupCount = 0
        val enqueuedEvents = mutableListOf<NotificationReconcileEvent>()
        val reconciler = reconciler(
            scope = this,
            readActiveNotifications = {
                lookupCount += 1
                if (lookupCount == 1) {
                    null
                } else {
                    listOf(activeCapture)
                }
            },
            enqueueReconcile = { event ->
                enqueuedEvents += event
            }
        )

        reconciler.enqueueActiveReconcileOrRetry()
        advanceUntilIdle()

        assertEquals(2, lookupCount)
        assertEquals(
            listOf(
                NotificationReconcileEvent(
                    activeCaptures = listOf(activeCapture),
                    snapshotCapturedAt = 1_000L
                )
            ),
            enqueuedEvents
        )
        assertEquals(1, notificationDao.notifications.size)
        assertEquals(NotificationStatus.POSTED, notificationDao.notifications.single().status)
        assertEquals("stored-active-key", notificationDao.notifications.single().notificationKey)
        assertEquals(
            listOf("error:Active notifications lookup returned null"),
            statusRecorder.events
        )
    }

    @Test
    fun enqueueActiveReconcileOrRetry_withThrowingLookupDoesNotDestructivelyReconcileStoredActiveNotifications() = runTest {
        notificationRepository.recordPosted(
            notificationCapture(
                notificationKey = "stored-active-key",
                packageName = "com.example.chat",
                title = "Stored title",
                sourcePostTime = 1_000L,
                observedAt = 2_000L
            )
        )
        val enqueuedEvents = mutableListOf<NotificationReconcileEvent>()
        val reconciler = reconciler(
            scope = this,
            readActiveNotifications = { error("lookup failed") },
            enqueueReconcile = { event ->
                enqueuedEvents += event
            }
        )

        reconciler.enqueueActiveReconcileOrRetry(retryCount = 4)

        assertEquals(emptyList<NotificationReconcileEvent>(), enqueuedEvents)
        assertEquals(1, notificationDao.notifications.size)
        assertEquals(NotificationStatus.POSTED, notificationDao.notifications.single().status)
        assertEquals("stored-active-key", notificationDao.notifications.single().notificationKey)
        assertEquals(
            listOf(
                "error:Failed to read active notifications",
                "error:Active notification lookup failed repeatedly"
            ),
            statusRecorder.events
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun enqueueActiveReconcileOrRetry_withThrowingLookupRetriesWithoutEnqueuingEmptyReconcile() = runTest {
        notificationRepository.recordPosted(
            notificationCapture(
                notificationKey = "stored-active-key",
                packageName = "com.example.chat",
                title = "Stored title",
                sourcePostTime = 1_000L,
                observedAt = 2_000L
            )
        )
        val activeCapture = notificationCapture(
            notificationKey = "stored-active-key",
            packageName = "com.example.chat",
            observedAt = 3_000L
        )
        var lookupCount = 0
        val enqueuedEvents = mutableListOf<NotificationReconcileEvent>()
        val reconciler = reconciler(
            scope = this,
            readActiveNotifications = {
                lookupCount += 1
                if (lookupCount == 1) {
                    error("lookup failed")
                } else {
                    listOf(activeCapture)
                }
            },
            enqueueReconcile = { event ->
                enqueuedEvents += event
            }
        )

        reconciler.enqueueActiveReconcileOrRetry()
        advanceUntilIdle()

        assertEquals(2, lookupCount)
        assertEquals(
            listOf(
                NotificationReconcileEvent(
                    activeCaptures = listOf(activeCapture),
                    snapshotCapturedAt = 1_000L
                )
            ),
            enqueuedEvents
        )
        assertEquals(1, notificationDao.notifications.size)
        assertEquals(NotificationStatus.POSTED, notificationDao.notifications.single().status)
        assertEquals("stored-active-key", notificationDao.notifications.single().notificationKey)
        assertEquals(
            listOf("error:Failed to read active notifications"),
            statusRecorder.events
        )
    }

    @Test
    fun processReconcile_preservesExistingReconcileProcessingContract() = runTest {
        appInfoSource.setResolved(
            packageName = "com.example.chat",
            label = "Chat App"
        )
        notificationRepository.recordPosted(
            notificationCapture(
                notificationKey = "stale-key",
                packageName = "com.example.chat",
                title = "Old title",
                text = "Old body",
                sourcePostTime = 1_000L,
                observedAt = 2_000L
            )
        )
        val activeCapture = notificationCapture(
            notificationKey = "active-key",
            packageName = "com.example.chat",
            title = "Active title",
            sourcePostTime = 3_000L,
            observedAt = 4_000L
        )
        val reconciler = reconciler(
            scope = this,
            currentTimeMillis = { 9_000L }
        )

        reconciler.processReconcile(NotificationReconcileEvent(listOf(activeCapture)))

        assertEquals(3, notificationDao.notifications.size)
        assertEquals(
            listOf(NotificationStatus.POSTED, NotificationStatus.REMOVED, NotificationStatus.POSTED),
            notificationDao.notifications.map { notification -> notification.status }
        )
        assertEquals("stale-key", notificationDao.notifications[1].notificationKey)
        assertEquals(9_000L, notificationDao.notifications[1].removedAt)
        assertEquals("active-key", notificationDao.notifications[2].notificationKey)
        assertEquals("Active title", notificationDao.notifications[2].title)
        assertEquals(
            listOf("posted:4000", "reconciled:9000"),
            statusRecorder.events
        )
    }

    @Test
    fun processReconcile_appendsSyntheticRemovalForStoredNotificationMissingFromActiveLookup() = runTest {
        processPosted(
            notificationCapture(
                notificationKey = "missing-key",
                packageName = "com.example.chat",
                title = "Stored title",
                text = "Stored body",
                sourcePostTime = 1_000L,
                observedAt = 2_000L,
                flags = 2,
                hasActions = true,
                appLabel = "Stored Chat",
                appInfoResolved = true
            )
        )
        val activeCapture = notificationCapture(
            notificationKey = "active-key",
            packageName = "com.example.chat",
            title = "Active title",
            text = "Active body",
            sourcePostTime = 3_000L,
            observedAt = 4_000L
        )
        val reconciler = reconciler(
            scope = this,
            currentTimeMillis = { 9_000L }
        )

        reconciler.processReconcile(NotificationReconcileEvent(listOf(activeCapture)))

        assertEquals(3, notificationDao.notifications.size)
        assertEquals(
            listOf(NotificationStatus.POSTED, NotificationStatus.REMOVED, NotificationStatus.POSTED),
            notificationDao.notifications.map { notification -> notification.status }
        )
        val missingRemoval = notificationDao.notifications[1]
        assertEquals("missing-key", missingRemoval.notificationKey)
        assertEquals("Stored title", missingRemoval.title)
        assertEquals("Stored body", missingRemoval.text)
        assertEquals(1_000L, missingRemoval.timestamp)
        assertEquals(9_000L, missingRemoval.observedAt)
        assertEquals(9_000L, missingRemoval.removedAt)
        assertEquals(8_000L, missingRemoval.timeToRemoval)
        assertEquals(2, missingRemoval.flags)
        assertEquals(true, missingRemoval.hasActions)
        assertEquals(null, eventProcessor.activeSnapshot("missing-key"))
        assertEquals("active-key", notificationDao.notifications[2].notificationKey)
        assertEquals(
            listOf("posted:4000", "reconciled:9000"),
            statusRecorder.events
        )
    }

    @Test
    fun processReconcile_marksOnlyStoredActiveNotificationsMissingFromLookupAsStale() = runTest {
        notificationRepository.recordPosted(
            notificationCapture(
                notificationKey = "still-active-key",
                packageName = "com.example.chat",
                title = "Still active title",
                sourcePostTime = 1_000L,
                observedAt = 2_000L
            )
        )
        notificationRepository.recordPosted(
            notificationCapture(
                notificationKey = "stale-key",
                packageName = "com.example.mail",
                title = "Stale title",
                text = "Stale body",
                sourcePostTime = 3_000L,
                observedAt = 4_000L,
                flags = 16,
                hasActions = true
            )
        )
        val reconciler = reconciler(
            scope = this,
            currentTimeMillis = { 9_000L }
        )

        reconciler.processReconcile(
            NotificationReconcileEvent(
                listOf(
                    notificationCapture(
                        notificationKey = "still-active-key",
                        packageName = "com.example.chat",
                        observedAt = 8_000L
                    )
                )
            )
        )

        assertEquals(3, notificationDao.notifications.size)
        assertEquals(
            listOf(NotificationStatus.POSTED, NotificationStatus.POSTED, NotificationStatus.REMOVED),
            notificationDao.notifications.map { notification -> notification.status }
        )

        val activeRows = notificationDao.notifications.filter { notification ->
            notification.notificationKey == "still-active-key"
        }
        assertEquals(1, activeRows.size)
        assertEquals(NotificationStatus.POSTED, activeRows.single().status)

        val staleRemoval = notificationDao.notifications.single { notification ->
            notification.notificationKey == "stale-key" &&
                notification.status == NotificationStatus.REMOVED
        }
        assertEquals("Stale title", staleRemoval.title)
        assertEquals("Stale body", staleRemoval.text)
        assertEquals(9_000L, staleRemoval.observedAt)
        assertEquals(9_000L, staleRemoval.removedAt)
        assertEquals(6_000L, staleRemoval.timeToRemoval)
        assertEquals(16, staleRemoval.flags)
        assertEquals(true, staleRemoval.hasActions)
        assertEquals(listOf("reconciled:9000"), statusRecorder.events)
    }

    @Test
    fun processReconcile_doesNotTerminalizePostedRowsObservedAfterActiveSnapshotCutoff() = runTest {
        notificationRepository.recordPosted(
            notificationCapture(
                notificationKey = "old-stale-key",
                packageName = "com.example.chat",
                title = "Old stale title",
                sourcePostTime = 1_000L,
                observedAt = 2_000L
            )
        )
        notificationRepository.recordPosted(
            notificationCapture(
                notificationKey = "new-posted-key",
                packageName = "com.example.chat",
                title = "New posted title",
                sourcePostTime = 2_500L,
                observedAt = 3_000L
            )
        )
        val reconciler = reconciler(
            scope = this,
            currentTimeMillis = { 9_000L }
        )

        reconciler.processReconcile(
            NotificationReconcileEvent(
                activeCaptures = emptyList(),
                snapshotCapturedAt = 2_500L
            )
        )

        val oldRows = notificationDao.notifications.filter { notification ->
            notification.notificationKey == "old-stale-key"
        }
        assertEquals(
            listOf(NotificationStatus.POSTED, NotificationStatus.REMOVED),
            oldRows.map { notification -> notification.status }
        )

        val newRows = notificationDao.notifications.filter { notification ->
            notification.notificationKey == "new-posted-key"
        }
        assertEquals(listOf(NotificationStatus.POSTED), newRows.map { notification -> notification.status })
        assertEquals(listOf("reconciled:9000"), statusRecorder.events)
    }

    @Test
    fun processReconcile_doesNotAppendDuplicateRemovalForAlreadyTerminalMissingNotification() = runTest {
        val postedCapture = notificationCapture(
            notificationKey = "terminal-key",
            packageName = "com.example.chat",
            title = "Stored title",
            sourcePostTime = 1_000L,
            observedAt = 2_000L
        )
        notificationRepository.recordPosted(postedCapture)
        notificationRepository.recordRemoved(
            capture = postedCapture.copy(observedAt = 3_000L),
            status = NotificationStatus.REMOVED,
            removalReason = RemovalReason.UNKNOWN,
            timeToRemoval = 1_000L,
            removedAt = 3_000L
        )
        val activeCapture = notificationCapture(
            notificationKey = "active-key",
            packageName = "com.example.chat",
            sourcePostTime = 4_000L,
            observedAt = 5_000L
        )
        val reconciler = reconciler(
            scope = this,
            currentTimeMillis = { 9_000L }
        )

        reconciler.processReconcile(NotificationReconcileEvent(listOf(activeCapture)))

        val terminalRows = notificationDao.notifications.filter { notification ->
            notification.notificationKey == "terminal-key" &&
                notification.status != NotificationStatus.POSTED
        }
        assertEquals(1, terminalRows.size)
        assertEquals(3_000L, terminalRows.single().removedAt)
        assertEquals(
            listOf(NotificationStatus.POSTED, NotificationStatus.REMOVED, NotificationStatus.POSTED),
            notificationDao.notifications.map { notification -> notification.status }
        )
        assertEquals(listOf("posted:5000", "reconciled:9000"), statusRecorder.events)
    }

    @Test
    fun processReconcile_preservesStoredActiveNotificationAndRefreshesSnapshot() = runTest {
        notificationRepository.recordPosted(
            notificationCapture(
                notificationKey = "active-key",
                packageName = "com.example.chat",
                title = "Stored title",
                text = "Stored body",
                sourcePostTime = 1_000L,
                observedAt = 2_000L,
                flags = 1,
                hasActions = true,
                appLabel = "Stored Chat",
                appInfoResolved = true
            )
        )
        val activeCapture = notificationCapture(
            notificationKey = "active-key",
            packageName = "com.example.chat",
            title = "",
            text = "",
            sourcePostTime = 3_000L,
            observedAt = 4_000L,
            flags = 8,
            hasActions = false
        )
        val reconciler = reconciler(
            scope = this,
            currentTimeMillis = { 9_000L }
        )

        reconciler.processReconcile(NotificationReconcileEvent(listOf(activeCapture)))

        assertEquals(1, notificationDao.notifications.size)
        assertEquals(NotificationStatus.POSTED, notificationDao.notifications.single().status)

        val activeSnapshot = eventProcessor.activeSnapshot("active-key")
        assertEquals("Stored title", activeSnapshot?.title)
        assertEquals("Stored body", activeSnapshot?.text)
        assertEquals(1_000L, activeSnapshot?.sourcePostTime)
        assertEquals(4_000L, activeSnapshot?.observedAt)
        assertEquals(8, activeSnapshot?.flags)
        assertEquals(true, activeSnapshot?.hasActions)
        assertEquals("Stored Chat", activeSnapshot?.appLabel)
        assertEquals(true, activeSnapshot?.appInfoResolved)
        assertEquals(listOf("reconciled:9000"), statusRecorder.events)
    }

    @Test
    fun processReconcile_updatesExistingActiveSnapshotWithoutAppendingDuplicatePost() = runTest {
        processPosted(
            notificationCapture(
                notificationKey = "active-key",
                packageName = "com.example.chat",
                title = "Snapshot title",
                text = "Snapshot body",
                sourcePostTime = 1_000L,
                observedAt = 2_000L,
                flags = 1,
                hasActions = false
            )
        )
        val activeCapture = notificationCapture(
            notificationKey = "active-key",
            packageName = "com.example.chat",
            title = "Lookup title",
            text = "Lookup body",
            sourcePostTime = 3_000L,
            observedAt = 4_000L,
            flags = 8,
            hasActions = true
        )
        val reconciler = reconciler(
            scope = this,
            currentTimeMillis = { 9_000L }
        )

        reconciler.processReconcile(NotificationReconcileEvent(listOf(activeCapture)))

        assertEquals(1, notificationDao.notifications.size)
        assertEquals(NotificationStatus.POSTED, notificationDao.notifications.single().status)

        val activeSnapshot = eventProcessor.activeSnapshot("active-key")
        assertEquals("Snapshot title", activeSnapshot?.title)
        assertEquals("Snapshot body", activeSnapshot?.text)
        assertEquals(1_000L, activeSnapshot?.sourcePostTime)
        assertEquals(4_000L, activeSnapshot?.observedAt)
        assertEquals(8, activeSnapshot?.flags)
        assertEquals(true, activeSnapshot?.hasActions)
        assertEquals(listOf("reconciled:9000"), statusRecorder.events)
    }

    private fun reconciler(
        scope: CoroutineScope,
        readActiveNotifications: () -> List<NotificationCapture>? = { emptyList() },
        enqueueReconcile: (NotificationReconcileEvent) -> Unit = {},
        currentTimeMillis: () -> Long = { 1_000L }
    ): NotificationReconciler {
        return NotificationReconciler(
            notificationRepository = notificationRepository,
            notificationEventProcessor = eventProcessor,
            statusRecorder = statusRecorder,
            readActiveNotifications = readActiveNotifications,
            enqueueReconcile = enqueueReconcile,
            scope = scope,
            isAcceptingEvents = { true },
            currentTimeMillis = currentTimeMillis,
            retryDelayMillis = 1L
        )
    }

    private suspend fun processPosted(capture: NotificationCapture): NotificationProcessingResult.Posted {
        return eventProcessor.process(NotificationProcessingCommand.Posted(capture))
            as NotificationProcessingResult.Posted
    }

    private class FakeReconcileStatusRecorder : NotificationReconcileStatusRecorder {
        val events = mutableListOf<String>()

        override fun markPosted(at: Long) {
            events += "posted:$at"
        }

        override fun markReconciled(at: Long) {
            events += "reconciled:$at"
        }

        override fun markError(message: String) {
            events += "error:$message"
        }
    }
}
