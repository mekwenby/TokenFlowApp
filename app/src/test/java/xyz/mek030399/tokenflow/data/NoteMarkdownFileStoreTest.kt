package xyz.mek030399.tokenflow.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class NoteMarkdownFileStoreTest {
    @Test
    fun importAcceptsCaseInsensitiveMdAndKeepsMultiDotTitle() {
        val body = "first\r\nsecond\r\n"

        val imported = parse("release.notes.v2.MD", body.toByteArray(StandardCharsets.UTF_8))

        assertEquals("release.notes.v2", imported.title)
        assertEquals(body, imported.body)
    }

    @Test
    fun importAllowsEmptyTitleForRepositoryFallback() {
        val imported = parse(".md", "body".toByteArray(StandardCharsets.UTF_8))

        assertEquals("", imported.title)
        assertEquals("body", imported.body)
    }

    @Test
    fun importRejectsMissingOrUnsupportedExtension() {
        assertFailure(NoteMarkdownFileError.UNSUPPORTED_EXTENSION) {
            parseImportedMarkdownNote(
                displayName = null,
                declaredSizeBytes = 4,
                input = ByteArrayInputStream("body".toByteArray()),
            )
        }
        assertFailure(NoteMarkdownFileError.UNSUPPORTED_EXTENSION) {
            parse("note.markdown", "body".toByteArray())
        }
    }

    @Test
    fun importRemovesOneUtf8BomAndPreservesTheRemainingBody() {
        val body = "# Heading\r\n\r\nBody  \r\n"
        val bytes = UTF8_BOM_BYTES + body.toByteArray(StandardCharsets.UTF_8)

        val imported = parse("bom.md", bytes)

        assertEquals(body, imported.body)
    }

    @Test
    fun importRejectsEmptyAndWhitespaceOnlyBodies() {
        listOf(byteArrayOf(), " \t\r\n".toByteArray()).forEach { bytes ->
            assertFailure(NoteMarkdownFileError.EMPTY) { parse("empty.md", bytes) }
        }
    }

    @Test
    fun importRejectsMalformedUtf8() {
        val malformed = byteArrayOf(0x23, 0x20, 0xC3.toByte(), 0x28)

        assertFailure(NoteMarkdownFileError.INVALID_UTF8) {
            parse("invalid.md", malformed)
        }
    }

    @Test
    fun importAcceptsExactlyTwoMiB() {
        val bytes = ByteArray(MAX_MARKDOWN_NOTE_BYTES.toInt()) { 'x'.code.toByte() }

        val imported = parseImportedMarkdownNote(
            displayName = "limit.md",
            declaredSizeBytes = MAX_MARKDOWN_NOTE_BYTES,
            input = ByteArrayInputStream(bytes),
        )

        assertEquals(MAX_MARKDOWN_NOTE_BYTES.toInt(), imported.body.length)
    }

    @Test
    fun importRejectsOversizedMetadataBeforeReading() {
        val input = object : ByteArrayInputStream("body".toByteArray()) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                throw AssertionError("Oversized metadata must be rejected before reading")
            }
        }

        assertFailure(NoteMarkdownFileError.TOO_LARGE) {
            parseImportedMarkdownNote(
                displayName = "large.md",
                declaredSizeBytes = MAX_MARKDOWN_NOTE_BYTES + 1,
                input = input,
            )
        }
    }

    @Test
    fun importRejectsActualStreamAboveTwoMiB() {
        val bytes = ByteArray(MAX_MARKDOWN_NOTE_BYTES.toInt() + 1) { 'x'.code.toByte() }

        assertFailure(NoteMarkdownFileError.TOO_LARGE) {
            parse("large.md", bytes)
        }
    }

    @Test
    fun exportWritesOnlyExactUtf8BodyAndRoundTrips() {
        val body = "# Notes\r\n\r\nUnicode: \u4F60\u597D\r\n"
        val output = ByteArrayOutputStream()

        writeMarkdownNoteBody(output, body)

        val bytes = output.toByteArray()
        assertArrayEquals(body.toByteArray(StandardCharsets.UTF_8), bytes)
        assertFalse(bytes.take(UTF8_BOM_BYTES.size).toByteArray().contentEquals(UTF8_BOM_BYTES))
        assertEquals(body, parse("round-trip.md", bytes).body)
    }

    @Test
    fun safeFileNameCleansWindowsCharactersAndTrailingDots() {
        assertEquals("a_b_c_d_e_f_g_h_i_j.md", markdownNoteFileName("a<b>c:d\"e/f\\g|h?i*j"))
        assertEquals("line_break.md", markdownNoteFileName("line\nbreak"))
        assertEquals("draft.md", markdownNoteFileName("draft..."))
        assertEquals("_CON.md", markdownNoteFileName("CON"))
    }

    @Test
    fun safeFileNameHandlesEmptyRepeatedSuffixAndUnicode() {
        assertEquals("note.md", markdownNoteFileName("  "))
        assertEquals("note.md", markdownNoteFileName(".MD"))
        assertEquals("Report.md", markdownNoteFileName(" Report.MD.md "))
        assertEquals("roadmap_Q3.md", markdownNoteFileName("roadmap/Q3"))
        assertEquals("\u4F1A\u8BAE\u7B14\u8BB0.md", markdownNoteFileName("\u4F1A\u8BAE\u7B14\u8BB0"))
        assertEquals("x".repeat(80) + ".md", markdownNoteFileName("x".repeat(81)))
        assertEquals("x".repeat(77) + ".md", markdownNoteFileName("x".repeat(77) + ".md-tail"))
    }

    @Test
    fun safeFileNameTruncatesAtUnicodeCodePointBoundaries() {
        val exact = "x".repeat(79) + "\uD83D\uDE80"

        assertEquals("$exact.md", markdownNoteFileName(exact))
        assertEquals("x".repeat(80) + ".md", markdownNoteFileName("x".repeat(80) + "\uD83D\uDE80"))
    }

    private fun parse(displayName: String, bytes: ByteArray): ImportedMarkdownNote =
        parseImportedMarkdownNote(
            displayName = displayName,
            declaredSizeBytes = bytes.size.toLong(),
            input = ByteArrayInputStream(bytes),
        )

    private fun assertFailure(
        expected: NoteMarkdownFileError,
        block: () -> Unit,
    ) {
        val failure = assertThrows(NoteMarkdownFileException::class.java, block)
        assertEquals(expected, failure.reason)
        assertEquals(expected, failure.error)
    }

    private companion object {
        val UTF8_BOM_BYTES = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    }
}
