package com.example.pushrecorder.service

import android.service.notification.NotificationListenerService as AndroidNotificationListenerService
import com.example.pushrecorder.appinfo.AppRegistryRepository
import com.example.pushrecorder.appinfo.FakeAppInfoSource
import com.example.pushrecorder.appinfo.FakeAppRecordDao
import com.example.pushrecorder.data.FakeNotificationDao
import com.example.pushrecorder.data.NotificationEntity
import com.example.pushrecorder.data.NotificationEventJournalEntity
import com.example.pushrecorder.data.NotificationRepository
import com.example.pushrecorder.data.NotificationStatus
import com.example.pushrecorder.data.RemovalReason
import com.example.pushrecorder.data.notificationCapture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationProcessingPipelineSemanticsTest {
    @Test
    fun journaledQueueProcessing_preservesFifoPostedThenRemovedLifecycleSemantics() = runTest {
        val pipeline = processingPipeline(currentTimeMillis = { 5_000L })
        pipeline.appInfoSource.setResolved(
            packageName = "com.example.chat",
            label = "Chat"
        )
        pipeline.start()

        assertTrue(
            pipeline.enqueue(
                NotificationProcessingCommand.Posted(
                    notificationCapture(
                        notificationKey = "chat-key",
                        packageName = "com.example.chat",
                        title = "New message",
                        text = "Hello",
                        sourcePostTime = 1_000L,
                        observedAt = 2_000L
                    )
                )
            )
        )
        assertTrue(
            pipeline.enqueue(
                NotificationProcessingCommand.Removed(
                    NotificationRemovalCommand(
                        capture = notificationCapture(
                            notificationKey = "chat-key",
                            packageName = "com.example.chat",
                            title = "",
                            text = "",
                            sourcePostTime = 9_000L,
                            observedAt = 3_000L
                        ),
                        systemReason = AndroidNotificationListenerService.REASON_CLICK
                    )
                )
            )
        )
        runCurrent()

        assertEquals(listOf("chat-key", "chat-key"), pipeline.processedKeys)
        assertEquals(
            emptyList<NotificationEventJournalEntitySummary>(),
            pipeline.pendingJournalSummaries()
        )
        assertEquals(
            listOf(NotificationStatus.POSTED, NotificationStatus.CLICKED),
            pipeline.notificationDao.notifications.map(NotificationEntity::status)
        )
        val removed = pipeline.notificationDao.notifications.last()
        assertEquals("New message", removed.title)
        assertEquals("Hello", removed.text)
        assertEquals(1_000L, removed.timestamp)
        assertEquals(5_000L, removed.observedAt)
        assertEquals(RemovalReason.USER_CLICKED, removed.removalReason)
        assertEquals(4_000L, removed.timeToRemoval)
        assertEquals(5_000L, removed.removedAt)
        assertEquals(2, pipeline.status.queuedCount)
        assertEquals(2, pipeline.status.processedCount)
        assertEquals(listOf(2_000L), pipeline.status.postedAt)
        assertEquals(listOf(5_000L), pipeline.status.removedAt)
    }

    @Test
    fun journaledQueueProcessing_whenOneEventFails_keepsFailedJournalAndContinuesNextEvent() = runTest {
        val pipeline = processingPipeline(failingKeys = setOf("bad-key"))
        pipeline.start()

        assertTrue(
            pipeline.enqueue(
                NotificationProcessingCommand.Posted(
                    notificationCapture(notificationKey = "bad-key")
                )
            )
        )
        assertTrue(
            pipeline.enqueue(
                NotificationProcessingCommand.Posted(
                    notificationCapture(
                        notificationKey = "good-key",
                        title = "Still processed"
                    )
                )
            )
        )
        runCurrent()

        assertEquals(listOf("good-key"), pipeline.processedKeys)
        assertEquals(listOf("bad-key"), pipeline.status.failedKeys)
        assertEquals(2, pipeline.status.queuedCount)
        assertEquals(1, pipeline.status.processedCount)
        assertEquals(
            listOf("Still processed"),
            pipeline.notificationDao.notifications.map { it.title }
        )
        assertEquals(
            listOf(NotificationEventJournalEntitySummary(id = 1L, notificationKey = "bad-key")),
            pipeline.pendingJournalSummaries()
        )
        val failedJournal = pipeline.pendingJournalRows().single()
        assertEquals(1, failedJournal.retryCount)
        assertEquals(1_000L, failedJournal.lastAttemptAt)
        assertEquals(2_000L, failedJournal.nextAttemptAt)
    }

    @Test
    fun journaledQueueProcessing_acknowledgesJournalOnlyAfterDownstreamProcessingSucceeds() = runTest {
        val pipeline = processingPipeline(failingKeys = setOf("bad-key"))
        pipeline.start()

        assertTrue(
            pipeline.enqueue(
                NotificationProcessingCommand.Posted(
                    notificationCapture(notificationKey = "bad-key")
                )
            )
        )
        runCurrent()

        assertEquals(emptyList<String>(), pipeline.processedKeys)
        assertEquals(listOf("bad-key"), pipeline.status.failedKeys)
        assertEquals(
            listOf(NotificationEventJournalEntitySummary(id = 1L, notificationKey = "bad-key")),
            pipeline.pendingJournalSummaries()
        )

        pipeline.clearFailures()
        pipeline.replayPendingEvents()
        runCurrent()

        assertEquals(emptyList<String>(), pipeline.processedKeys)
        assertEquals(
            listOf(NotificationEventJournalEntitySummary(id = 1L, notificationKey = "bad-key")),
            pipeline.pendingJournalSummaries()
        )

        pipeline.advanceJournalClockTo(2_000L)
        pipeline.replayPendingEvents()
        runCurrent()

        assertEquals(listOf("bad-key"), pipeline.processedKeys)
        assertEquals(
            emptyList<NotificationEventJournalEntitySummary>(),
            pipeline.pendingJournalSummaries()
        )
        assertEquals(1, pipeline.status.processedCount)
    }

    @Test
    fun journaledQueueProcessing_whenDownstreamInsertIsRejected_keepsJournalAndDoesNotMarkProcessed() = runTest {
        val pipeline = processingPipeline()
        pipeline.notificationDao.nextInsertResult = -1L
        pipeline.start()

        assertTrue(
            pipeline.enqueue(
                NotificationProcessingCommand.Posted(
                    notificationCapture(notificationKey = "ignored-insert-key")
                )
            )
        )
        runCurrent()

        assertEquals(emptyList<String>(), pipeline.processedKeys)
        assertEquals(listOf("ignored-insert-key"), pipeline.status.failedKeys)
        assertEquals(1, pipeline.status.queuedCount)
        assertEquals(0, pipeline.status.processedCount)
        assertEquals(emptyList<Long>(), pipeline.status.postedAt)
        assertEquals(emptyList<NotificationEntity>(), pipeline.notificationDao.notifications)
        assertEquals(
            listOf(NotificationEventJournalEntitySummary(id = 1L, notificationKey = "ignored-insert-key")),
            pipeline.pendingJournalSummaries()
        )
        val failedJournal = pipeline.pendingJournalRows().single()
        assertEquals(1, failedJournal.retryCount)
        assertEquals(1_000L, failedJournal.lastAttemptAt)
        assertEquals(2_000L, failedJournal.nextAttemptAt)
        assertTrue(failedJournal.lastError?.contains("Failed to persist posted notification event") == true)
    }

    @Test
    fun listenerPostedCallback_returnsBeforeBlockingNotificationInsertCompletes() = runTest {
        val pipeline = processingPipeline()
        val insertStarted = CompletableDeferred<Unit>()
        val releaseInsert = CompletableDeferred<Unit>()
        var callbackReturned = false
        pipeline.notificationDao.beforeInsert = {
            assertTrue(
                "Blocking notification row insert must execute after the posted callback returned.",
                callbackReturned
            )
            insertStarted.complete(Unit)
            releaseInsert.await()
        }
        val adapter = NotificationListenerAdapter(
            enqueueEvent = { command -> pipeline.enqueue(command) }
        )
        pipeline.start()

        adapter.onNotificationPosted(notificationCapture(notificationKey = "posted-blocking-insert"))
        callbackReturned = true

        assertTrue(callbackReturned)
        assertFalse(
            "Notification row insert must not start inside the posted callback.",
            insertStarted.isCompleted
        )

        runCurrent()

        assertTrue(
            "Blocking notification row insert should run from queued processing after the callback returned.",
            insertStarted.isCompleted
        )
        assertEquals(emptyList<String>(), pipeline.processedKeys)

        releaseInsert.complete(Unit)
        runCurrent()

        assertEquals(listOf("posted-blocking-insert"), pipeline.processedKeys)
        assertEquals(1, pipeline.status.processedCount)
    }

    @Test
    fun listenerRemovedCallback_returnsBeforeBlockingNotificationInsertCompletes() = runTest {
        val pipeline = processingPipeline(currentTimeMillis = { 6_000L })
        val insertStarted = CompletableDeferred<Unit>()
        val releaseInsert = CompletableDeferred<Unit>()
        var callbackReturned = false
        pipeline.notificationDao.beforeInsert = {
            assertTrue(
                "Blocking notification row insert must execute after the removed callback returned.",
                callbackReturned
            )
            insertStarted.complete(Unit)
            releaseInsert.await()
        }
        val adapter = NotificationListenerAdapter(
            enqueueEvent = { command -> pipeline.enqueue(command) }
        )
        pipeline.start()

        adapter.onNotificationRemoved(
            capture = notificationCapture(notificationKey = "removed-blocking-insert"),
            systemReason = AndroidNotificationListenerService.REASON_CLICK
        )
        callbackReturned = true

        assertTrue(callbackReturned)
        assertFalse(
            "Notification row insert must not start inside the removed callback.",
            insertStarted.isCompleted
        )

        runCurrent()

        assertTrue(
            "Blocking notification row insert should run from queued processing after the callback returned.",
            insertStarted.isCompleted
        )
        assertEquals(emptyList<String>(), pipeline.processedKeys)

        releaseInsert.complete(Unit)
        runCurrent()

        assertEquals(listOf("removed-blocking-insert"), pipeline.processedKeys)
        assertEquals(1, pipeline.status.processedCount)
    }

    @Test
    fun cancellationCloseAndDrain_completesAcceptedWorkBeforeRejectingNewEvents() = runTest {
        val pipeline = processingPipeline()
        var drainedCount = 0
        pipeline.start()

        assertTrue(
            pipeline.enqueue(
                NotificationProcessingCommand.Posted(
                    notificationCapture(
                        notificationKey = "before-close",
                        title = "Before close"
                    )
                )
            )
        )
        pipeline.closeAndDrain {
            drainedCount += 1
        }
        runCurrent()

        assertEquals(1, drainedCount)
        assertEquals(listOf("before-close"), pipeline.processedKeys)
        assertEquals(
            emptyList<NotificationEventJournalEntitySummary>(),
            pipeline.pendingJournalSummaries()
        )

        assertFalse(
            pipeline.enqueue(
                NotificationProcessingCommand.Posted(
                    notificationCapture(
                        notificationKey = "after-close",
                        title = "After close"
                    )
                )
            )
        )
        runCurrent()

        assertEquals(listOf("before-close"), pipeline.processedKeys)
        assertEquals(
            emptyList<NotificationEventJournalEntitySummary>(),
            pipeline.pendingJournalSummaries()
        )
        assertEquals(emptyList<String>(), pipeline.status.rejectedKeys)
        assertEquals(
            listOf(
                "Rejected posted notification after journal persistence teardown",
                "Rejected posted notification after journal persistence teardown"
            ),
            pipeline.status.errors
        )
    }

    private fun TestScope.processingPipeline(
        currentTimeMillis: () -> Long = { 4_000L },
        failingKeys: Set<String> = emptySet()
    ): ProcessingPipeline {
        return ProcessingPipeline(
            scope = this,
            currentTimeMillis = currentTimeMillis,
            failingKeys = failingKeys
        )
    }

    private class ProcessingPipeline(
        scope: TestScope,
        currentTimeMillis: () -> Long,
        failingKeys: Set<String>
    ) {
        val notificationDao = FakeNotificationDao()
        val appInfoSource = FakeAppInfoSource()
        val status = RecordingStatusRecorder()
        val processedKeys = mutableListOf<String>()
        private val failingKeys = failingKeys.toMutableSet()
        private var journalTimeMillis = 1_000L

        private val appRecordDao = FakeAppRecordDao()
        private val notificationRepository = NotificationRepository(notificationDao)
        private val appRegistryRepository = AppRegistryRepository(appRecordDao, appInfoSource)
        private val journalDao = FakeNotificationEventJournalDao()
        private val journalDispatcher = StandardTestDispatcher(scope.testScheduler)
        private val journalRepository = NotificationEventJournalRepository(
            journalDao = journalDao,
            currentTimeMillis = { journalTimeMillis }
        )
        private val processor = NotificationEventProcessor(
            notificationRepository = notificationRepository,
            appRegistryRepository = appRegistryRepository,
            currentTimeMillis = currentTimeMillis
        )
        private val queue = NotificationProcessingQueue(
            scope = scope.backgroundScope,
            processEvent = { command -> processQueuedCommand(command) },
            onQueued = status::markEventQueued,
            onProcessed = status::markEventProcessed,
            onFailed = { command, error ->
                status.failedKeys += command.notificationKey
                status.markEventFailed(
                    message = "Failed to process ${command.eventName()}",
                    at = 0L
                )
                journalRepository.markProcessingFailed(command, error)
            },
            onRejected = { command, _, _ ->
                status.rejectedKeys += command.notificationKey
            }
        )
        private val enqueuer = NotificationDurableEventEnqueuer(
            scope = scope.backgroundScope,
            dispatcher = journalDispatcher,
            journalRepository = journalRepository,
            enqueueCommand = { command -> queue.enqueue(command) },
            statusRecorder = status,
            logError = { message, _ -> status.errors += message }
        )

        fun start() {
            queue.start()
            enqueuer.start()
        }

        fun enqueue(command: NotificationProcessingCommand): Boolean {
            return enqueuer.enqueue(command)
        }

        fun closeAndDrain(onDrained: () -> Unit) {
            enqueuer.closeAndDrain {
                queue.closeAndDrain(onDrained)
            }
        }

        fun replayPendingEvents() {
            enqueuer.replayPendingEvents()
        }

        fun clearFailures() {
            failingKeys.clear()
        }

        fun advanceJournalClockTo(timeMillis: Long) {
            journalTimeMillis = timeMillis
        }

        fun pendingJournalSummaries(): List<NotificationEventJournalEntitySummary> {
            return journalDao.events.map { event ->
                NotificationEventJournalEntitySummary(
                    id = event.id,
                    notificationKey = event.notificationKey
                )
            }
        }

        fun pendingJournalRows(): List<NotificationEventJournalEntity> {
            return journalDao.events.toList()
        }

        private suspend fun processQueuedCommand(command: NotificationProcessingCommand) {
            if (command.notificationKey in failingKeys) {
                error("forced failure")
            }

            when (val result = processor.process(command)) {
                is NotificationProcessingResult.Posted -> status.markPosted(result.observedAt)
                is NotificationProcessingResult.Removed -> status.markRemoved(result.removedAt)
            }
            processedKeys += command.notificationKey
            journalRepository.acknowledge(command)
        }
    }

    private data class NotificationEventJournalEntitySummary(
        val id: Long,
        val notificationKey: String
    )

    private class RecordingStatusRecorder : NotificationListenerEventStatusRecorder {
        var queuedCount = 0
        var processedCount = 0
        val postedAt = mutableListOf<Long>()
        val removedAt = mutableListOf<Long>()
        val failedKeys = mutableListOf<String>()
        val rejectedKeys = mutableListOf<String>()
        val errors = mutableListOf<String>()

        override fun markPosted(at: Long) {
            postedAt += at
        }

        override fun markRemoved(at: Long) {
            removedAt += at
        }

        override fun markEventQueued() {
            queuedCount += 1
        }

        override fun markEventProcessed() {
            processedCount += 1
        }

        override fun markEventFailed(message: String, at: Long) = Unit

        override fun markError(message: String, at: Long) {
            errors += message
        }

        override fun markError(message: String) {
            errors += message
        }
    }
}
