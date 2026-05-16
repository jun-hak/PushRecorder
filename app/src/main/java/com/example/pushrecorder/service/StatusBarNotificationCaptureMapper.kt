package com.example.pushrecorder.service

import android.service.notification.StatusBarNotification
import com.example.pushrecorder.data.NotificationCapture

internal fun StatusBarNotification.toNotificationCapture(): NotificationCapture {
    val postedNotification = notification
    return NotificationCapture(
        notificationKey = key,
        packageName = packageName,
        title = NotificationContentExtractor.title(postedNotification),
        text = NotificationContentExtractor.text(postedNotification),
        sourcePostTime = NotificationContentExtractor.timestamp(postTime),
        observedAt = System.currentTimeMillis(),
        flags = postedNotification.flags,
        hasActions = postedNotification.actions?.isNotEmpty() == true
    )
}
