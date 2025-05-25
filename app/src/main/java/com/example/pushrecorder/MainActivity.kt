package com.example.pushrecorder

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.example.pushrecorder.data.NotificationDao
import com.example.pushrecorder.data.NotificationEntity
import com.example.pushrecorder.ui.screens.AllNotificationsView
import com.example.pushrecorder.ui.screens.GroupedNotificationsView
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import javax.inject.Inject

enum class ViewMode {
    ALL, GROUPED
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var notificationDao: NotificationDao

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableEdgeToEdge()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val notifications = remember { mutableStateListOf<NotificationEntity>() }
                    var viewMode by remember { mutableStateOf(ViewMode.ALL) }
                    
                    LaunchedEffect(Unit) {
                        notificationDao.getAllNotifications().collectLatest { newNotifications ->
                            notifications.clear()
                            notifications.addAll(newNotifications)
                        }
                    }

                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        containerColor = Color.Transparent
                    ) { paddingValues ->
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(paddingValues)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            ) {
                                Button(
                                    onClick = { requestNotificationListenerPermission() },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("노티피케이션 접근 권한 요청")
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = { viewMode = ViewMode.ALL },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (viewMode == ViewMode.ALL) 
                                                MaterialTheme.colorScheme.primary 
                                            else 
                                                MaterialTheme.colorScheme.secondary
                                        )
                                    ) {
                                        Text("전체 보기")
                                    }
                                    Button(
                                        onClick = { viewMode = ViewMode.GROUPED },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (viewMode == ViewMode.GROUPED) 
                                                MaterialTheme.colorScheme.primary 
                                            else 
                                                MaterialTheme.colorScheme.secondary
                                        )
                                    ) {
                                        Text("앱별 보기")
                                    }
                                }
                            }

                            when (viewMode) {
                                ViewMode.ALL -> AllNotificationsView(notifications)
                                ViewMode.GROUPED -> GroupedNotificationsView(notifications)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun requestNotificationListenerPermission() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        startActivity(intent)
    }
}