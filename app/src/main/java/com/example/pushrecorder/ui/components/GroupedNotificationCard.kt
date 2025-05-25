package com.example.pushrecorder.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.pushrecorder.data.NotificationEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupedNotificationCard(
    packageName: String,
    notifications: List<NotificationEntity>
) {
    var expanded by remember { mutableStateOf(false) }
    
    val latestNotification = remember(notifications) {
        notifications.maxByOrNull { it.timestamp }
    }
    
    val remainingNotifications = remember(notifications) {
        notifications.sortedByDescending { it.timestamp }
            .drop(1)
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 4.dp
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = packageName,
                    style = MaterialTheme.typography.titleMedium
                )
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded) "접기" else "펼치기"
                    )
                }
            }
            
            if (latestNotification != null) {
                Spacer(modifier = Modifier.height(8.dp))
                NotificationItem(
                    notification = latestNotification,
                    showDetails = true
                )
            }
            
            if (remainingNotifications.isNotEmpty()) {
                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                        ) {
                            Spacer(modifier = Modifier.height(8.dp))
                            remainingNotifications.forEach { notification ->
                                NotificationItem(
                                    notification = notification,
                                    showDetails = true
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        }
                    }
                }
            }
        }
    }
} 