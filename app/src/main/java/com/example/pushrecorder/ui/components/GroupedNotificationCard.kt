package com.example.pushrecorder.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.pushrecorder.appinfo.AppDisplayInfo
import com.example.pushrecorder.data.NotificationGroupSummary
import com.example.pushrecorder.data.NotificationStatus
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun GroupedNotificationCard(
    group: NotificationGroupSummary,
    appInfo: AppDisplayInfo,
    onClick: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    val colorScheme = MaterialTheme.colorScheme
    val latestNotification = group.latestNotification
    val latestTitle = latestNotification.title.ifBlank {
        latestNotification.text.ifBlank { "내용 없음" }
    }
    val latestBody = latestNotification.text
        .takeIf { it.isNotBlank() && it != latestTitle }
    val contentColor = colorScheme.onSurfaceVariant

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = colorScheme.surface,
            contentColor = contentColor
        ),
        border = BorderStroke(
            width = 1.dp,
            color = colorScheme.outlineVariant
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 2.dp
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AppIdentity(
                appInfo = appInfo,
                modifier = Modifier.fillMaxWidth(),
                supportingText = latestTitle,
                iconSize = 36.dp,
                showPackageName = false,
                trailing = {
                    NotificationCountBadge(count = group.notificationCount)
                }
            )

            latestBody?.let { body ->
                Text(
                    text = body,
                    modifier = Modifier.padding(start = 48.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Text(
                text = buildString {
                    append(formatGroupStatus(latestNotification.status))
                    append(" · ")
                    append(dateFormat.format(latestNotification.observedAt))
                },
                modifier = Modifier.padding(start = 48.dp),
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun NotificationCountBadge(count: Int) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = "${count}개",
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1
        )
    }
}

private fun formatGroupStatus(status: NotificationStatus): String {
    return when (status) {
        NotificationStatus.POSTED -> "수신됨"
        NotificationStatus.REMOVED -> "제거됨"
        NotificationStatus.CLICKED -> "클릭됨"
    }
}
