package com.example.pushrecorder.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import com.example.pushrecorder.appinfo.AppDisplayInfo
import com.example.pushrecorder.data.NotificationGroupSummary
import com.example.pushrecorder.data.NotificationEntity
import com.example.pushrecorder.ui.appinfo.rememberAppDisplayInfo
import com.example.pushrecorder.ui.components.GroupedNotificationCard
import com.example.pushrecorder.ui.components.NotificationItem

@Composable
fun AllNotificationsView(
    notifications: LazyPagingItems<NotificationEntity>,
    onNotificationClick: (NotificationEntity) -> Unit,
    listState: LazyListState = rememberLazyListState(),
    bottomContentPadding: Dp = 16.dp
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 16.dp,
            end = 16.dp,
            bottom = bottomContentPadding
        )
    ) {
        items(
            count = notifications.itemCount,
            key = { index -> notifications.peek(index)?.id ?: "notification-$index" }
        ) { index ->
            notifications[index]?.let { notification ->
                NotificationItem(
                    notification = notification,
                    onClick = { onNotificationClick(notification) }
                )
            }
        }

        pagingStateItems(notifications)
    }
}

@Composable
fun GroupedNotificationsView(
    groups: LazyPagingItems<NotificationGroupSummary>,
    resolveAppInfo: suspend (String) -> AppDisplayInfo,
    onGroupClick: (NotificationGroupSummary) -> Unit,
    listState: LazyListState = rememberLazyListState(),
    bottomContentPadding: Dp = 16.dp
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 16.dp,
            end = 16.dp,
            bottom = bottomContentPadding
        )
    ) {
        items(
            count = groups.itemCount,
            key = { index -> groups.peek(index)?.latestNotification?.packageName ?: "group-$index" }
        ) { index ->
            groups[index]?.let { group ->
                val packageName = group.latestNotification.packageName
                val appInfo by rememberAppDisplayInfo(packageName, resolveAppInfo)

                GroupedNotificationCard(
                    group = group,
                    appInfo = appInfo ?: AppDisplayInfo.fallback(packageName),
                    onClick = { onGroupClick(group) }
                )
            }
        }

        pagingStateItems(groups)
    }
}

@Composable
fun PackageNotificationsView(
    notifications: LazyPagingItems<NotificationEntity>,
    onNotificationClick: (NotificationEntity) -> Unit,
    listState: LazyListState = rememberLazyListState(),
    bottomContentPadding: Dp = 16.dp
) {
    AllNotificationsView(
        notifications = notifications,
        onNotificationClick = onNotificationClick,
        listState = listState,
        bottomContentPadding = bottomContentPadding
    )
}

private fun <T : Any> androidx.compose.foundation.lazy.LazyListScope.pagingStateItems(
    pagingItems: LazyPagingItems<T>
) {
    val refreshError = pagingItems.loadState.refresh as? LoadState.Error
    val appendError = pagingItems.loadState.append as? LoadState.Error

    when {
        pagingItems.loadState.refresh is LoadState.Loading -> {
            item(key = "refresh-loading") {
                LoadingRow()
            }
        }
        refreshError != null -> {
            item(key = "refresh-error") {
                ErrorRow(refreshError.error.localizedMessage)
            }
        }
        pagingItems.loadState.refresh is LoadState.NotLoading && pagingItems.itemCount == 0 -> {
            item(key = "empty") {
                EmptyRow()
            }
        }
        pagingItems.loadState.append is LoadState.Loading -> {
            item(key = "append-loading") {
                LoadingRow()
            }
        }
        appendError != null -> {
            item(key = "append-error") {
                ErrorRow(appendError.error.localizedMessage)
            }
        }
    }
}

@Composable
private fun LoadingRow() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyRow() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "저장된 푸시가 없습니다",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun ErrorRow(message: String?) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message ?: "푸시 목록을 불러오지 못했습니다",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
    }
}
