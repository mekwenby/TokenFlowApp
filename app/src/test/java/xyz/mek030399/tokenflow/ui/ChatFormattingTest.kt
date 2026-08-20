package xyz.mek030399.tokenflow.ui

import xyz.mek030399.tokenflow.data.ProcessEvent
import xyz.mek030399.tokenflow.data.Usage
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatFormattingTest {
    @Test
    fun generationActivityDefaultsToCallingModel() {
        assertEquals(
            GenerationActivity.CALLING_MODEL,
            currentGenerationActivity(emptyList(), generationActive = true),
        )
    }

    @Test
    fun generationActivityMapsKnownAndUnknownTools() {
        assertEquals(
            GenerationActivity.SEARCHING_WEB,
            currentGenerationActivity(
                listOf(ProcessEvent(type = "tool_started", id = "web", name = "web_search")),
                generationActive = true,
            ),
        )
        assertEquals(
            GenerationActivity.READING_URL,
            currentGenerationActivity(
                listOf(ProcessEvent(type = "tool_started", id = "url", name = "read_url")),
                generationActive = true,
            ),
        )
        assertEquals(
            GenerationActivity.SEARCHING_LOCAL_KNOWLEDGE,
            currentGenerationActivity(
                listOf(ProcessEvent(type = "tool_started", id = "knowledge", name = "search_knowledge")),
                generationActive = true,
            ),
        )
        assertEquals(
            GenerationActivity.CALCULATING,
            currentGenerationActivity(
                listOf(ProcessEvent(type = "tool_started", id = "calculation", name = "calculate")),
                generationActive = true,
            ),
        )
        assertEquals(
            GenerationActivity.CONVERTING_UNITS,
            currentGenerationActivity(
                listOf(ProcessEvent(type = "tool_started", id = "conversion", name = "convert_units")),
                generationActive = true,
            ),
        )
        assertEquals(
            GenerationActivity.CALLING_TOOL,
            currentGenerationActivity(
                listOf(ProcessEvent(type = "tool_started", id = "future", name = "future_tool")),
                generationActive = true,
            ),
        )
    }

    @Test
    fun completedAndFailedEventsCloseTheMatchingToolCall() {
        listOf("tool_completed", "tool_failed").forEach { terminalType ->
            assertEquals(
                GenerationActivity.CALLING_MODEL,
                currentGenerationActivity(
                    listOf(
                        ProcessEvent(type = "tool_started", id = "call", name = "web_search"),
                        ProcessEvent(type = terminalType, id = "call", name = "web_search"),
                    ),
                    generationActive = true,
                ),
            )
        }
    }

    @Test
    fun mostRecentlyStartedOpenToolCallWins() {
        assertEquals(
            GenerationActivity.READING_URL,
            currentGenerationActivity(
                listOf(
                    ProcessEvent(type = "tool_started", id = "web", name = "web_search"),
                    ProcessEvent(type = "tool_started", id = "url", name = "read_url"),
                ),
                generationActive = true,
            ),
        )
        assertEquals(
            GenerationActivity.SEARCHING_WEB,
            currentGenerationActivity(
                listOf(
                    ProcessEvent(type = "tool_started", id = "web", name = "web_search"),
                    ProcessEvent(type = "tool_started", id = "url", name = "read_url"),
                    ProcessEvent(type = "tool_completed", id = "url", name = "read_url"),
                ),
                generationActive = true,
            ),
        )
    }

    @Test
    fun repeatedToolCallIdRefreshesItsPositionAndTerminalClosesIt() {
        val events = listOf(
            ProcessEvent(type = "tool_started", id = "shared", name = "read_url"),
            ProcessEvent(type = "tool_started", id = "web", name = "web_search"),
            ProcessEvent(type = "tool_started", id = "shared", name = "search_knowledge"),
        )
        assertEquals(
            GenerationActivity.SEARCHING_LOCAL_KNOWLEDGE,
            currentGenerationActivity(events, generationActive = true),
        )
        assertEquals(
            GenerationActivity.SEARCHING_WEB,
            currentGenerationActivity(
                events + ProcessEvent(type = "tool_failed", id = "shared", name = "search_knowledge"),
                generationActive = true,
            ),
        )
    }

    @Test
    fun unrelatedTerminalEventDoesNotCloseAnActiveToolCall() {
        assertEquals(
            GenerationActivity.SEARCHING_WEB,
            currentGenerationActivity(
                listOf(
                    ProcessEvent(type = "tool_started", id = "web", name = "web_search"),
                    ProcessEvent(type = "tool_completed", id = "other", name = "read_url"),
                ),
                generationActive = true,
            ),
        )
    }

    @Test
    fun inactiveGenerationIgnoresDanglingToolEvents() {
        assertEquals(
            GenerationActivity.CALLING_MODEL,
            currentGenerationActivity(
                listOf(ProcessEvent(type = "tool_started", id = "web", name = "web_search")),
                generationActive = false,
            ),
        )
    }

    @Test
    fun tokenCountUsesCompactKNotation() {
        assertEquals("0", formatTokenCount(-1))
        assertEquals("999", formatTokenCount(999))
        assertEquals("1K", formatTokenCount(1_000))
        assertEquals("1K", formatTokenCount(1_001))
        assertEquals("1.2K", formatTokenCount(1_200))
        assertEquals("12.4K", formatTokenCount(12_400))
        assertEquals("1000K", formatTokenCount(999_999))
    }

    @Test
    fun tokenUsageShowsTotalInputAndOutput() {
        assertEquals("2.6K↑2.5K↓100", formatTokenUsage(Usage(inputTokens = 2_500, outputTokens = 100)))
        assertEquals(
            "2.6K↑2.5K↓100 · 缓存 80%",
            formatTokenUsage(
                Usage(inputTokens = 2_500, outputTokens = 100, cacheReadTokens = 2_000, cacheMetricsReported = true),
                "缓存 80%",
            ),
        )
        assertEquals("0↑0↓0", formatTokenUsage(Usage()))
    }

    @Test
    fun cacheHitPercentageRequiresReportedMetrics() {
        assertEquals(null, Usage(inputTokens = 100, cacheReadTokens = 80).cacheHitPercentage)
        assertEquals(null, Usage(cacheMetricsReported = true).cacheHitPercentage)
    }

    @Test
    fun cacheHitPercentageRoundsAndClampsProviderValues() {
        assertEquals(0, Usage(inputTokens = 100, cacheMetricsReported = true).cacheHitPercentage)
        assertEquals(38, Usage(
            inputTokens = 8,
            cacheReadTokens = 3,
            cacheMetricsReported = true,
        ).cacheHitPercentage)
        assertEquals(100, Usage(
            inputTokens = 100,
            cacheReadTokens = 150,
            cacheMetricsReported = true,
        ).cacheHitPercentage)
    }

    @Test
    fun noteAttachmentNamesAreSafeMarkdownNames() {
        assertEquals("Meeting notes.md", noteAttachmentName(" Meeting notes "))
        assertEquals("Meeting notes.md", noteAttachmentName("Meeting notes.md"))
        assertEquals("roadmap_Q3.md", noteAttachmentName("roadmap/Q3"))
        assertEquals("note.md", noteAttachmentName("  "))
    }

    @Test
    fun lineSpacingCompressesOnlyTheExtraLeading() {
        assertEquals(21f, scaledChatLineHeightSp(14f, 21f, 1f), 0.001f)
        assertEquals(15.4f, scaledChatLineHeightSp(14f, 21f, 0.2f), 0.001f)
        assertEquals(13.2f, scaledChatLineHeightSp(12f, 18f, 0.2f), 0.001f)
    }

    @Test
    fun lineSpacingClampsItsRangeAndNeverDropsBelowTheFontSize() {
        assertEquals(15.4f, scaledChatLineHeightSp(14f, 21f, -1f), 0.001f)
        assertEquals(21f, scaledChatLineHeightSp(14f, 21f, 2f), 0.001f)
        assertEquals(16f, scaledChatLineHeightSp(16f, 12f, 0.2f), 0.001f)
    }

    @Test
    fun textUnitLineSpacingPreservesUnitsAndFallsBackForIncompatibleStyles() {
        val emResult = scaledChatLineHeight(1.em, 2.em, 0.2f)

        assertEquals(1.2f, emResult.value, 0.001f)
        assertEquals(1.em.type, emResult.type)
        assertEquals(2.em, scaledChatLineHeight(1.sp, 2.em, 0.2f))
        assertEquals(TextUnit.Unspecified, scaledChatLineHeight(1.sp, TextUnit.Unspecified, 0.2f))
    }
}
