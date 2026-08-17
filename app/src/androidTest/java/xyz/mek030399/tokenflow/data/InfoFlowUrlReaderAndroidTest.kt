package xyz.mek030399.tokenflow.data

import android.os.Looper
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class InfoFlowUrlReaderAndroidTest {
    @Test
    fun mainThreadReadMovesDnsValidationOffMain() = runBlocking {
        val lookupRanOnMain = AtomicBoolean(true)
        val responseBody = """{
            "url":"https://example.test/article",
            "final_url":"https://example.test/article",
            "title":"Example",
            "markdown":"${"Background content. ".repeat(20)}",
            "cache_hit":false
        }""".trimIndent()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(responseBody.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        val reader = InfoFlowUrlReader(
            builtIn = object : UrlContentReader {
                override suspend fun read(rawUrl: String) = UrlReadResult("fallback")
            },
            client = client,
            endpointUrl = "https://infoflow.test/v1/read_url",
            dnsLookup = { host ->
                assertEquals("example.test", host)
                lookupRanOnMain.set(Looper.myLooper() == Looper.getMainLooper())
                listOf(InetAddress.getByAddress(host, byteArrayOf(1, 1, 1, 1)))
            },
        )

        val result = withContext(Dispatchers.Main) {
            reader.read("https://example.test/article")
        }

        assertFalse(lookupRanOnMain.get())
        assertEquals("infoflow", result.source)
        assertFalse(result.fallbackUsed)
    }
}
