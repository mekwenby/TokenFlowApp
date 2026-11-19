package xyz.mek030399.tokenflow.data

import java.io.ByteArrayInputStream
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownAgentImportTest {
    @Test
    fun importsUtf8MarkdownPromptVerbatimUsingHeadingAndCurrentModel() {
        val body = "# 旅行助手 ###\r\n\r\n你是一个旅行助手。  \r\n- 保留格式\r\n"
        val bytes = "\uFEFF$body".toByteArray(Charsets.UTF_8)

        val imported = parseImportedMarkdownNote("旅行.MD", bytes.size.toLong(), ByteArrayInputStream(bytes))
            .toImportedAgent("global-model")

        assertEquals("旅行助手", imported.name)
        assertEquals(body, imported.systemPrompt)
        assertEquals("global-model", imported.modelId)
        assertFalse(imported.enableInfiniteCloud)
        assertEquals(null, imported.cloudServerId)
    }

    @Test
    fun ignoresExampleHeadingsInsideFencedCodeAndPreservesFrontmatter() {
        val body = "---\nname: metadata remains in the prompt\n---\n" +
            "```markdown\n# Example\n```\n~~~\n# Another example\n~~~\n# Actual agent\nInstructions"

        val imported = ImportedMarkdownNote("File name", body).toImportedAgent(null)

        assertEquals("Actual agent", imported.name)
        assertEquals(body, imported.systemPrompt)
        assertEquals(null, imported.modelId)
    }

    @Test
    fun usesFileNameWithoutHeadingAndAlwaysCreatesIndependentAgents() {
        val source = ImportedMarkdownNote("Friendly assistant", "Help the user.")

        val first = source.toImportedAgent(null)
        val second = source.toImportedAgent(null)

        assertEquals("Friendly assistant", first.name)
        assertNotEquals(first.id, second.id)
        assertEquals(first.id, UUID.fromString(first.id).toString())
        assertEquals("Markdown", ImportedMarkdownNote(" ", "Prompt").toImportedAgent(null).name)
    }

    @Test
    fun limitsOnlyTheDisplayNameWithoutSplittingUnicode() {
        val name = "\uD83D\uDE00".repeat(130)
        val body = "# $name\nFull instructions"

        val imported = ImportedMarkdownNote("file", body).toImportedAgent(null)

        assertEquals(120, imported.name.codePointCount(0, imported.name.length))
        assertTrue(imported.name.last().isLowSurrogate())
        assertEquals(body, imported.systemPrompt)
    }

    @Test
    fun rejectsEmptyPromptsBeforeCreatingAnAgent() {
        val failure = assertThrows(NoteMarkdownFileException::class.java) {
            ImportedMarkdownNote("file", "\r\n  ").toImportedAgent(null)
        }

        assertEquals(NoteMarkdownFileError.EMPTY, failure.reason)
    }

    @Test
    fun acceptsExactly512KiBButRejectsLargerUtf8PromptsBeforeSaving() {
        val body = "x".repeat(MAX_MARKDOWN_AGENT_BYTES)
        assertEquals(body, ImportedMarkdownNote("At limit", body).toImportedAgent(null).systemPrompt)

        val failure = assertThrows(NoteMarkdownFileException::class.java) {
            ImportedMarkdownNote("Too large", "你".repeat(MAX_MARKDOWN_AGENT_BYTES / 3 + 1))
                .toImportedAgent(null)
        }
        assertEquals(NoteMarkdownFileError.TOO_LARGE, failure.reason)
    }
}
