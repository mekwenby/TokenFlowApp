package xyz.mek030399.tokenflow.ui

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import xyz.mek030399.tokenflow.R
import xyz.mek030399.tokenflow.data.ApiException
import xyz.mek030399.tokenflow.data.ChatDataSource
import xyz.mek030399.tokenflow.data.ChatEvent
import xyz.mek030399.tokenflow.data.ChatMessage
import xyz.mek030399.tokenflow.data.BookmarkedMessage
import xyz.mek030399.tokenflow.data.Note
import xyz.mek030399.tokenflow.data.AgentProfile
import xyz.mek030399.tokenflow.data.KnowledgeDocument
import xyz.mek030399.tokenflow.data.KnowledgeDocumentPreview
import xyz.mek030399.tokenflow.data.KnowledgeImportSource
import xyz.mek030399.tokenflow.data.KnowledgeSnippet
import xyz.mek030399.tokenflow.data.ConfigurationException
import xyz.mek030399.tokenflow.data.Conversation
import xyz.mek030399.tokenflow.data.ConversationWriteRequest
import xyz.mek030399.tokenflow.data.ImportPreview
import xyz.mek030399.tokenflow.data.ImportedMarkdownNote
import xyz.mek030399.tokenflow.data.GlobalChatSettings
import xyz.mek030399.tokenflow.data.ModelProfile
import xyz.mek030399.tokenflow.data.MAX_MODEL_OUTPUT_TOKENS
import xyz.mek030399.tokenflow.data.NoteChangedDuringSummaryException
import xyz.mek030399.tokenflow.data.NoteMarkdownFileError
import xyz.mek030399.tokenflow.data.NoteMarkdownFileAccess
import xyz.mek030399.tokenflow.data.NoteMarkdownFileException
import xyz.mek030399.tokenflow.data.NoteSummaryTooLongException
import xyz.mek030399.tokenflow.data.ProcessEvent
import xyz.mek030399.tokenflow.data.ProviderConfig
import xyz.mek030399.tokenflow.data.ProviderDraft
import xyz.mek030399.tokenflow.data.RemoteModel
import xyz.mek030399.tokenflow.data.SettingMode
import xyz.mek030399.tokenflow.data.SendMessageRequest
import xyz.mek030399.tokenflow.data.Usage
import xyz.mek030399.tokenflow.data.UrlReaderBackend
import xyz.mek030399.tokenflow.data.MessageAttachment
import xyz.mek030399.tokenflow.data.PendingAttachment
import xyz.mek030399.tokenflow.data.UrlReadDiagnostic
import xyz.mek030399.tokenflow.data.VisionStatus
import java.io.IOException
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AppPhase { LOADING, SETUP, READY }
enum class AppScreen { CHAT, BOOKMARKS, NOTES, AGENTS, KNOWLEDGE, GLOBAL_SETTINGS, PROVIDERS, EXA, TRANSFER, ABOUT }

sealed interface UiText {
    data class Resource(@param:StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Dynamic(val value: String) : UiText
}

private fun uiText(@StringRes id: Int, vararg args: Any): UiText = UiText.Resource(id, args.toList())

data class ConversationConfig(
    val model: String = "",
    val modelMode: SettingMode = SettingMode.INHERIT,
    val thinkingEffort: String = "medium",
    val systemPrompt: String = "",
    val systemPromptMode: SettingMode = SettingMode.INHERIT,
    val nickname: String = "",
    val userAvatar: String = "U",
    val userAvatarMode: SettingMode = SettingMode.INHERIT,
    val assistantAvatar: String = "AI",
    val assistantAvatarMode: SettingMode = SettingMode.INHERIT,
    val urlReaderBackend: UrlReaderBackend = UrlReaderBackend.BUILT_IN,
    val urlReaderMode: SettingMode = SettingMode.INHERIT,
    val maxToolCalls: Int = 7,
    val enableSearch: Boolean = true,
    val enableRead: Boolean = true,
    val enableKnowledge: Boolean = false,
)

data class GenerationState(
    val active: Boolean = true,
    val stopping: Boolean = false,
    val hasDelta: Boolean = false,
    val events: List<ProcessEvent> = emptyList(),
    val usage: Usage = Usage(),
    val error: UiText? = null,
)

data class ProviderEditorState(
    val draft: ProviderDraft = ProviderDraft(),
    val selectedModels: List<ModelProfile> = emptyList(),
    val remoteModels: List<RemoteModel> = emptyList(),
    val modelSearch: String = "",
    val busy: Boolean = false,
    val error: UiText? = null,
)

data class TransferState(
    val busy: Boolean = false,
    val exportContent: String? = null,
    val importPreview: ImportPreview? = null,
    val error: UiText? = null,
)

data class TtsUiState(
    val loading: Boolean = false,
    val filePath: String? = null,
    val error: UiText? = null,
)

data class SpeechAutoPlayTarget(
    val conversationId: String,
    val chatInstanceId: String,
)

data class SpeechAutoPlayRequest(
    val messageId: String,
    val filePath: String,
    val target: SpeechAutoPlayTarget,
)

sealed interface KnowledgePreviewState {
    data object Closed : KnowledgePreviewState
    data class Loading(val document: KnowledgeDocument) : KnowledgePreviewState
    data class Ready(val preview: KnowledgeDocumentPreview) : KnowledgePreviewState
    data class Error(val document: KnowledgeDocument, val message: UiText) : KnowledgePreviewState
}

internal fun shouldAutoPlaySpeech(
    request: SpeechAutoPlayRequest,
    conversationId: String?,
    chatInstanceId: String,
): Boolean = request.target.conversationId == conversationId &&
    request.target.chatInstanceId == chatInstanceId

data class AppUiState(
    val phase: AppPhase = AppPhase.LOADING,
    val screen: AppScreen = AppScreen.CHAT,
    val loading: Boolean = false,
    val providers: List<ProviderConfig> = emptyList(),
    val models: List<ModelProfile> = emptyList(),
    val conversations: List<Conversation> = emptyList(),
    val activeConversationId: String? = null,
    val messages: Map<String, List<ChatMessage>> = emptyMap(),
    val attachments: Map<String, List<MessageAttachment>> = emptyMap(),
    val pendingAttachments: List<PendingAttachment> = emptyList(),
    val config: ConversationConfig = ConversationConfig(),
    val conversationSearch: String = "",
    val enableSearch: Boolean = true,
    val enableRead: Boolean = true,
    val showProcess: Boolean = false,
    val enableKnowledge: Boolean = false,
    val exaConfigured: Boolean = false,
    val exaTestResult: String = "",
    val urlTestResult: UrlReadDiagnostic? = null,
    val urlTestBusy: Boolean = false,
    val globalSettings: GlobalChatSettings = GlobalChatSettings(),
    val generations: Map<String, GenerationState> = emptyMap(),
    val providerEditor: ProviderEditorState? = null,
    val transfer: TransferState = TransferState(),
    val bookmarks: List<BookmarkedMessage> = emptyList(),
    val notes: List<Note> = emptyList(),
    val noteFileImporting: Boolean = false,
    val noteFileExporting: Boolean = false,
    val noteSummarizingId: String? = null,
    val noteImportingId: String? = null,
    val agents: List<AgentProfile> = emptyList(),
    val knowledgeDocuments: List<KnowledgeDocument> = emptyList(),
    val knowledgePreview: KnowledgePreviewState = KnowledgePreviewState.Closed,
    val knowledgeResults: List<KnowledgeSnippet> = emptyList(),
    val pendingKnowledgeChunkIds: List<Long> = emptyList(),
    val knowledgeSourcePreview: KnowledgeSnippet? = null,
    val scrollToMessageId: String? = null,
    val notice: UiText? = null,
    val visionTestingModelId: String? = null,
    val tts: Map<String, TtsUiState> = emptyMap(),
) {
    val activeConversation: Conversation?
        get() = activeConversationId?.let { id -> conversations.firstOrNull { it.id == id } }
    val activeMessages: List<ChatMessage>
        get() = activeConversationId?.let { messages[it] }.orEmpty()
    val activeGeneration: GenerationState?
        get() = activeConversationId?.let { generations[it] }
    val hasModels: Boolean get() = models.isNotEmpty()
}

class AppViewModel internal constructor(
    private val repository: ChatDataSource,
    private val noteMarkdownFiles: NoteMarkdownFileAccess? = null,
) : ViewModel() {
    private val mutableState = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = mutableState.asStateFlow()
    private val mutableSpeechAutoPlay = MutableSharedFlow<SpeechAutoPlayRequest>(extraBufferCapacity = 1)
    val speechAutoPlay: SharedFlow<SpeechAutoPlayRequest> = mutableSpeechAutoPlay.asSharedFlow()
    private val generationJobs = mutableMapOf<String, Job>()
    private var ttsJob: Job? = null
    private var knowledgePreviewJob: Job? = null
    private var knowledgePreviewGeneration = 0L
    private val noteSavingMessageIds = mutableSetOf<String>()
    private var pendingMarkdownExport: Note? = null
    private var workspaceLoadVersion = 0L

    init {
        observeKnowledgePreviewScreen()
        bootstrap()
    }

    fun retryLoad() = loadWorkspace()

    fun openScreen(screen: AppScreen) {
        if (screen == AppScreen.CHAT && mutableState.value.models.isEmpty()) return
        val leavingKnowledge = mutableState.value.screen == AppScreen.KNOWLEDGE && screen != AppScreen.KNOWLEDGE
        if (leavingKnowledge) invalidateKnowledgePreviewRequest()
        mutableState.update {
            it.copy(
                screen = screen,
                providerEditor = null,
                knowledgePreview = if (leavingKnowledge) KnowledgePreviewState.Closed else it.knowledgePreview,
            )
        }
    }

    fun clearNotice() = mutableState.update { it.copy(notice = null) }

    fun reportCameraCaptureFailure() = mutableState.update {
        it.copy(notice = uiText(R.string.camera_capture_failed))
    }

    fun reportNoteMarkdownFileError(error: Throwable) {
        val message = when ((error as? NoteMarkdownFileException)?.reason) {
            NoteMarkdownFileError.UNSUPPORTED_EXTENSION -> R.string.note_markdown_invalid_extension
            NoteMarkdownFileError.TOO_LARGE -> R.string.note_markdown_too_large
            NoteMarkdownFileError.EMPTY -> R.string.note_markdown_empty
            NoteMarkdownFileError.INVALID_UTF8 -> R.string.note_markdown_invalid_utf8
            NoteMarkdownFileError.READ_FAILED -> R.string.note_markdown_read_failed
            NoteMarkdownFileError.WRITE_FAILED -> R.string.note_markdown_write_failed
            null -> R.string.file_operation_failed
        }
        mutableState.update { it.copy(notice = uiText(message)) }
    }

    fun reportNoteMarkdownExported() = mutableState.update {
        it.copy(notice = uiText(R.string.note_markdown_exported))
    }

    fun setConversationSearch(value: String) = mutableState.update { it.copy(conversationSearch = value) }

    fun setTools(search: Boolean, read: Boolean, process: Boolean, knowledge: Boolean = mutableState.value.enableKnowledge) {
        mutableState.update {
            it.copy(
                enableSearch = search,
                enableRead = read,
                enableKnowledge = knowledge,
                showProcess = process,
                config = it.config.copy(enableSearch = search, enableRead = read, enableKnowledge = knowledge),
            )
        }
        val id = mutableState.value.activeConversationId ?: return
        viewModelScope.launch {
            runCatching {
                repository.updateConversation(
                    id,
                    ConversationWriteRequest(enableSearch = search, enableRead = read, enableKnowledge = knowledge),
                )
            }.onSuccess { updated -> mutableState.update { it.copy(conversations = upsertConversation(it.conversations, updated)) } }
                .onFailure(::handleError)
        }
    }

    fun newConversation() {
        discardPendingAttachments(mutableState.value.pendingAttachments)
        mutableState.update {
            val config = defaultConfig(it.models, it.globalSettings)
            it.copy(
                screen = AppScreen.CHAT,
                activeConversationId = null,
                config = config,
                enableSearch = config.enableSearch,
                enableRead = config.enableRead,
                enableKnowledge = config.enableKnowledge,
                pendingAttachments = emptyList(),
            )
        }
    }

    fun openConversation(id: String) {
        val current = mutableState.value
        val switchingConversation = current.activeConversationId != id
        if (switchingConversation) discardPendingAttachments(current.pendingAttachments)
        mutableState.update { state ->
            state.copy(
                screen = AppScreen.CHAT,
                activeConversationId = id,
                config = state.conversations.firstOrNull { it.id == id }?.toConfig() ?: state.config,
                enableSearch = state.conversations.firstOrNull { it.id == id }?.enableSearch ?: true,
                enableRead = state.conversations.firstOrNull { it.id == id }?.enableRead ?: true,
                enableKnowledge = state.conversations.firstOrNull { it.id == id }?.enableKnowledge ?: false,
                scrollToMessageId = null,
                pendingAttachments = if (switchingConversation) emptyList() else state.pendingAttachments,
            )
        }
        if (current.messages.containsKey(id)) return
        viewModelScope.launch { loadConversation(id) }
    }

    fun saveSettings(config: ConversationConfig) {
        val normalized = config.copy(maxToolCalls = config.maxToolCalls.coerceIn(0, 20))
        val id = mutableState.value.activeConversationId
        if (id == null) {
            mutableState.update { it.copy(config = normalized, notice = uiText(R.string.settings_saved)) }
            return
        }
        if (mutableState.value.generations[id]?.active == true) {
            mutableState.update { it.copy(notice = uiText(R.string.wait_for_response)) }
            return
        }
        viewModelScope.launch {
            runCatching { repository.updateConversation(id, normalized.toRequest()) }
                .onSuccess { updated ->
                    mutableState.update {
                        it.copy(
                            conversations = upsertConversation(it.conversations, updated),
                            config = updated.toConfig(),
                            notice = uiText(R.string.settings_saved),
                        )
                    }
                }
                .onFailure(::handleError)
        }
    }

    fun saveGlobalSettings(settings: GlobalChatSettings, mimoTtsKey: String?) {
        viewModelScope.launch {
            runCatching { repository.saveGlobalSettings(settings, mimoTtsKey) }
                .onSuccess { saved ->
                    mutableState.update { state ->
                        state.copy(
                            globalSettings = saved,
                            models = state.models.map { it.copy(isDefault = it.id == saved.defaultModelId) },
                            notice = uiText(R.string.settings_saved),
                        )
                    }
                    loadWorkspace()
                }
                .onFailure(::handleError)
        }
    }

    fun testUrl(url: String) {
        if (url.isBlank()) return
        mutableState.update { it.copy(urlTestBusy = true, urlTestResult = null) }
        viewModelScope.launch {
            try {
                val result = repository.testUrl(url.trim())
                mutableState.update { it.copy(urlTestBusy = false, urlTestResult = result) }
            } catch (cancelled: CancellationException) {
                mutableState.update { it.copy(urlTestBusy = false) }
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(urlTestBusy = false, notice = readableError(error)) }
            }
        }
    }

    fun renameConversation(id: String, title: String) {
        if (title.trim().isEmpty()) return
        viewModelScope.launch {
            runCatching { repository.updateConversation(id, ConversationWriteRequest(title = title.trim())) }
                .onSuccess { updated -> mutableState.update { it.copy(conversations = upsertConversation(it.conversations, updated)) } }
                .onFailure(::handleError)
        }
    }

    fun generateTitle(id: String? = mutableState.value.activeConversationId) {
        id ?: return
        viewModelScope.launch {
            runCatching { repository.generateTitle(id, true) }
                .onSuccess { updated -> mutableState.update { it.copy(conversations = upsertConversation(it.conversations, updated)) } }
                .onFailure(::handleError)
        }
    }

    fun deleteConversations(ids: Set<String>) {
        if (ids.isEmpty()) return
        if (ids.any { mutableState.value.generations[it]?.active == true }) {
            mutableState.update { it.copy(notice = uiText(R.string.wait_for_delete)) }
            return
        }
        viewModelScope.launch {
            runCatching { repository.deleteConversations(ids) }
                .onSuccess {
                    workspaceLoadVersion += 1
                    mutableState.update { state ->
                        val activeDeleted = state.activeConversationId in ids
                        val deletedMessageIds = buildSet {
                            ids.forEach { conversationId ->
                                addAll(state.messages[conversationId].orEmpty().map(ChatMessage::id))
                            }
                            state.bookmarks
                                .filter { it.conversationId in ids }
                                .forEach { add(it.messageId) }
                        }
                        state.copy(
                            conversations = state.conversations.filterNot { it.id in ids },
                            activeConversationId = if (activeDeleted) null else state.activeConversationId,
                            messages = state.messages - ids,
                            bookmarks = state.bookmarks.filterNot { it.conversationId in ids },
                            scrollToMessageId = state.scrollToMessageId?.takeUnless {
                                activeDeleted || it in deletedMessageIds
                            },
                            loading = false,
                            config = if (activeDeleted) defaultConfig(state.models, state.globalSettings) else state.config,
                        )
                    }
                }
                .onFailure(::handleError)
        }
    }

    fun pinConversation(id: String, pinned: Boolean) = refreshAfter {
        repository.setConversationPinned(id, pinned)
    }

    fun archiveConversation(id: String, archived: Boolean) = refreshAfter {
        repository.setConversationArchived(id, archived)
    }

    fun toggleBookmark(messageId: String) = refreshAfter {
        repository.toggleBookmark(messageId)
    }

    fun deleteBookmarks(messageIds: Set<String>) = refreshAfter {
        repository.deleteBookmarks(messageIds)
    }

    fun openBookmark(bookmark: BookmarkedMessage) {
        if (mutableState.value.conversations.none { it.id == bookmark.conversationId }) {
            mutableState.update { state ->
                state.copy(
                    bookmarks = state.bookmarks.filterNot {
                        it.id == bookmark.id || it.messageId == bookmark.messageId
                    },
                    scrollToMessageId = state.scrollToMessageId?.takeUnless { it == bookmark.messageId },
                )
            }
            return
        }
        openConversation(bookmark.conversationId)
        mutableState.update { it.copy(scrollToMessageId = bookmark.messageId) }
    }

    fun consumeScrollTarget() = mutableState.update { it.copy(scrollToMessageId = null) }

    fun saveNote(note: Note) = refreshAfter { repository.saveNote(note) }

    fun importMarkdownNote(uri: String) {
        startMarkdownNoteImport {
            requireNotNull(noteMarkdownFiles) { "Markdown note files are unavailable" }.read(uri)
        }
    }

    internal fun importMarkdownNote(imported: ImportedMarkdownNote) {
        startMarkdownNoteImport { imported }
    }

    private fun startMarkdownNoteImport(load: suspend () -> ImportedMarkdownNote) {
        if (mutableState.value.noteFileImporting) return
        mutableState.update { it.copy(noteFileImporting = true) }
        viewModelScope.launch {
            try {
                val imported = load()
                val note = repository.saveNote(Note(title = imported.title, body = imported.body))
                mutableState.update {
                    it.copy(
                        notes = upsertNote(it.notes, note),
                        notice = uiText(R.string.note_markdown_imported),
                    )
                }
                loadWorkspace()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: NoteMarkdownFileException) {
                reportNoteMarkdownFileError(error)
            } catch (_: Exception) {
                mutableState.update { it.copy(notice = uiText(R.string.note_markdown_import_failed)) }
            } finally {
                mutableState.update { it.copy(noteFileImporting = false) }
            }
        }
    }

    internal fun prepareMarkdownNoteExport(note: Note) {
        pendingMarkdownExport = note
    }

    fun exportMarkdownNote(uri: String?) {
        val note = pendingMarkdownExport
        pendingMarkdownExport = null
        if (uri == null || note == null || mutableState.value.noteFileExporting) return
        val files = noteMarkdownFiles
        if (files == null) {
            mutableState.update { it.copy(notice = uiText(R.string.note_markdown_write_failed)) }
            return
        }
        mutableState.update { it.copy(noteFileExporting = true) }
        viewModelScope.launch {
            try {
                files.write(uri, note.body)
                reportNoteMarkdownExported()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: NoteMarkdownFileException) {
                reportNoteMarkdownFileError(error)
            } catch (_: Exception) {
                mutableState.update { it.copy(notice = uiText(R.string.note_markdown_write_failed)) }
            } finally {
                mutableState.update { it.copy(noteFileExporting = false) }
            }
        }
    }

    fun saveMessageAsNote(message: ChatMessage) {
        if (mutableState.value.notes.any { it.sourceMessageId == message.id } || !noteSavingMessageIds.add(message.id)) return
        viewModelScope.launch {
            try {
                runCatching { repository.saveMessageAsNote(message.id) }
                    .onSuccess { note ->
                        mutableState.update { it.copy(notes = upsertNote(it.notes, note)) }
                        runCatching { repository.summarizeNoteTitle(note.id) }
                            .onSuccess { summarized -> mutableState.update { it.copy(notes = upsertNote(it.notes, summarized)) } }
                    }
                    .onFailure(::handleError)
            } finally {
                noteSavingMessageIds.remove(message.id)
            }
        }
    }

    fun summarizeNoteTitle(noteId: String) {
        viewModelScope.launch {
            runCatching { repository.summarizeNoteTitle(noteId) }
                .onSuccess { note -> mutableState.update { it.copy(notes = upsertNote(it.notes, note)) } }
                .onFailure(::handleError)
        }
    }

    fun summarizeNote(noteId: String, modelId: String, rewritePrompt: String = "") {
        if (mutableState.value.noteSummarizingId != null) return
        mutableState.update { it.copy(noteSummarizingId = noteId) }
        viewModelScope.launch {
            try {
                runCatching { repository.summarizeNote(noteId, modelId, rewritePrompt) }
                    .onSuccess { note -> mutableState.update { it.copy(notes = upsertNote(it.notes, note)) } }
                    .onFailure(::handleError)
            } finally {
                mutableState.update { it.copy(noteSummarizingId = null) }
            }
        }
    }

    fun importNoteToKnowledge(noteId: String) {
        val current = mutableState.value
        if (current.noteImportingId != null || current.knowledgeDocuments.any {
                it.sourceNoteId == noteId && (it.status == "ready" || it.status == "indexing")
            }
        ) return
        mutableState.update { it.copy(noteImportingId = noteId) }
        viewModelScope.launch {
            try {
                runCatching { repository.importNoteToKnowledge(noteId) }
                    .onSuccess { document -> mutableState.update { state ->
                        state.copy(
                            knowledgeDocuments = listOf(document) + state.knowledgeDocuments.filterNot {
                                it.id == document.id ||
                                    (document.sourceNoteId != null && it.sourceNoteId == document.sourceNoteId)
                            },
                        )
                    } }
                    .onFailure(::handleError)
            } finally {
                mutableState.update { it.copy(noteImportingId = null) }
            }
        }
    }

    fun clearContext() {
        val current = mutableState.value
        val id = current.activeConversationId ?: return
        if (current.generations[id]?.active == true) {
            mutableState.update { it.copy(notice = uiText(R.string.wait_for_response)) }
            return
        }
        viewModelScope.launch {
            runCatching { repository.clearContext(id) }
                .onSuccess { boundary -> mutableState.update { state ->
                    val messages = state.messages[id].orEmpty()
                    state.copy(messages = state.messages + (id to (messages.filterNot { it.id == boundary.id } + boundary)))
                } }
                .onFailure(::handleError)
        }
    }

    fun createBranch(messageId: String, title: String) {
        viewModelScope.launch {
            runCatching { repository.createBranch(messageId, title) }
                .onSuccess { branch ->
                    loadWorkspace()
                    mutableState.update {
                        it.copy(
                            screen = AppScreen.CHAT,
                            conversations = upsertConversation(it.conversations, branch),
                            activeConversationId = branch.id,
                            config = branch.toConfig(),
                            messages = it.messages + (branch.id to emptyList()),
                        )
                    }
                    loadConversation(branch.id)
                }
                .onFailure(::handleError)
        }
    }

    fun deleteNote(id: String) = refreshAfter { repository.deleteNote(id) }

    fun deleteNotes(ids: Set<String>) = refreshAfter { repository.deleteNotes(ids) }

    fun saveAgent(agent: AgentProfile) = refreshAfter { repository.saveAgent(agent) }

    fun deleteAgent(id: String) = refreshAfter { repository.deleteAgent(id) }

    fun startAgent(id: String) {
        viewModelScope.launch {
            runCatching { repository.createConversationFromAgent(id) }
                .onSuccess { created ->
                    loadWorkspace()
                    mutableState.update {
                        it.copy(
                            screen = AppScreen.CHAT,
                            activeConversationId = created.id,
                            config = created.toConfig(),
                            enableSearch = created.enableSearch,
                            enableRead = created.enableRead,
                            enableKnowledge = created.enableKnowledge,
                            messages = it.messages + (created.id to emptyList()),
                        )
                    }
                }.onFailure(::handleError)
        }
    }

    fun importKnowledge(source: KnowledgeImportSource) = refreshAfter { repository.importKnowledge(source) }

    fun deleteKnowledge(id: String) = refreshAfter { repository.deleteKnowledge(id) }

    fun searchKnowledge(query: String) {
        viewModelScope.launch {
            runCatching { repository.searchKnowledge(query) }
                .onSuccess { results -> mutableState.update { it.copy(knowledgeResults = results) } }
                .onFailure(::handleError)
        }
    }

    fun toggleKnowledgeAttachment(chunkId: Long) = mutableState.update { state ->
        val ids = state.pendingKnowledgeChunkIds
        state.copy(pendingKnowledgeChunkIds = if (chunkId in ids) ids - chunkId else ids + chunkId)
    }

    fun openKnowledgeCitation(chunkId: Long) {
        viewModelScope.launch {
            runCatching { repository.knowledgeSnippet(chunkId) }
                .onSuccess { snippet ->
                    mutableState.update { state ->
                        if (snippet == null) state.copy(notice = uiText(R.string.knowledge_source_unavailable))
                        else state.copy(knowledgeSourcePreview = snippet)
                    }
                }
                .onFailure(::handleError)
        }
    }

    fun closeKnowledgeSourcePreview() = mutableState.update { it.copy(knowledgeSourcePreview = null) }

    fun openKnowledgePreview(documentId: String) {
        val current = mutableState.value
        if (current.screen != AppScreen.KNOWLEDGE) return
        val document = current.knowledgeDocuments.firstOrNull {
            it.id == documentId && it.status == "ready"
        } ?: return
        loadKnowledgePreview(document)
    }

    fun retryKnowledgePreview() {
        val failed = mutableState.value.knowledgePreview as? KnowledgePreviewState.Error ?: return
        val document = mutableState.value.knowledgeDocuments.firstOrNull {
            it.id == failed.document.id && it.status == "ready"
        } ?: return
        loadKnowledgePreview(document)
    }

    fun closeKnowledgePreview() {
        invalidateKnowledgePreviewRequest()
        mutableState.update { it.copy(knowledgePreview = KnowledgePreviewState.Closed) }
    }

    fun addAttachments(items: List<PendingAttachment>) {
        val combined = (mutableState.value.pendingAttachments + items).distinctBy { it.uri }
        val accepted = combined.take(5)
        discardPendingAttachments(combined.drop(5))
        mutableState.update { state ->
            state.copy(
                pendingAttachments = accepted,
                notice = if (combined.size > 5) UiText.Dynamic("A message can contain at most 5 attachments") else state.notice,
            )
        }
    }

    fun removeAttachment(uri: String) {
        val removed = mutableState.value.pendingAttachments.filter { it.uri == uri }
        mutableState.update {
            it.copy(pendingAttachments = it.pendingAttachments.filterNot { item -> item.uri == uri })
        }
        discardPendingAttachments(removed)
    }

    fun send(content: String) {
        val message = content.trim()
        val current = mutableState.value
        if ((message.isEmpty() && current.pendingAttachments.isEmpty()) || current.models.isEmpty()) return
        val hasImages = current.pendingAttachments.any { item ->
            item.mimeType.startsWith("image/", true) || item.displayName.substringAfterLast('.', "").lowercase() in
                setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif")
        }
        val modelId = if (current.config.modelMode == SettingMode.INHERIT) current.globalSettings.defaultModelId else current.config.model
        val model = current.models.firstOrNull { it.id == modelId }
        if (hasImages && model?.visionStatus != VisionStatus.SUPPORTED && current.globalSettings.visionFallbackModelId == null) {
            mutableState.update { it.copy(screen = AppScreen.GLOBAL_SETTINGS, notice = uiText(R.string.configure_vision_fallback)) }
            return
        }
        val initialConversationId = current.activeConversationId
        if (initialConversationId != null && generationJobs[initialConversationId]?.isActive == true) return
        val transferredAttachments = current.pendingAttachments
        val request = SendMessageRequest(
            content = message,
            enableSearch = current.enableSearch,
            enableRead = current.enableRead,
            enableKnowledge = current.enableKnowledge,
            knowledgeChunkIds = current.pendingKnowledgeChunkIds,
            timeZone = TimeZone.getDefault().id,
            requestId = UUID.randomUUID().toString(),
            attachments = transferredAttachments,
        )
        val transferredUris = transferredAttachments.mapTo(mutableSetOf()) { it.uri }
        mutableState.update { state ->
            state.copy(
                pendingAttachments = state.pendingAttachments.filterNot { it.uri in transferredUris },
                pendingKnowledgeChunkIds = emptyList(),
            )
        }
        viewModelScope.launch {
            val conversationId = initialConversationId ?: run {
                val created = runCatching { repository.createConversation(current.config.toRequest()) }
                    .getOrElse {
                        restoreOrDiscardTransferredAttachments(initialConversationId, transferredAttachments)
                        handleError(it)
                        return@launch
                    }
                mutableState.update { state ->
                    val stillOnDraft = state.activeConversationId == initialConversationId
                    state.copy(
                        conversations = upsertConversation(state.conversations, created),
                        activeConversationId = if (stillOnDraft) created.id else state.activeConversationId,
                        config = if (stillOnDraft) created.toConfig() else state.config,
                        messages = state.messages + (created.id to emptyList()),
                    )
                }
                created.id
            }
            if (generationJobs[conversationId]?.isActive == true) {
                restoreOrDiscardTransferredAttachments(conversationId, transferredAttachments)
                return@launch
            }
            launchGeneration(conversationId, transferredAttachments) { repository.sendMessage(conversationId, request) }
        }
    }

    fun regenerateLatest() {
        val id = mutableState.value.activeConversationId ?: return
        if (generationJobs[id]?.isActive == true) return
        val request = SendMessageRequest(
            enableSearch = mutableState.value.enableSearch,
            enableRead = mutableState.value.enableRead,
            enableKnowledge = mutableState.value.enableKnowledge,
            timeZone = TimeZone.getDefault().id,
            requestId = UUID.randomUUID().toString(),
        )
        launchGeneration(id) { repository.regenerate(id, request) }
    }

    fun stopGeneration(id: String? = mutableState.value.activeConversationId) {
        id ?: return
        mutableState.update { state ->
            state.copy(generations = state.generations + (id to (state.generations[id] ?: GenerationState()).copy(stopping = true)))
        }
        generationJobs[id]?.cancel()
    }

    fun beginNewProvider() {
        mutableState.update { it.copy(screen = AppScreen.PROVIDERS, providerEditor = ProviderEditorState()) }
    }

    fun editProvider(id: String) {
        viewModelScope.launch {
            runCatching { repository.provider(id) }
                .onSuccess { editor ->
                    if (editor != null) mutableState.update {
                        it.copy(
                            screen = AppScreen.PROVIDERS,
                            providerEditor = ProviderEditorState(editor.draft, editor.models),
                        )
                    }
                }
                .onFailure(::handleError)
        }
    }

    fun closeProviderEditor() = mutableState.update { it.copy(providerEditor = null) }

    fun updateProviderDraft(draft: ProviderDraft) = mutableState.update { state ->
        state.copy(providerEditor = state.providerEditor?.copy(draft = draft, error = null))
    }

    fun setProviderModelSearch(value: String) = mutableState.update { state ->
        state.copy(providerEditor = state.providerEditor?.copy(modelSearch = value))
    }

    fun fetchProviderModels() {
        val editor = mutableState.value.providerEditor ?: return
        mutableState.update { it.copy(providerEditor = editor.copy(busy = true, error = null)) }
        viewModelScope.launch {
            runCatching { repository.fetchModels(editor.draft) }
                .onSuccess { remote -> mutableState.update { state ->
                    state.copy(providerEditor = state.providerEditor?.copy(remoteModels = remote, busy = false))
                } }
                .onFailure { error -> mutableState.update { state ->
                    state.copy(providerEditor = state.providerEditor?.copy(busy = false, error = readableError(error)))
                } }
        }
    }

    fun toggleProviderModel(remote: RemoteModel) {
        mutableState.update { state ->
            val editor = state.providerEditor ?: return@update state
            val existing = editor.selectedModels.firstOrNull { it.remoteId == remote.id }
            val selected = if (existing == null) {
                editor.selectedModels + ModelProfile(
                    providerId = editor.draft.id,
                    remoteId = remote.id,
                    displayName = remote.displayName,
                    isDefault = state.models.isEmpty() && editor.selectedModels.isEmpty(),
                )
            } else editor.selectedModels - existing
            state.copy(providerEditor = editor.copy(selectedModels = selected))
        }
    }

    fun addManualModel(modelId: String) {
        val remote = modelId.trim()
        if (remote.isEmpty()) return
        mutableState.update { state ->
            val editor = state.providerEditor ?: return@update state
            if (editor.selectedModels.any { it.remoteId == remote }) return@update state
            state.copy(
                providerEditor = editor.copy(
                    selectedModels = editor.selectedModels + ModelProfile(
                        providerId = editor.draft.id,
                        remoteId = remote,
                        displayName = remote,
                        isDefault = state.models.isEmpty() && editor.selectedModels.isEmpty(),
                    ),
                ),
            )
        }
    }

    fun updateSelectedModel(id: String, alias: String, maxOutputTokens: Int, makeDefault: Boolean) {
        mutableState.update { state ->
            val editor = state.providerEditor ?: return@update state
            val models = editor.selectedModels.map { model ->
                if (model.id == id) model.copy(
                    displayName = alias,
                    maxOutputTokens = maxOutputTokens.coerceIn(1, MAX_MODEL_OUTPUT_TOKENS),
                    isDefault = makeDefault,
                ) else if (makeDefault) model.copy(isDefault = false) else model
            }
            state.copy(providerEditor = editor.copy(selectedModels = models))
        }
    }

    fun removeSelectedModel(id: String) {
        mutableState.update { state ->
            val editor = state.providerEditor ?: return@update state
            state.copy(providerEditor = editor.copy(selectedModels = editor.selectedModels.filterNot { it.id == id }))
        }
    }

    fun saveProvider() {
        val editor = mutableState.value.providerEditor ?: return
        if (editor.selectedModels.isEmpty()) {
            mutableState.update { it.copy(providerEditor = editor.copy(error = UiText.Dynamic("Add at least one model"))) }
            return
        }
        mutableState.update { it.copy(providerEditor = editor.copy(busy = true, error = null)) }
        viewModelScope.launch {
            runCatching { repository.saveProvider(editor.draft, editor.selectedModels) }
                .onSuccess {
                    mutableState.update { state -> state.copy(providerEditor = null, notice = uiText(R.string.provider_saved)) }
                    loadWorkspace(openChatWhenReady = true)
                }
                .onFailure { error -> mutableState.update { state ->
                    state.copy(providerEditor = state.providerEditor?.copy(busy = false, error = readableError(error)))
                } }
        }
    }

    fun deleteProvider(id: String) {
        viewModelScope.launch {
            runCatching { repository.deleteProvider(id) }
                .onSuccess { loadWorkspace() }
                .onFailure(::handleError)
        }
    }

    fun setDefaultModel(id: String) {
        viewModelScope.launch {
            runCatching { repository.setDefaultModel(id) }
                .onSuccess { loadWorkspace() }
                .onFailure(::handleError)
        }
    }

    fun testModelVision(id: String) {
        if (mutableState.value.visionTestingModelId != null) return
        mutableState.update { it.copy(visionTestingModelId = id) }
        viewModelScope.launch {
            runCatching { repository.testModelVision(id) }
                .onSuccess { status ->
                    mutableState.update { state -> state.copy(
                        visionTestingModelId = null,
                        models = state.models.map { model -> if (model.id == id) model.copy(visionStatus = status, visionCheckedAt = System.currentTimeMillis()) else model },
                        providerEditor = state.providerEditor?.let { editor -> editor.copy(
                            selectedModels = editor.selectedModels.map { model -> if (model.id == id) model.copy(visionStatus = status, visionCheckedAt = System.currentTimeMillis()) else model },
                        ) },
                        notice = UiText.Dynamic("Vision test: ${status.name.lowercase()}")
                    ) }
                }
                .onFailure { error -> mutableState.update { it.copy(visionTestingModelId = null, notice = readableError(error)) } }
        }
    }

    fun synthesizeSpeech(
        messageId: String,
        force: Boolean = false,
        autoPlayTarget: SpeechAutoPlayTarget? = null,
    ) {
        ttsJob?.cancel()
        mutableState.update { state ->
            val current = state.tts[messageId] ?: TtsUiState()
            state.copy(tts = state.tts + (messageId to current.copy(loading = true, error = null)))
        }
        ttsJob = viewModelScope.launch {
            runCatching { repository.synthesizeSpeech(messageId, force) }
                .onSuccess { audio ->
                    mutableState.update { state ->
                        state.copy(tts = state.tts + (messageId to TtsUiState(filePath = audio.file.absolutePath)))
                    }
                    autoPlayTarget?.let { target ->
                        mutableSpeechAutoPlay.tryEmit(
                            SpeechAutoPlayRequest(messageId, audio.file.absolutePath, target),
                        )
                    }
                }
                .onFailure { error -> mutableState.update { state ->
                    val current = state.tts[messageId] ?: TtsUiState()
                    state.copy(
                        tts = state.tts + (messageId to current.copy(loading = false, error = readableError(error))),
                        notice = readableError(error),
                    )
                } }
        }
    }

    fun reportSpeechPlaybackError(messageId: String) {
        mutableState.update { state ->
            val current = state.tts[messageId] ?: TtsUiState()
            state.copy(
                tts = state.tts + (messageId to current.copy(error = uiText(R.string.speech_playback_failed))),
                notice = uiText(R.string.speech_playback_failed),
            )
        }
    }

    fun saveExaKey(value: String) {
        runCatching { repository.saveExaKey(value) }
            .onSuccess { mutableState.update { it.copy(exaConfigured = value.isNotBlank(), notice = uiText(R.string.exa_saved)) } }
            .onFailure(::handleError)
    }

    fun testExa(query: String) {
        viewModelScope.launch {
            runCatching { repository.testExa(query) }
                .onSuccess { result -> mutableState.update { it.copy(exaTestResult = result) } }
                .onFailure(::handleError)
        }
    }

    fun exportConfiguration(password: CharArray) {
        mutableState.update { it.copy(transfer = it.transfer.copy(busy = true, error = null, exportContent = null)) }
        viewModelScope.launch {
            runCatching { repository.exportConfiguration(password) }
                .onSuccess { content -> mutableState.update { it.copy(transfer = TransferState(exportContent = content)) } }
                .onFailure { error -> mutableState.update { it.copy(transfer = TransferState(error = readableError(error))) } }
            password.fill('\u0000')
        }
    }

    fun consumeExport() = mutableState.update { it.copy(transfer = it.transfer.copy(exportContent = null)) }

    fun prepareImport() = mutableState.update { it.copy(transfer = TransferState()) }

    fun previewImport(raw: String, password: CharArray) {
        mutableState.update { it.copy(transfer = TransferState(busy = true)) }
        viewModelScope.launch {
            runCatching { repository.previewImport(raw, password) }
                .onSuccess { preview -> mutableState.update { it.copy(transfer = TransferState(importPreview = preview)) } }
                .onFailure { error -> mutableState.update { it.copy(transfer = TransferState(error = readableError(error))) } }
            password.fill('\u0000')
        }
    }

    fun cancelImportPreview() = mutableState.update { it.copy(transfer = TransferState()) }

    fun applyImport(openChatWhenReady: Boolean = false) {
        val preview = mutableState.value.transfer.importPreview ?: return
        mutableState.update { it.copy(transfer = it.transfer.copy(busy = true, error = null)) }
        viewModelScope.launch {
            runCatching { repository.applyImport(preview) }
                .onSuccess {
                    mutableState.update { it.copy(transfer = TransferState(), notice = uiText(R.string.import_complete)) }
                    loadWorkspace(openChatWhenReady = openChatWhenReady)
                }
                .onFailure { error -> mutableState.update { it.copy(transfer = it.transfer.copy(busy = false, error = readableError(error))) } }
        }
    }

    private fun bootstrap() {
        viewModelScope.launch {
            runCatching { repository.initialize() }
                .onSuccess { loadWorkspace() }
                .onFailure { error ->
                    mutableState.update { it.copy(phase = AppPhase.SETUP, screen = AppScreen.PROVIDERS, notice = readableError(error)) }
                }
        }
    }

    private fun loadWorkspace(openChatWhenReady: Boolean = false) {
        val loadVersion = ++workspaceLoadVersion
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true) }
            runCatching { repository.workspace() }
                .onSuccess { workspace ->
                    if (loadVersion != workspaceLoadVersion) return@onSuccess
                    mutableState.update { state ->
                        val hasModels = workspace.models.isNotEmpty()
                        val active = state.activeConversationId?.takeIf { id -> workspace.conversations.any { it.id == id } }
                        state.copy(
                            phase = if (hasModels) AppPhase.READY else AppPhase.SETUP,
                            screen = if (!hasModels) AppScreen.PROVIDERS else if (openChatWhenReady) AppScreen.CHAT else state.screen,
                            loading = false,
                            providers = workspace.providers,
                            models = workspace.models,
                            conversations = workspace.conversations,
                            activeConversationId = active,
                            exaConfigured = workspace.exaConfigured,
                            globalSettings = workspace.globalSettings,
                            bookmarks = workspace.bookmarks,
                            notes = workspace.notes,
                            agents = workspace.agents,
                            knowledgeDocuments = workspace.knowledgeDocuments,
                            config = active?.let { id -> workspace.conversations.first { it.id == id }.toConfig() }
                                ?: if (state.config.modelMode == SettingMode.INHERIT || state.config.model in workspace.models.map { it.id }) {
                                    state.config.copy(
                                        model = if (state.config.modelMode == SettingMode.INHERIT) workspace.globalSettings.defaultModelId.orEmpty() else state.config.model,
                                        systemPrompt = if (state.config.systemPromptMode == SettingMode.INHERIT) workspace.globalSettings.systemPrompt else state.config.systemPrompt,
                                        userAvatar = if (state.config.userAvatarMode == SettingMode.INHERIT) workspace.globalSettings.userAvatar else state.config.userAvatar,
                                        assistantAvatar = if (state.config.assistantAvatarMode == SettingMode.INHERIT) workspace.globalSettings.assistantAvatar else state.config.assistantAvatar,
                                        urlReaderBackend = if (state.config.urlReaderMode == SettingMode.INHERIT) workspace.globalSettings.urlReaderBackend else state.config.urlReaderBackend,
                                    )
                                } else defaultConfig(workspace.models, workspace.globalSettings),
                        )
                    }
                }
                .onFailure { error ->
                    if (loadVersion == workspaceLoadVersion) {
                        mutableState.update { it.copy(loading = false, notice = readableError(error)) }
                    }
                }
        }
    }

    private suspend fun loadConversation(id: String) {
        runCatching { repository.conversation(id) }
            .onSuccess { detail -> mutableState.update { state ->
                if (state.conversations.none { it.id == id }) return@update state
                state.copy(
                    conversations = upsertConversation(state.conversations, detail.conversation),
                    messages = state.messages + (id to detail.messages),
                    attachments = state.attachments + (id to detail.attachments),
                    config = if (state.activeConversationId == id) detail.conversation.toConfig() else state.config,
                )
            } }
            .onFailure { error ->
                if (mutableState.value.conversations.any { it.id == id }) handleError(error)
            }
    }

    private fun launchGeneration(
        id: String,
        transferredAttachments: List<PendingAttachment> = emptyList(),
        stream: () -> kotlinx.coroutines.flow.Flow<ChatEvent>,
    ) {
        mutableState.update { state ->
            state.copy(generations = state.generations + (id to GenerationState()))
        }
        val job = viewModelScope.launch {
            var userMessageAccepted = false
            try {
                stream().collect { event ->
                    if (event is ChatEvent.UserMessage) userMessageAccepted = true
                    handleChatEvent(id, event)
                }
            } catch (_: CancellationException) {
                mutableState.update { state ->
                    state.copy(generations = state.generations + (id to (state.generations[id] ?: GenerationState()).copy(active = false, stopping = false)))
                }
            } catch (error: Throwable) {
                val message = readableError(error)
                mutableState.update { state ->
                    state.copy(
                        generations = state.generations + (id to (state.generations[id] ?: GenerationState()).copy(active = false, error = message)),
                        notice = message,
                    )
                }
            } finally {
                if (!userMessageAccepted) restoreOrDiscardTransferredAttachments(id, transferredAttachments)
                generationJobs.remove(id)
                loadConversation(id)
                loadWorkspace()
            }
        }
        generationJobs[id] = job
    }

    private fun handleChatEvent(id: String, event: ChatEvent) {
        mutableState.update { state ->
            when (event) {
                is ChatEvent.UserMessage -> state.copy(
                    messages = state.messages + (id to (state.messages[id].orEmpty() + event.message)),
                    attachments = state.attachments + (id to (state.attachments[id].orEmpty() + event.attachments)),
                )
                is ChatEvent.AssistantMessage -> state.copy(messages = state.messages + (id to (state.messages[id].orEmpty() + event.message)))
                is ChatEvent.Delta -> {
                    val messages = state.messages[id].orEmpty().toMutableList()
                    val index = messages.indexOfLast { it.role == "assistant" && it.status == "generating" }
                    if (index >= 0) messages[index] = messages[index].copy(content = messages[index].content + event.content)
                    val generation = (state.generations[id] ?: GenerationState()).copy(hasDelta = true)
                    state.copy(messages = state.messages + (id to messages), generations = state.generations + (id to generation))
                }
                is ChatEvent.Process -> {
                    val generation = state.generations[id] ?: GenerationState()
                    state.copy(generations = state.generations + (id to generation.copy(events = mergeProcess(generation.events, event.event))))
                }
                is ChatEvent.Done -> {
                    val generation = state.generations[id] ?: GenerationState()
                    state.copy(generations = state.generations + (id to generation.copy(active = false, stopping = false, usage = event.usage)))
                }
            }
        }
    }

    private fun handleError(error: Throwable) {
        mutableState.update { it.copy(notice = readableError(error)) }
    }

    private fun loadKnowledgePreview(document: KnowledgeDocument) {
        val requestGeneration = invalidateKnowledgePreviewRequest()
        mutableState.update { it.copy(knowledgePreview = KnowledgePreviewState.Loading(document)) }
        knowledgePreviewJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val preview = repository.knowledgeDocumentPreview(document.id)
                if (requestGeneration != knowledgePreviewGeneration) return@launch
                mutableState.update { state ->
                    if (requestGeneration != knowledgePreviewGeneration) state
                    else state.copy(
                        knowledgePreview = preview?.let { KnowledgePreviewState.Ready(it) }
                            ?: unavailableKnowledgePreview(document),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (requestGeneration == knowledgePreviewGeneration) {
                    mutableState.update { state ->
                        if (requestGeneration != knowledgePreviewGeneration) state
                        else state.copy(knowledgePreview = unavailableKnowledgePreview(document))
                    }
                }
            } finally {
                if (requestGeneration == knowledgePreviewGeneration) knowledgePreviewJob = null
            }
        }
        knowledgePreviewJob?.start()
    }

    private fun invalidateKnowledgePreviewRequest(): Long {
        knowledgePreviewGeneration += 1
        knowledgePreviewJob?.cancel()
        knowledgePreviewJob = null
        return knowledgePreviewGeneration
    }

    private fun unavailableKnowledgePreview(document: KnowledgeDocument) = KnowledgePreviewState.Error(
        document = document,
        message = uiText(R.string.knowledge_preview_unavailable),
    )

    private fun observeKnowledgePreviewScreen() {
        viewModelScope.launch {
            state.collect { current ->
                if (current.screen != AppScreen.KNOWLEDGE && current.knowledgePreview !is KnowledgePreviewState.Closed) {
                    closeKnowledgePreview()
                }
            }
        }
    }

    private fun discardPendingAttachments(attachments: List<PendingAttachment>) {
        if (attachments.isEmpty()) return
        viewModelScope.launch { runCatching { repository.discardPendingAttachments(attachments) } }
    }

    private fun restoreOrDiscardTransferredAttachments(
        ownerConversationId: String?,
        attachments: List<PendingAttachment>,
    ) {
        if (attachments.isEmpty()) return
        val current = mutableState.value
        if (current.activeConversationId != ownerConversationId) {
            discardPendingAttachments(attachments)
            return
        }
        val combined = (attachments + current.pendingAttachments).distinctBy { it.uri }
        mutableState.update { it.copy(pendingAttachments = combined.take(5)) }
        discardPendingAttachments(combined.drop(5))
    }

    private fun refreshAfter(action: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { action() }.onSuccess { loadWorkspace() }.onFailure(::handleError)
        }
    }

    private fun readableError(error: Throwable): UiText = when (error) {
        is ApiException -> UiText.Dynamic(if (error.status > 0) "HTTP ${error.status}: ${error.message}" else error.message)
        is NoteChangedDuringSummaryException -> uiText(R.string.note_changed_during_summary)
        is NoteSummaryTooLongException -> uiText(R.string.note_too_long_to_summarize, error.maxCharacters)
        is ConfigurationException, is IllegalArgumentException -> UiText.Dynamic(error.message ?: "Invalid configuration")
        is IOException -> uiText(R.string.error_network)
        else -> UiText.Dynamic(error.message ?: "Request failed")
    }
}

private fun Conversation.toConfig() = ConversationConfig(
    model = model.orEmpty(),
    modelMode = modelMode,
    thinkingEffort = thinkingEffort,
    systemPrompt = systemPrompt,
    systemPromptMode = systemPromptMode,
    nickname = nickname,
    userAvatar = userAvatar,
    userAvatarMode = userAvatarMode,
    assistantAvatar = assistantAvatar,
    assistantAvatarMode = assistantAvatarMode,
    urlReaderBackend = urlReaderBackend ?: UrlReaderBackend.BUILT_IN,
    urlReaderMode = if (urlReaderBackend == null) SettingMode.INHERIT else SettingMode.OVERRIDE,
    maxToolCalls = maxToolCalls,
    enableSearch = enableSearch,
    enableRead = enableRead,
    enableKnowledge = enableKnowledge,
)

private fun ConversationConfig.toRequest() = ConversationWriteRequest(
    model = model,
    modelMode = modelMode,
    thinkingEffort = thinkingEffort,
    systemPrompt = systemPrompt,
    systemPromptMode = systemPromptMode,
    nickname = nickname,
    userAvatar = userAvatar,
    userAvatarMode = userAvatarMode,
    assistantAvatar = assistantAvatar,
    assistantAvatarMode = assistantAvatarMode,
    urlReaderMode = urlReaderMode,
    urlReaderBackend = urlReaderBackend,
    maxToolCalls = maxToolCalls,
    enableSearch = enableSearch,
    enableRead = enableRead,
    enableKnowledge = enableKnowledge,
)

private fun defaultConfig(models: List<ModelProfile>, global: GlobalChatSettings = GlobalChatSettings()) = ConversationConfig(
    model = global.defaultModelId ?: (models.firstOrNull { it.isDefault } ?: models.firstOrNull())?.id.orEmpty(),
    systemPrompt = global.systemPrompt,
    userAvatar = global.userAvatar,
    assistantAvatar = global.assistantAvatar,
    urlReaderBackend = global.urlReaderBackend,
)

private fun upsertConversation(items: List<Conversation>, conversation: Conversation): List<Conversation> =
    (listOf(conversation) + items.filterNot { it.id == conversation.id })
        .sortedByDescending { it.lastMessageAt ?: it.updatedAt }

private fun upsertNote(items: List<Note>, note: Note): List<Note> =
    (listOf(note) + items.filterNot { it.id == note.id }).sortedByDescending(Note::updatedAt)

private fun mergeProcess(events: List<ProcessEvent>, incoming: ProcessEvent): List<ProcessEvent> {
    val index = events.indexOfLast { it.type == "thinking" && it.id == incoming.id }
    if (index < 0) return events + incoming
    return events.toMutableList().also { list ->
        list[index] = list[index].copy(content = list[index].content + incoming.content)
    }
}

class AppViewModelFactory internal constructor(
    private val repository: ChatDataSource,
    private val noteMarkdownFiles: NoteMarkdownFileAccess,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        AppViewModel(repository, noteMarkdownFiles) as T
}
