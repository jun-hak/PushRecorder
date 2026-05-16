package com.example.pushrecorder.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.example.pushrecorder.appinfo.AppDisplayInfo

@Composable
fun AppIdentity(
    appInfo: AppDisplayInfo,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    iconSize: Dp = 40.dp,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AppIcon(
            appInfo = appInfo,
            iconSize = iconSize,
            modifier = Modifier.size(iconSize)
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = appInfo.label,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (appInfo.isResolved) {
                Text(
                    text = appInfo.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            supportingText?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        trailing?.invoke()
    }
}

@Composable
private fun AppIcon(
    appInfo: AppDisplayInfo,
    iconSize: Dp,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val iconSizePx = with(density) { iconSize.roundToPx() }
    val imageBitmap = remember(appInfo.packageName, appInfo.icon, iconSizePx) {
        appInfo.icon
            ?.toBitmap(width = iconSizePx, height = iconSizePx)
            ?.asImageBitmap()
    }

    if (imageBitmap != null) {
        Image(
            bitmap = imageBitmap,
            contentDescription = "${appInfo.label} 아이콘",
            modifier = modifier.clip(CircleShape)
        )
    } else {
        Box(
            modifier = modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = appInfo.label.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
