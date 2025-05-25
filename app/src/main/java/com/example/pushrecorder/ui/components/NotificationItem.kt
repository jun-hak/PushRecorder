package com.example.pushrecorder.ui.components

import android.app.Notification
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.pushrecorder.data.NotificationEntity
import com.example.pushrecorder.data.NotificationStatus
import com.example.pushrecorder.data.RemovalReason
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun NotificationItem(
    notification: NotificationEntity,
    showDetails: Boolean = true
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    val colorScheme = MaterialTheme.colorScheme
    
    val containerColor = remember(notification.status, notification.removalReason, colorScheme) {
        when (notification.status) {
            NotificationStatus.POSTED -> colorScheme.primaryContainer.copy(alpha = 0.7f)
            NotificationStatus.CLICKED -> colorScheme.tertiaryContainer.copy(alpha = 0.7f)
            NotificationStatus.REMOVED -> when (notification.removalReason) {
                RemovalReason.USER_DISMISSED -> colorScheme.errorContainer.copy(alpha = 0.7f)
                RemovalReason.AUTO_REMOVED -> colorScheme.secondaryContainer.copy(alpha = 0.7f)
                else -> colorScheme.surfaceVariant.copy(alpha = 0.7f)
            }
        }
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 4.dp
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = notification.title,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = notification.text,
                style = MaterialTheme.typography.bodyMedium
            )
            
            if (showDetails) {
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "상태: ${notification.status}",
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text(
                        text = "시간: ${dateFormat.format(notification.timestamp)}",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                
                if (notification.status != NotificationStatus.POSTED) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "제거 이유: ${notification.removalReason}",
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text(
                        text = "제거까지 걸린 시간: ${notification.timeToRemoval}ms",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "플래그: ${formatFlags(notification.flags)}",
                    style = MaterialTheme.typography.labelSmall
                )
                Text(
                    text = "액션 존재: ${notification.hasActions}",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

private fun formatFlags(flags: Int): String {
    val flagList = mutableListOf<String>()
    if (flags and Notification.FLAG_AUTO_CANCEL != 0) flagList.add("AUTO_CANCEL")
    if (flags and Notification.FLAG_FOREGROUND_SERVICE != 0) flagList.add("FOREGROUND_SERVICE")
    if (flags and Notification.FLAG_GROUP_SUMMARY != 0) flagList.add("GROUP_SUMMARY")
    if (flags and Notification.FLAG_HIGH_PRIORITY != 0) flagList.add("HIGH_PRIORITY")
    if (flags and Notification.FLAG_INSISTENT != 0) flagList.add("INSISTENT")
    if (flags and Notification.FLAG_LOCAL_ONLY != 0) flagList.add("LOCAL_ONLY")
    if (flags and Notification.FLAG_NO_CLEAR != 0) flagList.add("NO_CLEAR")
    if (flags and Notification.FLAG_ONGOING_EVENT != 0) flagList.add("ONGOING_EVENT")
    if (flags and Notification.FLAG_ONLY_ALERT_ONCE != 0) flagList.add("ONLY_ALERT_ONCE")
    return flagList.joinToString(", ")
} 