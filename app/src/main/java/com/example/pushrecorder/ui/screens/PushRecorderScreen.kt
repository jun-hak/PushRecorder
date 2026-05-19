package com.example.pushrecorder.ui.screens

import android.app.Notification
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import com.example.pushrecorder.appinfo.AppDisplayInfo
import com.example.pushrecorder.data.NotificationEntity
import com.example.pushrecorder.data.NotificationStatus
import com.example.pushrecorder.data.RemovalReason
import com.example.pushrecorder.service.NotificationListenerStatus
import com.example.pushrecorder.ui.NotificationAppInfoSnapshot
import com.example.pushrecorder.ui.NotificationViewMode
import com.example.pushrecorder.ui.PushRecorderUiState
import com.example.pushrecorder.ui.PushRecorderViewModel
import com.example.pushrecorder.ui.appinfo.rememberAppDisplayInfo
import com.example.pushrecorder.ui.components.AppIdentity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun PushRecorderRoute(
    onOpenNotificationSettings: () -> Unit,
    viewModel: PushRecorderViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listenerStatus by viewModel.listenerStatus.collectAsStateWithLifecycle()
    val selectedNotificationDetail by viewModel.selectedNotificationDetail.collectAsStateWithLifecycle(null)
    val allListState = rememberLazyListState()
    val groupedListState = rememberLazyListState()
    val packageListState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val selectedAppInfo by rememberAppDisplayInfo(
        packageName = uiState.selectedPackageName,
        resolveAppInfo = viewModel::resolveAppInfo
    )

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshListenerStatus()
    }

    BackHandler(enabled = uiState.selectedNotification != null) {
        viewModel.clearSelectedNotification()
    }

    BackHandler(enabled = uiState.selectedNotification == null && uiState.selectedPackageName != null) {
        viewModel.clearSelectedPackage()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
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
                onCollapseStatusCard = { viewModel.setStatusCardCollapsed(true) },
                onRestoreStatusCard = {
                    viewModel.setStatusCardCollapsed(false)
                    coroutineScope.launch {
                        when {
                            uiState.selectedPackageName != null -> packageListState
                            uiState.viewMode == NotificationViewMode.GROUPED -> groupedListState
                            else -> allListState
                        }.animateScrollToItem(0)
                    }
                },
                content = {
                    val bottomContentPadding = if (uiState.isStatusCardCollapsed) {
                        88.dp
                    } else {
                        16.dp
                    }

                    when {
                        uiState.selectedPackageName != null -> {
                            val notifications = viewModel.packageNotifications.collectAsLazyPagingItems()
                            PackageNotificationsView(
                                notifications = notifications,
                                onNotificationClick = viewModel::selectNotification,
                                listState = packageListState,
                                bottomContentPadding = bottomContentPadding
                            )
                        }

                        uiState.viewMode == NotificationViewMode.ALL -> {
                            val notifications = viewModel.allNotifications.collectAsLazyPagingItems()
                            AllNotificationsView(
                                notifications = notifications,
                                onNotificationClick = viewModel::selectNotification,
                                listState = allListState,
                                bottomContentPadding = bottomContentPadding
                            )
                        }

                        uiState.viewMode == NotificationViewMode.GROUPED -> {
                            val groups = viewModel.groupedNotifications.collectAsLazyPagingItems()
                            GroupedNotificationsView(
                                groups = groups,
                                resolveAppInfo = viewModel::resolveAppInfo,
                                listState = groupedListState,
                                bottomContentPadding = bottomContentPadding,
                                onGroupClick = { group ->
                                    viewModel.selectPackage(group.latestNotification.packageName)
                                }
                            )
                        }
                    }
                }
            )

            selectedNotificationDetail?.let { notification ->
                NotificationDetailBottomSheet(
                    notification = notification,
                    appInfoSnapshot = uiState.selectedNotificationAppInfoSnapshot
                        ?: NotificationAppInfoSnapshot.fromNotification(notification),
                    onDismiss = viewModel::clearSelectedNotification
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotificationDetailBottomSheet(
    notification: NotificationEntity,
    appInfoSnapshot: NotificationAppInfoSnapshot,
    onDismiss: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = appInfoSnapshot.stableLabel(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = notification.title.ifBlank { "(제목 없음)" },
                    style = MaterialTheme.typography.titleLarge
                )
                if (notification.text.isNotBlank()) {
                    Text(
                        text = notification.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            AppInfoMetadataRows(appInfoSnapshot = appInfoSnapshot)

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            PayloadMetadataRows(notification = notification)

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            TimestampMetadataRows(
                notification = notification,
                dateFormat = dateFormat
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            LifecycleMetadataRows(notification = notification)

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            DetailSectionTitle("진단 정보")
            DiagnosticMetadataRows(
                notification = notification,
                appInfoSnapshot = appInfoSnapshot,
                dateFormat = dateFormat
            )
        }
    }
}

@Composable
private fun AppInfoMetadataRows(
    appInfoSnapshot: NotificationAppInfoSnapshot
) {
    DetailSectionTitle("앱 정보")
    DetailRow("앱", appInfoSnapshot.stableLabel())
    DetailRow("패키지 이름", appInfoSnapshot.packageName)
    DetailRow("앱 정보 해석", formatDetailResolved(appInfoSnapshot.isResolved))
}

@Composable
private fun PayloadMetadataRows(
    notification: NotificationEntity
) {
    DetailSectionTitle("페이로드")
    DetailRow("제목", notification.title.ifBlank { "(제목 없음)" })
    DetailRow("본문", notification.text.ifBlank { "없음" })
    DetailRow("액션 포함", formatDetailBoolean(notification.hasActions))
    if (notification.isGroupSummary()) {
        DetailRow("그룹 요약", formatDetailBoolean(true))
    }
}

@Composable
private fun TimestampMetadataRows(
    notification: NotificationEntity,
    dateFormat: SimpleDateFormat
) {
    DetailRow("게시 시각", formatDetailOptionalTime(notification.timestamp, dateFormat))
    DetailRow("이벤트 기록 시각", dateFormat.format(notification.observedAt))
    if (notification.removedAt > 0L) {
        DetailRow("제거 시각", dateFormat.format(notification.removedAt))
    } else {
        DetailRow("제거 시각", "없음")
    }
}

@Composable
private fun LifecycleMetadataRows(
    notification: NotificationEntity
) {
    DetailRow("알림 상태", formatDetailStatus(notification.status))

    if (notification.status != NotificationStatus.POSTED) {
        DetailRow("제거 출처", formatDetailRemovalSource(notification))
        DetailRow("제거 사유", formatDetailRemovalReason(notification.removalReason))
        if (notification.timeToRemoval > 0L) {
            DetailRow("제거 상세", formatDetailRemovalDetails(notification.timeToRemoval))
        }
    }
}

@Composable
private fun DiagnosticMetadataRows(
    notification: NotificationEntity,
    appInfoSnapshot: NotificationAppInfoSnapshot,
    dateFormat: SimpleDateFormat
) {
    DetailRow("리스너/소스 상태", formatDetailSourceState(notification))
    DetailRow("중복/저장 상태", formatDetailStorageState(notification))
    DetailRow("목록 기준/알림 유형", formatDetailListPlacement(notification, dateFormat))
    DetailRow("디버그 메타데이터", formatDetailDebugMetadata(notification))
    DetailRow("ID", notification.id.toString())
    DetailRow("알림 키", notification.notificationKey)
    DetailRow("앱", appInfoSnapshot.stableLabel())
    DetailRow("패키지 이름", appInfoSnapshot.packageName)
    DetailRow("앱 정보 해석", formatDetailResolved(appInfoSnapshot.isResolved))
    if (notification.flags != 0) {
        DetailRow("플래그", formatDetailFlags(notification.flags))
    }
    if (notification.hasActions) {
        DetailRow("액션", formatDetailBoolean(notification.hasActions))
    }
    if (notification.timestamp > 0L) {
        DetailRow("원본 시각", formatDetailOptionalTime(notification.timestamp, dateFormat))
    }
    notification.validEventJournalId()?.let { eventJournalId ->
        DetailRow("저널 ID", eventJournalId.toString())
    }
}

@Composable
private fun DetailSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun DetailRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(0.34f),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            modifier = Modifier.weight(0.66f),
            style = MaterialTheme.typography.bodyMedium
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
    onCollapseStatusCard: () -> Unit,
    onRestoreStatusCard: () -> Unit,
    content: @Composable () -> Unit
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        floatingActionButton = {
            if (uiState.isStatusCardCollapsed && uiState.selectedNotification == null) {
                ListenerStatusFloatingButton(
                    listenerStatus = listenerStatus,
                    onClick = onRestoreStatusCard
                )
            }
        }
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
                onClearSelectedPackage = onClearSelectedPackage,
                onCollapseStatusCard = onCollapseStatusCard
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
    onClearSelectedPackage: () -> Unit,
    onCollapseStatusCard: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        if (!uiState.isStatusCardCollapsed) {
            ListenerStatusCard(
                listenerStatus = listenerStatus,
                onOpenNotificationSettings = onOpenNotificationSettings,
                onCollapseStatusCard = onCollapseStatusCard
            )

            Spacer(modifier = Modifier.height(4.dp))
        }

        CompactSearchControl(
            searchQuery = uiState.searchQuery,
            onSearchQueryChange = onSearchQueryChange
        )

        Spacer(modifier = Modifier.height(4.dp))

        if (uiState.selectedPackageName == null) {
            ViewModeSegmentedControl(
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
private fun CompactSearchControl(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .height(48.dp),
        shape = RoundedCornerShape(12.dp),
        color = colorScheme.surface,
        contentColor = colorScheme.onSurface,
        border = BorderStroke(1.dp, colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .semantics { contentDescription = "푸시 검색" },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = colorScheme.onSurface
                ),
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (searchQuery.isBlank()) {
                            Text(
                                text = "앱, 제목, 내용 검색",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        innerTextField()
                    }
                }
            )

            if (searchQuery.isNotBlank()) {
                Text(
                    text = "지우기",
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .clickable { onSearchQueryChange("") }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = colorScheme.primary,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun ListenerStatusCard(
    listenerStatus: NotificationListenerStatus,
    onOpenNotificationSettings: () -> Unit,
    onCollapseStatusCard: () -> Unit
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
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = uiState.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "마지막 캡처: ${formatTime(listenerStatus.lastCapturedAt, dateFormat)}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = onCollapseStatusCard) {
                        Text("접기")
                    }
                    OutlinedButton(onClick = onOpenNotificationSettings) {
                        Text(uiState.settingsButtonText)
                    }
                }
            }

            if (uiState.showStatusDetails) {
                Text(
                    text = uiState.connectionSummary,
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
            }
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
        }
    }
}

@Composable
private fun ListenerStatusFloatingButton(
    listenerStatus: NotificationListenerStatus,
    onClick: () -> Unit
) {
    val uiState = listenerStatus.toListenerStatusUiState()
    val colorScheme = MaterialTheme.colorScheme
    val containerColor = when {
        uiState.hasError -> colorScheme.errorContainer
        uiState.colorRole == ListenerStatusColorRole.CAPTURING -> colorScheme.primaryContainer
        uiState.colorRole == ListenerStatusColorRole.DISABLED -> colorScheme.errorContainer
        else -> colorScheme.tertiaryContainer
    }
    val contentColor = when {
        uiState.hasError -> colorScheme.onErrorContainer
        uiState.colorRole == ListenerStatusColorRole.CAPTURING -> colorScheme.onPrimaryContainer
        uiState.colorRole == ListenerStatusColorRole.DISABLED -> colorScheme.onErrorContainer
        else -> colorScheme.onTertiaryContainer
    }
    FloatingActionButton(
        onClick = onClick,
        modifier = Modifier.semantics {
            contentDescription = uiState.floatingAccessibilityLabel
        },
        containerColor = containerColor,
        contentColor = contentColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .background(contentColor, CircleShape)
            )
            Text(
                text = uiState.floatingLabel,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

internal data class ListenerStatusUiState(
    val title: String,
    val connectionSummary: String,
    val settingsButtonText: String,
    val floatingLabel: String,
    val floatingAccessibilityLabel: String,
    val hasError: Boolean,
    val colorRole: ListenerStatusColorRole,
    val showStatusDetails: Boolean
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
        floatingLabel = when {
            lastErrorMessage != null -> "오류"
            colorRole == ListenerStatusColorRole.CAPTURING -> "기록 중"
            colorRole == ListenerStatusColorRole.ENABLED_WAITING -> "대기 중"
            else -> "꺼짐"
        },
        floatingAccessibilityLabel = when {
            lastErrorMessage != null -> "캡처 상태: 오류 있음. 탭하면 상단 상태 카드로 복귀"
            colorRole == ListenerStatusColorRole.CAPTURING -> "캡처 상태: 기록 중. 탭하면 상단 상태 카드로 복귀"
            colorRole == ListenerStatusColorRole.ENABLED_WAITING -> "캡처 상태: 연결 대기 중. 탭하면 상단 상태 카드로 복귀"
            else -> "캡처 상태: 꺼짐. 탭하면 상단 상태 카드로 복귀"
        },
        hasError = lastErrorMessage != null,
        colorRole = colorRole,
        showStatusDetails = colorRole != ListenerStatusColorRole.CAPTURING ||
            pendingEventCount > 0 ||
            lastErrorMessage != null
    )
}

@Composable
private fun ViewModeSegmentedControl(
    viewMode: NotificationViewMode,
    onViewModeChange: (NotificationViewMode) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .height(48.dp),
        shape = CircleShape,
        color = colorScheme.surface,
        contentColor = colorScheme.onSurface,
        border = BorderStroke(1.dp, colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(1.dp),
            horizontalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            ViewModeSegment(
                text = "전체",
                selected = viewMode == NotificationViewMode.ALL,
                onClick = { onViewModeChange(NotificationViewMode.ALL) },
                modifier = Modifier.weight(1f)
            )
            ViewModeSegment(
                text = "앱별",
                selected = viewMode == NotificationViewMode.GROUPED,
                onClick = { onViewModeChange(NotificationViewMode.GROUPED) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ViewModeSegment(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val containerColor = if (selected) {
        colorScheme.primaryContainer
    } else {
        Color.Transparent
    }
    val contentColor = if (selected) {
        colorScheme.onPrimaryContainer
    } else {
        colorScheme.onSurfaceVariant
    }

    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .fillMaxHeight()
            .background(containerColor, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
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

private fun formatDetailStatus(status: NotificationStatus): String {
    return when (status) {
        NotificationStatus.POSTED -> "수신됨"
        NotificationStatus.REMOVED -> "제거됨"
        NotificationStatus.CLICKED -> "클릭됨"
    }
}

private fun formatDetailRemovalReason(reason: RemovalReason): String {
    return when (reason) {
        RemovalReason.UNKNOWN -> "알 수 없음"
        RemovalReason.USER_CLICKED -> "사용자 클릭"
        RemovalReason.USER_DISMISSED -> "사용자 직접 제거"
        RemovalReason.AUTO_REMOVED -> "앱/시스템 제거"
    }
}

private fun formatDetailRemovalSource(notification: NotificationEntity): String {
    return when {
        notification.validEventJournalId() != null -> "리스너 제거 이벤트"
        notification.status == NotificationStatus.CLICKED &&
            notification.removalReason == RemovalReason.UNKNOWN -> "클릭 상태"
        notification.removalReason == RemovalReason.UNKNOWN -> "활성 알림 재조정"
        else -> "알림 수명주기 상태"
    }
}

private fun formatDetailSourceState(notification: NotificationEntity): String {
    return when {
        notification.validEventJournalId() != null -> "리스너 저널 수신"
        notification.status == NotificationStatus.REMOVED &&
            notification.removalReason == RemovalReason.UNKNOWN -> "활성 알림 재조정 감지"
        notification.status == NotificationStatus.CLICKED -> "클릭 상태 기록"
        else -> "리스너 캡처 기록"
    }
}

private fun formatDetailStorageState(notification: NotificationEntity): String {
    val journalState = if (notification.validEventJournalId() != null) {
        "저널 중복 방지 적용"
    } else {
        "저널 ID 없음"
    }
    val persistedState = if (notification.id > 0L) {
        "DB 저장됨"
    } else {
        "저장 전 항목"
    }
    return "$persistedState / $journalState"
}

private fun formatDetailListPlacement(
    notification: NotificationEntity,
    dateFormat: SimpleDateFormat
): String {
    val notificationType = when {
        notification.isGroupSummary() -> "그룹 요약"
        notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0 -> "포그라운드 서비스"
        notification.flags and Notification.FLAG_ONGOING_EVENT != 0 -> "진행 중"
        notification.flags and Notification.FLAG_NO_CLEAR != 0 -> "사용자 제거 제한"
        else -> "일반 알림"
    }
    return "기록 시각: ${dateFormat.format(notification.observedAt)} / 유형: $notificationType"
}

private fun formatDetailDebugMetadata(notification: NotificationEntity): String {
    val debugValues = mutableListOf<String>()
    debugValues.add("status=${notification.status.name}")
    debugValues.add("reason=${notification.removalReason.name}")
    debugValues.add("flags=${notification.flags}")
    notification.validEventJournalId()?.let { eventJournalId ->
        debugValues.add("journal=$eventJournalId")
    }
    if (notification.timeToRemoval > 0L) {
        debugValues.add("ttlMs=${notification.timeToRemoval}")
    }
    return debugValues.joinToString(", ")
}

private fun NotificationEntity.validEventJournalId(): Long? {
    return eventJournalId?.takeIf { it > 0L }
}

private fun formatDetailRemovalDetails(timeToRemoval: Long): String {
    return "수신 후 ${timeToRemoval}ms"
}

private fun formatDetailBoolean(value: Boolean): String {
    return if (value) "있음" else "없음"
}

private fun formatDetailResolved(value: Boolean): String {
    return if (value) "해결됨" else "미해결"
}

private fun NotificationEntity.isGroupSummary(): Boolean {
    return flags and Notification.FLAG_GROUP_SUMMARY != 0
}

private fun NotificationAppInfoSnapshot.stableLabel(): String {
    return label.ifBlank { packageName }
}

private fun formatDetailFlags(flags: Int): String {
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

private fun formatDetailOptionalTime(
    timestamp: Long,
    dateFormat: SimpleDateFormat
): String {
    return if (timestamp > 0L) {
        dateFormat.format(Date(timestamp))
    } else {
        "없음"
    }
}
