package com.example.pushrecorder.service

import com.example.pushrecorder.data.NotificationCapture

data class NotificationRemovalCommand(
    val capture: NotificationCapture,
    val systemReason: Int? = null
) {
    val notificationKey: String = capture.notificationKey
    val packageName: String = capture.packageName
    val title: String = capture.title
    val text: String = capture.text
    val sourcePostTime: Long = capture.sourcePostTime
    val flags: Int = capture.flags
    val hasActions: Boolean = capture.hasActions
}
