package com.example.pushrecorder.appinfo

import com.example.pushrecorder.data.AppRecordDao
import com.example.pushrecorder.data.AppRecordEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppRegistryRepository @Inject constructor(
    private val appRecordDao: AppRecordDao,
    private val appInfoSource: AppInfoSource
) {
    suspend fun packageSnapshot(packageName: String): AppDisplayInfo {
        val displayInfo = appInfoSource.resolve(packageName)
        if (displayInfo.isResolved) {
            return displayInfo
        }

        val storedRecord = appRecordDao.getAppRecord(packageName)
        return storedRecord?.toDisplayInfo(isResolved = false) ?: displayInfo
    }

    suspend fun recordInstalledPackage(
        packageName: String,
        observedAt: Long = System.currentTimeMillis()
    ) {
        appInfoSource.invalidate(packageName)
        val displayInfo = appInfoSource.resolve(packageName)
        val existing = appRecordDao.getAppRecord(packageName)
        val label = when {
            displayInfo.isResolved -> displayInfo.label
            !existing?.label.isNullOrBlank() -> existing.label
            else -> packageName
        }

        appRecordDao.upsert(
            AppRecordEntity(
                packageName = packageName,
                label = label,
                isInstalled = true,
                firstSeenAt = existing?.firstSeenAt ?: observedAt,
                lastSeenAt = maxOf(existing?.lastSeenAt ?: 0L, observedAt),
                lastInstalledAt = observedAt,
                lastRemovedAt = existing?.lastRemovedAt ?: 0L
            )
        )
    }

    suspend fun recordRemovedPackage(
        packageName: String,
        observedAt: Long = System.currentTimeMillis()
    ) {
        appInfoSource.invalidate(packageName)
        val existing = appRecordDao.getAppRecord(packageName)

        appRecordDao.upsert(
            AppRecordEntity(
                packageName = packageName,
                label = existing?.label?.takeIf { it.isNotBlank() } ?: packageName,
                isInstalled = false,
                firstSeenAt = existing?.firstSeenAt ?: observedAt,
                lastSeenAt = maxOf(existing?.lastSeenAt ?: 0L, observedAt),
                lastInstalledAt = existing?.lastInstalledAt ?: 0L,
                lastRemovedAt = observedAt
            )
        )
    }

    suspend fun recordObservedPackage(
        packageName: String,
        observedAt: Long = System.currentTimeMillis()
    ) {
        val displayInfo = appInfoSource.resolve(packageName)
        val existing = appRecordDao.getAppRecord(packageName)
        val label = when {
            displayInfo.isResolved -> displayInfo.label
            !existing?.label.isNullOrBlank() -> existing.label
            else -> packageName
        }

        appRecordDao.upsert(
            AppRecordEntity(
                packageName = packageName,
                label = label,
                isInstalled = true,
                firstSeenAt = existing?.firstSeenAt ?: observedAt,
                lastSeenAt = maxOf(existing?.lastSeenAt ?: 0L, observedAt),
                lastInstalledAt = observedLastInstalledAt(existing, observedAt),
                lastRemovedAt = existing?.lastRemovedAt ?: 0L
            )
        )
    }

    suspend fun syncInstalledApps(
        observedAt: Long = System.currentTimeMillis()
    ): Int {
        val installedApps = appInfoSource.installedApps()
        val installedPackages = installedApps.mapTo(mutableSetOf()) { app ->
            app.packageName
        }

        installedApps.forEach { displayInfo ->
            upsertInstalledApp(displayInfo, observedAt)
        }

        var retainedInstalledCount = 0
        appRecordDao.getInstalledAppRecords()
            .filter { record -> record.packageName !in installedPackages }
            .forEach { record ->
                val displayInfo = appInfoSource.resolve(record.packageName)
                if (displayInfo.isResolved) {
                    retainedInstalledCount += 1
                    upsertInstalledApp(displayInfo, observedAt)
                } else {
                    appRecordDao.upsert(
                        record.copy(
                            isInstalled = false,
                            lastSeenAt = maxOf(record.lastSeenAt, observedAt),
                            lastRemovedAt = observedAt
                        )
                    )
                }
            }

        return installedApps.size + retainedInstalledCount
    }

    suspend fun resolveDisplayInfo(packageName: String): AppDisplayInfo {
        return packageSnapshot(packageName)
    }

    private fun AppRecordEntity.toDisplayInfo(isResolved: Boolean): AppDisplayInfo {
        return AppDisplayInfo(
            packageName = packageName,
            label = label.takeIf { it.isNotBlank() } ?: packageName,
            icon = null,
            isResolved = isResolved
        )
    }

    private suspend fun upsertInstalledApp(
        displayInfo: AppDisplayInfo,
        observedAt: Long
    ) {
        val existing = appRecordDao.getAppRecord(displayInfo.packageName)
        val label = when {
            displayInfo.isResolved -> displayInfo.label
            !existing?.label.isNullOrBlank() -> existing.label
            else -> displayInfo.packageName
        }

        appRecordDao.upsert(
            AppRecordEntity(
                packageName = displayInfo.packageName,
                label = label,
                isInstalled = true,
                firstSeenAt = existing?.firstSeenAt ?: observedAt,
                lastSeenAt = maxOf(existing?.lastSeenAt ?: 0L, observedAt),
                lastInstalledAt = observedLastInstalledAt(existing, observedAt),
                lastRemovedAt = existing?.lastRemovedAt ?: 0L
            )
        )
    }

    private fun observedLastInstalledAt(
        existing: AppRecordEntity?,
        observedAt: Long
    ): Long {
        return when {
            existing == null -> observedAt
            !existing.isInstalled -> observedAt
            existing.lastInstalledAt > 0L -> existing.lastInstalledAt
            else -> observedAt
        }
    }
}
