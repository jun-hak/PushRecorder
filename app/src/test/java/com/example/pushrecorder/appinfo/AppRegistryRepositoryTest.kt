package com.example.pushrecorder.appinfo

import com.example.pushrecorder.data.AppRecordEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRegistryRepositoryTest {
    private val appRecordDao = FakeAppRecordDao()
    private val appInfoSource = FakeAppInfoSource()
    private val repository = AppRegistryRepository(appRecordDao, appInfoSource)

    @Test
    fun recordInstalledPackage_marksInstalledEvenWhenLabelResolveFails() = runTest {
        repository.recordInstalledPackage(
            packageName = "com.example.installed",
            observedAt = 1_000L
        )

        val record = appRecordDao.records.getValue("com.example.installed")
        assertTrue(record.isInstalled)
        assertEquals("com.example.installed", record.label)
        assertEquals(1_000L, record.firstSeenAt)
        assertEquals(1_000L, record.lastSeenAt)
        assertEquals(1_000L, record.lastInstalledAt)
        assertEquals(listOf("com.example.installed"), appInfoSource.invalidatedPackages)
    }

    @Test
    fun recordRemovedPackage_onlyMarksAppRecordRemoved() = runTest {
        appRecordDao.records["com.example.removed"] = appRecord(
            packageName = "com.example.removed",
            label = "Removed App",
            isInstalled = true,
            firstSeenAt = 100L,
            lastSeenAt = 500L,
            lastInstalledAt = 100L
        )

        repository.recordRemovedPackage(
            packageName = "com.example.removed",
            observedAt = 1_000L
        )

        val record = appRecordDao.records.getValue("com.example.removed")
        assertFalse(record.isInstalled)
        assertEquals("Removed App", record.label)
        assertEquals(100L, record.firstSeenAt)
        assertEquals(1_000L, record.lastSeenAt)
        assertEquals(100L, record.lastInstalledAt)
        assertEquals(1_000L, record.lastRemovedAt)
        assertEquals(listOf("com.example.removed"), appInfoSource.invalidatedPackages)
    }

    @Test
    fun resolveDisplayInfo_doesNotWriteToAppRegistry() = runTest {
        appRecordDao.records["com.example.ui"] = appRecord(
            packageName = "com.example.ui",
            label = "Stored App",
            isInstalled = false
        )

        val displayInfo = repository.resolveDisplayInfo("com.example.ui")

        assertEquals("Stored App", displayInfo.label)
        assertFalse(displayInfo.isResolved)
        assertEquals(0, appRecordDao.upsertCount)
    }

    @Test
    fun recordObservedPackage_doesNotMoveLastSeenAtBackwards() = runTest {
        appRecordDao.records["com.example.observed"] = appRecord(
            packageName = "com.example.observed",
            label = "Observed App",
            isInstalled = true,
            firstSeenAt = 100L,
            lastSeenAt = 2_000L,
            lastInstalledAt = 100L
        )

        repository.recordObservedPackage(
            packageName = "com.example.observed",
            observedAt = 1_000L
        )

        val record = appRecordDao.records.getValue("com.example.observed")
        assertTrue(record.isInstalled)
        assertEquals(2_000L, record.lastSeenAt)
        assertEquals(100L, record.lastInstalledAt)
    }

    @Test
    fun recordObservedPackage_usesResolvedLabelWhenAvailable() = runTest {
        appInfoSource.setResolved(
            packageName = "com.example.resolved",
            label = "Resolved App"
        )

        repository.recordObservedPackage(
            packageName = "com.example.resolved",
            observedAt = 1_000L
        )

        val record = appRecordDao.records.getValue("com.example.resolved")
        assertEquals("Resolved App", record.label)
        assertTrue(record.isInstalled)
    }

    @Test
    fun recordObservedPackage_updatesLastInstalledAtWhenAppWasNotInstalled() = runTest {
        appRecordDao.records["com.example.resurrected"] = appRecord(
            packageName = "com.example.resurrected",
            label = "Resurrected App",
            isInstalled = false,
            firstSeenAt = 100L,
            lastSeenAt = 5_000L,
            lastInstalledAt = 300L,
            lastRemovedAt = 4_000L
        )

        repository.recordObservedPackage(
            packageName = "com.example.resurrected",
            observedAt = 1_000L
        )

        val record = appRecordDao.records.getValue("com.example.resurrected")
        assertTrue(record.isInstalled)
        assertEquals(100L, record.firstSeenAt)
        assertEquals(5_000L, record.lastSeenAt)
        assertEquals(1_000L, record.lastInstalledAt)
        assertEquals(4_000L, record.lastRemovedAt)
    }

    @Test
    fun recordObservedPackage_updatesZeroLastInstalledAtForInstalledApp() = runTest {
        appRecordDao.records["com.example.zero"] = appRecord(
            packageName = "com.example.zero",
            label = "Zero Install App",
            isInstalled = true,
            firstSeenAt = 100L,
            lastSeenAt = 5_000L,
            lastInstalledAt = 0L
        )

        repository.recordObservedPackage(
            packageName = "com.example.zero",
            observedAt = 1_000L
        )

        val record = appRecordDao.records.getValue("com.example.zero")
        assertTrue(record.isInstalled)
        assertEquals(100L, record.firstSeenAt)
        assertEquals(5_000L, record.lastSeenAt)
        assertEquals(1_000L, record.lastInstalledAt)
    }

    @Test
    fun syncInstalledApps_insertsNewlyDiscoveredApps() = runTest {
        appInfoSource.setInstalledApps(
            appInfoSource.appDisplayInfo(
                packageName = "com.example.discovered",
                label = "Discovered App"
            )
        )

        val syncedCount = repository.syncInstalledApps(observedAt = 1_000L)

        assertEquals(1, syncedCount)
        assertEquals(1, appRecordDao.upsertCount)
        val record = appRecordDao.records.getValue("com.example.discovered")
        assertEquals("com.example.discovered", record.packageName)
        assertEquals("Discovered App", record.label)
        assertTrue(record.isInstalled)
        assertEquals(1_000L, record.firstSeenAt)
        assertEquals(1_000L, record.lastSeenAt)
        assertEquals(1_000L, record.lastInstalledAt)
        assertEquals(0L, record.lastRemovedAt)
    }

    @Test
    fun syncInstalledApps_updatesExistingInstalledAppMetadata() = runTest {
        appRecordDao.records["com.example.existing"] = appRecord(
            packageName = "com.example.existing",
            label = "Old Label",
            isInstalled = true,
            firstSeenAt = 100L,
            lastSeenAt = 500L,
            lastInstalledAt = 200L,
            lastRemovedAt = 0L
        )
        appInfoSource.setInstalledApps(
            appInfoSource.appDisplayInfo(
                packageName = "com.example.existing",
                label = "Updated Label"
            )
        )

        val syncedCount = repository.syncInstalledApps(observedAt = 1_000L)

        assertEquals(1, syncedCount)
        assertEquals(1, appRecordDao.upsertCount)
        val record = appRecordDao.records.getValue("com.example.existing")
        assertEquals("com.example.existing", record.packageName)
        assertEquals("Updated Label", record.label)
        assertTrue(record.isInstalled)
        assertEquals(100L, record.firstSeenAt)
        assertEquals(1_000L, record.lastSeenAt)
        assertEquals(200L, record.lastInstalledAt)
        assertEquals(0L, record.lastRemovedAt)
    }

    @Test
    fun syncInstalledApps_backfillsVisibleInstalledAppsWithoutMovingLastSeenBackwards() = runTest {
        appRecordDao.records["com.example.old"] = appRecord(
            packageName = "com.example.old",
            label = "Old Label",
            isInstalled = false,
            firstSeenAt = 100L,
            lastSeenAt = 5_000L,
            lastInstalledAt = 300L,
            lastRemovedAt = 4_000L
        )
        appInfoSource.setInstalledApps(
            appInfoSource.appDisplayInfo(
                packageName = "com.example.old",
                label = "New Label"
            ),
            appInfoSource.appDisplayInfo(
                packageName = "com.example.new",
                label = "New App"
            )
        )

        val syncedCount = repository.syncInstalledApps(observedAt = 1_000L)

        assertEquals(2, syncedCount)
        val oldRecord = appRecordDao.records.getValue("com.example.old")
        assertTrue(oldRecord.isInstalled)
        assertEquals("New Label", oldRecord.label)
        assertEquals(100L, oldRecord.firstSeenAt)
        assertEquals(5_000L, oldRecord.lastSeenAt)
        assertEquals(1_000L, oldRecord.lastInstalledAt)
        assertEquals(4_000L, oldRecord.lastRemovedAt)

        val newRecord = appRecordDao.records.getValue("com.example.new")
        assertTrue(newRecord.isInstalled)
        assertEquals("New App", newRecord.label)
        assertEquals(1_000L, newRecord.firstSeenAt)
        assertEquals(1_000L, newRecord.lastSeenAt)
        assertEquals(1_000L, newRecord.lastInstalledAt)
        assertEquals(0L, newRecord.lastRemovedAt)
    }

    @Test
    fun syncInstalledApps_marksMissingInstalledRecordAsRemovedWithoutDeletingHistory() = runTest {
        appRecordDao.records["com.example.missed.remove"] = appRecord(
            packageName = "com.example.missed.remove",
            label = "Missed Remove",
            isInstalled = true,
            firstSeenAt = 100L,
            lastSeenAt = 500L,
            lastInstalledAt = 100L
        )
        appInfoSource.setInstalledApps()

        val syncedCount = repository.syncInstalledApps(observedAt = 1_000L)

        assertEquals(0, syncedCount)
        val record = appRecordDao.records.getValue("com.example.missed.remove")
        assertFalse(record.isInstalled)
        assertEquals("Missed Remove", record.label)
        assertEquals(100L, record.firstSeenAt)
        assertEquals(1_000L, record.lastSeenAt)
        assertEquals(100L, record.lastInstalledAt)
        assertEquals(1_000L, record.lastRemovedAt)
    }

    @Test
    fun syncInstalledApps_retainsResolvableNonLauncherObservedPackage() = runTest {
        appRecordDao.records["com.example.nonlauncher"] = appRecord(
            packageName = "com.example.nonlauncher",
            label = "Old Non Launcher",
            isInstalled = true,
            firstSeenAt = 100L,
            lastSeenAt = 500L,
            lastInstalledAt = 100L
        )
        appInfoSource.setResolved(
            packageName = "com.example.nonlauncher",
            label = "Resolved Non Launcher"
        )
        appInfoSource.setInstalledApps()

        val syncedCount = repository.syncInstalledApps(observedAt = 1_000L)

        assertEquals(1, syncedCount)
        val record = appRecordDao.records.getValue("com.example.nonlauncher")
        assertTrue(record.isInstalled)
        assertEquals("Resolved Non Launcher", record.label)
        assertEquals(1_000L, record.lastSeenAt)
        assertEquals(100L, record.lastInstalledAt)
        assertEquals(0L, record.lastRemovedAt)
    }

    private fun appRecord(
        packageName: String,
        label: String,
        isInstalled: Boolean,
        firstSeenAt: Long = 0L,
        lastSeenAt: Long = 0L,
        lastInstalledAt: Long = 0L,
        lastRemovedAt: Long = 0L
    ): AppRecordEntity {
        return AppRecordEntity(
            packageName = packageName,
            label = label,
            isInstalled = isInstalled,
            firstSeenAt = firstSeenAt,
            lastSeenAt = lastSeenAt,
            lastInstalledAt = lastInstalledAt,
            lastRemovedAt = lastRemovedAt
        )
    }
}
