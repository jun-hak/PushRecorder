package com.example.pushrecorder.service

import android.app.Notification
import android.app.Person
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationContentExtractorInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun bigTextStylePrefersExpandedTextOverCollapsedText() {
        val notification = baseBuilder()
            .setContentTitle("Deposit")
            .setContentText("Short body")
            .setStyle(
                Notification.BigTextStyle()
                    .bigText("Expanded body with all transaction details")
            )
            .build()

        assertEquals("Deposit", NotificationContentExtractor.title(notification))
        assertEquals(
            "Expanded body with all transaction details",
            NotificationContentExtractor.text(notification)
        )
    }

    @Test
    fun inboxStylePreservesAllVisibleLines() {
        val notification = baseBuilder()
            .setContentTitle("News")
            .setStyle(
                Notification.InboxStyle()
                    .addLine("First headline")
                    .addLine("Second headline")
                    .addLine("Third headline")
            )
            .build()

        assertEquals("News", NotificationContentExtractor.title(notification))
        assertEquals(
            """
                First headline
                Second headline
                Third headline
            """.trimIndent(),
            NotificationContentExtractor.text(notification)
        )
    }

    @Test
    fun messagingStyleStoresConversationTitleAndTranscript() {
        val me = Person.Builder().setName("Me").build()
        val alice = Person.Builder().setName("Alice").build()
        val bob = Person.Builder().setName("Bob").build()

        val notification = baseBuilder()
            .setStyle(
                Notification.MessagingStyle(me)
                    .setConversationTitle("Family chat")
                    .addMessage("Dinner?", 1_000L, alice)
                    .addMessage("On my way", 2_000L, bob)
            )
            .build()

        assertEquals("Family chat", NotificationContentExtractor.title(notification))
        assertEquals(
            """
                Alice: Dinner?
                Bob: On my way
            """.trimIndent(),
            NotificationContentExtractor.text(notification)
        )
    }

    @Test
    fun tickerTextIsUsedWhenStructuredContentIsMissing() {
        val notification = baseBuilder()
            .setTicker("Fallback ticker")
            .build()

        assertEquals("Fallback ticker", NotificationContentExtractor.title(notification))
        assertEquals("Fallback ticker", NotificationContentExtractor.text(notification))
    }

    @Test
    fun groupSummaryNotificationFallsBackToSummaryText() {
        val notification = baseBuilder()
            .setContentTitle("3 new messages")
            .setSubText("Family")
            .setGroup("family-chat")
            .setGroupSummary(true)
            .setStyle(
                Notification.InboxStyle()
                    .setSummaryText("Family summary")
            )
            .build()

        assertEquals("3 new messages", NotificationContentExtractor.title(notification))
        assertEquals("Family summary", NotificationContentExtractor.text(notification))
    }

    @Test
    fun malformedMessageExtrasFallBackToPlainText() {
        val notification = baseBuilder()
            .setContentTitle("Broken app")
            .setContentText("Plain fallback")
            .build()
        notification.extras.putParcelableArray(
            Notification.EXTRA_MESSAGES,
            arrayOf(notification.extras)
        )

        assertEquals("Broken app", NotificationContentExtractor.title(notification))
        assertEquals("Plain fallback", NotificationContentExtractor.text(notification))
    }

    @Test
    fun messagingStyleWithoutConversationTitleUsesContentTitle() {
        val me = Person.Builder().setName("Me").build()
        val alice = Person.Builder().setName("Alice").build()

        val notification = baseBuilder()
            .setContentTitle("Alice")
            .setStyle(
                Notification.MessagingStyle(me)
                    .addMessage("Ping", 1_000L, alice)
            )
            .build()

        assertEquals("Alice", NotificationContentExtractor.title(notification))
        assertEquals("Alice: Ping", NotificationContentExtractor.text(notification))
    }

    private fun baseBuilder(): Notification.Builder {
        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
    }

    private companion object {
        private const val CHANNEL_ID = "test-notifications"
    }
}
