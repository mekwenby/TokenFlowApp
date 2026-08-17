package xyz.mek030399.tokenflow.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ChatContextTest {
    @Test
    fun messagesWithoutBoundaryRemainAvailable() {
        val messages = listOf(
            message("user-1", "user"),
            message("assistant-1", "assistant"),
        )

        assertSame(messages, messages.afterLatestContextBoundary())
    }

    @Test
    fun latestBoundaryStartsANewModelContext() {
        val messages = listOf(
            message("old-user", "user"),
            message("first-boundary", CONTEXT_BOUNDARY_ROLE),
            message("discarded-user", "user"),
            message("latest-boundary", CONTEXT_BOUNDARY_ROLE),
            message("current-user", "user"),
            message("current-assistant", "assistant"),
        )

        assertEquals(
            listOf("current-user", "current-assistant"),
            messages.afterLatestContextBoundary().map(ChatMessage::id),
        )
    }

    @Test
    fun modelContextOnlyContainsEligibleMessagesAfterBoundary() {
        val messages = listOf(
            message("old-user", "user"),
            message("boundary", CONTEXT_BOUNDARY_ROLE),
            message("user", "user"),
            message("completed", "assistant"),
            message("failed", "assistant", "failed"),
            message("generating", "assistant", "generating"),
            message("unknown", "tool"),
            message("excluded", "assistant"),
        )

        assertEquals(
            listOf("user", "completed"),
            messages.forModelContext(excludedMessageId = "excluded").map(ChatMessage::id),
        )
    }

    @Test
    fun regenerationTargetMustBeAfterLatestBoundary() {
        val withoutCurrentAssistant = listOf(
            message("old-assistant", "assistant"),
            message("boundary", CONTEXT_BOUNDARY_ROLE),
            message("current-user", "user"),
        )
        val withCurrentAssistant = withoutCurrentAssistant + message("current-assistant", "assistant", "failed")

        assertEquals(null, withoutCurrentAssistant.latestAssistantInCurrentContext())
        assertEquals("current-assistant", withCurrentAssistant.latestAssistantInCurrentContext()?.id)
    }

    @Test
    fun nextMessageTimestampIsStrictlyAfterStoredMessages() {
        val messages = listOf(
            message("one", "user", createdAt = 20),
            message("two", "assistant", createdAt = 25),
        )

        assertEquals(26L, nextMessageCreatedAt(messages, currentTimeMillis = 10))
        assertEquals(40L, nextMessageCreatedAt(messages, currentTimeMillis = 40))
        assertEquals(40L, nextMessageCreatedAt(emptyList(), currentTimeMillis = 40))
    }

    private fun message(
        id: String,
        role: String,
        status: String = "completed",
        createdAt: Long = 0,
    ) = ChatMessage(
        id = id,
        conversationId = "conversation",
        role = role,
        status = status,
        createdAt = createdAt,
    )
}
