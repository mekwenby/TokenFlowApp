package xyz.mek030399.tokenflow.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ContextManagementTest {
    private fun message(id: String, role: String, body: String = id, status: String = "completed") =
        ChatMessage(id = id, conversationId = "chat", role = role, content = body, status = status)

    @Test fun recentRoundsKeepTheCurrentQuestionAndIgnoreFailedRepliesAndPreviousBoundary() {
        val raw = listOf(message("old", "user"), message("boundary", CONTEXT_BOUNDARY_ROLE),
            message("one", "user"), message("reply", "assistant"), message("failed", "assistant", status = "failed"),
            message("two", "user"), message("two-reply", "assistant"), message("three", "user"))
        assertEquals(listOf("two", "two-reply", "three"),
            ContextBuilder.select(raw, ContextPolicy(ContextMode.RECENT, 2), null).map { it.id })
    }

    @Test fun summaryCannotCrossAClearBoundaryOrAChangedSourceMessage() {
        val raw = listOf(message("one", "user"), message("reply", "assistant"), message("two", "user"))
        val source = raw.take(2)
        val summary = ContextSummary("chat", "notes", sourceMessageIds = source.map { it.id },
            sourceDigest = ContextBuilder.digest(source), modelId = "model", remoteModelId = "remote")
        assertNotNull(ContextBuilder.validSummary(summary, raw))
        assertNull(ContextBuilder.validSummary(summary, raw + message("reset", CONTEXT_BOUNDARY_ROLE)))
        assertNull(ContextBuilder.validSummary(summary, listOf(raw.first().copy(content = "edited")) + raw.drop(1)))
        assertEquals(listOf("two"), ContextBuilder.select(raw, ContextPolicy(ContextMode.SUMMARY), summary).map { it.id })
        assertEquals(raw, ContextBuilder.select(raw, ContextPolicy(), summary))
    }

    @Test fun eightyPercentThresholdReservesOutputAndUnknownCapacityHasNoTrigger() {
        val policy = ContextPolicy(ContextMode.SUMMARY, autoCompact = true)
        assertFalse(ContextPreview(policy, 799, 1200, 200, emptyList(), "").needsCompression)
        assertTrue(ContextPreview(policy, 800, 1200, 200, emptyList(), "").needsCompression)
        assertFalse(ContextPreview(policy, 9999, null, 200, emptyList(), "").needsCompression)
    }

    @Test fun compressionRequiresHistoryBeforeTheCurrentRoundBeyondTheSavedSummary() {
        val old = listOf(message("old", "user"), message("reply", "assistant"))
        val current = message("current", "user")
        val summary = ContextSummary("chat", "notes", sourceMessageIds = old.map { it.id },
            sourceDigest = ContextBuilder.digest(old), modelId = "model", remoteModelId = "remote")
        assertFalse(ContextBuilder.hasHistoryToCompress(listOf(current), null))
        assertTrue(ContextBuilder.hasHistoryToCompress(old + current, null))
        assertFalse(ContextBuilder.hasHistoryToCompress(old + current, summary))
        assertTrue(ContextBuilder.hasHistoryToCompress(old + message("more", "user") + current, summary))
        assertFalse(ContextBuilder.hasHistoryToCompress(old + message("reset", CONTEXT_BOUNDARY_ROLE) + current, summary))
    }

    @Test fun estimateCountsDocumentsToolsAndImageDimensionsButNotBase64Length() {
        val small = CanonicalMessage("user", parts = listOf(CanonicalContentPart.Image("image/png", "abc", 512, 512)))
        val same = small.copy(parts = listOf(CanonicalContentPart.Image("image/png", "x".repeat(100_000), 512, 512)))
        assertEquals(ContextBuilder.estimate("", listOf(small), emptyList()), ContextBuilder.estimate("", listOf(same), emptyList()))
        val big = small.copy(parts = listOf(CanonicalContentPart.Image("image/png", "abc", 4096, 4096)))
        assertTrue(ContextBuilder.estimate("", listOf(big), emptyList()) > ContextBuilder.estimate("", listOf(small), emptyList()))
        val document = CanonicalMessage("user", parts = listOf(CanonicalContentPart.Document("file.txt", "中文".repeat(1000))))
        assertTrue(ContextBuilder.estimate("system", listOf(document), OfflineCalculationTools().definitions()) > 3000)
    }

    @Test fun largeHistoryIsSummarizedInBoundedBatchesWithoutDroppingOriginals() = runTest {
        val raw = listOf(message("one", "user", "历史🙂".repeat(4000)), message("reply", "assistant", "data".repeat(3000)),
            message("current", "user", "question"))
        val canonical = raw.associate { it.id to CanonicalMessage(it.role, it.content) }
        val model = ModelProfile(providerId = "provider", remoteId = "model", maxOutputTokens = 256, contextWindowTokens = 4096)
        var calls = 0
        val captured = StringBuilder()
        val result = ContextCompressor.compress("chat", raw, canonical, ContextPolicy(ContextMode.SUMMARY, 1), model,
            "system", emptyList(), null, emptyList()) { messages, output ->
            calls++
            assertTrue(ContextBuilder.estimate(ContextCompressor.PROMPT, messages, emptyList()) + output <= 4096)
            messages.filterNot { it.content.startsWith("Previous reference notes") }.forEach { captured.append(it.content) }
            SummaryResponse("Concise notes", Usage(10, 3))
        }
        assertTrue(calls > 1)
        assertEquals(listOf("one", "reply"), result.sourceMessageIds)
        assertEquals(calls * 10L, result.usage.inputTokens)
        assertNotNull(ContextBuilder.validSummary(result, raw))
        assertTrue(captured.contains("🙂"))
        assertEquals(3, raw.size)
    }

    @Test fun oversizedCurrentQuestionStopsBeforeAnySummaryCall() = runTest {
        val raw = listOf(message("old", "user", "history".repeat(1000)), message("now", "user", "z".repeat(20_000)))
        var calls = 0
        val failure = runCatching {
            ContextCompressor.compress("chat", raw, raw.associate { it.id to CanonicalMessage(it.role, it.content) },
                ContextPolicy(ContextMode.SUMMARY, 1), ModelProfile(providerId = "p", remoteId = "m", maxOutputTokens = 256, contextWindowTokens = 4096),
                "system", emptyList(), null, emptyList()) { _, _ -> calls++; SummaryResponse("notes") }
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals(0, calls)
    }

    @Test fun emptyOrCancelledSummaryNeverReturnsACandidate() = runTest {
        val raw = listOf(message("old", "user", "history".repeat(1000)), message("now", "user"))
        val model = ModelProfile(providerId = "p", remoteId = "m", maxOutputTokens = 256, contextWindowTokens = 4096)
        for (cancelled in listOf(false, true)) {
            val failure = runCatching {
                ContextCompressor.compress("chat", raw, raw.associate { it.id to CanonicalMessage(it.role, it.content) },
                    ContextPolicy(ContextMode.SUMMARY, 1), model, "system", emptyList(), null, emptyList()) { _, _ ->
                    if (cancelled) throw CancellationException("stop") else SummaryResponse("")
                }
            }.exceptionOrNull()
            assertTrue(if (cancelled) failure is CancellationException else failure is IllegalArgumentException)
        }
    }
}
