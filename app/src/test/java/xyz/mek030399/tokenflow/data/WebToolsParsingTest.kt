package xyz.mek030399.tokenflow.data

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WebToolsParsingTest {
    @Test
    fun webToolSchemasDescribeNetworkRecipientsReturnsDefaultsAndLimits() {
        val search = webSearchToolDefinition()
        val searchProperties = search.parameters.getValue("properties").jsonObject
        val count = searchProperties.getValue("count").jsonObject

        assertTrue(search.description.contains("over the network using Exa"))
        assertTrue(search.description.contains("Exa receives the query and requested result count"))
        assertTrue(search.description.contains("result titles, public URLs, optional published dates, and highlights"))
        assertTrue(searchProperties.getValue("query").jsonObject.getValue("description").jsonPrimitive.content.contains("sent to Exa"))
        assertEquals(1, count.getValue("minimum").jsonPrimitive.int)
        assertEquals(10, count.getValue("maximum").jsonPrimitive.int)
        assertTrue(count.getValue("description").jsonPrimitive.content.contains("defaults to 5"))

        val read = readUrlToolDefinition()
        val urlDescription = read.parameters.getValue("properties").jsonObject
            .getValue("url").jsonObject.getValue("description").jsonPrimitive.content

        assertTrue(read.description.contains("public HTTPS URL on port 443"))
        assertTrue(read.description.contains("target site receives the request"))
        assertTrue(read.description.contains("additional public HTTPS port 443 subresources"))
        assertTrue(read.description.contains("URL is also sent to InfoFlow"))
        assertTrue(read.description.contains("InfoFlow Markdown and page metadata"))
        assertTrue(read.description.contains("ongoing model conversation"))
        assertTrue(read.description.contains("PDF and other non-text documents are not guaranteed"))
        assertTrue(urlDescription.contains("no default"))
        assertTrue(urlDescription.contains("Do not include credentials, secrets, or personal data"))
    }

    @Test
    fun knowledgeToolDescriptionSetsRetrievalAndSafetyExpectations() {
        val definition = searchKnowledgeToolDefinition()
        val queryDescription = definition.parameters.getValue("properties").jsonObject
            .getValue("query").jsonObject.getValue("description").jsonPrimitive.content

        assertTrue(SEARCH_KNOWLEDGE_TOOL_DESCRIPTION.contains("entirely on this device"))
        assertTrue(SEARCH_KNOWLEDGE_TOOL_DESCRIPTION.contains("no request is sent to a network service"))
        assertTrue(SEARCH_KNOWLEDGE_TOOL_DESCRIPTION.contains("included in the ongoing model conversation"))
        assertTrue(SEARCH_KNOWLEDGE_TOOL_DESCRIPTION.contains("before web search"))
        assertTrue(SEARCH_KNOWLEDGE_TOOL_DESCRIPTION.contains("another tool call is available"))
        assertTrue(SEARCH_KNOWLEDGE_TOOL_DESCRIPTION.contains("untrusted passages"))
        assertTrue(SEARCH_KNOWLEDGE_TOOL_DESCRIPTION.contains("preserve source conflicts", ignoreCase = true))
        assertTrue(SEARCH_KNOWLEDGE_TOOL_DESCRIPTION.contains("exact document and reference"))
        assertTrue(queryDescription.contains("on-device knowledge index"))
        assertTrue(queryDescription.contains("no default"))
        assertTrue(queryDescription.contains("minimum context"))
    }

    @Test
    fun knowledgeSearchPropagatesCancellationAndConvertsOrdinaryFailures() = runTest {
        val cancellation = runCatching {
            executeKnowledgeSearch("{\"query\":\"project\"}") { throw CancellationException("stopped") }
        }.exceptionOrNull()
        val failure = executeKnowledgeSearch("{\"query\":\"project\"}") {
            throw IOException("index unavailable")
        }

        assertTrue(cancellation is CancellationException)
        assertFalse(failure.ok)
        assertTrue(failure.content.contains("index unavailable"))
    }

    @Test
    fun knowledgeSearchReturnsStableCitationsAndOneBasedChunks() = runTest {
        val result = executeKnowledgeSearch("{\"query\":\"project\"}") {
            listOf(
                KnowledgeSnippet(
                    chunkId = 42,
                    documentId = "document-1",
                    documentName = "Project notes.md",
                    position = 0,
                    text = "Project details",
                ),
            )
        }

        val citation = KnowledgeCitation(42, "document-1", "Project notes.md", 0)
        val item = DirectApiTransport.defaultJson.parseToJsonElement(result.content)
            .jsonObject.getValue("results").jsonArray.single().jsonObject

        assertTrue(result.ok)
        assertEquals(listOf(citation), result.citations)
        assertEquals("[[KB:42]]", citation.marker)
        assertEquals("Project notes.md · 片段 1", citation.displayLabel)
        assertEquals(citation.marker, item.getValue("citation").jsonPrimitive.content)
        assertEquals(citation.documentName, item.getValue("document").jsonPrimitive.content)
        assertEquals(1, item.getValue("chunk").jsonPrimitive.int)
        assertEquals("Project details", item.getValue("content").jsonPrimitive.content)
        assertFalse(result.content.contains("#0"))
    }

    @Test
    fun knowledgeSearchLimitKeepsValidJsonAndOnlyDeliveredCitations() = runTest {
        val oversizedText = "quoted \\\"line\\\"\\n".repeat(2_000)
        val omittedCitation = KnowledgeCitation(2, "document-2", "x".repeat(20_000), 0)
        val result = executeKnowledgeSearch("{\"query\":\"project\"}") {
            listOf(
                KnowledgeSnippet(1, "document-1", "Large.md", 0, oversizedText),
                KnowledgeSnippet(
                    omittedCitation.chunkId,
                    omittedCitation.documentId,
                    omittedCitation.documentName,
                    omittedCitation.position,
                    "content that cannot fit with its document name",
                ),
            )
        }

        val parsedResults = DirectApiTransport.defaultJson.parseToJsonElement(result.content)
            .jsonObject.getValue("results").jsonArray.map { it.jsonObject }

        assertTrue(result.content.length <= 20_000)
        assertEquals(1, parsedResults.size)
        assertEquals(listOf(1L), result.citations.map(KnowledgeCitation::chunkId))
        assertEquals(result.citations.map(KnowledgeCitation::marker), parsedResults.map {
            it.getValue("citation").jsonPrimitive.content
        })
        assertTrue(parsedResults.single().getValue("content").jsonPrimitive.content.isNotEmpty())
        assertTrue(parsedResults.single().getValue("content").jsonPrimitive.content.length < oversizedText.length)
        assertFalse(result.citations.contains(omittedCitation))
    }

    @Test
    fun knowledgeSearchSkipsOversizedMetadataAndStillFitsLaterResults() = runTest {
        val result = executeKnowledgeSearch("{\"query\":\"project\"}") {
            listOf(
                KnowledgeSnippet(1, "document-1", "x".repeat(20_000), 0, "first"),
                KnowledgeSnippet(2, "document-2", "Small.md", 1, "second"),
            )
        }
        val item = DirectApiTransport.defaultJson.parseToJsonElement(result.content)
            .jsonObject.getValue("results").jsonArray.single().jsonObject

        assertTrue(result.content.length <= 20_000)
        assertEquals(listOf(2L), result.citations.map(KnowledgeCitation::chunkId))
        assertEquals("[[KB:2]]", item.getValue("citation").jsonPrimitive.content)
        assertEquals("second", item.getValue("content").jsonPrimitive.content)
    }

    @Test
    fun oversizedKnowledgeSearchFailureRemainsBoundedValidJson() = runTest {
        val result = executeKnowledgeSearch("{\"query\":\"project\"}") {
            throw IOException("quoted \\\"failure\\\"\\n".repeat(2_000))
        }
        val error = DirectApiTransport.defaultJson.parseToJsonElement(result.content)
            .jsonObject.getValue("error").jsonPrimitive.content

        assertFalse(result.ok)
        assertTrue(result.content.length <= 20_000)
        assertTrue(error.isNotEmpty())
        assertTrue(result.citations.isEmpty())
    }

    @Test
    fun citationMetadataDecodesLegacyJsonAndRoundTripsNewFields() {
        val json = DirectApiTransport.defaultJson
        val legacy = json.decodeFromString<AssistantMetadata>(
            "{\"events\":[{\"type\":\"status\",\"message\":\"legacy\"}],\"completion_status\":\"completed\"}",
        )
        val citation = KnowledgeCitation(7, "document-7", "Guide.md", 2)
        val current = AssistantMetadata(
            events = listOf(ProcessEvent(type = "tool_completed", knowledgeCitations = listOf(citation))),
            completionStatus = "completed",
            knowledgeCitations = listOf(citation),
        )

        assertTrue(legacy.knowledgeCitations.isEmpty())
        assertTrue(legacy.events.single().knowledgeCitations.isEmpty())
        assertEquals(current, json.decodeFromString<AssistantMetadata>(json.encodeToString(current)))
    }

    @Test
    fun extractsArticleTextAndDropsScriptsNavigationAndForms() {
        val html = """
            <html><head><title>Example</title><script>steal()</script></head>
            <body><nav>Menu</nav><main><article><h1>Heading</h1><p>${"content ".repeat(30)}</p></article></main><form>secret</form></body></html>
        """.trimIndent()

        val result = extractReadableHtml(html, "https://example.com/page")

        assertTrue(result.startsWith("Example\n\nHeading"))
        assertFalse(result.contains("steal"))
        assertFalse(result.contains("Menu"))
        assertFalse(result.contains("secret"))
        assertFalse(shouldUseRenderedFallback(true, result))
    }

    @Test
    fun onlyShortHtmlUsesIsolatedWebViewFallback() {
        assertTrue(shouldUseRenderedFallback(true, "short"))
        assertFalse(shouldUseRenderedFallback(false, "short"))
        assertFalse(shouldUseRenderedFallback(true, "x".repeat(200)))
    }

    @Test
    fun boundedUrlReadAcceptsResponsesShorterThanLimit() {
        val expected = "short response".encodeToByteArray()

        assertArrayEquals(expected, Buffer().write(expected).readUrlBytes(2 * 1024 * 1024L))
    }

    @Test
    fun boundedUrlReadRejectsResponsesLargerThanLimit() {
        val source = Buffer().write(ByteArray(33))

        assertThrows(IOException::class.java) { source.readUrlBytes(32) }
    }
}
