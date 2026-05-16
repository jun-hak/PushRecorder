package com.example.pushrecorder.ui.screens

import com.example.pushrecorder.service.NotificationListenerStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ListenerStatusUiStateTest {
    @Test
    fun disabledListenerMapsToDisabledUiState() {
        val status = NotificationListenerStatus(
            isListenerEnabled = false,
            isServiceConnected = false
        )

        val uiState = status.toListenerStatusUiState()

        assertEquals("리스너 꺼짐 - 푸시를 캡처하지 못할 수 있음", uiState.title)
        assertEquals("권한: 꺼짐 / 서비스 연결: 꺼짐", uiState.connectionSummary)
        assertEquals("알림 접근 권한 켜기", uiState.settingsButtonText)
        assertEquals(ListenerStatusColorRole.DISABLED, uiState.colorRole)
    }

    @Test
    fun missingListenerPermissionMapsToPermissionMissingUiState() {
        val status = NotificationListenerStatus(
            isListenerEnabled = false,
            isServiceConnected = true
        )

        val uiState = status.toListenerStatusUiState()

        assertEquals("리스너 꺼짐 - 푸시를 캡처하지 못할 수 있음", uiState.title)
        assertEquals("권한: 꺼짐 / 서비스 연결: 켜짐", uiState.connectionSummary)
        assertEquals("알림 접근 권한 켜기", uiState.settingsButtonText)
        assertEquals(ListenerStatusColorRole.DISABLED, uiState.colorRole)
    }

    @Test
    fun enabledListenerWithoutServiceConnectionMapsToWaitingUiState() {
        val status = NotificationListenerStatus(
            isListenerEnabled = true,
            isServiceConnected = false
        )

        val uiState = status.toListenerStatusUiState()

        assertEquals("권한은 켜졌지만 리스너 연결 대기 중", uiState.title)
        assertEquals("권한: 켜짐 / 서비스 연결: 꺼짐", uiState.connectionSummary)
        assertEquals("알림 접근 권한 켜기", uiState.settingsButtonText)
        assertEquals(ListenerStatusColorRole.ENABLED_WAITING, uiState.colorRole)
    }
}
