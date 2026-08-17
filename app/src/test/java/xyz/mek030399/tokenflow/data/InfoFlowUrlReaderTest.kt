package xyz.mek030399.tokenflow.data

import java.net.InetAddress
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InfoFlowUrlReaderTest {
    private lateinit var server: MockWebServer
    private val json = DirectApiTransport.defaultJson

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun sendsAnonymousExpectedStaticRequest() = runTest {
        server.enqueue(infoFlowResponse("Long enough markdown. ".repeat(20), title = "Example"))
        val reader = reader("ifk-secret")

        val result = reader.read("https://1.1.1.1/")

        val request = server.takeRequest()
        assertEquals("/v1/read_url", request.path)
        assertEquals(null, request.getHeader("X-InfoFlow-API-Key"))
        assertEquals(null, request.getHeader("Authorization"))
        val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("https://1.1.1.1/", body.getValue("url").jsonPrimitive.content)
        assertFalse(body.getValue("render").jsonPrimitive.content.toBoolean())
        assertEquals("20000", body.getValue("max_chars").jsonPrimitive.content)
        assertTrue(result.content.contains("Example"))
        assertFalse(result.fallbackUsed)
    }

    @Test
    fun shortMarkdownRetriesWithRendering() = runTest {
        server.enqueue(infoFlowResponse("short"))
        server.enqueue(infoFlowResponse("Rendered content. ".repeat(20)))

        val result = reader("key").read("https://1.1.1.1/")

        val first = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val second = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertFalse(first.getValue("render").jsonPrimitive.content.toBoolean())
        assertTrue(second.getValue("render").jsonPrimitive.content.toBoolean())
        assertTrue(result.content.contains("Rendered content"))
    }

    @Test
    fun httpFailureFallsBackToBuiltInReader() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("do not expose this"))
        val fallback = object : UrlContentReader {
            override suspend fun read(rawUrl: String) = UrlReadResult("built-in content")
        }

        val result = reader("key", fallback).read("https://1.1.1.1/")

        assertEquals("built-in content", result.content)
        assertTrue(result.fallbackUsed)
        assertEquals("HTTP 429", result.fallbackReason)
        assertFalse(result.fallbackReason.contains("do not expose"))
    }

    @Test
    fun missingKeyStillCallsPublicInfoFlowEndpoint() = runTest {
        server.enqueue(infoFlowResponse("Anonymous response. ".repeat(20)))

        val result = reader(null).read("https://1.1.1.1/")

        assertTrue(result.content.contains("Anonymous response"))
        assertEquals(null, server.takeRequest().getHeader("X-InfoFlow-API-Key"))
    }

    @Test
    fun validatesDnsOffCallerThreadAndReturnsInfoFlowContent() = runTest {
        server.enqueue(infoFlowResponse("Background DNS response. ".repeat(20)))
        val callerThread = Thread.currentThread()
        val lookupThread = AtomicReference<Thread>()
        val lookedUpHost = AtomicReference<String>()
        val publicAddress = InetAddress.getByAddress("example.test", byteArrayOf(1, 1, 1, 1))
        val reader = reader(
            key = null,
            dnsLookup = { host ->
                lookedUpHost.set(host)
                lookupThread.set(Thread.currentThread())
                listOf(publicAddress)
            },
        )

        val result = reader.read("https://example.test/article")

        assertEquals("example.test", lookedUpHost.get())
        assertNotSame(callerThread, lookupThread.get())
        assertEquals("infoflow", result.source)
        assertFalse(result.fallbackUsed)
        assertTrue(result.content.contains("Background DNS response"))
        assertFalse(result.content.contains("NetworkOnMainThreadException"))
    }

    @Test
    fun rejectsMixedPrivateDnsBeforeCallingInfoFlow() = runTest {
        val publicAddress = InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1))
        val privateAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val reader = reader(
            key = null,
            dnsLookup = { listOf(publicAddress, privateAddress) },
        )
        var failure: Throwable? = null

        try {
            reader.read("https://example.test/article")
        } catch (error: Throwable) {
            failure = error
        }

        assertTrue(failure is ConfigurationException)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun cancellationFromBuiltInFallbackIsRethrown() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        val fallback = object : UrlContentReader {
            override suspend fun read(rawUrl: String): UrlReadResult {
                throw CancellationException("cancelled")
            }
        }
        var cancelled = false

        try {
            reader("key", fallback).read("https://1.1.1.1/")
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
    }

    private fun reader(
        key: String?,
        builtIn: UrlContentReader = object : UrlContentReader {
            override suspend fun read(rawUrl: String) = UrlReadResult("fallback")
        },
        dnsLookup: (String) -> List<InetAddress> = { host -> InetAddress.getAllByName(host).toList() },
    ) = InfoFlowUrlReader(
        apiKeyProvider = { key },
        builtIn = builtIn,
        json = json,
        endpointUrl = server.url("/v1/read_url").toString(),
        dnsLookup = dnsLookup,
    )

    private fun infoFlowResponse(markdown: String, title: String = "") = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(
            json.encodeToString(
                kotlinx.serialization.json.buildJsonObject {
                    put("url", "https://1.1.1.1/")
                    put("final_url", "https://1.1.1.1/")
                    put("title", title)
                    put("markdown", markdown)
                    put("cache_hit", false)
                    put("request_id", "request-1")
                },
            ),
        )
}
