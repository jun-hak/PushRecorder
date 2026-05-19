package com.example.pushrecorder.ui.screens

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationScreensPagingSourceTest {
    @Test
    fun notificationListsUseIndexedPagingAccessForRenderedRows() {
        val source = readNotificationScreensSource()

        assertTrue(
            "All-notification rows must use indexed LazyPagingItems access so scrolling can trigger append loads.",
            source.contains("notifications[index]?.let { notification ->")
        )
        assertTrue(
            "Grouped rows must use indexed LazyPagingItems access so scrolling can trigger append loads.",
            source.contains("groups[index]?.let { group ->")
        )
        assertTrue(
            "Stable keys may peek without notifying Paging, but row content must still use indexed access.",
            source.contains("notifications.peek(index)?.id")
        )
        assertTrue(
            "Grouped stable keys may peek without notifying Paging, but row content must still use indexed access.",
            source.contains("groups.peek(index)?.latestNotification?.packageName")
        )
    }

    @Test
    fun notificationListsKeepPagingFootersForRefreshAndAppendStates() {
        val source = readNotificationScreensSource()
        val allNotificationsViewSource = source.substring(
            source.indexOf("fun AllNotificationsView("),
            source.indexOf("@Composable\nfun GroupedNotificationsView(")
        )
        val groupedNotificationsViewSource = source.substring(
            source.indexOf("fun GroupedNotificationsView("),
            source.indexOf("@Composable\nfun PackageNotificationsView(")
        )
        val packageNotificationsViewSource = source.substring(
            source.indexOf("fun PackageNotificationsView("),
            source.indexOf("private fun <T : Any>")
        )
        val pagingStateSource = source.substring(
            source.indexOf("private fun <T : Any>"),
            source.indexOf("@Composable\nprivate fun LoadingRow()")
        )

        assertTrue(
            "All-notification rows must keep the shared paging footer so append loads and errors stay visible below compact rows.",
            allNotificationsViewSource.contains("pagingStateItems(notifications)")
        )
        assertTrue(
            "Grouped rows must keep the shared paging footer so grouped browsing still exposes append loads and errors.",
            groupedNotificationsViewSource.contains("pagingStateItems(groups)")
        )
        assertTrue(
            "Package-detail rows must reuse the all-notifications renderer so package browsing keeps the same load-more behavior.",
            packageNotificationsViewSource.contains("AllNotificationsView(") &&
                packageNotificationsViewSource.contains("notifications = notifications") &&
                packageNotificationsViewSource.contains("onNotificationClick = onNotificationClick")
        )
        assertTrue(
            "The paging footer must continue to distinguish refresh and append loading states.",
            pagingStateSource.contains("pagingItems.loadState.refresh is LoadState.Loading") &&
                pagingStateSource.contains("pagingItems.loadState.append is LoadState.Loading") &&
                pagingStateSource.contains("item(key = \"refresh-loading\")") &&
                pagingStateSource.contains("item(key = \"append-loading\")")
        )
        assertTrue(
            "The paging footer must continue to expose both refresh and append errors after header and sheet changes.",
            pagingStateSource.contains("val refreshError = pagingItems.loadState.refresh as? LoadState.Error") &&
                pagingStateSource.contains("val appendError = pagingItems.loadState.append as? LoadState.Error") &&
                pagingStateSource.contains("item(key = \"refresh-error\")") &&
                pagingStateSource.contains("item(key = \"append-error\")")
        )
        assertTrue(
            "The empty state must remain tied to a finished refresh rather than replacing append load-more states.",
            pagingStateSource.contains("pagingItems.loadState.refresh is LoadState.NotLoading && pagingItems.itemCount == 0") &&
                pagingStateSource.indexOf("pagingItems.loadState.refresh is LoadState.NotLoading && pagingItems.itemCount == 0") <
                    pagingStateSource.indexOf("pagingItems.loadState.append is LoadState.Loading")
        )
    }

    @Test
    fun notificationListsAcceptRouteOwnedListStateAndExtraFabBottomPadding() {
        val source = readNotificationScreensSource()
        val routeSource = readPushRecorderScreenSource()

        assertTrue(
            "Notification lists should accept route-owned list states so the status FAB can restore the active header and scroll to the top.",
            source.contains("listState: LazyListState = rememberLazyListState()") &&
                source.contains("state = listState")
        )
        assertTrue(
            "Notification lists should accept extra bottom padding so the status FAB does not cover the last row or paging footer.",
            source.contains("bottomContentPadding: Dp = 16.dp") &&
                source.contains("bottom = bottomContentPadding")
        )
        assertTrue(
            "Package-detail rows should pass the package list state and bottom padding through to the all-notifications renderer.",
            source.contains("AllNotificationsView(") &&
                source.contains("listState = listState") &&
                source.contains("bottomContentPadding = bottomContentPadding")
        )
        assertTrue(
            "Route rendering should use separate list states for all, grouped, and package notification views.",
            routeSource.contains("PackageNotificationsView(") &&
                routeSource.contains("listState = packageListState") &&
                routeSource.contains("AllNotificationsView(") &&
                routeSource.contains("listState = allListState") &&
                routeSource.contains("GroupedNotificationsView(") &&
                routeSource.contains("listState = groupedListState")
        )
        assertTrue(
            "Route rendering should add enough list bottom padding while the status FAB is visible.",
            routeSource.contains("val bottomContentPadding = if (uiState.isStatusCardCollapsed)") &&
                routeSource.contains("88.dp") &&
                routeSource.contains("bottomContentPadding = bottomContentPadding")
        )
    }

    @Test
    fun groupedNotificationTapForwardsTheRenderedGroupToSelectionHandler() {
        val source = readNotificationScreensSource()
        val routeSource = readPushRecorderScreenSource()

        assertTrue(
            "Grouped rows must keep forwarding the same rendered group, not a derived index or display label.",
            source.contains("onClick = { onGroupClick(group) }")
        )
        assertTrue(
            "Route selection must still open the package from the tapped group's latest notification.",
            routeSource.contains("viewModel.selectPackage(group.latestNotification.packageName)")
        )
    }

    @Test
    fun groupedRowsPreserveRepositoryPagingOrderWithoutUiReordering() {
        val source = readNotificationScreensSource()
        val groupedViewSource = source.substring(
            source.indexOf("fun GroupedNotificationsView("),
            source.indexOf("@Composable\nfun PackageNotificationsView(")
        )

        assertTrue(
            "Grouped rows must render directly from LazyPagingItems count and index access so repository paging order remains authoritative.",
            groupedViewSource.contains("count = groups.itemCount") &&
                groupedViewSource.contains("groups[index]?.let { group ->") &&
                groupedViewSource.indexOf("groups[index]?.let { group ->") <
                    groupedViewSource.indexOf("GroupedNotificationCard(")
        )
        assertTrue(
            "Grouped preview ordering must not be changed in the UI layer after compact header changes.",
            !groupedViewSource.contains("sortedBy") &&
                !groupedViewSource.contains("sortedWith") &&
                !groupedViewSource.contains("reversed()") &&
                !groupedViewSource.contains("itemSnapshotList.items")
        )
    }

    @Test
    fun groupedDrillInKeepsPackageExpansionAheadOfViewModeRendering() {
        val routeSource = readPushRecorderScreenSource()
        val contentSource = routeSource.substring(
            routeSource.indexOf("content = {"),
            routeSource.indexOf("selectedNotificationDetail?.let")
        )

        assertTrue(
            "A selected grouped package must render package notifications before checking compact view-mode segments.",
            contentSource.indexOf("uiState.selectedPackageName != null") <
                contentSource.indexOf("uiState.viewMode == NotificationViewMode.ALL") &&
                contentSource.indexOf("uiState.selectedPackageName != null") <
                    contentSource.indexOf("uiState.viewMode == NotificationViewMode.GROUPED")
        )
        assertTrue(
            "Grouped drill-in must expand into the package notification paging flow, not keep rendering grouped previews behind the thinner header.",
            contentSource.contains("val notifications = viewModel.packageNotifications.collectAsLazyPagingItems()") &&
                contentSource.contains("PackageNotificationsView(") &&
                contentSource.contains("notifications = notifications")
        )
        assertTrue(
            "The grouped preview flow should only be collected in the grouped view-mode branch.",
            contentSource.contains("uiState.viewMode == NotificationViewMode.GROUPED") &&
                contentSource.contains("val groups = viewModel.groupedNotifications.collectAsLazyPagingItems()") &&
                contentSource.indexOf("uiState.viewMode == NotificationViewMode.GROUPED") <
                    contentSource.indexOf("val groups = viewModel.groupedNotifications.collectAsLazyPagingItems()")
        )
    }

    @Test
    fun groupedRowsRenderOnlyCompactGroupPreviewCards() {
        val source = readNotificationScreensSource()
        val groupedViewSource = source.substring(
            source.indexOf("fun GroupedNotificationsView("),
            source.indexOf("@Composable\nfun PackageNotificationsView(")
        )

        assertTrue(
            "Grouped rows must render the compact grouped preview card, not the full notification row card.",
            groupedViewSource.contains("GroupedNotificationCard(") &&
                !groupedViewSource.contains("NotificationItem(")
        )
        assertTrue(
            "Grouped rows must keep package drill-in behavior instead of opening the notification detail sheet directly.",
            groupedViewSource.contains("onClick = { onGroupClick(group) }") &&
                !groupedViewSource.contains("onNotificationClick") &&
                !groupedViewSource.contains("selectNotification")
        )
    }

    @Test
    fun groupedRowsAvoidDeadSelectedPackageRenderingState() {
        val source = readNotificationScreensSource()
        val routeSource = readPushRecorderScreenSource()
        val groupedViewSource = source.substring(
            source.indexOf("fun GroupedNotificationsView("),
            source.indexOf("\nfun PackageNotificationsView(")
        )

        assertTrue(
            "Grouped rows should not carry selected-package highlighting because selecting a package switches to package detail rendering.",
            !groupedViewSource.contains("selectedPackageName: String? = null") &&
                !groupedViewSource.contains("selected = selectedPackageName == packageName")
        )
        assertTrue(
            "Grouped rows must keep package drill-in based on the rendered package, not a display label or row index.",
            groupedViewSource.contains("val packageName = group.latestNotification.packageName") &&
                groupedViewSource.contains("onClick = { onGroupClick(group) }")
        )
        assertTrue(
            "The route should continue opening package detail from the tapped group latest notification.",
            !routeSource.contains("selectedPackageName = uiState.selectedPackageName") &&
                routeSource.contains("viewModel.selectPackage(group.latestNotification.packageName)")
        )
    }

    @Test
    fun notificationRowsKeepSameItemIdentityWhenRendered() {
        val source = readNotificationScreensSource()

        assertTrue(
            "All-notification rows must render the exact item returned by indexed Paging access.",
            source.contains("notification = notification")
        )
        assertTrue(
            "All-notification rows must forward the exact rendered item to the row selection handler.",
            source.contains("onClick = { onNotificationClick(notification) }")
        )
        assertTrue(
            "Package-detail rows reuse the all-notifications renderer with the same selection handler so selecting a package does not change item identity.",
            source.contains("onNotificationClick = onNotificationClick")
        )
    }

    @Test
    fun notificationRowTapRecordsSelectedNotificationForDetailPresentation() {
        val routeSource = readPushRecorderScreenSource()

        assertTrue(
            "The route must read selected notification state from the ViewModel-backed UI state.",
            routeSource.contains("val selectedNotificationDetail by viewModel.selectedNotificationDetail.collectAsStateWithLifecycle(null)") &&
                routeSource.contains("selectedNotificationDetail?.let { notification ->")
        )
        assertTrue(
            "All-notification rows must forward the tapped notification to the ViewModel selection event.",
            routeSource.contains("onNotificationClick = viewModel::selectNotification")
        )
        assertTrue(
            "Package notification rows must forward the tapped notification to the same ViewModel selection event.",
            !routeSource.contains("selectedNotification = notification") &&
                routeSource.contains("onNotificationClick = viewModel::selectNotification")
        )
        assertTrue(
            "The selected notification must open the detail bottom sheet until dismissed.",
            routeSource.contains("selectedNotificationDetail?.let { notification ->") &&
                routeSource.contains("NotificationDetailBottomSheet(") &&
                routeSource.contains("notification = notification") &&
                routeSource.contains("appInfoSnapshot = uiState.selectedNotificationAppInfoSnapshot") &&
                routeSource.contains("onDismiss = viewModel::clearSelectedNotification")
        )
        assertTrue(
            "The detail presentation should use a Material bottom sheet instead of expanding rows in the scan path.",
            routeSource.contains("ModalBottomSheet(") &&
                routeSource.contains("onDismissRequest = onDismiss")
        )
        assertTrue(
            "System back while the detail sheet is selected must close the sheet through the same clear-selection path.",
            routeSource.contains("BackHandler(enabled = uiState.selectedNotification != null)") &&
                routeSource.contains("viewModel.clearSelectedNotification()")
        )
    }

    @Test
    fun notificationDetailSheetOverlaysWithoutChangingGroupedPreviewRendering() {
        val routeSource = readPushRecorderScreenSource()
        val contentSource = routeSource.substring(
            routeSource.indexOf("content = {"),
            routeSource.indexOf("selectedNotificationDetail?.let")
        )
        val detailSheetSource = routeSource.substring(
            routeSource.indexOf("selectedNotificationDetail?.let"),
            routeSource.indexOf("@OptIn(ExperimentalMaterial3Api::class)")
        )

        assertTrue(
            "The detail sheet should be composed after the list content so opening it overlays current grouped or package browsing state instead of replacing it.",
            routeSource.indexOf("PushRecorderScreen(") <
                routeSource.indexOf("selectedNotificationDetail?.let { notification ->") &&
                routeSource.indexOf("content = {") <
                    routeSource.indexOf("selectedNotificationDetail?.let { notification ->")
        )
        assertTrue(
            "Grouped preview collection must stay inside the grouped view-mode branch and outside detail-sheet presentation.",
            contentSource.contains("uiState.viewMode == NotificationViewMode.GROUPED") &&
                contentSource.contains("val groups = viewModel.groupedNotifications.collectAsLazyPagingItems()") &&
                contentSource.contains("GroupedNotificationsView(") &&
                !detailSheetSource.contains("groupedNotifications") &&
                !detailSheetSource.contains("GroupedNotificationsView(")
        )
        assertTrue(
            "Opening and dismissing the detail sheet should use notification selection events, not grouped package selection events.",
            detailSheetSource.contains("NotificationDetailBottomSheet(") &&
                detailSheetSource.contains("onDismiss = viewModel::clearSelectedNotification") &&
                !detailSheetSource.contains("selectPackage") &&
                !detailSheetSource.contains("clearSelectedPackage") &&
                !detailSheetSource.contains("onViewModeChange")
        )
        assertTrue(
            "Back handling must close an open detail sheet before package drill-in back handling can run.",
            routeSource.contains("BackHandler(enabled = uiState.selectedNotification != null)") &&
                routeSource.contains("viewModel.clearSelectedNotification()") &&
                routeSource.contains("BackHandler(enabled = uiState.selectedNotification == null && uiState.selectedPackageName != null)") &&
                routeSource.contains("viewModel.clearSelectedPackage()")
        )
    }

    @Test
    fun notificationDetailDismissalPathsClearOnlyTransientDetailSelection() {
        val routeSource = readPushRecorderScreenSource()
        val viewModelSource = readPushRecorderViewModelSource()
        val detailSheetSource = routeSource.substring(
            routeSource.indexOf("selectedNotificationDetail?.let"),
            routeSource.indexOf("@OptIn(ExperimentalMaterial3Api::class)")
        )
        val clearSelectedNotificationSource = viewModelSource.substring(
            viewModelSource.indexOf("fun clearSelectedNotification("),
            viewModelSource.indexOf("suspend fun resolveAppInfo(")
        )

        assertTrue(
            "Dismiss gestures and outside taps must close the bottom sheet through the same selected-notification clear event.",
            detailSheetSource.contains("NotificationDetailBottomSheet(") &&
                detailSheetSource.contains("onDismiss = viewModel::clearSelectedNotification") &&
                routeSource.contains("ModalBottomSheet(") &&
                routeSource.contains("onDismissRequest = onDismiss")
        )
        assertTrue(
            "System back must close the detail bottom sheet through the selected-notification clear event.",
            routeSource.contains("BackHandler(enabled = uiState.selectedNotification != null)") &&
                routeSource.contains("viewModel.clearSelectedNotification()")
        )
        assertTrue(
            "Dismissing the detail sheet must remove the selected row and its captured app-info snapshot together.",
            clearSelectedNotificationSource.contains("selectedNotification = null") &&
                clearSelectedNotificationSource.contains("selectedNotificationAppInfoSnapshot = null")
        )
        assertTrue(
            "Dismissing the detail sheet must not clear package drill-in, search, or view-mode state that sits behind the sheet.",
            !clearSelectedNotificationSource.contains("selectedPackageName = null") &&
                !clearSelectedNotificationSource.contains("remove<String>(KEY_SELECTED_PACKAGE)") &&
                !clearSelectedNotificationSource.contains("viewMode =") &&
                !clearSelectedNotificationSource.contains("searchQuery =")
        )
    }

    @Test
    fun notificationDetailSheetShowsPrimaryContentAndDiagnostics() {
        val routeSource = readPushRecorderScreenSource()

        assertTrue(
            "The detail sheet must show the selected notification app label fallback in the primary heading.",
            routeSource.contains("text = appInfoSnapshot.stableLabel()") &&
                routeSource.contains("notification.title.ifBlank { \"(제목 없음)\" }")
        )
        assertTrue(
            "The primary detail section must show both app identity and the package name before lower-level diagnostics.",
                routeSource.contains("DetailRow(\"앱\", appInfoSnapshot.stableLabel())") &&
                routeSource.contains("DetailRow(\"패키지 이름\", appInfoSnapshot.packageName)") &&
                routeSource.indexOf("DetailRow(\"앱\", appInfoSnapshot.stableLabel())") <
                    routeSource.indexOf("DetailRow(\"알림 상태\", formatDetailStatus(notification.status))") &&
                routeSource.indexOf("DetailRow(\"패키지 이름\", appInfoSnapshot.packageName)") <
                    routeSource.indexOf("DetailRow(\"알림 상태\", formatDetailStatus(notification.status))")
        )
        assertTrue(
            "The detail sheet must show the captured title and body text outside the compact row preview.",
            routeSource.contains("notification.title.ifBlank { \"(제목 없음)\" }") &&
                routeSource.contains("text = notification.text")
        )
        assertTrue(
            "The detail sheet must repeat captured payload fields as explicit detail rows so blank bodies and action presence are still inspectable.",
            routeSource.contains("PayloadMetadataRows(notification = notification)") &&
                routeSource.contains("DetailSectionTitle(\"페이로드\")") &&
                routeSource.contains("DetailRow(\"제목\", notification.title.ifBlank { \"(제목 없음)\" })") &&
                routeSource.contains("DetailRow(\"본문\", notification.text.ifBlank { \"없음\" })") &&
                routeSource.contains("DetailRow(\"액션 포함\", formatDetailBoolean(notification.hasActions))")
        )
        assertTrue(
            "The detail sheet must keep status and timing metadata visible for the selected notification.",
            routeSource.contains("DetailRow(\"알림 상태\", formatDetailStatus(notification.status))") &&
                routeSource.contains("TimestampMetadataRows(")
        )
        assertTrue(
            "The detail sheet must show source-posted, observed event, and removed timestamp rows using the existing detail date formatter.",
                routeSource.contains("TimestampMetadataRows(") &&
                routeSource.contains("DetailRow(\"게시 시각\", formatDetailOptionalTime(notification.timestamp, dateFormat))") &&
                routeSource.contains("DetailRow(\"이벤트 기록 시각\", dateFormat.format(notification.observedAt))") &&
                routeSource.contains("DetailRow(\"제거 시각\", dateFormat.format(notification.removedAt))")
        )
        assertTrue(
            "The detail sheet must keep terminal removal metadata available when a notification has left the shade.",
            routeSource.contains("if (notification.status != NotificationStatus.POSTED)") &&
                routeSource.contains("DetailRow(\"제거 출처\", formatDetailRemovalSource(notification))") &&
                routeSource.contains("DetailRow(\"제거 사유\", formatDetailRemovalReason(notification.removalReason))") &&
                routeSource.contains("DetailRow(\"제거 시각\", dateFormat.format(notification.removedAt))") &&
                routeSource.contains("DetailRow(\"제거 상세\", formatDetailRemovalDetails(notification.timeToRemoval))")
        )
        assertTrue(
            "Removal lifecycle rows should only show optional details when the selected notification has those details.",
            routeSource.contains("if (notification.removedAt > 0L)") &&
                routeSource.contains("if (notification.timeToRemoval > 0L)")
        )
        assertTrue(
            "Removal source should distinguish listener-journaled removals from reconcile-derived removals where possible.",
            routeSource.contains("private fun formatDetailRemovalSource(notification: NotificationEntity): String") &&
                routeSource.contains("notification.validEventJournalId() != null -> \"리스너 제거 이벤트\"") &&
                routeSource.contains("notification.removalReason == RemovalReason.UNKNOWN -> \"활성 알림 재조정\"")
        )
        assertTrue(
            "The detail sheet must keep low-level diagnostic metadata accessible without expanding the default list row.",
            routeSource.contains("DetailRow(\"ID\", notification.id.toString())") &&
                routeSource.contains("DetailRow(\"알림 키\", notification.notificationKey)") &&
                routeSource.contains("DetailRow(\"앱\", appInfoSnapshot.stableLabel())") &&
                routeSource.contains("DetailRow(\"패키지 이름\", appInfoSnapshot.packageName)") &&
                routeSource.contains("DetailRow(\"앱 정보 해석\", formatDetailResolved(appInfoSnapshot.isResolved))") &&
                routeSource.contains("DetailRow(\"플래그\", formatDetailFlags(notification.flags))") &&
                routeSource.contains("DetailRow(\"액션\", formatDetailBoolean(notification.hasActions))") &&
                routeSource.contains("DetailRow(\"원본 시각\", formatDetailOptionalTime(notification.timestamp, dateFormat))") &&
                routeSource.contains("DetailRow(\"저널 ID\", eventJournalId.toString())")
        )
        assertTrue(
            "Empty or unavailable diagnostic fields should stay out of the detail sheet instead of rendering as noisy 없음 rows.",
            routeSource.contains("DiagnosticMetadataRows(") &&
                routeSource.contains("if (notification.flags != 0)") &&
                routeSource.contains("if (notification.hasActions)") &&
                routeSource.contains("if (notification.timestamp > 0L)") &&
                routeSource.contains("notification.validEventJournalId()?.let { eventJournalId ->")
        )
        assertTrue(
            "Flag diagnostics should be readable labels rather than only raw integers.",
            routeSource.contains("private fun formatDetailFlags(flags: Int): String") &&
                routeSource.contains("Notification.FLAG_AUTO_CANCEL") &&
                routeSource.contains("Notification.FLAG_FOREGROUND_SERVICE") &&
                routeSource.contains("?: \"없음\"")
        )
        assertTrue(
            "Persisted group-summary payload metadata should be surfaced from notification flags without adding storage dependencies.",
            routeSource.contains("private fun NotificationEntity.isGroupSummary(): Boolean") &&
                routeSource.contains("return flags and Notification.FLAG_GROUP_SUMMARY != 0") &&
                routeSource.contains("if (notification.isGroupSummary())") &&
                routeSource.contains("DetailRow(\"그룹 요약\", formatDetailBoolean(true))")
        )
    }

    @Test
    fun notificationDetailSheetHandlesMissingRemovalMetadataAndActiveState() {
        val routeSource = readPushRecorderScreenSource()

        assertTrue(
            "Active notifications should show status without removal lifecycle rows.",
            routeSource.contains("DetailRow(\"알림 상태\", formatDetailStatus(notification.status))") &&
                routeSource.contains("if (notification.status != NotificationStatus.POSTED)")
        )
        assertTrue(
            "Missing removed-at metadata should render as unavailable rather than a formatted epoch time.",
            routeSource.contains("if (notification.removedAt > 0L)") &&
                routeSource.contains("DetailRow(\"제거 시각\", \"없음\")")
        )
        assertTrue(
            "Missing removal elapsed metadata should be omitted from lifecycle details.",
            routeSource.contains("if (notification.timeToRemoval > 0L)") &&
                routeSource.contains("DetailRow(\"제거 상세\", formatDetailRemovalDetails(notification.timeToRemoval))")
        )
        assertTrue(
            "Clicked terminal rows with missing removal reason should not be labelled as reconcile-derived removals.",
            routeSource.contains("notification.status == NotificationStatus.CLICKED") &&
                routeSource.contains("notification.removalReason == RemovalReason.UNKNOWN -> \"클릭 상태\"") &&
                routeSource.contains("notification.removalReason == RemovalReason.UNKNOWN -> \"활성 알림 재조정\"")
        )
        assertTrue(
            "Status labels should continue to distinguish active, removed, and clicked notifications in the sheet.",
            routeSource.contains("NotificationStatus.POSTED -> \"수신됨\"") &&
                routeSource.contains("NotificationStatus.REMOVED -> \"제거됨\"") &&
                routeSource.contains("NotificationStatus.CLICKED -> \"클릭됨\"")
        )
    }

    @Test
    fun notificationDetailSheetGroupsAppInfoAndDiagnosticsIntoExplicitSections() {
        val routeSource = readPushRecorderScreenSource()

        assertTrue(
            "The bottom sheet should present selected app metadata as an explicit app-info section.",
            routeSource.contains("private fun AppInfoMetadataRows(") &&
                routeSource.contains("DetailSectionTitle(\"앱 정보\")") &&
                routeSource.contains("DetailRow(\"앱\", appInfoSnapshot.stableLabel())") &&
                routeSource.contains("DetailRow(\"패키지 이름\", appInfoSnapshot.packageName)") &&
                routeSource.contains("DetailRow(\"앱 정보 해석\", formatDetailResolved(appInfoSnapshot.isResolved))")
        )
        assertTrue(
            "The bottom sheet should place selected notification diagnostics under an explicit diagnostics section.",
            routeSource.contains("DetailSectionTitle(\"진단 정보\")") &&
                routeSource.contains("DiagnosticMetadataRows(") &&
                routeSource.contains("notification = notification") &&
                routeSource.contains("appInfoSnapshot = appInfoSnapshot") &&
                routeSource.contains("dateFormat = dateFormat")
        )
        assertTrue(
            "The diagnostics section must render selected notification identity and optional diagnostic fields.",
            routeSource.contains("DetailRow(\"리스너/소스 상태\", formatDetailSourceState(notification))") &&
                routeSource.contains("DetailRow(\"중복/저장 상태\", formatDetailStorageState(notification))") &&
                routeSource.contains("DetailRow(\"목록 기준/알림 유형\", formatDetailListPlacement(notification, dateFormat))") &&
                routeSource.contains("DetailRow(\"디버그 메타데이터\", formatDetailDebugMetadata(notification))") &&
                routeSource.contains("DetailRow(\"ID\", notification.id.toString())") &&
                routeSource.contains("DetailRow(\"알림 키\", notification.notificationKey)") &&
                routeSource.contains("DetailRow(\"플래그\", formatDetailFlags(notification.flags))") &&
                routeSource.contains("DetailRow(\"액션\", formatDetailBoolean(notification.hasActions))") &&
                routeSource.contains("DetailRow(\"원본 시각\", formatDetailOptionalTime(notification.timestamp, dateFormat))") &&
                routeSource.contains("DetailRow(\"저널 ID\", eventJournalId.toString())")
        )
        assertTrue(
            "Diagnostics must summarize listener/source state without adding repository lookups to the sheet.",
            routeSource.contains("private fun formatDetailSourceState(notification: NotificationEntity): String") &&
                routeSource.contains("notification.validEventJournalId() != null -> \"리스너 저널 수신\"") &&
                routeSource.contains("notification.status == NotificationStatus.REMOVED") &&
                routeSource.contains("notification.status == NotificationStatus.CLICKED") &&
                routeSource.contains("else -> \"리스너 캡처 기록\"")
        )
        assertTrue(
            "Diagnostics must expose duplicate/storage indicators from existing persisted notification fields.",
            routeSource.contains("private fun formatDetailStorageState(notification: NotificationEntity): String") &&
                routeSource.contains("저널 중복 방지 적용") &&
                routeSource.contains("저널 ID 없음") &&
                routeSource.contains("DB 저장됨") &&
                routeSource.contains("저장 전 항목")
        )
        assertTrue(
            "Diagnostics must expose list placement and notification-type data available on the selected row.",
            routeSource.contains("private fun formatDetailListPlacement(") &&
                routeSource.contains("기록 시각: \${dateFormat.format(notification.observedAt)} / 유형: \$notificationType") &&
                routeSource.contains("notification.isGroupSummary() -> \"그룹 요약\"") &&
                routeSource.contains("Notification.FLAG_FOREGROUND_SERVICE") &&
                routeSource.contains("Notification.FLAG_ONGOING_EVENT") &&
                routeSource.contains("Notification.FLAG_NO_CLEAR") &&
                routeSource.contains("private fun formatDetailDebugMetadata(notification: NotificationEntity): String") &&
                routeSource.contains("status=\${notification.status.name}") &&
                routeSource.contains("reason=\${notification.removalReason.name}") &&
                routeSource.contains("flags=\${notification.flags}") &&
                routeSource.contains("ttlMs=\${notification.timeToRemoval}")
        )
    }

    @Test
    fun notificationDetailSheetHandlesMissingPresentAndInvalidJournalIds() {
        val routeSource = readPushRecorderScreenSource()
        val viewModelSource = readPushRecorderViewModelSource()

        assertTrue(
            "Detail navigation must be driven by the selected notification object, not by looking up a journal id that may be missing or invalid.",
            viewModelSource.contains("fun selectNotification(notification: NotificationEntity)") &&
                viewModelSource.contains("selectedNotification = notification") &&
                routeSource.contains("selectedNotificationDetail?.let { notification ->") &&
                routeSource.contains("notification = notification") &&
                !routeSource.contains("selectedJournal") &&
                !routeSource.contains("loadNotificationByJournal") &&
                !routeSource.contains("findNotificationByJournal")
        )
        assertTrue(
            "Missing journal ids should keep the detail sheet open and omit the journal diagnostic row through the same optional guard.",
            routeSource.contains("notification.validEventJournalId()?.let { eventJournalId ->") &&
                routeSource.contains("DetailRow(\"저널 ID\", eventJournalId.toString())")
        )
        assertTrue(
            "Present positive journal ids should still identify listener-journaled removals in the detail fallback wording.",
            routeSource.contains("private fun NotificationEntity.validEventJournalId(): Long?") &&
                routeSource.contains("return eventJournalId?.takeIf { it > 0L }") &&
                routeSource.contains("notification.validEventJournalId() != null -> \"리스너 제거 이벤트\"")
        )
        assertTrue(
            "Invalid zero or negative journal ids should fall back to lifecycle or reconcile wording instead of being treated as valid listener events.",
            routeSource.contains("return eventJournalId?.takeIf { it > 0L }") &&
                routeSource.contains("notification.removalReason == RemovalReason.UNKNOWN -> \"활성 알림 재조정\"") &&
                routeSource.contains("else -> \"알림 수명주기 상태\"") &&
                !routeSource.contains("eventJournalId != null -> \"리스너 제거 이벤트\"")
        )
    }

    @Test
    fun notificationDetailSheetKeepsActionDiagnosticsOptionalAndBounded() {
        val routeSource = readPushRecorderScreenSource()
        val entitySource = readNotificationEntitySource()
        val captureMapperSource = readStatusBarNotificationCaptureMapperSource()

        assertTrue(
            "Notifications with absent action metadata should default to no actions and still render without requiring an action row.",
            entitySource.contains("val hasActions: Boolean = false") &&
                routeSource.contains("if (notification.hasActions)") &&
                !routeSource.contains("else {\n        DetailRow(\"액션\"")
        )
        assertTrue(
            "Notifications with empty platform actions should collapse to the same false action state before the UI sees them.",
            captureMapperSource.contains("hasActions = postedNotification.actions?.isNotEmpty() == true")
        )
        assertTrue(
            "Notifications with populated actions should render only a bounded diagnostic summary, not the raw action list.",
            routeSource.contains("if (notification.hasActions)") &&
                routeSource.contains("DetailRow(\"액션\", formatDetailBoolean(notification.hasActions))") &&
                routeSource.contains("private fun formatDetailBoolean(value: Boolean): String") &&
                !routeSource.contains("postedNotification.actions") &&
                !routeSource.contains("notification.actions")
        )
        assertTrue(
            "The action diagnostic row should use the same weighted detail layout so long labels cannot overflow the sheet.",
            routeSource.contains("private fun DetailRow(") &&
                routeSource.contains("modifier = Modifier.weight(0.34f)") &&
                routeSource.contains("modifier = Modifier.weight(0.66f)") &&
                routeSource.contains("maxLines = 1") &&
                routeSource.contains("overflow = TextOverflow.Ellipsis")
        )
    }

    @Test
    fun notificationDetailSheetFallsBackForMissingOrPartialAppInfoSnapshots() {
        val routeSource = readPushRecorderScreenSource()
        val viewModelSource = readPushRecorderViewModelSource()

        assertTrue(
            "Selected notification snapshots should still be built from captured row data with package fallback when the app label is missing.",
            viewModelSource.contains("label = notification.appLabel.ifBlank { notification.packageName }")
        )
        assertTrue(
            "The detail sheet should also guard against a partial snapshot with a blank label before rendering app metadata.",
            routeSource.contains("private fun NotificationAppInfoSnapshot.stableLabel(): String") &&
                routeSource.contains("return label.ifBlank { packageName }")
        )
        assertTrue(
            "Every visible app label in the detail sheet should use the stable snapshot label while keeping package diagnostics accessible separately.",
            routeSource.contains("text = appInfoSnapshot.stableLabel()") &&
                routeSource.contains("DetailRow(\"앱\", appInfoSnapshot.stableLabel())") &&
                routeSource.contains("DetailRow(\"패키지 이름\", appInfoSnapshot.packageName)") &&
                !routeSource.contains("text = appInfoSnapshot.label") &&
                !routeSource.contains("DetailRow(\"앱\", appInfoSnapshot.label)")
        )
    }

    private fun readNotificationScreensSource(): String {
        val candidates = listOf(
            Path.of("src/main/java/com/example/pushrecorder/ui/screens/NotificationScreens.kt"),
            Path.of("app/src/main/java/com/example/pushrecorder/ui/screens/NotificationScreens.kt")
        )
        val sourcePath = candidates.firstOrNull(Files::isReadable)
            ?: error("NotificationScreens.kt was not found from known Gradle test working directories")

        return String(Files.readAllBytes(sourcePath))
    }

    private fun readPushRecorderScreenSource(): String {
        val candidates = listOf(
            Path.of("src/main/java/com/example/pushrecorder/ui/screens/PushRecorderScreen.kt"),
            Path.of("app/src/main/java/com/example/pushrecorder/ui/screens/PushRecorderScreen.kt")
        )
        val sourcePath = candidates.firstOrNull(Files::isReadable)
            ?: error("PushRecorderScreen.kt was not found from known Gradle test working directories")

        return String(Files.readAllBytes(sourcePath))
    }

    private fun readNotificationEntitySource(): String {
        val candidates = listOf(
            Path.of("src/main/java/com/example/pushrecorder/data/NotificationEntity.kt"),
            Path.of("app/src/main/java/com/example/pushrecorder/data/NotificationEntity.kt")
        )
        val sourcePath = candidates.firstOrNull(Files::isReadable)
            ?: error("NotificationEntity.kt was not found from known Gradle test working directories")

        return String(Files.readAllBytes(sourcePath))
    }

    private fun readPushRecorderViewModelSource(): String {
        val candidates = listOf(
            Path.of("src/main/java/com/example/pushrecorder/ui/PushRecorderViewModel.kt"),
            Path.of("app/src/main/java/com/example/pushrecorder/ui/PushRecorderViewModel.kt")
        )
        val sourcePath = candidates.firstOrNull(Files::isReadable)
            ?: error("PushRecorderViewModel.kt was not found from known Gradle test working directories")

        return String(Files.readAllBytes(sourcePath))
    }

    private fun readStatusBarNotificationCaptureMapperSource(): String {
        val candidates = listOf(
            Path.of("src/main/java/com/example/pushrecorder/service/StatusBarNotificationCaptureMapper.kt"),
            Path.of("app/src/main/java/com/example/pushrecorder/service/StatusBarNotificationCaptureMapper.kt")
        )
        val sourcePath = candidates.firstOrNull(Files::isReadable)
            ?: error("StatusBarNotificationCaptureMapper.kt was not found from known Gradle test working directories")

        return String(Files.readAllBytes(sourcePath))
    }
}
