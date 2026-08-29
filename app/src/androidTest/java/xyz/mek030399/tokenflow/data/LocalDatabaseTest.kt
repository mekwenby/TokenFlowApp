package xyz.mek030399.tokenflow.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class LocalDatabaseTest {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        TokenFlowDatabase::class.java,
    )

    private lateinit var database: TokenFlowDatabase
    private lateinit var dao: LocalDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            TokenFlowDatabase::class.java,
        ).build()
        dao = database.localDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun conversationMessagesAndBookmarksCascadeAndBatchDelete() = runBlocking {
        seedModel()
        val first = Conversation(id = "conversation-1", model = "model-1")
        val second = Conversation(id = "conversation-2", model = "model-1")
        dao.putConversation(first.toEntity())
        dao.putConversation(second.toEntity())
        dao.putMessages(listOf(ChatMessage(id = "message-1", conversationId = first.id, role = "assistant", content = "hello").toEntity()))
        dao.putBookmark(BookmarkEntity("bookmark-1", "message-1", 1))

        dao.deleteConversations(listOf(first.id, second.id))

        assertTrue(dao.conversations().isEmpty())
        assertTrue(dao.messages(first.id).isEmpty())
        assertTrue(dao.bookmarks().isEmpty())
    }

    @Test
    fun noteFromTheSameMessageIsStoredOnlyOnce() = runBlocking {
        val first = Note(id = "note-1", body = "first", sourceMessageId = "message-1").toEntity()
        val duplicate = Note(id = "note-2", body = "duplicate", sourceMessageId = "message-1").toEntity()

        dao.putNoteIfSourceAbsent(first)
        val stored = dao.putNoteIfSourceAbsent(duplicate)

        assertEquals("note-1", stored.id)
        assertEquals(listOf("note-1"), dao.notes().map { it.id })
    }

    @Test
    fun importedNoteTextRemainsInKnowledgeAfterOriginalNoteIsDeleted() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val note = Note(
            id = "note-independent-copy",
            title = "Independent copy",
            body = "# Durable knowledge\n\nRoom 数据库迁移 remains searchable.",
        )
        dao.putNote(note.toEntity())
        val store = KnowledgeStore(context, dao)
        val document = store.importText(note.title, "", note.body, sourceNoteId = note.id)
        val storedFile = File(document.storedPath)

        try {
            assertEquals("ready", document.status)
            assertEquals(note.id, document.sourceNoteId)
            assertEquals(note.id, dao.knowledgeDocumentForSourceNote(note.id)?.sourceNoteId)
            assertEquals("Independent copy.md", document.name)
            assertEquals("text/markdown", document.mimeType)
            assertEquals(note.body.toByteArray(Charsets.UTF_8).size.toLong(), document.sizeBytes)
            assertEquals(note.body, storedFile.readText(Charsets.UTF_8))

            dao.deleteNote(note.id)

            assertNull(dao.note(note.id))
            assertTrue(storedFile.isFile)
            val result = store.search("durable knowledge").single()
            assertEquals(document.id, result.documentId)
            assertTrue(result.text.contains("Room 数据库迁移"))
        } finally {
            store.delete(document.id)
        }
        assertTrue(!storedFile.exists())
    }

    @Test
    fun knowledgePreviewReadsCanonicalizedPrivateSourceAndReportsTruncation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = "knowledge-preview-${System.nanoTime()}"
        val directory = File(context.filesDir, "knowledge").also(File::mkdirs)
        val file = File(directory, "$id.txt")
        val now = System.currentTimeMillis()
        dao.putKnowledgeDocument(
            KnowledgeDocumentEntity(
                id = id,
                name = "Preview.txt",
                mimeType = "text/plain",
                storedPath = file.absolutePath,
                sizeBytes = 0,
                status = "ready",
                error = "",
                chunkCount = 0,
                createdAt = now,
                updatedAt = now,
            ),
        )
        val store = KnowledgeStore(context, dao)

        try {
            file.writeText("  first\r\nsecond\rthird  ", Charsets.UTF_8)
            val canonical = requireNotNull(store.preview(id))
            assertEquals(id, canonical.documentId)
            assertEquals("Preview.txt", canonical.documentName)
            assertEquals("txt", canonical.extension)
            assertEquals("first\nsecond\nthird", canonical.text)
            assertFalse(canonical.truncated)

            file.writeText("x".repeat(KnowledgeStore.MAX_TEXT_CHARS), Charsets.UTF_8)
            val exactLimit = requireNotNull(store.preview(id))
            assertEquals(KnowledgeStore.MAX_TEXT_CHARS, exactLimit.text.length)
            assertFalse(exactLimit.truncated)

            file.appendText("y", Charsets.UTF_8)
            val truncated = requireNotNull(store.preview(id))
            assertEquals(KnowledgeStore.MAX_TEXT_CHARS, truncated.text.length)
            assertTrue(truncated.text.all { it == 'x' })
            assertTrue(truncated.truncated)
        } finally {
            dao.deleteKnowledgeDocument(id)
            file.delete()
        }
    }

    @Test
    fun knowledgePreviewRejectsMissingAndNonPrivateFiles() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = KnowledgeStore(context, dao)
        val now = System.currentTimeMillis()
        val missingId = "knowledge-preview-missing-${System.nanoTime()}"
        val outsideId = "knowledge-preview-outside-${System.nanoTime()}"
        val missing = File(context.filesDir, "knowledge/$missingId.txt")
        val outside = File(context.cacheDir, "$outsideId.txt").apply { writeText("outside") }
        dao.putKnowledgeDocument(
            KnowledgeDocumentEntity(
                missingId, "Missing.txt", "text/plain", missing.absolutePath, 0, "ready", "", 0, now, now,
            ),
        )
        dao.putKnowledgeDocument(
            KnowledgeDocumentEntity(
                outsideId, "Outside.txt", "text/plain", outside.absolutePath, outside.length(), "ready", "", 0, now, now,
            ),
        )

        try {
            assertNull(store.preview(missingId))
            assertNull(store.preview(outsideId))
        } finally {
            dao.deleteKnowledgeDocument(missingId)
            dao.deleteKnowledgeDocument(outsideId)
            outside.delete()
        }
    }

    @Test
    fun knowledgePreviewParseFailureDoesNotChangeDocumentState() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = "knowledge-preview-corrupt-${System.nanoTime()}"
        val file = File(context.filesDir, "knowledge/$id.pdf")
        file.parentFile?.mkdirs()
        file.writeText("not a PDF", Charsets.UTF_8)
        val now = System.currentTimeMillis()
        dao.putKnowledgeDocument(
            KnowledgeDocumentEntity(
                id, "Corrupt.pdf", "application/pdf", file.absolutePath, file.length(), "ready", "", 0, now, now,
            ),
        )
        val store = KnowledgeStore(context, dao)

        try {
            assertTrue(runCatching { store.preview(id) }.isFailure)
            val unchanged = requireNotNull(dao.knowledgeDocument(id))
            assertEquals("ready", unchanged.status)
            assertEquals("", unchanged.error)
        } finally {
            dao.deleteKnowledgeDocument(id)
            file.delete()
        }
    }

    @Test
    fun conditionalNoteSummaryUpdateDoesNotOverwriteANewerEdit() = runBlocking {
        val original = Note(id = "note-summary-race", title = "Original", body = "Original body", updatedAt = 10)
        val edited = original.copy(title = "Edited", body = "User edit", updatedAt = 11)
        dao.putNote(original.toEntity())
        dao.putNote(edited.toEntity())

        val changed = dao.updateNoteIfUnchanged(
            id = original.id,
            expectedTitle = original.title,
            expectedBody = original.body,
            expectedUpdatedAt = original.updatedAt,
            newTitle = "Generated",
            newBody = "Generated summary",
            newUpdatedAt = 12,
        )

        assertEquals(0, changed)
        assertEquals(edited, dao.note(original.id)?.toDomain())
    }

    @Test
    fun startupMarksGeneratingMessagesInterrupted() = runBlocking {
        seedModel()
        val conversation = Conversation(id = "conversation-1", model = "model-1", status = "generating")
        dao.putConversation(conversation.toEntity())
        dao.putMessages(listOf(ChatMessage(id = "assistant-1", conversationId = conversation.id, role = "assistant", status = "generating").toEntity()))

        val interrupted = dao.generatingMessages().map { it.copy(status = "interrupted") }
        dao.putMessages(interrupted)
        dao.interruptGeneratingConversations()

        assertEquals("interrupted", dao.message("assistant-1")?.status)
        assertEquals("idle", dao.conversation(conversation.id)?.status)
    }

    @Test
    fun deletingProviderKeepsConversationAndClearsMissingModel() = runBlocking {
        seedModel()
        val conversation = Conversation(id = "conversation-1", model = "model-1")
        dao.putConversation(conversation.toEntity())

        dao.deleteProvider("provider-1")

        assertNull(dao.conversation(conversation.id)?.modelId)
        assertTrue(dao.models().isEmpty())
    }

    @Test
    fun updatingProviderAndModelPreservesConversationModelReference() = runBlocking {
        seedModel()
        val conversation = Conversation(id = "conversation-1", model = "model-1")
        dao.putConversation(conversation.toEntity())

        dao.saveProviderWithModels(
            ProviderConfig("provider-1", "Renamed", "https://api.example.com/v1", ProviderProtocol.OPENAI_RESPONSES).toEntity(),
            listOf(ModelProfile("model-1", "provider-1", "model-a", "Renamed model").toEntity()),
        )

        assertEquals("model-1", dao.conversation(conversation.id)?.modelId)
        assertEquals("Renamed model", dao.model("model-1")?.displayName)
    }

    @Test
    fun globalSettingsAndConversationOverridesRemainIndependent() = runBlocking {
        seedModel()
        dao.putAppSettings(
            AppSettingsEntity(
                systemPrompt = "global prompt",
                urlReaderBackend = UrlReaderBackend.INFOFLOW.name,
            ),
        )
        val inherited = Conversation(id = "conversation-inherit")
        val overridden = Conversation(
            id = "conversation-override",
            model = "model-1",
            modelMode = SettingMode.OVERRIDE,
            systemPrompt = "local prompt",
            systemPromptMode = SettingMode.OVERRIDE,
            urlReaderBackend = UrlReaderBackend.BUILT_IN,
        )
        dao.putConversation(inherited.toEntity())
        dao.putConversation(overridden.toEntity())

        assertEquals("global prompt", dao.appSettings()?.systemPrompt)
        assertEquals(SettingMode.INHERIT, dao.conversation(inherited.id)?.toDomain()?.modelMode)
        assertEquals("model-1", dao.conversation(overridden.id)?.toDomain()?.model)
        assertEquals(UrlReaderBackend.BUILT_IN, dao.conversation(overridden.id)?.toDomain()?.urlReaderBackend)

        dao.deleteProvider("provider-1")
        assertEquals("model-1", dao.conversation(overridden.id)?.modelOverrideId)
        assertEquals("model-1", dao.conversation(overridden.id)?.toDomain()?.model)
    }

    @Test
    fun urlDiagnosticDelegatesValidationToReaderWithoutResolvingOnCallerThread() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val secrets = SecretStore(context)
        val gateway = ModelGateway()
        var receivedUrl = ""
        val reader = object : UrlContentReader {
            override suspend fun read(rawUrl: String): UrlReadResult {
                receivedUrl = rawUrl
                if (rawUrl.endsWith("/cancel")) throw kotlinx.coroutines.CancellationException("cancelled")
                return UrlReadResult(
                    content = "page",
                    source = "infoflow",
                    finalUrl = "https://resolved.example/page",
                )
            }
        }
        val repository = ChatRepository(
            dao = dao,
            secretStore = secrets,
            gateway = gateway,
            engine = DirectChatEngine(gateway, WebToolExecutor(secrets, ExaClient(), UrlReader(context))),
            archive = ConfigArchiveCodec(),
            infoFlowReader = reader,
        )

        val result = repository.testUrl("  https://does-not-resolve.invalid/page  ")

        assertEquals("https://does-not-resolve.invalid/page", receivedUrl)
        assertTrue(result.success)
        assertEquals("infoflow", result.source)
        assertEquals("https://resolved.example/page", result.finalUrl)

        var cancelled = false
        try {
            repository.testUrl("https://does-not-resolve.invalid/cancel")
        } catch (_: kotlinx.coroutines.CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }

    @Test
    fun migrationOneToSevenPreservesMessagesAndAddsCloudTables() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-${System.nanoTime()}.db"
        context.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE providers (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, baseUrl TEXT NOT NULL, protocol TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
                        db.execSQL("CREATE TABLE models (id TEXT NOT NULL PRIMARY KEY, providerId TEXT NOT NULL, remoteId TEXT NOT NULL, displayName TEXT NOT NULL, maxOutputTokens INTEGER NOT NULL, isDefault INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, FOREIGN KEY(providerId) REFERENCES providers(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                        db.execSQL("CREATE INDEX index_models_providerId ON models(providerId)")
                        db.execSQL("CREATE INDEX index_models_providerId_remoteId ON models(providerId, remoteId)")
                        db.execSQL("CREATE TABLE conversations (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, titleAutoGenerated INTEGER NOT NULL, modelId TEXT, thinkingEffort TEXT NOT NULL, systemPrompt TEXT NOT NULL, nickname TEXT NOT NULL, userAvatar TEXT NOT NULL, assistantAvatar TEXT NOT NULL, maxToolCalls INTEGER NOT NULL, status TEXT NOT NULL, statusMessage TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, lastMessageAt INTEGER, FOREIGN KEY(modelId) REFERENCES models(id) ON UPDATE NO ACTION ON DELETE SET NULL)")
                        db.execSQL("CREATE INDEX index_conversations_modelId ON conversations(modelId)")
                        db.execSQL("CREATE INDEX index_conversations_updatedAt ON conversations(updatedAt)")
                        db.execSQL("CREATE TABLE messages (id TEXT NOT NULL PRIMARY KEY, conversationId TEXT NOT NULL, parentMessageId TEXT, requestId TEXT NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, metadata TEXT NOT NULL, status TEXT NOT NULL, createdAt INTEGER NOT NULL, FOREIGN KEY(conversationId) REFERENCES conversations(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                        db.execSQL("CREATE INDEX index_messages_conversationId ON messages(conversationId)")
                        db.execSQL("CREATE INDEX index_messages_conversationId_createdAt ON messages(conversationId, createdAt)")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        helper.writableDatabase.apply {
            execSQL("INSERT INTO providers VALUES('p','Provider','https://api.example.com/v1','OPENAI_RESPONSES',1,1)")
            execSQL("INSERT INTO models VALUES('default','p','default','Default',4096,1,1,2)")
            execSQL("INSERT INTO models VALUES('other','p','other','Other',4096,0,1,1)")
            execSQL("INSERT INTO conversations VALUES('inherit','',0,'default','medium','','','U','AI',7,'idle','',1,1,NULL)")
            execSQL("INSERT INTO conversations VALUES('override','',0,'other','medium','custom','','U','AI',7,'idle','',1,1,NULL)")
            execSQL("INSERT INTO messages VALUES('m','override',NULL,'r','user','kept','','completed',2)")
        }
        helper.close()

        val migrated = Room.databaseBuilder(context, TokenFlowDatabase::class.java, name)
            .addMigrations(
                TokenFlowDatabase.MIGRATION_1_2,
                TokenFlowDatabase.MIGRATION_2_3,
                TokenFlowDatabase.MIGRATION_3_4,
                TokenFlowDatabase.MIGRATION_4_5,
                TokenFlowDatabase.MIGRATION_5_6,
                TokenFlowDatabase.MIGRATION_6_7,
            )
            .build()
        try {
            val migratedDao = migrated.localDao()
            assertEquals(SettingMode.INHERIT, migratedDao.conversation("inherit")?.toDomain()?.modelMode)
            assertEquals(SettingMode.INHERIT, migratedDao.conversation("inherit")?.toDomain()?.systemPromptMode)
            assertEquals(SettingMode.OVERRIDE, migratedDao.conversation("override")?.toDomain()?.modelMode)
            assertEquals("other", migratedDao.conversation("override")?.modelOverrideId)
            assertEquals("kept", migratedDao.messages("override").single().content)
            assertEquals(UrlReaderBackend.BUILT_IN.name, migratedDao.appSettings()?.urlReaderBackend)
            assertTrue(migratedDao.conversation("override")?.enableSearch == true)
            assertTrue(migratedDao.conversation("override")?.enableRead == true)
            assertTrue(migratedDao.bookmarks().isEmpty())
            assertTrue(migratedDao.notes().isEmpty())
            assertTrue(migratedDao.agents().isEmpty())
            assertEquals(VisionStatus.UNKNOWN.name, migratedDao.model("default")?.visionStatus)
            assertEquals("mimo_default", migratedDao.appSettings()?.mimoTtsVoice)
            assertEquals(DEFAULT_ASSISTANT_NICKNAME, migratedDao.appSettings()?.assistantNickname)
            assertTrue(migratedDao.attachmentsForMessage("m").isEmpty())
            assertTrue(migratedDao.conversation("override")?.enableInfiniteCloud == false)
            assertTrue(migratedDao.cloudServers().isEmpty())
            assertTrue(migratedDao.cloudMcpServers().isEmpty())
            assertTrue(migratedDao.cloudTasks().isEmpty())
            assertTrue(migratedDao.cloudArtifactDeliveries().isEmpty())
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun deletingCloudServerUnbindsProfilesAndPreservesTaskHistory() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val secrets = SecretStore(context)
        val server = CloudServerProfile(id = "cloud-delete", name = "Deleted host", host = "host.example", username = "runner")
        dao.putCloudServer(server.toEntity())
        dao.putCloudMcpServer(CloudMcpServer(id = "mcp-delete", cloudServerId = server.id, name = "Tools", command = "tool").toEntity())
        dao.putConversation(Conversation(id = "cloud-conversation", enableInfiniteCloud = true, cloudServerId = server.id).toEntity())
        dao.putAgent(AgentProfile(id = "cloud-agent", name = "Cloud agent", enableInfiniteCloud = true, cloudServerId = server.id).toEntity())
        dao.putCloudTaskMonotonic(CloudTask(
            id = "cloud-task-history",
            cloudServerId = server.id,
            serverName = server.name,
            kind = "python",
            status = CloudTaskStatus.SUCCEEDED,
        ).toEntity())
        val gateway = ModelGateway()
        val cloud = InfiniteCloudManager(context, dao, secrets)
        val repository = ChatRepository(
            dao = dao,
            secretStore = secrets,
            gateway = gateway,
            engine = DirectChatEngine(gateway, WebToolExecutor(secrets, ExaClient(), UrlReader(context))),
            archive = ConfigArchiveCodec(),
            infiniteCloud = cloud,
        )

        repository.deleteCloudServer(server.id)

        assertNull(dao.cloudServer(server.id))
        assertTrue(dao.cloudMcpServers(server.id).isEmpty())
        assertFalse(dao.conversation("cloud-conversation")!!.enableInfiniteCloud)
        assertNull(dao.conversation("cloud-conversation")!!.cloudServerId)
        assertFalse(dao.agent("cloud-agent")!!.enableInfiniteCloud)
        assertNull(dao.agent("cloud-agent")!!.cloudServerId)
        val history = dao.cloudTask("cloud-task-history")!!
        assertNull(history.cloudServerId)
        assertEquals("Deleted host", history.serverName)
    }

    @Test
    fun deletingCloudServerIsBlockedByUnknownTask() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val server = CloudServerProfile(id = "cloud-active", name = "Active host", host = "host.example", username = "runner")
        dao.putCloudServer(server.toEntity())
        dao.putCloudTaskMonotonic(CloudTask(
            id = "cloud-task-unknown",
            cloudServerId = server.id,
            serverName = server.name,
            kind = "shell",
            status = CloudTaskStatus.UNKNOWN,
        ).toEntity())
        val secrets = SecretStore(context)
        val gateway = ModelGateway()
        val repository = ChatRepository(
            dao = dao,
            secretStore = secrets,
            gateway = gateway,
            engine = DirectChatEngine(gateway, WebToolExecutor(secrets, ExaClient(), UrlReader(context))),
            archive = ConfigArchiveCodec(),
            infiniteCloud = InfiniteCloudManager(context, dao, secrets),
        )

        assertTrue(runCatching { repository.deleteCloudServer(server.id) }.isFailure)
        assertTrue(dao.cloudServer(server.id) != null)
    }

    @Test
    fun cloudTaskWritesAdvanceMonotonically() = runBlocking {
        val unknown = CloudTask(
            id = "cloud-task-monotonic",
            serverName = "Server",
            kind = "shell",
            summary = "pending",
            status = CloudTaskStatus.UNKNOWN,
            createdAt = 1,
            updatedAt = 100,
        ).toEntity()
        dao.putCloudTaskMonotonic(unknown)

        val running = dao.putCloudTaskMonotonic(
            unknown.copy(
                summary = "running",
                status = CloudTaskStatus.RUNNING.name,
                updatedAt = 20,
            ),
        )
        val cancelled = dao.putCloudTaskMonotonic(
            running.copy(
                summary = "cancelled",
                status = CloudTaskStatus.CANCELLED.name,
                updatedAt = 30,
            ),
        )
        dao.putCloudTaskMonotonic(
            cancelled.copy(
                summary = "late running",
                status = CloudTaskStatus.RUNNING.name,
                updatedAt = 200,
            ),
        )

        val stored = dao.cloudTask(unknown.id)!!
        assertEquals(CloudTaskStatus.CANCELLED.name, stored.status)
        assertEquals("cancelled", stored.summary)
        assertEquals(100L, stored.updatedAt)
    }

    @Test
    fun deletingCloudTaskHistoryIsBatchableAndRejectsActiveTasks() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        listOf(
            CloudTask(id = "task-complete", serverName = "Server", kind = "shell", status = CloudTaskStatus.SUCCEEDED),
            CloudTask(id = "task-failed", serverName = "Server", kind = "python", status = CloudTaskStatus.FAILED),
            CloudTask(id = "task-running", serverName = "Server", kind = "javascript", status = CloudTaskStatus.RUNNING),
            CloudTask(id = "task-unknown", serverName = "Server", kind = "shell", status = CloudTaskStatus.UNKNOWN),
        ).forEach { dao.putCloudTaskMonotonic(it.toEntity()) }
        val secrets = SecretStore(context)
        val gateway = ModelGateway()
        val repository = ChatRepository(
            dao = dao,
            secretStore = secrets,
            gateway = gateway,
            engine = DirectChatEngine(gateway, WebToolExecutor(secrets, ExaClient(), UrlReader(context))),
            archive = ConfigArchiveCodec(),
        )

        repository.deleteCloudTasks(setOf("task-complete", "task-failed"))

        assertNull(dao.cloudTask("task-complete"))
        assertNull(dao.cloudTask("task-failed"))
        assertTrue(dao.cloudTask("task-running") != null)
        assertTrue(runCatching { repository.deleteCloudTasks(setOf("task-running")) }.isFailure)
        assertTrue(runCatching { repository.deleteCloudTasks(setOf("task-unknown")) }.isFailure)
        assertTrue(dao.cloudTask("task-running") != null)
        assertTrue(dao.cloudTask("task-unknown") != null)
    }

    @Test
    fun deletingCompletedTaskQueuesItsArtifactsBeforeRemovingHistory() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val server = CloudServerProfile(
            id = "artifact-history-server",
            name = "Artifact server",
            host = "host.example",
            username = "runner",
        )
        val conversation = Conversation(id = "artifact-history-conversation")
        val message = ChatMessage(
            id = "artifact-history-message",
            conversationId = conversation.id,
            requestId = "artifact-history-request",
            role = "assistant",
        )
        val task = CloudTask(
            id = "artifact-history-task",
            cloudServerId = server.id,
            serverName = server.name,
            conversationId = conversation.id,
            requestId = message.requestId,
            kind = "python",
            status = CloudTaskStatus.SUCCEEDED,
            artifactPaths = listOf("/workspace/one/result.txt", "/workspace/two/result.txt"),
        )
        dao.putCloudServer(server.toEntity())
        dao.putConversation(conversation.toEntity())
        dao.putMessages(listOf(message.toEntity()))
        dao.putCloudTaskMonotonic(task.toEntity())
        val secrets = SecretStore(context)
        val gateway = ModelGateway()
        val repository = ChatRepository(
            dao = dao,
            secretStore = secrets,
            gateway = gateway,
            engine = DirectChatEngine(gateway, WebToolExecutor(secrets, ExaClient(), UrlReader(context))),
            archive = ConfigArchiveCodec(),
        )

        repository.deleteCloudTasks(setOf(task.id))

        assertNull(dao.cloudTask(task.id))
        val deliveries = dao.cloudArtifactDeliveriesForMessage(message.id)
        assertEquals(2, deliveries.size)
        assertTrue(deliveries.all { it.status == CloudArtifactDeliveryStatus.PENDING.name })
        assertTrue(deliveries.all { it.taskId == null })
        assertEquals(2, deliveries.map(CloudArtifactDeliveryEntity::displayName).distinct().size)
    }

    @Test
    fun cloudArtifactQueueUpsertIsMonotonicAndAllocatesNamesAtomically() = runBlocking {
        val conversation = Conversation(id = "artifact-upsert-conversation")
        val message = ChatMessage(id = "artifact-upsert-message", conversationId = conversation.id, role = "assistant")
        dao.putConversation(conversation.toEntity())
        dao.putMessages(listOf(message.toEntity()))
        val first = CloudArtifactDelivery(
            id = "artifact-upsert-first",
            messageId = message.id,
            sourceType = CloudArtifactSourceType.MCP,
            sourceIdentity = "artifact-upsert-source-one",
            localCachePath = "/private/one/result.txt",
            displayName = "result.txt",
            attachmentId = "artifact-upsert-attachment-one",
        ).toEntity()
        val second = first.copy(
            id = "artifact-upsert-second",
            sourceIdentity = "artifact-upsert-source-two",
            localCachePath = "/private/two/result.txt",
            attachmentId = "artifact-upsert-attachment-two",
        )

        val queued = dao.putCloudArtifactDelivery(first)
        val delivered = dao.putCloudArtifactDelivery(
            queued.copy(
                status = CloudArtifactDeliveryStatus.DELIVERED.name,
                localCachePath = null,
                deliveredAt = 20,
                updatedAt = 20,
            ),
        )
        val stale = dao.putCloudArtifactDelivery(first.copy(updatedAt = 30))
        val disambiguated = dao.putCloudArtifactDelivery(second)

        assertEquals(CloudArtifactDeliveryStatus.DELIVERED.name, delivered.status)
        assertEquals(CloudArtifactDeliveryStatus.DELIVERED.name, stale.status)
        assertEquals("result.txt", queued.displayName)
        assertTrue(disambiguated.displayName.startsWith("result-"))
        assertTrue(disambiguated.displayName.endsWith(".txt"))
    }

    @Test
    fun cloudArtifactDeliveryPersistsAndKeepsStableAttachmentIdentity() = runBlocking {
        val conversation = Conversation(id = "artifact-conversation")
        val message = ChatMessage(id = "artifact-message", conversationId = conversation.id, role = "assistant")
        dao.putConversation(conversation.toEntity())
        dao.putMessages(listOf(message.toEntity()))
        val delivery = CloudArtifactDelivery(
            id = "artifact-delivery",
            requestId = "request",
            messageId = message.id,
            sourceType = CloudArtifactSourceType.MCP,
            sourceIdentity = "mcp-source",
            localCachePath = "/private/cache/artifact.bin",
            displayName = "artifact.bin",
            attachmentId = "artifact-attachment",
        )

        dao.putCloudArtifactDelivery(delivery.toEntity())

        assertEquals(delivery, dao.cloudArtifactDelivery(delivery.id)?.toDomain())
        assertEquals(delivery, dao.pendingCloudArtifactDeliveries().single().toDomain())
        dao.deleteMessage(message.id)
        assertNull(dao.cloudArtifactDelivery(delivery.id))
    }

    @Test
    fun immediateArtifactIsQueuedBeforeGenerationFinalization() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val conversation = Conversation(id = "immediate-artifact-conversation")
        val message = ChatMessage(
            id = "immediate-artifact-message",
            conversationId = conversation.id,
            requestId = "immediate-artifact-request",
            role = "assistant",
        )
        dao.putConversation(conversation.toEntity())
        dao.putMessages(listOf(message.toEntity()))
        dao.putCloudServer(
            CloudServerProfile(
                id = "immediate-artifact-server",
                name = "Immediate artifact server",
                host = "host.example",
                username = "runner",
            ).toEntity(),
        )
        val cloud = InfiniteCloudManager(context, dao, SecretStore(context))

        cloud.registerResponseArtifact(
            serverId = "immediate-artifact-server",
            requestId = message.requestId,
            messageId = message.id,
            path = "/workspace/first/result.txt",
            resolve = false,
        )

        val queued = dao.pendingCloudArtifactDeliveries().single().toDomain()
        assertEquals(CloudArtifactDeliveryStatus.PENDING, queued.status)
        assertEquals("immediate-artifact-server", queued.cloudServerId)
        assertEquals("/workspace/first/result.txt", queued.remotePath)
        assertEquals(message.id, queued.messageId)
    }

    @Test
    fun migrationSixToSevenMatchesExportedSchemaIncludingArtifactQueue() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-cloud-six-seven-${System.nanoTime()}.db"
        context.deleteDatabase(name)
        migrationHelper.createDatabase(name, 6).close()

        val migrated = migrationHelper.runMigrationsAndValidate(
            name,
            7,
            true,
            TokenFlowDatabase.MIGRATION_6_7,
        )
        try {
            migrated.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'cloud_artifact_deliveries'",
            ).use { cursor -> assertTrue(cursor.moveToFirst()) }
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun preReleaseVersionEightHeaderDowngradesToSevenWithoutDataLoss() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-pre-release-eight-seven-${System.nanoTime()}.db"
        context.deleteDatabase(name)
        migrationHelper.createDatabase(name, 7).apply {
            execSQL(
                "INSERT INTO providers " +
                    "(id, name, baseUrl, protocol, createdAt, updatedAt) " +
                    "VALUES ('compat-provider', 'Compatibility', 'https://api.example.com/v1', " +
                    "'OPENAI_RESPONSES', 1, 1)",
            )
            execSQL("PRAGMA user_version = 8")
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            name,
            7,
            true,
            TokenFlowDatabase.PRE_RELEASE_DOWNGRADE_8_7,
        )
        try {
            migrated.query("PRAGMA user_version").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(7, cursor.getInt(0))
            }
            migrated.query(
                "SELECT name FROM providers WHERE id = 'compat-provider'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Compatibility", cursor.getString(0))
            }
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun failedMcpArtifactDeliveryRemainsRetryableUntilAttachmentIsPersisted() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val conversation = Conversation(id = "artifact-retry-conversation")
        val message = ChatMessage(id = "artifact-retry-message", conversationId = conversation.id, role = "assistant")
        dao.putConversation(conversation.toEntity())
        dao.putMessages(listOf(message.toEntity()))
        val cacheFile = File(context.cacheDir, "infinite_cloud_mcp_artifacts/retry-${System.nanoTime()}.bin")
        cacheFile.parentFile?.mkdirs()
        cacheFile.delete()
        val delivery = CloudArtifactDelivery(
            id = "artifact-retry-delivery",
            requestId = "artifact-retry-request",
            messageId = message.id,
            sourceType = CloudArtifactSourceType.MCP,
            sourceIdentity = "artifact-retry-source",
            localCachePath = cacheFile.absolutePath,
            displayName = "result.bin",
            attachmentId = "artifact-retry-attachment",
        )
        dao.putCloudArtifactDelivery(delivery.toEntity())
        val secrets = SecretStore(context)
        val gateway = ModelGateway()
        val repository = ChatRepository(
            dao = dao,
            secretStore = secrets,
            gateway = gateway,
            engine = DirectChatEngine(gateway, WebToolExecutor(secrets, ExaClient(), UrlReader(context))),
            archive = ConfigArchiveCodec(),
            attachmentStore = AttachmentStore(context, dao),
        )

        val failed = repository.retryCloudArtifactDelivery(delivery.id)
        assertEquals(CloudArtifactDeliveryStatus.FAILED, failed.status)
        assertEquals(1, failed.retryCount)
        assertTrue(dao.cloudArtifactDelivery(delivery.id) != null)

        cacheFile.writeBytes("artifact".encodeToByteArray())
        val delivered = repository.retryCloudArtifactDelivery(delivery.id)
        assertEquals(CloudArtifactDeliveryStatus.DELIVERED, delivered.status)
        assertEquals(delivery.attachmentId, dao.attachment(delivery.attachmentId)?.id)
        assertFalse(cacheFile.exists())
    }

    @Test
    fun migrationFiveToSixAddsDefaultAssistantNicknameAndPreservesSettings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-assistant-nickname-${System.nanoTime()}.db"
        context.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(5) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE app_settings (" +
                                "id INTEGER NOT NULL PRIMARY KEY, systemPrompt TEXT NOT NULL, " +
                                "userAvatar TEXT NOT NULL, assistantAvatar TEXT NOT NULL, " +
                                "urlReaderBackend TEXT NOT NULL, visionFallbackModelId TEXT, " +
                                "mimoTtsVoice TEXT NOT NULL DEFAULT 'mimo_default')",
                        )
                        db.execSQL(
                            "INSERT INTO app_settings(" +
                                "id,systemPrompt,userAvatar,assistantAvatar,urlReaderBackend," +
                                "visionFallbackModelId,mimoTtsVoice" +
                                ") VALUES(1,'kept prompt','U','AI','INFOFLOW',NULL,'mimo_default')",
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        val db = helper.writableDatabase
        try {
            TokenFlowDatabase.MIGRATION_5_6.migrate(db)

            db.query(
                "SELECT systemPrompt, urlReaderBackend, assistantNickname " +
                    "FROM app_settings WHERE id = 1",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("kept prompt", cursor.getString(0))
                assertEquals(UrlReaderBackend.INFOFLOW.name, cursor.getString(1))
                assertEquals(DEFAULT_ASSISTANT_NICKNAME, cursor.getString(2))
            }
        } finally {
            helper.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun migrationFourToFiveAddsNullableUniqueNoteSource() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-knowledge-${System.nanoTime()}.db"
        context.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE knowledge_documents (" +
                                "id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, mimeType TEXT NOT NULL, " +
                                "storedPath TEXT NOT NULL, sizeBytes INTEGER NOT NULL, status TEXT NOT NULL, " +
                                "error TEXT NOT NULL, chunkCount INTEGER NOT NULL, createdAt INTEGER NOT NULL, " +
                                "updatedAt INTEGER NOT NULL)",
                        )
                        db.execSQL(
                            "INSERT INTO knowledge_documents VALUES(" +
                                "'legacy-1','One.md','text/markdown','one',1,'ready','',1,1,1)",
                        )
                        db.execSQL(
                            "CREATE TABLE knowledge_chunks (" +
                                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, documentId TEXT NOT NULL, " +
                                "position INTEGER NOT NULL, text TEXT NOT NULL, searchText TEXT NOT NULL, " +
                                "FOREIGN KEY(documentId) REFERENCES knowledge_documents(id) " +
                                "ON UPDATE NO ACTION ON DELETE CASCADE)",
                        )
                        db.execSQL(
                            "INSERT INTO knowledge_chunks(documentId,position,text,searchText) " +
                                "VALUES('legacy-1',0,'legacy text','legacy text')",
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        val db = helper.writableDatabase
        try {
            TokenFlowDatabase.MIGRATION_4_5.migrate(db)

            db.query("SELECT sourceNoteId FROM knowledge_documents WHERE id = 'legacy-1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
            }
            db.query(
                "SELECT d.name, c.text FROM knowledge_documents d " +
                    "JOIN knowledge_chunks c ON c.documentId = d.id WHERE d.id = 'legacy-1'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("One.md", cursor.getString(0))
                assertEquals("legacy text", cursor.getString(1))
            }
            db.execSQL(
                "INSERT INTO knowledge_documents(" +
                    "id,name,mimeType,storedPath,sizeBytes,status,error,chunkCount,createdAt,updatedAt,sourceNoteId" +
                    ") VALUES('legacy-2','Two.md','text/markdown','two',1,'ready','',1,1,1,NULL)",
            )
            db.query("SELECT COUNT(*) FROM knowledge_documents WHERE sourceNoteId IS NULL").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(2, cursor.getInt(0))
            }
            db.execSQL("UPDATE knowledge_documents SET sourceNoteId = 'note-1' WHERE id = 'legacy-1'")
            val duplicateRejected = runCatching {
                db.execSQL("UPDATE knowledge_documents SET sourceNoteId = 'note-1' WHERE id = 'legacy-2'")
            }.isFailure
            assertTrue(duplicateRejected)
        } finally {
            helper.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun latestAssistantSupportsRegenerationReplacement() = runBlocking {
        seedModel()
        val conversation = Conversation(id = "conversation-1", model = "model-1")
        dao.putConversation(conversation.toEntity())
        dao.putMessages(
            listOf(
                ChatMessage(id = "user-1", conversationId = conversation.id, role = "user", content = "question", createdAt = 1).toEntity(),
                ChatMessage(id = "assistant-1", conversationId = conversation.id, role = "assistant", content = "old", createdAt = 2).toEntity(),
            ),
        )

        val latest = requireNotNull(dao.latestAssistant(conversation.id))
        dao.deleteMessage(latest.id)
        dao.putMessages(listOf(ChatMessage(id = "assistant-2", conversationId = conversation.id, role = "assistant", content = "new", createdAt = 3).toEntity()))

        assertEquals(listOf("user-1", "assistant-2"), dao.messages(conversation.id).map { it.id })
    }

    @Test
    fun workspaceEntitiesSupportBookmarkNotesAgentsAndBilingualFts() = runBlocking {
        seedModel()
        val conversation = Conversation(id = "conversation-workspace", model = "model-1")
        dao.putConversation(conversation.toEntity())
        dao.putMessages(listOf(ChatMessage(id = "assistant-workspace", conversationId = conversation.id, role = "assistant", content = "Room 数据迁移").toEntity()))
        dao.putBookmark(BookmarkEntity("bookmark-1", "assistant-workspace", 1))
        dao.putNote(Note(id = "note-1", title = "Migration", body = "Room schema").toEntity())
        dao.putAgent(AgentProfile(id = "agent-1", name = "Reviewer", modelId = "model-1").toEntity())
        dao.putKnowledgeDocument(KnowledgeDocumentEntity("document-1", "guide.md", "text/markdown", "private", 10, "ready", "", 1, 1, 1))
        dao.replaceKnowledgeChunks(
            "document-1",
            listOf(KnowledgeChunkEntity(documentId = "document-1", position = 0, text = "Room 数据迁移", searchText = KnowledgeStore.searchable("Room 数据迁移"))),
        )

        assertEquals("assistant-workspace", dao.bookmarks().single().messageId)
        assertEquals("Migration", dao.notes().single().title)
        assertEquals("Reviewer", dao.agents().single().name)
        assertEquals(1, dao.searchKnowledgeChunks("\"room\"", 5).size)
        assertEquals(1, dao.searchKnowledgeChunks("\"数据\"", 5).size)

        dao.deleteMessage("assistant-workspace")
        assertTrue(dao.bookmarks().isEmpty())
    }

    private suspend fun seedModel() {
        dao.putProvider(ProviderConfig("provider-1", "Provider", "https://api.example.com/v1", ProviderProtocol.OPENAI_RESPONSES).toEntity())
        dao.putModels(listOf(ModelProfile("model-1", "provider-1", "model-a").toEntity()))
    }
}
