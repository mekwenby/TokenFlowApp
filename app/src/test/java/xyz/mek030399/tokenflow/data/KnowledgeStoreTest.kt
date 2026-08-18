package xyz.mek030399.tokenflow.data

import java.io.StringReader
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeStoreTest {
    @Test
    fun chunksLongTextWithOverlapAndBoundedSize() {
        val text = (1..400).joinToString("\n\n") { "Paragraph $it contains enough searchable content." }
        val chunks = KnowledgeStore.chunk(text)

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 1_200 })
        assertTrue(chunks.zipWithNext().all { (left, right) ->
            left.takeLast(80).split(' ').any { it.length > 4 && right.contains(it) }
        })
    }

    @Test
    fun searchTextIncludesLatinTermsAndChineseBigrams() {
        val searchable = KnowledgeStore.searchable("Compose Room 数据库迁移与本地知识检索")

        assertTrue(searchable.contains("compose"))
        assertTrue(searchable.contains("room"))
        assertTrue(searchable.contains("数据"))
        assertTrue(searchable.contains("知识"))
        assertEquals(searchable.split(' ').distinct().size, searchable.split(' ').size)
    }

    @Test
    fun canonicalizesLineEndingsAndOuterWhitespace() {
        assertEquals(
            "first\nsecond\nthird",
            KnowledgeStore.canonicalize("  \r\nfirst\r\nsecond\rthird\n\t"),
        )
    }

    @Test
    fun boundedReadDistinguishesExactLimitFromTruncation() {
        val exact = KnowledgeStore.readBounded(StringReader("abcd"), 4)
        val truncated = KnowledgeStore.readBounded(StringReader("abcde"), 4)

        assertEquals("abcd", exact.text)
        assertFalse(exact.truncated)
        assertEquals("abcd", truncated.text)
        assertTrue(truncated.truncated)
    }

    @Test
    fun boundedReadDoesNotSplitUnicodeSurrogatePairs() {
        val beforePairBoundary = KnowledgeStore.readBounded(StringReader("a\uD83D\uDE00z"), 2)
        val afterPairBoundary = KnowledgeStore.readBounded(StringReader("\uD83D\uDE00z"), 2)

        assertEquals("a", beforePairBoundary.text)
        assertTrue(beforePairBoundary.truncated)
        assertEquals("\uD83D\uDE00", afterPairBoundary.text)
        assertTrue(afterPairBoundary.truncated)
    }

    @Test
    fun boundedWriterStopsAfterProvingTruncation() {
        var reachedAfterLimit = false
        val bounded = KnowledgeStore.writeBoundedText(maxChars = 4) { writer ->
            writer.write("abc")
            writer.write("def")
            reachedAfterLimit = true
        }

        assertEquals("abcd", bounded.text)
        assertTrue(bounded.truncated)
        assertFalse(reachedAfterLimit)
    }

    @Test
    fun boundedWriterKeepsExactLimitAndPropagatesCancellation() {
        val exact = KnowledgeStore.writeBoundedText(maxChars = 4) { writer -> writer.write("abcd") }

        assertEquals("abcd", exact.text)
        assertFalse(exact.truncated)
        assertThrows(CancellationException::class.java) {
            KnowledgeStore.writeBoundedText(
                maxChars = 4,
                checkActive = { throw CancellationException("cancelled") },
            ) { writer -> writer.write("a") }
        }
    }
}
