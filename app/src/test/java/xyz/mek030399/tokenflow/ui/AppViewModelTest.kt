package xyz.mek030399.tokenflow.ui

import xyz.mek030399.tokenflow.R
import xyz.mek030399.tokenflow.data.ChatDataSource
import xyz.mek030399.tokenflow.data.ChatEvent
import xyz.mek030399.tokenflow.data.ChatMessage
import xyz.mek030399.tokenflow.data.BookmarkedMessage
import xyz.mek030399.tokenflow.data.ConfigArchivePayload
import xyz.mek030399.tokenflow.data.ConfigProviderRecord
import xyz.mek030399.tokenflow.data.Conversation
import xyz.mek030399.tokenflow.data.ConversationDetail
import xyz.mek030399.tokenflow.data.ConversationWriteRequest
import xyz.mek030399.tokenflow.data.GlobalChatSettings
import xyz.mek030399.tokenflow.data.ImportPreview
import xyz.mek030399.tokenflow.data.KnowledgeDocument
import xyz.mek030399.tokenflow.data.KnowledgeSnippet
import xyz.mek030399.tokenflow.data.MAX_NOTE_SUMMARY_INPUT_CHARACTERS
import xyz.mek030399.tokenflow.data.ModelProfile
import xyz.mek030399.tokenflow.data.Note
import xyz.mek030399.tokenflow.data.NoteSummaryTooLongException
import xyz.mek030399.tokenflow.data.PendingAttachment
import xyz.mek030399.tokenflow.data.PendingAttachmentOrigin
import xyz.mek030399.tokenflow.data.ProcessEvent
import xyz.mek030399.tokenflow.data.ProviderConfig
import xyz.mek030399.tokenflow.data.ProviderDraft
import xyz.mek030399.tokenflow.data.ProviderEditorData
import xyz.mek030399.tokenflow.data.ProviderProtocol
import xyz.mek030399.tokenflow.data.RemoteModel
import xyz.mek030399.tokenflow.data.SendMessageRequest
import xyz.mek030399.tokenflow.data.TtsAudio
import xyz.mek030399.tokenflow.data.Usage
import xyz.mek030399.tokenflow.data.UrlReadDiagnostic
import xyz.mek030399.tokenflow.data.VisionStatus
import xyz.mek030399.tokenflow.data.WorkspaceSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun firstLaunchWithoutModelsOpensProviderSetupWithoutLogin() = runTest(dispatcher) {
        val fake = FakeChatDataSource(withModel = false)
        val viewModel = AppViewModel(fake)

        advanceUntilIdle()

        assertTrue(fake.initialized)
        assertEquals(AppPhase.SETUP, viewModel.state.value.phase)
        assertEquals(AppScreen.PROVIDERS, viewModel.state.value.screen)
        assertFalse(viewModel.state.value.hasModels)
    }

    @Test
    fun prepareImportClearsStaleTransferState() = runTest(dispatcher) {
        val fake = FakeChatDataSource(withModel = false)
        val preview = importPreview(fake, listOf(fake.model))
        fake.nextImportPreview = preview
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()

        viewModel.previewImport("archive", "archive password".toCharArray())
        advanceUntilIdle()
        assertEquals(preview, viewModel.state.value.transfer.importPreview)

        viewModel.prepareImport()

        assertEquals(TransferState(), viewModel.state.value.transfer)
    }

    @Test
    fun invalidImportPasswordShowsErrorAndClearsBusyState() = runTest(dispatcher) {
        val fake = FakeChatDataSource(withModel = false).apply {
            previewImportFailure = IllegalArgumentException("Invalid archive password")
        }
        val password = "incorrect password".toCharArray()
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()

        viewModel.previewImport("archive", password)
        advanceUntilIdle()

        assertFalse(viewModel.state.value.transfer.busy)
        assertNotNull(viewModel.state.value.transfer.error)
        assertEquals(null, viewModel.state.value.transfer.importPreview)
        assertTrue(password.all { it == '\u0000' })
    }

    @Test
    fun cancellingImportPreviewClearsPreviewWithoutApplying() = runTest(dispatcher) {
        val fake = FakeChatDataSource(withModel = false)
        val preview = importPreview(fake, listOf(fake.model))
        fake.nextImportPreview = preview
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()
        viewModel.previewImport("archive", "archive password".toCharArray())
        advanceUntilIdle()

        viewModel.cancelImportPreview()

        assertEquals(TransferState(), viewModel.state.value.transfer)
        assertEquals(null, fake.appliedImport)
    }

    @Test
    fun failedImportApplyKeepsPreviewAndShowsError() = runTest(dispatcher) {
        val fake = FakeChatDataSource(withModel = false)
        val preview = importPreview(fake, listOf(fake.model))
        fake.nextImportPreview = preview
        fake.applyImportFailure = IllegalStateException("Database merge failed")
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()
        viewModel.previewImport("archive", "archive password".toCharArray())
        advanceUntilIdle()

        viewModel.applyImport(openChatWhenReady = true)
        advanceUntilIdle()

        assertFalse(viewModel.state.value.transfer.busy)
        assertEquals(preview, viewModel.state.value.transfer.importPreview)
        assertNotNull(viewModel.state.value.transfer.error)
        assertEquals(AppPhase.SETUP, viewModel.state.value.phase)
        assertEquals(AppScreen.PROVIDERS, viewModel.state.value.screen)
    }

    @Test
    fun setupImportWithModelsOpensChatWhenRequested() = runTest(dispatcher) {
        val fake = FakeChatDataSource(withModel = false)
        fake.nextImportPreview = importPreview(fake, listOf(fake.model))
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()

        viewModel.previewImport("archive", "archive password".toCharArray())
        advanceUntilIdle()
        viewModel.applyImport(openChatWhenReady = true)
        advanceUntilIdle()

        assertNotNull(fake.appliedImport)
        assertEquals(AppPhase.READY, viewModel.state.value.phase)
        assertEquals(AppScreen.CHAT, viewModel.state.value.screen)
        assertEquals(TransferState(), viewModel.state.value.transfer)
        assertEquals(R.string.import_complete, (viewModel.state.value.notice as UiText.Resource).id)
    }

    @Test
    fun setupImportWithoutModelsStaysInProviderSetup() = runTest(dispatcher) {
        val fake = FakeChatDataSource(withModel = false)
        fake.nextImportPreview = importPreview(fake, emptyList())
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()

        viewModel.previewImport("archive", "archive password".toCharArray())
        advanceUntilIdle()
        viewModel.applyImport(openChatWhenReady = true)
        advanceUntilIdle()

        assertNotNull(fake.appliedImport)
        assertEquals(AppPhase.SETUP, viewModel.state.value.phase)
        assertEquals(AppScreen.PROVIDERS, viewModel.state.value.screen)
        assertFalse(viewModel.state.value.hasModels)
    }

    @Test
    fun configuredImportKeepsCurrentScreenByDefault() = runTest(dispatcher) {
        val fake = FakeChatDataSource(withModel = true)
        fake.nextImportPreview = importPreview(fake, listOf(fake.model))
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()
        viewModel.openScreen(AppScreen.TRANSFER)

        viewModel.previewImport("archive", "archive password".toCharArray())
        advanceUntilIdle()
        viewModel.applyImport()
        advanceUntilIdle()

        assertNotNull(fake.appliedImport)
        assertEquals(AppPhase.READY, viewModel.state.value.phase)
        assertEquals(AppScreen.TRANSFER, viewModel.state.value.screen)
    }

    @Test
    fun configuredWorkspaceStartsChatAndMergesStreamEvents() = runTest(dispatcher) {
        val fake = FakeChatDataSource(withModel = true)
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()

        assertEquals(AppPhase.READY, viewModel.state.value.phase)
        viewModel.send("Hello locally")
        advanceUntilIdle()

        assertNotNull(fake.sentRequest?.requestId)
        assertNotNull(fake.sentRequest?.timeZone)
        assertEquals("Hello locally", fake.sentRequest?.content)
        assertEquals("Answer from provider", viewModel.state.value.activeMessages.last().content)
        assertFalse(viewModel.state.value.activeGeneration?.active ?: true)
        assertTrue(viewModel.state.value.generations.values.single().events.any { it.type == "thinking" })
    }

    @Test
    fun knowledgeCitationOpensPreviewAndMissingChunkShowsNotice() = runTest(dispatcher) {
        val snippet = KnowledgeSnippet(
            chunkId = 42,
            documentId = "document-1",
            documentName = "pricing.md",
            position = 0,
            text = "Cached input costs two yuan.",
        )
        val fake = FakeChatDataSource(withModel = true).apply { knowledgeSnippets += snippet }
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()

        viewModel.openKnowledgeCitation(snippet.chunkId)
        advanceUntilIdle()

        assertEquals(snippet, viewModel.state.value.knowledgeSourcePreview)
        viewModel.closeKnowledgeSourcePreview()
        assertEquals(null, viewModel.state.value.knowledgeSourcePreview)

        fake.knowledgeSnippets.clear()
        viewModel.openKnowledgeCitation(snippet.chunkId)
        advanceUntilIdle()

        assertEquals(null, viewModel.state.value.knowledgeSourcePreview)
        assertEquals(R.string.knowledge_source_unavailable, (viewModel.state.value.notice as UiText.Resource).id)
    }

    @Test
    fun conversationSelectionAndBatchDeleteUseUuidIds() = runTest(dispatcher) {
        val fake = FakeChatDataSource(withModel = true).apply {
            conversations += Conversation(id = "conversation-existing", title = "Existing", model = model.id)
        }
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()

        viewModel.openConversation("conversation-existing")
        advanceUntilIdle()
        assertEquals("conversation-existing", viewModel.state.value.activeConversationId)

        viewModel.deleteConversations(setOf("conversation-existing"))
        advanceUntilIdle()
        assertTrue("conversation-existing" in fake.deleted)
        assertEquals(null, viewModel.state.value.activeConversationId)
    }

    @Test
    fun deletingConversationRemovesBookmarksAndRelatedScrollTarget() = runTest(dispatcher) {
        val conversation = Conversation(id = "conversation-bookmarked", title = "Saved", model = "model-1")
        val message = ChatMessage(id = "message-bookmarked", conversationId = conversation.id, role = "assistant")
        val bookmark = BookmarkedMessage(
            id = "bookmark-1",
            messageId = message.id,
            conversationId = conversation.id,
        )
        val fake = FakeChatDataSource(withModel = true).apply {
            conversations += conversation
            messageMap[conversation.id] = listOf(message)
            bookmarks += bookmark
        }
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()

        viewModel.openBookmark(bookmark)
        advanceUntilIdle()
        assertEquals(message.id, viewModel.state.value.scrollToMessageId)

        viewModel.deleteConversations(setOf(conversation.id))
        advanceUntilIdle()

        assertTrue(viewModel.state.value.bookmarks.isEmpty())
        assertEquals(null, viewModel.state.value.scrollToMessageId)
        assertEquals(null, viewModel.state.value.activeConversationId)
    }

    @Test
    fun staleBookmarkIsDiscardedWithoutOpeningMissingConversation() = runTest(dispatcher) {
        val bookmark = BookmarkedMessage(
            id = "bookmark-stale",
            messageId = "message-missing",
            conversationId = "conversation-missing",
        )
        val fake = FakeChatDataSource(withModel = true).apply { bookmarks += bookmark }
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()

        viewModel.openBookmark(bookmark)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.bookmarks.isEmpty())
        assertEquals(null, viewModel.state.value.activeConversationId)
        assertEquals(null, viewModel.state.value.scrollToMessageId)
        assertEquals(null, viewModel.state.value.notice)
    }

    @Test
    fun delayedBookmarkLoadCannotRestoreADeletedConversation() = runTest(dispatcher) {
        val conversation = Conversation(id = "conversation-delayed", title = "Delayed", model = "model-1")
        val bookmark = BookmarkedMessage(
            id = "bookmark-delayed",
            messageId = "message-delayed",
            conversationId = conversation.id,
        )
        val gate = CompletableDeferred<Unit>()
        val fake = FakeChatDataSource(withModel = true).apply {
            conversations += conversation
            bookmarks += bookmark
            conversationGate = gate
        }
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()

        viewModel.openBookmark(bookmark)
        runCurrent()
        viewModel.deleteConversations(setOf(conversation.id))
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.conversations.none { it.id == conversation.id })
        assertTrue(viewModel.state.value.bookmarks.isEmpty())
        assertEquals(null, viewModel.state.value.activeConversationId)
        assertEquals(null, viewModel.state.value.notice)
    }

    @Test
    fun staleWorkspaceLoadCannotRestoreADeletedConversation() = runTest(dispatcher) {
        val conversation = Conversation(id = "conversation-workspace-race", title = "Workspace race", model = "model-1")
        val bookmark = BookmarkedMessage(
            id = "bookmark-workspace-race",
            messageId = "message-workspace-race",
            conversationId = conversation.id,
        )
        val fake = FakeChatDataSource(withModel = true).apply {
            conversations += conversation
            bookmarks += bookmark
        }
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        fake.workspaceGate = gate

        viewModel.pinConversation(conversation.id, true)
        runCurrent()
        viewModel.deleteConversations(setOf(conversation.id))
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.conversations.none { it.id == conversation.id })
        assertTrue(viewModel.state.value.bookmarks.isEmpty())
        assertFalse(viewModel.state.value.loading)
    }

    @Test
    fun creatingBranchRegistersItBeforeLoadingItsMessages() = runTest(dispatcher) {
        val fake = FakeChatDataSource(withModel = true)
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()

        viewModel.createBranch("source-message", "Branch title")
        advanceUntilIdle()

        val branch = viewModel.state.value.conversations.single { it.id == "conversation-branch" }
        assertEquals(branch.id, viewModel.state.value.activeConversationId)
        assertEquals("Branched answer", viewModel.state.value.messages.getValue(branch.id).single().content)
        assertEquals(null, viewModel.state.value.notice)
    }

    @Test
    fun cameraDraftsAreDiscardedWhenRemovedRejectedOrAbandoned() = runTest(dispatcher) {
        val fake = FakeChatDataSource(withModel = true).apply {
            conversations += Conversation(id = "conversation-existing", title = "Existing", model = model.id)
        }
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()

        val removed = cameraDraft("removed")
        viewModel.addAttachments(listOf(removed))
        viewModel.removeAttachment(removed.uri)
        advanceUntilIdle()
        assertTrue(removed in fake.discardedAttachments)

        val accepted = (1..5).map { cameraDraft("accepted-$it") }
        val rejected = cameraDraft("rejected")
        viewModel.addAttachments(accepted + rejected)
        advanceUntilIdle()
        assertEquals(5, viewModel.state.value.pendingAttachments.size)
        assertTrue(rejected in fake.discardedAttachments)

        viewModel.newConversation()
        advanceUntilIdle()
        assertTrue(fake.discardedAttachments.containsAll(accepted))

        val switched = cameraDraft("switched")
        viewModel.addAttachments(listOf(switched))
        viewModel.openConversation("conversation-existing")
        advanceUntilIdle()
        assertTrue(switched in fake.discardedAttachments)
        assertTrue(viewModel.state.value.pendingAttachments.isEmpty())
    }

    @Test
    fun switchingImmediatelyAfterSendDoesNotDiscardInFlightCameraDraft() = runTest(dispatcher) {
        val fake = FakeChatDataSource(withModel = true).apply {
            conversations += Conversation(id = "conversation-source", title = "Source", model = model.id)
            conversations += Conversation(id = "conversation-other", title = "Other", model = model.id)
        }
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()
        viewModel.openConversation("conversation-source")
        advanceUntilIdle()
        assertEquals("conversation-source", viewModel.state.value.activeConversationId)

        val inFlight = cameraDraft("in-flight")
        viewModel.addAttachments(listOf(inFlight))
        viewModel.send("with camera")
        assertTrue(viewModel.state.value.pendingAttachments.isEmpty())
        viewModel.openConversation("conversation-other")
        advanceUntilIdle()

        assertTrue(inFlight !in fake.discardedAttachments)
        assertEquals(listOf(inFlight), fake.sentRequest?.attachments)
    }

    @Test
    fun speechAutoPlayIsOneShotAndScopedToItsChatInstance() = runTest(dispatcher) {
        val viewModel = AppViewModel(FakeChatDataSource(withModel = true))
        advanceUntilIdle()
        val event = async(start = CoroutineStart.UNDISPATCHED) { viewModel.speechAutoPlay.first() }
        val target = SpeechAutoPlayTarget("conversation-a", "chat-instance-a")

        viewModel.synthesizeSpeech("assistant-message", autoPlayTarget = target)
        advanceUntilIdle()

        val request = event.await()
        assertEquals("assistant-message", request.messageId)
        assertEquals(target, request.target)
        assertTrue(shouldAutoPlaySpeech(request, "conversation-a", "chat-instance-a"))
        assertFalse(shouldAutoPlaySpeech(request, "conversation-a", "chat-instance-after-notes"))
        assertFalse(shouldAutoPlaySpeech(request, "conversation-b", "chat-instance-a"))
        assertTrue(viewModel.speechAutoPlay.replayCache.isEmpty())
    }

    @Test
    fun noteSummaryUsesSelectedModelAndReplacesTitleAndBodyTogether() = runTest(dispatcher) {
        val fake = FakeChatDataSource(withModel = true).apply {
            notes += Note(id = "note-summary", title = "Original", body = "Original body")
        }
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()

        viewModel.summarizeNote("note-summary", fake.model.id, "Keep the decision log")
        advanceUntilIdle()

        assertEquals(fake.model.id, fake.noteSummaryModelId)
        assertEquals("Keep the decision log", fake.noteSummaryPrompt)
        assertEquals("Generated note title", viewModel.state.value.notes.single().title)
        assertEquals("Generated note body", viewModel.state.value.notes.single().body)
        assertEquals(null, viewModel.state.value.noteSummarizingId)
    }

    @Test
    fun oversizedNoteSummaryKeepsOriginalAndUsesLocalizedNotice() = runTest(dispatcher) {
        val original = Note(id = "note-oversized", title = "Original", body = "Original body")
        val fake = FakeChatDataSource(withModel = true).apply {
            notes += original
            noteSummaryFailure = NoteSummaryTooLongException(MAX_NOTE_SUMMARY_INPUT_CHARACTERS)
        }
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()

        viewModel.summarizeNote(original.id, fake.model.id)
        advanceUntilIdle()

        assertEquals(original, viewModel.state.value.notes.single())
        assertEquals(
            UiText.Resource(
                xyz.mek030399.tokenflow.R.string.note_too_long_to_summarize,
                listOf(MAX_NOTE_SUMMARY_INPUT_CHARACTERS),
            ),
            viewModel.state.value.notice,
        )
        assertEquals(null, viewModel.state.value.noteSummarizingId)
    }

    @Test
    fun activeKnowledgeCopyBlocksDuplicateNoteImport() = runTest(dispatcher) {
        val fake = FakeChatDataSource(withModel = true).apply {
            notes += Note(id = "note-imported", title = "Imported", body = "Body")
            knowledgeDocuments += knowledgeDocument("existing", "note-imported", "ready")
        }
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()

        viewModel.importNoteToKnowledge("note-imported")
        advanceUntilIdle()

        assertEquals(0, fake.noteKnowledgeImportCalls)
        assertEquals("existing", viewModel.state.value.knowledgeDocuments.single().id)
    }

    @Test
    fun failedKnowledgeCopyCanRetryAndIsReplacedImmediately() = runTest(dispatcher) {
        val fake = FakeChatDataSource(withModel = true).apply {
            notes += Note(id = "note-retry", title = "Retry", body = "Body")
            knowledgeDocuments += knowledgeDocument("failed", "note-retry", "error")
        }
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()

        viewModel.importNoteToKnowledge("note-retry")
        advanceUntilIdle()

        assertEquals(1, fake.noteKnowledgeImportCalls)
        assertEquals(listOf("imported-note-retry"), viewModel.state.value.knowledgeDocuments.map { it.id })
        assertEquals("ready", viewModel.state.value.knowledgeDocuments.single().status)
        assertEquals(null, viewModel.state.value.noteImportingId)
    }

    @Test
    fun urlTestCancellationClearsBusyStateWithoutShowingAnError() = runTest(dispatcher) {
        val fake = FakeChatDataSource(withModel = true)
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()
        fake.urlTestFailure = CancellationException("cancelled")

        viewModel.testUrl("https://example.com")
        advanceUntilIdle()

        assertFalse(viewModel.state.value.urlTestBusy)
        assertEquals(null, viewModel.state.value.urlTestResult)
        assertEquals(null, viewModel.state.value.notice)
    }

    private fun cameraDraft(id: String) = PendingAttachment(
        uri = "content://camera/$id",
        displayName = "$id.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 100,
        origin = PendingAttachmentOrigin.CAMERA,
        appOwnedDraftPath = "/camera/$id.jpg",
    )

    private fun importPreview(fake: FakeChatDataSource, models: List<ModelProfile>) = ImportPreview(
        payload = ConfigArchivePayload(
            createdAt = 1L,
            providers = listOf(ConfigProviderRecord(fake.provider, "provider-key")),
            models = models,
            defaultModelId = models.firstOrNull()?.id,
        ),
        newProviders = 1,
        updatedProviders = 0,
        newModels = models.size,
        updatedModels = 0,
        replacesExaKey = false,
    )
}

private class FakeChatDataSource(withModel: Boolean) : ChatDataSource {
    val provider = ProviderConfig("provider-1", "Provider", "https://api.example.com/v1", ProviderProtocol.OPENAI_RESPONSES, true)
    val model = ModelProfile(
        "model-1",
        provider.id,
        "model-a",
        "Model A",
        4096,
        true,
        visionStatus = VisionStatus.SUPPORTED,
    )
    val conversations = mutableListOf<Conversation>()
    val messageMap = mutableMapOf<String, List<ChatMessage>>()
    val deleted = mutableSetOf<String>()
    val discardedAttachments = mutableListOf<PendingAttachment>()
    val bookmarks = mutableListOf<BookmarkedMessage>()
    val notes = mutableListOf<Note>()
    val knowledgeDocuments = mutableListOf<KnowledgeDocument>()
    val knowledgeSnippets = mutableListOf<KnowledgeSnippet>()
    var initialized = false
    var sentRequest: SendMessageRequest? = null
    var noteSummaryFailure: Throwable? = null
    var noteSummaryModelId: String? = null
    var noteSummaryPrompt: String? = null
    var noteKnowledgeImportCalls = 0
    var urlTestFailure: Throwable? = null
    var conversationGate: CompletableDeferred<Unit>? = null
    var workspaceGate: CompletableDeferred<Unit>? = null
    var nextImportPreview: ImportPreview? = null
    var appliedImport: ImportPreview? = null
    var previewImportFailure: Throwable? = null
    var applyImportFailure: Throwable? = null
    private var models = if (withModel) listOf(model) else emptyList()

    override suspend fun initialize() {
        initialized = true
    }

    override suspend fun workspace(): WorkspaceSnapshot {
        val snapshot = WorkspaceSnapshot(
            providers = if (models.isEmpty()) emptyList() else listOf(provider),
            models = models,
            conversations = conversations.toList(),
            exaConfigured = false,
            globalSettings = GlobalChatSettings(defaultModelId = models.firstOrNull()?.id),
            bookmarks = bookmarks.toList(),
            notes = notes.toList(),
            knowledgeDocuments = knowledgeDocuments.toList(),
        )
        workspaceGate?.await()
        return snapshot
    }

    override suspend fun provider(id: String) = ProviderEditorData(
        ProviderDraft(provider.id, provider.name, provider.baseUrl, provider.protocol, "secret"),
        models,
    )

    override suspend fun fetchModels(draft: ProviderDraft) = listOf(RemoteModel("model-a"))

    override suspend fun saveProvider(draft: ProviderDraft, models: List<ModelProfile>): ProviderConfig {
        this.models = models
        return provider
    }

    override suspend fun deleteProvider(id: String) {
        models = emptyList()
    }

    override suspend fun setDefaultModel(id: String) = Unit
    override suspend fun synthesizeSpeech(messageId: String, force: Boolean) =
        TtsAudio(File("$messageId.wav"), fromCache = false)
    override fun exaConfigured() = false
    override fun saveExaKey(value: String) = Unit
    override suspend fun testUrl(url: String): UrlReadDiagnostic {
        urlTestFailure?.let { throw it }
        return UrlReadDiagnostic(
            source = "infoflow",
            finalUrl = url,
            elapsedMs = 1,
            success = true,
            detail = "ok",
        )
    }
    override suspend fun conversations() = conversations.toList()
    override suspend fun knowledgeSnippets(ids: List<Long>) =
        knowledgeSnippets.filter { it.chunkId in ids }

    override suspend fun conversation(id: String): ConversationDetail {
        val conversation = conversations.first { it.id == id }
        val detail = ConversationDetail(conversation, messageMap[id].orEmpty())
        conversationGate?.await()
        return detail
    }

    override suspend fun createConversation(request: ConversationWriteRequest): Conversation {
        val conversation = Conversation(
            id = "conversation-${conversations.size + 1}",
            model = request.model ?: model.id,
            thinkingEffort = request.thinkingEffort ?: "medium",
            systemPrompt = request.systemPrompt.orEmpty(),
            nickname = request.nickname.orEmpty(),
            maxToolCalls = request.maxToolCalls ?: 7,
        )
        conversations += conversation
        return conversation
    }

    override suspend fun createBranch(messageId: String, title: String): Conversation {
        val branch = Conversation(id = "conversation-branch", title = title, model = model.id)
        conversations += branch
        messageMap[branch.id] = listOf(ChatMessage(
            id = "branch-assistant",
            conversationId = branch.id,
            role = "assistant",
            content = "Branched answer",
        ))
        return branch
    }

    override suspend fun updateConversation(id: String, request: ConversationWriteRequest): Conversation {
        val index = conversations.indexOfFirst { it.id == id }
        val current = conversations[index]
        val updated = current.copy(
            title = request.title ?: current.title,
            model = request.model ?: current.model,
            systemPrompt = request.systemPrompt ?: current.systemPrompt,
        )
        conversations[index] = updated
        return updated
    }

    override suspend fun deleteConversations(ids: Set<String>) {
        deleted += ids
        conversations.removeAll { it.id in ids }
        ids.forEach(messageMap::remove)
        bookmarks.removeAll { it.conversationId in ids }
    }

    override suspend fun discardPendingAttachments(attachments: List<PendingAttachment>) {
        discardedAttachments += attachments
    }

    override suspend fun summarizeNote(noteId: String, modelId: String, rewritePrompt: String): Note {
        noteSummaryModelId = modelId
        noteSummaryPrompt = rewritePrompt
        noteSummaryFailure?.let { throw it }
        val index = notes.indexOfFirst { it.id == noteId }
        val updated = notes[index].copy(title = "Generated note title", body = "Generated note body")
        notes[index] = updated
        return updated
    }

    override suspend fun importNoteToKnowledge(noteId: String): KnowledgeDocument {
        noteKnowledgeImportCalls += 1
        val document = knowledgeDocument("imported-$noteId", noteId, "ready")
        knowledgeDocuments.removeAll { it.sourceNoteId == noteId }
        knowledgeDocuments += document
        return document
    }

    override suspend fun generateTitle(id: String, force: Boolean): Conversation =
        updateConversation(id, ConversationWriteRequest(title = "Generated title"))

    override fun sendMessage(id: String, request: SendMessageRequest): Flow<ChatEvent> = flow {
        sentRequest = request
        val user = ChatMessage("user-1", id, requestId = request.requestId, role = "user", content = request.content)
        val initial = ChatMessage("assistant-1", id, requestId = request.requestId, role = "assistant", status = "generating")
        val final = initial.copy(content = "Answer from provider", status = "completed")
        emit(ChatEvent.UserMessage(user))
        emit(ChatEvent.AssistantMessage(initial))
        emit(ChatEvent.Process(ProcessEvent(type = "thinking", id = "thinking-1", content = "summary")))
        emit(ChatEvent.Delta("Answer from provider"))
        messageMap[id] = listOf(user, final)
        emit(ChatEvent.Done(Usage(3, 4), false))
    }

    override fun regenerate(id: String, request: SendMessageRequest): Flow<ChatEvent> = flow { }
    override suspend fun exportConfiguration(password: CharArray) = "archive"
    override suspend fun previewImport(raw: String, password: CharArray): ImportPreview {
        previewImportFailure?.let { throw it }
        return requireNotNull(nextImportPreview) { "Import preview was not configured" }
    }

    override suspend fun applyImport(preview: ImportPreview) {
        applyImportFailure?.let { throw it }
        appliedImport = preview
        models = preview.payload.models
    }
}

private fun knowledgeDocument(id: String, sourceNoteId: String, status: String) = KnowledgeDocument(
    id = id,
    name = "$id.md",
    mimeType = "text/markdown",
    storedPath = "/knowledge/$id.md",
    sizeBytes = 4,
    status = status,
    sourceNoteId = sourceNoteId,
)
