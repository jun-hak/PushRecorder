package com.example.pushrecorder.capture

import com.example.pushrecorder.data.NotificationCapture
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ActiveNotificationSnapshotStore @Inject constructor() {
    private val snapshots = mutableMapOf<String, NotificationCapture>()

    fun get(notificationKey: String): NotificationCapture? {
        return snapshots[notificationKey]
    }

    fun put(capture: NotificationCapture) {
        snapshots[capture.notificationKey] = capture
    }

    fun remove(notificationKey: String) {
        snapshots.remove(notificationKey)
    }

    fun retain(notificationKeys: Set<String>) {
        snapshots.keys.retainAll(notificationKeys)
    }

    fun retainForPackage(
        packageName: String,
        notificationKeys: Set<String>
    ) {
        snapshots.entries.removeAll { (_, capture) ->
            capture.packageName == packageName && capture.notificationKey !in notificationKeys
        }
    }

    fun clear() {
        snapshots.clear()
    }
}
