package com.example.pushrecorder.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import com.example.pushrecorder.appinfo.AppDisplayInfo
import com.example.pushrecorder.service.NotificationListenerStatus
import com.example.pushrecorder.ui.NotificationViewMode
import com.example.pushrecorder.ui.PushRecorderUiState
import com.example.pushrecorder.ui.PushRecorderViewModel
import com.example.pushrecorder.ui.appinfo.rememberAppDisplayInfo
import com.example.pushrecorder.ui.components.AppIdentity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PushRecorderRoute(
    onOpenNotificationSettings: () -> Unit,
    viewModel: PushRecorderViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listenerStatus by viewModel.listenerStatus.collectAsStateWithLifecycle()
    val selectedAppInfo by rememberAppDisplayInfo(
        packageName = uiState.selectedPackageName,
        resolveAppInfo = viewModel::resolveAppInfo
    )

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshListenerStatus()
    }

    BackHandler(enabled = uiState.selectedPackageName != null) {
        viewModel.clearSelectedPackage()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        PushRecorderScreen(
            uiState = uiState,
            listenerStatus = listenerStatus,
            selectedAppInfo = selectedAppInfo,
            onOpenNotificationSettings = {
                viewModel.refreshListenerStatus()
                onOpenNotificationSettings()
            },
            onSearchQueryChange = viewModel::setSearchQuery,
            onViewModeChange = viewModel::setViewMode,
            onClearSelectedPackage = viewModel::clearSelectedPackage,
            content = {
                when {
                    uiState.selectedPackageName != null -> {
                        val notifications = viewModel.packageNotifications.collectAsLazyPagingItems()
                        PackageNotificationsView(notifications)
                    }

                    uiState.viewMode == NotificationViewMode.ALL -> {
                        val notifications = viewModel.allNotifications.collectAsLazyPagingItems()
                        AllNotificationsView(notifications)
                    }

                    uiState.viewMode == NotificationViewMode.GROUPED -> {
                        val groups = viewModel.groupedNotifications.collectAsLazyPagingItems()
                        GroupedNotificationsView(
                            groups = groups,
                            resolveAppInfo = viewModel::resolveAppInfo,
                            onGroupClick = { group ->
                                viewModel.selectPackage(group.latestNotification.packageName)
                            }
                        )
                    }
                }
            }
        )
    }
}

@Composable
private fun PushRecorderScreen(
    uiState: PushRecorderUiState,
    listenerStatus: NotificationListenerStatus,
    selectedAppInfo: AppDisplayInfo?,
    onOpenNotificationSettings: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onViewModeChange: (NotificationViewMode) -> Unit,
    onClearSelectedPackage: () -> Unit,
    content: @Composable () -> Unit
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Transparent
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            PushRecorderHeader(
                uiState = uiState,
                listenerStatus = listenerStatus,
                selectedAppInfo = selectedAppInfo,
                onOpenNotificationSettings = onOpenNotificationSettings,
                onSearchQueryChange = onSearchQueryChange,
                onViewModeChange = onViewModeChange,
                onClearSelectedPackage = onClearSelectedPackage
            )

            content()
        }
    }
}

@Composable
private fun PushRecorderHeader(
    uiState: PushRecorderUiState,
    listenerStatus: NotificationListenerStatus,
    selectedAppInfo: AppDisplayInfo?,
    onOpenNotificationSettings: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onViewModeChange: (NotificationViewMode) -> Unit,
    onClearSelectedPackage: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        ListenerStatusCard(
            listenerStatus = listenerStatus,
            onOpenNotificationSettings = onOpenNotificationSettings
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = uiState.searchQuery,
            onValueChange = onSearchQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("푸시 검색") },
            placeholder = { Text("앱, 제목, 내용") }
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (uiState.selectedPackageName == null) {
            ViewModeButtons(
                viewMode = uiState.viewMode,
                onViewModeChange = onViewModeChange
            )
        } else {
            SelectedPackageHeader(
                packageName = uiState.selectedPackageName,
                selectedAppInfo = selectedAppInfo,
                onClearSelectedPackage = onClearSelectedPackage
            )
        }
    }
}

@Composable
private fun ListenerStatusCard(
    listenerStatus: NotificationListenerStatus,
    onOpenNotificationSettings: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }
    val uiState = listenerStatus.toListenerStatusUiState()
    val colorScheme = MaterialTheme.colorScheme
    val containerColor = when (uiState.colorRole) {
        ListenerStatusColorRole.CAPTURING -> colorScheme.secondaryContainer
        ListenerStatusColorRole.DISABLED -> colorScheme.errorContainer
        ListenerStatusColorRole.ENABLED_WAITING -> colorScheme.tertiaryContainer
    }
    val contentColor = when (uiState.colorRole) {
        ListenerStatusColorRole.CAPTURING -> colorScheme.onSecondaryContainer
        ListenerStatusColorRole.DISABLED -> colorScheme.onErrorContainer
        ListenerStatusColorRole.ENABLED_WAITING -> colorScheme.onTertiaryContainer
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = uiState.title,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = uiState.connectionSummary,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "마지막 캡처: ${formatTime(listenerStatus.lastCapturedAt, dateFormat)}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "마지막 연결: ${formatTime(listenerStatus.lastConnectedAt, dateFormat)}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "앱 목록 동기화: ${formatTime(listenerStatus.lastInstalledAppSyncAt, dateFormat)}" +
                    " (${listenerStatus.lastInstalledAppSyncCount}개)",
                style = MaterialTheme.typography.bodySmall
            )
            if (listenerStatus.pendingEventCount > 0) {
                Text(
                    text = "처리 대기 이벤트: ${listenerStatus.pendingEventCount}개",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (listenerStatus.lastErrorMessage != null) {
                Text(
                    text = "최근 오류: ${listenerStatus.lastErrorMessage} " +
                        "(${formatTime(listenerStatus.lastErrorAt, dateFormat)})",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Button(
                onClick = onOpenNotificationSettings,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(uiState.settingsButtonText)
            }
        }
    }
}

internal data class ListenerStatusUiState(
    val title: String,
    val connectionSummary: String,
    val settingsButtonText: String,
    val colorRole: ListenerStatusColorRole
)

internal enum class ListenerStatusColorRole {
    CAPTURING,
    ENABLED_WAITING,
    DISABLED
}

internal fun NotificationListenerStatus.toListenerStatusUiState(): ListenerStatusUiState {
    val colorRole = when {
        isCapturing -> ListenerStatusColorRole.CAPTURING
        isListenerEnabled -> ListenerStatusColorRole.ENABLED_WAITING
        else -> ListenerStatusColorRole.DISABLED
    }
    return ListenerStatusUiState(
        title = when (colorRole) {
            ListenerStatusColorRole.CAPTURING -> "캡처 동작 중"
            ListenerStatusColorRole.ENABLED_WAITING -> "권한은 켜졌지만 리스너 연결 대기 중"
            ListenerStatusColorRole.DISABLED -> "리스너 꺼짐 - 푸시를 캡처하지 못할 수 있음"
        },
        connectionSummary = "권한: ${formatEnabled(isListenerEnabled)} / " +
            "서비스 연결: ${formatEnabled(isServiceConnected)}",
        settingsButtonText = if (isCapturing) {
            "알림 접근 설정 열기"
        } else {
            "알림 접근 권한 켜기"
        },
        colorRole = colorRole
    )
}

@Composable
private fun ViewModeButtons(
    viewMode: NotificationViewMode,
    onViewModeChange: (NotificationViewMode) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ViewModeButton(
            text = "전체 보기",
            selected = viewMode == NotificationViewMode.ALL,
            onClick = { onViewModeChange(NotificationViewMode.ALL) },
            modifier = Modifier.weight(1f)
        )
        ViewModeButton(
            text = "앱별 보기",
            selected = viewMode == NotificationViewMode.GROUPED,
            onClick = { onViewModeChange(NotificationViewMode.GROUPED) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ViewModeButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.secondary
            }
        )
    ) {
        Text(text)
    }
}

@Composable
private fun SelectedPackageHeader(
    packageName: String,
    selectedAppInfo: AppDisplayInfo?,
    onClearSelectedPackage: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        AppIdentity(
            appInfo = selectedAppInfo ?: AppDisplayInfo.fallback(packageName),
            modifier = Modifier.weight(1f),
            supportingText = "앱별 푸시 상세",
            iconSize = 44.dp
        )

        TextButton(onClick = onClearSelectedPackage) {
            Text("뒤로")
        }
    }
}

private fun formatEnabled(value: Boolean): String {
    return if (value) "켜짐" else "꺼짐"
}

private fun formatTime(
    timestamp: Long,
    dateFormat: SimpleDateFormat
): String {
    return if (timestamp > 0L) {
        dateFormat.format(Date(timestamp))
    } else {
        "아직 없음"
    }
}
