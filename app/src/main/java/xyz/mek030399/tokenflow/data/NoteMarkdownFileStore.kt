package xyz.mek030399.tokenflow.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val MAX_MARKDOWN_NOTE_BYTES = 2L * 1024L * 1024L

internal data class ImportedMarkdownNote(
    val title: String,
    val body: String,
)

internal enum class NoteMarkdownFileError {
    UNSUPPORTED_EXTENSION,
    TOO_LARGE,
    EMPTY,
    INVALID_UTF8,
    READ_FAILED,
    WRITE_FAILED,
}

internal class NoteMarkdownFileException(
    val reason: NoteMarkdownFileError,
    cause: Throwable? = null,
) : Exception("Markdown note file operation failed: ${reason.name}", cause) {
    val error: NoteMarkdownFileError
        get() = reason
}

internal interface NoteMarkdownFileAccess {
    suspend fun read(uri: String): ImportedMarkdownNote
    suspend fun write(uri: String, body: String)
}

internal class NoteMarkdownFileStore(context: Context) : NoteMarkdownFileAccess {
    private val contentResolver = context.applicationContext.contentResolver

    override suspend fun read(uri: String): ImportedMarkdownNote = withContext(Dispatchers.IO) {
        try {
            val parsedUri = Uri.parse(uri)
            val metadata = queryMetadata(parsedUri)
            validateMetadata(metadata.displayName, metadata.sizeBytes)
            val input = contentResolver.openInputStream(parsedUri)
                ?: throw IOException("Unable to open the Markdown note")
            input.use { stream ->
                parseImportedMarkdownNote(metadata.displayName, metadata.sizeBytes, stream)
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: NoteMarkdownFileException) {
            throw failure
        } catch (failure: Exception) {
            throw NoteMarkdownFileException(NoteMarkdownFileError.READ_FAILED, failure)
        }
    }

    override suspend fun write(uri: String, body: String): Unit = withContext(Dispatchers.IO) {
        try {
            val output = contentResolver.openOutputStream(Uri.parse(uri), "wt")
                ?: throw IOException("Unable to open the Markdown note destination")
            output.use { stream -> writeMarkdownNoteBody(stream, body) }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            throw NoteMarkdownFileException(NoteMarkdownFileError.WRITE_FAILED, failure)
        }
    }

    private fun queryMetadata(uri: Uri): OpenableMetadata {
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return OpenableMetadata()
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            return OpenableMetadata(
                displayName = nameIndex.takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let(cursor::getString),
                sizeBytes = sizeIndex.takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let(cursor::getLong),
            )
        }
        return OpenableMetadata()
    }

    private data class OpenableMetadata(
        val displayName: String? = null,
        val sizeBytes: Long? = null,
    )
}

internal fun parseImportedMarkdownNote(
    displayName: String?,
    declaredSizeBytes: Long?,
    input: InputStream,
): ImportedMarkdownNote {
    val title = validateMetadata(displayName, declaredSizeBytes)
    val bytes = readLimitedBytes(input)
    val decoded = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (failure: CharacterCodingException) {
        throw NoteMarkdownFileException(NoteMarkdownFileError.INVALID_UTF8, failure)
    }
    val body = decoded.removePrefix(UTF8_BOM)
    if (body.isBlank()) {
        throw NoteMarkdownFileException(NoteMarkdownFileError.EMPTY)
    }
    return ImportedMarkdownNote(title = title, body = body)
}

internal fun writeMarkdownNoteBody(output: OutputStream, body: String) {
    output.write(body.toByteArray(StandardCharsets.UTF_8))
}

internal fun markdownNoteFileName(title: String): String {
    var base = stripMarkdownSuffixes(title)
        .map { character ->
            if (character.code in WINDOWS_CONTROL_CHARACTER_RANGE ||
                character in WINDOWS_INVALID_FILE_NAME_CHARACTERS
            ) {
                '_'
            } else {
                character
            }
        }
        .joinToString("")
        .trim()
        .trimEnd('.', ' ')
    base = stripMarkdownSuffixes(base)
        .takeCodePoints(MAX_MARKDOWN_NOTE_BASE_CHARACTERS)
        .trim()
        .trimEnd('.', ' ')
    base = stripMarkdownSuffixes(base).trimEnd('.', ' ')
    if (base.isBlank()) base = DEFAULT_MARKDOWN_NOTE_NAME
    if (WINDOWS_RESERVED_FILE_NAME.matches(base)) base = "_$base"
    return "$base$MARKDOWN_FILE_EXTENSION"
}

private fun String.takeCodePoints(maxCodePoints: Int): String {
    if (codePointCount(0, length) <= maxCodePoints) return this
    return substring(0, offsetByCodePoints(0, maxCodePoints))
}

private fun validateMetadata(displayName: String?, declaredSizeBytes: Long?): String {
    if (displayName == null || !displayName.endsWith(MARKDOWN_FILE_EXTENSION, ignoreCase = true)) {
        throw NoteMarkdownFileException(NoteMarkdownFileError.UNSUPPORTED_EXTENSION)
    }
    if (declaredSizeBytes != null && declaredSizeBytes > MAX_MARKDOWN_NOTE_BYTES) {
        throw NoteMarkdownFileException(NoteMarkdownFileError.TOO_LARGE)
    }
    return displayName.dropLast(MARKDOWN_FILE_EXTENSION.length).trim()
}

private fun readLimitedBytes(input: InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var totalBytes = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) continue
        totalBytes += count
        if (totalBytes > MAX_MARKDOWN_NOTE_BYTES) {
            throw NoteMarkdownFileException(NoteMarkdownFileError.TOO_LARGE)
        }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun stripMarkdownSuffixes(value: String): String {
    var result = value.trim()
    while (result.endsWith(MARKDOWN_FILE_EXTENSION, ignoreCase = true)) {
        result = result.dropLast(MARKDOWN_FILE_EXTENSION.length).trimEnd()
    }
    return result
}

private const val MARKDOWN_FILE_EXTENSION = ".md"
private const val DEFAULT_MARKDOWN_NOTE_NAME = "note"
private const val MAX_MARKDOWN_NOTE_BASE_CHARACTERS = 80
private const val UTF8_BOM = "\uFEFF"
private const val WINDOWS_INVALID_FILE_NAME_CHARACTERS = "<>:\"/\\|?*"
private val WINDOWS_CONTROL_CHARACTER_RANGE = 0..31
private val WINDOWS_RESERVED_FILE_NAME = Regex(
    pattern = "^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?$",
    option = RegexOption.IGNORE_CASE,
)
