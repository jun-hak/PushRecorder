package com.example.pushrecorder.ui.appinfo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.example.pushrecorder.appinfo.AppDisplayInfo

@Composable
fun rememberAppDisplayInfo(
    packageName: String?,
    resolveAppInfo: suspend (String) -> AppDisplayInfo
): State<AppDisplayInfo?> {
    val fallbackInfo = remember(packageName) {
        packageName?.let(AppDisplayInfo::fallback)
    }
    val currentResolver by rememberUpdatedState(resolveAppInfo)

    return produceState(
        initialValue = fallbackInfo,
        packageName
    ) {
        value = fallbackInfo
        value = packageName?.let { currentPackage ->
            currentResolver(currentPackage)
        }
    }
}
