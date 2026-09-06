package xyz.mek030399.tokenflow.data

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MimoTtsClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private val call = AtomicReference<Call>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun callStart(startedCall: Call) { call.set(startedCall) }
        }).build()
    }

    @After
    fun tearDown() {
        client.dispatcher.cancelAll()
        server.shutdown()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdownNow()
    }

    @Test(timeout = 10_000)
    fun returnsSuccessfulResponseBody() = runBlocking {
        val body = "{\"choices\":[]}"
        server.enqueue(MockResponse().setBody(body))

        assertEquals(body, withTimeout(2_000) { executeMimoRequest(client, request()) })
    }

    @Test(timeout = 10_000)
    fun truncatedResponseFailsInsteadOfLeavingSynthesisSuspended() = runBlocking {
        server.enqueue(MockResponse().setBody("x".repeat(10_000))
            .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))

        val error = runCatching { withTimeout(2_000) { executeMimoRequest(client, request()) } }.exceptionOrNull()

        assertTrue("Expected an I/O error, got $error", error is IOException)
    }

    @Test(timeout = 10_000)
    fun responseBodyReadTimeoutResumesWithFailure() = runBlocking {
        client = client.newBuilder().readTimeout(200, TimeUnit.MILLISECONDS).build()
        server.enqueue(MockResponse().setBody("response").setBodyDelay(3, TimeUnit.SECONDS))

        val error = runCatching { withTimeout(2_000) { executeMimoRequest(client, request()) } }.exceptionOrNull()

        assertTrue("Expected the socket timeout, got $error", error is SocketTimeoutException)
    }

    @Test(timeout = 10_000)
    fun preservesProviderErrorStatusAndMessage() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setBody("{\"error\":{\"message\":\"Rate limited\"}}"))

        val error = runCatching { withTimeout(2_000) { executeMimoRequest(client, request()) } }.exceptionOrNull()

        assertTrue(error is ApiException)
        assertEquals(429, (error as ApiException).status)
        assertEquals("Rate limited", error.message)
    }

    @Test(timeout = 10_000)
    fun invalidErrorJsonUsesHttpStatusFallback() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody("unavailable"))

        val error = runCatching { withTimeout(2_000) { executeMimoRequest(client, request()) } }.exceptionOrNull()

        assertTrue(error is ApiException)
        assertEquals("MiMo TTS request failed (503)", error?.message)
    }

    @Test(timeout = 10_000)
    fun cancellationClosesTheHttpCall() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val operation = async { executeMimoRequest(client, request()) }
        try {
            withTimeout(2_000) {
                while (call.get() == null) kotlinx.coroutines.yield()
            }
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            operation.cancel()
            withTimeout(2_000) { operation.join() }

            assertTrue(call.get().isCanceled())
            assertTrue(runCatching { operation.await() }.exceptionOrNull() is CancellationException)
        } finally {
            operation.cancel()
            client.dispatcher.cancelAll()
        }
    }

    private fun request() = Request.Builder().url(server.url("/tts")).build()
}
