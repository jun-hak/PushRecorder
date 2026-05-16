package com.example.pushrecorder

import android.app.Activity
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.os.Bundle
import android.util.Log
import com.example.pushrecorder.service.NotificationStorageCleanupScheduler
import com.example.pushrecorder.service.NotificationStorageCleanupWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Inject

@HiltAndroidApp
class PushRecorderApplication : Application() {
    @Inject
    lateinit var storageCleanupScheduler: NotificationStorageCleanupScheduler

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        registerStorageCleanupEntryPoint()
        registerScheduledStorageCleanup()
        requestStorageCleanup(force = true)
    }

    private fun registerStorageCleanupEntryPoint() {
        registerActivityLifecycleCallbacks(
            object : ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    requestStorageCleanup()
                }

                override fun onActivityCreated(
                    activity: Activity,
                    savedInstanceState: Bundle?
                ) = Unit

                override fun onActivityResumed(activity: Activity) = Unit

                override fun onActivityPaused(activity: Activity) = Unit

                override fun onActivityStopped(activity: Activity) = Unit

                override fun onActivitySaveInstanceState(
                    activity: Activity,
                    outState: Bundle
                ) = Unit

                override fun onActivityDestroyed(activity: Activity) = Unit
            }
        )
    }

    private fun requestStorageCleanup(force: Boolean = false) {
        storageCleanupScheduler.requestCleanup(
            scope = applicationScope,
            force = force,
            onFailure = { error ->
                Log.e(TAG, "Failed to clean notification storage", error)
            }
        )
    }

    private fun registerScheduledStorageCleanup() {
        NotificationStorageCleanupWorker.enqueuePeriodic(this)
    }

    private companion object {
        private const val TAG = "PushRecorderApplication"
    }
}
