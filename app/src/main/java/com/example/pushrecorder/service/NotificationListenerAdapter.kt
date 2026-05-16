package com.example.pushrecorder.service

import com.example.pushrecorder.data.NotificationCapture

internal class NotificationListenerAdapter(
    private val enqueueEvent: (NotificationProcessingCommand) -> Unit
) {
    fun onNotificationPosted(capture: NotificationCapture) {
        enqueueEvent(NotificationProcessingCommand.Posted(capture))
    }

    fun onNotificationRemoved(
        capture: NotificationCapture,
        systemReason: Int?
    ) {
        enqueueEvent(
            NotificationProcessingCommand.Removed(
                NotificationRemovalCommand(
                    capture = capture,
                    systemReason = systemReason
                )
            )
        )
    }
}

internal interface NotificationEventProcessing {
    suspend fun process(command: NotificationProcessingCommand): NotificationProcessingResult
}

internal interface NotificationListenerEventStatusRecorder {
    fun markPosted(at: Long)

    fun markRemoved(at: Long)

    fun markEventQueued()

    fun markEventProcessed()

    fun markEventFailed(
        message: String,
        at: Long
    )

    fun markError(
        message: String,
        at: Long
    )

    fun markError(message: String)
}
