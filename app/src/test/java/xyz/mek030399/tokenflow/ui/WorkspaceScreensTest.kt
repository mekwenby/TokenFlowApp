package xyz.mek030399.tokenflow.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.mek030399.tokenflow.data.AgentProfile
import xyz.mek030399.tokenflow.data.BuiltInAgents
import java.util.UUID

class WorkspaceScreensTest {
    @Test
    fun copyingBuiltInAgentCreatesIndependentEditableDraftsWithoutChangingTheSource() {
        val prompt = "# 完整角色提示词\n\n" + "日常对话与角色细节 \uD83D\uDE00\n".repeat(4_000)
        val source = AgentProfile(
            id = BuiltInAgents.SYAN_ID,
            name = "SYAN",
            description = "AI 原创角色",
            systemPrompt = prompt,
            thinkingEffort = "high",
            maxToolCalls = 3,
            enableSearch = false,
            enableRead = true,
            enableKnowledge = true,
            createdAt = 10,
            updatedAt = 20,
        )

        val first = copyAgentForEditing(source, "SYAN 副本", "default-model", now = 100)
        val second = copyAgentForEditing(source, "SYAN 副本", "default-model", now = 101)

        assertNotEquals(source.id, first.id)
        assertNotEquals(first.id, second.id)
        assertEquals(first.id, UUID.fromString(first.id).toString())
        assertFalse(BuiltInAgents.isBuiltIn(first.id))
        assertEquals("SYAN 副本", first.name)
        assertEquals("default-model", first.modelId)
        assertEquals(100L, first.createdAt)
        assertEquals(100L, first.updatedAt)
        assertEquals(101L, second.createdAt)
        assertEquals(101L, second.updatedAt)
        for (copy in listOf(first, second)) {
            assertEquals(prompt, copy.systemPrompt)
            assertEquals(source.description, copy.description)
            assertEquals(source.thinkingEffort, copy.thinkingEffort)
            assertEquals(source.maxToolCalls, copy.maxToolCalls)
            assertEquals(source.enableSearch, copy.enableSearch)
            assertEquals(source.enableRead, copy.enableRead)
            assertEquals(source.enableKnowledge, copy.enableKnowledge)
            assertEquals(source.enableInfiniteCloud, copy.enableInfiniteCloud)
            assertEquals(source.cloudServerId, copy.cloudServerId)
        }
        assertEquals(BuiltInAgents.SYAN_ID, source.id)
        assertEquals("SYAN", source.name)
        assertEquals(null, source.modelId)
        assertEquals(10L, source.createdAt)
        assertEquals(20L, source.updatedAt)
    }

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
