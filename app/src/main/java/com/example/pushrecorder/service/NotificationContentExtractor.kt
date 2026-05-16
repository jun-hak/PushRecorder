package com.example.pushrecorder.service

import android.app.Notification
import android.os.Bundle
import android.os.Parcelable
import com.example.pushrecorder.data.NotificationStorageLimits

object NotificationContentExtractor {
    const val MAX_STORED_TITLE_LENGTH = NotificationStorageLimits.MAX_STORED_TITLE_LENGTH
    const val MAX_STORED_TEXT_LENGTH = NotificationStorageLimits.MAX_STORED_TEXT_LENGTH

    fun timestamp(
        postTime: Long,
        currentTimeMillis: () -> Long = System::currentTimeMillis
    ): Long {
        return postTime.takeIf { it > 0L } ?: currentTimeMillis()
    }

    fun title(notification: Notification): String {
        val extras = notification.extras
        return titleFromContent(
            TitleContent(
                conversationTitle = extras.readText(Notification.EXTRA_CONVERSATION_TITLE),
                title = extras.readText(Notification.EXTRA_TITLE),
                bigTitle = extras.readText(Notification.EXTRA_TITLE_BIG),
                subText = extras.readText(Notification.EXTRA_SUB_TEXT),
                tickerText = notification.tickerText?.toString()
            )
        )
    }

    internal fun titleFromContent(content: TitleContent): String {
        return firstNonBlank(
            content.conversationTitle,
            content.title,
            content.bigTitle,
            content.subText,
            content.tickerText
        ).let(NotificationStorageLimits::limitTitle)
    }

    fun text(notification: Notification): String {
        val extras = notification.extras
        return textFromContent(
            TextContent(
                bigText = extras.readText(Notification.EXTRA_BIG_TEXT),
                textLines = extras.readTextLines(Notification.EXTRA_TEXT_LINES),
                messages = extras.readMessages(),
                text = extras.readText(Notification.EXTRA_TEXT),
                summaryText = extras.readText(Notification.EXTRA_SUMMARY_TEXT),
                infoText = extras.readText(Notification.EXTRA_INFO_TEXT),
                subText = extras.readText(Notification.EXTRA_SUB_TEXT),
                tickerText = notification.tickerText?.toString()
            )
        )
    }

    internal fun textFromContent(content: TextContent): String {
        return firstNonBlank(
            content.bigText,
            content.textLines,
            content.messages,
            content.text,
            content.summaryText,
            content.infoText,
            content.subText,
            content.tickerText
        ).let(NotificationStorageLimits::limitText)
    }

    internal data class TitleContent(
        val conversationTitle: String? = null,
        val title: String? = null,
        val bigTitle: String? = null,
        val subText: String? = null,
        val tickerText: String? = null
    )

    internal data class TextContent(
        val bigText: String? = null,
        val textLines: String? = null,
        val messages: String? = null,
        val text: String? = null,
        val summaryText: String? = null,
        val infoText: String? = null,
        val subText: String? = null,
        val tickerText: String? = null
    )

    internal data class MessageContent(
        val sender: String?,
        val text: String?
    )

    private fun Bundle.readText(key: String): String {
        return getCharSequence(key)?.toString().orEmpty()
    }

    private fun Bundle.readTextLines(key: String): String {
        return visibleLinesFrom(getCharSequenceArray(key))
    }

    internal fun visibleLinesFrom(lines: Array<CharSequence?>?): String {
        return lines
            ?.mapNotNull { line -> line?.toString()?.trimToContentOrNull() }
            ?.joinToString(separator = "\n")
            .orEmpty()
    }

    private fun Bundle.readMessages(): String {
        val bundles = getParcelableArray(
            Notification.EXTRA_MESSAGES,
            Parcelable::class.java
        ) ?: return ""

        return runCatching {
            val messages = Notification.MessagingStyle.Message
                .getMessagesFromBundleArray(bundles)
                .map { message ->
                    MessageContent(
                        sender = message.senderPerson?.name?.toString(),
                        text = message.text?.toString()
                    )
                }
            visibleMessagesFrom(messages)
        }.getOrDefault("")
    }

    internal fun visibleMessagesFrom(messages: List<MessageContent>): String {
        return messages
            .mapNotNull { message ->
                val text = message.text?.trimToContentOrNull() ?: return@mapNotNull null
                message.sender
                    ?.trimToContentOrNull()
                    ?.let { sender -> "$sender: $text" }
                    ?: text
            }
            .joinToString(separator = "\n")
    }

    private fun firstNonBlank(vararg values: String?): String {
        return values
            .firstNotNullOfOrNull { value -> value?.trimToContentOrNull() }
            .orEmpty()
    }

    private fun String.trimToContentOrNull(): String? {
        return trim().takeIf { it.isNotBlank() }
    }
}
