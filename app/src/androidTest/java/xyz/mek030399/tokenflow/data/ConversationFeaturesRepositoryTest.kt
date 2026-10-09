package xyz.mek030399.tokenflow.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
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
import org.junit.Rule
import org.junit.Test

class ConversationFeaturesRepositoryTest {
    @get:Rule val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), TokenFlowDatabase::class.java)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: TokenFlowDatabase
    private lateinit var dao: LocalDao
    private lateinit var secrets: SecretStore
    private lateinit var repository: ChatRepository
    private lateinit var gateway: FeatureGateway
    private lateinit var provider: ProviderConfig
    private lateinit var model: ModelProfile

    @Before fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, TokenFlowDatabase::class.java).build()
        dao = database.localDao()
        secrets = SecretStore(context)
        provider = ProviderConfig(id = "features-${System.nanoTime()}", name = "Test", baseUrl = "https://api.example.com/v1", protocol = ProviderProtocol.OPENAI_RESPONSES)
        model = ModelProfile(id = "model-${provider.id}", providerId = provider.id, remoteId = "test", maxOutputTokens = 512, contextWindowTokens = 16_384)
        dao.putProvider(provider.toEntity())
        dao.putModels(listOf(model.toEntity()))
        secrets.write(secrets.providerKeyName(provider.id), "test-only-key")
        gateway = FeatureGateway()
        repository = ChatRepository(dao, secrets, gateway,
            DirectChatEngine(gateway, WebToolExecutor(secrets, ExaClient(), UrlReader(context))),
            ConfigArchiveCodec(), attachmentStore = AttachmentStore(context, dao))
    }

    @After fun tearDown() { secrets.remove(secrets.providerKeyName(provider.id)); database.close() }

    private suspend fun conversation(id: String, auto: Boolean = false) = repository.createConversation(
        ConversationWriteRequest(title = id, model = model.id, modelMode = SettingMode.OVERRIDE, maxToolCalls = 0,
            enableSearch = false, enableRead = false,
            contextPolicy = if (auto) ContextPolicy(ContextMode.SUMMARY, 1, true) else ContextPolicy()))

    private fun request(id: String, content: String = "current question") = SendMessageRequest(content, false, false,
        timeZone = "UTC", requestId = id)

    private suspend fun history(id: String): List<ChatMessage> {
        val messages = listOf(ChatMessage(id = "$id-old-user", conversationId = id, role = "user", content = "historical data ".repeat(6000), createdAt = 1),
            ChatMessage(id = "$id-old-reply", conversationId = id, role = "assistant", content = "historical answer ".repeat(2000), createdAt = 2),
            ChatMessage(id = "$id-middle-user", conversationId = id, role = "user", content = "middle question", createdAt = 3),
            ChatMessage(id = "$id-middle-reply", conversationId = id, role = "assistant", content = "middle answer", createdAt = 4),
            ChatMessage(id = "$id-latest-user", conversationId = id, role = "user", content = "recent question", createdAt = 5),
            ChatMessage(id = "$id-latest-reply", conversationId = id, role = "assistant", content = "recent answer", createdAt = 6))
        dao.putMessages(messages.map(ChatMessage::toEntity))
        return messages
    }

    @Test fun literalSearchIncludesArchivedAndInterruptedBodiesAndPaginatesWithoutDuplicates() = runBlocking {
        val chat = conversation("search")
        dao.updateConversation(chat.id) { it.copy(archivedAt = 100) }
        val messages = (0..54).map { index -> ChatMessage(id = "search-$index", conversationId = chat.id,
            role = if (index % 2 == 0) "user" else "assistant", content = "中文 FLOW %_ literal $index", createdAt = index.toLong()) }
        dao.putMessages(messages.map(ChatMessage::toEntity) + listOf(
            ChatMessage(id = "live", conversationId = chat.id, role = "assistant", content = "FLOW", status = "generating").toEntity(),
            ChatMessage(id = "partial", conversationId = chat.id, role = "assistant", content = "FLOW", status = "interrupted", createdAt = 60).toEntity(),
            ChatMessage(id = "process", conversationId = chat.id, role = "tool", content = "FLOW").toEntity()))
        val first = repository.searchMessages("flow")
        val next = repository.searchMessages("flow", first.nextCursor)
        assertEquals(50, first.items.size)
        assertEquals(6, next.items.size)
        assertNull(next.nextCursor)
        assertEquals(56, (first.items + next.items).map { it.messageId }.distinct().size)
        assertTrue(first.items.all { it.archivedAt != null })
        assertEquals(50, repository.searchMessages("%_").items.size)
        assertEquals(50, repository.searchMessages("中文").items.size)
        assertTrue(repository.searchMessages("no-match").items.isEmpty())
        dao.deleteMessage("partial")
        assertFalse(repository.searchMessages("flow").items.any { it.messageId == "partial" })
    }

    @Test fun automaticSummaryCommitsOnceAndKeepsOriginalHistoryAndSeparateUsage() = runBlocking {
        val chat = conversation("auto", true)
        val old = history(chat.id)
        val events = repository.sendMessage(chat.id, request("auto-request")).toList()
        val summaries = gateway.requests.filter { it.systemPrompt == ContextCompressor.PROMPT }
        assertTrue(summaries.size > 1)
        assertTrue(summaries.all { it.thinkingEffort == "off" && it.tools.isEmpty() && it.maxOutputTokens <= 512 })
        val persisted = dao.messages(chat.id).map(MessageEntity::toDomain)
        assertTrue(persisted.map { it.id }.containsAll(old.map { it.id }))
        assertEquals(1, persisted.count { it.role == "user" && it.requestId == "auto-request" })
        val summary = ConfigArchiveCodec.defaultJson.decodeFromString<ContextSummary>(dao.contextSummary(chat.id)!!.payloadJson)
        assertNotNull(ContextBuilder.validSummary(summary, persisted))
        val reply = gateway.requests.last()
        assertTrue(reply.messages.first().content.contains("Historical conversation summary"))
        assertFalse(reply.messages.any { it.content.contains("historical data historical data") })
        assertNotNull(events.filterIsInstance<ChatEvent.Process>().first { it.event.type == "context_compressed" }.event.usage)
    }

    @Test fun automaticCompressionAllowsAFittingFirstQuestionWithNoOlderHistory() = runBlocking {
        val chat = conversation("first-question", true)
        val question = "x".repeat(26_000)
        val preview = repository.contextPreview(chat.id, request("first-question-request", question))
        assertTrue(preview.needsCompression)
        assertTrue(preview.estimatedInputTokens <= preview.inputBudget!!)
        repository.sendMessage(chat.id, request("first-question-request", question)).toList()
        assertEquals(1, gateway.requests.size)
        assertNull(dao.contextSummary(chat.id))
        assertEquals(question, dao.messages(chat.id).first { it.role == "user" }.content)
    }

    @Test fun failedCompressionLeavesNoUserAndRegenerationPreservesTheOldReply() = runBlocking {
        val chat = conversation("failure", true)
        val old = history(chat.id)
        gateway.failSummary = true
        assertTrue(runCatching { repository.sendMessage(chat.id, request("failure-request")).toList() }.isFailure)
        assertEquals(old.map { it.id }, dao.messages(chat.id).map { it.id })
        assertNull(dao.contextSummary(chat.id))
        assertTrue(runCatching { repository.regenerate(chat.id, request("retry-request")).toList() }.isFailure)
        assertEquals(old.last().content, dao.message(old.last().id)?.content)
    }

    @Test fun cancelledCompressionDoesNotCommitACandidateOrQuestion() = runBlocking {
        val chat = conversation("cancel", true)
        val old = history(chat.id)
        gateway.summaryGate = CompletableDeferred()
        val job = launch(Dispatchers.IO) { repository.sendMessage(chat.id, request("cancel-request")).toList() }
        gateway.summaryStarted.await()
        job.cancelAndJoin()
        assertEquals(old.map { it.id }, dao.messages(chat.id).map { it.id })
        assertNull(dao.contextSummary(chat.id))
    }

    @Test fun manualCandidateNeedsConfirmationAndBranchSummaryRemapsSourceIds() = runBlocking {
        val chat = conversation("manual")
        repository.updateConversation(chat.id, ConversationWriteRequest(contextPolicy = ContextPolicy(recentRounds = 1)))
        history(chat.id)
        val candidate = repository.compactContext(chat.id)
        assertNull(dao.contextSummary(chat.id))
        repository.saveContextSummary(candidate.copy(body = "User edited historical facts"))
        val preview = repository.contextPreview(chat.id)
        assertEquals("User edited historical facts", preview.summary?.body)
        val oldBranch = repository.createBranch("${chat.id}-old-reply", "before summary cutoff")
        val newBranch = repository.createBranch("${chat.id}-latest-reply", "after summary cutoff")
        assertNull(dao.contextSummary(oldBranch.id))
        val copied = ConfigArchiveCodec.defaultJson.decodeFromString<ContextSummary>(dao.contextSummary(newBranch.id)!!.payloadJson)
        assertNotNull(ContextBuilder.validSummary(copied, dao.messages(newBranch.id).map(MessageEntity::toDomain)))
        assertTrue(copied.sourceMessageIds.none { it in candidate.sourceMessageIds })
        repository.clearContext(chat.id)
        assertNull(dao.contextSummary(chat.id))
        assertTrue(repository.contextPreview(chat.id).messages.isEmpty())
    }

    @Test fun sevenAndLegacyEightUpgradeToNineWithoutLosingProviderData() {
        for (version in listOf(7, 8)) {
            val name = "feature-migration-$version-${System.nanoTime()}.db"
            try {
                migrations.createDatabase(name, 7).apply {
                    execSQL("INSERT INTO providers VALUES ('kept','Kept','https://api.example.com/v1','OPENAI_RESPONSES',1,1)")
                    if (version == 8) execSQL("PRAGMA user_version = 8")
                    close()
                }
                migrations.runMigrationsAndValidate(name, 9, true, TokenFlowDatabase.MIGRATION_7_9, TokenFlowDatabase.MIGRATION_8_9).use { migrated ->
                    migrated.query("SELECT name FROM providers WHERE id='kept'").use { assertTrue(it.moveToFirst()); assertEquals("Kept", it.getString(0)) }
                    migrated.query("SELECT COUNT(*) FROM context_summaries").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
                }
            } finally { context.deleteDatabase(name) }
        }
    }

    @Test fun branchPreservesExcludedRepliesAndAnInheritedSummaryRemainsValid() = runBlocking {
        val chat = conversation("branch-status")
        val raw = listOf(
            ChatMessage(id = "old-user", conversationId = chat.id, role = "user", content = "old question", createdAt = 1),
            ChatMessage(id = "failed-reply", conversationId = chat.id, role = "assistant", content = "failed partial", status = "failed", createdAt = 2),
            ChatMessage(id = "completed-reply", conversationId = chat.id, role = "assistant", content = "completed answer", createdAt = 3),
            ChatMessage(id = "new-user", conversationId = chat.id, role = "user", content = "next question", createdAt = 4),
            ChatMessage(id = "new-reply", conversationId = chat.id, role = "assistant", content = "next answer", createdAt = 5),
        )
        dao.putMessages(raw.map(ChatMessage::toEntity))
        val source = ContextBuilder.eligible(raw).take(2)
        repository.saveContextSummary(ContextSummary(chat.id, "Old question and completed answer", sourceMessageIds = source.map { it.id },
            sourceDigest = ContextBuilder.digest(source), modelId = model.id, remoteModelId = model.remoteId))
        val branch = repository.createBranch("new-reply", "branched")
        val copied = dao.messages(branch.id).map(MessageEntity::toDomain)
        assertEquals("failed", copied.first { it.content == "failed partial" }.status)
        val summary = ConfigArchiveCodec.defaultJson.decodeFromString<ContextSummary>(dao.contextSummary(branch.id)!!.payloadJson)
        assertNotNull(ContextBuilder.validSummary(summary, copied))
        val preview = repository.contextPreview(branch.id)
        assertEquals(summary.body, preview.summary?.body)
        assertEquals(listOf("next question", "next answer"), preview.messages.drop(1).map { it.content })
    }
}

private class FeatureGateway : ModelGateway() {
    val requests = mutableListOf<ModelCallRequest>()
    var failSummary = false
    var summaryGate: CompletableDeferred<Unit>? = null
    val summaryStarted = CompletableDeferred<Unit>()
    override fun stream(request: ModelCallRequest) = flow {
        requests += request
        val summary = request.systemPrompt == ContextCompressor.PROMPT
        if (summary) {
            summaryStarted.complete(Unit)
            if (failSummary) error("Summary failed")
            summaryGate?.await()
        }
        emit(ModelStreamEvent.TextDelta(if (summary) "Concise historical facts." else "Reply."))
        emit(ModelStreamEvent.TokenUsage(Usage(20, 5)))
        emit(ModelStreamEvent.Completed)
    }
}
