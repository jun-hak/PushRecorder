package com.example.pushrecorder.appinfo

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.LruCache
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppInfoResolver @Inject constructor(
    @ApplicationContext context: Context
) : AppInfoSource {
    private val packageManager = context.packageManager
    private val cache = LruCache<String, AppDisplayInfo>(MAX_CACHE_SIZE)

    override fun resolve(packageName: String): AppDisplayInfo {
        synchronized(cache) {
            cache.get(packageName)?.let { return it }
        }

        val displayInfo = load(packageName)
        synchronized(cache) {
            cache.put(packageName, displayInfo)
        }
        return displayInfo
    }

    override fun invalidate(packageName: String) {
        synchronized(cache) {
            cache.remove(packageName)
        }
    }

    override fun installedApps(): List<AppDisplayInfo> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launcherPackages = packageManager
            .queryIntentActivities(
                launcherIntent,
                PackageManager.ResolveInfoFlags.of(0)
            )
            .mapNotNull { resolveInfo -> resolveInfo.activityInfo?.packageName }

        val installedPackages = packageManager
            .getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
            .map { appInfo -> appInfo.packageName }

        return (launcherPackages + installedPackages)
            .distinct()
            .map(::resolve)
            .sortedBy { displayInfo -> displayInfo.label.lowercase() }
    }

    private fun load(packageName: String): AppDisplayInfo {
        return runCatching {
            val appInfo = packageManager.getApplicationInfo(
                packageName,
                PackageManager.ApplicationInfoFlags.of(0)
            )
            AppDisplayInfo(
                packageName = packageName,
                label = packageManager.getApplicationLabel(appInfo).toString(),
                icon = packageManager.getApplicationIcon(appInfo),
                isResolved = true
            )
        }.getOrElse {
            AppDisplayInfo.fallback(packageName)
        }
    }

    private companion object {
        private const val MAX_CACHE_SIZE = 128
    }
}
