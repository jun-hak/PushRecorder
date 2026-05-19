package com.example.pushrecorder.ui.screens

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushRecorderHeaderSourceTest {
    @Test
    fun searchControlUsesCompactInputWhileKeepingSearchCallback() {
        val source = readPushRecorderScreenSource()
        val searchControlSource = source.substring(
            source.indexOf("private fun CompactSearchControl("),
            source.indexOf("@Composable\nprivate fun ListenerStatusCard(")
        )

        assertTrue(
            "Header search should use the compact search control instead of a full-height text field.",
            source.contains("CompactSearchControl(")
        )
        assertTrue(
            "Compact search must preserve the existing filtering callback.",
            searchControlSource.contains("onValueChange = onSearchQueryChange")
        )
        assertTrue(
            "Compact search must display the current query text instead of keeping local input state.",
            searchControlSource.contains("value = searchQuery")
        )
        assertTrue(
            "Compact search should render the editable text field inside its decoration box.",
            searchControlSource.contains("innerTextField()")
        )
        assertTrue(
            "Compact search must keep a stable accessibility label after the placeholder disappears.",
            searchControlSource.contains(".semantics { contentDescription = \"푸시 검색\" }")
        )
        assertTrue(
            "Compact search should only show the hint while the current query is blank.",
            searchControlSource.contains("if (searchQuery.isBlank())") &&
                searchControlSource.contains("text = \"앱, 제목, 내용 검색\"")
        )
        assertTrue(
            "Compact search should keep the Android-recommended touch height while remaining visually simple.",
            searchControlSource.contains(".minimumInteractiveComponentSize()") &&
                searchControlSource.contains(".height(48.dp)")
        )
        assertTrue(
            "Search header spacing should stay tight around the compact search control.",
            source.contains(".padding(horizontal = 12.dp, vertical = 6.dp)") &&
                source.contains("Spacer(modifier = Modifier.height(4.dp))")
        )
        assertFalse(
            "The search header should not bring back the full-height Material outlined input.",
            source.contains("OutlinedTextField(")
        )
    }

    @Test
    fun searchControlClearActionResetsQueryThroughFilteringCallback() {
        val source = readPushRecorderScreenSource()
        val searchControlSource = source.substring(
            source.indexOf("private fun CompactSearchControl("),
            source.indexOf("@Composable\nprivate fun ListenerStatusCard(")
        )

        assertTrue(
            "The compact search clear action should only be visible while a query is active.",
            searchControlSource.contains("if (searchQuery.isNotBlank())")
        )
        assertTrue(
            "The clear action must reset the same query state used by filtering instead of keeping separate input state.",
            searchControlSource.contains(".clickable { onSearchQueryChange(\"\") }")
        )
        assertTrue(
            "The compact search clear action should render inside the same short search control, not as a separate header row.",
            searchControlSource.indexOf("Row(") <
                searchControlSource.indexOf("BasicTextField(") &&
                searchControlSource.indexOf("BasicTextField(") <
                    searchControlSource.indexOf("text = \"지우기\"")
        )
    }

    @Test
    fun viewModeControlUsesThinSegmentedControlWhileKeepingModeSwitching() {
        val source = readPushRecorderScreenSource()

        assertTrue(
            "View mode switching should use a thinner segmented control in the header.",
            source.contains("ViewModeSegmentedControl(")
        )
        assertTrue(
            "The segmented control should preserve a 48dp touch target instead of using an inaccessible 30dp target.",
            source.contains(".minimumInteractiveComponentSize()") &&
                source.contains(".height(48.dp)")
        )
        assertTrue(
            "The segmented control should avoid extra inner chrome in the header.",
            source.contains(".padding(1.dp)") &&
                source.contains("Arrangement.spacedBy(1.dp)")
        )
        assertTrue(
            "Each segment should use compact label text and narrow side padding.",
            source.contains(".padding(horizontal = 6.dp)") &&
                source.contains("MaterialTheme.typography.labelMedium")
        )
        assertTrue(
            "The all-notifications segment must preserve the existing ALL mode callback.",
            source.contains("onViewModeChange(NotificationViewMode.ALL)")
        )
        assertTrue(
            "The grouped segment must preserve the existing GROUPED mode callback.",
            source.contains("onViewModeChange(NotificationViewMode.GROUPED)")
        )
        assertTrue(
            "The view mode header should use compact segments rather than full-height Material buttons.",
            !source.contains("ButtonDefaults.buttonColors") &&
                !source.contains("private fun ViewModeButton(")
        )
    }

    @Test
    fun compactHeaderKeepsRouteViewModeSetterWiring() {
        val source = readPushRecorderScreenSource()
        val routeSource = source.substring(
            source.indexOf("PushRecorderScreen("),
            source.indexOf("content = {")
        )
        val screenSource = source.substring(
            source.indexOf("private fun PushRecorderScreen("),
            source.indexOf("@Composable\nprivate fun PushRecorderHeader(")
        )
        val headerSource = source.substring(
            source.indexOf("private fun PushRecorderHeader("),
            source.indexOf("@Composable\nprivate fun CompactSearchControl(")
        )

        assertTrue(
            "Route-level view-mode switching must still call the ViewModel setter after the compact header change.",
            routeSource.contains("onViewModeChange = viewModel::setViewMode")
        )
        assertTrue(
            "The screen container must pass the route view-mode callback into the compact header.",
            screenSource.contains("onViewModeChange = onViewModeChange")
        )
        assertTrue(
            "The compact header must pass the same view-mode callback into the segmented control.",
            headerSource.contains("ViewModeSegmentedControl(") &&
                headerSource.contains("onViewModeChange = onViewModeChange")
        )
    }

    @Test
    fun captureStatusDefaultsToFloatingButtonAndRestoresInlineCard() {
        val source = readPushRecorderScreenSource()
        val routeSource = source.substring(
            source.indexOf("val allListState = rememberLazyListState()"),
            source.indexOf("selectedNotificationDetail?.let")
        )
        val screenSource = source.substring(
            source.indexOf("private fun PushRecorderScreen("),
            source.indexOf("@Composable\nprivate fun PushRecorderHeader(")
        )
        val headerSource = source.substring(
            source.indexOf("private fun PushRecorderHeader("),
            source.indexOf("@Composable\nprivate fun CompactSearchControl(")
        )

        assertTrue(
            "Route should keep separate LazyListState instances so mode/package switches do not inherit stale scroll positions.",
            routeSource.contains("val allListState = rememberLazyListState()") &&
                routeSource.contains("val groupedListState = rememberLazyListState()") &&
                routeSource.contains("val packageListState = rememberLazyListState()")
        )
        assertTrue(
            "Tapping the floating status button should expand the inline card before scrolling the active list to the top.",
            routeSource.contains("viewModel.setStatusCardCollapsed(false)") &&
                routeSource.contains("uiState.selectedPackageName != null -> packageListState") &&
                routeSource.contains("uiState.viewMode == NotificationViewMode.GROUPED -> groupedListState") &&
                routeSource.contains("else -> allListState") &&
                routeSource.contains(".animateScrollToItem(0)") &&
                routeSource.indexOf("viewModel.setStatusCardCollapsed(false)") <
                    routeSource.indexOf(".animateScrollToItem(0)")
        )
        assertTrue(
            "The floating status button must be hidden while the notification detail sheet is open.",
            screenSource.contains("if (uiState.isStatusCardCollapsed && uiState.selectedNotification == null)")
        )
        assertTrue(
            "The inline status card should only consume header height while the user has restored it.",
            headerSource.contains("if (!uiState.isStatusCardCollapsed)") &&
                headerSource.contains("ListenerStatusCard(")
        )
        assertTrue(
            "The restored inline card should expose an action to collapse itself back into the FAB.",
            headerSource.contains("onCollapseStatusCard = onCollapseStatusCard")
        )
    }

    @Test
    fun floatingStatusButtonShowsShortAccessibleState() {
        val source = readPushRecorderScreenSource()
        val floatingButtonSource = source.substring(
            source.indexOf("private fun ListenerStatusFloatingButton("),
            source.indexOf("internal data class ListenerStatusUiState(")
        )
        val statusMappingSource = source.substring(
            source.indexOf("internal fun NotificationListenerStatus.toListenerStatusUiState()"),
            source.indexOf("@Composable\nprivate fun ViewModeSegmentedControl(")
        )

        assertTrue(
            "The collapsed capture status should render as a fixed Material FAB instead of another header row.",
            floatingButtonSource.contains("FloatingActionButton(")
        )
        assertTrue(
            "The FAB must expose a readable accessibility label describing the current status and restore action.",
            floatingButtonSource.contains("contentDescription = uiState.floatingAccessibilityLabel")
        )
        assertTrue(
            "The FAB should show compact labels for active, waiting, disabled, and error states.",
            statusMappingSource.contains("floatingLabel = when") &&
                statusMappingSource.contains("\"기록 중\"") &&
                statusMappingSource.contains("\"대기 중\"") &&
                statusMappingSource.contains("\"꺼짐\"") &&
                statusMappingSource.contains("\"오류\"")
        )
        assertTrue(
            "The FAB should use error colors when the listener reports a recent error.",
            floatingButtonSource.contains("uiState.hasError -> colorScheme.errorContainer") &&
                floatingButtonSource.contains("uiState.hasError -> colorScheme.onErrorContainer")
        )
    }

    @Test
    fun thinnedHeaderKeepsSelectedPackageDrillInControls() {
        val source = readPushRecorderScreenSource()
        val headerSource = source.substring(
            source.indexOf("private fun PushRecorderHeader("),
            source.indexOf("@Composable\nprivate fun CompactSearchControl(")
        )

        assertTrue(
            "The thin header must keep the grouped drill-in state visible as a selected package header.",
            headerSource.contains("if (uiState.selectedPackageName == null)") &&
                headerSource.contains("ViewModeSegmentedControl(") &&
                headerSource.contains("SelectedPackageHeader(")
        )
        assertTrue(
            "Selected package drill-in should keep using the current selected package and app info after the header was thinned.",
            headerSource.contains("packageName = uiState.selectedPackageName") &&
                headerSource.contains("selectedAppInfo = selectedAppInfo") &&
                headerSource.contains("onClearSelectedPackage = onClearSelectedPackage")
        )
        assertFalse(
            "Grouped drill-in must not clear package selection just because the compact mode switch is hidden.",
            headerSource.contains("onViewModeChange = onClearSelectedPackage")
        )
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
}
