package xyz.mek030399.tokenflow.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantIdentityRepositoryTest {
    private val json = DirectApiTransport.defaultJson

    @Test
    fun identitySnapshotSurvivesGenerationRegenerationFailureInterruptionAndBranching() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, TokenFlowDatabase::class.java).build()
        val dao = database.localDao()
        val secrets = SecretStore(context)
        val suffix = System.nanoTime().toString()
        val provider = ProviderConfig(
            id = "identity-provider-$suffix",
            name = "Identity provider",
            baseUrl = "https://api.example.com/v1",
            protocol = ProviderProtocol.OPENAI_RESPONSES,
        )
        val model = ModelProfile(
            id = "identity-model-$suffix",
            providerId = provider.id,
            remoteId = "provider/actual-model-v1",
        )
        val gateway = IdentityModelGateway()
        val repository = ChatRepository(
            dao = dao,
            secretStore = secrets,
            gateway = gateway,
            engine = DirectChatEngine(gateway, NoIdentityTestTools),
            archive = ConfigArchiveCodec(),
        )

        try {
            dao.putProvider(provider.toEntity())
            dao.putModels(listOf(model.toEntity()))
            secrets.write(secrets.providerKeyName(provider.id), "test-key")
            val normalized = repository.saveGlobalSettings(
                repository.globalSettings().copy(assistantNickname = "   "),
            )
            assertEquals(DEFAULT_ASSISTANT_NICKNAME, normalized.assistantNickname)
            assertEquals(DEFAULT_ASSISTANT_NICKNAME, repository.globalSettings().assistantNickname)
            repository.saveGlobalSettings(
                repository.globalSettings().copy(assistantNickname = "Original nickname"),
            )
            val conversation = repository.createConversation(
                ConversationWriteRequest(
                    title = "Identity test",
                    model = model.id,
                    modelMode = SettingMode.OVERRIDE,
                    maxToolCalls = 0,
                    enableSearch = false,
                    enableRead = false,
                    enableKnowledge = false,
                ),
            )
            val originalIdentity = AssistantIdentitySnapshot(
                modelId = model.id,
                remoteModelId = model.remoteId,
                nickname = "Original nickname",
            )
            var processIdentity: AssistantIdentitySnapshot? = null

            val originalEvents = repository.sendMessage(
                conversation.id,
                request("identity-original-$suffix", "First question"),
            ).onEach { event ->
                if (event is ChatEvent.Process) {
                    processIdentity = dao.messages(conversation.id)
                        .single { it.requestId == "identity-original-$suffix" && it.role == "assistant" }
                        .toDomain()
                        .assistantMetadata(json)
                        .assistantIdentity
                }
            }.toList()

            val initial = originalEvents.filterIsInstance<ChatEvent.AssistantMessage>().single().message
            val original = dao.messages(conversation.id)
                .single { it.requestId == "identity-original-$suffix" && it.role == "assistant" }
                .toDomain()
            assertEquals("generating", initial.assistantMetadata(json).completionStatus)
            assertEquals(originalIdentity, initial.assistantMetadata(json).assistantIdentity)
            assertEquals(originalIdentity, processIdentity)
            assertEquals("completed", original.assistantMetadata(json).completionStatus)
            assertEquals(originalIdentity, original.assistantMetadata(json).assistantIdentity)

            repository.saveGlobalSettings(
                repository.globalSettings().copy(assistantNickname = "Renamed assistant"),
            )
            val originalAfterRename = requireNotNull(dao.message(original.id)).toDomain()
            assertEquals(originalIdentity, originalAfterRename.assistantMetadata(json).assistantIdentity)

            val branch = repository.createBranch(original.id, "Identity branch")
            val branchedAssistant = dao.messages(branch.id).single { it.role == "assistant" }.toDomain()
            assertEquals(originalIdentity, branchedAssistant.assistantMetadata(json).assistantIdentity)

            val regeneratedEvents = repository.regenerate(
                conversation.id,
                request("identity-regenerated-$suffix"),
            ).toList()
            val regeneratedIdentity = originalIdentity.copy(nickname = "Renamed assistant")
            val regeneratedInitial = regeneratedEvents.filterIsInstance<ChatEvent.AssistantMessage>().single().message
            val regenerated = dao.messages(conversation.id)
                .single { it.requestId == "identity-regenerated-$suffix" && it.role == "assistant" }
                .toDomain()
            assertEquals(regeneratedIdentity, regeneratedInitial.assistantMetadata(json).assistantIdentity)
            assertEquals(regeneratedIdentity, regenerated.assistantMetadata(json).assistantIdentity)
            assertEquals("completed", regenerated.assistantMetadata(json).completionStatus)

            val failure = runCatching {
                repository.sendMessage(
                    conversation.id,
                    request("identity-failure-$suffix", "Cause a failure"),
                ).toList()
            }.exceptionOrNull()
            val failed = dao.messages(conversation.id)
                .single { it.requestId == "identity-failure-$suffix" && it.role == "assistant" }
                .toDomain()
            assertNotNull(failure)
            assertEquals("failed", failed.status)
            assertEquals("failed", failed.assistantMetadata(json).completionStatus)
            assertEquals(regeneratedIdentity, failed.assistantMetadata(json).assistantIdentity)

            val interruptedConversation = repository.createConversation(
                ConversationWriteRequest(
                    title = "Interrupted identity test",
                    model = model.id,
                    modelMode = SettingMode.OVERRIDE,
                    maxToolCalls = 0,
                    enableSearch = false,
                    enableRead = false,
                    enableKnowledge = false,
                ),
            )
            val partialEvents = repository.sendMessage(
                interruptedConversation.id,
                request("identity-interrupted-$suffix", "Stop after the initial row"),
            ).take(2).toList()
            val interrupted = dao.messages(interruptedConversation.id)
                .single { it.requestId == "identity-interrupted-$suffix" && it.role == "assistant" }
                .toDomain()
            assertTrue(partialEvents[1] is ChatEvent.AssistantMessage)
            assertEquals("interrupted", interrupted.status)
            assertEquals("interrupted", interrupted.assistantMetadata(json).completionStatus)
            assertEquals(regeneratedIdentity, interrupted.assistantMetadata(json).assistantIdentity)
        } finally {
            secrets.remove(secrets.providerKeyName(provider.id))
            database.close()
        }
    }

    private fun request(requestId: String, content: String = "") = SendMessageRequest(
        content = content,
        enableSearch = false,
        enableRead = false,
        enableKnowledge = false,
        timeZone = "UTC",
        requestId = requestId,
    )
}

private class IdentityModelGateway : ModelGateway() {
    override fun stream(request: ModelCallRequest) = flow<ModelStreamEvent> {
        emit(ModelStreamEvent.ThinkingDelta("identity process"))
        emit(ModelStreamEvent.TextDelta("identity response"))
        if (request.requestId.startsWith("identity-failure-")) {
            error("Expected identity test failure")
        }
        emit(ModelStreamEvent.TokenUsage(Usage(inputTokens = 3, outputTokens = 2)))
        emit(ModelStreamEvent.Completed)
    }
}

private object NoIdentityTestTools : ToolRunner {
    override fun definitions(enableSearch: Boolean, enableRead: Boolean) = emptyList<ToolDefinition>()

    override suspend fun execute(
        call: CanonicalToolCall,
        enableSearch: Boolean,
        enableRead: Boolean,
    ) = ToolExecutionResult("{}", true)
}
