package xyz.mek030399.tokenflow.data

import android.content.Context
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
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

class CustomAgentRepositoryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: TokenFlowDatabase
    private lateinit var dao: LocalDao
    private lateinit var secrets: SecretStore
    private lateinit var repository: ChatRepository
    private lateinit var gateway: CustomAgentGateway
    private lateinit var tools: CustomAgentTools
    private lateinit var customAgent: AgentProfile
    private lateinit var provider: ProviderConfig
    private lateinit var largeModel: ModelProfile
    private lateinit var smallModel: ModelProfile
    private val codec = ConfigArchiveCodec()

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, TokenFlowDatabase::class.java).build()
        dao = database.localDao()
        secrets = SecretStore(context)
        customAgent = AgentProfile(
            name = "Saved assistant",
            description = "User-created assistant",
            systemPrompt = "# Custom role\n" + "角色说明。".repeat(8_000) + "\nEnd of custom role.",
            createdAt = 1L,
            updatedAt = 1L,
        )
        provider = ProviderConfig(
            id = "custom-agent-test-" + UUID.randomUUID(),
            name = "Custom agent test provider",
            baseUrl = "https://api.example.com/v1",
            protocol = ProviderProtocol.OPENAI_RESPONSES,
        )
        largeModel = ModelProfile(
            id = "large-" + provider.id,
            providerId = provider.id,
            remoteId = "test-128k",
            maxOutputTokens = 16_384,
            contextWindowTokens = 131_072,
            isDefault = true,
        )
        smallModel = largeModel.copy(
            id = "small-" + provider.id,
            remoteId = "test-64k",
            contextWindowTokens = 65_536,
            isDefault = false,
        )
        dao.putProvider(provider.toEntity())
        dao.putModels(listOf(largeModel.toEntity(), smallModel.toEntity()))
        secrets.write(secrets.providerKeyName(provider.id), "test-only-key")
        gateway = CustomAgentGateway()
        tools = CustomAgentTools(context, secrets)
        repository = ChatRepository(
            dao = dao,
            secretStore = secrets,
            gateway = gateway,
            engine = DirectChatEngine(gateway, tools),
            archive = codec,
        )
    }

    @After
    fun tearDown() {
        secrets.remove(secrets.providerKeyName(provider.id))
        database.close()
    }

    @Test
    fun freshWorkspaceHasNoDefaultAgentsOrBundledRoleResource() = runBlocking {
        repository.initialize()
        repeat(3) {
            assertTrue(repository.workspace().agents.isEmpty())
            assertTrue(dao.agents().isEmpty())
        }
        assertEquals(0, context.resources.getIdentifier("syan_system_prompt", "raw", context.packageName))
        rejected { repository.createConversationFromAgent("missing-agent") }
        assertTrue(dao.conversations().isEmpty())
    }

    @Test
    fun customCopiesRemainIndependentAndExistingConversationPromptsSurviveEditsAndDeletion() = runBlocking {
        val saved = repository.saveAgent(customAgent)
        val copy = repository.saveAgent(saved.copy(id = UUID.randomUUID().toString(), name = "Saved assistant copy"))
        val originalChat = repository.createConversationFromAgent(saved.id)
        val copiedChat = repository.createConversationFromAgent(copy.id)

        val edited = repository.saveAgent(saved.copy(name = "Edited assistant", systemPrompt = "My edited role"))
        repeat(3) {
            assertEquals(setOf(edited, copy), repository.workspace().agents.toSet())
        }
        assertEquals(saved.createdAt, edited.createdAt)
        assertEquals(saved.systemPrompt, dao.agent(copy.id)?.systemPrompt)
        assertEquals(saved.systemPrompt, repository.conversation(originalChat.id).conversation.systemPrompt)
        assertEquals(saved.systemPrompt, repository.conversation(copiedChat.id).conversation.systemPrompt)

        repository.deleteAgent(saved.id)
        assertEquals(listOf(copy), repository.workspace().agents)
        assertEquals(saved.systemPrompt, repository.conversation(originalChat.id).conversation.systemPrompt)
        repository.deleteAgent(copy.id)
        assertTrue(repository.workspace().agents.isEmpty())
        assertEquals(saved.systemPrompt, repository.conversation(copiedChat.id).conversation.systemPrompt)
    }

    @Test
    fun savedAgentsInheritTheDefaultUnlessTheyBindAModel() = runBlocking {
        val saved = repository.saveAgent(customAgent)
        val inherited = repository.createConversationFromAgent(saved.id)
        assertEquals(saved.name, inherited.title)
        assertEquals(SettingMode.INHERIT, inherited.modelMode)
        assertNull(inherited.model)
        assertEquals(SettingMode.OVERRIDE, inherited.systemPromptMode)
        assertEquals(saved.systemPrompt, inherited.systemPrompt)
        assertEquals(largeModel.contextWindowTokens, repository.contextPreview(inherited.id).contextWindowTokens)

        val boundAgent = repository.saveAgent(saved.copy(id = UUID.randomUUID().toString(), name = "Bound assistant", modelId = smallModel.id))
        val bound = repository.createConversationFromAgent(boundAgent.id)
        assertEquals(SettingMode.OVERRIDE, bound.modelMode)
        assertEquals(smallModel.id, bound.model)
        assertEquals(smallModel.contextWindowTokens, repository.contextPreview(bound.id).contextWindowTokens)
        dao.setDefaultModel(smallModel.id)
        assertEquals(smallModel.contextWindowTokens, repository.contextPreview(inherited.id).contextWindowTokens)
    }

    @Test
    fun markdownAgentAtImportLimitRemainsReadableAndStartsWithTheFullPrompt() = runBlocking {
        val body = "x".repeat(MAX_MARKDOWN_AGENT_BYTES)
        val imported = ImportedMarkdownNote("Imported Markdown", body).toImportedAgent(largeModel.id)

        val saved = repository.saveAgent(imported)
        val loaded = repository.workspace().agents.single { it.id == saved.id }
        val conversation = repository.createConversationFromAgent(saved.id)

        assertEquals(body, loaded.systemPrompt)
        assertEquals(largeModel.id, loaded.modelId)
        assertEquals("Imported Markdown", conversation.title)
        assertEquals(body, repository.conversation(conversation.id).conversation.systemPrompt)
    }

    @Test
    fun longCustomPromptAndEnabledToolsSendAt128kAnd64kStopsBeforeTheModelCall() = runBlocking {
        val saved = repository.saveAgent(customAgent)
        val chat = repository.createConversationFromAgent(saved.id)
        assertEquals(saved.maxToolCalls, chat.maxToolCalls)
        assertTrue(chat.enableSearch)
        assertTrue(chat.enableRead)
        assertFalse(chat.enableKnowledge)
        assertFalse(chat.enableInfiniteCloud)
        val preview = repository.contextPreview(chat.id, request("preview"))
        assertTrue(preview.estimatedInputTokens > 60_000)
        assertTrue(preview.estimatedInputTokens + preview.outputReserve < 131_072)

        repository.sendMessage(chat.id, request("large")).toList()
        val call = gateway.requests.single()
        assertEquals(largeModel.id, call.model.id)
        assertEquals(saved.thinkingEffort, call.thinkingEffort)
        assertTrue(call.systemPrompt.contains(saved.systemPrompt))
        assertEquals(setOf("calculate", "convert_units", "web_search", "read_url") + DEVICE_INFO_TOOL_NAMES, call.tools.map(ToolDefinition::name).toSet())
        assertTrue(tools.options.all { it.enableSearch && it.enableRead && !it.enableKnowledge && !it.enableInfiniteCloud && it.cloudServerId == null })

        dao.setDefaultModel(smallModel.id)
        val smallChat = repository.createConversationFromAgent(saved.id)
        val smallPreview = repository.contextPreview(smallChat.id, request("small-preview"))
        assertTrue(smallPreview.estimatedInputTokens + smallPreview.outputReserve > 65_536)
        rejected { repository.sendMessage(smallChat.id, request("small")).toList() }
        assertEquals(1, gateway.requests.size)
        assertTrue(dao.messages(smallChat.id).isEmpty())
    }

    @Test
    fun archivesAndWorkspacePreserveCustomCopiesAndFormerlyReservedStoredIds() = runBlocking {
        val copy = repository.saveAgent(customAgent.copy(name = "Portable assistant"))
        // A pre-existing persistent row with the former registry ID is user data.
        val previous = customAgent.copy(id = "builtin:syan", name = "Previously stored assistant", systemPrompt = "Previously saved prompt")
        dao.putAgent(previous.toEntity())
        assertEquals(previous, repository.workspace().agents.single { it.id == previous.id })
        val edited = repository.saveAgent(previous.copy(name = "Edited stored assistant"))
        val snapshot = repository.createConversationFromAgent(previous.id)
        assertEquals(previous.systemPrompt, snapshot.systemPrompt)

        val password = "custom-agent-test-password".toCharArray()
        val exported = repository.exportConfiguration(password)
        assertEquals(setOf(copy, edited), codec.decode(exported, password).agents.toSet())
        repository.deleteAgent(copy.id)
        repository.deleteAgent(previous.id)
        assertTrue(repository.workspace().agents.isEmpty())
        val preview = repository.previewImport(exported, password)
        assertEquals(2, preview.newAgents)
        repository.applyImport(preview)
        assertEquals(setOf(copy, edited), repository.workspace().agents.toSet())
        assertEquals(previous.systemPrompt, repository.conversation(snapshot.id).conversation.systemPrompt)
        repository.deleteAgent(previous.id)
        assertEquals(listOf(copy), repository.workspace().agents)
    }

    private fun request(id: String) = SendMessageRequest(
        content = "请使用配置的角色说明。",
        enableSearch = true,
        enableRead = true,
        enableKnowledge = false,
        timeZone = "Asia/Hong_Kong",
        requestId = "custom-agent-test-$id",
    )

    private suspend fun rejected(action: suspend () -> Unit) {
        val failure = runCatching { action() }.exceptionOrNull()
        assertTrue("Expected IllegalArgumentException, got $failure", failure is IllegalArgumentException)
    }
}

private class CustomAgentGateway : ModelGateway() {
    val requests = mutableListOf<ModelCallRequest>()

    override fun stream(request: ModelCallRequest) = flow<ModelStreamEvent> {
        requests += request
        emit(ModelStreamEvent.TextDelta("已使用保存的自定义角色说明。"))
        emit(ModelStreamEvent.Completed)
    }
}

private class CustomAgentTools(context: Context, secrets: SecretStore) : ToolRunner {
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
