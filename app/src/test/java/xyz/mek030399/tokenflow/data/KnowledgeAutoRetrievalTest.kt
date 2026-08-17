package xyz.mek030399.tokenflow.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeAutoRetrievalTest {
    @Test
    fun manualChunksKeepPriorityAndAutomaticChunksFillToFive() {
        assertEquals(
            listOf(10L, 20L, 30L, 40L, 50L),
            mergeKnowledgeChunkIds(
                manualIds = listOf(10L, 20L, 10L),
                automaticIds = listOf(20L, 30L, 40L, 50L, 60L),
            ),
        )
    }

    @Test
    fun automaticHitsTrackManualAutomaticAndFinalSnippets() = runTest {
        val snippets = (10L..60L step 10).associateWith(::snippet)

        val result = resolveKnowledgeSnippets(
            manualIds = listOf(10L, 20L, 10L),
            enableAutomaticSearch = true,
            query = "matching query",
            loadManual = { listOf(snippets.getValue(20L), snippets.getValue(10L)) },
            automaticSearch = {
                listOf(20L, 30L, 40L, 50L, 60L).map(snippets::getValue)
            },
        )

        assertTrue(result.automaticAttempted)
        assertFalse(result.automaticFailed)
        assertEquals(listOf(10L, 20L), result.manualSnippets.map(KnowledgeSnippet::chunkId))
        assertEquals(listOf(20L, 30L, 40L, 50L, 60L), result.automaticSnippets.map(KnowledgeSnippet::chunkId))
        assertEquals(listOf(10L, 20L, 30L, 40L, 50L), result.finalSnippets.map(KnowledgeSnippet::chunkId))

        val event = requireNotNull(knowledgeRetrievalProcessEvent("request", result))
        assertEquals("knowledge_retrieval", event.type)
        assertEquals("knowledge_retrieval_hits", event.messageKey)
        assertEquals(result.finalSnippets.map(KnowledgeSnippet::chunkId), event.knowledgeCitations.map(KnowledgeCitation::chunkId))
    }

    @Test
    fun automaticZeroHitsProducesEmptyRetrievalEvent() = runTest {
        val result = resolveKnowledgeSnippets(
            manualIds = emptyList(),
            enableAutomaticSearch = true,
            query = "missing query",
            loadManual = { emptyList() },
            automaticSearch = { emptyList() },
        )

        assertTrue(result.automaticAttempted)
        assertFalse(result.automaticFailed)
        assertTrue(result.finalSnippets.isEmpty())
        val event = requireNotNull(knowledgeRetrievalProcessEvent("request", result))
        assertEquals("knowledge_retrieval_empty", event.messageKey)
        assertTrue(event.ok)
        assertTrue(event.knowledgeCitations.isEmpty())
    }

    @Test
    fun automaticFailureKeepsManualSnippetsAndReportsFailure() = runTest {
        val manual = listOf(snippet(7L), snippet(8L))

        val result = resolveKnowledgeSnippets(
            manualIds = listOf(7L, 8L),
            enableAutomaticSearch = true,
            query = "matching query",
            loadManual = { manual },
            automaticSearch = { error("index unavailable") },
        )

        assertTrue(result.automaticAttempted)
        assertTrue(result.automaticFailed)
        assertEquals("Automatic knowledge search failed", result.failureMessage)
        assertEquals(manual, result.finalSnippets)
        val event = requireNotNull(knowledgeRetrievalProcessEvent("request", result))
        assertEquals("knowledge_retrieval_failed", event.messageKey)
        assertFalse(event.ok)
        assertEquals(listOf(7L, 8L), event.knowledgeCitations.map(KnowledgeCitation::chunkId))
        assertFalse(event.message.contains(manual.first().text))
    }

    @Test
    fun manualOnlyLoadsDistinctSnippetsWithoutSearching() = runTest {
        var searched = false

        val result = resolveKnowledgeSnippets(
            manualIds = listOf(9L, 9L),
            enableAutomaticSearch = false,
            query = "matching query",
            loadManual = { listOf(snippet(9L)) },
            automaticSearch = {
                searched = true
                listOf(snippet(10L))
            },
        )

        assertFalse(searched)
        assertFalse(result.automaticAttempted)
        assertEquals(listOf(9L), result.finalSnippets.map(KnowledgeSnippet::chunkId))
        assertEquals(
            "knowledge_manual_loaded",
            requireNotNull(knowledgeRetrievalProcessEvent("request", result)).messageKey,
        )
    }

    @Test
    fun fiveManualIdsSkipAutomaticSearchAndRemainUncapped() = runTest {
        var searched = false
        val ids = listOf(1L, 2L, 3L, 4L, 5L, 6L)

        val result = resolveKnowledgeSnippets(
            manualIds = ids,
            enableAutomaticSearch = true,
            query = "matching query",
            loadManual = { requested -> requested.map(::snippet) },
            automaticSearch = {
                searched = true
                listOf(snippet(7L))
            },
        )

        assertFalse(searched)
        assertFalse(result.automaticAttempted)
        assertEquals(ids, result.finalSnippets.map(KnowledgeSnippet::chunkId))
    }

    @Test
    fun staleManualIdsDoNotConsumeAutomaticFillSlots() = runTest {
        var searched = false

        val result = resolveKnowledgeSnippets(
            manualIds = listOf(1L, 2L, 3L, 4L, 5L),
            enableAutomaticSearch = true,
            query = "matching query",
            loadManual = { listOf(snippet(1L)) },
            automaticSearch = {
                searched = true
                (2L..6L).map(::snippet)
            },
        )

        assertTrue(searched)
        assertTrue(result.automaticAttempted)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), result.finalSnippets.map(KnowledgeSnippet::chunkId))
    }

    @Test
    fun manualLoadFailureStillUsesAutomaticHitsAndReportsFailure() = runTest {
        val automatic = snippet(11L)

        val result = resolveKnowledgeSnippets(
            manualIds = listOf(7L),
            enableAutomaticSearch = true,
            query = "matching query",
            loadManual = { error("manual failure included ${automatic.text}") },
            automaticSearch = { listOf(automatic) },
        )

        assertTrue(result.manualFailed)
        assertFalse(result.automaticFailed)
        assertEquals("Manual knowledge loading failed", result.failureMessage)
        assertEquals(listOf(automatic), result.finalSnippets)
        val event = requireNotNull(knowledgeRetrievalProcessEvent("request", result))
        assertEquals("knowledge_retrieval_failed", event.messageKey)
        assertFalse(event.ok)
        assertEquals(listOf(11L), event.knowledgeCitations.map(KnowledgeCitation::chunkId))
        assertFalse(event.message.contains(automatic.text))
    }

    @Test
    fun automaticCancellationIsRethrown() = runTest {
        val cancellation = CancellationException("stop retrieval")
        var caught: CancellationException? = null

        try {
            resolveKnowledgeSnippets(
                manualIds = emptyList(),
                enableAutomaticSearch = true,
                query = "matching query",
                loadManual = { emptyList() },
                automaticSearch = { throw cancellation },
            )
        } catch (failure: CancellationException) {
            caught = failure
        }

        assertSame(cancellation, caught)
    }

    @Test
    fun manualLoadCancellationIsRethrown() = runTest {
        val cancellation = CancellationException("stop manual loading")
        var caught: CancellationException? = null

        try {
            resolveKnowledgeSnippets(
                manualIds = listOf(1L),
                enableAutomaticSearch = true,
                query = "matching query",
                loadManual = { throw cancellation },
                automaticSearch = { listOf(snippet(2L)) },
            )
        } catch (failure: CancellationException) {
            caught = failure
        }

        assertSame(cancellation, caught)
    }

    @Test
    fun injectedAndToolCitationsAreDeduplicatedInStableOrder() {
        val first = snippet(1L).toKnowledgeCitation()
        val second = snippet(2L).toKnowledgeCitation()
        val third = snippet(3L).toKnowledgeCitation()
        val retrievalOnly = snippet(4L).toKnowledgeCitation()
        val failedTool = snippet(5L).toKnowledgeCitation()

        val citations = aggregateKnowledgeCitations(
            injected = listOf(first, second),
            events = listOf(
                ProcessEvent(
                    type = "knowledge_retrieval",
                    knowledgeCitations = listOf(second, retrievalOnly),
                ),
                ProcessEvent(type = "tool_completed", knowledgeCitations = listOf(second, third)),
                ProcessEvent(type = "tool_failed", knowledgeCitations = listOf(third, failedTool)),
            ),
        )

        assertEquals(listOf(1L, 2L, 3L, 5L), citations.map(KnowledgeCitation::chunkId))
        assertEquals("[[KB:1]]", first.marker)
        assertEquals("document-1.md · 片段 2", first.displayLabel)
    }

    @Test
    fun injectionLimitExcludesCandidatesFromContextAndRetrievalEvent() {
        val first = snippet(1L).copy(text = "first passage body")
        val second = snippet(2L).copy(text = "second passage body")
        val firstHeader = "${first.toKnowledgeCitation().marker} ${first.toKnowledgeCitation().displayLabel}\n"

        val injected = buildInjectedKnowledgeContext(
            snippets = listOf(first, second),
            maxCharacters = firstHeader.length + 5,
        )
        val result = KnowledgeRetrievalResult(
            automaticAttempted = true,
            automaticFailed = false,
            manualSnippets = emptyList(),
            automaticSnippets = listOf(first, second),
            finalSnippets = listOf(first, second),
        )
        val event = requireNotNull(
            knowledgeRetrievalProcessEvent("request", result, injected.citations),
        )

        assertEquals(listOf(1L), injected.citations.map(KnowledgeCitation::chunkId))
        assertTrue(injected.content.contains("first"))
        assertFalse(injected.content.contains(second.toKnowledgeCitation().marker))
        assertEquals(listOf(1L), event.knowledgeCitations.map(KnowledgeCitation::chunkId))
    }

    private fun snippet(id: Long) = KnowledgeSnippet(
        chunkId = id,
        documentId = "document-$id",
        documentName = "document-$id.md",
        position = (id % 3).toInt(),
        text = "content-$id",
    )
}
