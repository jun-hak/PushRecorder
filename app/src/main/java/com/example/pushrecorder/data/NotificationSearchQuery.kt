package com.example.pushrecorder.data

object NotificationSearchQuery {
    fun fromUserInput(query: String): String {
        return escapeLikeTerm(query)
    }

    fun escapeLikeTerm(query: String): String {
        return buildString(query.length) {
            query.forEach { character ->
                when (character) {
                    LIKE_ESCAPE, '%', '_' -> {
                        append(LIKE_ESCAPE)
                        append(character)
                    }
                    else -> append(character)
                }
            }
        }
    }

    const val LIKE_ESCAPE = '\\'
}
