package com.example.pushrecorder.appinfo

import android.graphics.drawable.Drawable

data class AppDisplayInfo(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val isResolved: Boolean
) {
    companion object {
        fun fallback(packageName: String): AppDisplayInfo {
            return AppDisplayInfo(
                packageName = packageName,
                label = packageName,
                icon = null,
                isResolved = false
            )
        }
    }
}
