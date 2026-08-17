package xyz.mek030399.tokenflow.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigImportTest {
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
        secrets.remove(keyName)
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
