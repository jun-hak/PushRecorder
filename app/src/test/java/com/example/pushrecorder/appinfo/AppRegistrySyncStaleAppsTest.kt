package com.example.pushrecorder.appinfo

import com.example.pushrecorder.data.AppRecordEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRegistrySyncStaleAppsTest {
    private val appRecordDao = FakeAppRecordDao()
    private val appInfoSource = FakeAppInfoSource()
    private val repository = AppRegistryRepository(appRecordDao, appInfoSource)

    @Test
    fun syncInstalledApps_marksStaleInstalledAppRemovedWithoutDeletingRegistryRecord() = runTest {
        appRecordDao.records["com.example.stale"] = appRecord(
            packageName = "com.example.stale",
            label = "Stale App",
            isInstalled = true,
            firstSeenAt = 100L,
            lastSeenAt = 500L,
            lastInstalledAt = 100L,
            lastRemovedAt = 0L
        )
        appInfoSource.setInstalledApps()

        val syncedCount = repository.syncInstalledApps(observedAt = 1_000L)

        assertEquals(0, syncedCount)
        assertEquals(setOf("com.example.stale"), appRecordDao.records.keys)

        val record = appRecordDao.records.getValue("com.example.stale")
        assertFalse(record.isInstalled)
        assertEquals("Stale App", record.label)
        assertEquals(100L, record.firstSeenAt)
        assertEquals(1_000L, record.lastSeenAt)
        assertEquals(100L, record.lastInstalledAt)
        assertEquals(1_000L, record.lastRemovedAt)
    }

    @Test
    fun syncInstalledApps_keepsResolvableStaleAppInstalledWhenMissingFromLauncherList() = runTest {
        appRecordDao.records["com.example.resolvable"] = appRecord(
            packageName = "com.example.resolvable",
            label = "Old Label",
            isInstalled = true,
            firstSeenAt = 100L,
            lastSeenAt = 500L,
            lastInstalledAt = 100L,
            lastRemovedAt = 0L
        )
        appInfoSource.setResolved(
            packageName = "com.example.resolvable",
            label = "Resolved Label"
        )
        appInfoSource.setInstalledApps()

        val syncedCount = repository.syncInstalledApps(observedAt = 1_000L)

        assertEquals(1, syncedCount)
        assertEquals(listOf("com.example.resolvable"), appInfoSource.resolvedPackages)

        val record = appRecordDao.records.getValue("com.example.resolvable")
        assertTrue(record.isInstalled)
        assertEquals("Resolved Label", record.label)
        assertEquals(100L, record.firstSeenAt)
        assertEquals(1_000L, record.lastSeenAt)
        assertEquals(100L, record.lastInstalledAt)
        assertEquals(0L, record.lastRemovedAt)
    }

    private fun appRecord(
        packageName: String,
        label: String,
        isInstalled: Boolean,
        firstSeenAt: Long,
        lastSeenAt: Long,
        lastInstalledAt: Long,
        lastRemovedAt: Long
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
