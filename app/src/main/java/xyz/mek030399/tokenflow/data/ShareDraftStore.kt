package xyz.mek030399.tokenflow.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import java.io.File
import java.net.URLConnection
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
data class ComposerDraft(val text: String = "", val attachments: List<PendingAttachment> = emptyList(), val submittedRequestId: String? = null)
@Serializable
data class SharedFile(val id: String, val name: String, val attachment: PendingAttachment? = null, val error: String? = null)
@Serializable
data class IncomingShare(val id: String, val text: String = "", val files: List<SharedFile> = emptyList())
@Serializable
data class SavedShareState(val drafts: Map<String, ComposerDraft> = emptyMap(), val incoming: IncomingShare? = null, val receipts: List<String> = emptyList())

/** Owns copies, never persists a grant to an external app's temporary URI. */
class ShareDraftStore(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val root = File(context.applicationContext.filesDir, DIRECTORY_NAME).apply { mkdirs() }
    private val stateFile = AtomicFile(File(root, "state.json"))
    private val mutex = Mutex()
    private val json = ConfigArchiveCodec.defaultJson
    private data class SettledSubmission(val conversationId: String, val accepted: Boolean)
    private val settledSubmissions = linkedMapOf<String, SettledSubmission>()

    suspend fun load(): SavedShareState = mutex.withLock {
        withContext(Dispatchers.IO) {
            val state = runCatching { stateFile.openRead().use { json.decodeFromString<SavedShareState>(it.readBytes().toString(Charsets.UTF_8)) } }
                .getOrDefault(SavedShareState())
            val owned = (state.drafts.values.flatMap { it.attachments } + state.incoming?.files.orEmpty().mapNotNull { it.attachment })
                .mapNotNull { it.appOwnedDraftPath }.toSet()
            root.listFiles()?.filter { it.name != "state.json" && it.name != "state.json.bak" && it.name != "state.json.new" }
                ?.filter { it.absolutePath !in owned && System.currentTimeMillis() - it.lastModified() > MAX_IDLE_MILLIS }
                ?.forEach { it.delete() }
            state
        }
    }

    suspend fun save(state: SavedShareState) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val normalized = normalizeSettledSubmissions(state, readState())
            val stream = stateFile.startWrite()
            try {
                stream.write(json.encodeToString(normalized).toByteArray(Charsets.UTF_8))
                stateFile.finishWrite(stream)
            } catch (failure: Throwable) { stateFile.failWrite(stream); throw failure }
        }
    }

    /** Read-modify-write one shared state without another UI or generation overwriting the middle. */
    suspend fun update(transform: (SavedShareState) -> SavedShareState): SavedShareState = mutex.withLock {
        withContext(Dispatchers.IO) {
            val existing = readState()
            val updated = normalizeSettledSubmissions(transform(existing), existing)
            val stream = stateFile.startWrite()
            try {
                stream.write(json.encodeToString(updated).toByteArray(Charsets.UTF_8))
                stateFile.finishWrite(stream)
            } catch (failure: Throwable) { stateFile.failWrite(stream); throw failure }
            updated
        }
    }

    /** A delayed UI save must never revive a submitted draft after application-owned work settles. */
    suspend fun settleSubmission(
        requestId: String,
        conversationId: String,
        accepted: Boolean,
        transform: (SavedShareState) -> SavedShareState,
    ): SavedShareState = update { saved ->
        settledSubmissions[requestId] = SettledSubmission(conversationId, accepted)
        if (settledSubmissions.size > 256) settledSubmissions.remove(settledSubmissions.keys.first())
        transform(saved)
    }

    private fun readState(): SavedShareState = runCatching {
        stateFile.openRead().use { json.decodeFromString<SavedShareState>(it.readBytes().toString(Charsets.UTF_8)) }
    }.getOrDefault(SavedShareState())

    private fun normalizeSettledSubmissions(state: SavedShareState, previous: SavedShareState): SavedShareState {
        val drafts = state.drafts.toMutableMap()
        state.drafts.forEach { (key, draft) ->
            val requestId = draft.submittedRequestId ?: return@forEach
            val settled = settledSubmissions[requestId] ?: return@forEach
            if (key != settled.conversationId) drafts.remove(key)
            val newer = drafts[settled.conversationId]?.takeIf {
                it.submittedRequestId != requestId &&
                    (it.submittedRequestId != null || it.text.isNotEmpty() || it.attachments.isNotEmpty())
            } ?: previous.drafts[settled.conversationId]?.takeIf {
                it.submittedRequestId != requestId
            }
            drafts[settled.conversationId] = newer ?: if (settled.accepted) ComposerDraft()
                else draft.copy(submittedRequestId = null)
        }
        return state.copy(drafts = drafts)
    }

    @Suppress("DEPRECATION")
    suspend fun capture(intent: Intent): IncomingShare? = withContext(Dispatchers.IO) {
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return@withContext null
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
        val uris = buildList {
            if (intent.action == Intent.ACTION_SEND_MULTIPLE) addAll(intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty())
            else intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let(::add)
            intent.clipData?.let { clip -> repeat(clip.itemCount) { clip.getItemAt(it).uri?.let(::add) } }
        }.distinct()
        require(uris.size <= 100) { "Too many shared files" }
        var total = 0L
        val files = mutableListOf<SharedFile>()
        try {
            uris.forEachIndexed { index, uri ->
                val id = UUID.randomUUID().toString()
                var name = "shared-file"
                val result = runCatching {
                    if (index >= AttachmentStore.MAX_ATTACHMENTS) throw ShareReadException("many")
                    if (uri.scheme != "content") throw ShareReadException("uri")
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) name = cursor.getString(0).orEmpty()
                    }
                    name = name.substringAfterLast('/').substringAfterLast('\\').filterNot(Char::isISOControl).take(160).ifBlank { "shared-file" }
                    val mime = resolver.getType(uri) ?: URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream"
                    val reported = name.substringAfterLast('.', "").lowercase()
                    val extension = reported.takeIf { it in IMAGE_EXTENSIONS || it in DOCUMENT_EXTENSIONS }
                        ?: MIME_EXTENSIONS[mime] ?: if (mime.startsWith("text/")) "txt" else reported
                    val image = mime.startsWith("image/") || extension in IMAGE_EXTENSIONS
                    if (!image && extension !in DOCUMENT_EXTENSIONS) throw ShareReadException("type")
                    val suffix = extension.filter(Char::isLetterOrDigit).take(12).ifBlank { "bin" }
                    if (reported != extension || reported.isBlank()) name = "$name.$suffix"
                    val limit = if (image) AttachmentStore.MAX_IMAGE_BYTES else AttachmentStore.MAX_DOCUMENT_BYTES
                    val target = File(root, "$id.$suffix")
                    try {
                        resolver.openInputStream(uri)?.use { input ->
                            target.outputStream().use { output ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                var count = 0L
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    count += read
                                    if (count > limit || total + count > AttachmentStore.MAX_TOTAL_BYTES) throw ShareReadException("size")
                                    output.write(buffer, 0, read)
                                }
                                if (count <= 0) throw ShareReadException("empty")
                            }
                        } ?: error("Cannot read shared file")
                        total += target.length()
                        PendingAttachment(uri = "share:$id", displayName = name, mimeType = mime, sizeBytes = target.length(),
                            origin = PendingAttachmentOrigin.SHARE, appOwnedDraftPath = target.absolutePath)
                    } catch (failure: Throwable) { target.delete(); throw failure }
                }
                files += SharedFile(id, name, result.getOrNull(), result.exceptionOrNull()?.let { if (it is ShareReadException) it.reason else "read" })
            }
            IncomingShare(UUID.randomUUID().toString(), text, files)
        } catch (failure: Throwable) {
            discard(files.mapNotNull { it.attachment })
            throw failure
        }
    }

    suspend fun discard(attachments: List<PendingAttachment>) = withContext(Dispatchers.IO) {
        attachments.filter { it.origin == PendingAttachmentOrigin.SHARE }.forEach { attachment ->
            val file = attachment.appOwnedDraftPath?.let(::File)?.canonicalFile
            if (file?.parentFile == root.canonicalFile) file.delete()
        }
    }

    companion object {
        const val DIRECTORY_NAME = "share_drafts"
        const val MAX_IDLE_MILLIS = 24L * 60 * 60 * 1000
        private val MIME_EXTENSIONS = mapOf("application/pdf" to "pdf", "application/json" to "json", "application/xml" to "xml",
            "application/msword" to "doc", "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
            "application/vnd.ms-excel" to "xls", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx",
            "image/jpeg" to "jpg", "image/png" to "png", "image/webp" to "webp", "image/gif" to "gif", "image/heic" to "heic", "image/heif" to "heif")
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif")
        private val DOCUMENT_EXTENSIONS = setOf("txt", "md", "json", "csv", "xml", "yaml", "yml", "log", "kt", "kts", "java", "go", "py",
            "js", "ts", "tsx", "jsx", "c", "cc", "cpp", "h", "hpp", "cs", "rs", "swift", "sql", "sh", "ps1", "html", "css", "toml", "ini", "properties", "gradle", "doc", "docx", "xls", "xlsx", "pdf")
    }
}

private class ShareReadException(val reason: String) : IllegalArgumentException(reason)
