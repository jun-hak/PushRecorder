package com.example.pushrecorder.service

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationContentExtractorTest {
    @Test
    fun timestampUsesNotificationPostTimeWhenPresent() {
        val timestamp = NotificationContentExtractor.timestamp(
            postTime = 1_234L,
            currentTimeMillis = { 9_999L }
        )

        assertEquals(1_234L, timestamp)
    }

    @Test
    fun timestampFallsBackToCurrentTimeWhenPostTimeIsZero() {
        val timestamp = NotificationContentExtractor.timestamp(
            postTime = 0L,
            currentTimeMillis = { 9_999L }
        )

        assertEquals(9_999L, timestamp)
    }

    @Test
    fun timestampFallsBackToCurrentTimeWhenPostTimeIsNegative() {
        val timestamp = NotificationContentExtractor.timestamp(
            postTime = -1L,
            currentTimeMillis = { 9_999L }
        )

        assertEquals(9_999L, timestamp)
    }

    @Test
    fun textFromContentPrefersExpandedBodyOverCollapsedBody() {
        val text = NotificationContentExtractor.textFromContent(
            NotificationContentExtractor.TextContent(
                bigText = " Expanded body ",
                textLines = "First line\nSecond line",
                messages = "Alice: Ping",
                text = "Collapsed body",
                summaryText = "Summary",
                infoText = "Info",
                subText = "Sub text",
                tickerText = "Ticker fallback"
            )
        )

        assertEquals("Expanded body", text)
    }

    @Test
    fun textFromContentFallsBackThroughVisibleBodyFields() {
        val text = NotificationContentExtractor.textFromContent(
            NotificationContentExtractor.TextContent(
                bigText = " ",
                textLines = null,
                messages = "\n",
                text = "",
                summaryText = "Summary body",
                infoText = "Info",
                subText = "Sub text",
                tickerText = "Ticker fallback"
            )
        )

        assertEquals("Summary body", text)
    }

    @Test
    fun textFromContentFallsBackWhenEarlierBodyFieldsAreNull() {
        val text = NotificationContentExtractor.textFromContent(
            NotificationContentExtractor.TextContent(
                bigText = null,
                textLines = null,
                messages = null,
                text = "Collapsed body",
                summaryText = "Summary",
                infoText = "Info",
                subText = "Sub text",
                tickerText = "Ticker fallback"
            )
        )

        assertEquals("Collapsed body", text)
    }

    @Test
    fun textFromContentReturnsEmptyWhenBodyFieldsAreAbsent() {
        val text = NotificationContentExtractor.textFromContent(
            NotificationContentExtractor.TextContent()
        )

        assertEquals("", text)
    }

    @Test
    fun textFromContentPreservesMultilineInboxOrMessageText() {
        val text = NotificationContentExtractor.textFromContent(
            NotificationContentExtractor.TextContent(
                textLines = """
                    First line
                    Second line
                """.trimIndent(),
                messages = "Alice: Ping",
                text = "Collapsed body"
            )
        )

        assertEquals(
            """
                First line
                Second line
            """.trimIndent(),
            text
        )
    }

    @Test
    fun visibleLinesFromPreservesRepeatedVisibleLines() {
        val text = NotificationContentExtractor.visibleLinesFrom(
            arrayOf(
                "Retrying upload",
                "Retrying upload",
                "  ",
                "\tRetrying upload\t"
            )
        )

        assertEquals(
            """
                Retrying upload
                Retrying upload
                Retrying upload
            """.trimIndent(),
            text
        )
    }

    @Test
    fun visibleMessagesFromPreservesRepeatedVisibleMessages() {
        val text = NotificationContentExtractor.visibleMessagesFrom(
            listOf(
                NotificationContentExtractor.MessageContent(
                    sender = "Alice",
                    text = "Still there?"
                ),
                NotificationContentExtractor.MessageContent(
                    sender = "Alice",
                    text = "Still there?"
                ),
                NotificationContentExtractor.MessageContent(
                    sender = "Bob",
                    text = "  "
                ),
                NotificationContentExtractor.MessageContent(
                    sender = " Alice ",
                    text = "\tStill there?\t"
                ),
                NotificationContentExtractor.MessageContent(
                    sender = " ",
                    text = "Still there?"
                )
            )
        )

        assertEquals(
            """
                Alice: Still there?
                Alice: Still there?
                Alice: Still there?
                Still there?
            """.trimIndent(),
            text
        )
    }

    @Test
    fun textFromContentUsesTickerWhenStructuredBodyFieldsAreBlank() {
        val text = NotificationContentExtractor.textFromContent(
            NotificationContentExtractor.TextContent(
                textLines = "",
                messages = " ",
                text = "\t",
                infoText = "",
                subText = "\n",
                tickerText = " Fallback ticker "
            )
        )

        assertEquals("Fallback ticker", text)
    }

    @Test
    fun textFromContentReturnsEmptyWhenAllCandidatesAreBlank() {
        val text = NotificationContentExtractor.textFromContent(
            NotificationContentExtractor.TextContent(
                textLines = "",
                messages = " ",
                text = "\t",
                infoText = "",
                subText = "\n"
            )
        )

        assertEquals("", text)
    }

    @Test
    fun titleFromContentPrefersConversationTitle() {
        val title = NotificationContentExtractor.titleFromContent(
            NotificationContentExtractor.TitleContent(
                conversationTitle = "Family chat",
                title = "Alice",
                bigTitle = "Expanded title",
                subText = "Messages",
                tickerText = "Ticker fallback"
            )
        )

        assertEquals("Family chat", title)
    }

    @Test
    fun titleFromContentFallsBackThroughVisibleTitleFields() {
        val title = NotificationContentExtractor.titleFromContent(
            NotificationContentExtractor.TitleContent(
                conversationTitle = "  ",
                bigTitle = "Expanded title",
                subText = "Messages",
                tickerText = "Ticker fallback"
            )
        )

        assertEquals("Expanded title", title)
    }

    @Test
    fun titleFromContentFallsBackWhenEarlierTitleFieldsAreNull() {
        val title = NotificationContentExtractor.titleFromContent(
            NotificationContentExtractor.TitleContent(
                conversationTitle = null,
                title = null,
                bigTitle = "Expanded title",
                subText = "Messages",
                tickerText = "Ticker fallback"
            )
        )

        assertEquals("Expanded title", title)
    }

    @Test
    fun titleFromContentReturnsEmptyWhenTitleFieldsAreAbsent() {
        val title = NotificationContentExtractor.titleFromContent(
            NotificationContentExtractor.TitleContent()
        )

        assertEquals("", title)
    }

    @Test
    fun titleFromContentUsesTickerWhenStructuredTitleFieldsAreBlank() {
        val title = NotificationContentExtractor.titleFromContent(
            NotificationContentExtractor.TitleContent(
                title = " ",
                bigTitle = "",
                subText = "\t",
                tickerText = " Fallback ticker "
            )
        )

        assertEquals("Fallback ticker", title)
    }

    @Test
    fun titleFromContentReturnsEmptyWhenAllCandidatesAreBlank() {
        val title = NotificationContentExtractor.titleFromContent(
            NotificationContentExtractor.TitleContent(
                title = "",
                bigTitle = " ",
                subText = "\n"
            )
        )

        assertEquals("", title)
    }
}
