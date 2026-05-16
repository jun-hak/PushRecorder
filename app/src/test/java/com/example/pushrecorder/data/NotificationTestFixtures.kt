package com.example.pushrecorder.data

fun notificationCapture(
    notificationKey: String = "key-1",
    packageName: String = "com.example.source",
    title: String = "title",
    text: String = "text",
    sourcePostTime: Long = 1_000L,
    observedAt: Long = 2_000L,
    flags: Int = 0,
    hasActions: Boolean = false,
    appLabel: String = "Source App",
    appInfoResolved: Boolean = true
): NotificationCapture {
    return NotificationCapture(
        notificationKey = notificationKey,
        packageName = packageName,
        title = title,
        text = text,
        sourcePostTime = sourcePostTime,
        observedAt = observedAt,
        flags = flags,
        hasActions = hasActions,
        appLabel = appLabel,
        appInfoResolved = appInfoResolved
    )
}
