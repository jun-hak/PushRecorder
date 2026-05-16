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
    notification: NotificationEntity
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
                text = notification.appLabel,
                style = MaterialTheme.typography.labelMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = notification.title,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = notification.text,
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "상태: ${formatStatus(notification.status)}",
                    style = MaterialTheme.typography.labelSmall
                )
                Text(
                    text = "기록: ${dateFormat.format(notification.observedAt)}",
                    style = MaterialTheme.typography.labelSmall
                )
            }

            Text(
                text = "앱 패키지: ${notification.packageName}",
                style = MaterialTheme.typography.labelSmall
            )
            Text(
                text = "앱 정보 스냅샷: ${formatBoolean(notification.appInfoResolved)}",
                style = MaterialTheme.typography.labelSmall
            )

            if (notification.status != NotificationStatus.POSTED) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "제거 이유: ${formatRemovalReason(notification.removalReason)}",
                    style = MaterialTheme.typography.labelSmall
                )
                Text(
                    text = "제거까지 걸린 시간: ${notification.timeToRemoval}ms",
                    style = MaterialTheme.typography.labelSmall
                )
                if (notification.removedAt > 0) {
                    Text(
                        text = "제거: ${dateFormat.format(notification.removedAt)}",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "플래그: ${formatFlags(notification.flags)}",
                style = MaterialTheme.typography.labelSmall
            )
            Text(
                text = "액션 존재: ${formatBoolean(notification.hasActions)}",
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

private fun formatFlags(flags: Int): String {
    val flagList = mutableListOf<String>()
    if (flags and Notification.FLAG_AUTO_CANCEL != 0) flagList.add("AUTO_CANCEL")
    if (flags and Notification.FLAG_FOREGROUND_SERVICE != 0) flagList.add("FOREGROUND_SERVICE")
    if (flags and Notification.FLAG_GROUP_SUMMARY != 0) flagList.add("GROUP_SUMMARY")
    if (flags and Notification.FLAG_INSISTENT != 0) flagList.add("INSISTENT")
    if (flags and Notification.FLAG_LOCAL_ONLY != 0) flagList.add("LOCAL_ONLY")
    if (flags and Notification.FLAG_NO_CLEAR != 0) flagList.add("NO_CLEAR")
    if (flags and Notification.FLAG_ONGOING_EVENT != 0) flagList.add("ONGOING_EVENT")
    if (flags and Notification.FLAG_ONLY_ALERT_ONCE != 0) flagList.add("ONLY_ALERT_ONCE")
    return flagList.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "없음"
}

private fun formatStatus(status: NotificationStatus): String {
    return when (status) {
        NotificationStatus.POSTED -> "수신됨"
        NotificationStatus.REMOVED -> "제거됨"
        NotificationStatus.CLICKED -> "클릭됨"
    }
}

private fun formatRemovalReason(reason: RemovalReason): String {
    return when (reason) {
        RemovalReason.UNKNOWN -> "알 수 없음"
        RemovalReason.USER_CLICKED -> "사용자 클릭"
        RemovalReason.USER_DISMISSED -> "사용자 직접 제거"
        RemovalReason.AUTO_REMOVED -> "앱/시스템 제거"
    }
}

private fun formatBoolean(value: Boolean): String {
    return if (value) "있음" else "없음"
}
