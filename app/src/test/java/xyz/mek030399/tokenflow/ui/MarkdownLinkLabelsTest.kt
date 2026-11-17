package xyz.mek030399.tokenflow.ui

import org.commonmark.node.FencedCodeBlock
import org.junit.Assert.*
import org.junit.Test
import xyz.mek030399.tokenflow.data.KnowledgeCitation

class MarkdownLinkLabelsTest {
    @Test fun screenshotReferencesUseKnownTitlesAndKeepExactClickTargets() {
        val first = "https://www.wingontravel.com/discovery/restaurant-154979511"
        val second = "https://m.dianping.com/shop/1175084160?msource=applemaps"
        val rendered = inlineAnnotatedString(parseMarkdown("来源：[[$first]] [[$second]]"),
            webLinkTitles = mapOf(first to "餐厅详情", second to "餐厅评价"))
        assertEquals("来源：餐厅详情 餐厅评价", rendered.text)
        val links = rendered.getStringAnnotations("URL", 0, rendered.length)
        assertEquals(listOf(first, second), links.map { it.item })
        assertEquals(listOf("餐厅详情", "餐厅评价"), links.map { rendered.text.substring(it.start, it.end) })
    }

    @Test fun rawUrlsUseDomainsAndExplicitMarkdownTitlesKeepTheirFormatting() {
        val url = "https://www.example.com/very/long/path?q=original%2Fvalue#section"
        val rendered = inlineAnnotatedString(parseMarkdown("$url <$url> [$url]($url \"页面标题\") [**保留标题**]($url)"))
        assertEquals("example.com example.com 页面标题 保留标题", rendered.text)
        assertEquals(List(4) { url }, rendered.getStringAnnotations("URL", 0, rendered.length).map { it.item })
        assertTrue(rendered.spanStyles.any { it.item.fontWeight == androidx.compose.ui.text.font.FontWeight.Bold })
    }

    @Test fun adjacentReferencesRemainSeparatedAndUnrelatedBracketsStayLiteral() {
        val rendered = inlineAnnotatedString(parseMarkdown("[[https://a.example/x]][[https://b.example/y]] [literal] [[KB:99]]"))
        assertEquals("a.example b.example [literal] [[KB:99]]", rendered.text)
        assertEquals(2, rendered.getStringAnnotations("URL", 0, rendered.length).size)
        assertEquals("[[a.example", inlineAnnotatedString(parseMarkdown("[[https://a.example/x")).text)
    }

    @Test fun inlineFencedAndHtmlCodeKeepUrlsVerbatim() {
        val url = "https://example.com/path?retain=1"
        val inline = inlineAnnotatedString(parseMarkdown("`[[$url]]` <code>[[$url]]</code>"),
            webLinkTitles = mapOf(url to "Must not replace code"))
        assertEquals("[[$url]] [[$url]]", inline.text)
        assertTrue(inline.getStringAnnotations("URL", 0, inline.length).isEmpty())
        assertEquals("[[$url]]\n", (parseMarkdown("```text\n[[$url]]\n```").firstChild as FencedCodeBlock).literal)
    }

    @Test fun htmlAnchorsKeepTheOuterHrefWithoutDuplicateOrUnsafeAnnotations() {
        val outer = "https://outer.example/full?target=1"
        val inner = "https://inner.example/visible"
        val rendered = inlineAnnotatedString(parseMarkdown("<a href=\"$outer\">$inner</a>"),
            webLinkTitles = mapOf(outer to "真实来源", inner to "Wrong target"))
        assertEquals("真实来源", rendered.text)
        assertEquals(listOf(outer), rendered.getStringAnnotations("URL", 0, rendered.length).map { it.item })
        val unsafe = inlineAnnotatedString(parseMarkdown("<a href=\"javascript:bad()\">$inner</a>"))
        assertTrue(unsafe.getStringAnnotations("URL", 0, unsafe.length).isEmpty())
        val hidden = inlineAnnotatedString(parseMarkdown("text <script>$inner</script>"))
        assertEquals("text ", hidden.text)
    }

    @Test fun sourceTitlesStayPlainAndLongLabelsDoNotSplitUnicodeCharacters() {
        val url = "https://example.com/path"
        val title = "**plain** [[KB:42]] <script>data</script>"
        val rendered = inlineAnnotatedString(parseMarkdown(url), webLinkTitles = mapOf(url to title))
        assertEquals(title, rendered.text)
        assertTrue(rendered.getStringAnnotations(KNOWLEDGE_CITATION_ANNOTATION_TAG, 0, rendered.length).isEmpty())
        val long = inlineAnnotatedString(parseMarkdown(url), webLinkTitles = mapOf(url to "😀".repeat(150)))
        assertEquals(80, long.text.codePointCount(0, long.length))
        assertTrue(long.text.endsWith("…"))
        assertFalse(long.text.dropLast(1).last().isHighSurrogate())
        assertEquals("example.com", inlineAnnotatedString(parseMarkdown(url), webLinkTitles = mapOf(url to url)).text)
    }

    @Test fun knowledgeCitationsKeepTheirIndependentVerifiedClickTarget() {
        val citation = KnowledgeCitation(chunkId = 42, documentId = "doc", documentName = "Reference.md", position = 0)
        val url = "https://example.com/path"
        val rendered = inlineAnnotatedString(parseMarkdown("[[KB:42]] [[$url]]"),
            knowledgeCitations = listOf(citation), knowledgeCitationLabels = mapOf(42L to "Reference.md · 1"),
            webLinkTitles = mapOf(url to "网页标题"))
        assertEquals("Reference.md · 1 网页标题", rendered.text)
        assertEquals(listOf("42"), rendered.getStringAnnotations(KNOWLEDGE_CITATION_ANNOTATION_TAG, 0, rendered.length).map { it.item })
        assertEquals(listOf(url), rendered.getStringAnnotations("URL", 0, rendered.length).map { it.item })
    }
}
