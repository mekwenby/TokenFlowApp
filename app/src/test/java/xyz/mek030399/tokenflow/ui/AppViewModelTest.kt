package xyz.mek030399.tokenflow.ui

import xyz.mek030399.tokenflow.R
import androidx.lifecycle.ViewModelStore
import xyz.mek030399.tokenflow.background.GenerationCoordinator
import xyz.mek030399.tokenflow.background.GenerationLifecycle
import xyz.mek030399.tokenflow.background.GenerationSnapshot
import xyz.mek030399.tokenflow.data.MessageSearchCursor
import xyz.mek030399.tokenflow.data.MessageSearchHit
import xyz.mek030399.tokenflow.data.MessageSearchPage
import xyz.mek030399.tokenflow.data.ChatDataSource
import xyz.mek030399.tokenflow.data.ChatEvent
import xyz.mek030399.tokenflow.data.ChatMessage
import xyz.mek030399.tokenflow.data.AgentProfile
import xyz.mek030399.tokenflow.data.BookmarkedMessage
import xyz.mek030399.tokenflow.data.CloudConnectionProbe
import xyz.mek030399.tokenflow.data.CloudArtifactDelivery
import xyz.mek030399.tokenflow.data.CloudArtifactDeliveryStatus
import xyz.mek030399.tokenflow.data.CloudArtifactSourceType
import xyz.mek030399.tokenflow.data.CloudFileEntry
import xyz.mek030399.tokenflow.data.CloudMcpServer
import xyz.mek030399.tokenflow.data.CloudServerDraft
import xyz.mek030399.tokenflow.data.CloudServerProfile
import xyz.mek030399.tokenflow.data.CloudTask
import xyz.mek030399.tokenflow.data.ConfigArchivePayload
import xyz.mek030399.tokenflow.data.ConfigProviderRecord
import xyz.mek030399.tokenflow.data.Conversation
import xyz.mek030399.tokenflow.data.ConversationDetail
import xyz.mek030399.tokenflow.data.ConversationWriteRequest
import xyz.mek030399.tokenflow.data.GlobalChatSettings
import xyz.mek030399.tokenflow.data.ImportPreview
import xyz.mek030399.tokenflow.data.ImportedMarkdownNote
import xyz.mek030399.tokenflow.data.KnowledgeDocument
import xyz.mek030399.tokenflow.data.KnowledgeDocumentPreview
import xyz.mek030399.tokenflow.data.KnowledgeSnippet
import xyz.mek030399.tokenflow.data.KnowledgeScope
import xyz.mek030399.tokenflow.data.KnowledgeScopeMode
import xyz.mek030399.tokenflow.data.MAX_NOTE_SUMMARY_INPUT_CHARACTERS
import xyz.mek030399.tokenflow.data.ModelProfile
import xyz.mek030399.tokenflow.data.Note
import xyz.mek030399.tokenflow.data.NoteMarkdownFileAccess
import xyz.mek030399.tokenflow.data.NoteMarkdownFileError
import xyz.mek030399.tokenflow.data.NoteMarkdownFileException
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
    fun applicationGenerationSurvivesViewModelClearingAndRecreatedUiRehydratesTheReply() = runTest(dispatcher) {
        val applicationScope = CoroutineScope(SupervisorJob() + dispatcher)
        val coordinator = GenerationCoordinator(applicationScope)
        val gate = CompletableDeferred<Unit>()
        val fake = FakeChatDataSource(true).apply {
            conversations += Conversation(id = "background-chat", model = model.id)
            sendMessageGate = gate
        }
        val first = AppViewModel(fake, generationCoordinator = coordinator)
        val firstOwner = ViewModelStore().apply { put("app", first) }
        advanceUntilIdle()
        first.openConversation("background-chat")
        advanceUntilIdle()
        assertTrue(first.send("Keep running"))
        runCurrent()
        assertEquals("Answer from provider", first.state.value.activeMessages.last().content)
        firstOwner.clear()
        runCurrent()
        assertTrue(coordinator.snapshots.value.getValue("background-chat").active)
        val recreated = AppViewModel(fake, generationCoordinator = coordinator)
        val recreatedOwner = ViewModelStore().apply { put("app", recreated) }
        advanceUntilIdle()
        recreated.openConversation("background-chat")
        runCurrent()
        assertTrue(recreated.state.value.activeGeneration?.active == true)
        assertEquals("Answer from provider", recreated.state.value.activeMessages.last().content)
        assertEquals("summary", recreated.state.value.activeGeneration?.events?.single()?.content)
        assertFalse(recreated.send("duplicate"))
        assertEquals(1, fake.sendMessageCalls)
        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(recreated.state.value.activeGeneration?.active == true)
        assertEquals("completed", recreated.state.value.activeMessages.last().status)
        assertEquals(7L, recreated.state.value.activeGeneration?.usage?.totalTokens)
        assertEquals("", recreated.state.value.composerText)
        recreatedOwner.clear()
        applicationScope.cancel()
    }

    @Test
    fun managedFailureBeforeAcceptanceRestoresDraftAndAttachmentsAndAllowsExplicitRetry() = runTest(dispatcher) {
        val applicationScope = CoroutineScope(SupervisorJob() + dispatcher)
        val coordinator = GenerationCoordinator(applicationScope)
        val fake = FakeChatDataSource(true).apply {
            conversations += Conversation(id = "failed-background", model = model.id)
            sendMessageFailureBeforeUser = IllegalStateException("prepare failed")
        }
        val viewModel = AppViewModel(fake, generationCoordinator = coordinator)
        val owner = ViewModelStore().apply { put("app", viewModel) }
        advanceUntilIdle()
        viewModel.openConversation("failed-background")
        advanceUntilIdle()
        val attachment = cameraDraft("background-draft")
        viewModel.setComposerText("Original question")
        viewModel.addAttachments(listOf(attachment))
        assertTrue(viewModel.send("Original question"))
        advanceUntilIdle()
        assertEquals("Original question", viewModel.state.value.composerText)
        assertEquals(listOf(attachment), viewModel.state.value.pendingAttachments)
        assertFalse(attachment in fake.discardedAttachments)
        fake.sendMessageFailureBeforeUser = null
        assertTrue(viewModel.send(viewModel.state.value.composerText))
        advanceUntilIdle()
        assertEquals(2, fake.sendMessageCalls)
        assertEquals("", viewModel.state.value.composerText)
        assertTrue(attachment in fake.discardedAttachments)
        owner.clear()
        applicationScope.cancel()
    }

    @Test
    fun recreatedUiCanStopAnAcceptedBackgroundReplyWithoutRestoringTheSubmittedQuestion() = runTest(dispatcher) {
        val applicationScope = CoroutineScope(SupervisorJob() + dispatcher)
        val coordinator = GenerationCoordinator(applicationScope)
        val cleanup = CompletableDeferred<Unit>()
        val fake = FakeChatDataSource(true).apply {
            conversations += Conversation(id = "stop-background", model = model.id)
            sendMessageGate = CompletableDeferred()
            cancellationCleanupGate = cleanup
        }
        val original = AppViewModel(fake, generationCoordinator = coordinator)
        val originalOwner = ViewModelStore().apply { put("app", original) }
        advanceUntilIdle()
        original.openConversation("stop-background")
        advanceUntilIdle()
        original.send("Accepted question")
        runCurrent()
        originalOwner.clear()
        val recreated = AppViewModel(fake, generationCoordinator = coordinator)
        val recreatedOwner = ViewModelStore().apply { put("app", recreated) }
        advanceUntilIdle()
        recreated.openConversation("stop-background")
        runCurrent()
        recreated.stopGeneration()
        runCurrent()
        assertTrue(recreated.state.value.activeGeneration?.stopping == true)
        assertFalse(recreated.send("Too early"))
        cleanup.complete(Unit)
        advanceUntilIdle()
        assertEquals("interrupted", recreated.state.value.activeMessages.last().status)
        assertEquals("", recreated.state.value.composerText)
        assertEquals(1, fake.sendMessageCalls)
        recreatedOwner.clear()
        applicationScope.cancel()
    }

    @Test
    fun foregroundServiceStartupRejectionRestoresComposerWithoutCallingTheModel() = runTest(dispatcher) {
        val applicationScope = CoroutineScope(SupervisorJob() + dispatcher)
        val coordinator = GenerationCoordinator(applicationScope, object : GenerationLifecycle {
            override fun onStarting(snapshot: GenerationSnapshot) { throw IllegalStateException("Background unavailable") }
        })
        val fake = FakeChatDataSource(true).apply {
            conversations += Conversation(id = "rejected-background", model = model.id)
        }
        val viewModel = AppViewModel(fake, generationCoordinator = coordinator)
        val owner = ViewModelStore().apply { put("app", viewModel) }
        advanceUntilIdle()
        viewModel.openConversation("rejected-background")
        advanceUntilIdle()
        viewModel.send("Retry later")
        advanceUntilIdle()
        assertEquals(0, fake.sendMessageCalls)
        assertEquals("Retry later", viewModel.state.value.composerText)
        assertEquals(UiText.Dynamic("Background unavailable"), viewModel.state.value.notice)
        owner.clear()
        applicationScope.cancel()
    }

    @Test
    fun deletedFinishedConversationIsNotReintroducedByAnotherManagedStream() = runTest(dispatcher) {
        val applicationScope = CoroutineScope(SupervisorJob() + dispatcher)
        val coordinator = GenerationCoordinator(applicationScope)
        val fake = FakeChatDataSource(true).apply {
            conversations += Conversation(id = "finished-delete", model = model.id)
            conversations += Conversation(id = "other-stream", model = model.id)
        }
        val viewModel = AppViewModel(fake, generationCoordinator = coordinator)
        val owner = ViewModelStore().apply { put("app", viewModel) }
        advanceUntilIdle()
        viewModel.openConversation("finished-delete")
        advanceUntilIdle()
        viewModel.send("Delete this completed conversation")
        advanceUntilIdle()
        viewModel.deleteConversations(setOf("finished-delete"))
        advanceUntilIdle()
        assertFalse(coordinator.snapshots.value.containsKey("finished-delete"))
        viewModel.openConversation("other-stream")
        advanceUntilIdle()
        fake.sendMessageGate = CompletableDeferred()
        viewModel.send("Another request")
        runCurrent()
        assertFalse(viewModel.state.value.messages.containsKey("finished-delete"))
        assertFalse(viewModel.state.value.generations.containsKey("finished-delete"))
        fake.sendMessageGate!!.complete(Unit)
        advanceUntilIdle()
        owner.clear()
        applicationScope.cancel()
    }

    @Test
    fun pendingNewConversationCreationSurvivesUiClearingAndRekeysTheSingleApplicationRequest() = runTest(dispatcher) {
        val applicationScope = CoroutineScope(SupervisorJob() + dispatcher)
        val coordinator = GenerationCoordinator(applicationScope)
        val create = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<Unit>()
        val fake = FakeChatDataSource(true).apply { createConversationGate = create; sendMessageGate = reply }
        val original = AppViewModel(fake, generationCoordinator = coordinator)
        val originalOwner = ViewModelStore().apply { put("app", original) }
        advanceUntilIdle()
        original.setComposerText("New chat question")
        assertTrue(original.send("New chat question"))
        runCurrent()
        val run = coordinator.snapshots.value.getValue(NEW_DRAFT_KEY).runId
        assertTrue(original.state.value.activeGeneration?.active == true)
        assertEquals(0, fake.sendMessageCalls)
        originalOwner.clear()
        val recreated = AppViewModel(fake, generationCoordinator = coordinator)
        val recreatedOwner = ViewModelStore().apply { put("app", recreated) }
        advanceUntilIdle()
        assertTrue(recreated.state.value.activeGeneration?.active == true)
        assertFalse(recreated.send("duplicate"))
        create.complete(Unit)
        runCurrent()
        val actualId = fake.conversations.single().id
        assertFalse(coordinator.snapshots.value.containsKey(NEW_DRAFT_KEY))
        assertEquals(run, coordinator.snapshots.value.getValue(actualId).runId)
        assertEquals(actualId, recreated.state.value.activeConversationId)
        assertFalse(recreated.state.value.generations.containsKey(NEW_DRAFT_KEY))
        assertEquals(1, fake.sendMessageCalls)
        assertEquals("New chat question", fake.sentRequest?.content)
        assertEquals("Answer from provider", recreated.state.value.activeMessages.last().content)
        reply.complete(Unit)
        advanceUntilIdle()
        assertEquals("completed", recreated.state.value.activeMessages.last().status)
        recreatedOwner.clear()
        applicationScope.cancel()
    }

    @Test
    fun cancelPendingNewConversationCreationRestoresDraftWithoutCreatingOrBillingAModelRequest() = runTest(dispatcher) {
        val applicationScope = CoroutineScope(SupervisorJob() + dispatcher)
        val coordinator = GenerationCoordinator(applicationScope)
        val fake = FakeChatDataSource(true).apply { createConversationGate = CompletableDeferred() }
        val original = AppViewModel(fake, generationCoordinator = coordinator)
        val originalOwner = ViewModelStore().apply { put("app", original) }
        advanceUntilIdle()
        original.send("Unaccepted question")
        runCurrent()
        originalOwner.clear()
        val recreated = AppViewModel(fake, generationCoordinator = coordinator)
        val recreatedOwner = ViewModelStore().apply { put("app", recreated) }
        advanceUntilIdle()
        recreated.stopGeneration()
        advanceUntilIdle()
        assertEquals("Unaccepted question", recreated.state.value.composerText)
        assertEquals(0, fake.sendMessageCalls)
        assertTrue(fake.conversations.isEmpty())
        assertFalse(recreated.state.value.activeGeneration?.active == true)
        recreatedOwner.clear()
        applicationScope.cancel()
    }

    @Test
    fun managedNewConversationCreationFailureRestoresOriginalDraftWithoutCallingAModel() = runTest(dispatcher) {
        val applicationScope = CoroutineScope(SupervisorJob() + dispatcher)
        val coordinator = GenerationCoordinator(applicationScope)
        val fake = FakeChatDataSource(true).apply { createConversationFailure = IllegalStateException("create failed") }
        val viewModel = AppViewModel(fake, generationCoordinator = coordinator)
        val owner = ViewModelStore().apply { put("app", viewModel) }
        advanceUntilIdle()
        viewModel.send("Keep this input")
        advanceUntilIdle()
        assertEquals("Keep this input", viewModel.state.value.composerText)
        assertTrue(fake.conversations.isEmpty())
        assertEquals(0, fake.sendMessageCalls)
        assertEquals(UiText.Dynamic("create failed"), viewModel.state.value.notice)
        owner.clear()
        applicationScope.cancel()
    }

    @Test
    fun streamingReplyDoesNotReopenItsConversationAfterTheUserStartsANewDraft() = runTest(dispatcher) {
        val applicationScope = CoroutineScope(SupervisorJob() + dispatcher)
        val coordinator = GenerationCoordinator(applicationScope)
        val nextToken = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val fake = FakeChatDataSource(true).apply { sendMessageContinuationGate = nextToken; sendMessageGate = finish }
        val viewModel = AppViewModel(fake, generationCoordinator = coordinator)
        val owner = ViewModelStore().apply { put("app", viewModel) }
        advanceUntilIdle()
        viewModel.send("First question")
        runCurrent()
        val originalId = fake.conversations.single().id
        assertEquals(originalId, viewModel.state.value.activeConversationId)
        viewModel.newConversation()
        assertEquals(null, viewModel.state.value.activeConversationId)
        nextToken.complete(Unit)
        runCurrent()
        assertEquals(null, viewModel.state.value.activeConversationId)
        assertTrue(coordinator.snapshots.value.getValue(originalId).active)
        viewModel.setComposerText("Independent next draft")
        assertEquals("Independent next draft", viewModel.state.value.composerText)
        finish.complete(Unit)
        advanceUntilIdle()
        assertEquals(null, viewModel.state.value.activeConversationId)
        assertEquals("Independent next draft", viewModel.state.value.composerText)
        owner.clear()
        applicationScope.cancel()
    }

    @Test
    fun recreatedUiInitiallyRestoresAnActiveNewConversationButLaterTokensRespectExplicitNewDraftNavigation() = runTest(dispatcher) {
        val applicationScope = CoroutineScope(SupervisorJob() + dispatcher)
        val coordinator = GenerationCoordinator(applicationScope)
        val nextToken = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val fake = FakeChatDataSource(true).apply { sendMessageContinuationGate = nextToken; sendMessageGate = finish }
        val original = AppViewModel(fake, generationCoordinator = coordinator)
        val originalOwner = ViewModelStore().apply { put("app", original) }
        advanceUntilIdle()
        original.send("Restore running chat")
        runCurrent()
        val originalId = fake.conversations.single().id
        originalOwner.clear()
        val restored = AppViewModel(fake, generationCoordinator = coordinator)
        val restoredOwner = ViewModelStore().apply { put("app", restored) }
        advanceUntilIdle()
        assertEquals(originalId, restored.state.value.activeConversationId)
        assertEquals("Answer from provider", restored.state.value.activeMessages.last().content)
        assertEquals(1, fake.sendMessageCalls)
        restored.newConversation()
        nextToken.complete(Unit)
        runCurrent()
        assertEquals(null, restored.state.value.activeConversationId)
        finish.complete(Unit)
        advanceUntilIdle()
        restoredOwner.clear()
        applicationScope.cancel()
    }

    @Test
    fun textAndAttachmentsRemainOwnedByTheirDraftAcrossConversationSwitches() = runTest(dispatcher) {
        val fake = FakeChatDataSource(true).apply {
            conversations += Conversation(id = "one", model = model.id)
            conversations += Conversation(id = "two", model = model.id)
        }
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()
        viewModel.openConversation("one")
        viewModel.setComposerText("unsent first")
        val attachment = cameraDraft("draft-one")
        viewModel.addAttachments(listOf(attachment))
        viewModel.openConversation("two")
        viewModel.setComposerText("unsent second")
        viewModel.openConversation("one")
        advanceUntilIdle()
        assertEquals("unsent first", viewModel.state.value.composerText)
        assertEquals(listOf(attachment), viewModel.state.value.pendingAttachments)
        assertFalse(attachment in fake.discardedAttachments)
        viewModel.openConversation("two")
        assertEquals("unsent second", viewModel.state.value.composerText)
    }

    @Test
    fun staleSearchCompletionCannotReplaceANewerQuery() = runTest(dispatcher) {
        val oldGate = CompletableDeferred<Unit>()
        val fake = FakeChatDataSource(true).apply {
            searchHandler = { query, _ ->
                if (query == "old") withContext(NonCancellable) { oldGate.await() }
                MessageSearchPage(listOf(MessageSearchHit(query, "chat", "Chat", null, "user", 1, query)))
            }
        }
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()
        viewModel.searchMessages("old")
        advanceTimeBy(301)
        runCurrent()
        viewModel.searchMessages("new")
        advanceTimeBy(301)
        runCurrent()
        assertEquals(listOf("new"), viewModel.state.value.messageSearchPage.items.map { it.messageId })
        oldGate.complete(Unit)
        advanceUntilIdle()
        assertEquals("new", viewModel.state.value.messageSearchQuery)
        assertEquals(listOf("new"), viewModel.state.value.messageSearchPage.items.map { it.messageId })
        assertFalse(viewModel.state.value.messageSearchBusy)
    }

    @Test
    fun knowledgeSearchFromThePreviousScopeCannotPopulateAMessageSearchDestination() = runTest(dispatcher) {
        val oldSearch = CompletableDeferred<Unit>()
        val firstScope = KnowledgeScope(KnowledgeScopeMode.SELECTED, listOf("first-document"))
        val secondScope = KnowledgeScope(KnowledgeScopeMode.SELECTED, listOf("second-document"))
        val fake = FakeChatDataSource(true).apply {
            conversations += Conversation(id = "first", model = model.id, knowledgeScope = firstScope)
            conversations += Conversation(id = "second", model = model.id, knowledgeScope = secondScope)
            messageMap["second"] = listOf(ChatMessage(id = "second-message", conversationId = "second", role = "user", content = "match"))
        }
        val repository = object : ChatDataSource by fake {
            override suspend fun searchKnowledge(query: String, scope: KnowledgeScope): List<KnowledgeSnippet> {
                assertEquals(firstScope, scope)
                withContext(NonCancellable) { oldSearch.await() }
                return listOf(KnowledgeSnippet(1, "first-document", "First", 0, "old result"))
            }
        }
        val viewModel = AppViewModel(repository)
        val owner = ViewModelStore().apply { put("app", viewModel) }
        advanceUntilIdle()
        viewModel.openConversation("first")
        advanceUntilIdle()
        viewModel.searchKnowledge("old query")
        runCurrent()
        viewModel.openScreen(AppScreen.MESSAGE_SEARCH)
        viewModel.openSearchResult(MessageSearchHit("second-message", "second", "Second", null, "user", 1, "match"))
        runCurrent()
        assertEquals("second", viewModel.state.value.activeConversationId)
        oldSearch.complete(Unit)
        advanceUntilIdle()
        assertEquals(secondScope, viewModel.state.value.config.knowledgeScope)
        assertTrue(viewModel.state.value.knowledgeResults.isEmpty())
        assertFalse(viewModel.state.value.knowledgeSearchBusy)
        owner.clear()
    }

    @Test
    fun laterNotificationNavigationWinsOverAnEarlierSlowConversationRead() = runTest(dispatcher) {
        val firstRead = CompletableDeferred<Unit>()
        val fake = FakeChatDataSource(true).apply {
            conversations += Conversation(id = "first", model = model.id)
            conversations += Conversation(id = "second", model = model.id)
        }
        val repository = object : ChatDataSource by fake {
            override suspend fun conversation(id: String): ConversationDetail {
                val detail = fake.conversation(id)
                if (id == "first") withContext(NonCancellable) { firstRead.await() }
                return detail
            }
        }
        val viewModel = AppViewModel(repository)
        val owner = ViewModelStore().apply { put("app", viewModel) }
        advanceUntilIdle()
        viewModel.openNotifiedConversation("first")
        runCurrent()
        viewModel.openNotifiedConversation("second")
        runCurrent()
        assertEquals("second", viewModel.state.value.activeConversationId)
        firstRead.complete(Unit)
        advanceUntilIdle()
        assertEquals("second", viewModel.state.value.activeConversationId)
        owner.clear()
    }

    @Test
    fun pendingNewConversationNotificationSelectsItsDraftAndFollowsTheResolvedConversation() = runTest(dispatcher) {
        val applicationScope = CoroutineScope(SupervisorJob() + dispatcher)
        val coordinator = GenerationCoordinator(applicationScope)
        val creation = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<Unit>()
        val fake = FakeChatDataSource(true).apply {
            conversations += Conversation(id = "other", model = model.id)
            createConversationGate = creation
            sendMessageGate = reply
        }
        val viewModel = AppViewModel(fake, generationCoordinator = coordinator)
        val owner = ViewModelStore().apply { put("app", viewModel) }
        advanceUntilIdle()
        assertTrue(viewModel.send("pending new question"))
        runCurrent()
        viewModel.openConversation("other")
        runCurrent()
        viewModel.setComposerText("keep other draft")
        viewModel.openNotifiedConversation(NEW_DRAFT_KEY)
        runCurrent()
        assertEquals(null, viewModel.state.value.activeConversationId)
        assertTrue(viewModel.state.value.activeGeneration?.active == true)
        creation.complete(Unit)
        runCurrent()
        val actualId = fake.conversations.first { it.id != "other" }.id
        assertEquals(actualId, viewModel.state.value.activeConversationId)
        assertEquals("keep other draft", viewModel.state.value.drafts["other"]?.text)
        reply.complete(Unit)
        advanceUntilIdle()
        owner.clear()
        applicationScope.cancel()
    }

    @Test
    fun firstSearchNavigationLoadsMessagesBeforePublishingTheScrollTarget() = runTest(dispatcher) {
        val conversation = Conversation(id = "found", title = "Archived", model = "model-1", archivedAt = 100)
        val fake = FakeChatDataSource(true).apply {
            conversations += conversation
            messageMap[conversation.id] = listOf(ChatMessage(id = "found-message", conversationId = conversation.id, role = "user", content = "match"))
        }
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()
        viewModel.openScreen(AppScreen.MESSAGE_SEARCH)
        viewModel.openSearchResult(MessageSearchHit("found-message", "found", "Archived", 100, "user", 1, "match"))
        advanceUntilIdle()
        assertEquals(AppScreen.CHAT, viewModel.state.value.screen)
        assertEquals("found-message", viewModel.state.value.activeMessages.single().id)
        assertEquals("found-message", viewModel.state.value.scrollToMessageId)
        assertEquals("found-message", viewModel.state.value.highlightMessageId)
    }

    @Test
    fun newestSearchSelectionWinsWhenThePreviousLoadIgnoresCancellation() = runTest(dispatcher) {
        val firstGate = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        val fake = FakeChatDataSource(true).apply {
            for (id in listOf("first", "second")) {
                conversations += Conversation(id = id, model = model.id)
                messageMap[id] = listOf(ChatMessage(id = "$id-message", conversationId = id, role = "user", content = "match"))
            }
        }
        val repository = object : ChatDataSource by fake {
            override suspend fun conversation(id: String): ConversationDetail {
                val detail = fake.conversation(id)
                withContext(NonCancellable) { (if (id == "first") firstGate else secondGate).await() }
                return detail
            }
        }
        val viewModel = AppViewModel(repository)
        advanceUntilIdle()
        viewModel.openScreen(AppScreen.MESSAGE_SEARCH)
        viewModel.openSearchResult(MessageSearchHit("first-message", "first", "First", null, "user", 1, "match"))
        runCurrent()
        viewModel.openSearchResult(MessageSearchHit("second-message", "second", "Second", null, "user", 1, "match"))
        runCurrent()

        firstGate.complete(Unit)
        runCurrent()
        assertEquals(AppScreen.MESSAGE_SEARCH, viewModel.state.value.screen)
        secondGate.complete(Unit)
        advanceUntilIdle()
        assertEquals("second", viewModel.state.value.activeConversationId)
        assertEquals("second-message", viewModel.state.value.scrollToMessageId)
    }

    @Test
    fun searchNavigationReportsReadFailuresWithoutClaimingTheMessageWasDeleted() = runTest(dispatcher) {
        val fake = FakeChatDataSource(true).apply {
            conversations += Conversation(id = "found", model = model.id)
            conversationFailure = IllegalStateException("database unavailable")
        }
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()
        viewModel.openScreen(AppScreen.MESSAGE_SEARCH)
        viewModel.openSearchResult(MessageSearchHit("found-message", "found", "Found", null, "user", 1, "match"))
        advanceUntilIdle()

        assertEquals(AppScreen.MESSAGE_SEARCH, viewModel.state.value.screen)
        assertEquals(UiText.Dynamic("database unavailable"), viewModel.state.value.notice)
    }

    @Test
    fun deletedSearchTargetsRefreshTheResultsAndRemainOnSearch() = runTest(dispatcher) {
        val searches = mutableListOf<String>()
        val fake = FakeChatDataSource(true).apply {
            searchHandler = { query, _ -> searches += query; MessageSearchPage() }
        }
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()
        viewModel.openScreen(AppScreen.MESSAGE_SEARCH)
        viewModel.searchMessages("match")
        advanceUntilIdle()
        viewModel.openSearchResult(MessageSearchHit("deleted-message", "deleted", "Deleted", null, "user", 1, "match"))
        advanceUntilIdle()

        assertEquals(AppScreen.MESSAGE_SEARCH, viewModel.state.value.screen)
        assertEquals(UiText.Resource(R.string.search_message_missing), viewModel.state.value.notice)
        assertEquals(listOf("match", "match"), searches)
    }

    @Test
    fun returningToChatResumesTheShareDialogAfterOpeningModelSettings() = runTest(dispatcher) {
        val viewModel = AppViewModel(FakeChatDataSource(true))
        advanceUntilIdle()
        viewModel.configureSharedModel()
        assertFalse(viewModel.state.value.shareVisible)
        assertEquals(AppScreen.PROVIDERS, viewModel.state.value.screen)

        viewModel.openScreen(AppScreen.CHAT)

        assertTrue(viewModel.state.value.shareVisible)
        assertEquals(AppScreen.CHAT, viewModel.state.value.screen)
    }

    @Test
    fun configuringTheFirstModelResumesTheShareDialogWhenSetupReturnsToChat() = runTest(dispatcher) {
        val viewModel = AppViewModel(FakeChatDataSource(false))
        advanceUntilIdle()
        viewModel.configureSharedModel()
        viewModel.beginNewProvider()
        viewModel.addManualModel("configured-model")
        viewModel.saveProvider()
        advanceUntilIdle()

        assertEquals(AppPhase.READY, viewModel.state.value.phase)
        assertEquals(AppScreen.CHAT, viewModel.state.value.screen)
        assertTrue(viewModel.state.value.shareVisible)
    }

    @Test
    fun aSubmittedNewConversationDraftCannotBeSentTwiceWhileCreationIsPending() = runTest(dispatcher) {
        val creationGate = CompletableDeferred<Unit>()
        val fake = FakeChatDataSource(true).apply { createConversationGate = creationGate }
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()
        viewModel.setComposerText("first question")

        assertTrue(viewModel.send("first question"))
        runCurrent()
        assertEquals("", viewModel.state.value.composerText)
        assertFalse(viewModel.send("first question"))
        creationGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, fake.conversations.size)
        assertEquals(1, fake.sendMessageCalls)
    }

    @Test
    fun delayedConversationCreationDoesNotReplaceANewlyEditedDraft() = runTest(dispatcher) {
        val creationGate = CompletableDeferred<Unit>()
        val fake = FakeChatDataSource(true).apply { createConversationGate = creationGate }
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()
        viewModel.setComposerText("first question")
        val attachment = cameraDraft("first-question")
        viewModel.addAttachments(listOf(attachment))
        assertTrue(viewModel.send("first question"))
        runCurrent()
        viewModel.setComposerText("next question")
        creationGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(null, viewModel.state.value.activeConversationId)
        assertEquals("next question", viewModel.state.value.composerText)
        assertTrue(viewModel.state.value.drafts[NEW_DRAFT_KEY]?.attachments.orEmpty().isEmpty())
        assertEquals("first question", fake.sentRequest?.content)
        assertEquals(listOf(attachment), fake.sentRequest?.attachments)
    }

    @Test
    fun attachmentsSelectedDuringConversationCreationBelongToANewDraft() = runTest(dispatcher) {
        val creationGate = CompletableDeferred<Unit>()
        val fake = FakeChatDataSource(true).apply { createConversationGate = creationGate }
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()
        viewModel.setComposerText("first question")
        val submittedAttachment = cameraDraft("submitted")
        viewModel.addAttachments(listOf(submittedAttachment))
        assertTrue(viewModel.send("first question"))
        runCurrent()
        val nextAttachment = cameraDraft("next-draft")
        viewModel.addAttachments(listOf(nextAttachment))
        creationGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(null, viewModel.state.value.activeConversationId)
        assertEquals("", viewModel.state.value.composerText)
        assertEquals(listOf(nextAttachment), viewModel.state.value.pendingAttachments)
        assertEquals(listOf(nextAttachment), viewModel.state.value.drafts[NEW_DRAFT_KEY]?.attachments)
        assertEquals(listOf(submittedAttachment), fake.sentRequest?.attachments)
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
    fun bootstrapShowsTheLocalWorkspaceBeforeCloudSynchronizationCompletes() = runTest(dispatcher) {
        val synchronizationGate = CompletableDeferred<Unit>()
        val fake = FakeChatDataSource(withModel = true).apply {
            cloudSyncGate = synchronizationGate
        }

        val viewModel = AppViewModel(fake)
        runCurrent()

        assertEquals(AppPhase.READY, viewModel.state.value.phase)
        assertFalse(viewModel.state.value.loading)
        assertEquals(1, fake.cloudSyncCalls)
        assertFalse(synchronizationGate.isCompleted)

        synchronizationGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(AppPhase.READY, viewModel.state.value.phase)
    }

    @Test
    fun resumeBeforeWorkspaceIsReadyDoesNotStartCloudSynchronization() = runTest(dispatcher) {
        val initializationGate = CompletableDeferred<Unit>()
        val synchronizationGate = CompletableDeferred<Unit>()
        val fake = FakeChatDataSource(withModel = true).apply {
            this.initializationGate = initializationGate
            cloudSyncGate = synchronizationGate
        }
        val viewModel = AppViewModel(fake)
        runCurrent()

        viewModel.onAppResumed()
        runCurrent()

        assertEquals(0, fake.cloudSyncCalls)
        assertEquals(AppPhase.LOADING, viewModel.state.value.phase)

        initializationGate.complete(Unit)
        runCurrent()

        assertEquals(AppPhase.READY, viewModel.state.value.phase)
        assertEquals(1, fake.cloudSyncCalls)

        synchronizationGate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun failureBeforeUserMessageRestoresAndConsumesTheComposerDraft() = runTest(dispatcher) {
        val conversation = Conversation(id = "conversation-draft", model = "model-1")
        val fake = FakeChatDataSource(withModel = true).apply {
            conversations += conversation
            sendMessageFailureBeforeUser = IllegalStateException("cloud upload failed")
        }
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()
        viewModel.openConversation(conversation.id)
        advanceUntilIdle()

        viewModel.send("  preserve this draft  ")
        advanceUntilIdle()

        val recovery = requireNotNull(viewModel.state.value.composerDraftRecovery)
        assertEquals(conversation.id, recovery.conversationId)
        assertEquals("  preserve this draft  ", recovery.content)
        assertEquals(fake.sentRequest?.requestId, recovery.requestId)

        viewModel.consumeComposerDraftRecovery(recovery.requestId)

        assertEquals(null, viewModel.state.value.composerDraftRecovery)
    }

    @Test
    fun conversationCreationFailureRestoresTheComposerDraft() = runTest(dispatcher) {
        val fake = FakeChatDataSource(withModel = true).apply {
            createConversationFailure = IllegalStateException("database unavailable")
        }
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()

        viewModel.send("new conversation draft")
        advanceUntilIdle()

        val recovery = requireNotNull(viewModel.state.value.composerDraftRecovery)
        assertEquals(null, recovery.conversationId)
        assertEquals("new conversation draft", recovery.content)
    }

    @Test
    fun failureAfterUserMessageDoesNotRestoreTheComposerDraft() = runTest(dispatcher) {
        val conversation = Conversation(id = "conversation-accepted", model = "model-1")
        val fake = FakeChatDataSource(withModel = true).apply {
            conversations += conversation
            sendMessageFailureAfterUser = IllegalStateException("provider failed")
        }
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()
        viewModel.openConversation(conversation.id)
        advanceUntilIdle()

        viewModel.send("accepted user message")
        advanceUntilIdle()

        assertEquals(null, viewModel.state.value.composerDraftRecovery)
    }

    @Test
    fun rejectedVisionSendPreservesTheDraftThroughSettingsAndOnlyItsOwnerCanConsumeIt() = runTest(dispatcher) {
        val source = Conversation(id = "source", model = "model-1")
        val other = Conversation(id = "other", model = "model-1")
        val fake = FakeChatDataSource(withModel = true, modelVisionStatus = VisionStatus.UNKNOWN).apply {
            conversations += listOf(source, other)
        }
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()
        viewModel.openConversation(source.id)
        advanceUntilIdle()
        val attachment = cameraDraft("vision")
        viewModel.addAttachments(listOf(attachment))
        viewModel.toggleKnowledgeAttachment(42)

        assertFalse(viewModel.send("  keep this question  "))

        val recovery = requireNotNull(viewModel.state.value.composerDraftRecovery)
        assertEquals(AppScreen.GLOBAL_SETTINGS, viewModel.state.value.screen)
        assertEquals(source.id, recovery.conversationId)
        assertEquals("  keep this question  ", recovery.content)
        assertEquals(listOf(attachment), viewModel.state.value.pendingAttachments)
        assertEquals(listOf(42L), viewModel.state.value.pendingKnowledgeChunkIds)
        assertEquals(null, fake.sentRequest)
        viewModel.openScreen(AppScreen.CHAT)
        assertEquals(recovery, viewModel.state.value.composerDraftRecovery)

        viewModel.openConversation(other.id)
        viewModel.consumeComposerDraftRecovery(recovery.requestId)
        assertEquals(recovery, viewModel.state.value.composerDraftRecovery)
        viewModel.openConversation(source.id)
        viewModel.consumeComposerDraftRecovery(recovery.requestId)
        assertEquals(null, viewModel.state.value.composerDraftRecovery)
        advanceUntilIdle()
    }

    @Test
    fun explicitNewConversationClearsARejectedDraft() = runTest(dispatcher) {
        val viewModel = AppViewModel(FakeChatDataSource(withModel = true, modelVisionStatus = VisionStatus.UNKNOWN))
        advanceUntilIdle()
        viewModel.addAttachments(listOf(cameraDraft("new-draft")))
        assertFalse(viewModel.send("abandoned question"))
        assertNotNull(viewModel.state.value.composerDraftRecovery)

        viewModel.newConversation()

        assertEquals(null, viewModel.state.value.composerDraftRecovery)
        advanceUntilIdle()
    }

    @Test
    fun stoppingWaitsForPersistedMessagesAndRejectsNewGenerationsUntilTheRefreshFinishes() = runTest(dispatcher) {
        val cleanupGate = CompletableDeferred<Unit>()
        val readGate = CompletableDeferred<Unit>()
        val conversation = Conversation(id = "stopped", model = "model-1")
        val fake = FakeChatDataSource(withModel = true).apply {
            conversations += conversation
            sendMessageGate = CompletableDeferred()
            cancellationCleanupGate = cleanupGate
        }
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()
        viewModel.openConversation(conversation.id)
        advanceUntilIdle()
        assertTrue(viewModel.send("first request"))
        runCurrent()
        fake.conversationGate = readGate

        viewModel.stopGeneration()
        runCurrent()

        assertTrue(viewModel.state.value.activeGeneration?.active == true)
        assertTrue(viewModel.state.value.activeGeneration?.stopping == true)
        assertFalse(viewModel.send("too early"))
        viewModel.regenerateLatest()
        runCurrent()
        assertEquals(1, fake.sendMessageCalls)
        assertEquals(0, fake.regenerateCalls)

        cleanupGate.complete(Unit)
        runCurrent()
        assertEquals("interrupted", fake.messageMap.getValue(conversation.id).last().status)
        assertTrue(viewModel.state.value.activeGeneration?.active == true)
        assertFalse(viewModel.send("still refreshing"))
        readGate.complete(Unit)
        advanceUntilIdle()

        assertEquals("interrupted", viewModel.state.value.activeMessages.last().status)
        assertFalse(viewModel.state.value.activeGeneration?.active == true)
        assertFalse(viewModel.state.value.activeGeneration?.stopping == true)
        assertEquals(null, viewModel.state.value.notice)
        viewModel.openScreen(AppScreen.NOTES)
        viewModel.openConversation(conversation.id)
        assertEquals("interrupted", viewModel.state.value.activeMessages.last().status)

        fake.conversationGate = null
        fake.cancellationCleanupGate = null
        assertTrue(viewModel.send("next request"))
        runCurrent()
        assertTrue(viewModel.state.value.activeGeneration?.active == true)
        assertEquals(2, fake.sendMessageCalls)
        viewModel.stopGeneration()
        advanceUntilIdle()
        assertFalse(viewModel.state.value.activeGeneration?.active == true)
    }

    @Test
    fun completionKeepsTheConversationBusyUntilItsFinalMessagesAreRead() = runTest(dispatcher) {
        val conversation = Conversation(id = "completed", model = "model-1")
        val fake = FakeChatDataSource(withModel = true).apply { conversations += conversation }
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()
        viewModel.openConversation(conversation.id)
        advanceUntilIdle()
        val readGate = CompletableDeferred<Unit>()
        fake.conversationGate = readGate

        assertTrue(viewModel.send("request"))
        runCurrent()

        assertTrue(viewModel.state.value.activeGeneration?.active == true)
        assertEquals(7L, viewModel.state.value.activeGeneration?.usage?.totalTokens)
        assertFalse(viewModel.send("too early"))
        readGate.complete(Unit)
        advanceUntilIdle()
        assertEquals("completed", viewModel.state.value.activeMessages.last().status)
        assertFalse(viewModel.state.value.activeGeneration?.active == true)
    }

    @Test
    fun finalMessageReadFailureStillReleasesTheGenerationSlot() = runTest(dispatcher) {
        val conversation = Conversation(id = "read-failure", model = "model-1")
        val fake = FakeChatDataSource(withModel = true).apply {
            conversations += conversation
            sendMessageGate = CompletableDeferred()
        }
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()
        viewModel.openConversation(conversation.id)
        advanceUntilIdle()
        assertTrue(viewModel.send("request"))
        runCurrent()
        fake.conversationFailure = IllegalStateException("read failed")

        viewModel.stopGeneration()
        advanceUntilIdle()

        assertFalse(viewModel.state.value.activeGeneration?.active == true)
        assertFalse(viewModel.state.value.activeGeneration?.stopping == true)
        assertEquals(UiText.Dynamic("read failed"), viewModel.state.value.notice)
        fake.conversationFailure = null
        fake.sendMessageGate = null
        assertTrue(viewModel.send("retry"))
        advanceUntilIdle()
        assertEquals(2, fake.sendMessageCalls)
        assertEquals("completed", viewModel.state.value.activeMessages.last().status)
    }

    @Test
    fun stoppingBeforeTheGenerationCoroutineStartsDoesNotLeaveItBusy() = runTest(dispatcher) {
        val conversation = Conversation(id = "not-started", model = "model-1")
        val fake = FakeChatDataSource(withModel = true).apply { conversations += conversation }
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()
        viewModel.openConversation(conversation.id)
        advanceUntilIdle()

        viewModel.regenerateLatest()
        viewModel.stopGeneration()
        advanceUntilIdle()

        assertEquals(0, fake.regenerateCalls)
        assertFalse(viewModel.state.value.activeGeneration?.active == true)
        assertTrue(viewModel.send("next request"))
        advanceUntilIdle()
        assertEquals(1, fake.sendMessageCalls)
    }

    @Test
    fun savingSettingsForAnotherConversationDoesNotReplaceTheVisibleConfiguration() = runTest(dispatcher) {
        val first = Conversation(id = "first", model = "model-1", systemPrompt = "first prompt")
        val second = Conversation(id = "second", model = "model-1", systemPrompt = "second prompt")
        val fake = FakeChatDataSource(withModel = true).apply { conversations += listOf(first, second) }
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()
        viewModel.openConversation(second.id)
        advanceUntilIdle()
        val expected = viewModel.state.value.config
        viewModel.openConversation(first.id)
        advanceUntilIdle()
        val saveGate = CompletableDeferred<Unit>()
        fake.conversationUpdateAfterWrite = { _, _ -> saveGate.await() }

        viewModel.saveSettings(viewModel.state.value.config.copy(systemPrompt = "updated first prompt"))
        runCurrent()
        viewModel.openConversation(second.id)
        saveGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(second.id, viewModel.state.value.activeConversationId)
        assertEquals(expected, viewModel.state.value.config)
        assertEquals("updated first prompt", viewModel.state.value.conversations.first { it.id == first.id }.systemPrompt)
    }

    @Test
    fun savingSettingsCannotReinsertAConversationDeletedWhileWaitingForTheResult() = runTest(dispatcher) {
        val conversation = Conversation(id = "deleted-during-save", model = "model-1")
        val fake = FakeChatDataSource(withModel = true).apply { conversations += conversation }
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()
        viewModel.openConversation(conversation.id)
        advanceUntilIdle()
        val saveGate = CompletableDeferred<Unit>()
        fake.conversationUpdateAfterWrite = { _, _ -> saveGate.await() }

        viewModel.saveSettings(viewModel.state.value.config.copy(systemPrompt = "updated"))
        runCurrent()
        viewModel.deleteConversations(setOf(conversation.id))
        runCurrent()
        saveGate.complete(Unit)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.conversations.none { it.id == conversation.id })
        assertEquals(null, viewModel.state.value.activeConversationId)
    }

    @Test
    fun noteTitleResultReloadsTheLatestNoteInsteadOfReplacingItWithTheReturnedSnapshot() = runTest(dispatcher) {
        val note = Note(id = "note", title = "initial", body = "initial body")
        val fake = FakeChatDataSource(withModel = true).apply { notes += note }
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()
        val titleGate = CompletableDeferred<Unit>()
        fake.noteTitleHandler = { titleGate.await(); note.copy(title = "generated") }

        viewModel.summarizeNoteTitle(note.id)
        runCurrent()
        val edited = note.copy(title = "manually edited", body = "new body")
        fake.notes[0] = edited
        titleGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(edited, viewModel.state.value.notes.single())
    }

    @Test
    fun automaticNoteTitleResultDoesNotReinsertADeletedNote() = runTest(dispatcher) {
        val message = ChatMessage(id = "saved-answer", conversationId = "conversation", role = "assistant", content = "answer")
        val titleGate = CompletableDeferred<Unit>()
        val fake = FakeChatDataSource(withModel = true).apply {
            messageMap[message.conversationId] = listOf(message)
            noteTitleHandler = { id ->
                val old = notes.first { it.id == id }
                titleGate.await()
                old.copy(title = "generated")
            }
        }
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()

        viewModel.saveMessageAsNote(message)
        runCurrent()
        val noteId = viewModel.state.value.notes.single().id
        viewModel.deleteNote(noteId)
        runCurrent()
        titleGate.complete(Unit)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.notes.isEmpty())
    }

    @Test
    fun cloudUploadStreamsTheInputAndViewModelAlwaysClosesIt() = runTest(dispatcher) {
        val fake = FakeChatDataSource(withModel = true).apply {
            cloudServers += cloudServer("server-a", "/workspace")
        }
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()
        val bytes = ByteArray(32 * 1024) { index -> (index % 251).toByte() }
        val input = CloseTrackingInputStream(bytes)

        viewModel.uploadCloudFile("payload.bin", input)
        advanceUntilIdle()

        assertTrue(input.closed)
        val upload = fake.cloudUploads.single()
        assertEquals("server-a", upload.first)
        assertEquals("/workspace/payload.bin", upload.second)
        assertArrayEquals(bytes, upload.third)
    }

    @Test
    fun rejectedCloudUploadStillClosesTheTransferredInput() = runTest(dispatcher) {
        val fake = FakeChatDataSource(withModel = true).apply {
            cloudServers += cloudServer("server-a", "/workspace")
        }
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()
        val input = CloseTrackingInputStream(byteArrayOf(1, 2, 3))

        viewModel.uploadCloudFile(
            fileName = "ignored.bin",
            input = input,
            expectedServerId = "different-server",
        )
        advanceUntilIdle()

        assertTrue(input.closed)
        assertTrue(fake.cloudUploads.isEmpty())
    }

    @Test
    fun cancellingCloudUploadClosesTheTransferredInput() = runTest(dispatcher) {
        val uploadStarted = CompletableDeferred<Unit>()
        val neverComplete = CompletableDeferred<Unit>()
        val fake = FakeChatDataSource(withModel = true).apply {
            cloudServers += cloudServer("server-a", "/workspace")
            cloudUploadHandler = { _, _, _ ->
                uploadStarted.complete(Unit)
                neverComplete.await()
            }
        }
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()
        val input = CloseTrackingInputStream(byteArrayOf(1, 2, 3))

        val upload = viewModel.uploadCloudFile("payload.bin", input)
        runCurrent()
        assertTrue(uploadStarted.isCompleted)

        upload.cancel()
        advanceUntilIdle()

        assertTrue(input.closed)
        assertEquals(null, viewModel.state.value.notice)
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
    fun cameraDraftsAreDiscardedWhenRemovedOrRejectedAndPreservedWhenSwitching() = runTest(dispatcher) {
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
        assertFalse(fake.discardedAttachments.containsAll(accepted))
        assertEquals(accepted, viewModel.state.value.pendingAttachments)
        accepted.forEach { viewModel.removeAttachment(it.uri) }
        advanceUntilIdle()

        val switched = cameraDraft("switched")
        viewModel.addAttachments(listOf(switched))
        viewModel.openConversation("conversation-existing")
        advanceUntilIdle()
        assertFalse(switched in fake.discardedAttachments)
        assertTrue(viewModel.state.value.pendingAttachments.isEmpty())
        viewModel.newConversation()
        advanceUntilIdle()
        assertEquals(listOf(switched), viewModel.state.value.pendingAttachments)
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
    fun markdownImportUpsertsNewestNoteAndReportsSuccess() = runTest(dispatcher) {
        val existing = Note(id = "existing-note", title = "Existing", body = "Old", updatedAt = 1L)
        val fake = FakeChatDataSource(withModel = true).apply { notes += existing }
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()

        viewModel.importMarkdownNote(ImportedMarkdownNote(title = "Imported", body = "# Body\r\n"))

        assertTrue(viewModel.state.value.noteFileImporting)
        advanceUntilIdle()

        assertFalse(viewModel.state.value.noteFileImporting)
        assertEquals(listOf("Imported", "Existing"), viewModel.state.value.notes.map(Note::title))
        val imported = viewModel.state.value.notes.first()
        assertEquals("# Body\r\n", imported.body)
        assertEquals(null, imported.sourceMessageId)
        assertEquals(null, imported.sourceConversationId)
        assertEquals(R.string.note_markdown_imported, (viewModel.state.value.notice as UiText.Resource).id)
    }

    @Test
    fun failedMarkdownImportDoesNotInsertNoteAndClearsBusyState() = runTest(dispatcher) {
        val existing = Note(id = "existing-note", title = "Existing", body = "Old")
        val fake = FakeChatDataSource(withModel = true).apply {
            notes += existing
            noteSaveFailure = IllegalStateException("Save failed")
        }
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()

        viewModel.importMarkdownNote(ImportedMarkdownNote(title = "Imported", body = "Body"))
        advanceUntilIdle()

        assertFalse(viewModel.state.value.noteFileImporting)
        assertEquals(listOf(existing), viewModel.state.value.notes)
        assertEquals(
            R.string.note_markdown_import_failed,
            (viewModel.state.value.notice as UiText.Resource).id,
        )
    }

    @Test
    fun concurrentMarkdownImportIsIgnoredWhileSequentialDuplicatesUseNewIds() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val fake = FakeChatDataSource(withModel = true).apply { noteSaveGate = gate }
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()
        val imported = ImportedMarkdownNote(title = "Repeated", body = "Same body")

        viewModel.importMarkdownNote(imported)
        viewModel.importMarkdownNote(ImportedMarkdownNote(title = "Ignored", body = "Ignored"))
        runCurrent()

        assertEquals(1, fake.noteSaveCalls)
        gate.complete(Unit)
        advanceUntilIdle()
        fake.noteSaveGate = null

        viewModel.importMarkdownNote(imported)
        advanceUntilIdle()

        assertEquals(2, fake.noteSaveCalls)
        assertEquals(listOf("Repeated", "Repeated"), viewModel.state.value.notes.map(Note::title))
        assertEquals(2, viewModel.state.value.notes.map(Note::id).distinct().size)
    }

    @Test
    fun markdownFileErrorsAndExportSuccessUseLocalizedNotices() = runTest(dispatcher) {
        val viewModel = AppViewModel(FakeChatDataSource(withModel = true))
        advanceUntilIdle()
        val mappings = listOf(
            NoteMarkdownFileError.UNSUPPORTED_EXTENSION to R.string.note_markdown_invalid_extension,
            NoteMarkdownFileError.TOO_LARGE to R.string.note_markdown_too_large,
            NoteMarkdownFileError.EMPTY to R.string.note_markdown_empty,
            NoteMarkdownFileError.INVALID_UTF8 to R.string.note_markdown_invalid_utf8,
            NoteMarkdownFileError.READ_FAILED to R.string.note_markdown_read_failed,
            NoteMarkdownFileError.WRITE_FAILED to R.string.note_markdown_write_failed,
        )

        mappings.forEach { (error, expectedResource) ->
            viewModel.reportNoteMarkdownFileError(NoteMarkdownFileException(error))
            assertEquals(expectedResource, (viewModel.state.value.notice as UiText.Resource).id)
        }

        viewModel.reportNoteMarkdownFileError(IllegalStateException("Unknown"))
        assertEquals(R.string.file_operation_failed, (viewModel.state.value.notice as UiText.Resource).id)

        viewModel.reportNoteMarkdownExported()
        assertEquals(R.string.note_markdown_exported, (viewModel.state.value.notice as UiText.Resource).id)
    }

    @Test
    fun markdownUriIoRunsInViewModelAndExportsPreparedSnapshot() = runTest(dispatcher) {
        val files = FakeNoteMarkdownFileAccess()
        val fake = FakeChatDataSource(withModel = true)
        val viewModel = AppViewModel(fake, files)
        advanceUntilIdle()
        files.imported = ImportedMarkdownNote("From URI", "Original body")

        viewModel.importMarkdownNote("content://notes/source.md")
        advanceUntilIdle()

        val imported = viewModel.state.value.notes.single()
        assertEquals("From URI", imported.title)
        viewModel.prepareMarkdownNoteExport(imported)
        files.writeGate = CompletableDeferred()
        viewModel.exportMarkdownNote("content://notes/export.md")
        runCurrent()

        assertTrue(viewModel.state.value.noteFileExporting)
        assertEquals("content://notes/export.md", files.writtenUri)
        assertEquals("Original body", files.writtenBody)

        files.writeGate?.complete(Unit)
        advanceUntilIdle()

        assertFalse(viewModel.state.value.noteFileExporting)
        assertEquals(R.string.note_markdown_exported, (viewModel.state.value.notice as UiText.Resource).id)
    }

    @Test
    fun markdownImportRefreshSupersedesAnOlderWorkspaceSnapshot() = runTest(dispatcher) {
        val fake = FakeChatDataSource(withModel = true)
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        fake.workspaceGate = gate

        viewModel.retryLoad()
        runCurrent()
        viewModel.importMarkdownNote(ImportedMarkdownNote("Fresh", "Fresh body"))
        runCurrent()

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("Fresh"), viewModel.state.value.notes.map(Note::title))
        assertFalse(viewModel.state.value.loading)
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
    fun readyKnowledgePreviewCanCloseAndLeavingKnowledgeCancelsIt() = runTest(dispatcher) {
        val document = knowledgeDocument("preview-ready", "note-preview", "ready")
        val preview = knowledgePreview(document, "Ready preview body")
        val pending = CompletableDeferred<KnowledgeDocumentPreview?>()
        val fake = FakeChatDataSource(withModel = true).apply {
            knowledgeDocuments += document
            knowledgePreviewHandler = { pending.await() }
        }
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()
        viewModel.openScreen(AppScreen.KNOWLEDGE)

        viewModel.openKnowledgePreview(document.id)
        assertEquals(KnowledgePreviewState.Loading(document), viewModel.state.value.knowledgePreview)
        pending.complete(preview)
        advanceUntilIdle()
        assertEquals(KnowledgePreviewState.Ready(preview), viewModel.state.value.knowledgePreview)

        viewModel.closeKnowledgePreview()
        assertEquals(KnowledgePreviewState.Closed, viewModel.state.value.knowledgePreview)

        fake.knowledgePreviewHandler = { preview }
        viewModel.openKnowledgePreview(document.id)
        advanceUntilIdle()
        assertEquals(KnowledgePreviewState.Ready(preview), viewModel.state.value.knowledgePreview)
        viewModel.openScreen(AppScreen.NOTES)
        assertEquals(KnowledgePreviewState.Closed, viewModel.state.value.knowledgePreview)
    }

    @Test
    fun missingAndFailedKnowledgePreviewsShowRetryableLocalizedError() = runTest(dispatcher) {
        val document = knowledgeDocument("preview-error", "note-error", "ready")
        val fake = FakeChatDataSource(withModel = true).apply {
            knowledgeDocuments += document
            knowledgePreviewHandler = { null }
        }
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()
        viewModel.openScreen(AppScreen.KNOWLEDGE)

        viewModel.openKnowledgePreview(document.id)
        advanceUntilIdle()
        assertEquals(
            KnowledgePreviewState.Error(
                document,
                UiText.Resource(xyz.mek030399.tokenflow.R.string.knowledge_preview_unavailable),
            ),
            viewModel.state.value.knowledgePreview,
        )

        fake.knowledgePreviewHandler = { throw IllegalStateException("Unreadable preview") }
        viewModel.retryKnowledgePreview()
        advanceUntilIdle()
        assertEquals(
            KnowledgePreviewState.Error(
                document,
                UiText.Resource(xyz.mek030399.tokenflow.R.string.knowledge_preview_unavailable),
            ),
            viewModel.state.value.knowledgePreview,
        )

        val preview = knowledgePreview(document, "Recovered preview")
        fake.knowledgePreviewHandler = { preview }
        viewModel.retryKnowledgePreview()
        assertEquals(KnowledgePreviewState.Loading(document), viewModel.state.value.knowledgePreview)
        advanceUntilIdle()
        assertEquals(KnowledgePreviewState.Ready(preview), viewModel.state.value.knowledgePreview)
        assertEquals(listOf(document.id, document.id, document.id), fake.knowledgePreviewCalls)
    }

    @Test
    fun closedKnowledgePreviewCannotBeRestoredByStaleLoad() = runTest(dispatcher) {
        val document = knowledgeDocument("preview-close-race", "note-close-race", "ready")
        val preview = knowledgePreview(document, "Late preview")
        val pending = CompletableDeferred<KnowledgeDocumentPreview?>()
        val fake = FakeChatDataSource(withModel = true).apply {
            knowledgeDocuments += document
            knowledgePreviewHandler = { withContext(NonCancellable) { pending.await() } }
        }
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()
        viewModel.openScreen(AppScreen.KNOWLEDGE)

        viewModel.openKnowledgePreview(document.id)
        runCurrent()
        assertEquals(KnowledgePreviewState.Loading(document), viewModel.state.value.knowledgePreview)
        viewModel.closeKnowledgePreview()
        pending.complete(preview)
        advanceUntilIdle()

        assertEquals(KnowledgePreviewState.Closed, viewModel.state.value.knowledgePreview)
    }

    @Test
    fun newerKnowledgePreviewWinsWhenLoadsFinishOutOfOrder() = runTest(dispatcher) {
        val documentA = knowledgeDocument("preview-a", "note-a", "ready")
        val documentB = knowledgeDocument("preview-b", "note-b", "ready")
        val previewA = knowledgePreview(documentA, "Preview A")
        val previewB = knowledgePreview(documentB, "Preview B")
        val pendingA = CompletableDeferred<KnowledgeDocumentPreview?>()
        val pendingB = CompletableDeferred<KnowledgeDocumentPreview?>()
        val fake = FakeChatDataSource(withModel = true).apply {
            knowledgeDocuments += listOf(documentA, documentB)
            knowledgePreviewHandler = { documentId ->
                withContext(NonCancellable) {
                    if (documentId == documentA.id) pendingA.await() else pendingB.await()
                }
            }
        }
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()
        viewModel.openScreen(AppScreen.KNOWLEDGE)

        viewModel.openKnowledgePreview(documentA.id)
        runCurrent()
        viewModel.openKnowledgePreview(documentB.id)
        runCurrent()
        assertEquals(KnowledgePreviewState.Loading(documentB), viewModel.state.value.knowledgePreview)

        pendingB.complete(previewB)
        runCurrent()
        assertEquals(KnowledgePreviewState.Ready(previewB), viewModel.state.value.knowledgePreview)
        pendingA.complete(previewA)
        advanceUntilIdle()

        assertEquals(KnowledgePreviewState.Ready(previewB), viewModel.state.value.knowledgePreview)
    }

    @Test
    fun nonReadyKnowledgeDocumentsCannotOpenPreview() = runTest(dispatcher) {
        val fake = FakeChatDataSource(withModel = true).apply {
            knowledgeDocuments += knowledgeDocument("preview-indexing", "note-indexing", "indexing")
            knowledgeDocuments += knowledgeDocument("preview-failed", "note-failed", "error")
        }
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()
        viewModel.openScreen(AppScreen.KNOWLEDGE)

        viewModel.openKnowledgePreview("preview-indexing")
        viewModel.openKnowledgePreview("preview-failed")
        viewModel.openKnowledgePreview("preview-missing")
        advanceUntilIdle()

        assertEquals(KnowledgePreviewState.Closed, viewModel.state.value.knowledgePreview)
        assertTrue(fake.knowledgePreviewCalls.isEmpty())
    }

    @Test
    fun directScreenChangeClosesPreviewAndInvalidatesItsPendingLoad() = runTest(dispatcher) {
        val document = knowledgeDocument("preview-navigation-race", "note-navigation-race", "ready")
        val preview = knowledgePreview(document, "Late navigation preview")
        val pending = CompletableDeferred<KnowledgeDocumentPreview?>()
        val fake = FakeChatDataSource(withModel = true).apply {
            knowledgeDocuments += document
            knowledgePreviewHandler = { withContext(NonCancellable) { pending.await() } }
        }
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()
        viewModel.openScreen(AppScreen.KNOWLEDGE)
        viewModel.openKnowledgePreview(document.id)
        runCurrent()
        assertEquals(KnowledgePreviewState.Loading(document), viewModel.state.value.knowledgePreview)

        viewModel.newConversation()
        runCurrent()
        assertEquals(AppScreen.CHAT, viewModel.state.value.screen)
        assertEquals(KnowledgePreviewState.Closed, viewModel.state.value.knowledgePreview)

        pending.complete(preview)
        advanceUntilIdle()
        assertEquals(KnowledgePreviewState.Closed, viewModel.state.value.knowledgePreview)
    }

    @Test
    fun cloudFilesLoadOnlyInFilesAndLateServerResponseCannotOverwriteSelection() = runTest(dispatcher) {
        val serverA = cloudServer("server-a", "/a")
        val serverB = cloudServer("server-b", "/b")
        val pendingA = CompletableDeferred<Pair<String, List<CloudFileEntry>>>()
        val pendingB = CompletableDeferred<Pair<String, List<CloudFileEntry>>>()
        val fake = FakeChatDataSource(withModel = true).apply {
            cloudServers += listOf(serverA, serverB)
            cloudFilesHandler = { serverId, _ ->
                withContext(NonCancellable) {
                    if (serverId == serverA.id) pendingA.await() else pendingB.await()
                }
            }
        }
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()

        viewModel.selectCloudServer(serverB.id)
        runCurrent()
        assertTrue(fake.cloudFileCalls.isEmpty())

        viewModel.openScreen(AppScreen.INFINITE_CLOUD)
        viewModel.selectCloudServer(serverA.id)
        viewModel.selectCloudSection(CloudSection.FILES)
        runCurrent()
        assertEquals(listOf(serverA.id), fake.cloudFileCalls.map { it.first })

        viewModel.selectCloudServer(serverB.id)
        runCurrent()
        pendingB.complete("/b/resolved" to listOf(cloudFile("b.txt", "/b/resolved/b.txt")))
        runCurrent()
        pendingA.complete("/a/resolved" to listOf(cloudFile("a.txt", "/a/resolved/a.txt")))
        advanceUntilIdle()

        assertEquals(serverB.id, viewModel.state.value.cloud.selectedServerId)
        assertEquals("/b/resolved", viewModel.state.value.cloud.currentPath)
        assertEquals(listOf("b.txt"), viewModel.state.value.cloud.files.map { it.name })
    }

    @Test
    fun changedServerConnectionResetsFilesWhenSelectedDuringSave() = runTest(dispatcher) {
        val serverA = cloudServer("server-save-a", "/old-root")
        val serverB = cloudServer("server-save-b", "/b")
        val savedA = serverA.copy(
            host = "new-server-save-a.example.com",
            startDirectory = "/new-root",
            updatedAt = serverA.updatedAt + 1,
        )
        val saveStarted = CompletableDeferred<Unit>()
        val releaseSave = CompletableDeferred<Unit>()
        var fileLoadCount = 0
        val fake = FakeChatDataSource(withModel = true).apply {
            cloudServers += listOf(serverA, serverB)
            cloudServerSaveHandler = {
                saveStarted.complete(Unit)
                withContext(NonCancellable) { releaseSave.await() }
                savedA
            }
            cloudFilesHandler = { _, path ->
                fileLoadCount += 1
                path to listOf(cloudFile(if (fileLoadCount == 1) "old.txt" else "new.txt", "$path/file.txt"))
            }
        }
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()
        viewModel.openScreen(AppScreen.INFINITE_CLOUD)
        viewModel.selectCloudServer(serverB.id)

        viewModel.saveCloudServer(CloudServerDraft(savedA))
        runCurrent()
        assertTrue(saveStarted.isCompleted)

        viewModel.selectCloudServer(serverA.id)
        viewModel.selectCloudSection(CloudSection.FILES)
        runCurrent()
        assertEquals(listOf("old.txt"), viewModel.state.value.cloud.files.map { it.name })

        releaseSave.complete(Unit)
        advanceUntilIdle()

        assertEquals(serverA.id, viewModel.state.value.cloud.selectedServerId)
        assertEquals("/new-root", viewModel.state.value.cloud.currentPath)
        assertEquals(listOf("new.txt"), viewModel.state.value.cloud.files.map { it.name })
        assertEquals(2, fileLoadCount)
    }

    @Test
    fun lateCloudTextCannotCrossServersAndSaveUsesTextOwner() = runTest(dispatcher) {
        val serverA = cloudServer("server-a", "/a")
        val serverB = cloudServer("server-b", "/b")
        val pendingTextA = CompletableDeferred<String>()
        val fake = FakeChatDataSource(withModel = true).apply {
            cloudServers += listOf(serverA, serverB)
            cloudFilesHandler = { _, path -> path to emptyList() }
            cloudTextHandler = { serverId, _ ->
                if (serverId == serverA.id) withContext(NonCancellable) { pendingTextA.await() } else "server B"
            }
        }
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()
        viewModel.openScreen(AppScreen.INFINITE_CLOUD)
        viewModel.selectCloudSection(CloudSection.FILES)
        advanceUntilIdle()

        viewModel.readCloudText("/a/file.txt")
        runCurrent()
        viewModel.selectCloudServer(serverB.id)
        advanceUntilIdle()
        pendingTextA.complete("late server A")
        advanceUntilIdle()
        assertEquals(null, viewModel.state.value.cloud.textPath)

        viewModel.readCloudText("/b/file.txt")
        advanceUntilIdle()
        assertEquals(serverB.id, viewModel.state.value.cloud.textServerId)
        viewModel.updateCloudText("updated B")
        viewModel.saveCloudText()
        advanceUntilIdle()

        assertEquals(listOf(Triple(serverB.id, "/b/file.txt", "updated B")), fake.cloudTextWrites)
    }

    @Test
    fun consecutiveCloudTextSavesKeepBusyUntilLatestContentIsWritten() = runTest(dispatcher) {
        val server = cloudServer("text-save-server", "/srv")
        val firstWriteStarted = CompletableDeferred<Unit>()
        val releaseFirstWrite = CompletableDeferred<Unit>()
        val fake = FakeChatDataSource(withModel = true).apply {
            cloudServers += server
            cloudFilesHandler = { _, path -> path to emptyList() }
            cloudTextHandler = { _, _ -> "initial" }
            cloudTextWriteHandler = { _, _, content ->
                if (content == "first edit") {
                    firstWriteStarted.complete(Unit)
                    releaseFirstWrite.await()
                }
            }
        }
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()
        viewModel.openScreen(AppScreen.INFINITE_CLOUD)
        viewModel.selectCloudSection(CloudSection.FILES)
        advanceUntilIdle()
        viewModel.readCloudText("/srv/file.txt")
        advanceUntilIdle()

        viewModel.updateCloudText("first edit")
        viewModel.saveCloudText()
        runCurrent()
        assertTrue(firstWriteStarted.isCompleted)
        assertTrue(viewModel.state.value.cloud.savingText)

        viewModel.updateCloudText("latest edit")
        viewModel.saveCloudText()
        runCurrent()
        assertTrue(viewModel.state.value.cloud.savingText)
        assertEquals(listOf(Triple(server.id, "/srv/file.txt", "first edit")), fake.cloudTextWrites)

        releaseFirstWrite.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            listOf(
                Triple(server.id, "/srv/file.txt", "first edit"),
                Triple(server.id, "/srv/file.txt", "latest edit"),
            ),
            fake.cloudTextWrites,
        )
        assertEquals("latest edit", viewModel.state.value.cloud.textContent)
        assertFalse(viewModel.state.value.cloud.savingText)
    }

    @Test
    fun cloudTaskLogsIgnoreOutOfOrderResultsAndErrorsAfterLeavingTasks() = runTest(dispatcher) {
        val server = cloudServer("task-server", "/srv")
        val taskA = cloudTask("task-a", server)
        val taskB = cloudTask("task-b", server)
        val taskC = cloudTask("task-c", server)
        val pendingA = CompletableDeferred<String>()
        val pendingB = CompletableDeferred<String>()
        val pendingC = CompletableDeferred<Unit>()
        val fake = FakeChatDataSource(withModel = true).apply {
            cloudServers += server
            cloudTasks += listOf(taskA, taskB, taskC)
            cloudTaskLogHandler = { id ->
                when (id) {
                    taskA.id -> withContext(NonCancellable) { pendingA.await() }
                    taskB.id -> withContext(NonCancellable) { pendingB.await() }
                    else -> {
                        withContext(NonCancellable) { pendingC.await() }
                        throw IllegalStateException("late task log failure")
                    }
                }
            }
        }
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()
        viewModel.openScreen(AppScreen.INFINITE_CLOUD)
        viewModel.selectCloudSection(CloudSection.TASKS)
        advanceUntilIdle()

        viewModel.loadCloudTaskLog(taskA.id)
        runCurrent()
        viewModel.loadCloudTaskLog(taskB.id)
        runCurrent()
        pendingB.complete("log B")
        runCurrent()
        assertEquals(taskB.id, viewModel.state.value.cloud.taskLogId)
        assertEquals("log B", viewModel.state.value.cloud.taskLog)

        pendingA.complete("late log A")
        advanceUntilIdle()
        assertEquals(taskB.id, viewModel.state.value.cloud.taskLogId)
        assertEquals("log B", viewModel.state.value.cloud.taskLog)

        viewModel.clearNotice()
        viewModel.loadCloudTaskLog(taskC.id)
        runCurrent()
        viewModel.selectCloudSection(CloudSection.SERVERS)
        pendingC.complete(Unit)
        advanceUntilIdle()

        assertEquals(null, viewModel.state.value.cloud.taskLogId)
        assertEquals("", viewModel.state.value.cloud.taskLog)
        assertEquals(null, viewModel.state.value.notice)
    }

    @Test
    fun cloudMcpTestCannotWriteBackAfterNavigationOrConfigurationChange() = runTest(dispatcher) {
        val serverA = cloudServer("mcp-server-a", "/a")
        val serverB = cloudServer("mcp-server-b", "/b")
        val mcp = cloudMcp("mcp-a", serverA.id, updatedAt = 1)
        val pendingServerSwitch = CompletableDeferred<List<String>>()
        val pendingSectionLeave = CompletableDeferred<List<String>>()
        val pendingConfigurationChange = CompletableDeferred<List<String>>()
        val pendingResults = mutableListOf(
            pendingServerSwitch,
            pendingSectionLeave,
            pendingConfigurationChange,
        )
        val fake = FakeChatDataSource(withModel = true).apply {
            cloudServers += listOf(serverA, serverB)
            cloudMcpServers += mcp
            cloudMcpTestHandler = {
                val pending = pendingResults.removeAt(0)
                withContext(NonCancellable) { pending.await() }
            }
        }
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()
        viewModel.openScreen(AppScreen.INFINITE_CLOUD)
        viewModel.selectCloudSection(CloudSection.MCP)

        viewModel.testCloudMcpServer(mcp)
        runCurrent()
        viewModel.selectCloudServer(serverB.id)
        pendingServerSwitch.complete(listOf("stale-after-switch"))
        advanceUntilIdle()
        assertEquals(null, viewModel.state.value.cloud.mcpTestName)
        assertTrue(viewModel.state.value.cloud.mcpTestTools.isEmpty())

        viewModel.selectCloudServer(serverA.id)
        viewModel.testCloudMcpServer(mcp)
        runCurrent()
        viewModel.selectCloudSection(CloudSection.SERVERS)
        pendingSectionLeave.complete(listOf("stale-after-leave"))
        advanceUntilIdle()
        assertEquals(null, viewModel.state.value.cloud.mcpTestName)
        assertTrue(viewModel.state.value.cloud.mcpTestTools.isEmpty())

        viewModel.selectCloudSection(CloudSection.MCP)
        viewModel.testCloudMcpServer(mcp)
        runCurrent()
        fake.cloudMcpServers[0] = mcp.copy(updatedAt = 2)
        viewModel.retryLoad()
        runCurrent()
        assertEquals(2, viewModel.state.value.cloudMcpServers.single().updatedAt)
        pendingConfigurationChange.complete(listOf("stale-after-edit"))
        advanceUntilIdle()

        assertEquals(null, viewModel.state.value.cloud.testingMcpServerId)
        assertEquals(null, viewModel.state.value.cloud.mcpTestName)
        assertTrue(viewModel.state.value.cloud.mcpTestTools.isEmpty())
    }

    @Test
    fun completedCloudMcpTestIsClearedWhenConfigurationChanges() = runTest(dispatcher) {
        val server = cloudServer("mcp-result-server", "/srv")
        val mcp = cloudMcp("mcp-result", server.id, updatedAt = 1)
        val fake = FakeChatDataSource(withModel = true).apply {
            cloudServers += server
            cloudMcpServers += mcp
            cloudMcpTestHandler = { listOf("first_tool") }
        }
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()
        viewModel.openScreen(AppScreen.INFINITE_CLOUD)
        viewModel.selectCloudSection(CloudSection.MCP)

        viewModel.testCloudMcpServer(mcp)
        advanceUntilIdle()

        assertEquals(mcp.name, viewModel.state.value.cloud.mcpTestName)
        assertEquals(listOf("first_tool"), viewModel.state.value.cloud.mcpTestTools)
        assertEquals(mcp.id, viewModel.state.value.cloud.mcpTestServerId)
        assertEquals(mcp.updatedAt, viewModel.state.value.cloud.mcpTestServerUpdatedAt)

        fake.cloudMcpServers[0] = mcp.copy(updatedAt = 2)
        viewModel.retryLoad()
        advanceUntilIdle()

        assertEquals(null, viewModel.state.value.cloud.mcpTestName)
        assertTrue(viewModel.state.value.cloud.mcpTestTools.isEmpty())
        assertEquals(null, viewModel.state.value.cloud.mcpTestServerId)
        assertEquals(null, viewModel.state.value.cloud.mcpTestServerUpdatedAt)
    }

    @Test
    fun toolDialogSettingsUseOneConversationWriteWithReadyServer() = runTest(dispatcher) {
        val server = cloudServer("ready-server", "/srv")
        val fake = FakeChatDataSource(withModel = true).apply {
            cloudServers += server
            conversations += Conversation(id = "conversation-cloud", model = model.id)
        }
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()
        viewModel.openConversation("conversation-cloud")
        advanceUntilIdle()
        fake.conversationWrites.clear()

        viewModel.saveToolSettings(
            search = false,
            read = true,
            knowledge = true,
            process = true,
            cloud = true,
            serverId = null,
        )
        advanceUntilIdle()

        assertEquals(1, fake.conversationWrites.size)
        val request = fake.conversationWrites.single()
        assertEquals(false, request.enableSearch)
        assertEquals(true, request.enableRead)
        assertEquals(true, request.enableKnowledge)
        assertEquals(true, request.enableInfiniteCloud)
        assertEquals(server.id, request.cloudServerId)
        assertTrue(request.updateCloudServerId)

        fake.conversationWrites.clear()
        viewModel.saveToolSettings(false, true, true, true, false, server.id)
        advanceUntilIdle()
        val disabled = fake.conversationWrites.single()
        assertEquals(false, disabled.enableInfiniteCloud)
        assertEquals(null, disabled.cloudServerId)
        assertTrue(disabled.updateCloudServerId)
    }

    @Test
    fun latestToolSettingsWinWhenAnOlderResponseReturnsLate() = runTest(dispatcher) {
        val server = cloudServer("tool-race-server", "/srv")
        val oldResponseStarted = CompletableDeferred<Unit>()
        val releaseOldResponse = CompletableDeferred<Unit>()
        val fake = FakeChatDataSource(withModel = true).apply {
            cloudServers += server
            conversations += Conversation(id = "conversation-tool-race", model = model.id)
            conversationUpdateAfterWrite = { request, _ ->
                if (request.enableSearch == false) {
                    oldResponseStarted.complete(Unit)
                    withContext(NonCancellable) { releaseOldResponse.await() }
                }
            }
        }
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()
        viewModel.openConversation("conversation-tool-race")
        advanceUntilIdle()

        viewModel.saveToolSettings(false, false, false, false, false, null)
        runCurrent()
        assertTrue(oldResponseStarted.isCompleted)
        viewModel.saveToolSettings(true, true, true, true, true, server.id)
        runCurrent()

        val stored = fake.conversations.single()
        assertTrue(stored.enableSearch)
        assertTrue(stored.enableRead)
        assertTrue(stored.enableKnowledge)
        assertTrue(stored.enableInfiniteCloud)
        assertEquals(server.id, stored.cloudServerId)
        assertEquals(true, fake.conversationWrites.last().enableSearch)

        releaseOldResponse.complete(Unit)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.enableSearch)
        assertTrue(viewModel.state.value.enableRead)
        assertTrue(viewModel.state.value.enableKnowledge)
        assertTrue(viewModel.state.value.showProcess)
        assertTrue(viewModel.state.value.config.enableInfiniteCloud)
        assertEquals(server.id, viewModel.state.value.config.cloudServerId)
        assertTrue(fake.conversations.single().enableInfiniteCloud)
        assertEquals(server.id, fake.conversations.single().cloudServerId)
    }

    @Test
    fun failedToolSettingsRestorePersistedConversation() = runTest(dispatcher) {
        val server = cloudServer("tool-failure-server", "/srv")
        val existing = Conversation(
            id = "conversation-tool-failure",
            model = "model-1",
            enableSearch = true,
            enableRead = true,
            enableKnowledge = false,
            enableInfiniteCloud = true,
            cloudServerId = server.id,
        )
        val fake = FakeChatDataSource(withModel = true).apply {
            cloudServers += server
            conversations += existing
            conversationUpdateBeforeWrite = { _, _ -> throw IllegalStateException("tool settings write failed") }
        }
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()
        viewModel.openConversation(existing.id)
        advanceUntilIdle()

        viewModel.saveToolSettings(false, false, true, true, false, null)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.enableSearch)
        assertTrue(viewModel.state.value.enableRead)
        assertFalse(viewModel.state.value.enableKnowledge)
        assertTrue(viewModel.state.value.config.enableInfiniteCloud)
        assertEquals(server.id, viewModel.state.value.config.cloudServerId)
        assertEquals(existing, fake.conversations.single())
        assertTrue(viewModel.state.value.notice != null)
    }

    @Test
    fun staleToolSettingsFailureCannotRollbackNewerSuccess() = runTest(dispatcher) {
        val server = cloudServer("tool-stale-failure-server", "/srv")
        val oldWriteStarted = CompletableDeferred<Unit>()
        val releaseOldWrite = CompletableDeferred<Unit>()
        val fake = FakeChatDataSource(withModel = true).apply {
            cloudServers += server
            conversations += Conversation(id = "conversation-tool-stale-failure", model = model.id)
            conversationUpdateBeforeWrite = { request, _ ->
                if (request.enableSearch == false) {
                    withContext(NonCancellable) {
                        oldWriteStarted.complete(Unit)
                        releaseOldWrite.await()
                        throw IllegalStateException("late tool settings failure")
                    }
                }
            }
        }
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()
        viewModel.openConversation("conversation-tool-stale-failure")
        advanceUntilIdle()

        viewModel.saveToolSettings(false, false, false, false, false, null)
        runCurrent()
        assertTrue(oldWriteStarted.isCompleted)
        viewModel.saveToolSettings(true, true, true, true, true, server.id)
        runCurrent()

        releaseOldWrite.complete(Unit)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.enableSearch)
        assertTrue(viewModel.state.value.enableRead)
        assertTrue(viewModel.state.value.enableKnowledge)
        assertTrue(viewModel.state.value.config.enableInfiniteCloud)
        assertEquals(server.id, viewModel.state.value.config.cloudServerId)
        assertTrue(fake.conversations.single().enableInfiniteCloud)
        assertEquals(server.id, fake.conversations.single().cloudServerId)
        assertEquals(null, viewModel.state.value.notice)
    }

    @Test
    fun trustingHostImmediatelyProbesReturnedProfile() = runTest(dispatcher) {
        val untrusted = cloudServer("trust-server", "/srv").copy(
            hostKeyAlgorithm = null,
            hostKeyBase64 = null,
            hostKeyFingerprint = null,
        )
        val candidate = cloudProbe("candidate-key", "SHA256:candidate", trusted = false)
        val trusted = untrusted.copy(
            hostKeyAlgorithm = candidate.hostKeyAlgorithm,
            hostKeyBase64 = candidate.hostKeyBase64,
            hostKeyFingerprint = candidate.fingerprint,
        )
        val fake = FakeChatDataSource(withModel = true).apply {
            cloudServers += untrusted
            cloudProbeHandler = { profile ->
                if (profile.hostKeyBase64 == null) candidate else cloudProbe(profile.hostKeyBase64, profile.hostKeyFingerprint!!, trusted = true)
            }
            trustedCloudServer = trusted
        }
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()

        viewModel.probeCloudServer(untrusted.id)
        advanceUntilIdle()
        viewModel.trustPendingCloudHost()
        advanceUntilIdle()

        assertEquals(listOf(null, candidate.hostKeyBase64), fake.probedCloudProfiles.map { it.hostKeyBase64 })
        assertEquals(candidate.hostKeyBase64, viewModel.state.value.cloudServers.single().hostKeyBase64)
        assertEquals(candidate.fingerprint, viewModel.state.value.cloud.serverDiagnostics[untrusted.id]?.probe?.fingerprint)
    }

    @Test
    fun changedHostKeyRequiresOldAndNewFingerprintConfirmation() = runTest(dispatcher) {
        val original = cloudServer("replace-server", "/srv")
        val replacementProbe = cloudProbe("replacement-key", "SHA256:replacement", trusted = false)
        val replacement = original.copy(
            hostKeyBase64 = replacementProbe.hostKeyBase64,
            hostKeyFingerprint = replacementProbe.fingerprint,
        )
        val fake = FakeChatDataSource(withModel = true).apply {
            cloudServers += original
            hostReplacementProbe = replacementProbe
            replacementCloudServer = replacement
            cloudProbeHandler = { profile ->
                cloudProbe(profile.hostKeyBase64!!, profile.hostKeyFingerprint!!, trusted = true)
            }
        }
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()

        viewModel.probeCloudHostReplacement(original.id)
        advanceUntilIdle()
        val pending = requireNotNull(viewModel.state.value.cloud.pendingHostKeyReplacement)
        assertEquals(original.hostKeyFingerprint, pending.oldFingerprint)
        assertEquals(replacementProbe.fingerprint, pending.probe.fingerprint)

        viewModel.replacePendingCloudHostKey()
        advanceUntilIdle()

        assertEquals(listOf(original.hostKeyBase64), fake.replacedExpectedHostKeys)
        assertEquals(replacementProbe.hostKeyBase64, viewModel.state.value.cloudServers.single().hostKeyBase64)
        assertEquals(replacementProbe.fingerprint, viewModel.state.value.cloud.serverDiagnostics[original.id]?.probe?.fingerprint)
    }

    @Test
    fun failedTaskArtifactCanBeRetriedAndWorkspaceIsRefreshed() = runTest(dispatcher) {
        val delivery = CloudArtifactDelivery(
            id = "delivery-1",
            messageId = "assistant-1",
            taskId = "task-1",
            sourceType = CloudArtifactSourceType.REMOTE,
            sourceIdentity = "server:/srv/result.txt",
            displayName = "result.txt",
            status = CloudArtifactDeliveryStatus.FAILED,
            error = "offline",
        )
        val fake = FakeChatDataSource(withModel = true).apply { cloudArtifactDeliveries += delivery }
        val viewModel = AppViewModel(fake)
        advanceUntilIdle()

        viewModel.retryCloudArtifactDelivery(delivery.id)
        advanceUntilIdle()

        assertEquals(listOf(delivery.id), fake.retriedArtifactDeliveries)
        assertEquals(CloudArtifactDeliveryStatus.DELIVERED, viewModel.state.value.cloudArtifactDeliveries.single().status)
        assertTrue(viewModel.state.value.cloud.retryingArtifactDeliveryIds.isEmpty())
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

private class FakeChatDataSource(withModel: Boolean, modelVisionStatus: VisionStatus = VisionStatus.SUPPORTED) : ChatDataSource {
    val provider = ProviderConfig("provider-1", "Provider", "https://api.example.com/v1", ProviderProtocol.OPENAI_RESPONSES, true)
    val model = ModelProfile(
        "model-1",
        provider.id,
        "model-a",
        "Model A",
        4096,
        true,
        visionStatus = modelVisionStatus,
    )
    val conversations = mutableListOf<Conversation>()
    val messageMap = mutableMapOf<String, List<ChatMessage>>()
    val deleted = mutableSetOf<String>()
    val discardedAttachments = mutableListOf<PendingAttachment>()
    val bookmarks = mutableListOf<BookmarkedMessage>()
    val notes = mutableListOf<Note>()
    val knowledgeDocuments = mutableListOf<KnowledgeDocument>()
    val cloudServers = mutableListOf<CloudServerProfile>()
    val cloudMcpServers = mutableListOf<CloudMcpServer>()
    val cloudTasks = mutableListOf<CloudTask>()
    val cloudArtifactDeliveries = mutableListOf<CloudArtifactDelivery>()
    val knowledgeSnippets = mutableListOf<KnowledgeSnippet>()
    val knowledgePreviewCalls = mutableListOf<String>()
    var knowledgePreviewHandler: suspend (String) -> KnowledgeDocumentPreview? = { null }
    var searchHandler: suspend (String, MessageSearchCursor?) -> MessageSearchPage = { _, _ -> MessageSearchPage() }
    override suspend fun searchMessages(query: String, cursor: MessageSearchCursor?) = searchHandler(query, cursor)
    var initialized = false
    var initializationGate: CompletableDeferred<Unit>? = null
    var sentRequest: SendMessageRequest? = null
    var createConversationFailure: Throwable? = null
    var createConversationGate: CompletableDeferred<Unit>? = null
    var sendMessageFailureBeforeUser: Throwable? = null
    var sendMessageFailureAfterUser: Throwable? = null
    var sendMessageGate: CompletableDeferred<Unit>? = null
    var sendMessageContinuationGate: CompletableDeferred<Unit>? = null
    var cancellationCleanupGate: CompletableDeferred<Unit>? = null
    var sendMessageCalls = 0
    var regenerateCalls = 0
    var noteSummaryFailure: Throwable? = null
    var noteSummaryModelId: String? = null
    var noteSummaryPrompt: String? = null
    var noteSaveFailure: Throwable? = null
    var noteSaveGate: CompletableDeferred<Unit>? = null
    var noteSaveCalls = 0
    var noteTitleHandler: suspend (String) -> Note = { id -> notes.first { it.id == id } }
    var noteKnowledgeImportCalls = 0
    var urlTestFailure: Throwable? = null
    var conversationGate: CompletableDeferred<Unit>? = null
    var conversationFailure: Throwable? = null
    var workspaceGate: CompletableDeferred<Unit>? = null
    var nextImportPreview: ImportPreview? = null
    var appliedImport: ImportPreview? = null
    var previewImportFailure: Throwable? = null
    var applyImportFailure: Throwable? = null
    val cloudFileCalls = mutableListOf<Pair<String, String>>()
    var cloudFilesHandler: suspend (String, String) -> Pair<String, List<CloudFileEntry>> = { _, path -> path to emptyList() }
    var cloudServerSaveHandler: suspend (CloudServerDraft) -> CloudServerProfile = { it.profile }
    var cloudTextHandler: suspend (String, String) -> String = { _, _ -> "" }
    var cloudTextWriteHandler: suspend (String, String, String) -> Unit = { _, _, _ -> }
    val cloudTextWrites = mutableListOf<Triple<String, String, String>>()
    var cloudTaskLogHandler: suspend (String) -> String = { "" }
    var cloudSyncGate: CompletableDeferred<Unit>? = null
    var cloudSyncCalls = 0
    val cloudUploads = mutableListOf<Triple<String, String, ByteArray>>()
    var cloudUploadHandler: suspend (String, String, InputStream) -> Unit = { serverId, remotePath, input ->
        cloudUploads += Triple(serverId, remotePath, input.readBytes())
    }
    var cloudMcpTestHandler: suspend (String) -> List<String> = { emptyList() }
    var cloudProbeHandler: suspend (CloudServerProfile) -> CloudConnectionProbe = {
        cloudProbe(it.hostKeyBase64 ?: "key", it.hostKeyFingerprint ?: "SHA256:key", trusted = it.hostKeyBase64 != null)
    }
    val probedCloudProfiles = mutableListOf<CloudServerProfile>()
    var trustedCloudServer: CloudServerProfile? = null
    var hostReplacementProbe: CloudConnectionProbe? = null
    var replacementCloudServer: CloudServerProfile? = null
    val replacedExpectedHostKeys = mutableListOf<String>()
    val conversationWrites = mutableListOf<ConversationWriteRequest>()
    var conversationUpdateBeforeWrite: suspend (ConversationWriteRequest, Conversation) -> Unit = { _, _ -> }
    var conversationUpdateAfterWrite: suspend (ConversationWriteRequest, Conversation) -> Unit = { _, _ -> }
    var savedAgent: AgentProfile? = null
    val retriedArtifactDeliveries = mutableListOf<String>()
    private var models = if (withModel) listOf(model) else emptyList()

    override suspend fun initialize() {
        initialized = true
        initializationGate?.await()
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
            cloudServers = cloudServers.toList(),
            cloudMcpServers = cloudMcpServers.toList(),
            cloudTasks = cloudTasks.toList(),
            cloudArtifactDeliveries = cloudArtifactDeliveries.toList(),
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

    override suspend fun knowledgeDocumentPreview(documentId: String): KnowledgeDocumentPreview? {
        knowledgePreviewCalls += documentId
        return knowledgePreviewHandler(documentId)
    }

    override suspend fun conversation(id: String): ConversationDetail {
        currentCoroutineContext().ensureActive()
        conversationFailure?.let { throw it }
        val conversation = conversations.first { it.id == id }
        val detail = ConversationDetail(conversation, messageMap[id].orEmpty())
        conversationGate?.await()
        return detail
    }

    override suspend fun createConversation(request: ConversationWriteRequest): Conversation {
        createConversationGate?.await()
        createConversationFailure?.let { throw it }
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
        conversationWrites += request
        val index = conversations.indexOfFirst { it.id == id }
        val current = conversations[index]
        conversationUpdateBeforeWrite(request, current)
        val updated = current.copy(
            title = request.title ?: current.title,
            model = request.model ?: current.model,
            systemPrompt = request.systemPrompt ?: current.systemPrompt,
            enableSearch = request.enableSearch ?: current.enableSearch,
            enableRead = request.enableRead ?: current.enableRead,
            enableKnowledge = request.enableKnowledge ?: current.enableKnowledge,
            enableInfiniteCloud = request.enableInfiniteCloud ?: current.enableInfiniteCloud,
            cloudServerId = if (request.updateCloudServerId) request.cloudServerId else current.cloudServerId,
        )
        conversations[index] = updated
        conversationUpdateAfterWrite(request, updated)
        return updated
    }

    override suspend fun saveCloudServer(draft: CloudServerDraft): CloudServerProfile {
        val saved = cloudServerSaveHandler(draft)
        val index = cloudServers.indexOfFirst { it.id == saved.id }
        if (index >= 0) cloudServers[index] = saved else cloudServers += saved
        return saved
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

    override suspend fun saveAgent(agent: AgentProfile): AgentProfile {
        savedAgent = agent
        return agent
    }

    override suspend fun cloudFiles(serverId: String, path: String): Pair<String, List<CloudFileEntry>> {
        cloudFileCalls += serverId to path
        return cloudFilesHandler(serverId, path)
    }

    override suspend fun readCloudText(serverId: String, path: String): String = cloudTextHandler(serverId, path)

    override suspend fun writeCloudText(serverId: String, path: String, content: String) {
        cloudTextWrites += Triple(serverId, path, content)
        cloudTextWriteHandler(serverId, path, content)
    }

    override suspend fun cloudTaskLog(id: String): String = cloudTaskLogHandler(id)

    override suspend fun syncCloudTasks() {
        cloudSyncCalls += 1
        cloudSyncGate?.await()
    }

    override suspend fun uploadCloudFile(serverId: String, remotePath: String, input: InputStream) {
        cloudUploadHandler(serverId, remotePath, input)
    }

    override suspend fun testCloudMcpServer(id: String): List<String> = cloudMcpTestHandler(id)

    override suspend fun probeCloudServer(profile: CloudServerProfile): CloudConnectionProbe {
        probedCloudProfiles += profile
        return cloudProbeHandler(profile)
    }

    override suspend fun trustCloudHostKey(serverId: String, probe: CloudConnectionProbe): CloudServerProfile =
        requireNotNull(trustedCloudServer)

    override suspend fun probeCloudHostReplacement(serverId: String): CloudConnectionProbe =
        requireNotNull(hostReplacementProbe)

    override suspend fun replaceCloudHostKey(
        serverId: String,
        expectedHostKeyBase64: String,
        probe: CloudConnectionProbe,
    ): CloudServerProfile {
        replacedExpectedHostKeys += expectedHostKeyBase64
        return requireNotNull(replacementCloudServer)
    }

    override suspend fun retryCloudArtifactDelivery(id: String): CloudArtifactDelivery {
        retriedArtifactDeliveries += id
        val index = cloudArtifactDeliveries.indexOfFirst { it.id == id }
        val updated = cloudArtifactDeliveries[index].copy(
            status = CloudArtifactDeliveryStatus.DELIVERED,
            error = "",
        )
        cloudArtifactDeliveries[index] = updated
        return updated
    }

    override suspend fun saveNote(note: Note): Note {
        noteSaveCalls += 1
        noteSaveGate?.await()
        noteSaveFailure?.let { throw it }
        notes.removeAll { it.id == note.id }
        notes.add(0, note)
        return note
    }

    override suspend fun saveMessageAsNote(messageId: String): Note {
        val message = messageMap.values.flatten().first { it.id == messageId }
        return saveNote(Note(title = "Saved answer", body = message.content, sourceMessageId = message.id))
    }

    override suspend fun summarizeNoteTitle(noteId: String): Note = noteTitleHandler(noteId)

    override suspend fun deleteNote(id: String) {
        notes.removeAll { it.id == id }
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
        sendMessageCalls += 1
        sentRequest = request
        sendMessageFailureBeforeUser?.let { throw it }
        val user = ChatMessage("user-1", id, requestId = request.requestId, role = "user", content = request.content)
        val initial = ChatMessage("assistant-1", id, requestId = request.requestId, role = "assistant", status = "generating")
        val final = initial.copy(content = "Answer from provider", status = "completed")
        emit(ChatEvent.UserMessage(user))
        sendMessageFailureAfterUser?.let { throw it }
        emit(ChatEvent.AssistantMessage(initial))
        emit(ChatEvent.Process(ProcessEvent(type = "thinking", id = "thinking-1", content = "summary")))
        emit(ChatEvent.Delta("Answer from provider"))
        try {
            sendMessageContinuationGate?.let { gate -> gate.await(); emit(ChatEvent.Delta(" continued")) }
            sendMessageGate?.await()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                cancellationCleanupGate?.await()
                messageMap[id] = listOf(user, final.copy(status = "interrupted"))
            }
            throw cancelled
        }
        messageMap[id] = listOf(user, final)
        emit(ChatEvent.Done(Usage(3, 4), false))
    }

    override fun regenerate(id: String, request: SendMessageRequest): Flow<ChatEvent> = flow { regenerateCalls += 1 }
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

private class CloseTrackingInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
    var closed = false
        private set

    override fun close() {
        closed = true
        super.close()
    }
}

private class FakeNoteMarkdownFileAccess : NoteMarkdownFileAccess {
    var imported = ImportedMarkdownNote("Imported", "Body")
    var writeGate: CompletableDeferred<Unit>? = null
    var writtenUri: String? = null
    var writtenBody: String? = null

    override suspend fun read(uri: String): ImportedMarkdownNote = imported

    override suspend fun write(uri: String, body: String) {
        writtenUri = uri
        writtenBody = body
        writeGate?.await()
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

private fun knowledgePreview(document: KnowledgeDocument, text: String) = KnowledgeDocumentPreview(
    documentId = document.id,
    documentName = document.name,
    extension = document.name.substringAfterLast('.'),
    text = text,
    truncated = false,
)

private fun cloudServer(id: String, startDirectory: String) = CloudServerProfile(
    id = id,
    name = id,
    host = "$id.example.com",
    username = "runner",
    startDirectory = startDirectory,
    hostKeyAlgorithm = "ssh-ed25519",
    hostKeyBase64 = "$id-key",
    hostKeyFingerprint = "SHA256:$id",
    keyConfigured = true,
)

private fun cloudFile(name: String, path: String) = CloudFileEntry(
    name = name,
    path = path,
    directory = false,
    size = 4,
    modifiedAt = 1,
)

private fun cloudTask(id: String, server: CloudServerProfile) = CloudTask(
    id = id,
    cloudServerId = server.id,
    serverName = server.name,
    kind = "shell",
)

private fun cloudMcp(id: String, serverId: String, updatedAt: Long) = CloudMcpServer(
    id = id,
    cloudServerId = serverId,
    name = id,
    command = "mcp-command",
    updatedAt = updatedAt,
)

private fun cloudProbe(key: String, fingerprint: String, trusted: Boolean) = CloudConnectionProbe(
    hostKeyAlgorithm = "ssh-ed25519",
    hostKeyBase64 = key,
    fingerprint = fingerprint,
    trusted = trusted,
    helperVersion = if (trusted) 3 else null,
    pythonVersion = if (trusted) "Python 3.12" else null,
    nodeAvailable = trusted,
)
