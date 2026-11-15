package xyz.mek030399.tokenflow.data

import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInAgentsTest {
    @Test
    fun bundledPromptPreservesTheCompleteOriginalDocument() {
        val bytes = bundledPrompt().readBytes()

        assertEquals(115_266, bytes.size)
        assertEquals(44_471, bytes.toString(Charsets.UTF_8).length)
        assertEquals(
            "e826d964088bc0b5f9716ae22a4231b01bb708179a03d6e4f47b9a70d6acc7bb",
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
        )
        assertTrue(BuiltInAgents.isBuiltIn(BuiltInAgents.SYAN_ID))
        assertFalse(BuiltInAgents.isBuiltIn(UUID.randomUUID().toString()))
    }

    @Test
    fun allProtocolsTransmitTheCompleteBundledRolePrompt() = runTest {
        val prompt = SystemPrompts.compose(bundledPrompt().readText(Charsets.UTF_8), "", "Asia/Hong_Kong")
        val server = MockWebServer()
        server.start()

        try {
            val gateway = ModelGateway()
            ProviderProtocol.entries.forEach { protocol ->
                val completion = when (protocol) {
                    ProviderProtocol.OPENAI_CHAT_COMPLETIONS -> "data: [DONE]\n\n"
                    ProviderProtocol.OPENAI_RESPONSES ->
                        "event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{}}\n\n"
                    ProviderProtocol.ANTHROPIC_MESSAGES ->
                        "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"
                }
                server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(completion))
                gateway.stream(
                    ModelCallRequest(
                        model = ModelProfile(providerId = "syan-test", remoteId = "test-model"),
                        provider = ProviderConfig(
                            id = "syan-test",
                            name = "SYAN test",
                            baseUrl = server.url("/v1").toString().trimEnd('/'),
                            protocol = protocol,
                        ),
                        apiKey = "test-only-key",
                        systemPrompt = prompt,
                        thinkingEffort = "off",
                        messages = listOf(CanonicalMessage("user", "下班了吗？")),
                        tools = emptyList(),
                        requestId = "syan-${protocol.name}",
                    ),
                ).toList()

                val body = DirectApiTransport.defaultJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
                val transmitted = when (protocol) {
                    ProviderProtocol.OPENAI_CHAT_COMPLETIONS -> body.getValue("messages").jsonArray
                        .first().jsonObject.getValue("content").jsonPrimitive.content
                    ProviderProtocol.OPENAI_RESPONSES -> body.getValue("instructions").jsonPrimitive.content
                    ProviderProtocol.ANTHROPIC_MESSAGES -> body.getValue("system").jsonPrimitive.content
                }
                assertEquals(protocol.name, prompt, transmitted)
            }
        } finally {
            server.shutdown()
        }
    }

    private fun bundledPrompt(): File = listOf(
        File("src/main/res/raw/syan_system_prompt.md"),
        File("app/src/main/res/raw/syan_system_prompt.md"),
    ).firstOrNull(File::isFile) ?: error("Bundled SYAN prompt is missing")
}
