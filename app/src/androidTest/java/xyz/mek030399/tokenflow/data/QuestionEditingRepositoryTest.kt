package xyz.mek030399.tokenflow.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class QuestionEditingRepositoryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: TokenFlowDatabase
    private lateinit var dao: LocalDao
    private lateinit var secrets: SecretStore
    private lateinit var attachments: AttachmentStore
    private lateinit var knowledge: KnowledgeStore
    private lateinit var repository: ChatRepository
    private lateinit var gateway: EditingGateway
    private lateinit var provider: ProviderConfig
    private lateinit var model: ModelProfile

    @Before fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, TokenFlowDatabase::class.java).build()
        dao = database.localDao()
        secrets = SecretStore(context)
        attachments = AttachmentStore(context, dao)
        knowledge = KnowledgeStore(context, dao)
        provider = ProviderConfig(id = "editing-${System.nanoTime()}", name = "Test", baseUrl = "https://api.example.com/v1",
            protocol = ProviderProtocol.OPENAI_RESPONSES)
        model = ModelProfile(id = "model-${provider.id}", providerId = provider.id, remoteId = "test", maxOutputTokens = 512,
            contextWindowTokens = 16_384)
        dao.putProvider(provider.toEntity())
        dao.putModels(listOf(model.toEntity()))
        secrets.write(secrets.providerKeyName(provider.id), "test-only-key")
        gateway = EditingGateway()
        repository = ChatRepository(dao, secrets, gateway,
            DirectChatEngine(gateway, WebToolExecutor(secrets, ExaClient(), UrlReader(context))),
            ConfigArchiveCodec(), attachmentStore = attachments, knowledgeStore = knowledge)
    }

    @After fun tearDown() = runBlocking {
        val messageIds = dao.conversations().flatMap { dao.messages(it.id) }.map { it.id }
        attachments.deleteFiles(attachments.forMessages(messageIds))
        dao.knowledgeDocuments().forEach { knowledge.delete(it.id) }
        secrets.remove(secrets.providerKeyName(provider.id))
        database.close()
    }

    private suspend fun chat(auto: Boolean = false) = repository.createConversation(ConversationWriteRequest(
        title = "original", model = model.id, modelMode = SettingMode.OVERRIDE, maxToolCalls = 0,
        enableSearch = false, enableRead = false,
        contextPolicy = if (auto) ContextPolicy(ContextMode.SUMMARY, 1, true) else ContextPolicy()))

    private fun request(id: String, knowledgeEnabled: Boolean = false) = SendMessageRequest(enableSearch = false,
        enableRead = false, enableKnowledge = knowledgeEnabled, timeZone = "UTC", requestId = id)

    private suspend fun history(conversationId: String): List<ChatMessage> {
        val messages = listOf(
            ChatMessage(id = "old-user-$conversationId", conversationId = conversationId, role = "user", content = "prior question", createdAt = 1),
            ChatMessage(id = "old-answer-$conversationId", conversationId = conversationId, role = "assistant", content = "prior answer", createdAt = 2),
            ChatMessage(id = "target-$conversationId", conversationId = conversationId, role = "user", content = "old question",
                metadata = ConfigArchiveCodec.defaultJson.encodeToString(UserMessageMetadata.serializer(), UserMessageMetadata(listOf(123))), createdAt = 3),
            ChatMessage(id = "target-answer-$conversationId", conversationId = conversationId, role = "assistant", content = "answer to old question", createdAt = 4),
            ChatMessage(id = "boundary-$conversationId", conversationId = conversationId, role = CONTEXT_BOUNDARY_ROLE, createdAt = 5),
            ChatMessage(id = "later-$conversationId", conversationId = conversationId, role = "user", content = "later question", createdAt = 6))
        dao.putMessages(messages.map(ChatMessage::toEntity))
        return messages
    }

    private suspend fun attach(messageId: String) = attachments.persist(messageId, listOf(PendingAttachment(
        uri = "", displayName = "editing.txt", mimeType = "text/plain", origin = PendingAttachmentOrigin.NOTE,
        inlineText = "attachment facts"))).single()

    @Test fun editedBranchPreservesOriginalAndOwnsItsAttachmentCopies() = runBlocking {
        val source = chat()
        val original = history(source.id)
        val attachment = attach(original[2].id)
        repository.setConversationArchived(source.id, true)
        val branch = repository.prepareEditedQuestion(original[2].id, "  edited question  ")
        assertEquals(original, dao.messages(source.id).map(MessageEntity::toDomain))
        assertEquals(listOf("prior question", "prior answer", "edited question"), branch.messages.map { it.content })
        assertEquals(source.id, branch.conversation.branchedFromConversationId)
        assertEquals(original[2].id, branch.conversation.branchedFromMessageId)
        assertNull(branch.conversation.archivedAt)
        assertTrue(branch.messages.map { it.id }.intersect(original.map { it.id }.toSet()).isEmpty())
        val metadata = ConfigArchiveCodec.defaultJson.decodeFromString<UserMessageMetadata>(branch.messages.last().metadata)
        assertTrue(metadata.knowledgeChunkIds.isEmpty())
        val copy = branch.attachments.single()
        assertNotEquals(attachment.storedPath, copy.storedPath)
        assertEquals(branch.messages.last().id, copy.messageId)
        assertEquals(File(attachment.storedPath).readText(), File(copy.storedPath).readText())
        File(attachment.storedPath).delete()
        assertEquals("attachment facts", File(copy.storedPath).readText())
    }

    @Test fun onlyPrefixSummaryIsInheritedWithRemappedIdsAndDigest() = runBlocking {
        val source = chat()
        val original = history(source.id)
        fun summary(count: Int) = ContextSummary(source.id, "previous facts", sourceMessageIds = original.take(count).map { it.id },
            sourceDigest = ContextBuilder.digest(original.take(count)), modelId = model.id, remoteModelId = model.remoteId)
        dao.putContextSummary(ContextSummaryEntity(source.id, ConfigArchiveCodec.defaultJson.encodeToString(ContextSummary.serializer(), summary(2))))
        val branch = repository.prepareEditedQuestion(original[2].id, "changed")
        val inherited = ConfigArchiveCodec.defaultJson.decodeFromString<ContextSummary>(dao.contextSummary(branch.conversation.id)!!.payloadJson)
        assertNotNull(ContextBuilder.validSummary(inherited, branch.messages))
        assertEquals(branch.messages.take(2).map { it.id }, inherited.sourceMessageIds)
        dao.putContextSummary(ContextSummaryEntity(source.id, ConfigArchiveCodec.defaultJson.encodeToString(ContextSummary.serializer(), summary(3))))
        val overlapping = repository.prepareEditedQuestion(original[2].id, "changed again")
        assertNull(dao.contextSummary(overlapping.conversation.id))
    }

    @Test fun savedEditedQuestionGeneratesOnceAcrossAllProtocolsWithoutChangingOriginal() = runBlocking {
        for (protocol in ProviderProtocol.entries) {
            dao.putProvider(provider.copy(protocol = protocol).toEntity())
            val source = chat()
            val original = history(source.id)
            val branch = repository.prepareEditedQuestion(original[2].id, "edited $protocol")
            repository.regenerateEditedQuestion(branch.conversation.id, request("edited-$protocol")).toList()
            val persisted = dao.messages(branch.conversation.id).map(MessageEntity::toDomain)
            assertEquals(1, persisted.count { it.content == "edited $protocol" && it.role == "user" })
            assertEquals("Reply.", persisted.last().content)
            assertEquals("completed", persisted.last().status)
            assertEquals(protocol, gateway.requests.last().provider.protocol)
            assertEquals("edited $protocol", gateway.requests.last().messages.last().content)
            assertFalse(gateway.requests.last().messages.any { it.content == "answer to old question" || it.content == "later question" })
            assertEquals(original, dao.messages(source.id).map(MessageEntity::toDomain))
            assertTrue(runCatching { repository.regenerateEditedQuestion(branch.conversation.id, request("duplicate-$protocol")).toList() }.isFailure)
            assertEquals(persisted, dao.messages(branch.conversation.id).map(MessageEntity::toDomain))
        }
    }

    @Test fun preflightFailureKeepsSavedQuestionAndAttachmentsForRetryWithoutDuplicates() = runBlocking {
        val source = chat()
        val original = history(source.id)
        attach(original[2].id)
        val branch = repository.prepareEditedQuestion(original[2].id, "retry this question")
        secrets.remove(secrets.providerKeyName(provider.id))
        assertTrue(runCatching { repository.regenerateEditedQuestion(branch.conversation.id, request("retry-edited")).toList() }.isFailure)
        assertEquals(branch.messages, dao.messages(branch.conversation.id).map(MessageEntity::toDomain))
        assertTrue(branch.attachments.all { File(it.storedPath).isFile })
        secrets.write(secrets.providerKeyName(provider.id), "test-only-key")
        repository.regenerateEditedQuestion(branch.conversation.id, request("retry-edited")).toList()
        repository.regenerate(branch.conversation.id, request("repeat-edited")).toList()
        assertEquals(1, dao.messages(branch.conversation.id).count { it.id == branch.messages.last().id })
        assertEquals(1, dao.messages(branch.conversation.id).count { it.role == "assistant" && it.content == "Reply." })
        assertTrue(branch.attachments.all { File(it.storedPath).isFile })
        assertEquals(original, dao.messages(source.id).map(MessageEntity::toDomain))
    }

    @Test fun cancellingCompressionKeepsEditedQuestionAndAllowsRetry() = runBlocking {
        val source = chat(auto = true)
        val original = history(source.id).mapIndexed { index, message ->
            if (index == 0) message.copy(content = "historical data ".repeat(6000)) else message
        }
        dao.putMessages(original.map(ChatMessage::toEntity))
        val branch = repository.prepareEditedQuestion(original[2].id, "edited current question")
        gateway.summaryGate = CompletableDeferred()
        val job = launch(Dispatchers.IO) { repository.regenerateEditedQuestion(branch.conversation.id, request("cancel-edited")).toList() }
        gateway.summaryStarted.await()
        job.cancelAndJoin()
        assertEquals(branch.messages, dao.messages(branch.conversation.id).map(MessageEntity::toDomain))
        assertNull(dao.contextSummary(branch.conversation.id))
        gateway.summaryGate = null
        repository.regenerateEditedQuestion(branch.conversation.id, request("cancel-edited")).toList()
        assertEquals(1, dao.messages(branch.conversation.id).count { it.id == branch.messages.last().id })
        assertEquals(original, dao.messages(source.id).map(MessageEntity::toDomain))
    }

    @Test fun failedAnswerCanBeRegeneratedWithoutReinsertingTheEditedQuestion() = runBlocking {
        val source = chat()
        val original = history(source.id)
        val branch = repository.prepareEditedQuestion(original[2].id, "edited question")
        gateway.failReply = true
        assertTrue(runCatching { repository.regenerateEditedQuestion(branch.conversation.id, request("failed-edited")).toList() }.isFailure)
        val failed = dao.messages(branch.conversation.id)
        assertEquals("failed", failed.last().status)
        assertEquals(branch.messages.last().id, failed.last { it.role == "user" }.id)
        gateway.failReply = false
        repository.regenerate(branch.conversation.id, request("retry-failed-edited")).toList()
        val retried = dao.messages(branch.conversation.id)
        assertEquals(1, retried.count { it.id == branch.messages.last().id })
        assertEquals(1, retried.count { it.role == "assistant" && it.status == "completed" && it.content == "Reply." })
        assertNull(dao.message(failed.last().id))
        assertEquals(original, dao.messages(source.id).map(MessageEntity::toDomain))
    }

    @Test fun sourceGenerationAndQuestionEditingShareTheSameOperationLock() = runBlocking {
        val source = chat()
        val original = history(source.id)
        gateway.replyGate = CompletableDeferred()
        val job = launch(Dispatchers.IO) { repository.sendMessage(source.id, request("busy-source").copy(content = "new question")).toList() }
        gateway.replyStarted.await()
        assertTrue(runCatching { repository.prepareEditedQuestion(original[2].id, "changed") }.isFailure)
        job.cancelAndJoin()
        assertEquals(1, dao.conversations().size)
        assertEquals(original[2].content, dao.message(original[2].id)?.content)
        assertEquals("changed", repository.prepareEditedQuestion(original[2].id, "changed").messages.last().content)
    }

    @Test fun editedQuestionRetrievesNewKnowledgeInsteadOfReusingTheOldCitations() = runBlocking {
        val oldDocument = knowledge.importText("old.md", "text/markdown", "apples orchard fruit")
        val newDocument = knowledge.importText("new.md", "text/markdown", "galaxies nebula telescope")
        val oldChunk = dao.knowledgeChunksForDocument(oldDocument.id).single().id
        val newChunk = dao.knowledgeChunksForDocument(newDocument.id).single().id
        val source = chat()
        val original = history(source.id)
        dao.putMessages(listOf(original[2].copy(metadata = ConfigArchiveCodec.defaultJson.encodeToString(UserMessageMetadata.serializer(),
            UserMessageMetadata(listOf(oldChunk)))).toEntity()))
        val branch = repository.prepareEditedQuestion(original[2].id, "galaxies")
        repository.regenerateEditedQuestion(branch.conversation.id, request("fresh-knowledge", knowledgeEnabled = true)).toList()
        val metadata = ConfigArchiveCodec.defaultJson.decodeFromString<UserMessageMetadata>(dao.message(branch.messages.last().id)!!.metadata)
        assertEquals(listOf(newChunk), metadata.knowledgeChunkIds)
        assertFalse(metadata.knowledgeChunkIds.contains(oldChunk))
    }
}

private class EditingGateway : ModelGateway() {
    val requests = mutableListOf<ModelCallRequest>()
    var summaryGate: CompletableDeferred<Unit>? = null
    var replyGate: CompletableDeferred<Unit>? = null
    var failReply = false
    val summaryStarted = CompletableDeferred<Unit>()
    val replyStarted = CompletableDeferred<Unit>()
    override fun stream(request: ModelCallRequest) = flow {
        requests += request
        if (request.systemPrompt == ContextCompressor.PROMPT) {
            summaryStarted.complete(Unit)
            summaryGate?.await()
            emit(ModelStreamEvent.TextDelta("Concise historical facts."))
        } else {
            replyStarted.complete(Unit)
            emit(ModelStreamEvent.TextDelta("Reply."))
            replyGate?.await()
            if (failReply) error("Test answer failure")
        }
        emit(ModelStreamEvent.TokenUsage(Usage(20, 5)))
        emit(ModelStreamEvent.Completed)
    }
}
