package com.example.pushrecorder.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.pushrecorder.data.NotificationDao
import com.example.pushrecorder.data.NotificationEntity
import com.example.pushrecorder.data.NotificationStatus
import com.example.pushrecorder.data.RemovalReason
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

@AndroidEntryPoint
class NotificationListenerService : NotificationListenerService() {
    companion object {
        private const val TAG = "NotificationListener"
        private const val AUTO_REMOVAL_THRESHOLD = 5000L // 5초 이내 제거는 자동 제거로 간주
        private const val CLICK_THRESHOLD = 2000L // 2초 이내 제거는 클릭으로 간주
    }

    @Inject
    lateinit var notificationDao: NotificationDao

    private val scope = CoroutineScope(Dispatchers.IO)
    private val activeNotifications = ConcurrentHashMap<String, NotificationEntity>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        val notification = sbn.notification
        val extras = notification.extras
        
        val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val text = extras.getString(Notification.EXTRA_TEXT) ?: ""
        
        Log.d(TAG, "Notification Posted - Package: $packageName, Title: $title, Text: $text")
        
        val currentTime = System.currentTimeMillis()
        val notificationEntity = NotificationEntity(
            packageName = packageName,
            title = title,
            text = text,
            timestamp = currentTime,
            status = NotificationStatus.POSTED,
            flags = notification.flags,
            hasActions = notification.actions?.isNotEmpty() == true
        )
        
        // 활성 노티피케이션 목록에 추가
        activeNotifications[sbn.key] = notificationEntity
        
        scope.launch {
            notificationDao.insert(notificationEntity)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        val notification = sbn.notification
        val extras = notification.extras
        
        val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val text = extras.getString(Notification.EXTRA_TEXT) ?: ""
        
        Log.d(TAG, "Notification Removed - Package: $packageName, Title: $title, Text: $text")
        
        // 원본 노티피케이션 정보 가져오기
        val originalNotification = activeNotifications[sbn.key]
        val currentTime = System.currentTimeMillis()
        val timeToRemoval = originalNotification?.let {
            currentTime - it.timestamp
        } ?: 0L

        // 제거 이유 분석
        val removalReason = analyzeRemovalReason(
            notification = notification,
            timeToRemoval = timeToRemoval,
            hasActions = originalNotification?.hasActions ?: false
        )

        // 상태 결정
        val status = when (removalReason) {
            RemovalReason.USER_CLICKED -> NotificationStatus.CLICKED
            else -> NotificationStatus.REMOVED
        }
        
        val notificationEntity = NotificationEntity(
            packageName = packageName,
            title = title,
            text = text,
            timestamp = currentTime,
            status = status,
            flags = notification.flags,
            hasActions = notification.actions?.isNotEmpty() == true,
            removalReason = removalReason,
            timeToRemoval = timeToRemoval
        )
        
        // 활성 노티피케이션 목록에서 제거
        activeNotifications.remove(sbn.key)
        
        scope.launch {
            notificationDao.insert(notificationEntity)
        }
    }

    private fun analyzeRemovalReason(
        notification: Notification,
        timeToRemoval: Long,
        hasActions: Boolean
    ): RemovalReason {
        // 1. 자동 제거 플래그 확인
        if (notification.flags and Notification.FLAG_AUTO_CANCEL != 0) {
            return RemovalReason.AUTO_REMOVED
        }

        // 2. 시간 기반 분석
        return when {
            // 매우 빠른 제거는 클릭으로 간주
            timeToRemoval <= CLICK_THRESHOLD -> RemovalReason.USER_CLICKED
            // 짧은 시간 내 제거는 자동 제거로 간주
            timeToRemoval <= AUTO_REMOVAL_THRESHOLD -> RemovalReason.AUTO_REMOVED
            // 액션이 있는 노티피케이션의 경우 사용자 상호작용으로 간주
            hasActions -> RemovalReason.USER_CLICKED
            // 그 외의 경우 사용자가 직접 제거한 것으로 간주
            else -> RemovalReason.USER_DISMISSED
        }
    }
} 