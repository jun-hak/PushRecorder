package com.example.pushrecorder.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.pushrecorder.data.NotificationEntity
import com.example.pushrecorder.data.NotificationStatus
import com.example.pushrecorder.data.RemovalReason
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun NotificationItem(
    notification: NotificationEntity,
    onClick: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    val colorScheme = MaterialTheme.colorScheme
    val appLabel = notification.appLabel.ifBlank { notification.packageName }

    val containerColor = remember(notification.status, notification.removalReason, colorScheme) {
        when (notification.status) {
            NotificationStatus.POSTED -> colorScheme.surface
            NotificationStatus.CLICKED -> colorScheme.tertiaryContainer.copy(alpha = 0.42f)
            NotificationStatus.REMOVED -> colorScheme.surfaceVariant.copy(alpha = 0.72f)
        }
    }

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 1.dp
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = appLabel,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Text(
                    text = dateFormat.format(notification.observedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = notification.title.ifBlank { "(제목 없음)" },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (notification.text.isNotBlank()) {
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = notification.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusBadge(notification.status, notification.removalReason)
            }

            if (shouldShowLifecycleMetadata(notification.status)) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = formatLifecycleSummary(notification),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (notification.removedAt > 0) {
                    Text(
                        text = "${formatLifecycleTimestampLabel(notification.status)}: " +
                            dateFormat.format(notification.removedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(
    status: NotificationStatus,
    removalReason: RemovalReason
) {
    val colorScheme = MaterialTheme.colorScheme
    val containerColor = when (status) {
        NotificationStatus.POSTED -> colorScheme.primaryContainer
        NotificationStatus.CLICKED -> colorScheme.tertiaryContainer
        NotificationStatus.REMOVED -> when (removalReason) {
            RemovalReason.USER_DISMISSED -> colorScheme.errorContainer
            RemovalReason.AUTO_REMOVED -> colorScheme.secondaryContainer
            else -> colorScheme.surfaceVariant
        }
    }
    val contentColor = when (status) {
        NotificationStatus.POSTED -> colorScheme.onPrimaryContainer
        NotificationStatus.CLICKED -> colorScheme.onTertiaryContainer
        NotificationStatus.REMOVED -> when (removalReason) {
            RemovalReason.USER_DISMISSED -> colorScheme.onErrorContainer
            RemovalReason.AUTO_REMOVED -> colorScheme.onSecondaryContainer
            else -> colorScheme.onSurfaceVariant
        }
    }

    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = formatStatus(status),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1
        )
    }
}

private fun formatStatus(status: NotificationStatus): String {
    return when (status) {
        NotificationStatus.POSTED -> "수신됨"
        NotificationStatus.REMOVED -> "제거됨"
        NotificationStatus.CLICKED -> "클릭됨"
    }
}

private fun shouldShowLifecycleMetadata(status: NotificationStatus): Boolean {
    return status != NotificationStatus.POSTED
}

private fun formatLifecycleSummary(notification: NotificationEntity): String {
    val prefix = when (notification.status) {
        NotificationStatus.POSTED -> "활성"
        NotificationStatus.REMOVED -> "제거"
        NotificationStatus.CLICKED -> "클릭"
    }
    val reason = formatRemovalReason(notification.removalReason)
    val elapsed = if (notification.timeToRemoval > 0L) {
        " · ${notification.timeToRemoval}ms"
    } else {
        ""
    }

    return "$prefix: $reason$elapsed"
}

private fun formatLifecycleTimestampLabel(status: NotificationStatus): String {
    return when (status) {
        NotificationStatus.POSTED -> "수신"
        NotificationStatus.REMOVED -> "제거"
        NotificationStatus.CLICKED -> "클릭"
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
