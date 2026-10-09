package xyz.mek030399.tokenflow.data

import org.junit.Assert.*
import org.junit.Test

class QuestionEditingTest {
    private fun message(id: String, role: String) = ChatMessage(id = id, conversationId = "source", role = role, content = id)

    @Test fun editingAnEarlierQuestionExcludesItsResponseAndLaterContextBoundaries() {
        val history = listOf(message("old", "user"), message("answer", "assistant"), message("target", "user"),
            message("target-answer", "assistant"), message("later-boundary", CONTEXT_BOUNDARY_ROLE), message("later", "user"))
        assertEquals(listOf("old", "answer", "target"), editedQuestionPrefix(history, "target").map { it.id })
    }

    @Test fun editingAfterABoundaryRetainsTheBoundarySoOldHistoryStaysExcluded() {
        val history = listOf(message("old", "user"), message("boundary", CONTEXT_BOUNDARY_ROLE), message("target", "user"))
        assertEquals(listOf("target"), editedQuestionPrefix(history, "target").forModelContext().map { it.id })
    }

    @Test fun rejectsAssistantAndMissingTargets() {
        val history = listOf(message("user", "user"), message("reply", "assistant"))
        assertTrue(runCatching { editedQuestionPrefix(history, "reply") }.isFailure)
        assertTrue(runCatching { editedQuestionPrefix(history, "missing") }.isFailure)
    }

    @Test fun onlySummariesStrictlyBeforeTheEditedQuestionCanBeInherited() {
        val history = listOf(message("old", "user"), message("answer", "assistant"), message("target", "user"))
        fun summary(source: List<ChatMessage>) = ContextSummary("source", "old facts", sourceMessageIds = source.map { it.id },
            sourceDigest = ContextBuilder.digest(source), modelId = "model", remoteModelId = "remote")
        assertNotNull(summaryBeforeEditedQuestion(summary(history.take(2)), history))
        assertNull(summaryBeforeEditedQuestion(summary(history), history))
    }

    @Test fun laterBoundaryInvalidatesASummaryWhenEditingBeforeThatBoundary() {
        val history = listOf(message("target", "user"))
        val summary = ContextSummary("source", "later facts", boundaryId = "later-boundary", sourceMessageIds = listOf("later"),
            sourceDigest = "irrelevant", modelId = "model", remoteModelId = "remote")
        assertNull(summaryBeforeEditedQuestion(summary, history))
    }
}
