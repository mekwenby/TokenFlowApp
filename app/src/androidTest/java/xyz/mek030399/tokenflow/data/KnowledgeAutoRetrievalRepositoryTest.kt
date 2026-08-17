package xyz.mek030399.tokenflow.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeAutoRetrievalRepositoryTest {
    @Test
    fun enabledKnowledgePersistsPrefetchWithZeroToolBudgetAndClearsSafely() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, TokenFlowDatabase::class.java).build()
        val dao = database.localDao()
        val secrets = SecretStore(context)
        val providerId = "knowledge-prefetch-${System.nanoTime()}"
        val provider = ProviderConfig(
            id = providerId,
            name = "Knowledge prefetch test",
            baseUrl = "https://api.example.com/v1",
            protocol = ProviderProtocol.OPENAI_RESPONSES,
        )
        val model = ModelProfile(
            id = "model-$providerId",
            providerId = providerId,
            remoteId = "test-model",
        )
        val knowledgeStore = KnowledgeStore(context, dao)
        val gateway = RecordingModelGateway()
        val webTools = WebToolExecutor(
            secretStore = secrets,
            exaClient = ExaClient(),
            urlReader = UrlReader(context),
            knowledgeStore = knowledgeStore,
        )
        val repository = ChatRepository(
            dao = dao,
            secretStore = secrets,
            gateway = gateway,
            engine = DirectChatEngine(gateway, webTools),
            archive = ConfigArchiveCodec(),
            knowledgeStore = knowledgeStore,
        )
        var document: KnowledgeDocument? = null

        try {
            dao.putProvider(provider.toEntity())
            dao.putModels(listOf(model.toEntity()))
            secrets.write(secrets.providerKeyName(providerId), "test-key")
            val conversation = repository.createConversation(
                ConversationWriteRequest(
                    title = "Knowledge prefetch test",
                    model = model.id,
                    modelMode = SettingMode.OVERRIDE,
                    maxToolCalls = 0,
                    enableSearch = false,
                    enableRead = false,
                    enableKnowledge = true,
                ),
            )
            document = knowledgeStore.importText(
                name = "zephyr.md",
                mimeType = "text/markdown",
                text = "Project Zephyr uses launch code NEBULA-482. The scheduled deadline is Friday.",
            )
            val expectedChunk = knowledgeStore.search("Project Zephyr launch code").single()
            assertEquals(expectedChunk.chunkId, repository.knowledgeSnippet(expectedChunk.chunkId)?.chunkId)
            assertEquals(null, repository.knowledgeSnippet(Long.MAX_VALUE))

            val events = repository.sendMessage(
                conversation.id,
                SendMessageRequest(
                    content = "What is the Project Zephyr launch code?",
                    enableSearch = false,
                    enableRead = false,
                    enableKnowledge = true,
                    timeZone = "UTC",
                    requestId = "request-$providerId",
                ),
            ).toList()

            val firstEvent = events.first()
            assertTrue(firstEvent is ChatEvent.UserMessage)
            val persisted = dao.messages(conversation.id).single { it.role == "user" }.toDomain()
            val metadata = DirectApiTransport.defaultJson.decodeFromString<UserMessageMetadata>(persisted.metadata)
            assertEquals(listOf(expectedChunk.chunkId), metadata.knowledgeChunkIds)
            assertEquals((firstEvent as ChatEvent.UserMessage).message.metadata, persisted.metadata)

            val retrievalEvent = events.filterIsInstance<ChatEvent.Process>().single().event
            assertEquals("knowledge_retrieval", retrievalEvent.type)
            assertEquals("knowledge_retrieval_hits", retrievalEvent.messageKey)
            assertEquals(listOf(expectedChunk.chunkId), retrievalEvent.knowledgeCitations.map(KnowledgeCitation::chunkId))
            val initialAssistant = events.filterIsInstance<ChatEvent.AssistantMessage>().single().message
            val initialAssistantMetadata = DirectApiTransport.defaultJson
                .decodeFromString<AssistantMetadata>(initialAssistant.metadata)
            assertEquals("generating", initialAssistantMetadata.completionStatus)
            assertEquals(listOf("knowledge_retrieval_hits"), initialAssistantMetadata.events.map(ProcessEvent::messageKey))
            assertEquals(listOf(expectedChunk.chunkId), initialAssistantMetadata.knowledgeCitations.map(KnowledgeCitation::chunkId))

            val completedAssistant = dao.messages(conversation.id)
                .single { it.role == "assistant" }
                .toDomain()
            val completedMetadata = DirectApiTransport.defaultJson
                .decodeFromString<AssistantMetadata>(completedAssistant.metadata)
            assertEquals("completed", completedMetadata.completionStatus)
            assertEquals(listOf("knowledge_retrieval_hits"), completedMetadata.events.map(ProcessEvent::messageKey))
            assertEquals(listOf(expectedChunk.chunkId), completedMetadata.knowledgeCitations.map(KnowledgeCitation::chunkId))

            val modelRequest = gateway.firstRequest
            assertNotNull(modelRequest)
            val captured = requireNotNull(modelRequest)
            val userMessage = captured.messages.single { it.role == "user" }
            assertTrue(userMessage.content.contains("<local_knowledge untrusted=\"true\">"))
            assertTrue(userMessage.content.contains("[[KB:${expectedChunk.chunkId}]]"))
            assertTrue(userMessage.content.contains("zephyr.md · 片段 1"))
            assertFalse(userMessage.content.contains("[zephyr.md#0]"))
            assertTrue(userMessage.content.contains("NEBULA-482"))
            assertTrue(captured.systemPrompt.contains("Local knowledge mode:"))
            assertTrue(captured.systemPrompt.contains("prefer supported local evidence over web content"))
            assertTrue(captured.tools.isEmpty())
            assertFalse(captured.systemPrompt.contains("- search_knowledge:"))
            assertTrue(captured.systemPrompt.contains("search_knowledge is unavailable"))

            val toolConversation = repository.createConversation(
                ConversationWriteRequest(
                    title = "Knowledge tool test",
                    model = model.id,
                    modelMode = SettingMode.OVERRIDE,
                    maxToolCalls = 1,
                    enableSearch = false,
                    enableRead = false,
                    enableKnowledge = true,
                ),
            )
            val toolRequestIndex = gateway.requests.size
            val toolEvents = repository.sendMessage(
                toolConversation.id,
                SendMessageRequest(
                    content = "Find the Project Zephyr launch code.",
                    enableSearch = false,
                    enableRead = false,
                    enableKnowledge = true,
                    timeZone = "UTC",
                    requestId = "tool-request-$providerId",
                ),
            ).toList()

            val toolRequest = gateway.requests[toolRequestIndex]
            val searchTool = toolRequest.tools.single { it.name == "search_knowledge" }
            assertEquals("search_knowledge", searchTool.name)
            assertTrue(toolRequest.systemPrompt.contains("- search_knowledge:"))
            assertEquals(
                "string",
                searchTool.parameters["properties"]!!.jsonObject["query"]!!.jsonObject["type"]!!.jsonPrimitive.content,
            )
            assertEquals(
                listOf("query"),
                searchTool.parameters["required"]!!.jsonArray.map { it.jsonPrimitive.content },
            )

            val toolProcessEvents = toolEvents.filterIsInstance<ChatEvent.Process>().map(ChatEvent.Process::event)
            assertEquals(
                listOf("knowledge_retrieval", "tool_started", "tool_completed"),
                toolProcessEvents.map(ProcessEvent::type),
            )
            val toolCompleted = toolProcessEvents.single { it.type == "tool_completed" }
            assertEquals(listOf(expectedChunk.chunkId), toolCompleted.knowledgeCitations.map(KnowledgeCitation::chunkId))
            val toolAssistant = dao.messages(toolConversation.id).single { it.role == "assistant" }.toDomain()
            val toolMetadata = DirectApiTransport.defaultJson.decodeFromString<AssistantMetadata>(toolAssistant.metadata)
            assertEquals(
                listOf("knowledge_retrieval", "tool_started", "tool_completed"),
                toolMetadata.events.map(ProcessEvent::type),
            )
            assertEquals(listOf(expectedChunk.chunkId), toolMetadata.knowledgeCitations.map(KnowledgeCitation::chunkId))

            val manualConversation = repository.createConversation(
                ConversationWriteRequest(
                    title = "Manual knowledge test",
                    model = model.id,
                    modelMode = SettingMode.OVERRIDE,
                    maxToolCalls = 0,
                    enableSearch = false,
                    enableRead = false,
                    enableKnowledge = false,
                ),
            )
            val manualRequestIndex = gateway.requests.size
            val manualEvents = repository.sendMessage(
                manualConversation.id,
                SendMessageRequest(
                    content = "Use the selected passage.",
                    enableSearch = false,
                    enableRead = false,
                    enableKnowledge = false,
                    knowledgeChunkIds = listOf(expectedChunk.chunkId),
                    timeZone = "UTC",
                    requestId = "manual-request-$providerId",
                ),
            ).toList()
            val manualEvent = manualEvents.filterIsInstance<ChatEvent.Process>().single().event
            assertEquals("knowledge_manual_loaded", manualEvent.messageKey)
            assertEquals(listOf(expectedChunk.chunkId), manualEvent.knowledgeCitations.map(KnowledgeCitation::chunkId))
            assertTrue(gateway.requests[manualRequestIndex].messages.single { it.role == "user" }.content.contains(expectedChunk.toKnowledgeCitation().marker))

            val regenerateRequestIndex = gateway.requests.size
            val regenerateEvents = repository.regenerate(
                conversation.id,
                SendMessageRequest(
                    enableSearch = false,
                    enableRead = false,
                    enableKnowledge = true,
                    timeZone = "UTC",
                    requestId = "regenerate-$providerId",
                ),
            ).toList()
            assertTrue(regenerateEvents.none { it is ChatEvent.UserMessage })
            val reusedEvent = regenerateEvents.filterIsInstance<ChatEvent.Process>().single().event
            assertEquals("knowledge_reused", reusedEvent.messageKey)
            assertEquals(listOf(expectedChunk.chunkId), reusedEvent.knowledgeCitations.map(KnowledgeCitation::chunkId))
            val regenerateRequest = gateway.requests[regenerateRequestIndex]
            assertTrue(regenerateRequest.messages.single { it.role == "user" }.content.contains(expectedChunk.toKnowledgeCitation().marker))
            val regeneratedAssistant = dao.messages(conversation.id).single { it.role == "assistant" }.toDomain()
            val regeneratedMetadata = DirectApiTransport.defaultJson
                .decodeFromString<AssistantMetadata>(regeneratedAssistant.metadata)
            assertEquals(listOf("knowledge_reused"), regeneratedMetadata.events.map(ProcessEvent::messageKey))
            assertEquals(listOf(expectedChunk.chunkId), regeneratedMetadata.knowledgeCitations.map(KnowledgeCitation::chunkId))

            repository.clearContext(conversation.id)
            assertTrue(dao.messages(conversation.id).map(MessageEntity::toDomain).forModelContext().isEmpty())

            val postBoundaryRequestIndex = gateway.requests.size
            val postBoundaryEvents = repository.sendMessage(
                conversation.id,
                SendMessageRequest(
                    content = "QUARTZ-7791",
                    enableSearch = false,
                    enableRead = false,
                    enableKnowledge = true,
                    timeZone = "UTC",
                    requestId = "post-boundary-$providerId",
                ),
            ).toList()

            val postBoundaryRequest = gateway.requests[postBoundaryRequestIndex]
            assertEquals(listOf("user"), postBoundaryRequest.messages.map(CanonicalMessage::role))
            assertFalse(postBoundaryRequest.messages.single().content.contains("<local_knowledge"))
            assertFalse(postBoundaryRequest.messages.single().content.contains("NEBULA-482"))
            val emptyRetrieval = postBoundaryEvents.filterIsInstance<ChatEvent.Process>().single().event
            assertEquals("knowledge_retrieval_empty", emptyRetrieval.messageKey)
            assertTrue(emptyRetrieval.knowledgeCitations.isEmpty())
            val postBoundaryAssistant = dao.messages(conversation.id)
                .single { it.role == "assistant" && it.requestId == "post-boundary-$providerId" }
                .toDomain()
            val postBoundaryMetadata = DirectApiTransport.defaultJson
                .decodeFromString<AssistantMetadata>(postBoundaryAssistant.metadata)
            assertTrue(postBoundaryMetadata.knowledgeCitations.isEmpty())
            assertEquals(listOf("knowledge_retrieval_empty"), postBoundaryMetadata.events.map(ProcessEvent::messageKey))

            val interruptedConversation = repository.createConversation(
                ConversationWriteRequest(
                    title = "Interrupted before engine test",
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
                SendMessageRequest(
                    content = "Stop after creating the assistant row.",
                    enableSearch = false,
                    enableRead = false,
                    enableKnowledge = false,
                    timeZone = "UTC",
                    requestId = "interrupted-$providerId",
                ),
            ).take(2).toList()
            assertTrue(partialEvents[0] is ChatEvent.UserMessage)
            assertTrue(partialEvents[1] is ChatEvent.AssistantMessage)
            val interruptedAssistant = dao.messages(interruptedConversation.id)
                .single { it.role == "assistant" }
                .toDomain()
            assertEquals("interrupted", interruptedAssistant.status)
            assertEquals(
                "interrupted",
                DirectApiTransport.defaultJson
                    .decodeFromString<AssistantMetadata>(interruptedAssistant.metadata)
                    .completionStatus,
            )
            assertEquals("idle", requireNotNull(dao.conversation(interruptedConversation.id)).status)

            knowledgeStore.delete(document.id)
            document = null
            assertTrue(knowledgeStore.snippets(metadata.knowledgeChunkIds).isEmpty())
        } finally {
            document?.let { knowledgeStore.delete(it.id) }
            secrets.remove(secrets.providerKeyName(providerId))
            database.close()
        }
    }
}

private class RecordingModelGateway : ModelGateway() {
    val requests = mutableListOf<ModelCallRequest>()
    val firstRequest: ModelCallRequest?
        get() = requests.firstOrNull()

    override fun stream(request: ModelCallRequest) = if (
        request.requestId.startsWith("tool-request-") && request.messages.none { it.role == "tool" }
    ) {
        flowOf<ModelStreamEvent>(
            ModelStreamEvent.ToolCallDelta(
                index = 0,
                id = "knowledge-call",
                name = "search_knowledge",
                arguments = "{\"query\":\"Project Zephyr launch code\"}",
            ),
            ModelStreamEvent.Completed,
        )
    } else {
        flowOf<ModelStreamEvent>(ModelStreamEvent.Completed)
    }.also {
        requests += request
    }
}
