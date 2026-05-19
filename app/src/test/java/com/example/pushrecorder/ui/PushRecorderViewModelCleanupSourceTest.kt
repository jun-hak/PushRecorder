package com.example.pushrecorder.ui

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushRecorderViewModelCleanupSourceTest {
    @Test
    fun viewModelStartupCleanupUsesSharedSchedulerEntryPoint() {
        val source = readViewModelSource()

        assertTrue(
            "ViewModel should inject the shared storage cleanup scheduler.",
            source.contains("private val storageCleanupScheduler: NotificationStorageCleanupScheduler")
        )
        assertTrue(
            "ViewModel startup cleanup should run through the shared scheduler.",
            source.contains("storageCleanupScheduler.runCleanup(")
        )
        assertTrue(
            "ViewModel cleanup failure should report the shared cleanup failure state.",
            source.contains("listenerStatusRepository.markError(\"Failed to clean notification storage\")")
        )
        assertFalse(
            "ViewModel should not duplicate notification-only retention cleanup rules.",
            source.contains("notificationRepository.deleteExpiredNotifications()")
        )
    }

    @Test
    fun viewModelOwnsSelectedNotificationStateAndClearEvent() {
        val source = readViewModelSource()

        assertTrue(
            "UI state should carry the selected notification so detail presentation is owned by the state holder.",
            source.contains("val selectedNotification: NotificationEntity? = null")
        )
        assertTrue(
            "UI state should expose the selected notification app-info snapshot separately for detail presentation.",
            source.contains("val selectedNotificationAppInfoSnapshot: NotificationAppInfoSnapshot? = null")
        )
        assertTrue(
            "ViewModel should expose a selection event for notification row taps.",
            source.contains("fun selectNotification(notification: NotificationEntity)") &&
                source.contains("selectedNotification = notification") &&
                source.contains(
                    "selectedNotificationAppInfoSnapshot = NotificationAppInfoSnapshot.fromNotification(notification)"
                )
        )
        assertTrue(
            "ViewModel should expose a dismiss event that clears the selected notification.",
            source.contains("fun clearSelectedNotification()") &&
                source.contains("selectedNotification = null") &&
                source.contains("selectedNotificationAppInfoSnapshot = null")
        )
    }

    @Test
    fun viewModelDefaultsStatusCardToCollapsedAndRemembersSessionChoice() {
        val source = readViewModelSource()

        assertTrue(
            "UI state should default the capture status area to the collapsed FAB presentation.",
            source.contains("val isStatusCardCollapsed: Boolean = true")
        )
        assertTrue(
            "The collapsed/expanded status presentation should be restored from SavedStateHandle for the current process/session.",
            source.contains("isStatusCardCollapsed = savedStateHandle[KEY_STATUS_CARD_COLLAPSED] ?: true")
        )
        assertTrue(
            "The ViewModel should expose a single event for toggling status-card presentation.",
            source.contains("fun setStatusCardCollapsed(isCollapsed: Boolean)") &&
                source.contains("state.copy(isStatusCardCollapsed = isCollapsed)") &&
                source.contains("savedStateHandle[KEY_STATUS_CARD_COLLAPSED] = isCollapsed")
        )
    }

    @Test
    fun selectedNotificationAppInfoSnapshotUsesCapturedNotificationFields() {
        val source = readViewModelSource()

        assertTrue(
            "The detail app-info snapshot must be derived from the captured notification row, not from a fresh app-info lookup.",
            source.contains("data class NotificationAppInfoSnapshot(") &&
                source.contains("val packageName: String") &&
                source.contains("val label: String") &&
                source.contains("val isResolved: Boolean")
        )
        assertTrue(
            "The detail app-info snapshot should preserve the captured package, captured app label fallback, and captured resolution flag.",
            source.contains("fun fromNotification(notification: NotificationEntity): NotificationAppInfoSnapshot") &&
                source.contains("packageName = notification.packageName") &&
                source.contains("label = notification.appLabel.ifBlank { notification.packageName }") &&
                source.contains("isResolved = notification.appInfoResolved")
        )
    }

    @Test
    fun selectedNotificationDetailObservesLatestLifecycleRowForOpenSheet() {
        val viewModelSource = readViewModelSource()
        val repositorySource = readSource("data/NotificationRepository.kt")
        val daoSource = readSource("data/NotificationDao.kt")

        assertTrue(
            "Open detail sheets should be backed by a flow that can update when a terminal lifecycle row is inserted.",
            viewModelSource.contains("val selectedNotificationDetail: Flow<NotificationEntity?> = uiState") &&
                viewModelSource.contains(".flatMapLatest { selectedNotification ->") &&
                viewModelSource.contains("notificationRepository.observeNotificationDetail(")
        )
        assertTrue(
            "Detail updates should be scoped to the selected notification key and only rows at or after the selected row.",
            viewModelSource.contains("notificationKey = selectedNotification.notificationKey") &&
                viewModelSource.contains("selectedId = selectedNotification.id")
        )
        assertTrue(
            "The repository should expose a detail-observation flow instead of forcing the sheet to keep only the tapped row copy.",
            repositorySource.contains("fun observeNotificationDetail(") &&
                repositorySource.contains("notificationDao.observeLatestNotificationForDetail(")
        )
        assertTrue(
            "The DAO detail query should return the latest row for the selected notification lifecycle key so removed/clicked updates can appear while the sheet is open.",
            daoSource.contains("fun observeLatestNotificationForDetail(") &&
                daoSource.contains("notificationKey = :notificationKey") &&
                daoSource.contains("id >= :selectedId") &&
                daoSource.contains("ORDER BY id DESC") &&
                daoSource.contains("Flow<NotificationEntity?>")
        )
    }

    @Test
    fun searchAndViewModeChangesPreserveGroupedPackageSelection() {
        val source = readViewModelSource()
        val setViewModeSource = source.substring(
            source.indexOf("fun setViewMode("),
            source.indexOf("fun setSearchQuery(")
        )
        val setSearchQuerySource = source.substring(
            source.indexOf("fun setSearchQuery("),
            source.indexOf("fun selectPackage(")
        )

        assertTrue(
            "Changing the compact view-mode segment should update only the mode, preserving grouped package drill-in state.",
            setViewModeSource.contains("state.copy(viewMode = viewMode)")
        )
        assertFalse(
            "Changing the compact view-mode segment must not clear selectedPackageName.",
            setViewModeSource.contains("selectedPackageName = null") ||
                setViewModeSource.contains("remove<String>(KEY_SELECTED_PACKAGE)")
        )
        assertTrue(
            "Typing in the compact search field should update only the query, preserving grouped package drill-in state.",
            setSearchQuerySource.contains("state.copy(searchQuery = query)")
        )
        assertFalse(
            "Typing in the compact search field must not clear selectedPackageName.",
            setSearchQuerySource.contains("selectedPackageName = null") ||
                setSearchQuerySource.contains("remove<String>(KEY_SELECTED_PACKAGE)")
        )
    }

    @Test
    fun searchQueryChangesDriveAllNotificationPagingSoClearingRestoresUnfilteredList() {
        val source = readViewModelSource()
        val normalizedQuerySource = source.substring(
            source.indexOf("private val normalizedQuery = uiState"),
            source.indexOf("private val selectedPackageName = uiState")
        )
        val allNotificationsSource = source.substring(
            source.indexOf("val allNotifications: Flow<PagingData<NotificationEntity>>"),
            source.indexOf("val groupedNotifications: Flow<PagingData<NotificationGroupSummary>>")
        )
        val setSearchQuerySource = source.substring(
            source.indexOf("fun setSearchQuery("),
            source.indexOf("fun selectPackage(")
        )

        assertTrue(
            "Search paging should be driven from the UI state's query, so resetting that query to blank restores the unfiltered all-notification list.",
            normalizedQuerySource.contains(".map { it.searchQuery.trim() }") &&
                allNotificationsSource.contains("normalizedQuery") &&
                allNotificationsSource.contains(".flatMapLatest(notificationRepository::pagedNotifications)")
        )
        assertTrue(
            "The same search setter must accept and persist the cleared empty query instead of using a separate clear path.",
            setSearchQuerySource.contains("state.copy(searchQuery = query)") &&
                setSearchQuerySource.contains("savedStateHandle[KEY_SEARCH_QUERY] = query")
        )
    }

    @Test
    fun activeSearchQueryDrivesGroupedNotificationPaging() {
        val source = readViewModelSource()
        val groupedNotificationsSource = source.substring(
            source.indexOf("val groupedNotifications: Flow<PagingData<NotificationGroupSummary>>"),
            source.indexOf("val packageNotifications: Flow<PagingData<NotificationEntity>>")
        )

        assertTrue(
            "The active compact search query must continue filtering grouped notification previews by package, app label, title, and text through the repository.",
            groupedNotificationsSource.contains("normalizedQuery") &&
                groupedNotificationsSource.contains(".flatMapLatest(notificationRepository::pagedNotificationGroups)")
        )
    }

    @Test
    fun selectedGroupedPackageDrivesPackageNotificationPagingWithSearchFilter() {
        val source = readViewModelSource()
        val packageNotificationsSource = source.substring(
            source.indexOf("val packageNotifications: Flow<PagingData<NotificationEntity>>"),
            source.indexOf("init {")
        )

        assertTrue(
            "Package drill-in paging must be driven by both selected grouped package and the normalized compact search query.",
            packageNotificationsSource.contains("combine(selectedPackageName, normalizedQuery) { packageName, query ->") &&
                packageNotificationsSource.contains("packageName to query")
        )
        assertTrue(
            "Without a selected package the package-detail list must stay empty instead of falling back to all notifications.",
            packageNotificationsSource.contains("if (packageName == null)") &&
                packageNotificationsSource.contains("flowOf(PagingData.empty())")
        )
        assertTrue(
            "Selecting a grouped package must load notifications by that package name while preserving the current search query.",
            packageNotificationsSource.contains("notificationRepository.pagedNotificationsByPackage(") &&
                packageNotificationsSource.contains("packageName = packageName") &&
                packageNotificationsSource.contains("query = query")
        )
    }

    @Test
    fun notificationDetailSelectionDoesNotAlterGroupedSelectionOrPreviewState() {
        val source = readViewModelSource()
        val selectNotificationSource = source.substring(
            source.indexOf("fun selectNotification("),
            source.indexOf("fun clearSelectedNotification(")
        )
        val clearSelectedNotificationSource = source.substring(
            source.indexOf("fun clearSelectedNotification("),
            source.indexOf("suspend fun resolveAppInfo(")
        )

        assertTrue(
            "Opening the detail sheet should only add selected notification state on top of the current grouped selection and preview mode.",
            selectNotificationSource.contains("state.copy(") &&
                selectNotificationSource.contains("selectedNotification = notification") &&
                selectNotificationSource.contains(
                    "selectedNotificationAppInfoSnapshot = NotificationAppInfoSnapshot.fromNotification(notification)"
                )
        )
        assertFalse(
            "Opening the detail sheet must not clear or rewrite grouped package drill-in state.",
            selectNotificationSource.contains("selectedPackageName = null") ||
                selectNotificationSource.contains("selectedPackageName = notification.packageName") ||
                selectNotificationSource.contains("remove<String>(KEY_SELECTED_PACKAGE)")
        )
        assertFalse(
            "Opening the detail sheet must not switch grouped preview mode or search state.",
            selectNotificationSource.contains("viewMode =") ||
                selectNotificationSource.contains("searchQuery =") ||
                selectNotificationSource.contains("KEY_VIEW_MODE") ||
                selectNotificationSource.contains("KEY_SEARCH_QUERY")
        )
        assertTrue(
            "Closing the detail sheet should remove only the transient selected notification state.",
            clearSelectedNotificationSource.contains("state.copy(") &&
                clearSelectedNotificationSource.contains("selectedNotification = null") &&
                clearSelectedNotificationSource.contains("selectedNotificationAppInfoSnapshot = null")
        )
        assertFalse(
            "Closing the detail sheet must leave grouped package drill-in state intact.",
            clearSelectedNotificationSource.contains("selectedPackageName = null") ||
                clearSelectedNotificationSource.contains("remove<String>(KEY_SELECTED_PACKAGE)")
        )
        assertFalse(
            "Closing the detail sheet must leave grouped preview mode and search state intact.",
            clearSelectedNotificationSource.contains("viewMode =") ||
                clearSelectedNotificationSource.contains("searchQuery =") ||
                clearSelectedNotificationSource.contains("KEY_VIEW_MODE") ||
                clearSelectedNotificationSource.contains("KEY_SEARCH_QUERY")
        )
    }

    private fun readViewModelSource(): String {
        return readSource("ui/PushRecorderViewModel.kt")
    }

    private fun readSource(relativePath: String): String {
        val candidates = listOf(
            Path.of("src/main/java/com/example/pushrecorder/$relativePath"),
            Path.of("app/src/main/java/com/example/pushrecorder/$relativePath")
        )
        val sourcePath = candidates.firstOrNull(Files::isReadable)
            ?: error("$relativePath was not found from known Gradle test working directories")

        return String(Files.readAllBytes(sourcePath))
    }
}
