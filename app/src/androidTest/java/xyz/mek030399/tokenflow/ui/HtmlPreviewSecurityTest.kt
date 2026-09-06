package xyz.mek030399.tokenflow.ui

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlPreviewSecurityTest {
    @Test
    fun commentCannotHidePolicyAndOnlyAllowedResourcesLoad() {
        val source = """
            <!doctype html><!-- <head> --><html><head><script>
            window.previewViolations = [];
            document.addEventListener('securitypolicyviolation', function(event) {
                window.previewViolations.push(event.effectiveDirective);
            });
            window.addEventListener('load', function() {
                document.body.dataset.inlineRan = 'yes';
                fetch('$FIXTURE_ORIGIN/connect').catch(function() { window.fetchRejected = true; });
                var script = document.createElement('script');
                script.src = '$FIXTURE_ORIGIN/external.js';
                document.body.appendChild(script);
                var frame = document.createElement('iframe');
                frame.src = '$FIXTURE_ORIGIN/frame';
                document.body.appendChild(frame);
                var form = document.createElement('form');
                form.method = 'post';
                form.action = '$FIXTURE_ORIGIN/form';
                document.body.appendChild(form);
                form.submit();
            });
            </script></head><body><img id="allowed" src="$FIXTURE_ORIGIN/image.png"></body></html>
        """.trimIndent()

        withPreview(source) { webView, requests, _ ->
            awaitJavascript(
                webView,
                "document.body.dataset.inlineRan === 'yes' && window.fetchRejected === true && " +
                    "window.previewViolations.length >= 4 && document.getElementById('allowed').naturalWidth === 1",
            )
            assertEquals("false", javascript(webView, "window.externalLoaded === true"))
            listOf("connect-src", "script-src-elem", "frame-src", "form-action").forEach { directive ->
                assertEquals("true", javascript(webView, "window.previewViolations.includes('$directive')"))
            }
            assertTrue(requests.any { it == "$FIXTURE_ORIGIN/image.png" })
            listOf("/connect", "/external.js", "/frame", "/form").forEach { path ->
                assertFalse("CSP permitted a request to $path", requests.any { it == FIXTURE_ORIGIN + path })
            }
        }
    }

    @Test
    fun fragmentScriptsStillRunInBodyAndTopLevelNavigationIsBlocked() {
        val source = """
            <!-- <head> --><script>
            const template = '<head>';
            document.body.dataset.fragmentRan = 'yes';
            </script><p>preview</p>
        """.trimIndent()

        withPreview(source) { webView, requests, navigations ->
            assertEquals("true", javascript(webView, "document.body.dataset.fragmentRan === 'yes'"))
            javascript(webView, "window.location.href = '$FIXTURE_ORIGIN/navigation'; void 0")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (navigations.isEmpty() && System.nanoTime() < deadline) Thread.sleep(25)

            assertTrue(navigations.contains("$FIXTURE_ORIGIN/navigation"))
            assertFalse(requests.contains("$FIXTURE_ORIGIN/navigation"))
            assertEquals("true", javascript(webView, "document.body.dataset.fragmentRan === 'yes'"))
        }
    }

    private fun withPreview(
        source: String,
        assertions: (WebView, ConcurrentLinkedQueue<String>, ConcurrentLinkedQueue<String>) -> Unit,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val loaded = CountDownLatch(1)
        val requests = ConcurrentLinkedQueue<String>()
        val navigations = ConcurrentLinkedQueue<String>()
        lateinit var webView: WebView
        instrumentation.runOnMainSync {
            webView = WebView(instrumentation.targetContext)
            configureHtmlPreview(webView)
            webView.webViewClient = object : HtmlPreviewWebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) { loaded.countDown() }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    request?.url?.toString()?.let(navigations::add)
                    return super.shouldOverrideUrlLoading(view, request)
                }

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    super.shouldInterceptRequest(view, request)?.let { return it }
                    val url = request?.url?.toString().orEmpty()
                    if (!url.startsWith("$FIXTURE_ORIGIN/")) return null
                    requests.add(url)
                    return if (url == "$FIXTURE_ORIGIN/image.png") {
                        WebResourceResponse("image/png", null, ByteArrayInputStream(ONE_PIXEL_PNG))
                    } else {
                        WebResourceResponse(
                            "application/javascript", "UTF-8",
                            ByteArrayInputStream("window.externalLoaded=true;".encodeToByteArray()),
                        )
                    }
                }
            }
            webView.loadDataWithBaseURL("about:blank", previewDocument(source), "text/html", "UTF-8", null)
        }
        try {
            assertTrue("The preview did not finish loading", loaded.await(5, TimeUnit.SECONDS))
            assertions(webView, requests, navigations)
        } finally {
            instrumentation.runOnMainSync {
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.destroy()
            }
        }
    }

    private fun awaitJavascript(webView: WebView, expression: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        do {
            if (javascript(webView, expression) == "true") return
            Thread.sleep(25)
        } while (System.nanoTime() < deadline)
        assertEquals(expression, "true", javascript(webView, expression))
    }

    private fun javascript(webView: WebView, expression: String): String {
        val result = AtomicReference<String>()
        val completed = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            webView.evaluateJavascript(expression) {
                result.set(it)
                completed.countDown()
            }
        }
        assertTrue("JavaScript evaluation did not finish", completed.await(3, TimeUnit.SECONDS))
        return result.get()
    }

    companion object {
        private const val FIXTURE_ORIGIN = "https://preview-fixture.test"
        private val ONE_PIXEL_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGNgYGD4DwABBAEAX+XDSwAAAABJRU5ErkJggg==",
        )
    }
}
