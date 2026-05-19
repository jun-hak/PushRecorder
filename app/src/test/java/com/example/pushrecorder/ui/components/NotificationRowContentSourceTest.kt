package com.example.pushrecorder.ui.components

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationRowContentSourceTest {
    @Test
    fun notificationItemKeepsPrimaryNotificationContentVisible() {
        val source = readSource("ui/components/NotificationItem.kt")

        assertTrue(
            "Notification rows must still render the captured app label with a stable package-name fallback.",
            source.contains("val appLabel = notification.appLabel.ifBlank { notification.packageName }") &&
                source.contains("text = appLabel")
        )
        assertTrue(
            "Notification rows must still render the captured title, with the existing empty-title fallback.",
            source.contains("text = notification.title.ifBlank")
        )
        assertTrue(
            "Notification rows must still render the captured body text when it is present.",
            source.contains("text = notification.text")
        )
        assertTrue(
            "Notification rows must still render the observed timestamp.",
            source.contains("dateFormat.format(notification.observedAt)")
        )
        assertTrue(
            "Notification rows must still render status metadata.",
            source.contains("StatusBadge(notification.status, notification.removalReason)")
        )
        assertTrue(
            "Notification rows must still render removal timing metadata for terminal events.",
            source.contains("notification.timeToRemoval")
        )
    }

    @Test
    fun notificationItemHandlesActiveRemovedAndMissingRemovalMetadata() {
        val source = readSource("ui/components/NotificationItem.kt")

        assertTrue(
            "Active notifications should keep removal lifecycle metadata out of the default scan path.",
            source.contains("private fun shouldShowLifecycleMetadata(status: NotificationStatus): Boolean") &&
                source.contains("return status != NotificationStatus.POSTED")
        )
        assertTrue(
            "Removed notifications should use removed-state wording instead of a generic terminal label.",
            source.contains("NotificationStatus.REMOVED -> \"제거\"") &&
                source.contains("private fun formatLifecycleTimestampLabel(status: NotificationStatus): String")
        )
        assertTrue(
            "Clicked notifications should not be presented as ordinary removed rows.",
            source.contains("NotificationStatus.CLICKED -> \"클릭\"")
        )
        assertTrue(
            "Rows with missing removal timing metadata should not render a misleading 0ms value.",
            source.contains("if (notification.timeToRemoval > 0L)") &&
                source.contains("} else {\n        \"\"")
        )
        assertTrue(
            "Rows with missing removal timestamps should skip the timestamp line instead of showing a fake removal time.",
            source.contains("if (notification.removedAt > 0)")
        )
    }

    @Test
    fun notificationItemKeepsCompactScanningLayout() {
        val source = readSource("ui/components/NotificationItem.kt")

        assertTrue(
            "Notification rows should keep the compact roomier padding instead of returning to the old 16dp all-around layout.",
            source.contains(".padding(horizontal = 14.dp, vertical = 12.dp)")
        )
        assertTrue(
            "Notification rows should keep one-line app labels for fast portrait scanning.",
            source.contains("text = appLabel") &&
                source.contains("maxLines = 1")
        )
        assertTrue(
            "Notification row titles should stay single-line in the default scan path.",
            source.contains("text = notification.title.ifBlank") &&
                source.contains("maxLines = 1")
        )
        assertTrue(
            "Notification row body previews should remain bounded in the default scan path.",
            source.contains("text = notification.text") &&
                source.contains("maxLines = 2")
        )
        assertFalse(
            "Notification rows should not keep an in-row diagnostics toggle now that full metadata lives in the detail sheet.",
            source.contains("showDiagnostics") ||
                source.contains("진단 정보") ||
                source.contains("NotificationDiagnostics(")
        )
    }

    @Test
    fun notificationItemDoesNotRenderDiagnosticOnlyFieldsInDefaultRow() {
        val source = readSource("ui/components/NotificationItem.kt")

        assertFalse(
            "The default compact row must not render database identity metadata.",
            source.contains("notification.id") ||
                source.contains("notification.notificationKey")
        )
        assertFalse(
            "The default compact row must not require app-info snapshot diagnostics outside the app-label fallback.",
            source.replace("notification.appLabel.ifBlank { notification.packageName }", "")
                .contains("notification.packageName") ||
                source.contains("notification.appInfoResolved")
        )
        assertFalse(
            "The default compact row must not render low-level notification flags or actions.",
            source.contains("notification.flags") ||
                source.contains("notification.hasActions") ||
                source.contains("formatFlags(")
        )
        assertFalse(
            "The default compact row must not render source timestamp or journal diagnostics.",
            source.contains("notification.timestamp") ||
                source.contains("notification.eventJournalId")
        )
    }

    @Test
    fun groupedNotificationCardKeepsCompactPreviewLayout() {
        val source = readSource("ui/components/GroupedNotificationCard.kt")

        assertTrue(
            "Grouped cards should keep compact preview padding for phone portrait browsing.",
            source.contains(".padding(horizontal = 12.dp, vertical = 10.dp)")
        )
        assertTrue(
            "Grouped cards should render an app-level preview instead of nesting the full notification row.",
            source.contains("AppIdentity(") &&
                !source.contains("NotificationItem(")
        )
        assertTrue(
            "Grouped cards should keep the preview title single-line.",
            source.contains("supportingText = latestTitle")
        )
        assertTrue(
            "Grouped cards should not add the resolved package-name line above the latest notification preview.",
            source.contains("showPackageName = false")
        )
        assertTrue(
            "Grouped cards should keep the preview body bounded.",
            source.contains("text = body") &&
                source.contains("maxLines = 2")
        )
    }

    @Test
    fun groupedNotificationCardKeepsSinglePackageSelectionClickTarget() {
        val source = readSource("ui/components/GroupedNotificationCard.kt")

        assertTrue(
            "Grouped package previews must keep the card-level click target that drills into the package.",
            source.contains("Card(\n        onClick = onClick")
        )
        assertFalse(
            "Grouped package previews must not add nested click targets that compete with package selection.",
            source.contains(".clickable(") ||
                source.contains("onNotificationClick") ||
                source.contains("selectNotification")
        )
    }

    @Test
    fun groupedNotificationCardDoesNotKeepDeadSelectedStateRendering() {
        val source = readSource("ui/components/GroupedNotificationCard.kt")

        assertFalse(
            "Grouped package previews should not keep selected-state parameters because selecting a package switches away from the grouped list.",
            source.contains("selected: Boolean = false") ||
                source.contains("val containerColor = if (selected)") ||
                source.contains("defaultElevation = if (selected)")
        )
        assertTrue(
            "Grouped package previews should keep a stable card surface and content color for scanning.",
            source.contains("containerColor = colorScheme.surface") &&
                source.contains("val contentColor = colorScheme.onSurfaceVariant")
        )
    }

    @Test
    fun groupedNotificationCardKeepsLatestNotificationContentForScanning() {
        val source = readSource("ui/components/GroupedNotificationCard.kt")

        assertTrue(
            "Grouped cards must still derive their preview from the latest notification title.",
            source.contains("latestNotification.title.ifBlank")
        )
        assertTrue(
            "Grouped cards must still derive their preview body from the latest notification text.",
            source.contains("latestNotification.text")
        )
        assertTrue(
            "Grouped cards must still show the app identity resolved for the group.",
            source.contains("appInfo = appInfo")
        )
        assertTrue(
            "Grouped cards must still show the latest notification timestamp.",
            source.contains("dateFormat.format(latestNotification.observedAt)")
        )
        assertTrue(
            "Grouped cards must still show latest notification status metadata.",
            source.contains("formatGroupStatus(latestNotification.status)")
        )
        assertTrue(
            "Grouped cards must still show the notification count metadata.",
            source.contains("NotificationCountBadge(count = group.notificationCount)")
        )
    }

    @Test
    fun appIdentityFallsBackToStablePackageLabelWhenAppInfoLabelIsMissing() {
        val source = readSource("ui/components/AppIdentity.kt")

        assertTrue(
            "App identity should never render a blank app label when a partial app-info snapshot has an empty label.",
            source.contains("val displayLabel = appInfo.label.ifBlank { appInfo.packageName }") &&
                source.contains("text = displayLabel")
        )
        assertTrue(
            "Fallback icons and icon descriptions should use the same stable label so missing icons do not break layout.",
            source.contains("contentDescription = \"\$displayLabel 아이콘\"") &&
                source.contains("displayLabel.firstOrNull()?.uppercaseChar()?.toString() ?: \"?\"") &&
                source.contains(".clip(CircleShape)") &&
                source.contains(".background(MaterialTheme.colorScheme.surfaceVariant)")
        )
    }

    @Test
    fun groupedNotificationCardDoesNotRenderDiagnosticOnlyFieldsInPreview() {
        val source = readSource("ui/components/GroupedNotificationCard.kt")

        assertFalse(
            "Grouped previews must not render package identity as scan-path diagnostic metadata.",
            source.contains("latestNotification.packageName")
        )
        assertFalse(
            "Grouped previews must not render notification row identity diagnostics.",
            source.contains("latestNotification.id") ||
                source.contains("latestNotification.notificationKey")
        )
        assertFalse(
            "Grouped previews must not require low-level notification diagnostics.",
            source.contains("latestNotification.flags") ||
                source.contains("latestNotification.hasActions") ||
                source.contains("latestNotification.timestamp") ||
                source.contains("latestNotification.eventJournalId") ||
                source.contains("latestNotification.appInfoResolved")
        )
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
