package com.example.pushrecorder.service

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationListenerServiceBoundaryTest {
    @Test
    fun serviceSourceOnlyWiresAndroidCallbacksAndDelegatesNotificationProcessing() {
        val source = readServiceSource()

        requiredBoundaryWiring.forEach { wiring ->
            assertTrue(
                "NotificationListenerService should keep Android callbacks wired through $wiring",
                source.contains(wiring)
            )
        }

        forbiddenProcessingHelpers.forEach { helper ->
            assertFalse(
                "NotificationListenerService should not directly invoke $helper; route through boundary collaborators.",
                source.contains(helper)
            )
        }
    }

    @Test
    fun notificationCallbacksStayThinAndOnlyDelegateToCaptureBoundary() {
        val source = readServiceSource()

        assertCallbackBody(
            source = source,
            signature = "override fun onNotificationPosted(sbn: StatusBarNotification)",
            expectedDelegation = "listenerAdapter.onNotificationPosted(sbn.toNotificationCapture())"
        )
        assertCallbackBody(
            source = source,
            signature = "override fun onNotificationRemoved(sbn: StatusBarNotification)",
            expectedDelegation = "listenerAdapter.onNotificationRemoved(sbn.toNotificationCapture(), systemReason = null)"
        )
        assertCallbackBody(
            source = source,
            signature = "override fun onNotificationRemoved( sbn: StatusBarNotification, rankingMap: AndroidNotificationListenerService.RankingMap, reason: Int )",
            expectedDelegation = "listenerAdapter.onNotificationRemoved(sbn.toNotificationCapture(), systemReason = reason)"
        )
    }

    @Test
    fun postedCallbackOnlyDispatchesWorkAndDoesNotWriteJournalInline() {
        val source = readServiceSource()
        val postedBody = callbackBody(
            source = source,
            signature = "override fun onNotificationPosted(sbn: StatusBarNotification)"
        )

        assertEquals(
            "Posted callback should only dispatch the captured notification into the ingestion boundary.",
            "listenerAdapter.onNotificationPosted(sbn.toNotificationCapture())",
            postedBody
        )
        forbiddenInlinePostedCallbackCalls.forEach { forbiddenCall ->
            assertFalse(
                "Posted callback must not perform blocking inline Room/journal work: $forbiddenCall",
                postedBody.contains(forbiddenCall)
            )
        }
    }

    @Test
    fun removedCallbackOnlyDispatchesWorkAndDoesNotWriteJournalInline() {
        val source = readServiceSource()
        val removedBody = callbackBody(
            source = source,
            signature = "override fun onNotificationRemoved(sbn: StatusBarNotification)"
        )

        assertEquals(
            "Removed callback should only dispatch the captured notification into the ingestion boundary.",
            "listenerAdapter.onNotificationRemoved(sbn.toNotificationCapture(), systemReason = null)",
            removedBody
        )
        forbiddenInlineRemovedCallbackCalls.forEach { forbiddenCall ->
            assertFalse(
                "Removed callback must not perform blocking inline Room/journal work: $forbiddenCall",
                removedBody.contains(forbiddenCall)
            )
        }
    }

    @Test
    fun removedCallbackWithReasonOnlyDispatchesWorkAndDoesNotWriteJournalInline() {
        val source = readServiceSource()
        val removedBody = callbackBody(
            source = source,
            signature = "override fun onNotificationRemoved( sbn: StatusBarNotification, rankingMap: AndroidNotificationListenerService.RankingMap, reason: Int )"
        )

        assertEquals(
            "Removed callback with Android reason should only dispatch the captured notification into the ingestion boundary.",
            "listenerAdapter.onNotificationRemoved(sbn.toNotificationCapture(), systemReason = reason)",
            removedBody
        )
        forbiddenInlineRemovedCallbackCalls.forEach { forbiddenCall ->
            assertFalse(
                "Removed callback with Android reason must not perform blocking inline Room/journal work: $forbiddenCall",
                removedBody.contains(forbiddenCall)
            )
        }
    }

    @Test
    fun notificationCallbackBlockingInventoryStaysDocumented() {
        val serviceSource = readSource(
            "src/main/java/com/example/pushrecorder/service/NotificationListenerService.kt",
            "app/src/main/java/com/example/pushrecorder/service/NotificationListenerService.kt"
        )
        val adapterSource = readSource(
            "src/main/java/com/example/pushrecorder/service/NotificationListenerAdapter.kt",
            "app/src/main/java/com/example/pushrecorder/service/NotificationListenerAdapter.kt"
        )
        val enqueuerSource = readSource(
            "src/main/java/com/example/pushrecorder/service/NotificationDurableEventEnqueuer.kt",
            "app/src/main/java/com/example/pushrecorder/service/NotificationDurableEventEnqueuer.kt"
        )
        val queueSource = readSource(
            "src/main/java/com/example/pushrecorder/service/NotificationProcessingQueue.kt",
            "app/src/main/java/com/example/pushrecorder/service/NotificationProcessingQueue.kt"
        )
        val statusRepositorySource = readSource(
            "src/main/java/com/example/pushrecorder/service/NotificationListenerStatusRepository.kt",
            "app/src/main/java/com/example/pushrecorder/service/NotificationListenerStatusRepository.kt"
        )
        val inventory = readSource(
            "docs/notification-ingestion-callback-flow.md",
            "../docs/notification-ingestion-callback-flow.md"
        )

        assertFalse(
            "Android service callbacks should not call runBlocking directly.",
            serviceSource.contains("runBlocking(")
        )
        assertFalse(
            "The listener adapter should stay a pure command wrapper.",
            adapterSource.contains("runBlocking(")
        )
        assertTrue(
            "The processing queue should keep callback enqueue non-suspending with trySend.",
            queueSource.contains("events.trySend(event)")
        )
        assertFalse(
            "The processing queue should not block callback threads with runBlocking.",
            queueSource.contains("runBlocking(")
        )
        assertTrue(
            "The callback-reachable durable journal bridge should enqueue non-blocking persistence work.",
            enqueuerSource.contains("commands.trySend(command)") &&
                enqueuerSource.contains("scope.launch(dispatcher)") &&
                enqueuerSource.contains("journalRepository.journalIfRequired(command)")
        )
        assertTrue(
            "The durable journal bridge should report rejection status asynchronously.",
            enqueuerSource.contains("scope.launch(dispatcher) {") &&
                enqueuerSource.contains("statusRecorder.markError(message)")
        )
        assertFalse(
            "The durable journal bridge should not block callback threads with runBlocking.",
            enqueuerSource.contains("runBlocking(")
        )
        assertTrue(
            "Startup journal replay should remain documented as outside notification callbacks.",
            enqueuerSource.contains("journalRepository.pendingEvents()")
        )
        assertTrue(
            "Storage cleanup should be automatically requested from startup and queued-event processing.",
            serviceSource.contains("requestStorageCleanup(force = true)") &&
                serviceSource.contains("requestStorageCleanup()")
        )
        assertSourceOrder(
            source = serviceSource,
            first = "durableEventEnqueuer.replayPendingEvents()",
            second = "requestStorageCleanup(force = true)",
            message = "Startup should request retention cleanup without waiting for a manual restart."
        )
        assertSourceOrder(
            source = serviceSource,
            first = "notificationEventJournalRepository.acknowledge(event)",
            second = "requestStorageCleanup()",
            message = "Queued ingestion should request cleanup after successful journal acknowledgement."
        )
        assertTrue(
            "Listener status persistence should be explicit in callback blocking inventory.",
            statusRepositorySource.contains("getSharedPreferences") &&
                statusRepositorySource.contains("preferences.edit")
        )
        blockingInventoryRequirements.forEach { requiredText ->
            assertTrue(
                "Callback blocking inventory should document: $requiredText",
                inventory.contains(requiredText)
            )
        }
    }

    @Test
    fun serviceShutdownDoesNotClearSingletonActiveSnapshots() {
        val source = readServiceSource()

        assertFalse(
            "An old listener instance must not clear singleton active snapshots populated by a newer instance",
            source.contains("clearActiveSnapshots()")
        )
    }

    private fun readServiceSource(): String {
        return readSource(
            "src/main/java/com/example/pushrecorder/service/NotificationListenerService.kt",
            "app/src/main/java/com/example/pushrecorder/service/NotificationListenerService.kt"
        )
    }

    private fun readSource(vararg candidatePaths: String): String {
        val candidates = listOf(
            *candidatePaths.map { candidatePath -> Path.of(candidatePath) }.toTypedArray()
        )
        val sourcePath = candidates.firstOrNull(Files::exists)
            ?: error("Source file was not found in known Gradle test working directories: ${candidatePaths.toList()}")

        return String(Files.readAllBytes(sourcePath))
    }

    private fun assertCallbackBody(
        source: String,
        signature: String,
        expectedDelegation: String
    ) {
        val body = callbackBody(source, signature)

        assertEquals(
            "NotificationListenerService callback $signature should stay a single boundary delegation",
            expectedDelegation,
            body
        )
    }

    private fun callbackBody(
        source: String,
        signature: String
    ): String {
        val normalizedSource = source.replace(Regex("\\s+"), " ")
        val normalizedSignature = signature.replace(Regex("\\s+"), " ")
        val startIndex = normalizedSource.indexOf(normalizedSignature)
        assertTrue(
            "NotificationListenerService should declare callback $signature",
            startIndex >= 0
        )
        val bodyStart = normalizedSource.indexOf("{", startIndex)
        val bodyEnd = normalizedSource.indexOf("}", bodyStart)
        return normalizedSource.substring(bodyStart + 1, bodyEnd).trim()
    }

    private fun assertSourceOrder(
        source: String,
        first: String,
        second: String,
        message: String
    ) {
        val firstIndex = source.indexOf(first)
        val secondIndex = source.indexOf(second)

        assertTrue("$message Missing source anchor: $first", firstIndex >= 0)
        assertTrue("$message Missing source anchor: $second", secondIndex >= 0)
        assertTrue(message, firstIndex < secondIndex)
    }

    private companion object {
        private val requiredBoundaryWiring = listOf(
            "class NotificationListenerService : AndroidNotificationListenerService()",
            "notificationEventProcessor: NotificationEventProcessor",
            "private val listenerAdapter: NotificationListenerAdapter by lazy",
            "NotificationListenerAdapter(",
            "durableEventEnqueuer.enqueue(event)",
            "NotificationReconciler(",
            "listenerAdapter.onNotificationPosted(sbn.toNotificationCapture())",
            "listenerAdapter.onNotificationRemoved(sbn.toNotificationCapture(), systemReason = null)",
            "listenerAdapter.onNotificationRemoved(sbn.toNotificationCapture(), systemReason = reason)",
            "listenerConnectionAdapter.onListenerConnected()",
            "listenerConnectionAdapter.onListenerDisconnected()"
        )

        private val forbiddenProcessingHelpers = listOf(
            "NotificationContentExtractor",
            "NotificationRemovalClassifier",
            "ActiveNotificationLookup",
            ".processPosted(",
            ".processRemoved(",
            ".recordPosted(",
            ".recordRemoved(",
            "runBlocking("
        )

        private val forbiddenInlinePostedCallbackCalls = listOf(
            "journalIfRequired",
            "NotificationEventJournalDao",
            "notificationEventJournalRepository",
            "insert(",
            "pendingEvents(",
            "acknowledge(",
            "markProcessingFailed(",
            "deleteById(",
            "runBlocking(",
            ".recordPosted(",
            ".process("
        )

        private val forbiddenInlineRemovedCallbackCalls = listOf(
            "journalIfRequired",
            "NotificationEventJournalDao",
            "notificationEventJournalRepository",
            "insert(",
            "pendingEvents(",
            "acknowledge(",
            "markProcessingFailed(",
            "deleteById(",
            "runBlocking(",
            ".recordRemoved(",
            ".process("
        )

        private val blockingInventoryRequirements = listOf(
            "NotificationListenerService.onNotificationPosted(sbn)",
            "NotificationListenerService.onNotificationRemoved(sbn)",
            "NotificationListenerService.onNotificationRemoved(sbn, rankingMap, reason)",
            "StatusBarNotification.toNotificationCapture()",
            "NotificationListenerAdapter.onNotificationPosted(capture)",
            "NotificationListenerAdapter.onNotificationRemoved(capture, systemReason)",
            "NotificationDurableEventEnqueuer.enqueue(event)",
            "NotificationProcessingQueue.enqueue(event)",
            "Channel.trySend` into the bounded journal persistence queue",
            "journalRepository.journalIfRequired(command)",
            "NotificationEventJournalDao.insert",
            "eventType = REMOVED",
            "callback `systemReason` preserved when Android provides one",
            "## Callback-Reachable Room Mutation Inventory",
            "Synchronous Room update",
            "Synchronous Room delete",
            "None reached from the Android callback",
            "NotificationRepository.recordRemoved",
            "the foreground event is rejected before it enters the processing queue",
            "No callback-reachable Room insert, update, or delete remains",
            "## Intentionally Remaining Blocking Persistence Calls",
            "own call sites",
            "run from service startup/shutdown,",
            "NotificationDurableEventEnqueuer.replayPendingEvents",
            "It is called from `NotificationListenerService.onCreate`, not from `onNotificationPosted`",
            "Startup replay keeps already-journaled events durable across process death",
            "NotificationDurableEventEnqueuer.persistThenEnqueue",
            "journalRepository.journalIfRequired(command)` reaches `NotificationEventJournalDao.insert",
            "which only checks queue state and calls `commands.trySend(command)`",
            "A durable local journal protects accepted POSTED/REMOVED events",
            "NotificationListenerService.processQueuedEvent",
            "notificationEventJournalRepository.acknowledge(event)` deletes the processed journal row",
            "Acknowledgement only happens after `processEvent(event)` succeeds",
            "NotificationListenerService` queue failure handler",
            "notificationEventJournalRepository.markProcessingFailed(event, error)",
            "Retry metadata prevents tight replay loops",
            "NotificationStorageCleanupScheduler.requestCleanup",
            "after queued event processing and service startup",
            "Automatic retention enforces local storage bounds",
            "NotificationEventProcessor.process",
            "preserve the existing two-row POSTED/REMOVED event-log model",
            "NotificationReconciler.processReconcile",
            "Reconciliation repairs missed removals",
            "AppRegistryRepository.packageSnapshot",
            "App labels/icons can be resolved and cached",
            "NotificationListenerStatusRepository.updateStatus",
            "Service lifecycle and connection status calls are reached from `onCreate`, `onDestroy`, `onListenerConnected`, and `onListenerDisconnected`",
            "Status persistence is small local diagnostic state",
            "## Callback-Reachable Non-Room Persistence Inventory",
            "NotificationListenerStatusRepository.markError",
            "SharedPreferences.edit",
            "the callback path returns before the status write runs",
            "markPosted",
            "markEventQueued",
            "from the successful Android callback path",
            "## Bounded Queue Rejection Paths",
            "no other callback-reachable `offer` or suspending `send` path",
            "Journal persistence queue, `NotificationDurableEventEnqueuer.enqueue`",
            "Processing queue, `NotificationProcessingQueue.enqueue`",
            "statusRecorder.markError` and logging are scheduled on the service dispatcher",
            "commands.trySend(command)",
            "events.trySend(event)",
            "No journal acknowledgement occurs because no journal row exists",
            "Journaled POSTED/REMOVED commands remain pending for replay",
            "Non-journaled `Reconcile` commands are dropped because there is no journal row to retry",
            "acknowledge(event)` only after",
            "markProcessingFailed`; the failed command is not counted as processed",
            "The current POSTED/REMOVED event-log model is preserved"
        )
    }
}
