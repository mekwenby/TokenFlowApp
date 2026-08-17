package xyz.mek030399.tokenflow.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteKnowledgeRepositoryTest {
    @Test
    fun noteImportIsConcurrentIdempotentAndRemainsAnIndependentSnapshot() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, TokenFlowDatabase::class.java).build()
        val dao = database.localDao()
        val secrets = SecretStore(context)
        val knowledgeStore = KnowledgeStore(context, dao)
        val repository = noteRepository(context, dao, secrets, ModelGateway(), knowledgeStore)
        val note = Note(
            id = "note-import-${System.nanoTime()}",
            title = "Original title",
            body = "Original durable note body",
            updatedAt = 1,
        )
        var currentDocumentId: String? = null

        try {
            dao.putNote(note.toEntity())
            val concurrentResults = coroutineScope {
                List(6) { async { repository.importNoteToKnowledge(note.id) } }.awaitAll()
            }
            val firstDocumentId = concurrentResults.map(KnowledgeDocument::id).distinct().single()
            currentDocumentId = firstDocumentId
            val stored = requireNotNull(dao.knowledgeDocument(firstDocumentId))

            assertEquals(1, dao.knowledgeDocuments().size)
            assertEquals(note.id, stored.sourceNoteId)
            assertEquals("ready", stored.status)
            assertEquals(note.body, File(stored.storedPath).readText(Charsets.UTF_8))

            dao.putNote(note.copy(title = "Edited title", body = "Edited note body", updatedAt = 2).toEntity())
            val afterEdit = repository.importNoteToKnowledge(note.id)
            assertEquals(firstDocumentId, afterEdit.id)
            assertEquals(note.body, File(stored.storedPath).readText(Charsets.UTF_8))

            dao.deleteNote(note.id)
            assertNull(dao.note(note.id))
            assertEquals(note.id, dao.knowledgeDocument(firstDocumentId)?.sourceNoteId)
            assertEquals(note.body, File(stored.storedPath).readText(Charsets.UTF_8))

            repository.deleteKnowledge(firstDocumentId)
            currentDocumentId = null
            assertNull(dao.knowledgeDocument(firstDocumentId))
            assertFalse(File(stored.storedPath).exists())

            val replacementNote = note.copy(
                title = "Replacement title",
                body = "Replacement knowledge body",
                updatedAt = 3,
            )
            dao.putNote(replacementNote.toEntity())
            val replacement = repository.importNoteToKnowledge(note.id)
            currentDocumentId = replacement.id

            assertNotEquals(firstDocumentId, replacement.id)
            assertEquals(note.id, replacement.sourceNoteId)
            assertEquals(replacementNote.body, File(replacement.storedPath).readText(Charsets.UTF_8))
            assertEquals(1, dao.knowledgeDocuments().size)
        } finally {
            currentDocumentId?.let { knowledgeStore.delete(it) }
            database.close()
        }
    }

    @Test
    fun failedNoteImportIsRemovedBeforeRetry() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, TokenFlowDatabase::class.java).build()
        val dao = database.localDao()
        val secrets = SecretStore(context)
        val knowledgeStore = KnowledgeStore(context, dao)
        val repository = noteRepository(context, dao, secrets, ModelGateway(), knowledgeStore)
        val noteId = "note-error-${System.nanoTime()}"
        var currentDocumentId: String? = null

        try {
            dao.putNote(Note(id = noteId, title = "Unreadable", body = "").toEntity())

            val first = repository.importNoteToKnowledge(noteId)
            val second = repository.importNoteToKnowledge(noteId)
            currentDocumentId = second.id

            assertEquals("error", first.status)
            assertEquals("error", second.status)
            assertNotEquals(first.id, second.id)
            assertNull(dao.knowledgeDocument(first.id))
            assertEquals(listOf(second.id), dao.knowledgeDocuments().map(KnowledgeDocumentEntity::id))
            assertEquals(noteId, dao.knowledgeDocumentForSourceNote(noteId)?.sourceNoteId)
        } finally {
            currentDocumentId?.let { knowledgeStore.delete(it) }
            database.close()
        }
    }

    @Test
    fun customRewritePromptOnlyAffectsTheBodyRequest() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, TokenFlowDatabase::class.java).build()
        val dao = database.localDao()
        val secrets = SecretStore(context)
        val providerId = "note-rewrite-${System.nanoTime()}"
        val provider = ProviderConfig(
            id = providerId,
            name = "Note rewrite test",
            baseUrl = "https://api.example.com/v1",
            protocol = ProviderProtocol.OPENAI_RESPONSES,
        )
        val model = ModelProfile(
            id = "model-$providerId",
            providerId = providerId,
            remoteId = "test-model",
        )
        val gateway = NoteResponseGateway(listOf("Rewritten **body** with facts.", "Generated title"))
        val repository = noteRepository(context, dao, secrets, gateway)
        val note = Note(
            id = "note-$providerId",
            title = "Original title",
            body = "Original body with https://example.com/source",
            updatedAt = 1,
        )
        val rewritePrompt = "Keep every URL and use bullet points."

        try {
            dao.putProvider(provider.toEntity())
            dao.putModels(listOf(model.toEntity()))
            dao.putNote(note.toEntity())
            secrets.write(secrets.providerKeyName(providerId), "test-key")

            val rewritten = repository.summarizeNote(note.id, model.id, "  $rewritePrompt  ")

            assertEquals(2, gateway.requests.size)
            val bodyRequest = gateway.requests[0]
            val titleRequest = gateway.requests[1]
            assertEquals(note.body, bodyRequest.messages.single().content)
            assertTrue(bodyRequest.systemPrompt.contains(rewritePrompt))
            assertTrue(bodyRequest.systemPrompt.startsWith(NOTE_REWRITE_SYSTEM_PROMPT))
            assertEquals(NOTE_TITLE_SYSTEM_PROMPT, titleRequest.systemPrompt)
            assertFalse(titleRequest.systemPrompt.contains(rewritePrompt))
            assertEquals("Rewritten **body** with facts.", titleRequest.messages.single().content)
            assertEquals("Generated title", rewritten.title)
            assertEquals("Rewritten **body** with facts.", rewritten.body)
            assertEquals(rewritten, dao.note(note.id)?.toDomain())
        } finally {
            secrets.remove(secrets.providerKeyName(providerId))
            database.close()
        }
    }
}

private fun noteRepository(
    context: android.content.Context,
    dao: LocalDao,
    secrets: SecretStore,
    gateway: ModelGateway,
    knowledgeStore: KnowledgeStore? = null,
) = ChatRepository(
    dao = dao,
    secretStore = secrets,
    gateway = gateway,
    engine = DirectChatEngine(
        gateway,
        WebToolExecutor(
            secretStore = secrets,
            exaClient = ExaClient(),
            urlReader = UrlReader(context),
            knowledgeStore = knowledgeStore,
        ),
    ),
    archive = ConfigArchiveCodec(),
    knowledgeStore = knowledgeStore,
)

private class NoteResponseGateway(private val responses: List<String>) : ModelGateway() {
    val requests = mutableListOf<ModelCallRequest>()

    override fun stream(request: ModelCallRequest) = flowOf<ModelStreamEvent>(
        ModelStreamEvent.TextDelta(responses[requests.size]),
        ModelStreamEvent.Completed,
    ).also { requests += request }
}
