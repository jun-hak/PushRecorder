package com.example.pushrecorder.service

import com.example.pushrecorder.data.NotificationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationStorageCleanupScheduler internal constructor(
    private val currentTimeMillis: () -> Long,
    private val cleanupIntervalMillis: Long,
    private val deleteExpiredNotifications: suspend () -> Int,
    private val deleteExpiredPendingEvents: suspend () -> Int
) {
    @Inject
    constructor(
        notificationRepository: NotificationRepository,
        journalRepository: NotificationEventJournalRepository
    ) : this(
        currentTimeMillis = System::currentTimeMillis,
        cleanupIntervalMillis = DEFAULT_CLEANUP_INTERVAL_MILLIS,
        deleteExpiredNotifications = notificationRepository::deleteExpiredNotifications,
        deleteExpiredPendingEvents = journalRepository::deleteExpiredPendingEvents
    )

    private val cleanupRunning = AtomicBoolean(false)

    @Volatile
    private var nextEligibleCleanupAt: Long = 0L

    init {
        require(cleanupIntervalMillis > 0L) {
            "cleanupIntervalMillis must be positive."
        }
    }

    fun requestCleanup(
        scope: CoroutineScope,
        force: Boolean = false,
        onFailure: (Throwable) -> Unit = {}
    ): Boolean {
        if (!markCleanupRequested(force)) return false

        scope.launch {
            runMarkedCleanup(onFailure)
        }
        return true
    }

    suspend fun runCleanup(
        force: Boolean = false,
        onFailure: (Throwable) -> Unit = {}
    ): Boolean {
        if (!markCleanupRequested(force)) return false

        runMarkedCleanup(onFailure)
        return true
    }

    private fun markCleanupRequested(force: Boolean): Boolean {
        val now = currentTimeMillis()
        synchronized(this) {
            if (!force && now < nextEligibleCleanupAt) {
                return false
            }
            if (!cleanupRunning.compareAndSet(false, true)) {
                return false
            }
            nextEligibleCleanupAt = now.saturatingAdd(cleanupIntervalMillis)
        }
        return true
    }

    private suspend fun runMarkedCleanup(onFailure: (Throwable) -> Unit) {
        try {
            runCleanupStep(onFailure, deleteExpiredNotifications)
            runCleanupStep(onFailure, deleteExpiredPendingEvents)
        } finally {
            cleanupRunning.set(false)
        }
    }

    private suspend fun runCleanupStep(
        onFailure: (Throwable) -> Unit,
        cleanup: suspend () -> Int
    ) {
        try {
            cleanup()
        } catch (error: Throwable) {
            onFailure(error)
        }
    }

    companion object {
        const val DEFAULT_CLEANUP_INTERVAL_MILLIS = 6L * 60L * 60L * 1000L
    }
}

private fun Long.saturatingAdd(other: Long): Long {
    return if (Long.MAX_VALUE - this < other) Long.MAX_VALUE else this + other
}
