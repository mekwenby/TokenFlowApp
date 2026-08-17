package xyz.mek030399.tokenflow.ui

import xyz.mek030399.tokenflow.data.Usage
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatFormattingTest {
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
