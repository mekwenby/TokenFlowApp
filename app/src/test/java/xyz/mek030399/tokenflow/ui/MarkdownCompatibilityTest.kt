package xyz.mek030399.tokenflow.ui

import androidx.compose.ui.text.font.FontWeight
import org.commonmark.node.FencedCodeBlock
import org.junit.Assert.*
import org.junit.Test

class MarkdownCompatibilityTest {
    @Test fun completedChineseReplyRendersStrongTextEndingInPunctuation() {
        val rendered = inlineAnnotatedString(parseMarkdown("**几个特别突出的“明星产品”：**默沙东的 Keytruda。"))

        assertEquals("几个特别突出的“明星产品”：默沙东的 Keytruda。", rendered.text)
        val strong = rendered.spanStyles.single { it.item.fontWeight == FontWeight.Bold }
        assertEquals("几个特别突出的“明星产品”：", rendered.text.substring(strong.start, strong.end))
    }

    @Test fun chineseOpeningAndClosingQuotesCanTouchSurroundingWords() {
        val rendered = inlineAnnotatedString(parseMarkdown("这是**“明星产品”**清单，另有**注意：**正文。"))

        assertEquals("这是“明星产品”清单，另有注意：正文。", rendered.text)
        assertEquals(listOf("“明星产品”", "注意："), rendered.spanStyles
            .filter { it.item.fontWeight == FontWeight.Bold }.map { rendered.text.substring(it.start, it.end) })
    }

    @Test fun nestedInlineFormattingAndSourceLinksSurviveChineseEmphasisRepair() {
        val rendered = inlineAnnotatedString(parseMarkdown("**中文 *重点* 与 `code` [来源](https://example.com/report)：**后文"))

        assertEquals("中文 重点 与 code 来源：后文", rendered.text)
        assertEquals(listOf("https://example.com/report"), rendered.getStringAnnotations("URL", 0, rendered.length).map { it.item })
        assertTrue(rendered.spanStyles.any { it.item.fontWeight == FontWeight.Bold && it.start == 0 && it.end == rendered.text.indexOf("后文") })
    }

    @Test fun validCommonMarkStrongAndEmphasisKeepTheirExistingMeaning() {
        val rendered = inlineAnnotatedString(parseMarkdown("**正常加粗**，*斜体*，**317亿美元**。"))

        assertEquals("正常加粗，斜体，317亿美元。", rendered.text)
        assertEquals(2, rendered.spanStyles.count { it.item.fontWeight == FontWeight.Bold })
    }

    @Test fun escapedStarsAndInlineCodeStayLiteral() {
        val rendered = inlineAnnotatedString(parseMarkdown("\\*\\*“保留”：\\*\\*文字，与 `**中文：**后文`。"))

        assertEquals("**“保留”：**文字，与 **中文：**后文。", rendered.text)
        assertFalse(rendered.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
    }

    @Test fun fencedCodeAndIndentedCodeAreNotRewritten() {
        val fenced = "```markdown\n**中文：**后文\n```"
        val document = parseMarkdown(fenced)

        assertEquals("**中文：**后文\n", (document.firstChild as FencedCodeBlock).literal)
        assertEquals(fenced, repairCjkStrongEmphasis(fenced, document))
        val indented = "    **中文：**后文"
        assertEquals(indented, repairCjkStrongEmphasis(indented, parseMarkdown(indented)))
    }

    @Test fun htmlAttributesAndLinkDestinationsAreNotRewritten() {
        val markdown = "<span title=\"**中文：**属性\">文字</span> [来源](https://example.com/**中文：**路径)"
        val document = parseMarkdown(markdown)

        assertEquals(markdown, repairCjkStrongEmphasis(markdown, document))
        assertEquals(listOf("https://example.com/**中文：**路径"),
            inlineAnnotatedString(document).getStringAnnotations("URL", 0, 100).map { it.item })
    }

    @Test fun unmatchedMarkersAndEnglishFormattingAreNotGuessed() {
        listOf("**尚未完成：", "**中文：\n\n**下一段", "**Note:**text", "***中文：***后文").forEach { source ->
            assertEquals(source, repairCjkStrongEmphasis(source, parseMarkdown(source)))
        }
    }

    @Test fun privateProviderCitationIdsBecomeAnExplicitUnavailableSourceLabel() {
        val marker = "\uE200cite\uE202turn166120search0\uE202turn166120search1\uE202turn166120search2\uE201"
        val rendered = inlineAnnotatedString(parseMarkdown("请核对公司年报。$marker"),
            unsupportedCitationLabel = "［来源链接未提供］")

        assertEquals("请核对公司年报。［来源链接未提供］", rendered.text)
        assertTrue(rendered.getStringAnnotations("URL", 0, rendered.length).isEmpty())
        assertFalse(rendered.text.contains("turn166120"))
    }

    @Test fun actualSourceLinksRemainClickableBesideUnavailableProviderCitations() {
        val rendered = inlineAnnotatedString(parseMarkdown("\uE200cite\uE202turn0search0\uE201 https://example.com/source"))

        assertEquals("[source link unavailable] https://example.com/source", rendered.text)
        assertEquals(listOf("https://example.com/source"), rendered.getStringAnnotations("URL", 0, rendered.length).map { it.item })
    }

    @Test fun citationSyntaxInsideCodeAndUnknownMarkersStayLiteral() {
        val marker = "\uE200cite\uE202turn0search0\uE201"
        val rendered = inlineAnnotatedString(parseMarkdown("`$marker` [[KB:99]] \uE200unknown\uE202value\uE201"))

        assertEquals("$marker [[KB:99]] \uE200unknown\uE202value\uE201", rendered.text)
        assertTrue(rendered.getStringAnnotations("URL", 0, rendered.length).isEmpty())
    }
}
