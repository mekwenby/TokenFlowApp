package xyz.mek030399.tokenflow.ui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.mek030399.tokenflow.data.ProcessEvent

class WebLinkTitlesTest {
    @Test
    fun successfulSearchTitlesUseExactSourceUrlsAndLiteralNormalizedLabels() {
        val url = "https://example.test/report?item=1&item=2#section"
        val event = search(source(url, "  Official\u202E guide\n[beta] <docs>\t "))

        assertEquals(mapOf(url to "Official guide [beta] <docs>"), sourceLinkTitles(listOf(event)))
        assertFalse(sourceLinkTitles(listOf(event)).containsKey("https://example.test/report"))
    }

    @Test
    fun structuredReadTitleAppliesToOriginalFinalAndReturnedUrlWithoutRewriting() {
        val original = "https://example.test/go?token=fixture#part"
        val final = "https://example.test/article?language=zh"
        val alternate = "http://example.test/alternate"
        val event = ProcessEvent(
            type = "tool_completed", name = "read_url",
            arguments = buildJsonObject { put("url", original) }.toString(),
            result = buildJsonObject {
                put("title", "Read article")
                put("final_url", final)
                put("url", alternate)
                put("markdown", "Untrusted body")
            }.toString(),
        )

        assertEquals(mapOf(final to "Read article", alternate to "Read article", original to "Read article"),
            sourceLinkTitles(listOf(event)))
    }

    @Test
    fun onlySuccessfulCompletedKnownWebToolsCanSupplyTitles() {
        val valid = search(source("https://example.test/valid", "Valid"))
        val ignored = listOf(
            valid.copy(type = "tool_started"),
            valid.copy(type = "tool_failed"),
            valid.copy(ok = false),
            valid.copy(name = "mcp__untrusted__search"),
            valid.copy(name = "cloud_run_python"),
        )

        assertTrue(sourceLinkTitles(ignored).isEmpty())
        assertEquals(mapOf("https://example.test/valid" to "Valid"), sourceLinkTitles(ignored + valid))
    }

    @Test
    fun titleAndUrlMustBeStringsAndDestinationsMustBeSafeHttpUrls() {
        val sources = listOf(
            source("https://example.test/title-url", "https://example.test/not-a-title"),
            source("https://example.test/blank", " \n\u202E\u0000 "),
            source("javascript:alert(1)", "Script"),
            source("file:///private/file", "File"),
            source("//example.test/relative", "Relative"),
            buildJsonObject { put("title", 123); put("url", "https://example.test/numeric-title") },
            buildJsonObject { put("title", "Numeric URL"); put("url", 123) },
            buildJsonObject { put("title", JsonNull); put("url", "https://example.test/null-title") },
        )
        assertTrue(sourceLinkTitles(listOf(search(*sources.toTypedArray()))).isEmpty())
    }

    @Test
    fun malformedTruncatedPlainReadAndNestedUnknownStructuresAreIgnored() {
        val ignored = listOf(
            ProcessEvent(type = "tool_completed", name = "read_url", result = "Page title\nPlain reader body",
                arguments = """{"url":"https://example.test/plain"}"""),
            ProcessEvent(type = "tool_completed", name = "web_search", result = """{"results":[{"title":"Cut off","url":"""),
            ProcessEvent(type = "tool_completed", name = "web_search", result = "[]"),
            ProcessEvent(type = "tool_completed", name = "web_search",
                result = """{"untrusted":{"results":[{"title":"Hidden","url":"https://example.test/hidden"}]}}"""),
            ProcessEvent(type = "tool_completed", name = "web_search",
                result = """{"results":[{"untrusted":{"title":"Hidden","url":"https://example.test/hidden"}}]}"""),
        )
        assertTrue(sourceLinkTitles(ignored).isEmpty())
    }

    @Test
    fun jsonLimitIsEnforcedInUtf8BytesWithInclusiveBoundary() {
        val valid = search(source("https://example.test/boundary", "Boundary"))
        val exact = valid.result.padEnd(64 * 1024, ' ')
        assertEquals(64 * 1024, exact.encodeToByteArray().size)
        assertEquals(mapOf("https://example.test/boundary" to "Boundary"), sourceLinkTitles(listOf(valid.copy(result = exact))))
        assertTrue(sourceLinkTitles(listOf(valid.copy(result = exact + " "))).isEmpty())

        val multibyte = buildJsonObject {
            put("results", JsonArray(listOf(source("https://example.test/oversized", "Oversized"))))
            put("unused", "汉".repeat(22_000))
        }.toString()
        assertTrue(multibyte.length < 64 * 1024)
        assertTrue(multibyte.encodeToByteArray().size > 64 * 1024)
        assertTrue(sourceLinkTitles(listOf(valid.copy(result = multibyte))).isEmpty())
    }

    @Test
    fun oversizedOrMalformedArgumentsDoNotInvalidateStructuredFinalUrlTitle() {
        val event = ProcessEvent(
            type = "tool_completed", name = "read_url",
            arguments = """{"url":"https://example.test/original"}""".padEnd(64 * 1024 + 1, ' '),
            result = """{"title":"Article","final_url":"https://example.test/final"}""",
        )
        assertEquals(mapOf("https://example.test/final" to "Article"), sourceLinkTitles(listOf(event)))
        assertEquals(mapOf("https://example.test/final" to "Article"),
            sourceLinkTitles(listOf(event.copy(arguments = "{"))))
    }

    @Test
    fun latestValidTitleWinsAndOnlyLastEightyEventsAreInspected() {
        val url = "https://example.test/updated"
        assertEquals(mapOf(url to "Latest"), sourceLinkTitles(listOf(
            search(source(url, "Older")),
            search(source(url, "Latest")),
        )))
        val old = search(source("https://example.test/old", "Old"))
        val laterStatusEvents = List(80) { ProcessEvent(type = "status") }
        assertTrue(sourceLinkTitles(listOf(old) + laterStatusEvents).isEmpty())
        assertEquals(mapOf("https://example.test/old" to "Old"),
            sourceLinkTitles(listOf(old) + laterStatusEvents.take(79)))
    }

    @Test
    fun titleMapContainsAtMostOneHundredNewestSourceEntries() {
        val older = search(*(0 until 70).map { source("https://example.test/old-$it", "Older $it") }.toTypedArray())
        val newer = search(*(0 until 70).map { source("https://example.test/new-$it", "Newer $it") }.toTypedArray())
        val titles = sourceLinkTitles(listOf(older, newer))

        assertEquals(100, titles.size)
        assertTrue((0 until 70).all { titles["https://example.test/new-$it"] == "Newer $it" })
        assertEquals(30, titles.keys.count { "/old-" in it })
    }

    @Test
    fun titleNormalizationRemovesControlsAndBidiAndPreservesWholeUnicodeCodePoints() {
        assertEquals("Onetwo three", normalizeWebLinkTitle("\u0000One\u202Etwo\u2066\u2069 \nthree"))
        assertEquals("First Second", normalizeWebLinkTitle(" \tFirst\u00A0\nSecond  "))
        assertEquals("Safe", normalizeWebLinkTitle("S\u200Ba\u202Ef\u2066e"))
        assertNull(normalizeWebLinkTitle("\u0000\u202E\u2066 \n"))
        assertNull(normalizeWebLinkTitle("HTTPS://example.test/path?q=1"))
        val title = normalizeWebLinkTitle("😀".repeat(121))!!
        assertEquals(120, title.codePointCount(0, title.length))
        assertEquals("😀".repeat(120), title)
        assertFalse(Character.isHighSurrogate(title.last()))
    }

    private fun source(url: String, title: String): JsonObject = buildJsonObject {
        put("url", url)
        put("title", title)
    }

    private fun search(vararg results: JsonObject) = ProcessEvent(
        type = "tool_completed", name = "web_search",
        result = buildJsonObject { put("results", JsonArray(results.toList())) }.toString(),
    )
}
