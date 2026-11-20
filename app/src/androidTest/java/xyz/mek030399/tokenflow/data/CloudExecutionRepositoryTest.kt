package xyz.mek030399.tokenflow.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CloudExecutionRepositoryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val json = ConfigArchiveCodec.defaultJson
    private lateinit var database: TokenFlowDatabase
    private lateinit var dao: LocalDao
    private lateinit var secrets: SecretStore
    private lateinit var repository: ChatRepository
    private lateinit var gateway: CloudGateway
    private val fixtureId = UUID.randomUUID().toString()
    private val provider = ProviderConfig(id = "cloud-provider-$fixtureId", name = "Cloud fixture",
        baseUrl = "https://example.com/v1", protocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS)
    private val model = ModelProfile(id = "cloud-model-$fixtureId", providerId = provider.id, remoteId = "fixture")
    private val server = CloudServerProfile(id = "cloud-server-$fixtureId", name = "Isolated fixture",
        host = "example.invalid", username = "fixture", hostKeyAlgorithm = "ssh-ed25519",
        hostKeyBase64 = "fixture-public-key", hostKeyFingerprint = "SHA256:fixture", keyConfigured = true)

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, TokenFlowDatabase::class.java).build()
        dao = database.localDao()
        secrets = SecretStore(context)
        dao.putProvider(provider.toEntity())
        dao.putModels(listOf(model.toEntity()))
        dao.putCloudServer(server.toEntity())
        secrets.write(secrets.providerKeyName(provider.id), "fixture-only-key")
        secrets.write(secrets.cloudPrivateKeyName(server.id), "fixture-only-key-never-used-for-ssh")
        gateway = CloudGateway()
        repository = ChatRepository(dao, secrets, gateway, DirectChatEngine(gateway, CloudTools()), ConfigArchiveCodec())
    }

    @After
    fun tearDown() {
        secrets.remove(secrets.providerKeyName(provider.id))
        secrets.remove(secrets.cloudPrivateKeyName(server.id))
        database.close()
    }

    private suspend fun conversation(cloud: Boolean = true, budget: Int = 7): Conversation {
        val value = Conversation(id = UUID.randomUUID().toString(), title = "Fixture", model = model.id, modelMode = SettingMode.OVERRIDE,
            enableInfiniteCloud = cloud, cloudServerId = server.id.takeIf { cloud }, maxToolCalls = budget)
        dao.putConversation(value.toEntity())
        return value
    }

    private fun request(mode: CloudExecutionMode = CloudExecutionMode.REQUIRED) = SendMessageRequest(
        content = "Run Python and return the actual output", enableSearch = false, enableRead = false,
        timeZone = "UTC", requestId = UUID.randomUUID().toString(), cloudExecutionMode = mode)

    @Test
    fun requiredModePersistsAndRegenerationInheritsIt() = runBlocking {
        val conversation = conversation()
        repository.sendMessage(conversation.id, request()).toList()
        var messages = dao.messages(conversation.id).map(MessageEntity::toDomain)
        assertEquals(CloudExecutionMode.REQUIRED,
            json.decodeFromString<UserMessageMetadata>(messages.first().metadata).cloudExecutionMode)
        val firstAnswer = messages.last().assistantMetadata(json)
        assertTrue(firstAnswer.cloudEnabled)
        assertEquals(CloudExecutionMode.REQUIRED, firstAnswer.cloudExecutionMode)
        assertTrue(firstAnswer.events.any { it.type == "tool_completed" && it.cloudDispatched })
        assertEquals(ToolChoice.REQUIRED, gateway.requests[0].toolChoice)
        assertEquals(ToolChoice.AUTO, gateway.requests[1].toolChoice)
        assertTrue(gateway.requests[0].systemPrompt.contains("Current Infinite Cloud execution capabilities"))

        repository.regenerate(conversation.id, request(CloudExecutionMode.AUTO)).toList()
        messages = dao.messages(conversation.id).map(MessageEntity::toDomain)
        assertEquals(2, messages.size)
        assertEquals(CloudExecutionMode.REQUIRED, messages.last().assistantMetadata(json).cloudExecutionMode)
        assertEquals(ToolChoice.REQUIRED, gateway.requests[2].toolChoice)
        assertEquals(12, database.openHelper.readableDatabase.version)
    }

    @Test
    fun textOnlyRequiredAnswerFailsWithoutPublishingTheClaim() = runBlocking {
        val conversation = conversation()
        gateway.textOnly = true
        val events = mutableListOf<ChatEvent>()
        val failure = runCatching { repository.sendMessage(conversation.id, request()).collect { events += it } }.exceptionOrNull()
        assertTrue(failure is ConfigurationException)
        assertFalse(events.any { it is ChatEvent.Delta || it is ChatEvent.Done })
        val answer = dao.messages(conversation.id).map(MessageEntity::toDomain).last()
        assertEquals("failed", answer.status)
        assertEquals("", answer.content)
        assertEquals(CloudExecutionMode.REQUIRED, answer.assistantMetadata(json).cloudExecutionMode)
        assertFalse(answer.assistantMetadata(json).events.any { it.cloudDispatched })
    }

    @Test
    fun disabledCloudAndZeroBudgetRejectBeforeAcceptingOrCallingTheModel() = runBlocking {
        listOf(conversation(cloud = false), conversation(budget = 0)).forEach { conversation ->
            val failure = runCatching { repository.sendMessage(conversation.id, request()).toList() }.exceptionOrNull()
            assertTrue(failure is ConfigurationException)
            assertTrue(dao.messages(conversation.id).isEmpty())
        }
        assertTrue(gateway.requests.isEmpty())
    }

    @Test
    fun agentConversationAutomaticallyOffersCloudToolsAndPreservesRoleInstructions() = runBlocking {
        val agent = repository.saveAgent(AgentProfile(id = UUID.randomUUID().toString(), name = "Cloud agent",
            modelId = model.id, systemPrompt = "Keep this complete role", enableInfiniteCloud = true, cloudServerId = server.id))
        val conversation = repository.createConversationFromAgent(agent.id)
        dao.putConversation(conversation.copy(title = "Agent fixture").toEntity())
        repository.sendMessage(conversation.id, request(CloudExecutionMode.AUTO)).toList()
        val call = gateway.requests.first()
        assertEquals(ToolChoice.AUTO, call.toolChoice)
        assertTrue(call.systemPrompt.contains("Keep this complete role"))
        assertTrue(call.systemPrompt.contains("Current Infinite Cloud execution capabilities"))
        assertFalse(call.systemPrompt.contains("This message explicitly requires Cloud"))
        assertEquals(listOf("cloud_run_python"), call.tools.map(ToolDefinition::name))
    }

    @Test
    fun autoChatAndPreviewOmitCloudWhenCredentialsAreMissingOrBudgetIsZero() = runBlocking {
        val enabled = conversation()
        val budgetless = conversation(budget = 0)
        val autoRequest = request(CloudExecutionMode.AUTO)
        val available = repository.contextPreview(enabled.id, autoRequest)
        assertEquals(listOf("cloud_run_python"), available.tools.map(ToolDefinition::name))
        assertTrue(available.systemPrompt.contains("user's selected Linux server"))

        val noBudget = repository.contextPreview(budgetless.id, autoRequest)
        secrets.remove(secrets.cloudPrivateKeyName(server.id))
        val noKey = repository.contextPreview(enabled.id, autoRequest)
        listOf(noBudget, noKey).forEach { preview ->
            assertTrue(preview.tools.isEmpty())
            assertFalse(preview.systemPrompt.contains("Current Infinite Cloud execution capabilities"))
        }
        repository.sendMessage(enabled.id, autoRequest).toList()
        val call = gateway.requests.single()
        assertEquals(ToolChoice.AUTO, call.toolChoice)
        assertTrue(call.tools.isEmpty())
        assertFalse(call.systemPrompt.contains("Current Infinite Cloud execution capabilities"))
    }

    @Test
    fun previewAccountsForCloudPromptAndSwitchingOffRemovesIt() = runBlocking {
        val conversation = conversation()
        val preview = repository.contextPreview(conversation.id, request())
        assertTrue(preview.systemPrompt.contains("This message explicitly requires Cloud"))
        repository.updateConversation(conversation.id, ConversationWriteRequest(enableInfiniteCloud = false))
        val disabled = repository.contextPreview(conversation.id, request(CloudExecutionMode.AUTO))
        assertFalse(disabled.systemPrompt.contains("Current Infinite Cloud execution capabilities"))
    }

    private class CloudGateway : ModelGateway() {
        val requests = mutableListOf<ModelCallRequest>()
        var textOnly = false
        override fun stream(request: ModelCallRequest) = flow {
            requests += request
            if (!textOnly && request.tools.any { it.name == "cloud_run_python" } && request.messages.lastOrNull()?.role != "tool") {
                emit(ModelStreamEvent.ToolCallDelta(0, "fixture-call-${requests.size}", "cloud_run_python", "{\"code\":\"print(42)\"}"))
            } else emit(ModelStreamEvent.TextDelta(if (textOnly) "Done, here is code you can run" else "The actual output is 42"))
            emit(ModelStreamEvent.Completed)
        }
    }

    private class CloudTools : ToolRunner {
        override fun definitions(enableSearch: Boolean, enableRead: Boolean) = emptyList<ToolDefinition>()
        override suspend fun execute(call: CanonicalToolCall, enableSearch: Boolean, enableRead: Boolean) =
            ToolExecutionResult("{\"output\":\"42\",\"exit_code\":0}", true, cloudDispatched = true)
        override suspend fun openSession(options: ToolOptions): ToolSession {
            val definitions = infiniteCloudToolDefinitions(options).filter { it.name == "cloud_run_python" }
            return object : ToolSession {
                override val definitions = definitions
                override val cloudToolNames = definitions.map(ToolDefinition::name).toSet()
                override suspend fun execute(call: CanonicalToolCall) =
                    ToolExecutionResult("{\"output\":\"42\",\"exit_code\":0}", true, cloudDispatched = true)
            }
        }
    }
}
