package com.example.pushrecorder.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.pushrecorder.data.NotificationEntity
import com.example.pushrecorder.ui.components.GroupedNotificationCard
import com.example.pushrecorder.ui.components.NotificationItem

@Composable
fun AllNotificationsView(notifications: List<NotificationEntity>) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(16.dp)
    ) {
        items(notifications) { notification ->
            NotificationItem(notification)
        }
    }
}

@Composable
fun GroupedNotificationsView(notifications: List<NotificationEntity>) {
    val groupedNotifications = remember(notifications) {
        notifications
            .sortedByDescending { it.timestamp }
            .groupBy { it.packageName }
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(16.dp)
    ) {
        groupedNotifications.forEach { (packageName, notifications) ->
            item {
                GroupedNotificationCard(packageName, notifications)
            }
        }
    }
} 