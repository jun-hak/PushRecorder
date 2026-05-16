package com.example.pushrecorder.data

object NotificationStorageLimits {
    const val MAX_STORED_TITLE_LENGTH = 512
    const val MAX_STORED_TEXT_LENGTH = 4_096

    fun limitTitle(title: String): String {
        return title.limitStoredLength(MAX_STORED_TITLE_LENGTH)
    }

    fun limitText(text: String): String {
        return text.limitStoredLength(MAX_STORED_TEXT_LENGTH)
    }

    private fun String.limitStoredLength(maxLength: Int): String {
        return if (length <= maxLength) this else take(maxLength)
    }
}
