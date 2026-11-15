package xyz.mek030399.tokenflow.data

import android.content.Context
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BuiltInAgentRepositoryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: TokenFlowDatabase
    private lateinit var dao: LocalDao
    private lateinit var secrets: SecretStore
    private lateinit var repository: ChatRepository
    private lateinit var gateway: BuiltInAgentGateway
    private lateinit var tools: BuiltInAgentTools
    private lateinit var syan: AgentProfile
    private lateinit var provider: ProviderConfig
    private lateinit var largeModel: ModelProfile
    private lateinit var smallModel: ModelProfile
    private val codec = ConfigArchiveCodec()

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, TokenFlowDatabase::class.java).build()
        dao = database.localDao()
        secrets = SecretStore(context)
        syan = BuiltInAgents.load(context).single { it.id == BuiltInAgents.SYAN_ID }
        provider = ProviderConfig(
            id = "syan-test-${UUID.randomUUID()}",
            name = "SYAN test provider",
            baseUrl = "https://api.example.com/v1",
            protocol = ProviderProtocol.OPENAI_RESPONSES,
        )
        largeModel = ModelProfile(
            id = "large-${provider.id}",
            providerId = provider.id,
            remoteId = "test-128k",
            maxOutputTokens = 16_384,
            contextWindowTokens = 131_072,
            isDefault = true,
        )
        smallModel = largeModel.copy(
            id = "small-${provider.id}",
            remoteId = "test-64k",
            contextWindowTokens = 65_536,
            isDefault = false,
        )
        dao.putProvider(provider.toEntity())
        dao.putModels(listOf(largeModel.toEntity(), smallModel.toEntity()))
        secrets.write(secrets.providerKeyName(provider.id), "test-only-key")
        gateway = BuiltInAgentGateway()
        tools = BuiltInAgentTools(context, secrets)
        repository = ChatRepository(
            dao = dao,
            secretStore = secrets,
            gateway = gateway,
            engine = DirectChatEngine(gateway, tools),
            archive = codec,
            builtInAgents = BuiltInAgents.load(context),
        )
    }

    @After
    fun tearDown() {
        secrets.remove(secrets.providerKeyName(provider.id))
        database.close()
    }

    @Test
    fun registryLoadsTheCompletePromptWithStableDefaults() {
        val agents = BuiltInAgents.load(context)
        assertEquals(agents, BuiltInAgents.load(context))
        assertEquals(agents.size, agents.map(AgentProfile::id).distinct().size)
        assertEquals("SYAN", syan.name)
        assertTrue(syan.description.contains("AI 原创角色"))
        assertNull(syan.modelId)
        assertEquals("medium", syan.thinkingEffort)
        assertEquals(7, syan.maxToolCalls)
        assertTrue(syan.enableSearch)
        assertTrue(syan.enableRead)
        assertFalse(syan.enableKnowledge)
        assertFalse(syan.enableInfiniteCloud)
        assertNull(syan.cloudServerId)
        assertEquals(0L, syan.createdAt)
        assertEquals(0L, syan.updatedAt)
        val bytes = syan.systemPrompt.toByteArray(Charsets.UTF_8)
        assertEquals(115_266, bytes.size)
        assertEquals(
            "e826d964088bc0b5f9716ae22a4231b01bb708179a03d6e4f47b9a70d6acc7bb",
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
        )
    }

    @Test
    fun refreshingKeepsOneReadOnlyOriginalAndCopiesHaveIndependentLifecycles() = runBlocking {
        assertEquals(listOf(syan), repository.workspace().agents)
        assertTrue(dao.agents().isEmpty())
        val custom = repository.saveAgent(syan.copy(id = UUID.randomUUID().toString(), name = "My SYAN"))
        // A stale or externally inserted row cannot shadow the bundled definition.
        dao.putAgent(syan.copy(name = "Shadow", systemPrompt = "Changed").toEntity())
        repeat(3) {
            assertEquals(listOf(syan, custom), repository.workspace().agents)
        }
        val password = "syan-test-password".toCharArray()
        assertEquals(listOf(custom), codec.decode(repository.exportConfiguration(password), password).agents)
        rejected { repository.saveAgent(syan.copy(systemPrompt = "Changed")) }
        rejected { repository.deleteAgent(syan.id) }
        assertEquals(syan, repository.workspace().agents.first())
        val originalChat = repository.createConversationFromAgent(syan.id)
        assertEquals(syan.systemPrompt, originalChat.systemPrompt)
        dao.deleteAgent(syan.id)

        val edited = repository.saveAgent(custom.copy(name = "Edited copy", systemPrompt = "My role"))
        assertEquals("My role", dao.agent(edited.id)?.systemPrompt)
        assertEquals(syan, repository.workspace().agents.first())
        repository.deleteAgent(custom.id)
        assertEquals(listOf(syan), repository.workspace().agents)
        assertTrue(dao.agents().isEmpty())
    }

    @Test
    fun startingAnAgentInheritsTheDefaultUnlessTheCopyBindsAModel() = runBlocking {
        val inherited = repository.createConversationFromAgent(syan.id)
        assertEquals("SYAN", inherited.title)
        assertEquals(SettingMode.INHERIT, inherited.modelMode)
        assertNull(inherited.model)
        assertEquals(SettingMode.OVERRIDE, inherited.systemPromptMode)
        assertEquals(syan.systemPrompt, inherited.systemPrompt)
        assertEquals(largeModel.contextWindowTokens, repository.contextPreview(inherited.id).contextWindowTokens)

        val boundCopy = repository.saveAgent(syan.copy(id = UUID.randomUUID().toString(), name = "Bound copy", modelId = smallModel.id))
        val bound = repository.createConversationFromAgent(boundCopy.id)
        assertEquals(SettingMode.OVERRIDE, bound.modelMode)
        assertEquals(smallModel.id, bound.model)
        assertEquals(smallModel.contextWindowTokens, repository.contextPreview(bound.id).contextWindowTokens)
        dao.setDefaultModel(smallModel.id)
        assertEquals(smallModel.contextWindowTokens, repository.contextPreview(inherited.id).contextWindowTokens)
    }

    @Test
    fun fullPromptAndEnabledToolsSendAt128kAnd64kStopsBeforeTheModelCall() = runBlocking {
        val chat = repository.createConversationFromAgent(syan.id)
        assertEquals(syan.maxToolCalls, chat.maxToolCalls)
        assertTrue(chat.enableSearch)
        assertTrue(chat.enableRead)
        assertFalse(chat.enableKnowledge)
        assertFalse(chat.enableInfiniteCloud)
        val preview = repository.contextPreview(chat.id, request("preview"))
        assertTrue(preview.estimatedInputTokens > 57_633)
        assertTrue(preview.estimatedInputTokens + preview.outputReserve < 131_072)

        repository.sendMessage(chat.id, request("large")).toList()
        val call = gateway.requests.single()
        assertEquals(largeModel.id, call.model.id)
        assertEquals("medium", call.thinkingEffort)
        assertTrue(call.systemPrompt.contains(syan.systemPrompt.trim()))
        assertEquals(setOf("calculate", "convert_units", "web_search", "read_url"), call.tools.map(ToolDefinition::name).toSet())
        assertTrue(tools.options.all { it.enableSearch && it.enableRead && !it.enableKnowledge && !it.enableInfiniteCloud && it.cloudServerId == null })

        dao.setDefaultModel(smallModel.id)
        val smallChat = repository.createConversationFromAgent(syan.id)
        val smallPreview = repository.contextPreview(smallChat.id, request("small-preview"))
        assertTrue(smallPreview.estimatedInputTokens + smallPreview.outputReserve > 65_536)
        rejected { repository.sendMessage(smallChat.id, request("small")).toList() }
        assertEquals(1, gateway.requests.size)
        assertTrue(dao.messages(smallChat.id).isEmpty())
    }

    @Test
    fun archivesRoundTripCopiesAndRejectReservedIdsAtPreviewAndApply() = runBlocking {
        val copy = repository.saveAgent(syan.copy(id = UUID.randomUUID().toString(), name = "Portable SYAN"))
        val password = "syan-test-password".toCharArray()
        val exported = repository.exportConfiguration(password)
        val payload = codec.decode(exported, password)
        assertEquals(listOf(copy), payload.agents)
        assertFalse(payload.agents.any { BuiltInAgents.isBuiltIn(it.id) })
        repository.deleteAgent(copy.id)
        val preview = repository.previewImport(exported, password)
        assertEquals(1, preview.newAgents)
        repository.applyImport(preview)
        assertEquals(listOf(syan, copy), repository.workspace().agents)

        val forbiddenCopy = copy.copy(id = UUID.randomUUID().toString(), name = "Must not be imported")
        val forbidden = ConfigArchivePayload(
            createdAt = 1,
            providers = emptyList(),
            models = emptyList(),
            agents = listOf(forbiddenCopy, syan.copy(name = "Replacement")),
        )
        val encoded = codec.encode(forbidden, password)
        rejected { repository.previewImport(encoded, password) }
        rejected {
            repository.applyImport(
                ImportPreview(
                    payload = forbidden,
                    newProviders = 0,
                    updatedProviders = 0,
                    newModels = 0,
                    updatedModels = 0,
                    newAgents = 2,
                    replacesExaKey = false,
                ),
            )
        }
        assertNull(dao.agent(forbiddenCopy.id))
        assertNull(dao.agent(syan.id))
        assertEquals(listOf(syan, copy), repository.workspace().agents)
    }

    private fun request(id: String) = SendMessageRequest(
        content = "下班了吗？",
        enableSearch = true,
        enableRead = true,
        enableKnowledge = false,
        timeZone = "Asia/Hong_Kong",
        requestId = "syan-test-$id",
    )

    private suspend fun rejected(action: suspend () -> Unit) {
        val failure = runCatching { action() }.exceptionOrNull()
        assertTrue("Expected IllegalArgumentException, got $failure", failure is IllegalArgumentException)
    }
}

private class BuiltInAgentGateway : ModelGateway() {
    val requests = mutableListOf<ModelCallRequest>()

    override fun stream(request: ModelCallRequest) = flow<ModelStreamEvent> {
        requests += request
        emit(ModelStreamEvent.TextDelta("刚下班。你呢？"))
        emit(ModelStreamEvent.Completed)
    }
}

private class BuiltInAgentTools(context: Context, secrets: SecretStore) : ToolRunner {
    private val delegate = WebToolExecutor(secrets, ExaClient(), UrlReader(context))
    val options = mutableListOf<ToolOptions>()

    override fun definitions(enableSearch: Boolean, enableRead: Boolean): List<ToolDefinition> = buildList {
        // Provide a configured-search schema without changing the device's Exa key.
        addAll(delegate.definitions(enableSearch = false, enableRead = enableRead))
        if (enableSearch) add(webSearchToolDefinition())
    }

    override fun definitions(options: ToolOptions): List<ToolDefinition> {
        this.options += options
        return definitions(options.enableSearch, options.enableRead)
    }

    override suspend fun execute(call: CanonicalToolCall, enableSearch: Boolean, enableRead: Boolean): ToolExecutionResult =
        error("No real tool calls are allowed in this test")
}
