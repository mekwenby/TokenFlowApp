package xyz.mek030399.tokenflow.ui

import androidx.compose.ui.text.style.TextDecoration
import xyz.mek030399.tokenflow.data.KnowledgeCitation
import xyz.mek030399.tokenflow.data.ProcessEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.commonmark.node.FencedCodeBlock
import org.commonmark.parser.Parser

class MarkdownSafetyTest {
    private val parser = Parser.builder().build()

    @Test
    fun linksOnlyAllowAbsoluteHttpAndHttpsUrls() {
        assertTrue(isSafeHttpUrl("https://example.com/path?q=1"))
        assertTrue(isSafeHttpUrl("http://localhost:8019"))
        assertFalse(isSafeHttpUrl("javascript:alert(1)"))
        assertFalse(isSafeHttpUrl("file:///data/user/0/token"))
        assertFalse(isSafeHttpUrl("//example.com/path"))
        assertFalse(isSafeHttpUrl("https:///missing-host"))
    }

    @Test
    fun previewDocumentInjectsRestrictivePolicyIntoCompleteDocuments() {
        val result = previewDocument("<!doctype html><html><head><title>Demo</title></head><body>ok</body></html>")

        assertTrue(result.contains("<head><meta http-equiv=\"Content-Security-Policy\""))
        assertTrue(result.contains("connect-src 'none'"))
        assertTrue(result.contains("frame-src 'none'"))
        assertTrue(result.contains("form-action 'none'"))
        assertTrue(result.contains("<title>Demo</title>"))
    }

    @Test
    fun previewDocumentWrapsFragmentsAndKeepsScriptsUnderCsp() {
        val result = previewDocument("<script>document.body.textContent='ok'</script>")

        assertTrue(result.startsWith("<!doctype html><html><head>"))
        assertTrue(result.contains(HTML_PREVIEW_CSP))
        assertTrue(result.contains("<body><script>"))
    }

    @Test
    fun imageWithoutOptionalTitleUsesAltTextWithoutCrashing() {
        val document = parser.parse("![Architecture diagram](https://example.com/diagram.png)")

        assertEquals("Architecture diagram", inlineAnnotatedString(document).text)
    }

    @Test
    fun imageWithoutTitleOrAltFallsBackToDestination() {
        val document = parser.parse("![](https://example.com/diagram.png)")

        assertEquals("https://example.com/diagram.png", inlineAnnotatedString(document).text)
    }

    @Test
    fun safeInlineHtmlRendersTextAndFiltersDangerousContent() {
        val document = parser.parse("<mark>marked <strong>bold</strong></mark> <span style=\"color:#ff0000;font-size:3em;position:fixed\">safe</span><script>secret()</script>")

        val rendered = inlineAnnotatedString(document)

        assertEquals("marked bold safe", rendered.text.trim())
        assertFalse(rendered.text.contains("secret"))
        assertTrue(rendered.spanStyles.isNotEmpty())
    }

    @Test
    fun htmlLinksOnlyAnnotateSafeHttpDestinations() {
        val document = parser.parse("<a href=\"https://example.com\">safe</a> <a href=\"javascript:alert(1)\" onclick=\"bad()\">blocked</a>")

        val rendered = inlineAnnotatedString(document)

        assertEquals("safe blocked", rendered.text)
        assertEquals(listOf("https://example.com"), rendered.getStringAnnotations("URL", 0, rendered.length).map { it.item })
    }

    @Test
    fun bareSourceUrlsBecomeClickableLinks() {
        val rendered = inlineAnnotatedString(parseMarkdown("Source: https://example.com/report?q=1"))

        assertEquals(
            listOf("https://example.com/report?q=1"),
            rendered.getStringAnnotations("URL", 0, rendered.length).map { it.item },
        )
        assertTrue(rendered.spanStyles.any { it.item.color.red < it.item.color.blue })
    }

    @Test
    fun allowedKnowledgeCitationBecomesLabeledClickableText() {
        val citation = knowledgeCitation(chunkId = 42, documentName = "Pricing.md", position = 0)

        val rendered = inlineAnnotatedString(
            parseMarkdown("See [[KB:42]] for details."),
            knowledgeCitations = listOf(citation),
            knowledgeCitationLabels = mapOf(citation.chunkId to "Pricing.md · Passage 1"),
        )

        assertEquals("See Pricing.md · Passage 1 for details.", rendered.text)
        assertEquals(
            listOf("42"),
            rendered.getStringAnnotations(KNOWLEDGE_CITATION_ANNOTATION_TAG, 0, rendered.length).map { it.item },
        )
        val offset = rendered.text.indexOf("Pricing.md")
        assertEquals(42L, rendered.knowledgeCitationChunkIdAt(offset))
        assertTrue(rendered.spanStyles.any { style ->
            style.start <= offset && style.end > offset && style.item.textDecoration == TextDecoration.Underline
        })
    }

    @Test
    fun unknownAndNonCanonicalKnowledgeCitationMarkersStayLiteral() {
        val citation = knowledgeCitation(chunkId = 42, documentName = "Pricing.md", position = 2)
        val markdown = "Unknown [[KB:99]], padded [[KB:042]], allowed [[KB:42]]."

        val rendered = inlineAnnotatedString(
            parseMarkdown(markdown),
            knowledgeCitations = listOf(citation),
            knowledgeCitationLabels = mapOf(citation.chunkId to "Pricing.md · Passage 3"),
        )

        assertEquals("Unknown [[KB:99]], padded [[KB:042]], allowed Pricing.md · Passage 3.", rendered.text)
        assertEquals(
            listOf("42"),
            rendered.getStringAnnotations(KNOWLEDGE_CITATION_ANNOTATION_TAG, 0, rendered.length).map { it.item },
        )
    }

    @Test
    fun knowledgeCitationMarkersInsideCodeAreNeverConverted() {
        val citation = knowledgeCitation(chunkId = 42, documentName = "Pricing.md", position = 0)
        val rendered = inlineAnnotatedString(
            parseMarkdown("Inline `[[KB:42]]` and plain [[KB:42]]."),
            knowledgeCitations = listOf(citation),
            knowledgeCitationLabels = mapOf(citation.chunkId to "Pricing.md · Passage 1"),
        )

        assertEquals("Inline [[KB:42]] and plain Pricing.md · Passage 1.", rendered.text)
        assertEquals(
            listOf("42"),
            rendered.getStringAnnotations(KNOWLEDGE_CITATION_ANNOTATION_TAG, 0, rendered.length).map { it.item },
        )

        val fenced = inlineAnnotatedString(
            parseMarkdown("```text\n[[KB:42]]\n```"),
            knowledgeCitations = listOf(citation),
            knowledgeCitationLabels = mapOf(citation.chunkId to "Pricing.md · Passage 1"),
        )
        assertTrue(fenced.getStringAnnotations(KNOWLEDGE_CITATION_ANNOTATION_TAG, 0, fenced.length).isEmpty())
    }

    @Test
    fun knowledgeCitationsDoNotChangeHttpAnnotations() {
        val citation = knowledgeCitation(chunkId = 42, documentName = "Pricing.md", position = 0)
        val rendered = inlineAnnotatedString(
            parseMarkdown("[[KB:42]] https://example.com/source"),
            knowledgeCitations = listOf(citation),
            knowledgeCitationLabels = mapOf(citation.chunkId to "Pricing.md · Passage 1"),
        )

        assertEquals(
            listOf("https://example.com/source"),
            rendered.getStringAnnotations("URL", 0, rendered.length).map { it.item },
        )
        assertEquals(
            listOf("42"),
            rendered.getStringAnnotations(KNOWLEDGE_CITATION_ANNOTATION_TAG, 0, rendered.length).map { it.item },
        )
    }

    @Test
    fun retrievalProcessCitationsDoNotAuthorizeAnswerMarkers() {
        val persisted = knowledgeCitation(42, "Persisted.md", 0)
        val retrievalOnly = knowledgeCitation(99, "Not injected.md", 1)
        val completedTool = knowledgeCitation(77, "Tool result.md", 2)
        val failedTool = knowledgeCitation(88, "Partial tool result.md", 3)
        val allowed = allowedAssistantKnowledgeCitations(
            persisted = listOf(persisted),
            liveEvents = listOf(
                ProcessEvent(type = "knowledge_retrieval", knowledgeCitations = listOf(retrievalOnly)),
                ProcessEvent(type = "tool_completed", knowledgeCitations = listOf(completedTool)),
                ProcessEvent(type = "tool_failed", knowledgeCitations = listOf(failedTool)),
            ),
        )

        assertEquals(listOf(42L, 77L, 88L), allowed.map(KnowledgeCitation::chunkId))
        val rendered = inlineAnnotatedString(
            parseMarkdown("[[KB:42]] [[KB:99]] [[KB:77]] [[KB:88]]"),
            knowledgeCitations = allowed,
            knowledgeCitationLabels = allowed.associate { it.chunkId to it.displayLabel },
        )

        assertTrue(rendered.text.contains("[[KB:99]]"))
        assertEquals(
            listOf("42", "77", "88"),
            rendered.getStringAnnotations(KNOWLEDGE_CITATION_ANNOTATION_TAG, 0, rendered.length).map { it.item },
        )
    }

    @Test
    fun processResultUrlsExcludeJsonPunctuation() {
        val rendered = annotatePlainUrls("{\"url\":\"https://example.com/source?q=1\",\"ok\":true}")

        assertEquals(
            listOf("https://example.com/source?q=1"),
            rendered.getStringAnnotations("URL", 0, rendered.length).map { it.item },
        )
        assertEquals("{\"url\":\"https://example.com/source?q=1\",\"ok\":true}", rendered.text)
    }

    @Test
    fun processResultUrlsKeepBalancedTrailingParentheses() {
        val url = "https://en.wikipedia.org/wiki/Function_(mathematics)"
        val rendered = annotatePlainUrls("Source: $url.")

        assertEquals(
            listOf(url),
            rendered.getStringAnnotations("URL", 0, rendered.length).map { it.item },
        )
        assertEquals("Source: $url.", rendered.text)
    }

    @Test
    fun codeBlockContentRemovesFencedLiteralTrailingNewline() {
        val document = parser.parse("```kotlin\nval answer = 42\n```")
        val literal = (document.firstChild as FencedCodeBlock).literal

        assertEquals("val answer = 42\n", literal)
        assertEquals("val answer = 42", normalizeCodeBlockContent(literal))
    }

    @Test
    fun codeBlockContentRemovesConsecutiveBlankBoundaryLines() {
        val code = "\n  \n\t\nfirst\nsecond\n\t\n   \n"

        assertEquals("first\nsecond", normalizeCodeBlockContent(code))
    }

    @Test
    fun codeBlockContentKeepsInternalBlankLines() {
        val code = "first\n\n  \n\t\nlast"

        assertEquals(code, normalizeCodeBlockContent(code))
    }

    @Test
    fun codeBlockContentKeepsIndentationAndTrailingWhitespaceOnContentLines() {
        val code = "  first  \n\tlast\t"

        assertEquals(code, normalizeCodeBlockContent(code))
    }

    @Test
    fun codeBlockContentNormalizesAllBlankInputToEmpty() {
        assertEquals("", normalizeCodeBlockContent("\n \n\t\n  \t\n"))
    }

    @Test
    fun fencedLanguageAliasesAreNormalized() {
        assertEquals("kotlin", normalizeCodeLanguage("{.KTS}"))
        assertEquals("javascript", normalizeCodeLanguage("jsx title=demo"))
        assertEquals("python", normalizeCodeLanguage("python3"))
        assertEquals("cpp", normalizeCodeLanguage("C++"))
        assertEquals("powershell", normalizeCodeLanguage("pwsh"))
        assertEquals("yaml", normalizeCodeLanguage("yml"))
    }

    @Test
    fun kotlinLexerDoesNotTreatCommentMarkersInsideStringsAsComments() {
        val code = "val endpoint = \"https://example.com/#part\" // request URL"

        val tokens = syntaxTokens(code, "kt")

        assertToken(code, tokens, "val", SyntaxKind.KEYWORD)
        assertToken(code, tokens, "\"https://example.com/#part\"", SyntaxKind.STRING)
        assertToken(code, tokens, "// request URL", SyntaxKind.COMMENT)
        assertEquals(1, tokens.count { it.kind == SyntaxKind.COMMENT })
    }

    @Test
    fun commonLanguageFamiliesProduceSemanticTokens() {
        val python = "def parse(value: str):\n    return value or None # fallback"
        val sql = "SELECT id, name FROM users WHERE id = 42 AND name = 'Ada'; -- one row"
        val json = "{\"enabled\": true, \"limit\": 1200}"

        assertToken(python, syntaxTokens(python, "py"), "def", SyntaxKind.KEYWORD)
        assertToken(python, syntaxTokens(python, "py"), "parse", SyntaxKind.FUNCTION)
        assertToken(python, syntaxTokens(python, "py"), "# fallback", SyntaxKind.COMMENT)
        assertToken(sql, syntaxTokens(sql, "postgresql"), "SELECT", SyntaxKind.KEYWORD)
        assertToken(sql, syntaxTokens(sql, "postgresql"), "42", SyntaxKind.NUMBER)
        assertToken(json, syntaxTokens(json, "json"), "\"enabled\"", SyntaxKind.PROPERTY)
        assertToken(json, syntaxTokens(json, "json"), "true", SyntaxKind.CONSTANT)
    }

    @Test
    fun markupLexerHighlightsTagsAttributesValuesAndComments() {
        val code = "<!-- title --><section data-kind=\"note\"><strong>Hi</strong></section>"
        val tokens = syntaxTokens(code, "html")

        assertToken(code, tokens, "<!-- title -->", SyntaxKind.COMMENT)
        assertToken(code, tokens, "section", SyntaxKind.TAG)
        assertToken(code, tokens, "data-kind", SyntaxKind.ATTRIBUTE)
        assertToken(code, tokens, "\"note\"", SyntaxKind.STRING)
        assertToken(code, tokens, "strong", SyntaxKind.TAG)
    }

    @Test
    fun syntaxPaletteChangesBetweenLightAndDarkThemes() {
        val light = highlightedCode("fun answer() = 42", "kotlin", darkTheme = false)
        val dark = highlightedCode("fun answer() = 42", "kotlin", darkTheme = true)

        assertEquals(light.text, dark.text)
        assertTrue(light.spanStyles.isNotEmpty())
        assertEquals(light.spanStyles.map { it.start to it.end }, dark.spanStyles.map { it.start to it.end })
        assertNotEquals(light.spanStyles.first().item.color, dark.spanStyles.first().item.color)
    }

    @Test
    fun explicitlyPlainCodeRemainsUnstyled() {
        assertTrue(syntaxTokens("val example = 12", "text").isEmpty())
    }

    private fun assertToken(
        code: String,
        tokens: List<SyntaxToken>,
        expectedText: String,
        expectedKind: SyntaxKind,
    ) {
        assertTrue(
            "Expected $expectedKind token for '$expectedText' in $tokens",
            tokens.any { token ->
                token.kind == expectedKind && code.substring(token.start, token.endExclusive) == expectedText
            },
        )
    }

    private fun knowledgeCitation(
        chunkId: Long,
        documentName: String,
        position: Int,
    ) = KnowledgeCitation(
        chunkId = chunkId,
        documentId = "document-$chunkId",
        documentName = documentName,
        position = position,
    )
}
