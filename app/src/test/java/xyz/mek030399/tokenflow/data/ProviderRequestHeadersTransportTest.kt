package xyz.mek030399.tokenflow.data

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProviderRequestHeadersTransportTest {
    private lateinit var server: MockWebServer
    private lateinit var gateway: ModelGateway

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        gateway = ModelGateway()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun allStreamProtocolsUseCustomHeadersAndSuppressEveryDefaultAuthenticationHeader() = runTest {
        ProviderProtocol.entries.forEachIndexed { index, protocol ->
            val authName = listOf("Authorization", "x-api-key", "api-key")[index]
            val headers = listOf(
                header(authName, "custom-credential"),
                header("user-agent", "custom-client/1"),
                header("Accept", "application/custom-stream"),
                header("anthropic-version", "custom-version"),
                header("X-Client-Request-Id", "custom-request"),
                header("X-Extra", "extra"),
                header("Bad Header", "invalid\n").copy(enabled = false),
            )
            server.enqueue(sse(completed(protocol)))
            gateway.stream(request(protocol).copy(apiKey = "", requestHeaders = headers)).toList()
            val recorded = server.takeRequest()

            assertEquals("custom-credential", recorded.getHeader(authName))
            listOf("Authorization", "x-api-key", "api-key").filterNot { it.equals(authName, true) }.forEach {
                assertNull(recorded.getHeader(it))
            }
            assertEquals(1, recorded.headers.values(authName).size)
            assertEquals("custom-client/1", recorded.getHeader("User-Agent"))
            assertEquals("application/custom-stream", recorded.getHeader("Accept"))
            assertEquals("custom-version", recorded.getHeader("anthropic-version"))
            assertEquals("custom-request", recorded.getHeader("X-Client-Request-Id"))
            assertEquals("extra", recorded.getHeader("X-Extra"))
            assertEquals("application/json; charset=utf-8", recorded.getHeader("Content-Type"))
            val body = DirectApiTransport.defaultJson.parseToJsonElement(recorded.body.readUtf8()).jsonObject
            assertEquals("fixture-model", body.getValue("model").jsonPrimitive.content)
            assertFalse("requestHeaders" in body)
            assertFalse("request_headers" in body)
        }
    }

    @Test
    fun disabledAuthAndInvalidRowsDoNotReplaceNativeProtocolDefaults() = runTest {
        ProviderProtocol.entries.forEach { protocol ->
            server.enqueue(sse(completed(protocol)))
            gateway.stream(request(protocol).copy(requestHeaders = listOf(
                header("Authorization", "disabled-secret").copy(enabled = false),
                header("Bad Header", "invalid\n").copy(enabled = false),
            ))).toList()
            val recorded = server.takeRequest()
            if (protocol == ProviderProtocol.ANTHROPIC_MESSAGES) {
                assertEquals("native-key", recorded.getHeader("x-api-key"))
                assertNull(recorded.getHeader("Authorization"))
                assertEquals(DirectApiTransport.ANTHROPIC_VERSION, recorded.getHeader("anthropic-version"))
            } else assertEquals("Bearer native-key", recorded.getHeader("Authorization"))
            assertTrue(recorded.getHeader("User-Agent").orEmpty().startsWith("TokenFlow-Android/"))
            assertEquals("text/event-stream", recorded.getHeader("Accept"))
            assertEquals("request-1", recorded.getHeader("X-Client-Request-Id"))
        }
    }

    @Test
    fun savedModelListsAndAnthropicPaginationCarryCustomHeadersOnEveryPage() = runTest {
        val headers = listOf(header("api-key", "model-list-key"), header("User-Agent", "list-client"), header("X-List", "yes"))
        server.enqueue(json("""{"data":[{"id":"model-a"}]}"""))
        gateway.listModels(provider(ProviderProtocol.OPENAI_RESPONSES), "", headers)
        val openAi = server.takeRequest()
        assertEquals("model-list-key", openAi.getHeader("api-key"))
        assertEquals("list-client", openAi.getHeader("User-Agent"))
        assertNull(openAi.getHeader("Authorization"))
        assertEquals("/v1/models", openAi.path)

        server.enqueue(json("""{"data":[{"id":"claude-a"}],"has_more":true,"last_id":"claude-a"}"""))
        server.enqueue(json("""{"data":[{"id":"claude-b"}],"has_more":false}"""))
        gateway.listModels(provider(ProviderProtocol.ANTHROPIC_MESSAGES), "", headers)
        repeat(2) {
            val page = server.takeRequest()
            assertEquals("model-list-key", page.getHeader("api-key"))
            assertEquals("yes", page.getHeader("X-List"))
            assertNull(page.getHeader("x-api-key"))
            assertEquals(DirectApiTransport.ANTHROPIC_VERSION, page.getHeader("anthropic-version"))
        }
    }

    @Test
    fun unsavedDraftModelLookupUsesDraftHeadersWithAnOptionalApiKey() = runTest {
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"data":[{"id":"draft-model"}]}""".toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val draftGateway = ModelGateway(DirectApiTransport(client = client))
        val models = draftGateway.listModels(ProviderDraft(
            name = "Unsaved provider", baseUrl = "https://draft.example/v1", apiKey = "",
            requestHeaders = listOf(header("X-API-Key", "draft-key"), header("User-Agent", "draft-client")),
        ))
        assertEquals(listOf("draft-model"), models.map(RemoteModel::id))
        assertEquals("draft-key", requests.single().header("x-api-key"))
        assertEquals("draft-client", requests.single().header("User-Agent"))
        assertNull(requests.single().header("Authorization"))
    }

    @Test
    fun generatedTitleKeepsProviderHeadersWhenItRemovesToolsAndReasoning() = runTest {
        server.enqueue(sse("""data: {"choices":[{"delta":{"content":"Title"}}]}""" + "\n\n" + completed(ProviderProtocol.OPENAI_CHAT_COMPLETIONS)))
        val engine = DirectChatEngine(gateway, object : ToolRunner {
            override fun definitions(enableSearch: Boolean, enableRead: Boolean) = emptyList<ToolDefinition>()
            override suspend fun execute(call: CanonicalToolCall, enableSearch: Boolean, enableRead: Boolean) = error("Unused")
        })
        assertEquals("Title", engine.generateTitle(request(ProviderProtocol.OPENAI_CHAT_COMPLETIONS).copy(
            requestHeaders = listOf(header("X-Task", "title"), header("api-key", "title-key")),
        )))
        val recorded = server.takeRequest()
        assertEquals("title", recorded.getHeader("X-Task"))
        assertEquals("title-key", recorded.getHeader("api-key"))
        assertNull(recorded.getHeader("Authorization"))
    }

    @Test
    fun sameOriginGetRedirectPreservesStaticCustomHeaders() = runTest {
        val headers = ProviderHeaderTemplates.apply(listOf(header("api-key", "redirect-key")), ProviderHeaderTemplate.OPENCODE)
        val sessionId = headers.single { it.name == "x-opencode-session" }.value
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/v1/final-models"))
        server.enqueue(json("""{"data":[{"id":"redirect-model"}]}"""))

        assertEquals(listOf("redirect-model"), gateway.listModels(provider(ProviderProtocol.OPENAI_RESPONSES), "", headers).map(RemoteModel::id))
        repeat(2) {
            val recorded = server.takeRequest()
            assertEquals(sessionId, recorded.getHeader("x-opencode-session"))
            assertEquals("redirect-key", recorded.getHeader("api-key"))
        }
    }

    @Test
    fun sameOriginPostRedirectPreservesTheBodyAndHeaders() = runTest {
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "/v1/redirected-responses"))
        server.enqueue(sse(completed(ProviderProtocol.OPENAI_RESPONSES)))
        gateway.stream(request(ProviderProtocol.OPENAI_RESPONSES).copy(
            requestHeaders = listOf(header("api-key", "redirect-key"), header("X-Static", "static-value")),
        )).toList()
        val original = server.takeRequest()
        val redirected = server.takeRequest()
        assertEquals("/v1/redirected-responses", redirected.path)
        assertEquals(original.body.readUtf8(), redirected.body.readUtf8())
        assertEquals("redirect-key", redirected.getHeader("api-key"))
        assertEquals("static-value", redirected.getHeader("X-Static"))
    }

    @Test
    fun crossOriginGetRedirectIsBlockedBeforeAnyHttpRequestReachesTheTarget() = runTest {
        val target = MockWebServer()
        target.start()
        try {
            target.enqueue(json("""{"data":[]}"""))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", target.url("/capture")))
            val failure = runCatching {
                gateway.listModels(provider(ProviderProtocol.OPENAI_RESPONSES), "", listOf(header("api-key", "private-header-value")))
            }.exceptionOrNull()
            assertBlocked(failure)
            assertEquals("private-header-value", server.takeRequest().getHeader("api-key"))
            assertNull(target.takeRequest(200, TimeUnit.MILLISECONDS))
        } finally { target.shutdown() }
    }

    @Test
    fun crossOriginPostRedirectCannotTransmitCustomHeadersOrTheModelBody() = runTest {
        val target = MockWebServer()
        target.start()
        try {
            target.enqueue(sse(completed(ProviderProtocol.OPENAI_RESPONSES)))
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", target.url("/capture")))
            val failure = runCatching {
                gateway.stream(request(ProviderProtocol.OPENAI_RESPONSES).copy(
                    requestHeaders = listOf(header("api-key", "private-header-value")),
                )).toList()
            }.exceptionOrNull()
            assertBlocked(failure)
            assertTrue(server.takeRequest().body.readUtf8().contains("fixture-input"))
            assertNull(target.takeRequest(200, TimeUnit.MILLISECONDS))
        } finally { target.shutdown() }
    }

    @Test
    fun aDifferentHostOnTheSamePortIsAlsoACrossOriginRedirect() = runTest {
        val original = server.url("/capture")
        val changedHost = original.newBuilder()
            .host(if (original.host == "localhost") "127.0.0.1" else "localhost").build()
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", changedHost))
        val failure = runCatching {
            gateway.listModels(provider(ProviderProtocol.OPENAI_RESPONSES), "native-key")
        }.exceptionOrNull()
        assertBlocked(failure)
        server.takeRequest()
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun upstreamHttpAndStreamErrorsCannotEchoActiveHeaderValuesOrApiKeys() = runTest {
        val headers = listOf(header("X-Credential", "private-header-value"))
        val errorBody = """{"error":{"message":"native-key and private-header-value were rejected","code":"private-header-value"}}"""
        server.enqueue(MockResponse().setResponseCode(401).setBody(errorBody))
        val getFailure = runCatching {
            gateway.listModels(provider(ProviderProtocol.OPENAI_RESPONSES), "native-key", headers)
        }.exceptionOrNull() as ApiException
        assertSanitized(getFailure)
        server.takeRequest()

        server.enqueue(MockResponse().setResponseCode(401).setBody(errorBody))
        val postFailure = runCatching {
            gateway.stream(request(ProviderProtocol.OPENAI_RESPONSES).copy(requestHeaders = headers)).toList()
        }.exceptionOrNull() as ApiException
        assertSanitized(postFailure)
        server.takeRequest()

        ProviderProtocol.entries.forEach { protocol ->
            val streamedError = when (protocol) {
                ProviderProtocol.OPENAI_CHAT_COMPLETIONS -> "data: $errorBody\n\n"
                ProviderProtocol.OPENAI_RESPONSES -> "event: error\ndata: " + errorBody.dropLast(1) + ""","type":"error"}""" + "\n\n"
                ProviderProtocol.ANTHROPIC_MESSAGES -> """event: error""" + "\ndata: " +
                    """{"type":"error","error":{"type":"private-header-value","message":"native-key private-header-value"}}""" + "\n\n"
            }
            server.enqueue(sse(streamedError))
            val streamFailure = runCatching {
                gateway.stream(request(protocol).copy(requestHeaders = headers)).toList()
            }.exceptionOrNull() as ApiException
            assertSanitized(streamFailure)
            server.takeRequest()
        }
    }

    @Test
    fun malformedResponsesDoNotExposeRawResponseInRecoveredExceptionDetails() = runTest {
        server.enqueue(json("private-header-value not-json"))
        val failure = runCatching {
            gateway.listModels(provider(ProviderProtocol.OPENAI_RESPONSES), "native-key", listOf(header("X-Credential", "private-header-value")))
        }.exceptionOrNull()
        assertEquals("Provider returned invalid JSON", failure?.message)
        val recovered = requireNotNull(failure)
        val details = generateSequence(recovered) { it.cause }.joinToString("\n") { it.message.orEmpty() } +
            recovered.stackTraceToString()
        assertTrue(details.contains("Provider returned invalid JSON"))
        assertFalse(details.contains("private-header-value"))
        assertFalse(details.contains("not-json"))
        assertFalse(details.contains("native-key"))
        server.takeRequest()
    }

    private fun request(protocol: ProviderProtocol) = ModelCallRequest(
        model = ModelProfile(providerId = "provider", remoteId = "fixture-model"),
        provider = provider(protocol), apiKey = "native-key", systemPrompt = "fixture-system", thinkingEffort = "off",
        messages = listOf(CanonicalMessage("user", "fixture-input")), tools = emptyList(), requestId = "request-1",
    )

    private fun provider(protocol: ProviderProtocol) = ProviderConfig(
        id = "provider", name = "Fixture", baseUrl = server.url("/v1").toString().trimEnd('/'), protocol = protocol,
    )

    private fun header(name: String, value: String) = ProviderRequestHeader(name = name, value = value)
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun sse(body: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)
    private fun completed(protocol: ProviderProtocol): String = when (protocol) {
        ProviderProtocol.OPENAI_CHAT_COMPLETIONS -> "data: [DONE]\n\n"
        ProviderProtocol.OPENAI_RESPONSES -> """event: response.completed""" + "\ndata: " + """{"type":"response.completed","response":{}}""" + "\n\n"
        ProviderProtocol.ANTHROPIC_MESSAGES -> """event: message_stop""" + "\ndata: " + """{"type":"message_stop"}""" + "\n\n"
    }

    private fun assertBlocked(failure: Throwable?) {
        assertTrue("Expected origin guard to reject the redirect", failure is ApiException)
        failure as ApiException
        assertEquals("provider_redirect_blocked", failure.code)
        assertFalse(failure.retryable)
        assertEquals("Provider redirect to a different origin was blocked", failure.message)
    }

    private fun assertSanitized(failure: ApiException) {
        assertFalse(failure.message.contains("private-header-value"))
        assertFalse(failure.message.contains("native-key"))
        assertFalse(failure.code.orEmpty().contains("private-header-value"))
        assertTrue(failure.message.contains("[redacted]"))
    }
}
