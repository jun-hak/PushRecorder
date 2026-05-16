package com.example.pushrecorder.appinfo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.Intent.EXTRA_REPLACING
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class AppPackageReceiver : BroadcastReceiver() {
    @Inject
    lateinit var appRegistryRepository: AppRegistryRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (!intent.isSupportedPackageEvent()) {
            return
        }

        val packageName = intent.data?.schemeSpecificPart ?: return
        val pendingResult = goAsync()

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                runCatching {
                    when (intent.action) {
                        Intent.ACTION_PACKAGE_ADDED -> {
                            if (!intent.getBooleanExtra(EXTRA_REPLACING, false)) {
                                appRegistryRepository.recordInstalledPackage(packageName)
                            }
                        }

                        Intent.ACTION_PACKAGE_REPLACED -> {
                            appRegistryRepository.recordInstalledPackage(packageName)
                        }

                        Intent.ACTION_PACKAGE_REMOVED -> {
                            if (!intent.getBooleanExtra(EXTRA_REPLACING, false)) {
                                appRegistryRepository.recordRemovedPackage(packageName)
                            }
                        }
                    }
                }.onFailure { error ->
                    Log.e(TAG, "Failed to record package event: ${intent.action} $packageName", error)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun Intent.isSupportedPackageEvent(): Boolean {
        return data?.scheme == "package" &&
            action in SUPPORTED_ACTIONS
    }

    private companion object {
        private const val TAG = "AppPackageReceiver"
        private val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_REMOVED,
            Intent.ACTION_PACKAGE_REPLACED
        )
    }
}
