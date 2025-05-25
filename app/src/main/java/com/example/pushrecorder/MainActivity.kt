package com.example.pushrecorder

import android.app.Notification
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.example.pushrecorder.data.NotificationDao
import com.example.pushrecorder.data.NotificationEntity
import com.example.pushrecorder.data.NotificationStatus
import com.example.pushrecorder.data.RemovalReason
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var notificationDao: NotificationDao

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Edge-to-edge 지원 설정
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableEdgeToEdge()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        containerColor = Color.Transparent
                    ) { paddingValues ->
                        MainScreen(
                            onRequestPermission = { requestNotificationListenerPermission() },
                            notifications = remember { mutableStateListOf() },
                            modifier = Modifier.padding(paddingValues)
                        )
                    }
                }
            }
        }

        observeNotifications()
    }

    private fun observeNotifications() {
        lifecycleScope.launch {
            notificationDao.getAllNotifications().collectLatest { notifications ->
                setContent {
                    MaterialTheme {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.background
                        ) {
                            Scaffold(
                                modifier = Modifier.fillMaxSize(),
                                containerColor = Color.Transparent
                            ) { paddingValues ->
                                MainScreen(
                                    onRequestPermission = { requestNotificationListenerPermission() },
                                    notifications = remember { notifications.toMutableStateList() },
                                    modifier = Modifier.padding(paddingValues)
                                )
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

@Composable
fun MainScreen(
    onRequestPermission: () -> Unit,
    notifications: List<NotificationEntity>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Button(
            onClick = onRequestPermission,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("노티피케이션 접근 권한 요청")
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(notifications) { notification ->
                NotificationItem(notification)
            }
        }
    }
}

@Composable
fun NotificationItem(notification: NotificationEntity) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    
    // 상태에 따른 카드 색상 설정
    val cardColors = CardDefaults.cardColors(
        containerColor = when (notification.status) {
            NotificationStatus.POSTED -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
            NotificationStatus.CLICKED -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f)
            NotificationStatus.REMOVED -> when (notification.removalReason) {
                RemovalReason.USER_DISMISSED -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                RemovalReason.AUTO_REMOVED -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
            }
        }
    )
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = cardColors,
        elevation = CardDefaults.cardElevation(
            defaultElevation = 4.dp
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = notification.packageName,
                style = MaterialTheme.typography.labelMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = notification.title,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = notification.text,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            
            // 상태 정보
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "상태: ${notification.status}",
                    style = MaterialTheme.typography.labelSmall
                )
                Text(
                    text = "시간: ${dateFormat.format(notification.timestamp)}",
                    style = MaterialTheme.typography.labelSmall
                )
            }
            
            // 상세 정보
            if (notification.status != NotificationStatus.POSTED) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "제거 이유: ${notification.removalReason}",
                    style = MaterialTheme.typography.labelSmall
                )
                Text(
                    text = "제거까지 걸린 시간: ${notification.timeToRemoval}ms",
                    style = MaterialTheme.typography.labelSmall
                )
            }
            
            // 플래그 정보
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "플래그: ${formatFlags(notification.flags)}",
                style = MaterialTheme.typography.labelSmall
            )
            Text(
                text = "액션 존재: ${notification.hasActions}",
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

private fun formatFlags(flags: Int): String {
    val flagList = mutableListOf<String>()
    if (flags and Notification.FLAG_AUTO_CANCEL != 0) flagList.add("AUTO_CANCEL")
    if (flags and Notification.FLAG_FOREGROUND_SERVICE != 0) flagList.add("FOREGROUND_SERVICE")
    if (flags and Notification.FLAG_GROUP_SUMMARY != 0) flagList.add("GROUP_SUMMARY")
    if (flags and Notification.FLAG_HIGH_PRIORITY != 0) flagList.add("HIGH_PRIORITY")
    if (flags and Notification.FLAG_INSISTENT != 0) flagList.add("INSISTENT")
    if (flags and Notification.FLAG_LOCAL_ONLY != 0) flagList.add("LOCAL_ONLY")
    if (flags and Notification.FLAG_NO_CLEAR != 0) flagList.add("NO_CLEAR")
    if (flags and Notification.FLAG_ONGOING_EVENT != 0) flagList.add("ONGOING_EVENT")
    if (flags and Notification.FLAG_ONLY_ALERT_ONCE != 0) flagList.add("ONLY_ALERT_ONCE")
    return flagList.joinToString(", ")
}