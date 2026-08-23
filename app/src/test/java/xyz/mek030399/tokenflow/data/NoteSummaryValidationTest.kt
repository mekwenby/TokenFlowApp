package xyz.mek030399.tokenflow.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteSummaryValidationTest {
    @Test
    fun acceptsTheFullInputAtTheSupportedLimit() {
        val body = "x".repeat(MAX_NOTE_SUMMARY_INPUT_CHARACTERS)

        assertSame(body, validateNoteSummaryInput(body))
    }

    @Test
    fun rejectsOversizedInputInsteadOfTruncatingIt() {
        val body = "x".repeat(MAX_NOTE_SUMMARY_INPUT_CHARACTERS + 1)

        val failure = runCatching { validateNoteSummaryInput(body) }.exceptionOrNull()

        assertEquals(NoteSummaryTooLongException::class.java, failure?.javaClass)
        assertEquals(MAX_NOTE_SUMMARY_INPUT_CHARACTERS, (failure as NoteSummaryTooLongException).maxCharacters)
    }

    @Test
    fun optionalRewriteInstructionsOnlyExtendTheBodyPrompt() {
        val customInstructions = "Keep the risk table and shorten the introduction"

        val bodyPrompt = InternalPrompts.noteRewrite("  $customInstructions  ")

        assertTrue(bodyPrompt.contains(customInstructions))
        assertTrue(bodyPrompt.startsWith(InternalPrompts.NOTE_REWRITE))
        assertTrue(bodyPrompt.contains("untrusted source-note data"))
        assertFalse(InternalPrompts.NOTE_TITLE.contains(customInstructions))
        assertEquals(InternalPrompts.NOTE_REWRITE, InternalPrompts.noteRewrite("  \n "))
    }
}
