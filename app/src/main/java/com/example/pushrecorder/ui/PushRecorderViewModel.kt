package com.example.pushrecorder.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.example.pushrecorder.appinfo.AppDisplayInfo
import com.example.pushrecorder.appinfo.AppRegistryRepository
import com.example.pushrecorder.data.NotificationEntity
import com.example.pushrecorder.data.NotificationGroupSummary
import com.example.pushrecorder.data.NotificationRepository
import com.example.pushrecorder.service.NotificationListenerStatusRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

enum class NotificationViewMode {
    ALL,
    GROUPED
}

data class PushRecorderUiState(
    val viewMode: NotificationViewMode = NotificationViewMode.ALL,
    val searchQuery: String = "",
    val selectedPackageName: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class PushRecorderViewModel @Inject constructor(
    private val notificationRepository: NotificationRepository,
    private val appRegistryRepository: AppRegistryRepository,
    private val listenerStatusRepository: NotificationListenerStatusRepository,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        PushRecorderUiState(
            viewMode = savedStateHandle.restoreViewMode(),
            searchQuery = savedStateHandle[KEY_SEARCH_QUERY] ?: "",
            selectedPackageName = savedStateHandle[KEY_SELECTED_PACKAGE]
        )
    )
    val uiState = _uiState.asStateFlow()
    val listenerStatus = listenerStatusRepository.status
    private val appInfoCache = ConcurrentHashMap<String, AppDisplayInfo>()

    private val normalizedQuery = uiState
        .map { it.searchQuery.trim() }
        .debounce(SEARCH_DEBOUNCE_MILLIS)
        .distinctUntilChanged()

    private val selectedPackageName = uiState
        .map { it.selectedPackageName }
        .distinctUntilChanged()

    val allNotifications: Flow<PagingData<NotificationEntity>> = normalizedQuery
        .flatMapLatest(notificationRepository::pagedNotifications)
        .cachedIn(viewModelScope)

    val groupedNotifications: Flow<PagingData<NotificationGroupSummary>> = normalizedQuery
        .flatMapLatest(notificationRepository::pagedNotificationGroups)
        .cachedIn(viewModelScope)

    val packageNotifications: Flow<PagingData<NotificationEntity>> =
        combine(selectedPackageName, normalizedQuery) { packageName, query ->
            packageName to query
        }
            .flatMapLatest { (packageName, query) ->
                if (packageName == null) {
                    flowOf(PagingData.empty())
                } else {
                    notificationRepository.pagedNotificationsByPackage(
                        packageName = packageName,
                        query = query
                    )
                }
            }
            .cachedIn(viewModelScope)

    init {
        listenerStatusRepository.refresh()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                notificationRepository.deleteExpiredNotifications()
            }.onFailure {
                listenerStatusRepository.markError("Failed to delete expired notifications")
            }

            runCatching {
                appRegistryRepository.syncInstalledApps()
            }.onSuccess { syncedCount ->
                listenerStatusRepository.markInstalledAppSync(syncedCount)
            }.onFailure {
                listenerStatusRepository.markError("Failed to sync installed apps")
            }
        }
    }

    fun refreshListenerStatus() {
        listenerStatusRepository.refresh()
    }

    fun setViewMode(viewMode: NotificationViewMode) {
        _uiState.update { state ->
            state.copy(viewMode = viewMode)
        }
        savedStateHandle[KEY_VIEW_MODE] = viewMode.name
    }

    fun setSearchQuery(query: String) {
        _uiState.update { state ->
            state.copy(searchQuery = query)
        }
        savedStateHandle[KEY_SEARCH_QUERY] = query
    }

    fun selectPackage(packageName: String) {
        _uiState.update { state ->
            state.copy(selectedPackageName = packageName)
        }
        savedStateHandle[KEY_SELECTED_PACKAGE] = packageName
    }

    fun clearSelectedPackage() {
        _uiState.update { state ->
            state.copy(selectedPackageName = null)
        }
        savedStateHandle.remove<String>(KEY_SELECTED_PACKAGE)
    }

    suspend fun resolveAppInfo(packageName: String): AppDisplayInfo {
        appInfoCache[packageName]?.let { cachedInfo ->
            return cachedInfo
        }

        return withContext(Dispatchers.IO) {
            appRegistryRepository.resolveDisplayInfo(packageName)
        }.also { displayInfo ->
            appInfoCache[packageName] = displayInfo
        }
    }

    private companion object {
        private const val SEARCH_DEBOUNCE_MILLIS = 300L
        private const val KEY_VIEW_MODE = "viewMode"
        private const val KEY_SEARCH_QUERY = "searchQuery"
        private const val KEY_SELECTED_PACKAGE = "selectedPackageName"
    }
}

private fun SavedStateHandle.restoreViewMode(): NotificationViewMode {
    val savedValue = get<String>("viewMode") ?: return NotificationViewMode.ALL
    return runCatching {
        NotificationViewMode.valueOf(savedValue)
    }.getOrDefault(NotificationViewMode.ALL)
}
