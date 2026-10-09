package xyz.mek030399.tokenflow.data

import android.content.Context
import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import xyz.mek030399.tokenflow.background.AndroidGenerationRuntime
import xyz.mek030399.tokenflow.background.GenerationCoordinator

class AppContainer(context: Context) {
    val generationRuntime = AndroidGenerationRuntime(context.applicationContext as Application)
    val generationCoordinator = GenerationCoordinator(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), generationRuntime)
    @Suppress("unused")
    private val cameraCaptureStore = CameraCaptureStore(context)
    val json = DirectApiTransport.defaultJson
    private val database = TokenFlowDatabase.open(context)
    private val secrets = SecretStore(context)
    private val gateway = ModelGateway(DirectApiTransport(json), json)
    private val knowledgeStore = KnowledgeStore(context, database.localDao())
    internal val noteMarkdownFiles: NoteMarkdownFileAccess = NoteMarkdownFileStore(context)
    internal val shareDrafts = ShareDraftStore(context)
    private val exaClient = ExaClient(json)
    private val builtInUrlReader = UrlReader(context, json)
    private val infiniteCloud = InfiniteCloudManager(context, database.localDao(), secrets, json)
    private val webTools = WebToolExecutor(
        secretStore = secrets,
        exaClient = exaClient,
        urlReader = builtInUrlReader,
        json = json,
        infoFlowReader = InfoFlowUrlReader(builtIn = builtInUrlReader, json = json),
        knowledgeStore = knowledgeStore,
        infiniteCloudTools = InfiniteCloudToolExecutor(infiniteCloud, json),
        infiniteCloudMcp = InfiniteCloudMcpExecutor(infiniteCloud, json),
    )
    private val engine = DirectChatEngine(gateway, webTools)
    val repository: ChatDataSource = ChatRepository(
        dao = database.localDao(),
        secretStore = secrets,
        gateway = gateway,
        engine = engine,
        archive = ConfigArchiveCodec(json),
        json = json,
        avatarStore = LocalAvatarStore(context),
        knowledgeStore = knowledgeStore,
        exaClient = exaClient,
        attachmentStore = AttachmentStore(context, database.localDao()),
        mimoTtsClient = MimoTtsClient(context, secrets, json),
        infoFlowReader = InfoFlowUrlReader(builtIn = builtInUrlReader, json = json),
        infiniteCloud = infiniteCloud,
    )
}
