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
import xyz.mek030399.tokenflow.data.CloudServerProfile
import xyz.mek030399.tokenflow.data.CloudServerDraft
import xyz.mek030399.tokenflow.data.CloudConnectionProbe
import xyz.mek030399.tokenflow.data.CloudArtifactDelivery
import xyz.mek030399.tokenflow.data.CloudFileEntry
import xyz.mek030399.tokenflow.data.CloudMcpServer
import xyz.mek030399.tokenflow.data.CloudTask
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class AppPhase { LOADING, SETUP, READY }
enum class AppScreen { CHAT, BOOKMARKS, NOTES, AGENTS, KNOWLEDGE, INFINITE_CLOUD, GLOBAL_SETTINGS, PROVIDERS, EXA, TRANSFER, ABOUT }
enum class CloudSection { SERVERS, TASKS, FILES, MCP }

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
    val enableInfiniteCloud: Boolean = false,
    val cloudServerId: String? = null,
)

data class CloudWorkspaceUiState(
    val section: CloudSection = CloudSection.SERVERS,
    val selectedServerId: String? = null,
    val taskServerFilterId: String? = null,
    val currentPath: String = "~",
    val files: List<CloudFileEntry> = emptyList(),
    val textServerId: String? = null,
    val textPath: String? = null,
    val textContent: String = "",
    val taskLogId: String? = null,
    val taskLog: String = "",
    val pendingTrustServerId: String? = null,
    val pendingProbe: CloudConnectionProbe? = null,
    val pendingHostKeyReplacement: CloudHostKeyReplacement? = null,
    val probingServerId: String? = null,
    val serverDiagnostics: Map<String, CloudServerDiagnostic> = emptyMap(),
    val busy: Boolean = false,
    val mutatingServerIds: Set<String> = emptySet(),
    val mutatingMcpServerIds: Set<String> = emptySet(),
    val savingText: Boolean = false,
    val testingMcpServerId: String? = null,
    val mcpTestName: String? = null,
    val mcpTestTools: List<String> = emptyList(),
    val mcpTestCloudServerId: String? = null,
    val mcpTestServerId: String? = null,
    val mcpTestServerUpdatedAt: Long? = null,
    val retryingArtifactDeliveryIds: Set<String> = emptySet(),
)

data class CloudHostKeyReplacement(
    val serverId: String,
    val expectedHostKeyBase64: String,
    val oldFingerprint: String,
    val probe: CloudConnectionProbe,
)

data class CloudServerDiagnostic(
    val probe: CloudConnectionProbe? = null,
    val error: String? = null,
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

data class ComposerDraftRecovery(
    val requestId: String,
    val conversationId: String?,
    val content: String,
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
    val composerDraftRecovery: ComposerDraftRecovery? = null,
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
    val cloudServers: List<CloudServerProfile> = emptyList(),
    val cloudMcpServers: List<CloudMcpServer> = emptyList(),
    val cloudTasks: List<CloudTask> = emptyList(),
    val cloudArtifactDeliveries: List<CloudArtifactDelivery> = emptyList(),
    val cloud: CloudWorkspaceUiState = CloudWorkspaceUiState(),
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
    private var cloudSyncJob: Job? = null
    private var cloudFilesJob: Job? = null
    private var cloudTextJob: Job? = null
    private var cloudTaskLogJob: Job? = null
    private var cloudMcpTestJob: Job? = null
    private val cloudServerMutationLocks = ConcurrentHashMap<String, Mutex>()
    private val cloudMcpMutationLocks = ConcurrentHashMap<String, Mutex>()
    private val cloudTextWriteLock = Mutex()
    private val toolSettingsJobs = mutableMapOf<String, Job>()
    private val toolSettingsGenerations = mutableMapOf<String, Long>()
    private var knowledgePreviewGeneration = 0L
    private var cloudFileGeneration = 0L
    private var cloudServerSelectionGeneration = 0L
    private var cloudTaskLogGeneration = 0L
    private var cloudMcpTestGeneration = 0L
    private var cloudTextSaveGeneration = 0L
    private val noteSavingMessageIds = mutableSetOf<String>()
    private var pendingMarkdownExport: Note? = null
    private var workspaceLoadVersion = 0L
    private var workspaceReady = false

    init {
        observeKnowledgePreviewScreen()
        bootstrap()
    }

    fun retryLoad() = loadWorkspace(onLoaded = ::onInitialWorkspaceLoaded)

    fun openScreen(screen: AppScreen) {
        if (screen == AppScreen.CHAT && mutableState.value.models.isEmpty()) return
        val leavingKnowledge = mutableState.value.screen == AppScreen.KNOWLEDGE && screen != AppScreen.KNOWLEDGE
        val leavingCloudFiles = mutableState.value.screen == AppScreen.INFINITE_CLOUD &&
            mutableState.value.cloud.section == CloudSection.FILES && screen != AppScreen.INFINITE_CLOUD
        val leavingCloudTasks = mutableState.value.screen == AppScreen.INFINITE_CLOUD &&
            mutableState.value.cloud.section == CloudSection.TASKS && screen != AppScreen.INFINITE_CLOUD
        val leavingCloudMcp = mutableState.value.screen == AppScreen.INFINITE_CLOUD &&
            mutableState.value.cloud.section == CloudSection.MCP && screen != AppScreen.INFINITE_CLOUD
        if (leavingKnowledge) invalidateKnowledgePreviewRequest()
        if (leavingCloudFiles) invalidateCloudFileRequests()
        if (leavingCloudTasks) invalidateCloudTaskLogRequest()
        if (leavingCloudMcp) invalidateCloudMcpTestRequest()
        mutableState.update {
            it.copy(
                screen = screen,
                providerEditor = null,
                knowledgePreview = if (leavingKnowledge) KnowledgePreviewState.Closed else it.knowledgePreview,
                cloud = if (leavingCloudFiles) it.cloud.copy(
                    busy = false,
                    textServerId = null,
                    textPath = null,
                    textContent = "",
                    savingText = false,
                ) else if (leavingCloudTasks) it.cloud.copy(
                    taskLogId = null,
                    taskLog = "",
                ) else if (leavingCloudMcp) it.cloud.copy(
                    testingMcpServerId = null,
                    mcpTestName = null,
                    mcpTestTools = emptyList(),
                    mcpTestCloudServerId = null,
                    mcpTestServerId = null,
                    mcpTestServerUpdatedAt = null,
                ) else it.cloud,
            )
        }
        if (screen == AppScreen.INFINITE_CLOUD) {
            syncCloudTasks()
            val state = mutableState.value
            if (state.cloud.section == CloudSection.FILES) {
                val server = state.cloudServers.firstOrNull { it.id == state.cloud.selectedServerId }
                if (server != null) loadCloudFiles(state.cloud.currentPath.ifBlank { server.startDirectory })
            }
        }
    }

    fun clearNotice() = mutableState.update { it.copy(notice = null) }

    fun selectCloudSection(section: CloudSection) {
        val previous = mutableState.value.cloud.section
        val leavingFiles = previous == CloudSection.FILES && section != CloudSection.FILES
        val leavingTasks = previous == CloudSection.TASKS && section != CloudSection.TASKS
        val leavingMcp = previous == CloudSection.MCP && section != CloudSection.MCP
        if (leavingFiles) invalidateCloudFileRequests()
        if (leavingTasks) invalidateCloudTaskLogRequest()
        if (leavingMcp) invalidateCloudMcpTestRequest()
        mutableState.update {
            it.copy(cloud = it.cloud.copy(
                section = section,
                busy = if (leavingFiles) false else it.cloud.busy,
                textServerId = if (leavingFiles) null else it.cloud.textServerId,
                textPath = if (leavingFiles) null else it.cloud.textPath,
                textContent = if (leavingFiles) "" else it.cloud.textContent,
                savingText = if (leavingFiles) false else it.cloud.savingText,
                taskLogId = if (leavingTasks) null else it.cloud.taskLogId,
                taskLog = if (leavingTasks) "" else it.cloud.taskLog,
                testingMcpServerId = if (leavingMcp) null else it.cloud.testingMcpServerId,
                mcpTestName = if (leavingMcp) null else it.cloud.mcpTestName,
                mcpTestTools = if (leavingMcp) emptyList() else it.cloud.mcpTestTools,
                mcpTestCloudServerId = if (leavingMcp) null else it.cloud.mcpTestCloudServerId,
                mcpTestServerId = if (leavingMcp) null else it.cloud.mcpTestServerId,
                mcpTestServerUpdatedAt = if (leavingMcp) null else it.cloud.mcpTestServerUpdatedAt,
            ))
        }
        if (section == CloudSection.TASKS) syncCloudTasks()
        if (section == CloudSection.FILES && previous != CloudSection.FILES) {
            val state = mutableState.value
            val server = state.cloudServers.firstOrNull { it.id == state.cloud.selectedServerId }
            if (server != null) loadCloudFiles(state.cloud.currentPath.ifBlank { server.startDirectory })
        }
    }

    fun onAppResumed() {
        if (workspaceReady) syncCloudTasks()
    }

    private fun syncCloudTasks() {
        if (cloudSyncJob?.isActive == true) return
        cloudSyncJob = viewModelScope.launch {
            try {
                repository.syncCloudTasks()
                loadWorkspace()
                mutableState.value.activeConversationId?.let { loadConversation(it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                loadWorkspace()
                handleError(error)
            } finally {
                cloudSyncJob = null
            }
        }
    }

    fun selectCloudServer(id: String) {
        val server = mutableState.value.cloudServers.firstOrNull { it.id == id } ?: return
        val serverChanged = mutableState.value.cloud.selectedServerId != id
        if (serverChanged) {
            cloudServerSelectionGeneration += 1
            invalidateCloudMcpTestRequest()
        }
        invalidateCloudFileRequests()
        mutableState.update {
            it.copy(cloud = it.cloud.copy(
                selectedServerId = id,
                currentPath = server.startDirectory,
                files = emptyList(),
                textServerId = null,
                textPath = null,
                textContent = "",
                savingText = false,
                busy = false,
                testingMcpServerId = if (serverChanged) null else it.cloud.testingMcpServerId,
                mcpTestName = if (serverChanged) null else it.cloud.mcpTestName,
                mcpTestTools = if (serverChanged) emptyList() else it.cloud.mcpTestTools,
                mcpTestCloudServerId = if (serverChanged) null else it.cloud.mcpTestCloudServerId,
                mcpTestServerId = if (serverChanged) null else it.cloud.mcpTestServerId,
                mcpTestServerUpdatedAt = if (serverChanged) null else it.cloud.mcpTestServerUpdatedAt,
            ))
        }
        if (mutableState.value.cloud.section == CloudSection.FILES) loadCloudFiles(server.startDirectory)
    }

    fun selectCloudTaskServerFilter(id: String?) {
        val normalized = id?.takeIf { candidate -> mutableState.value.cloudServers.any { it.id == candidate } }
        invalidateCloudTaskLogRequest()
        mutableState.update { it.copy(cloud = it.cloud.copy(
            taskServerFilterId = normalized,
            taskLogId = null,
            taskLog = "",
        )) }
    }

    fun saveCloudServer(draft: CloudServerDraft) {
        val initialState = mutableState.value
        val serverId = draft.profile.id
        val selectedServerIdAtStart = initialState.cloud.selectedServerId
        val selectionGenerationAtStart = cloudServerSelectionGeneration
        val previous = initialState.cloudServers.firstOrNull { it.id == serverId }
        val isNewServer = previous == null
        mutableState.update { it.copy(cloud = it.cloud.copy(
            mutatingServerIds = it.cloud.mutatingServerIds + serverId,
        )) }
        viewModelScope.launch {
            try {
                val saved = cloudServerMutationLocks.computeIfAbsent(serverId) { Mutex() }
                    .withLock { repository.saveCloudServer(draft) }
                val current = mutableState.value
                val selectionUnchanged = cloudServerSelectionGeneration == selectionGenerationAtStart &&
                    current.cloud.selectedServerId == selectedServerIdAtStart
                val shouldSelectSaved = isNewServer && selectionUnchanged &&
                    current.screen == AppScreen.INFINITE_CLOUD && current.cloud.section == CloudSection.SERVERS
                val connectionChanged = previous != null && !previous.hasSameCloudConnectionTarget(saved)
                val shouldResetSavedContext = shouldSelectSaved ||
                    (current.cloud.selectedServerId == saved.id && connectionChanged)
                if (shouldSelectSaved) cloudServerSelectionGeneration += 1
                if (shouldResetSavedContext) {
                    invalidateCloudFileRequests()
                    invalidateCloudMcpTestRequest()
                }
                mutableState.update { state ->
                    val selectedServerId = if (shouldSelectSaved) saved.id else state.cloud.selectedServerId
                    val servers = if (state.cloudServers.any { it.id == saved.id }) {
                        state.cloudServers.map { if (it.id == saved.id) saved else it }
                    } else state.cloudServers + saved
                    state.copy(cloud = state.cloud.copy(
                        selectedServerId = selectedServerId,
                        currentPath = if (shouldResetSavedContext) saved.startDirectory else state.cloud.currentPath,
                        files = if (shouldResetSavedContext) emptyList() else state.cloud.files,
                        textServerId = if (shouldResetSavedContext) null else state.cloud.textServerId,
                        textPath = if (shouldResetSavedContext) null else state.cloud.textPath,
                        textContent = if (shouldResetSavedContext) "" else state.cloud.textContent,
                        serverDiagnostics = state.cloud.serverDiagnostics - saved.id,
                    ), cloudServers = servers)
                }
                val afterUpdate = mutableState.value
                if (shouldResetSavedContext && afterUpdate.screen == AppScreen.INFINITE_CLOUD &&
                    afterUpdate.cloud.section == CloudSection.FILES && afterUpdate.cloud.selectedServerId == saved.id
                ) {
                    loadCloudFiles(saved.startDirectory)
                }
                loadWorkspace()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                handleError(error)
            } finally {
                mutableState.update { state -> state.copy(cloud = state.cloud.copy(
                    mutatingServerIds = state.cloud.mutatingServerIds - serverId,
                )) }
            }
        }
    }

    fun deleteCloudServer(id: String) {
        mutableState.update { it.copy(cloud = it.cloud.copy(
            mutatingServerIds = it.cloud.mutatingServerIds + id,
        )) }
        viewModelScope.launch {
            try {
                cloudServerMutationLocks.computeIfAbsent(id) { Mutex() }.withLock {
                    repository.deleteCloudServer(id)
                }
                if (mutableState.value.cloud.selectedServerId == id) {
                    cloudServerSelectionGeneration += 1
                    invalidateCloudFileRequests()
                    invalidateCloudMcpTestRequest()
                }
                loadWorkspace()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                handleError(error)
            } finally {
                mutableState.update { state -> state.copy(cloud = state.cloud.copy(
                    mutatingServerIds = state.cloud.mutatingServerIds - id,
                )) }
            }
        }
    }

    fun probeCloudServer(id: String) {
        val profile = mutableState.value.cloudServers.firstOrNull { it.id == id } ?: return
        startCloudServerProbe(profile)
    }

    private fun startCloudServerProbe(profile: CloudServerProfile) {
        if (mutableState.value.cloud.probingServerId != null) return
        mutableState.update { it.copy(cloud = it.cloud.copy(probingServerId = profile.id)) }
        viewModelScope.launch { performCloudServerProbe(profile) }
    }

    private suspend fun performCloudServerProbe(profile: CloudServerProfile) {
        val id = profile.id
        mutableState.update { it.copy(cloud = it.cloud.copy(probingServerId = id)) }
        try {
            val probe = repository.probeCloudServer(profile)
            mutableState.update { state ->
                val current = state.cloudServers.firstOrNull { it.id == id }
                if (current == null || !current.hasSameCloudProbeConfiguration(profile)) {
                    state.copy(cloud = state.cloud.copy(probingServerId = null))
                }
                else state.copy(
                    cloud = state.cloud.copy(
                        probingServerId = null,
                        pendingTrustServerId = id.takeUnless { probe.trusted },
                        pendingProbe = probe.takeUnless { probe.trusted },
                        serverDiagnostics = if (probe.trusted) {
                            state.cloud.serverDiagnostics + (id to CloudServerDiagnostic(probe = probe))
                        } else state.cloud.serverDiagnostics,
                    ),
                    notice = if (probe.trusted) UiText.Dynamic("Infinite Cloud connection succeeded") else state.notice,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            mutableState.update { state ->
                val current = state.cloudServers.firstOrNull { it.id == id }
                state.copy(cloud = state.cloud.copy(
                    probingServerId = null,
                    serverDiagnostics = if (current?.hasSameCloudProbeConfiguration(profile) == true) {
                        state.cloud.serverDiagnostics +
                            (id to CloudServerDiagnostic(error = error.message ?: "Infinite Cloud connection failed"))
                    } else state.cloud.serverDiagnostics,
                ))
            }
            if (mutableState.value.cloudServers.firstOrNull { it.id == id }?.hasSameCloudProbeConfiguration(profile) == true) {
                handleError(error)
            }
        }
    }

    private fun CloudServerProfile.hasSameCloudConnectionTarget(other: CloudServerProfile): Boolean =
        host == other.host && port == other.port && username == other.username && hostKeyBase64 == other.hostKeyBase64

    private fun CloudServerProfile.hasSameCloudProbeConfiguration(other: CloudServerProfile): Boolean =
        updatedAt == other.updatedAt && hasSameCloudConnectionTarget(other)

    fun trustPendingCloudHost() {
        val cloud = mutableState.value.cloud
        val id = cloud.pendingTrustServerId ?: return
        val probe = cloud.pendingProbe ?: return
        if (cloud.probingServerId != null) return
        mutableState.update { it.copy(cloud = it.cloud.copy(probingServerId = id)) }
        viewModelScope.launch {
            try {
                val trusted = repository.trustCloudHostKey(id, probe)
                mutableState.update { state ->
                    state.copy(
                        cloudServers = state.cloudServers.map { if (it.id == trusted.id) trusted else it },
                        cloud = state.cloud.copy(pendingTrustServerId = null, pendingProbe = null),
                    )
                }
                performCloudServerProbe(trusted)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(cloud = it.cloud.copy(probingServerId = null)) }
                handleError(error)
            }
        }
    }

    fun dismissCloudHostTrust() = mutableState.update {
        it.copy(cloud = it.cloud.copy(pendingTrustServerId = null, pendingProbe = null))
    }

    fun probeCloudHostReplacement(id: String) {
        val profile = mutableState.value.cloudServers.firstOrNull { it.id == id } ?: return
        val expectedKey = profile.hostKeyBase64 ?: return
        val oldFingerprint = profile.hostKeyFingerprint ?: return
        if (mutableState.value.cloud.probingServerId != null) return
        mutableState.update { it.copy(cloud = it.cloud.copy(probingServerId = id)) }
        viewModelScope.launch {
            try {
                val probe = repository.probeCloudHostReplacement(id)
                val current = mutableState.value.cloudServers.firstOrNull { it.id == id }
                if (current?.hostKeyBase64 != expectedKey) {
                    mutableState.update { it.copy(cloud = it.cloud.copy(probingServerId = null)) }
                    return@launch
                }
                mutableState.update { state ->
                    state.copy(cloud = state.cloud.copy(
                        probingServerId = null,
                        pendingHostKeyReplacement = if (probe.hostKeyBase64 == expectedKey) null else CloudHostKeyReplacement(
                            serverId = id,
                            expectedHostKeyBase64 = expectedKey,
                            oldFingerprint = oldFingerprint,
                            probe = probe,
                        ),
                    ), notice = if (probe.hostKeyBase64 == expectedKey) uiText(R.string.cloud_host_key_unchanged) else state.notice)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(cloud = it.cloud.copy(probingServerId = null)) }
                handleError(error)
            }
        }
    }

    fun replacePendingCloudHostKey() {
        val cloud = mutableState.value.cloud
        val replacement = cloud.pendingHostKeyReplacement ?: return
        if (cloud.probingServerId != null) return
        mutableState.update { it.copy(cloud = it.cloud.copy(probingServerId = replacement.serverId)) }
        viewModelScope.launch {
            try {
                val trusted = repository.replaceCloudHostKey(
                    replacement.serverId,
                    replacement.expectedHostKeyBase64,
                    replacement.probe,
                )
                mutableState.update { state ->
                    state.copy(
                        cloudServers = state.cloudServers.map { if (it.id == trusted.id) trusted else it },
                        cloud = state.cloud.copy(pendingHostKeyReplacement = null),
                    )
                }
                performCloudServerProbe(trusted)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(cloud = it.cloud.copy(probingServerId = null)) }
                handleError(error)
            }
        }
    }

    fun dismissCloudHostKeyReplacement() = mutableState.update {
        it.copy(cloud = it.cloud.copy(pendingHostKeyReplacement = null))
    }

    fun loadCloudFiles(path: String = mutableState.value.cloud.currentPath) {
        val state = mutableState.value
        if (state.cloud.section != CloudSection.FILES) return
        val id = state.cloud.selectedServerId ?: return
        cloudFilesJob?.cancel()
        cloudTextJob?.cancel()
        val generation = ++cloudFileGeneration
        mutableState.update {
            it.copy(cloud = it.cloud.copy(
                busy = true,
                textServerId = null,
                textPath = null,
                textContent = "",
            ))
        }
        cloudFilesJob = viewModelScope.launch {
            try {
                val (resolved, files) = repository.cloudFiles(id, path)
                mutableState.update { current ->
                    if (!isCurrentCloudFileRequest(current, id, generation)) current
                    else current.copy(cloud = current.cloud.copy(currentPath = resolved, files = files, busy = false))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (isCurrentCloudFileRequest(mutableState.value, id, generation)) {
                    mutableState.update { it.copy(cloud = it.cloud.copy(busy = false)) }
                    handleError(error)
                }
            } finally {
                if (generation == cloudFileGeneration) cloudFilesJob = null
            }
        }
    }

    fun readCloudText(path: String) {
        val state = mutableState.value
        if (state.cloud.section != CloudSection.FILES) return
        val id = state.cloud.selectedServerId ?: return
        cloudTextJob?.cancel()
        val generation = ++cloudFileGeneration
        mutableState.update { it.copy(cloud = it.cloud.copy(busy = true, textServerId = null, textPath = null, textContent = "")) }
        cloudTextJob = viewModelScope.launch {
            try {
                val content = repository.readCloudText(id, path)
                mutableState.update { current ->
                    if (!isCurrentCloudFileRequest(current, id, generation)) current
                    else current.copy(cloud = current.cloud.copy(
                        busy = false,
                        textServerId = id,
                        textPath = path,
                        textContent = content,
                    ))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (isCurrentCloudFileRequest(mutableState.value, id, generation)) {
                    mutableState.update { it.copy(cloud = it.cloud.copy(busy = false)) }
                    handleError(error)
                }
            } finally {
                if (generation == cloudFileGeneration) cloudTextJob = null
            }
        }
    }

    fun updateCloudText(value: String) = mutableState.update {
        if (it.cloud.textServerId != it.cloud.selectedServerId) it
        else it.copy(cloud = it.cloud.copy(textContent = value))
    }

    fun closeCloudText() {
        cloudTextJob?.cancel()
        cloudTextJob = null
        cloudFileGeneration += 1
        cloudTextSaveGeneration += 1
        mutableState.update { it.copy(cloud = it.cloud.copy(
            busy = false,
            textServerId = null,
            textPath = null,
            textContent = "",
            savingText = false,
        )) }
    }

    private fun invalidateCloudFileRequests() {
        cloudFilesJob?.cancel()
        cloudTextJob?.cancel()
        cloudFilesJob = null
        cloudTextJob = null
        cloudFileGeneration += 1
        cloudTextSaveGeneration += 1
    }

    private fun isCurrentCloudFileRequest(state: AppUiState, serverId: String, generation: Long): Boolean =
        generation == cloudFileGeneration &&
            state.screen == AppScreen.INFINITE_CLOUD &&
            state.cloud.section == CloudSection.FILES &&
            state.cloud.selectedServerId == serverId

    fun saveCloudText() {
        val state = mutableState.value
        val id = state.cloud.textServerId ?: return
        if (id != state.cloud.selectedServerId) return
        val path = state.cloud.textPath ?: return
        val content = state.cloud.textContent
        val generation = ++cloudTextSaveGeneration
        mutableState.update { it.copy(cloud = it.cloud.copy(savingText = true)) }
        viewModelScope.launch {
            try {
                cloudTextWriteLock.withLock { repository.writeCloudText(id, path, content) }
                val current = mutableState.value
                if (generation == cloudTextSaveGeneration && current.cloud.textServerId == id && current.cloud.textPath == path) {
                    mutableState.update { it.copy(notice = UiText.Dynamic("Remote file saved")) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (generation == cloudTextSaveGeneration) handleError(error)
            } finally {
                if (generation == cloudTextSaveGeneration) {
                    mutableState.update { it.copy(cloud = it.cloud.copy(savingText = false)) }
                }
            }
        }
    }

    fun cloudFileOperation(operation: String, values: Map<String, String>, expectedServerId: String? = null) = viewModelScope.launch {
        val snapshot = mutableState.value
        val id = expectedServerId ?: snapshot.cloud.selectedServerId ?: return@launch
        if (snapshot.cloud.selectedServerId != id) return@launch
        runCatching { repository.cloudFileOperation(id, operation, values) }
            .onSuccess {
                val current = mutableState.value
                if (current.cloud.selectedServerId == id && current.cloud.section == CloudSection.FILES) loadCloudFiles()
            }.onFailure(::handleError)
    }

    fun uploadCloudFile(
        fileName: String,
        input: InputStream,
        expectedServerId: String? = null,
        expectedDirectory: String? = null,
    ) = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
        input.use { source ->
            val state = mutableState.value
            val id = expectedServerId ?: state.cloud.selectedServerId ?: return@use
            if (state.cloud.selectedServerId != id) return@use
            val directory = expectedDirectory ?: state.cloud.currentPath
            val remote = directory.trimEnd('/') + "/" + fileName.replace('/', '_')
            try {
                repository.uploadCloudFile(id, remote, source)
                val current = mutableState.value
                if (current.cloud.selectedServerId == id && current.cloud.section == CloudSection.FILES) loadCloudFiles()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                handleError(error)
            }
        }
    }

    fun downloadCloudFile(path: String, output: OutputStream, expectedServerId: String? = null) = viewModelScope.launch {
        output.use { target ->
            val currentId = mutableState.value.cloud.selectedServerId
            val id = expectedServerId ?: currentId ?: return@use
            if (currentId != id) return@use
            runCatching { repository.downloadCloudFileTo(id, path, target) }
                .onSuccess { mutableState.update { it.copy(notice = uiText(R.string.cloud_download_complete)) } }
                .onFailure(::handleError)
        }
    }

    fun refreshCloudTask(id: String) = viewModelScope.launch {
        runCatching { repository.refreshCloudTask(id) }.onSuccess { loadWorkspace() }.onFailure(::handleError)
    }

    fun loadCloudTaskLog(id: String) {
        val snapshot = mutableState.value
        if (snapshot.screen != AppScreen.INFINITE_CLOUD || snapshot.cloud.section != CloudSection.TASKS) return
        if (snapshot.cloudTasks.none { it.id == id }) return
        cloudTaskLogJob?.cancel()
        val generation = ++cloudTaskLogGeneration
        cloudTaskLogJob = viewModelScope.launch {
            try {
                val log = repository.cloudTaskLog(id)
                val current = mutableState.value
                if (generation == cloudTaskLogGeneration &&
                    current.screen == AppScreen.INFINITE_CLOUD &&
                    current.cloud.section == CloudSection.TASKS &&
                    current.cloudTasks.any { it.id == id }
                ) {
                    mutableState.update { it.copy(cloud = it.cloud.copy(taskLogId = id, taskLog = log)) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (generation == cloudTaskLogGeneration) handleError(error)
            } finally {
                if (generation == cloudTaskLogGeneration) cloudTaskLogJob = null
            }
        }
    }

    fun closeCloudTaskLog() {
        invalidateCloudTaskLogRequest()
        mutableState.update { it.copy(cloud = it.cloud.copy(taskLogId = null, taskLog = "")) }
    }

    private fun invalidateCloudTaskLogRequest() {
        cloudTaskLogGeneration += 1
        cloudTaskLogJob?.cancel()
        cloudTaskLogJob = null
    }
    fun cancelCloudTask(id: String) = viewModelScope.launch {
        runCatching { repository.cancelCloudTask(id) }.onSuccess { loadWorkspace() }.onFailure(::handleError)
    }

    fun deleteCloudTasks(ids: Set<String>) = refreshAfter { repository.deleteCloudTasks(ids) }

    fun retryCloudArtifactDelivery(id: String) {
        if (id in mutableState.value.cloud.retryingArtifactDeliveryIds) return
        mutableState.update {
            it.copy(cloud = it.cloud.copy(
                retryingArtifactDeliveryIds = it.cloud.retryingArtifactDeliveryIds + id,
            ))
        }
        viewModelScope.launch {
            try {
                val updated = repository.retryCloudArtifactDelivery(id)
                mutableState.update { state ->
                    state.copy(cloudArtifactDeliveries = state.cloudArtifactDeliveries.map {
                        if (it.id == updated.id) updated else it
                    })
                }
                loadWorkspace()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                handleError(error)
            } finally {
                mutableState.update {
                    it.copy(cloud = it.cloud.copy(
                        retryingArtifactDeliveryIds = it.cloud.retryingArtifactDeliveryIds - id,
                    ))
                }
            }
        }
    }

    fun saveCloudMcpServer(value: CloudMcpServer, environment: Map<String, String>, headers: Map<String, String>) {
        if (mutableState.value.cloud.let { it.testingMcpServerId == value.id || it.mcpTestServerId == value.id }) {
            invalidateCloudMcpTestRequest()
            mutableState.update { it.copy(cloud = it.cloud.withoutMcpTest()) }
        }
        mutableState.update { it.copy(cloud = it.cloud.copy(
            mutatingMcpServerIds = it.cloud.mutatingMcpServerIds + value.id,
        )) }
        viewModelScope.launch {
            try {
                cloudMcpMutationLocks.computeIfAbsent(value.id) { Mutex() }.withLock {
                    repository.saveCloudMcpServer(value, environment, headers)
                }
                loadWorkspace()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                handleError(error)
            } finally {
                mutableState.update { state -> state.copy(cloud = state.cloud.copy(
                    mutatingMcpServerIds = state.cloud.mutatingMcpServerIds - value.id,
                )) }
            }
        }
    }

    fun deleteCloudMcpServer(id: String) {
        if (mutableState.value.cloud.let { it.testingMcpServerId == id || it.mcpTestServerId == id }) {
            invalidateCloudMcpTestRequest()
            mutableState.update { it.copy(cloud = it.cloud.withoutMcpTest()) }
        }
        mutableState.update { it.copy(cloud = it.cloud.copy(
            mutatingMcpServerIds = it.cloud.mutatingMcpServerIds + id,
        )) }
        viewModelScope.launch {
            try {
                cloudMcpMutationLocks.computeIfAbsent(id) { Mutex() }.withLock {
                    repository.deleteCloudMcpServer(id)
                }
                loadWorkspace()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                handleError(error)
            } finally {
                mutableState.update { state -> state.copy(cloud = state.cloud.copy(
                    mutatingMcpServerIds = state.cloud.mutatingMcpServerIds - id,
                )) }
            }
        }
    }

    fun testCloudMcpServer(mcp: CloudMcpServer) {
        val snapshot = mutableState.value
        val serverId = snapshot.cloud.selectedServerId ?: return
        if (snapshot.screen != AppScreen.INFINITE_CLOUD || snapshot.cloud.section != CloudSection.MCP) return
        if (mcp.cloudServerId != serverId || snapshot.cloudMcpServers.none { it.id == mcp.id && it.cloudServerId == serverId }) return
        cloudMcpTestJob?.cancel()
        val generation = ++cloudMcpTestGeneration
        mutableState.update { it.copy(cloud = it.cloud.copy(
            testingMcpServerId = mcp.id,
            mcpTestName = null,
            mcpTestTools = emptyList(),
            mcpTestCloudServerId = null,
            mcpTestServerId = null,
            mcpTestServerUpdatedAt = null,
        )) }
        cloudMcpTestJob = viewModelScope.launch {
            try {
                val tools = repository.testCloudMcpServer(mcp.id)
                val current = mutableState.value
                if (isCurrentCloudMcpTest(current, mcp, serverId, generation)) {
                    mutableState.update { it.copy(cloud = it.cloud.copy(
                        testingMcpServerId = null,
                        mcpTestName = mcp.name,
                        mcpTestTools = tools,
                        mcpTestCloudServerId = serverId,
                        mcpTestServerId = mcp.id,
                        mcpTestServerUpdatedAt = mcp.updatedAt,
                    )) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (isCurrentCloudMcpTest(mutableState.value, mcp, serverId, generation)) {
                    mutableState.update { it.copy(cloud = it.cloud.copy(testingMcpServerId = null)) }
                    handleError(error)
                }
            } finally {
                if (generation == cloudMcpTestGeneration) cloudMcpTestJob = null
            }
        }
    }

    fun closeCloudMcpTest() = mutableState.update { it.copy(cloud = it.cloud.withoutMcpTest()) }

    private fun CloudWorkspaceUiState.withoutMcpTest() = copy(
        testingMcpServerId = null,
        mcpTestName = null,
        mcpTestTools = emptyList(),
        mcpTestCloudServerId = null,
        mcpTestServerId = null,
        mcpTestServerUpdatedAt = null,
    )

    private fun invalidateCloudMcpTestRequest() {
        cloudMcpTestGeneration += 1
        cloudMcpTestJob?.cancel()
        cloudMcpTestJob = null
    }

    private fun isCurrentCloudMcpTest(
        state: AppUiState,
        mcp: CloudMcpServer,
        serverId: String,
        generation: Long,
    ): Boolean = generation == cloudMcpTestGeneration &&
        state.screen == AppScreen.INFINITE_CLOUD &&
        state.cloud.section == CloudSection.MCP &&
        state.cloud.selectedServerId == serverId &&
        state.cloud.testingMcpServerId == mcp.id &&
        mcp.cloudServerId == serverId &&
        state.cloudMcpServers.any {
            it.id == mcp.id && it.cloudServerId == serverId && it.updatedAt == mcp.updatedAt
        }

    fun setInfiniteCloud(enabled: Boolean, serverId: String?) {
        val state = mutableState.value
        saveToolSettings(
            search = state.enableSearch,
            read = state.enableRead,
            knowledge = state.enableKnowledge,
            process = state.showProcess,
            cloud = enabled,
            serverId = serverId,
        )
    }

    fun reportCameraCaptureFailure() = mutableState.update {
        it.copy(notice = uiText(R.string.camera_capture_failed))
    }

    fun reportCloudFileError(error: Throwable) = mutableState.update {
        it.copy(notice = error.message?.takeIf(String::isNotBlank)?.let(UiText::Dynamic)
            ?: uiText(R.string.file_operation_failed))
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
        saveConversationToolSettings(
            id,
            ConversationWriteRequest(enableSearch = search, enableRead = read, enableKnowledge = knowledge),
            applyResult = { updated ->
                mutableState.update { it.copy(conversations = upsertConversation(it.conversations, updated)) }
            },
        )
    }

    fun saveToolSettings(
        search: Boolean,
        read: Boolean,
        knowledge: Boolean,
        process: Boolean,
        cloud: Boolean,
        serverId: String?,
    ) {
        val initialState = mutableState.value
        val readyServerId = if (cloud) readyCloudServerId(serverId) else null
        if (cloud && readyServerId == null) {
            mutableState.update { it.copy(notice = uiText(R.string.cloud_select_ready_server)) }
            return
        }
        val cloudEnabled = cloud && readyServerId != null
        mutableState.update {
            it.copy(
                enableSearch = search,
                enableRead = read,
                enableKnowledge = knowledge,
                showProcess = process,
                config = it.config.copy(
                    enableSearch = search,
                    enableRead = read,
                    enableKnowledge = knowledge,
                    enableInfiniteCloud = cloudEnabled,
                    cloudServerId = readyServerId,
                ),
            )
        }
        val id = initialState.activeConversationId ?: return
        val previousConversation = initialState.conversations.firstOrNull { it.id == id }
        val applyPersisted: (Conversation) -> Unit = { updated ->
            mutableState.update {
                val conversations = upsertConversation(it.conversations, updated)
                if (it.activeConversationId != id) it.copy(conversations = conversations)
                else it.copy(
                    conversations = conversations,
                    config = updated.toConfig(),
                    enableSearch = updated.enableSearch,
                    enableRead = updated.enableRead,
                    enableKnowledge = updated.enableKnowledge,
                )
            }
        }
        saveConversationToolSettings(
            id,
            ConversationWriteRequest(
                enableSearch = search,
                enableRead = read,
                enableKnowledge = knowledge,
                enableInfiniteCloud = cloudEnabled,
                cloudServerId = readyServerId,
                updateCloudServerId = true,
            ),
            failureFallback = previousConversation,
            applyResult = applyPersisted,
            applyFailure = applyPersisted,
        )
    }

    private fun saveConversationToolSettings(
        conversationId: String,
        request: ConversationWriteRequest,
        failureFallback: Conversation? = null,
        applyResult: (Conversation) -> Unit,
        applyFailure: (Conversation) -> Unit = applyResult,
    ) {
        val generation = (toolSettingsGenerations[conversationId] ?: 0L) + 1L
        toolSettingsGenerations[conversationId] = generation
        toolSettingsJobs.remove(conversationId)?.cancel()
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val updated = repository.updateConversation(conversationId, request)
                if (toolSettingsGenerations[conversationId] == generation) applyResult(updated)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (toolSettingsGenerations[conversationId] == generation) {
                    val persisted = try {
                        repository.conversation(conversationId).conversation
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        failureFallback
                    }
                    if (toolSettingsGenerations[conversationId] == generation) {
                        persisted?.let(applyFailure)
                        handleError(error)
                    }
                }
            } finally {
                if (toolSettingsGenerations[conversationId] == generation) {
                    toolSettingsJobs.remove(conversationId)
                }
            }
        }
        toolSettingsJobs[conversationId] = job
        job.start()
    }

    private fun readyCloudServerId(serverId: String?): String? {
        val ready = mutableState.value.cloudServers.filter { it.keyConfigured && it.hostKeyFingerprint != null }
        return if (serverId == null) ready.firstOrNull()?.id else ready.firstOrNull { it.id == serverId }?.id
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

    fun saveAgent(agent: AgentProfile) {
        val serverId = if (agent.enableInfiniteCloud) readyCloudServerId(agent.cloudServerId) else null
        if (agent.enableInfiniteCloud && serverId == null) {
            mutableState.update { it.copy(notice = uiText(R.string.cloud_select_ready_server)) }
            return
        }
        refreshAfter {
            repository.saveAgent(agent.copy(
                enableInfiniteCloud = agent.enableInfiniteCloud && serverId != null,
                cloudServerId = serverId,
            ))
        }
    }

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
                composerDraftRecovery = null,
            )
        }
        viewModelScope.launch {
            val conversationId = initialConversationId ?: run {
                val created = runCatching { repository.createConversation(current.config.toRequest()) }
                    .getOrElse {
                        restoreOrDiscardTransferredAttachments(initialConversationId, transferredAttachments)
                        restoreComposerDraft(initialConversationId, request.requestId, content)
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
                restoreComposerDraft(conversationId, request.requestId, content)
                return@launch
            }
            launchGeneration(
                id = conversationId,
                transferredAttachments = transferredAttachments,
                transferredDraft = ComposerDraftRecovery(request.requestId, conversationId, content),
            ) { repository.sendMessage(conversationId, request) }
        }
    }

    fun consumeComposerDraftRecovery(requestId: String) = mutableState.update { state ->
        if (state.composerDraftRecovery?.requestId == requestId) state.copy(composerDraftRecovery = null) else state
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
                .onSuccess { loadWorkspace(onLoaded = ::onInitialWorkspaceLoaded) }
                .onFailure { error ->
                    mutableState.update { it.copy(phase = AppPhase.SETUP, screen = AppScreen.PROVIDERS, notice = readableError(error)) }
                }
        }
    }

    private fun loadWorkspace(
        openChatWhenReady: Boolean = false,
        onLoaded: (() -> Unit)? = null,
    ) {
        val loadVersion = ++workspaceLoadVersion
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true) }
            runCatching { repository.workspace() }
                .onSuccess { workspace ->
                    if (loadVersion != workspaceLoadVersion) return@onSuccess
                    val currentState = mutableState.value
                    val previousSelectedServerId = currentState.cloud.selectedServerId
                    val selectedServerId = previousSelectedServerId
                        ?.takeIf { selected -> workspace.cloudServers.any { it.id == selected } }
                        ?: workspace.cloudServers.firstOrNull()?.id
                    val previousSelectedServer = currentState.cloudServers.firstOrNull { it.id == previousSelectedServerId }
                    val selectedServer = workspace.cloudServers.firstOrNull { it.id == selectedServerId }
                    val selectedServerChanged = selectedServerId != previousSelectedServerId ||
                        (previousSelectedServer != null && selectedServer != null &&
                            !previousSelectedServer.hasSameCloudConnectionTarget(selectedServer))
                    val runningMcpTestInvalid = currentState.cloud.testingMcpServerId?.let { id ->
                        val previousMcp = currentState.cloudMcpServers.firstOrNull { it.id == id }
                        val currentMcp = workspace.cloudMcpServers.firstOrNull {
                            it.id == id && it.cloudServerId == selectedServerId
                        }
                        previousMcp == null || currentMcp == null || previousMcp.updatedAt != currentMcp.updatedAt
                    } == true
                    val displayedMcpTestInvalid = currentState.cloud.mcpTestServerId?.let { id ->
                        val currentMcp = workspace.cloudMcpServers.firstOrNull {
                            it.id == id && it.cloudServerId == selectedServerId
                        }
                        currentState.cloud.mcpTestCloudServerId != selectedServerId || currentMcp == null ||
                            currentMcp.updatedAt != currentState.cloud.mcpTestServerUpdatedAt
                    } == true
                    val mcpTestInvalid = selectedServerChanged || runningMcpTestInvalid || displayedMcpTestInvalid
                    val taskLogInvalid = currentState.cloud.taskLogId?.let { id ->
                        workspace.cloudTasks.none { it.id == id }
                    } == true
                    if (selectedServerChanged) {
                        cloudServerSelectionGeneration += 1
                        invalidateCloudFileRequests()
                    }
                    if (mcpTestInvalid) invalidateCloudMcpTestRequest()
                    if (taskLogInvalid) invalidateCloudTaskLogRequest()
                    mutableState.update { state ->
                        val hasModels = workspace.models.isNotEmpty()
                        val active = state.activeConversationId?.takeIf { id -> workspace.conversations.any { it.id == id } }
                        val pendingTrustServerId = state.cloud.pendingTrustServerId?.takeIf { id ->
                            val server = workspace.cloudServers.firstOrNull { it.id == id }
                            val probe = state.cloud.pendingProbe
                            server != null && probe != null && server.hostKeyBase64 == null &&
                                probe.host == server.host && probe.port == server.port
                        }
                        val pendingReplacement = state.cloud.pendingHostKeyReplacement?.takeIf { replacement ->
                            val server = workspace.cloudServers.firstOrNull { it.id == replacement.serverId }
                            server != null && server.hostKeyBase64 == replacement.expectedHostKeyBase64 &&
                                replacement.probe.host == server.host && replacement.probe.port == server.port
                        }
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
                            cloudServers = workspace.cloudServers,
                            cloudMcpServers = workspace.cloudMcpServers,
                            cloudTasks = workspace.cloudTasks,
                            cloudArtifactDeliveries = workspace.cloudArtifactDeliveries,
                            cloud = state.cloud.copy(
                                selectedServerId = selectedServerId,
                                taskServerFilterId = state.cloud.taskServerFilterId
                                    ?.takeIf { selected -> workspace.cloudServers.any { it.id == selected } },
                                currentPath = if (selectedServerChanged) {
                                    workspace.cloudServers.firstOrNull { it.id == selectedServerId }?.startDirectory ?: "~"
                                } else state.cloud.currentPath,
                                files = if (selectedServerChanged) emptyList() else state.cloud.files,
                                textServerId = if (selectedServerChanged) null else state.cloud.textServerId,
                                textPath = if (selectedServerChanged) null else state.cloud.textPath,
                                textContent = if (selectedServerChanged) "" else state.cloud.textContent,
                                savingText = if (selectedServerChanged) false else state.cloud.savingText,
                                busy = if (selectedServerChanged) false else state.cloud.busy,
                                taskLogId = if (taskLogInvalid) null else state.cloud.taskLogId,
                                taskLog = if (taskLogInvalid) "" else state.cloud.taskLog,
                                testingMcpServerId = if (mcpTestInvalid) null else state.cloud.testingMcpServerId,
                                mcpTestName = if (mcpTestInvalid) null else state.cloud.mcpTestName,
                                mcpTestTools = if (mcpTestInvalid) emptyList() else state.cloud.mcpTestTools,
                                mcpTestCloudServerId = if (mcpTestInvalid) null else state.cloud.mcpTestCloudServerId,
                                mcpTestServerId = if (mcpTestInvalid) null else state.cloud.mcpTestServerId,
                                mcpTestServerUpdatedAt = if (mcpTestInvalid) null else state.cloud.mcpTestServerUpdatedAt,
                                pendingTrustServerId = pendingTrustServerId,
                                pendingProbe = state.cloud.pendingProbe.takeIf { pendingTrustServerId != null },
                                pendingHostKeyReplacement = pendingReplacement,
                                serverDiagnostics = state.cloud.serverDiagnostics.filterKeys { id ->
                                    val old = state.cloudServers.firstOrNull { it.id == id }
                                    val current = workspace.cloudServers.firstOrNull { it.id == id }
                                    old != null && current != null && old.hasSameCloudProbeConfiguration(current)
                                },
                            ),
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
                    onLoaded?.invoke()
                }
                .onFailure { error ->
                    if (loadVersion == workspaceLoadVersion) {
                        mutableState.update { it.copy(loading = false, notice = readableError(error)) }
                    }
                }
        }
    }

    private fun onInitialWorkspaceLoaded() {
        workspaceReady = true
        syncCloudTasks()
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
        transferredDraft: ComposerDraftRecovery? = null,
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
                if (!userMessageAccepted) {
                    restoreOrDiscardTransferredAttachments(id, transferredAttachments)
                    transferredDraft?.let { draft ->
                        restoreComposerDraft(draft.conversationId, draft.requestId, draft.content)
                    }
                }
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

    private fun restoreComposerDraft(
        ownerConversationId: String?,
        requestId: String,
        content: String,
    ) {
        if (content.isEmpty()) return
        mutableState.update { state ->
            if (state.activeConversationId != ownerConversationId) state
            else state.copy(
                composerDraftRecovery = ComposerDraftRecovery(requestId, ownerConversationId, content),
            )
        }
    }

    private fun refreshAfter(action: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                action()
                loadWorkspace()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                handleError(error)
            }
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
    enableInfiniteCloud = enableInfiniteCloud,
    cloudServerId = cloudServerId,
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
    enableInfiniteCloud = enableInfiniteCloud,
    cloudServerId = cloudServerId,
    updateCloudServerId = true,
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
