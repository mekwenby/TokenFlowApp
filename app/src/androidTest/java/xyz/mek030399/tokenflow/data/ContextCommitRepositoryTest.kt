package xyz.mek030399.tokenflow.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ContextCommitRepositoryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: TokenFlowDatabase
    private lateinit var dao: LocalDao
    private lateinit var secrets: SecretStore
    private lateinit var attachments: AttachmentStore
    private lateinit var repository: ChatRepository
    private lateinit var gateway: ContextCommitGateway
    private lateinit var tools: ContextCommitTools
    private lateinit var provider: ProviderConfig
    private lateinit var model: ModelProfile
    private val createdFiles = mutableListOf<File>()

    @Before fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, TokenFlowDatabase::class.java).build()
        dao = database.localDao()
        secrets = SecretStore(context)
        attachments = AttachmentStore(context, dao)
        provider = ProviderConfig(id = "context-commit-${System.nanoTime()}", name = "Test",
            baseUrl = "https://api.example.com/v1", protocol = ProviderProtocol.OPENAI_RESPONSES)
        model = ModelProfile(providerId = provider.id, remoteId = "test", maxOutputTokens = 512,
            contextWindowTokens = 16_384)
        dao.putProvider(provider.toEntity())
        dao.putModels(listOf(model.toEntity()))
        secrets.write(secrets.providerKeyName(provider.id), "test-only-key")
        gateway = ContextCommitGateway()
        tools = ContextCommitTools()
        repository = ChatRepository(dao, secrets, gateway, DirectChatEngine(gateway, tools),
            ConfigArchiveCodec(), attachmentStore = attachments)
    }

    @After fun tearDown() {
        createdFiles.forEach(File::delete)
        secrets.remove(secrets.providerKeyName(provider.id))
        database.close()
    }

    private suspend fun conversation(auto: Boolean = false) = repository.createConversation(
        ConversationWriteRequest(title = "Test", model = model.id, modelMode = SettingMode.OVERRIDE,
            maxToolCalls = 1, enableSearch = false, enableRead = false,
            contextPolicy = if (auto) ContextPolicy(ContextMode.SUMMARY, 1, true) else ContextPolicy()))

    @Test fun editedSummaryUsesDynamicToolSchemasAndClosesRejectedSession() = runBlocking {
        val chat = conversation()
        val raw = listOf(
            ChatMessage(conversationId = chat.id, role = "user", content = "old question", createdAt = 1),
            ChatMessage(conversationId = chat.id, role = "assistant", content = "old answer", createdAt = 2),
            ChatMessage(conversationId = chat.id, role = "user", content = "current question", createdAt = 3),
        )
        dao.putMessages(raw.map(ChatMessage::toEntity))
        val source = raw.take(2)
        val summary = ContextSummary(chat.id, "Historical facts", sourceMessageIds = source.map(ChatMessage::id),
            sourceDigest = ContextBuilder.digest(source), modelId = model.id, remoteModelId = model.remoteId)
        tools.dynamicDefinitions = listOf(ToolDefinition("mcp__large", "schema documentation ".repeat(3000), buildJsonObject {}))

        val failure = runCatching { repository.saveContextSummary(summary) }.exceptionOrNull()

        assertEquals("Edited summary exceeds the input budget", failure?.message)
        assertNull(dao.contextSummary(chat.id))
        assertEquals(ContextMode.FULL, dao.conversation(chat.id)!!.toDomain().contextPolicy.mode)
        assertEquals(1, tools.sessionsOpened)
        assertEquals(1, tools.sessionsClosed)
    }

    @Test fun regenerationDeletesArtifactsDeliveredWhileContextWasPreparing() = runBlocking {
        val chat = conversation(auto = true)
        val raw = listOf(
            ChatMessage(conversationId = chat.id, role = "user", content = "historical facts ".repeat(6000), createdAt = 1),
            ChatMessage(conversationId = chat.id, role = "assistant", content = "historical answer", createdAt = 2),
            ChatMessage(conversationId = chat.id, role = "user", content = "current question", createdAt = 3),
            ChatMessage(conversationId = chat.id, role = "assistant", content = "previous answer", createdAt = 4),
        )
        dao.putMessages(raw.map(ChatMessage::toEntity))
        gateway.summaryGate = CompletableDeferred()
        val regenerated = async(Dispatchers.IO) {
            repository.regenerate(chat.id, SendMessageRequest("", false, false,
                timeZone = "UTC", requestId = "replacement-${chat.id}")).toList()
        }
        try {
            gateway.summaryStarted.await()
            val artifact = attachments.persistCloudArtifact(raw.last().id, "late.txt", "artifact".toByteArray())
            val artifactFile = File(artifact.storedPath).also(createdFiles::add)
            assertTrue(artifactFile.isFile)
            gateway.summaryGate!!.complete(Unit)
            regenerated.await()

            assertNull(dao.message(raw.last().id))
            assertNull(dao.attachment(artifact.id))
            assertFalse(artifactFile.exists())
            assertEquals("Replacement answer", dao.messages(chat.id).last().content)
        } finally {
            gateway.summaryGate!!.complete(Unit)
            regenerated.cancel()
        }
    }
}

private class ContextCommitGateway : ModelGateway() {
    val summaryStarted = CompletableDeferred<Unit>()
    var summaryGate: CompletableDeferred<Unit>? = null

    override fun stream(request: ModelCallRequest) = flow {
        val summary = request.systemPrompt == ContextCompressor.PROMPT
        if (summary) {
            summaryStarted.complete(Unit)
            summaryGate?.await()
        }
        emit(ModelStreamEvent.TextDelta(if (summary) "Concise historical facts" else "Replacement answer"))
        emit(ModelStreamEvent.Completed)
    }
}

private class ContextCommitTools : ToolRunner {
    var dynamicDefinitions = emptyList<ToolDefinition>()
    var sessionsOpened = 0
    var sessionsClosed = 0

    override fun definitions(enableSearch: Boolean, enableRead: Boolean) = emptyList<ToolDefinition>()
    override suspend fun execute(call: CanonicalToolCall, enableSearch: Boolean, enableRead: Boolean) =
        ToolExecutionResult("unused", false)

    override suspend fun openSession(options: ToolOptions): ToolSession {
        sessionsOpened++
        return object : ToolSession {
            override val definitions = dynamicDefinitions.toList()
            override suspend fun execute(call: CanonicalToolCall) = ToolExecutionResult("unused", false)
            override suspend fun close() { sessionsClosed++ }
        }
    }
}
