package com.example.pushrecorder.appinfo

class FakeAppInfoSource(
    private val resolvedApps: MutableMap<String, AppDisplayInfo> = mutableMapOf()
) : AppInfoSource {
    val invalidatedPackages = mutableListOf<String>()
    val resolvedPackages = mutableListOf<String>()
    private val installedApps = mutableListOf<AppDisplayInfo>()

    override fun resolve(packageName: String): AppDisplayInfo {
        resolvedPackages += packageName
        return resolvedApps[packageName] ?: AppDisplayInfo.fallback(packageName)
    }

    override fun invalidate(packageName: String) {
        invalidatedPackages += packageName
    }

    override fun installedApps(): List<AppDisplayInfo> {
        return installedApps.toList()
    }

    fun setInstalledApps(vararg apps: AppDisplayInfo) {
        installedApps.clear()
        installedApps += apps
    }

    fun setResolved(
        packageName: String,
        label: String,
        isResolved: Boolean = true
    ) {
        resolvedApps[packageName] = appDisplayInfo(
            packageName = packageName,
            label = label,
            isResolved = isResolved
        )
    }

    fun appDisplayInfo(
        packageName: String,
        label: String,
        isResolved: Boolean = true
    ): AppDisplayInfo {
        return AppDisplayInfo(
            packageName = packageName,
            label = label,
            icon = null,
            isResolved = isResolved
        )
    }
}
