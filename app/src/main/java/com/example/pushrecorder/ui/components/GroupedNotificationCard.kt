package com.example.pushrecorder.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.pushrecorder.appinfo.AppDisplayInfo
import com.example.pushrecorder.data.NotificationGroupSummary

@Composable
fun GroupedNotificationCard(
    group: NotificationGroupSummary,
    appInfo: AppDisplayInfo,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 4.dp
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppIdentity(
                    appInfo = appInfo,
                    modifier = Modifier.weight(1f),
                    supportingText = "저장된 푸시 ${group.notificationCount}개",
                    trailing = {
                        Text(
                            text = "보기",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            NotificationItem(notification = group.latestNotification)
        }
    }
}
