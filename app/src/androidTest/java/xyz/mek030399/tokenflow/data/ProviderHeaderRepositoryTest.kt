package xyz.mek030399.tokenflow.data

import android.content.Context
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProviderHeaderRepositoryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val json = ConfigArchiveCodec.defaultJson
    private val codec = ConfigArchiveCodec()
    private val fixtureId = UUID.randomUUID().toString()
    private val providerId = "provider-headers-$fixtureId"
    private val draft = ProviderDraft(
        id = providerId,
        name = "Header fixture",
        baseUrl = "https://original.example.invalid/v1",
        protocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
        apiKey = "fixture-original-key",
    )
    private val model = ModelProfile(
        id = "model-$fixtureId",
        providerId = providerId,
        remoteId = "fixture-model",
        maxOutputTokens = 512,
        contextWindowTokens = 131_072,
        isDefault = true,
    )
    private val headers = listOf(
        ProviderRequestHeader(id = "template-$fixtureId", name = "User-Agent",
            value = "fixture-static-agent-$fixtureId", templateId = ProviderHeaderTemplate.OPENCODE.id),
        ProviderRequestHeader(id = "manual-$fixtureId", name = "X-Manual-Fixture", value = "saved-manual-$fixtureId"),
        ProviderRequestHeader(id = "disabled-$fixtureId", name = "Invalid disabled name",
            value = "disabled\nfixture-value", enabled = false),
    )
    private lateinit var database: TokenFlowDatabase
    private lateinit var dao: LocalDao
    private lateinit var secrets: SecretStore
    private lateinit var gateway: HeaderGateway
    private lateinit var repository: ChatRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, TokenFlowDatabase::class.java).build()
        dao = database.localDao()
        secrets = SecretStore(context)
        gateway = HeaderGateway()
        repository = ChatRepository(dao, secrets, gateway, DirectChatEngine(gateway, LocalCalculationTools()), codec)
    }

    @After
    fun tearDown() {
        secrets.remove(secrets.providerKeyName(providerId))
        secrets.remove(secrets.providerHeadersName(providerId))
        database.close()
    }

    @Test
    fun savedHeadersReloadWithRowIdentityAndDisabledValuesFromEncryptedStorage() = runBlocking {
        seed()

        val reloaded = repository.provider(providerId)!!.draft
        assertEquals(headers, reloaded.requestHeaders)
        assertEquals(draft.apiKey, reloaded.apiKey)
        val storedJson = secrets.read(secrets.providerHeadersName(providerId))!!
        assertEquals(headers, json.decodeFromString<List<ProviderRequestHeader>>(storedJson))
        val ciphertext = context.getSharedPreferences("tokenflow_secrets_v2", Context.MODE_PRIVATE)
            .getString(secrets.providerHeadersName(providerId), null)
        assertNotNull(ciphertext)
        assertFalse(ciphertext!!.contains(headers.first().value))
        assertFalse(ciphertext.contains(headers[1].value))
        assertEquals(12, database.openHelper.readableDatabase.version)
    }

    @Test
    fun customAuthenticationWithBlankApiKeyRemovesThePreviousDefaultCredential() = runBlocking {
        seed()
        val customAuthentication = listOf(ProviderRequestHeader(
            id = "auth-$fixtureId", name = "Authorization", value = "Bearer fixture-custom-authorization"))

        repository.saveProvider(draft.copy(apiKey = "", requestHeaders = customAuthentication), listOf(model))

        assertNull(secrets.read(secrets.providerKeyName(providerId)))
        val reloaded = repository.provider(providerId)!!.draft
        assertEquals("", reloaded.apiKey)
        assertEquals(customAuthentication, reloaded.requestHeaders)
        val chat = conversation()
        repository.sendMessage(chat.id, request("Use custom authentication")).toList()
        assertEquals("", gateway.requests.single().apiKey)
        assertEquals(customAuthentication, gateway.requests.single().requestHeaders)
    }

    @Test
    fun deletingAProviderRemovesItsKeyAndHeaderSecretsTogether() = runBlocking {
        seed()
        assertNotNull(secrets.read(secrets.providerKeyName(providerId)))
        assertNotNull(secrets.read(secrets.providerHeadersName(providerId)))

        repository.deleteProvider(providerId)

        assertNull(secrets.read(secrets.providerKeyName(providerId)))
        assertNull(secrets.read(secrets.providerHeadersName(providerId)))
        assertNull(dao.provider(providerId))
        assertTrue(dao.modelsForProvider(providerId).isEmpty())
    }

    @Test
    fun encryptedArchiveRestoresCompleteHeaderRowsIncludingIdsAndTemplateOwnership() = runBlocking {
        seed()
        val password = "fixture-archive-password".toCharArray()

        val archive = repository.exportConfiguration(password)
        assertFalse(archive.contains(headers.first().value))
        val record = codec.decode(archive, password).providers.single { it.provider.id == providerId }
        assertEquals(headers, record.requestHeaders)
        repository.deleteProvider(providerId)
        repository.applyImport(repository.previewImport(archive, password))

        assertEquals(headers, repository.provider(providerId)!!.draft.requestHeaders)
        assertEquals(draft.apiKey, secrets.read(secrets.providerKeyName(providerId)))
        assertEquals(listOf(model.id), dao.modelsForProvider(providerId).map { it.id })
    }

    @Test
    fun anArchiveWithoutTheHeaderFieldWarnsBeforeClearingExistingHeaders() = runBlocking {
        val saved = seed()
        val legacyJson = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        val legacyCodec = ConfigArchiveCodec(legacyJson)
        val payload = ConfigArchivePayload(createdAt = 1L,
            providers = listOf(ConfigProviderRecord(saved.copy(name = "Legacy imported provider"), "fixture-legacy-key")),
            models = listOf(model))
        assertFalse(legacyJson.encodeToString(payload).contains("request_headers"))
        val password = "fixture-legacy-password".toCharArray()

        val preview = repository.previewImport(legacyCodec.encode(payload, password), password)
        assertEquals(1, preview.resetsProviderHeaders)
        assertEquals(headers, repository.provider(providerId)!!.draft.requestHeaders)
        repository.applyImport(preview)

        assertNull(secrets.read(secrets.providerHeadersName(providerId)))
        assertTrue(repository.provider(providerId)!!.draft.requestHeaders.isEmpty())
        assertEquals("fixture-legacy-key", secrets.read(secrets.providerKeyName(providerId)))
        assertEquals("Legacy imported provider", dao.provider(providerId)?.name)
    }

    @Test
    fun rejectedProviderSaveRollsBackBothSecretsAndDatabaseRows() = runBlocking {
        seed()
        val previous = dao.provider(providerId)
        val previousModels = dao.modelsForProvider(providerId)
        val keyName = secrets.providerKeyName(providerId)
        val headersName = secrets.providerHeadersName(providerId)
        val oldKey = secrets.read(keyName)
        val oldHeaders = secrets.read(headersName)
        val sql = database.openHelper.writableDatabase
        listOf("INSERT", "UPDATE").forEach { operation ->
            sql.execSQL("""CREATE TRIGGER reject_provider_${operation.lowercase()}
                BEFORE $operation ON providers WHEN NEW.name = 'Rejected header fixture'
                BEGIN SELECT RAISE(ABORT, 'fixture rejects provider save'); END""".trimIndent())
        }

        val failure = runCatching {
            repository.saveProvider(draft.copy(name = "Rejected header fixture", apiKey = "fixture-replacement-key",
                requestHeaders = listOf(ProviderRequestHeader(name = "X-Replacement", value = "replacement-value"))),
                listOf(model.copy(displayName = "Replacement model")))
        }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty().contains("fixture rejects provider save"))
        assertEquals(previous, dao.provider(providerId))
        assertEquals(previousModels, dao.modelsForProvider(providerId))
        assertEquals(oldKey, secrets.read(keyName))
        assertEquals(oldHeaders, secrets.read(headersName))
    }

    @Test
    fun failedImportRestoresHeaderAndApiKeySecretsAlongWithTheProvider() = runBlocking {
        val saved = seed()
        val previous = dao.provider(providerId)
        val previousModels = dao.modelsForProvider(providerId)
        val keyName = secrets.providerKeyName(providerId)
        val headersName = secrets.providerHeadersName(providerId)
        val oldKey = secrets.read(keyName)
        val oldHeaders = secrets.read(headersName)
        val invalidModel = model.copy(id = "invalid-$fixtureId", providerId = "missing-$fixtureId")
        val payload = ConfigArchivePayload(createdAt = 1L,
            providers = listOf(ConfigProviderRecord(saved.copy(name = "Imported replacement"), "fixture-import-key",
                listOf(ProviderRequestHeader(name = "X-Imported", value = "imported-value")))),
            models = listOf(invalidModel))
        // Bypass preview's relationship validation to exercise the commit failure and compensation path.
        val preview = ImportPreview(payload = payload, newProviders = 0, updatedProviders = 1,
            newModels = 1, updatedModels = 0, replacesExaKey = false)

        val failure = runCatching { repository.applyImport(preview) }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty().contains("FOREIGN KEY constraint failed", ignoreCase = true))
        assertEquals(previous, dao.provider(providerId))
        assertEquals(previousModels, dao.modelsForProvider(providerId))
        assertNull(dao.model(invalidModel.id))
        assertEquals(oldKey, secrets.read(keyName))
        assertEquals(oldHeaders, secrets.read(headersName))
    }

    @Test
    fun aRunningToolRoundKeepsItsSnapshotAndLaterModelFeaturesUseTheNewConfiguration() = runBlocking {
        seed()
        val chat = conversation()
        gateway.pauseNext = true
        gateway.calculateNext = true
        val generation = async(Dispatchers.IO) {
            repository.sendMessage(chat.id, request("Historical user facts. ".repeat(700))).toList()
        }
        val newHeaders = listOf(ProviderRequestHeader(id = "new-$fixtureId", name = "X-New-Fixture", value = "new-static-value"))
        val updatedDraft = draft.copy(name = "Updated fixture", baseUrl = "https://updated.example.invalid/v2",
            apiKey = "fixture-updated-key", requestHeaders = newHeaders)
        try {
            withTimeout(30_000) { gateway.started.await() }
            repository.saveProvider(updatedDraft, listOf(model))
        } finally {
            gateway.resume.complete(Unit)
        }
        withTimeout(30_000) { generation.await() }

        assertEquals(2, gateway.requests.size)
        gateway.requests.take(2).forEach { call ->
            assertEquals(draft.baseUrl, call.provider.baseUrl)
            assertEquals(draft.apiKey, call.apiKey)
            assertEquals(headers, call.requestHeaders)
        }
        assertEquals("tool", gateway.requests[1].messages.last().role)
        repository.sendMessage(chat.id, request("Next question")).toList()
        repository.generateTitle(chat.id, force = true)
        val note = repository.saveNote(Note(title = "Original note", body = "Original note body for rewriting"))
        repository.summarizeNote(note.id, model.id, "")
        val sourceReply = dao.messages(chat.id).last { it.role == "assistant" }
        val linkedNote = repository.saveMessageAsNote(sourceReply.id)
        repository.summarizeNoteTitle(linkedNote.id)
        repository.updateConversation(chat.id, ConversationWriteRequest(contextPolicy = ContextPolicy(recentRounds = 1)))
        val summary = repository.compactContext(chat.id)
        assertTrue(summary.body.isNotBlank())

        val later = gateway.requests.drop(2)
        assertTrue(later.any { it.systemPrompt == InternalPrompts.CONVERSATION_TITLE })
        assertTrue(later.any { it.systemPrompt == InternalPrompts.NOTE_REWRITE })
        assertTrue(later.any { it.systemPrompt == InternalPrompts.NOTE_TITLE })
        assertTrue(later.any { it.systemPrompt == InternalPrompts.SAVED_NOTE_TITLE })
        assertTrue(later.any { it.systemPrompt == ContextCompressor.PROMPT })
        later.forEach { call ->
            assertEquals(updatedDraft.baseUrl, call.provider.baseUrl)
            assertEquals(updatedDraft.apiKey, call.apiKey)
            assertEquals(newHeaders, call.requestHeaders)
        }
    }

    private suspend fun seed(): ProviderConfig =
        repository.saveProvider(draft.copy(requestHeaders = headers), listOf(model))

    private suspend fun conversation(): Conversation {
        val chat = Conversation(title = "Fixture chat", model = model.id, modelMode = SettingMode.OVERRIDE,
            enableSearch = false, enableRead = false)
        dao.putConversation(chat.toEntity())
        return chat
    }

    private fun request(content: String) = SendMessageRequest(content = content,
        enableSearch = false, enableRead = false, timeZone = "UTC", requestId = UUID.randomUUID().toString())

    private class HeaderGateway : ModelGateway() {
        val requests = CopyOnWriteArrayList<ModelCallRequest>()
        val started = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        var pauseNext = false
        var calculateNext = false

        override fun stream(request: ModelCallRequest) = flow {
            requests += request
            if (pauseNext) {
                pauseNext = false
                started.complete(Unit)
                resume.await()
            }
            if (calculateNext) {
                calculateNext = false
                emit(ModelStreamEvent.ToolCallDelta(0, "fixture-calculation", "calculate", "{\"expression\":\"2+2\"}"))
            } else {
                val text = when (request.systemPrompt) {
                    InternalPrompts.NOTE_REWRITE -> "Concise rewritten note body"
                    InternalPrompts.NOTE_TITLE, InternalPrompts.SAVED_NOTE_TITLE, InternalPrompts.CONVERSATION_TITLE -> "Fixture generated title"
                    ContextCompressor.PROMPT -> "Historical user facts summarized."
                    else -> "The actual calculation result is 4."
                }
                emit(ModelStreamEvent.TextDelta(text))
            }
            emit(ModelStreamEvent.Completed)
        }
    }

    private class LocalCalculationTools : ToolRunner {
        private val tools = OfflineCalculationTools()
        override fun definitions(enableSearch: Boolean, enableRead: Boolean) = tools.definitions()
        override suspend fun execute(call: CanonicalToolCall, enableSearch: Boolean, enableRead: Boolean) = tools.execute(call)
    }
}
