package com.example.pushrecorder.service

import android.service.notification.NotificationListenerService
import com.example.pushrecorder.data.NotificationEventJournalDao
import com.example.pushrecorder.data.NotificationEventJournalEntity
import com.example.pushrecorder.data.NotificationEventJournalRetentionPolicy
import com.example.pushrecorder.data.notificationCapture
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationEventJournalRepositoryTest {
    private val journalDao = FakeNotificationEventJournalDao()
    private val repository = NotificationEventJournalRepository(
        journalDao = journalDao,
        currentTimeMillis = { 9_000L }
    )

    @Test
    fun journalIfRequired_persistsPostedSnapshotAndReturnsCommandWithJournalId() = runTest {
        val command = NotificationProcessingCommand.Posted(
            notificationCapture(
                notificationKey = "posted-key",
                packageName = "com.example.chat",
                title = "hello",
                text = "body",
                sourcePostTime = 1_000L,
                observedAt = 2_000L,
                flags = 4,
                hasActions = true,
                appLabel = "Chat",
                appInfoResolved = true
            )
        )

        val journaled = repository.journalIfRequired(command)

        assertEquals(1L, journaled.eventJournalId)
        val saved = journalDao.events.single()
        assertEquals(NotificationEventJournalEntity.TYPE_POSTED, saved.eventType)
        assertEquals("posted-key", saved.notificationKey)
        assertEquals("com.example.chat", saved.packageName)
        assertEquals("hello", saved.title)
        assertEquals("body", saved.text)
        assertEquals(1_000L, saved.sourcePostTime)
        assertEquals(2_000L, saved.observedAt)
        assertEquals(4, saved.flags)
        assertTrue(saved.hasActions)
        assertEquals("Chat", saved.appLabel)
        assertTrue(saved.appInfoResolved)
        assertNull(saved.systemReason)
        assertEquals(9_000L, saved.createdAt)
        assertEquals(0, saved.retryCount)
        assertEquals(9_000L, saved.nextAttemptAt)
        assertNull(saved.lastAttemptAt)
        assertNull(saved.lastError)
    }

    @Test
    fun pendingEvents_replaysPostedAndRemovedEventsInJournalOrder() = runTest {
        val posted = repository.journalIfRequired(
            NotificationProcessingCommand.Posted(
                notificationCapture(notificationKey = "posted-key")
            )
        )
        val removed = repository.journalIfRequired(
            NotificationProcessingCommand.Removed(
                NotificationRemovalCommand(
                    capture = notificationCapture(notificationKey = "removed-key"),
                    systemReason = NotificationListenerService.REASON_CLICK
                )
            )
        )

        val pendingEvents = repository.pendingEvents()

        assertEquals(listOf(posted.eventJournalId, removed.eventJournalId), pendingEvents.map { it.eventJournalId })
        assertTrue(pendingEvents[0] is NotificationProcessingCommand.Posted)
        val removedCommand = pendingEvents[1] as NotificationProcessingCommand.Removed
        assertEquals("removed-key", removedCommand.notificationKey)
        assertEquals(NotificationListenerService.REASON_CLICK, removedCommand.command.systemReason)
    }

    @Test
    fun journalIfRequired_doesNotPersistReconcileOrAlreadyJournaledEvents() = runTest {
        val reconcile = NotificationProcessingCommand.Reconcile(
            activeCaptures = emptyList(),
            snapshotCapturedAt = 1_000L
        )
        val alreadyJournaled = NotificationProcessingCommand.Posted(
            capture = notificationCapture(notificationKey = "already"),
            eventJournalId = 123L
        )

        assertEquals(reconcile, repository.journalIfRequired(reconcile))
        assertEquals(alreadyJournaled, repository.journalIfRequired(alreadyJournaled))
        assertEquals(emptyList<NotificationEventJournalEntity>(), journalDao.events)
    }

    @Test
    fun acknowledge_deletesJournaledEvent() = runTest {
        val journaled = repository.journalIfRequired(
            NotificationProcessingCommand.Posted(
                notificationCapture(notificationKey = "posted-key")
            )
        )

        repository.acknowledge(journaled)

        assertEquals(emptyList<NotificationEventJournalEntity>(), journalDao.events)
    }

    @Test
    fun pendingEvents_replaysOnlyDueRowsUpToPolicyBatchSize() = runTest {
        val policy = NotificationEventJournalRetentionPolicy(
            maxPendingAgeMillis = 10_000L,
            maxPendingRows = 100,
            replayBatchSize = 2,
            retryBaseDelayMillis = 1_000L,
            retryMaxDelayMillis = 60_000L
        )
        val repository = NotificationEventJournalRepository(
            journalDao = journalDao,
            currentTimeMillis = { 9_000L },
            retentionPolicy = policy
        )
        journalDao.events += listOf(
            journalEntity(id = 1L, notificationKey = "due-1", nextAttemptAt = 8_000L),
            journalEntity(id = 2L, notificationKey = "future", nextAttemptAt = 9_001L),
            journalEntity(id = 3L, notificationKey = "due-2", nextAttemptAt = 9_000L),
            journalEntity(id = 4L, notificationKey = "due-3", nextAttemptAt = 1L)
        )

        val pendingEvents = repository.pendingEvents()

        assertEquals(listOf("due-1", "due-2"), pendingEvents.map { event -> event.notificationKey })
    }

    @Test
    fun markProcessingFailed_updatesRetryMetadataForJournaledEvent() = runTest {
        val repository = NotificationEventJournalRepository(
            journalDao = journalDao,
            currentTimeMillis = { 20_000L },
            retentionPolicy = NotificationEventJournalRetentionPolicy(
                maxPendingAgeMillis = 10_000L,
                maxPendingRows = 100,
                replayBatchSize = 100,
                retryBaseDelayMillis = 1_000L,
                retryMaxDelayMillis = 60_000L
            )
        )
        val command = repository.journalIfRequired(
            NotificationProcessingCommand.Posted(
                notificationCapture(notificationKey = "retry-key")
            )
        )

        repository.markProcessingFailed(command, IllegalStateException("database locked"))

        val saved = journalDao.events.single()
        assertEquals(1, saved.retryCount)
        assertEquals(20_000L, saved.lastAttemptAt)
        assertEquals(21_000L, saved.nextAttemptAt)
        assertEquals("database locked", saved.lastError)
    }

    @Test
    fun retryState_survivesRepositoryRecreationAndControlsReplay() = runTest {
        val policy = NotificationEventJournalRetentionPolicy(
            maxPendingAgeMillis = 10_000L,
            maxPendingRows = 100,
            replayBatchSize = 100,
            retryBaseDelayMillis = 1_000L,
            retryMaxDelayMillis = 60_000L
        )
        val firstRepository = NotificationEventJournalRepository(
            journalDao = journalDao,
            currentTimeMillis = { 20_000L },
            retentionPolicy = policy
        )
        val command = firstRepository.journalIfRequired(
            NotificationProcessingCommand.Posted(
                notificationCapture(notificationKey = "retry-after-recreate")
            )
        )

        firstRepository.markProcessingFailed(command, IllegalStateException("database locked"))

        val beforeRetryWindowRepository = NotificationEventJournalRepository(
            journalDao = journalDao,
            currentTimeMillis = { 20_999L },
            retentionPolicy = policy
        )
        assertEquals(emptyList<NotificationProcessingCommand>(), beforeRetryWindowRepository.pendingEvents())

        val replayRepository = NotificationEventJournalRepository(
            journalDao = journalDao,
            currentTimeMillis = { 21_000L },
            retentionPolicy = policy
        )
        val replayed = replayRepository.pendingEvents().single()
        assertEquals(command.eventJournalId, replayed.eventJournalId)
        assertEquals("retry-after-recreate", replayed.notificationKey)

        replayRepository.markProcessingFailed(replayed, IllegalStateException("still locked"))

        val saved = journalDao.events.single()
        assertEquals(2, saved.retryCount)
        assertEquals(21_000L, saved.lastAttemptAt)
        assertEquals(23_000L, saved.nextAttemptAt)
        assertEquals("still locked", saved.lastError)
    }

    @Test
    fun deleteExpiredPendingEvents_appliesAgeAndMaxRowRetention() = runTest {
        val repository = NotificationEventJournalRepository(
            journalDao = journalDao,
            currentTimeMillis = { 100_000L },
            retentionPolicy = NotificationEventJournalRetentionPolicy(
                maxPendingAgeMillis = 10_000L,
                maxPendingRows = 2,
                replayBatchSize = 100,
                retryBaseDelayMillis = 1_000L,
                retryMaxDelayMillis = 60_000L
            )
        )
        journalDao.events += listOf(
            journalEntity(id = 1L, notificationKey = "expired", createdAt = 89_999L),
            journalEntity(id = 2L, notificationKey = "old-retained", createdAt = 90_000L),
            journalEntity(id = 3L, notificationKey = "newer", createdAt = 95_000L),
            journalEntity(id = 4L, notificationKey = "newest", createdAt = 99_000L)
        )

        val deletedCount = repository.deleteExpiredPendingEvents()

        assertEquals(2, deletedCount)
        assertEquals(listOf("newer", "newest"), journalDao.events.map { event -> event.notificationKey })
    }
}

class FakeNotificationEventJournalDao : NotificationEventJournalDao {
    private var nextId = 1L

    var nextInsertResult: Long? = null

    val events = mutableListOf<NotificationEventJournalEntity>()
    var deleteByIdCallCount = 0
        private set
    var markAttemptFailedCallCount = 0
        private set
    var pendingEventsCallCount = 0
        private set

    override suspend fun insert(event: NotificationEventJournalEntity): Long {
        nextInsertResult?.let { result ->
            nextInsertResult = null
            return result
        }
        if (event.id != 0L && events.any { saved -> saved.id == event.id }) {
            return -1L
        }
        val savedEvent = if (event.id == 0L) {
            event.copy(id = nextId++)
        } else {
            nextId = maxOf(nextId, event.id + 1)
            event
        }
        events += savedEvent
        return savedEvent.id
    }

    override suspend fun pendingEvents(
        now: Long,
        limit: Int
    ): List<NotificationEventJournalEntity> {
        pendingEventsCallCount += 1
        return events
            .filter { event -> event.nextAttemptAt <= now }
            .sortedBy(NotificationEventJournalEntity::id)
            .take(limit)
    }

    override suspend fun deleteById(id: Long): Int {
        deleteByIdCallCount += 1
        val beforeSize = events.size
        events.removeAll { event -> event.id == id }
        return beforeSize - events.size
    }

    override suspend fun retryCountForId(id: Long): Int? {
        return events.firstOrNull { event -> event.id == id }?.retryCount
    }

    override suspend fun markAttemptFailed(
        id: Long,
        retryCount: Int,
        lastAttemptAt: Long,
        nextAttemptAt: Long,
        lastError: String?
    ): Int {
        markAttemptFailedCallCount += 1
        val index = events.indexOfFirst { event -> event.id == id }
        if (index < 0) return 0
        events[index] = events[index].copy(
            retryCount = retryCount,
            lastAttemptAt = lastAttemptAt,
            nextAttemptAt = nextAttemptAt,
            lastError = lastError
        )
        return 1
    }

    override suspend fun deleteCreatedBefore(cutoffTimestamp: Long): Int {
        val beforeSize = events.size
        events.removeAll { event -> event.createdAt < cutoffTimestamp }
        return beforeSize - events.size
    }

    override suspend fun trimToNewest(maxRows: Int): Int {
        val retainedIds = events
            .sortedByDescending(NotificationEventJournalEntity::id)
            .take(maxRows)
            .map(NotificationEventJournalEntity::id)
            .toSet()
        val beforeSize = events.size
        events.removeAll { event -> event.id !in retainedIds }
        events.sortBy(NotificationEventJournalEntity::id)
        return beforeSize - events.size
    }
}

private fun journalEntity(
    id: Long,
    notificationKey: String,
    createdAt: Long = 1_000L,
    nextAttemptAt: Long = createdAt
): NotificationEventJournalEntity {
    return NotificationEventJournalEntity(
        id = id,
        eventType = NotificationEventJournalEntity.TYPE_POSTED,
        notificationKey = notificationKey,
        packageName = "com.example",
        title = "title",
        text = "text",
        sourcePostTime = 1_000L,
        observedAt = 1_000L,
        flags = 0,
        hasActions = false,
        appLabel = "Example",
        appInfoResolved = false,
        systemReason = null,
        createdAt = createdAt,
        nextAttemptAt = nextAttemptAt
    )
}
