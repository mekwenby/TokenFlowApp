package xyz.mek030399.tokenflow.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceScreensTest {
    @Test
    fun markdownPreviewRequiresMarkdownExtensionAndStaysWithinRenderBounds() {
        assertTrue(shouldRenderKnowledgePreviewAsMarkdown("md", "# Heading\n\nBody"))
        assertTrue(shouldRenderKnowledgePreviewAsMarkdown("MARKDOWN", "Body"))
        assertFalse(shouldRenderKnowledgePreviewAsMarkdown("txt", "# Heading"))
        assertFalse(shouldRenderKnowledgePreviewAsMarkdown("md", "x".repeat(32_001)))
        assertFalse(shouldRenderKnowledgePreviewAsMarkdown("md", List(1_001) { "line" }.joinToString("\n")))
    }

    @Test
    fun plainTextBlocksPreserveUnicodeAndEveryCharacter() {
        val text = "first line\n" + "\uD83D\uDE00".repeat(19) + "\nlast line"

        val blocks = knowledgePreviewPlainTextBlocks(text, maxCodePoints = 8)

        assertEquals(text, blocks.joinToString(""))
        assertTrue(blocks.all { it.codePointCount(0, it.length) <= 8 })
        assertTrue(blocks.none { it.firstOrNull()?.isLowSurrogate() == true })
        assertTrue(blocks.none { it.lastOrNull()?.isHighSurrogate() == true })
    }
}
