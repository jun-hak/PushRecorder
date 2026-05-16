package com.example.pushrecorder.service

import kotlinx.coroutines.channels.BufferOverflow

internal object NotificationIngestionPolicy {
    // Producer-side boundary before Room journal persistence. This queue is also
    // bounded and written with trySend so Android callbacks never wait for Room.
    const val JOURNAL_PERSISTENCE_QUEUE_CAPACITY = 2_048
    val JOURNAL_PERSISTENCE_QUEUE_BUFFER_OVERFLOW = BufferOverflow.SUSPEND

    // Producer-side boundary for NotificationListenerService bursts.
    //
    // The processing queue is intentionally bounded and is written with trySend. With
    // BufferOverflow.SUSPEND, trySend never suspends the Android callback thread; it rejects
    // the newest in-memory enqueue attempt when the queue is full. POSTED/REMOVED commands are
    // durably journaled before enqueue, so rejected events remain pending for replay instead of
    // being silently dropped. Reconcile commands are best-effort runtime work and are not
    // journaled.
    const val PROCESSING_QUEUE_CAPACITY = 2_048
    val PROCESSING_QUEUE_BUFFER_OVERFLOW = BufferOverflow.SUSPEND

    val PROCESSING_QUEUE_OVERFLOW_POLICY = NotificationQueueOverflowPolicy.RejectNewestKeepDurableJournal
}

internal enum class NotificationQueueOverflowPolicy {
    RejectNewestKeepDurableJournal
}
