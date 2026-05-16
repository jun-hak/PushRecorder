package com.example.pushrecorder.appinfo

interface AppInfoSource {
    fun resolve(packageName: String): AppDisplayInfo
    fun installedApps(): List<AppDisplayInfo>
    fun invalidate(packageName: String)
}
