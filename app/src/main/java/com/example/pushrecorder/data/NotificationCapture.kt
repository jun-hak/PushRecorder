package com.example.pushrecorder.data

data class NotificationCapture(
    val notificationKey: String,
    val packageName: String,
    val title: String,
    val text: String,
    val sourcePostTime: Long,
    val observedAt: Long,
    val flags: Int,
    val hasActions: Boolean,
    val appLabel: String = packageName,
    val appInfoResolved: Boolean = false
) {
    fun toEntity(
        status: NotificationStatus,
        removalReason: RemovalReason = RemovalReason.UNKNOWN,
        timeToRemoval: Long = 0,
        removedAt: Long = 0,
        eventJournalId: Long? = null
    ): NotificationEntity {
        return NotificationEntity(
            notificationKey = notificationKey,
            packageName = packageName,
            title = NotificationStorageLimits.limitTitle(title),
            text = NotificationStorageLimits.limitText(text),
            timestamp = sourcePostTime,
            observedAt = observedAt,
            appLabel = appLabel,
            appInfoResolved = appInfoResolved,
            status = status,
            flags = flags,
            hasActions = hasActions,
            removalReason = removalReason,
            timeToRemoval = timeToRemoval,
            removedAt = removedAt,
            eventJournalId = eventJournalId
        )
    }
}
