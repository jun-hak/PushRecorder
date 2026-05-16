package com.example.pushrecorder.service

internal sealed interface ActiveNotificationLookupResult<out T> {
    data class Success<T>(val activeNotifications: List<T>) : ActiveNotificationLookupResult<T>
    data object Retry : ActiveNotificationLookupResult<Nothing>
}

internal object ActiveNotificationLookup {
    fun <T> read(
        readActiveNotifications: () -> List<T>?,
        onNull: () -> Unit = {},
        onError: (Throwable) -> Unit = {}
    ): ActiveNotificationLookupResult<T> {
        return runCatching {
            readActiveNotifications()
        }.fold(
            onSuccess = { activeNotifications ->
                if (activeNotifications == null) {
                    onNull()
                    ActiveNotificationLookupResult.Retry
                } else {
                    ActiveNotificationLookupResult.Success(activeNotifications)
                }
            },
            onFailure = { error ->
                onError(error)
                ActiveNotificationLookupResult.Retry
            }
        )
    }
}
