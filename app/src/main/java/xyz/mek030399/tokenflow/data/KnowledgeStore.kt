package xyz.mek030399.tokenflow.data

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class KnowledgeStore(
    context: Context,
    private val dao: LocalDao,
) {
    private data class DocumentReservation(
        val entity: KnowledgeDocumentEntity,
        val destination: File,
        val owned: Boolean,
    )

    private val appContext = context.applicationContext
    private val directory = File(appContext.filesDir, "knowledge").also(File::mkdirs)

    init {
        PDFBoxResourceLoader.init(appContext)
    }

    suspend fun import(source: KnowledgeImportSource): KnowledgeDocument = withContext(Dispatchers.IO) {
        val name = source.displayName.trim().ifBlank { "document" }
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        require(extension in SUPPORTED_EXTENSIONS) { "Supported files: TXT, Markdown, JSON, CSV and PDF" }
        require(source.sizeBytes < 0 || source.sizeBytes <= MAX_FILE_BYTES) { "File exceeds the 20 MiB limit" }

        createAndIndex(name, source.mimeType, extension, source.sizeBytes.coerceAtLeast(0)) { destination ->
            appContext.contentResolver.openInputStream(Uri.parse(source.uri))?.use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_FILE_BYTES) { "File exceeds the 20 MiB limit" }
                        output.write(buffer, 0, count)
                    }
                    total
                }
            } ?: error("Unable to read the selected file")
        }
    }

    suspend fun importText(
        name: String,
        mimeType: String,
        text: String,
        sourceNoteId: String? = null,
    ): KnowledgeDocument = withContext(Dispatchers.IO) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size.toLong() <= MAX_FILE_BYTES) { "File exceeds the 20 MiB limit" }
        val displayName = markdownFileName(name)

        createAndIndex(
            displayName,
            mimeType.ifBlank { "text/markdown" },
            "md",
            bytes.size.toLong(),
            sourceNoteId,
        ) { destination ->
            destination.writeBytes(bytes)
            bytes.size.toLong()
        }
    }

    private suspend fun createAndIndex(
        name: String,
        mimeType: String,
        extension: String,
        initialSize: Long,
        sourceNoteId: String? = null,
        copyTo: (File) -> Long,
    ): KnowledgeDocument {
        val reservation = reserveDocument(
            name = name,
            mimeType = mimeType,
            extension = extension,
            initialSize = initialSize,
            sourceNoteId = sourceNoteId,
        )
        if (!reservation.owned) return reservation.entity.toDomain()
        val destination = reservation.destination
        var entity = reservation.entity
        return try {
            val copied = copyTo(destination)
            val text = extract(destination, extension).take(MAX_TEXT_CHARS)
            require(text.isNotBlank()) { "No readable text was found" }
            val pieces = chunk(text)
            dao.replaceKnowledgeChunks(
                entity.id,
                pieces.mapIndexed { index, value ->
                    KnowledgeChunkEntity(
                        documentId = entity.id,
                        position = index,
                        text = value,
                        searchText = searchable(value),
                    )
                },
            )
            entity = entity.copy(
                sizeBytes = copied,
                status = "ready",
                chunkCount = pieces.size,
                updatedAt = System.currentTimeMillis(),
            )
            dao.putKnowledgeDocument(entity)
            entity.toDomain()
        } catch (error: Throwable) {
            entity = entity.copy(
                status = "error",
                error = error.message.orEmpty().ifBlank { "Indexing failed" }.take(500),
                updatedAt = System.currentTimeMillis(),
            )
            dao.putKnowledgeDocument(entity)
            entity.toDomain()
        }
    }

    private suspend fun reserveDocument(
        name: String,
        mimeType: String,
        extension: String,
        initialSize: Long,
        sourceNoteId: String?,
    ): DocumentReservation {
        while (true) {
            val now = System.currentTimeMillis()
            val id = UUID.randomUUID().toString()
            val destination = File(directory, "$id.${extension.ifBlank { "txt" }}")
            val candidate = KnowledgeDocumentEntity(
                id = id,
                name = name,
                mimeType = mimeType,
                storedPath = destination.absolutePath,
                sizeBytes = initialSize,
                status = "indexing",
                error = "",
                chunkCount = 0,
                createdAt = now,
                updatedAt = now,
                sourceNoteId = sourceNoteId,
            )
            if (dao.insertKnowledgeDocument(candidate) != INSERT_IGNORED) {
                return DocumentReservation(candidate, destination, owned = true)
            }

            val existing = sourceNoteId?.let { dao.knowledgeDocumentForSourceNote(it) }
                ?: dao.knowledgeDocument(id)
                ?: continue
            if (existing.status != "error") {
                return DocumentReservation(existing, File(existing.storedPath), owned = false)
            }
            delete(existing.id)
        }
    }

    private fun markdownFileName(value: String): String {
        val name = value.trim().ifBlank { "note" }
        return if (name.substringAfterLast('.', "").lowercase(Locale.ROOT) in MARKDOWN_EXTENSIONS) name else "$name.md"
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val entity = dao.knowledgeDocument(id) ?: return@withContext
        dao.replaceKnowledgeChunks(id, emptyList())
        dao.deleteKnowledgeDocument(id)
        File(entity.storedPath).takeIf(File::exists)?.delete()
    }

    suspend fun snippets(ids: List<Long>): List<KnowledgeSnippet> {
        if (ids.isEmpty()) return emptyList()
        return dao.knowledgeChunks(ids.distinct()).mapNotNull { chunk ->
            dao.knowledgeDocument(chunk.documentId)?.let { document -> chunk.toSnippet(document, 0) }
        }.sortedBy { ids.indexOf(it.chunkId) }
    }

    suspend fun search(query: String, limit: Int = 5): List<KnowledgeSnippet> {
        val terms = tokenize(query).distinct().take(12)
        if (terms.isEmpty()) return emptyList()
        val fts = terms.joinToString(" OR ") { "\"${it.replace("\"", "\"\"")}\"" }
        return dao.searchKnowledgeChunks(fts, 40).mapNotNull { chunk ->
            val document = dao.knowledgeDocument(chunk.documentId) ?: return@mapNotNull null
            val normalized = chunk.text.lowercase(Locale.ROOT)
            val score = terms.sumOf { term ->
                var index = normalized.indexOf(term)
                var count = 0
                while (index >= 0) {
                    count += 1
                    index = normalized.indexOf(term, index + term.length)
                }
                count
            } + if (normalized.contains(query.trim().lowercase(Locale.ROOT))) 8 else 0
            chunk.toSnippet(document, score)
        }.sortedWith(compareByDescending<KnowledgeSnippet> { it.score }.thenBy { it.documentName }.thenBy { it.position })
            .take(limit.coerceIn(1, 5))
    }

    private fun extract(file: File, extension: String): String = if (extension == "pdf") {
        PDDocument.load(file).use { document ->
            require(document.numberOfPages <= MAX_PDF_PAGES) { "PDF exceeds the 500 page limit" }
            PDFTextStripper().getText(document)
        }
    } else {
        file.inputStream().bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    companion object {
        const val MAX_FILE_BYTES = 20L * 1024 * 1024
        const val MAX_PDF_PAGES = 500
        const val MAX_TEXT_CHARS = 2_000_000
        private const val CHUNK_SIZE = 1_200
        private const val CHUNK_OVERLAP = 200
        private const val INSERT_IGNORED = -1L
        private val MARKDOWN_EXTENSIONS = setOf("md", "markdown")
        private val SUPPORTED_EXTENSIONS = setOf("txt", "md", "markdown", "json", "csv", "pdf")

        internal fun chunk(raw: String): List<String> {
            val normalized = raw.replace("\r\n", "\n").replace('\r', '\n').trim()
            if (normalized.isEmpty()) return emptyList()
            val result = mutableListOf<String>()
            var start = 0
            while (start < normalized.length) {
                var end = minOf(start + CHUNK_SIZE, normalized.length)
                if (end < normalized.length) {
                    val paragraph = normalized.lastIndexOf("\n\n", end)
                    if (paragraph > start + CHUNK_SIZE / 2) end = paragraph
                }
                result += normalized.substring(start, end).trim()
                if (end == normalized.length) break
                start = (end - CHUNK_OVERLAP).coerceAtLeast(start + 1)
            }
            return result.filter(String::isNotBlank)
        }

        internal fun searchable(value: String): String = tokenize(value).distinct().joinToString(" ")

        internal fun tokenize(value: String): List<String> {
            val lowered = value.lowercase(Locale.ROOT)
            val latin = Regex("[\\p{L}\\p{N}_-]{2,}").findAll(lowered)
                .map(MatchResult::value)
                .filter { token -> token.none(::isCjk) }
                .toList()
            val cjkRuns = Regex("[\\u3400-\\u4dbf\\u4e00-\\u9fff]+").findAll(lowered).map(MatchResult::value)
            val bigrams = cjkRuns.flatMap { run ->
                when (run.length) {
                    0 -> emptySequence()
                    1 -> sequenceOf(run)
                    else -> (0 until run.length - 1).asSequence().map { run.substring(it, it + 2) }
                }
            }.toList()
            return latin + bigrams
        }

        private fun isCjk(char: Char) = char.code in 0x3400..0x4DBF || char.code in 0x4E00..0x9FFF
    }
}

private fun KnowledgeChunkEntity.toSnippet(document: KnowledgeDocumentEntity, score: Int) = KnowledgeSnippet(
    chunkId = id,
    documentId = documentId,
    documentName = document.name,
    position = position,
    text = text,
    score = score,
)
