package xyz.mek030399.tokenflow.data

import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProviderHeaderVisionTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: TokenFlowDatabase
    private lateinit var secrets: SecretStore
    private lateinit var repository: ChatRepository
    private lateinit var gateway: VisionGateway
    private lateinit var primary: ModelProfile
    private lateinit var fallback: ModelProfile
    private val providerIds = mutableListOf<String>()
    private val files = mutableListOf<File>()
    private val primaryHeaders = listOf(ProviderRequestHeader(name = "User-Agent", value = "primary-fixture"))
    private val fallbackHeaders = listOf(ProviderRequestHeader(name = "Authorization", value = "Bearer vision-fixture"))

    @Before fun setUp(): Unit = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, TokenFlowDatabase::class.java).build()
        secrets = SecretStore(context)
        gateway = VisionGateway()
        val localTools = object : ToolRunner {
            override fun definitions(enableSearch: Boolean, enableRead: Boolean) = emptyList<ToolDefinition>()
            override suspend fun execute(call: CanonicalToolCall, enableSearch: Boolean, enableRead: Boolean) =
                ToolExecutionResult("unused", false)
        }
        repository = ChatRepository(database.localDao(), secrets, gateway, DirectChatEngine(gateway, localTools),
            ConfigArchiveCodec(), attachmentStore = AttachmentStore(context, database.localDao()))
        val mainDraft = ProviderDraft(name = "Main fixture", baseUrl = "https://main.example/v1", apiKey = "main-fixture-key",
            requestHeaders = primaryHeaders)
        val visionDraft = ProviderDraft(name = "Vision fixture", baseUrl = "https://vision.example/v1",
            protocol = ProviderProtocol.ANTHROPIC_MESSAGES, requestHeaders = fallbackHeaders)
        providerIds += listOf(mainDraft.id, visionDraft.id)
        primary = ModelProfile(providerId = mainDraft.id, remoteId = "text-fixture", isDefault = true,
            visionStatus = VisionStatus.UNSUPPORTED)
        fallback = ModelProfile(providerId = visionDraft.id, remoteId = "vision-fixture", visionStatus = VisionStatus.SUPPORTED)
        repository.saveProvider(mainDraft, listOf(primary))
        repository.saveProvider(visionDraft, listOf(fallback))
        repository.saveGlobalSettings(GlobalChatSettings(defaultModelId = primary.id, visionFallbackModelId = fallback.id), null)
    }

    @After fun tearDown() {
        providerIds.forEach { id ->
            secrets.remove(secrets.providerKeyName(id))
            secrets.remove(secrets.providerHeadersName(id))
        }
        files.forEach { it.delete() }
        database.close()
    }

    @Test fun savedVisionTestUsesCustomAuthenticationWithoutAStoredApiKey() = runBlocking {
        assertEquals(VisionStatus.SUPPORTED, repository.testModelVision(fallback.id))
        val call = gateway.requests.single()
        assertEquals(fallback.providerId, call.provider.id)
        assertEquals("", call.apiKey)
        assertEquals(fallbackHeaders, call.requestHeaders)
        assertTrue(call.messages.single().contentParts().any { it is CanonicalContentPart.Image })
    }

    @Test fun imageDescriptionUsesFallbackProviderHeadersAndAnswerUsesPrimaryHeaders() = runBlocking {
        val root = File(context.cacheDir, CameraCaptureStore.DIRECTORY_NAME).apply { mkdirs() }
        val photo = File(root, "header-vision-${UUID.randomUUID()}.jpg").also { files += it }
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        photo.outputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.JPEG, 80, output) }
        bitmap.recycle()
        val pending = PendingAttachment(uri = "camera://header-fixture", displayName = photo.name, mimeType = "image/jpeg",
            sizeBytes = photo.length(), origin = PendingAttachmentOrigin.CAMERA, appOwnedDraftPath = photo.absolutePath)
        val conversation = repository.createConversation(ConversationWriteRequest(title = "Header vision fixture",
            model = primary.id, modelMode = SettingMode.OVERRIDE, maxToolCalls = 0))
        val events = repository.sendMessage(conversation.id, SendMessageRequest(content = "Describe the supplied image",
            enableSearch = false, enableRead = false, timeZone = "UTC", requestId = UUID.randomUUID().toString(),
            attachments = listOf(pending))).toList()
        events.filterIsInstance<ChatEvent.UserMessage>().flatMap { it.attachments }.forEach { files += File(it.storedPath) }
        assertEquals(2, gateway.requests.size)
        val description = gateway.requests.first()
        assertEquals(fallback.providerId, description.provider.id)
        assertEquals("", description.apiKey)
        assertEquals(fallbackHeaders, description.requestHeaders)
        val answer = gateway.requests.last()
        assertEquals(primary.providerId, answer.provider.id)
        assertEquals("main-fixture-key", answer.apiKey)
        assertEquals(primaryHeaders, answer.requestHeaders)
        assertTrue(answer.messages.none { message -> message.contentParts().any { it is CanonicalContentPart.Image } })
    }

    private class VisionGateway : ModelGateway() {
        val requests = mutableListOf<ModelCallRequest>()
        override fun stream(request: ModelCallRequest) = flow {
            requests += request
            emit(ModelStreamEvent.TextDelta(if (request.systemPrompt == InternalPrompts.VISION_TEST) "TOKENFLOW 73" else "Fixture image description"))
            emit(ModelStreamEvent.Completed)
        }
    }
}
