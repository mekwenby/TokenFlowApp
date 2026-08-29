package xyz.mek030399.tokenflow.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigImportTest {
    @Test
    fun previewRejectsDuplicateMcpNamesForOneCloudServer() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, TokenFlowDatabase::class.java).build()
        val dao = database.localDao()
        val secrets = SecretStore(context)
        val server = CloudServerProfile(id = "preview-cloud", name = "Cloud", host = "host.example", username = "runner")
        val payload = ConfigArchivePayload(
            createdAt = 1,
            providers = emptyList(),
            models = emptyList(),
            cloudServers = listOf(server),
            cloudMcpServers = listOf(
                CloudMcpServer(id = "preview-mcp-1", cloudServerId = server.id, name = "Tools", command = "tool-a"),
                CloudMcpServer(id = "preview-mcp-2", cloudServerId = server.id, name = "Tools", command = "tool-b"),
            ),
        )
        val password = "archive-password".toCharArray()
        val encoded = ConfigArchiveCodec().encode(payload, password)

        try {
            assertTrue(runCatching { repository(context, dao, secrets).previewImport(encoded, password) }.isFailure)
        } finally {
            database.close()
        }
    }

    @Test
    fun cloudImportUpdatesConfigurationAndRequiresCredentialsAgain() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, TokenFlowDatabase::class.java).build()
        val dao = database.localDao()
        val secrets = SecretStore(context)
        val suffix = System.nanoTime()
        val server = CloudServerProfile(
            id = "cloud-import-$suffix",
            name = "Imported cloud",
            host = "old.example",
            username = "runner",
        )
        val mcp = CloudMcpServer(
            id = "mcp-import-$suffix",
            cloudServerId = server.id,
            name = "Tools",
            command = "old-tool",
            environmentNames = listOf("TOKEN"),
        )
        val omittedMcp = CloudMcpServer(
            id = "mcp-omitted-$suffix",
            cloudServerId = server.id,
            name = "Existing tools",
            command = "existing-tool",
            environmentNames = listOf("OMITTED_TOKEN"),
            headerNames = listOf("Authorization"),
        )
        dao.putCloudServer(server.toEntity())
        dao.putCloudMcpServer(mcp.toEntity())
        dao.putCloudMcpServer(omittedMcp.toEntity())
        val privateKeyName = secrets.cloudPrivateKeyName(server.id)
        val passphraseName = secrets.cloudPrivateKeyPassphraseName(server.id)
        val environmentName = secrets.cloudMcpEnvironmentName(mcp.id, "TOKEN")
        val omittedEnvironmentName = secrets.cloudMcpEnvironmentName(omittedMcp.id, "OMITTED_TOKEN")
        val omittedHeaderName = secrets.cloudMcpHeaderName(omittedMcp.id, "Authorization")
        secrets.writeAll(mapOf(
            privateKeyName to "private",
            passphraseName to "passphrase",
            environmentName to "token",
            omittedEnvironmentName to "omitted-token",
            omittedHeaderName to "omitted-header",
        ))

        try {
            repository(context, dao, secrets).applyImport(preview(ConfigArchivePayload(
                createdAt = 1,
                providers = emptyList(),
                models = emptyList(),
                cloudServers = listOf(server.copy(host = "new.example")),
                cloudMcpServers = listOf(mcp.copy(command = "new-tool")),
            )))

            assertEquals("new.example", dao.cloudServer(server.id)?.host)
            assertEquals("new-tool", dao.cloudMcpServers(server.id).first { it.id == mcp.id }.command)
            assertEquals(null, secrets.read(privateKeyName))
            assertEquals(null, secrets.read(passphraseName))
            assertEquals(null, secrets.read(environmentName))
            assertEquals("existing-tool", dao.cloudMcpServers(server.id).first { it.id == omittedMcp.id }.command)
            assertEquals(null, secrets.read(omittedEnvironmentName))
            assertEquals(null, secrets.read(omittedHeaderName))
        } finally {
            secrets.remove(privateKeyName)
            secrets.remove(passphraseName)
            secrets.remove(environmentName)
            secrets.remove(omittedEnvironmentName)
            secrets.remove(omittedHeaderName)
            database.close()
        }
    }

    @Test
    fun importChoosesFallbackThenExplicitDefaultAndMergesStableIds() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, TokenFlowDatabase::class.java).build()
        val dao = database.localDao()
        val secrets = SecretStore(context)
        val suffix = System.nanoTime()
        val providerId = "import-default-provider-$suffix"
        val firstModelId = "import-default-first-$suffix"
        val secondModelId = "import-default-second-$suffix"
        val provider = ProviderConfig(
            providerId,
            "Imported provider",
            "https://api.example.com/v1",
            ProviderProtocol.OPENAI_RESPONSES,
        )
        val first = ModelProfile(firstModelId, providerId, "model-first", "First")
        val second = ModelProfile(secondModelId, providerId, "model-second", "Second")
        val repository = repository(context, dao, secrets)

        try {
            repository.applyImport(preview(ConfigArchivePayload(
                createdAt = 1,
                providers = listOf(ConfigProviderRecord(provider, "import-key")),
                models = listOf(first, second),
            )))

            assertEquals(firstModelId, dao.defaultModel()?.id)
            assertEquals(setOf(firstModelId, secondModelId), dao.models().map { it.id }.toSet())

            repository.applyImport(preview(ConfigArchivePayload(
                createdAt = 2,
                providers = listOf(ConfigProviderRecord(provider.copy(name = "Updated provider"), "updated-key")),
                models = listOf(first.copy(displayName = "Updated first"), second),
                defaultModelId = secondModelId,
            )))

            assertEquals(secondModelId, dao.defaultModel()?.id)
            assertEquals(1, dao.providers().size)
            assertEquals(2, dao.models().size)
            assertEquals("Updated provider", dao.provider(providerId)?.name)
            assertEquals("Updated first", dao.model(firstModelId)?.displayName)
            assertEquals("updated-key", secrets.read(secrets.providerKeyName(providerId)))
        } finally {
            secrets.remove(secrets.providerKeyName(providerId))
            database.close()
        }
    }

    @Test
    fun importAppliesAssistantNicknameWhileLegacyPayloadPreservesCurrentValue() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, TokenFlowDatabase::class.java).build()
        val dao = database.localDao()
        val secrets = SecretStore(context)
        val repository = repository(context, dao, secrets)

        try {
            repository.applyImport(preview(ConfigArchivePayload(
                createdAt = 1,
                providers = emptyList(),
                models = emptyList(),
                globalAssistantNickname = "Imported assistant",
            )))
            assertEquals("Imported assistant", repository.globalSettings().assistantNickname)

            repository.applyImport(preview(ConfigArchivePayload(
                createdAt = 2,
                providers = emptyList(),
                models = emptyList(),
                globalSystemPrompt = "Imported legacy prompt",
            )))
            val settings = repository.globalSettings()
            assertEquals("Imported assistant", settings.assistantNickname)
            assertEquals("Imported legacy prompt", settings.systemPrompt)

            repository.applyImport(preview(ConfigArchivePayload(
                createdAt = 3,
                providers = emptyList(),
                models = emptyList(),
                globalAssistantNickname = "   ",
            )))
            assertEquals(DEFAULT_ASSISTANT_NICKNAME, repository.globalSettings().assistantNickname)
        } finally {
            database.close()
        }
    }

    @Test
    fun failedDatabaseMergeRestoresSecretsAndWritesNoPartialRows() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, TokenFlowDatabase::class.java).build()
        val dao = database.localDao()
        val secrets = SecretStore(context)
        val providerId = "import-rollback-${System.nanoTime()}"
        val keyName = secrets.providerKeyName(providerId)
        val original = ProviderConfig(providerId, "Original", "https://api.example.com/v1", ProviderProtocol.OPENAI_RESPONSES)
        dao.putProvider(original.toEntity())
        secrets.write(keyName, "old-key")
        val cloudServer = CloudServerProfile(
            id = "import-cloud-$providerId",
            name = "Cloud",
            host = "old.example",
            username = "runner",
        )
        val cloudMcp = CloudMcpServer(
            id = "import-mcp-$providerId",
            cloudServerId = cloudServer.id,
            name = "Tools",
            command = "old-tool",
            environmentNames = listOf("TOKEN"),
            headerNames = listOf("Authorization"),
        )
        dao.putCloudServer(cloudServer.toEntity())
        dao.putCloudMcpServer(cloudMcp.toEntity())
        val privateKeyName = secrets.cloudPrivateKeyName(cloudServer.id)
        val environmentName = secrets.cloudMcpEnvironmentName(cloudMcp.id, "TOKEN")
        val headerName = secrets.cloudMcpHeaderName(cloudMcp.id, "Authorization")
        secrets.writeAll(mapOf(privateKeyName to "old-private-key", environmentName to "old-token", headerName to "old-header"))
        val gateway = ModelGateway()
        val webTools = WebToolExecutor(secrets, ExaClient(), UrlReader(context))
        val repository = ChatRepository(
            dao,
            secrets,
            gateway,
            DirectChatEngine(gateway, webTools),
            ConfigArchiveCodec(),
        )
        val payload = ConfigArchivePayload(
            createdAt = 1,
            providers = listOf(ConfigProviderRecord(original.copy(name = "Imported"), "new-key")),
            models = listOf(ModelProfile("invalid-model", "missing-provider", "model-a")),
            cloudServers = listOf(cloudServer.copy(host = "new.example")),
            cloudMcpServers = listOf(cloudMcp.copy(command = "new-tool")),
        )
        val preview = ImportPreview(
            payload = payload,
            newProviders = 0,
            updatedProviders = 1,
            newModels = 1,
            updatedModels = 0,
            replacesExaKey = false,
        )

        var failed = false
        try {
            repository.applyImport(preview)
        } catch (_: Throwable) {
            failed = true
        }

        assertTrue(failed)
        assertEquals("old-key", secrets.read(keyName))
        assertEquals("Original", dao.provider(providerId)?.name)
        assertEquals(null, dao.model("invalid-model"))
        assertEquals("old.example", dao.cloudServer(cloudServer.id)?.host)
        assertEquals("old-tool", dao.cloudMcpServers(cloudServer.id).single().command)
        assertEquals("old-private-key", secrets.read(privateKeyName))
        assertEquals("old-token", secrets.read(environmentName))
        assertEquals("old-header", secrets.read(headerName))
        secrets.remove(keyName)
        secrets.remove(privateKeyName)
        secrets.remove(environmentName)
        secrets.remove(headerName)
        database.close()
    }

    private fun repository(context: android.content.Context, dao: LocalDao, secrets: SecretStore): ChatRepository {
        val gateway = ModelGateway()
        return ChatRepository(
            dao,
            secrets,
            gateway,
            DirectChatEngine(gateway, WebToolExecutor(secrets, ExaClient(), UrlReader(context))),
            ConfigArchiveCodec(),
        )
    }

    private fun preview(payload: ConfigArchivePayload) = ImportPreview(
        payload = payload,
        newProviders = payload.providers.size,
        updatedProviders = 0,
        newModels = payload.models.size,
        updatedModels = 0,
        replacesExaKey = payload.exaApiKey != null,
    )
}
