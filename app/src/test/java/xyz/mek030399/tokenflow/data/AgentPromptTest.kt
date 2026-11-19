package xyz.mek030399.tokenflow.data

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentPromptTest {
    @Test
    fun allProtocolsTransmitTheCompleteLongCustomRolePrompt() = runTest {
        val rolePrompt = "# Custom role\n" + "角色说明。".repeat(8_000) + "\nEnd of custom role."
        val prompt = SystemPrompts.compose(rolePrompt, "", "Asia/Hong_Kong")
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
                        model = ModelProfile(providerId = "custom-agent-test", remoteId = "test-model"),
                        provider = ProviderConfig(
                            id = "custom-agent-test",
                            name = "Custom agent test",
                            baseUrl = server.url("/v1").toString().trimEnd('/'),
                            protocol = protocol,
                        ),
                        apiKey = "test-only-key",
                        systemPrompt = prompt,
                        thinkingEffort = "off",
                        messages = listOf(CanonicalMessage("user", "请使用配置的角色说明。")),
                        tools = emptyList(),
                        requestId = "custom-agent-${protocol.name}",
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
}
