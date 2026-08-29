package xyz.mek030399.tokenflow.data

import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigArchiveTest {
    private val codec = ConfigArchiveCodec()
    private val payload = ConfigArchivePayload(
        createdAt = 1234L,
        providers = listOf(
            ConfigProviderRecord(
                ProviderConfig("provider-1", "OpenAI", "https://api.openai.com/v1", ProviderProtocol.OPENAI_RESPONSES, true),
                "sk-secret",
            ),
        ),
        models = listOf(ModelProfile("model-1", "provider-1", "gpt-test", "Test", 4096, true)),
        defaultModelId = "model-1",
        exaApiKey = "exa-secret",
        infoFlowApiKey = "infoflow-secret",
        globalSystemPrompt = "global prompt",
        globalUrlReaderBackend = UrlReaderBackend.INFOFLOW,
        agents = listOf(AgentProfile(id = "agent-1", name = "Reviewer", modelId = "model-1", systemPrompt = "Review code")),
        cloudServers = listOf(
            CloudServerProfile(
                id = "cloud-1",
                name = "Build host",
                host = "build.example.com",
                username = "runner",
                hostKeyAlgorithm = "ssh-ed25519",
                hostKeyBase64 = "AAAAC3NzaC1lZDI1NTE5AAAAITest",
                hostKeyFingerprint = "SHA256:fixed",
                keyConfigured = false,
            ),
        ),
        cloudMcpServers = listOf(
            CloudMcpServer(
                id = "mcp-1",
                cloudServerId = "cloud-1",
                name = "Build tools",
                command = "npx",
                arguments = listOf("example-mcp"),
                environmentNames = listOf("SERVICE_TOKEN"),
                secretsConfigured = false,
            ),
        ),
        globalAssistantNickname = "Archive assistant",
    )

    @Test
    fun encryptedArchiveRoundTripsWithoutPlaintextSecrets() {
        val encoded = codec.encode(payload, "correct horse battery".toCharArray())
        val serializedPayload = ConfigArchiveCodec.defaultJson.encodeToString(payload)

        assertFalse(encoded.contains("sk-secret"))
        assertFalse(encoded.contains("exa-secret"))
        assertFalse(encoded.contains("infoflow-secret"))
        assertTrue(encoded.contains("PBKDF2-HMAC-SHA256"))
        assertTrue(serializedPayload.contains("\"global_assistant_nickname\":\"Archive assistant\""))
        assertTrue(serializedPayload.contains("\"host_key_fingerprint\":\"SHA256:fixed\""))
        assertFalse(serializedPayload.contains("private_key"))
        assertFalse(serializedPayload.contains("passphrase"))
        assertFalse(serializedPayload.contains("environment_values"))
        assertFalse(serializedPayload.contains("header_values"))
        assertFalse(serializedPayload.contains("SERVICE_TOKEN\":"))
        assertEquals(payload, codec.decode(encoded, "correct horse battery".toCharArray()))
    }

    @Test
    fun wrongPasswordAndTamperingFailBeforeReturningPayload() {
        val encoded = codec.encode(payload, "correct horse battery".toCharArray())

        assertThrows(ConfigArchiveException::class.java) {
            codec.decode(encoded, "incorrect password".toCharArray())
        }
        val tampered = encoded.replace(Regex("\"ciphertext\":\"."), "\"ciphertext\":\"A")
        assertThrows(ConfigArchiveException::class.java) {
            codec.decode(tampered, "correct horse battery".toCharArray())
        }
    }

    @Test
    fun passwordMustContainAtLeastTenCharacters() {
        assertThrows(IllegalArgumentException::class.java) {
            codec.encode(payload, "short".toCharArray())
        }
    }

    @Test
    fun legacyPayloadWithoutAssistantNicknameDefaultsToNull() {
        val decoded = ConfigArchiveCodec.defaultJson.decodeFromString<ConfigArchivePayload>(
            """{"createdAt":1234,"providers":[],"models":[]}""",
        )

        assertNull(decoded.globalAssistantNickname)
    }
}
