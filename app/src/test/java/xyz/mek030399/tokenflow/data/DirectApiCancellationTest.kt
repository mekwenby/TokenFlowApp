package xyz.mek030399.tokenflow.data

import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectApiCancellationTest {
    @Test(timeout = 15_000)
    fun cancelsHttpCallWhileWaitingForResponseHeaders() = assertPromptCancellation(
        MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE),
    )

    @Test(timeout = 15_000)
    fun cancelsHttpCallAfterReceivingFirstStreamEvent() = assertPromptCancellation(
        MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody(FIRST_EVENT + "data: [DONE]\n\n")
            .throttleBody(FIRST_EVENT.encodeToByteArray().size.toLong(), 3, TimeUnit.SECONDS),
        awaitFirstEvent = true,
    )

    @Test(timeout = 15_000)
    fun engineCancellationDoesNotRetryProviderRequest() = assertPromptCancellation(
        MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE),
        throughEngine = true,
    )

    private fun assertPromptCancellation(
        response: MockResponse,
        awaitFirstEvent: Boolean = false,
        throughEngine: Boolean = false,
    ) = runBlocking {
        val server = MockWebServer()
        val call = AtomicReference<Call>()
        val failure = AtomicReference<Throwable>()
        val firstEventReceived = CountDownLatch(1)
        val engineEvents = CopyOnWriteArrayList<EngineEvent>()
        val client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun callStart(startedCall: Call) { call.set(startedCall) }
        }).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        server.start()
        server.enqueue(response)
        val provider = ProviderConfig(
            id = "provider",
            name = "Test",
            baseUrl = server.url("/v1").toString(),
            protocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
            apiKeyConfigured = true,
        )
        val transport = DirectApiTransport(client = client)
        val operation = scope.launch {
            try {
                if (throughEngine) {
                    val engine = DirectChatEngine(ModelGateway(transport), NoTools)
                    engine.run(
                        ModelCallRequest(
                            model = ModelProfile(providerId = provider.id, remoteId = "test-model"),
                            provider = provider,
                            apiKey = "test-key",
                            systemPrompt = "system",
                            thinkingEffort = "off",
                            messages = listOf(CanonicalMessage("user", "hello")),
                            tools = emptyList(),
                            requestId = "cancel-test",
                        ),
                        enableSearch = false,
                        enableRead = false,
                        maxToolCalls = 0,
                    ).collect { engineEvents += it }
                } else {
                    transport.stream(provider, "test-key", "chat/completions", JsonObject(emptyMap()), "cancel-test")
                        .collect { firstEventReceived.countDown() }
                }
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        try {
            assertNotNull("The HTTP request did not start", server.takeRequest(3, TimeUnit.SECONDS))
            if (awaitFirstEvent) assertTrue("The first SSE event was not delivered", firstEventReceived.await(3, TimeUnit.SECONDS))
            operation.cancel()
            withTimeout(2_000) { operation.join() }

            assertTrue("Cancellation did not reach OkHttp", call.get().isCanceled())
            assertTrue("Socket cancellation escaped as a transport failure: ${failure.get()}", failure.get() is CancellationException)
            assertEquals(1, server.requestCount)
            assertFalse(engineEvents.filterIsInstance<EngineEvent.Process>().any { it.event.messageKey == "retrying" })
        } finally {
            scope.cancel()
            client.dispatcher.cancelAll()
            withTimeout(3_000) { operation.join() }
            server.shutdown()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
    }

    private object NoTools : ToolRunner {
        override fun definitions(enableSearch: Boolean, enableRead: Boolean) = emptyList<ToolDefinition>()
        override suspend fun execute(call: CanonicalToolCall, enableSearch: Boolean, enableRead: Boolean): ToolExecutionResult =
            error("The cancellation test must not execute tools")
    }

    companion object {
        private const val FIRST_EVENT = "data: {\"choices\":[{\"delta\":{\"content\":\"first\"}}]}\n\n"
    }
}
