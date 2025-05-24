package com.example.pushrecorder.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.pushrecorder.data.AppDatabase
import com.example.pushrecorder.data.NotificationEntity
import com.example.pushrecorder.data.NotificationStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Date

class NotificationListenerService : NotificationListenerService() {
    companion object {
        private const val TAG = "NotificationListener"
    }

    private lateinit var database: AppDatabase
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getDatabase(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        val notification = sbn.notification
        val extras = notification.extras
        
        val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val text = extras.getString(Notification.EXTRA_TEXT) ?: ""
        
        Log.d(TAG, "Notification Posted - Package: $packageName, Title: $title, Text: $text")
        
        val notificationEntity = NotificationEntity(
            packageName = packageName,
            title = title,
            text = text,
            timestamp = Date(),
            status = NotificationStatus.POSTED
        )
        
        scope.launch {
            database.notificationDao().insert(notificationEntity)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        val notification = sbn.notification
        val extras = notification.extras
        
        val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val text = extras.getString(Notification.EXTRA_TEXT) ?: ""
        
        Log.d(TAG, "Notification Removed - Package: $packageName, Title: $title, Text: $text")
        
        val notificationEntity = NotificationEntity(
            packageName = packageName,
            title = title,
            text = text,
            timestamp = Date(),
            status = NotificationStatus.REMOVED
        )
        
        scope.launch {
            database.notificationDao().insert(notificationEntity)
        }
    }
} 