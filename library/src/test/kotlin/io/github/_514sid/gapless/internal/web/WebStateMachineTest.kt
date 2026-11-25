package io.github._514sid.gapless.internal.web

import androidx.test.core.app.ApplicationProvider
import io.github._514sid.gapless.GaplessFit
import io.github._514sid.gapless.GaplessWebConfig
import io.github._514sid.gapless.internal.ContentSize
import io.github._514sid.gapless.internal.Framing
import io.github._514sid.gapless.internal.PlaybackItem
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.After
import android.os.Looper
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.HttpAuthHandler
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebViewClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import android.app.Activity
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Robolectric's WebView never paints, so every play() here runs into the paint timeout. Whether
 * the renderer still runs scripts is scripted through [rendererResponds].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebStateMachineTest {

    private val scope = TestScope(StandardTestDispatcher())
    private val errors = mutableListOf<String>()
    private val shown = mutableListOf<UUID>()
    private val probed = mutableListOf<Any>()
    private var rendererResponds = true

    private val machine = WebStateMachine(
        context = ApplicationProvider.getApplicationContext(),
        scope = scope,
        respondsToScript = { view -> probed += view; rendererResponds },
    ).also { m ->
        m.onError = { errors += it }
        m.onShown = { shown += it }
    }

    @After
    fun tearDown() = machine.release()

    private fun page(url: String) = PlaybackItem.Web(url).also { it.assetId = url }

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    /** The page in [view] finished painting, as the WebView reports it. */
    private fun painted(view: WebView, url: String) {
        idleMain() // the load is posted
        shadowOf(view).webViewClient.onPageCommitVisible(view, url)
    }

    /** The page in [view] calls gapless.ready(). */
    private fun callReady(view: WebView) {
        val bridge = shadowOf(view).getJavascriptInterface("gapless")
        bridge.javaClass.getMethod("ready").apply { isAccessible = true }.invoke(bridge)
        idleMain()
    }

    private fun advancePastPaintTimeout() {
        scope.testScheduler.advanceTimeBy(5_001)
        scope.testScheduler.runCurrent()
    }

    // Pages that say when they are ready

    @Test
    fun `a page that waits for ready isn't ready when it paints, only once it calls ready`() {
        val item = PlaybackItem.Web("https://example.com/dash", waitForReady = true).also { it.assetId = "dash" }
        machine.prepare(item)
        val view = machine.renderState.webViewB!!

        painted(view, item.url)
        assertFalse(machine.pageCommitted)

        callReady(view)
        assertTrue(machine.pageCommitted)
    }

    @Test
    fun `a ready call before the paint counts once the page paints`() {
        val item = PlaybackItem.Web("https://example.com/dash", waitForReady = true).also { it.assetId = "dash" }
        machine.prepare(item)
        val view = machine.renderState.webViewB!!

        callReady(view)
        assertFalse(machine.pageCommitted)
        painted(view, item.url)
        assertTrue(machine.pageCommitted)
    }

    @Test
    fun `a page that doesn't wait is ready when it paints`() {
        val item = page("https://example.com/plain")
        machine.prepare(item)

        painted(machine.renderState.webViewB!!, item.url)

        assertTrue(machine.pageCommitted)
    }

    @Test
    fun `a waiting page that never calls ready is swapped in after the paint timeout`() {
        val item = PlaybackItem.Web("https://example.com/dash", waitForReady = true).also { it.assetId = "dash" }
        machine.prepare(item)
        painted(machine.renderState.webViewB!!, item.url)

        machine.play(item)
        advancePastPaintTimeout()

        assertEquals(listOf(item.playbackId), shown)
        assertEquals(1, machine.renderState.activeSlot)
    }

    // Scripts

    @Test
    fun `without document start scripts, the script runs when the page paints, in its origins only`() {
        val script = "document.body.style.background = 'red'"
        val item = PlaybackItem.Web(
            "https://example.com/news",
            javaScript = script,
            javaScriptOrigins = setOf("https://example.com"),
        ).also { it.assetId = "news" }
        machine.prepare(item)
        val view = machine.renderState.webViewB!!

        painted(view, "https://login.other.com/")
        assertNull(shadowOf(view).lastEvaluatedJavascript)

        painted(view, item.url)
        assertEquals(script, shadowOf(view).lastEvaluatedJavascript)
    }

    // Pages laid out at their own size

    @Test
    fun `a page with a size is laid out at it and framed, and a blanked slot fills the player again`() {
        val viewport = ContentSize(1920f, 1080f)
        val framing = Framing(GaplessFit.Cover, 0xFFFFFFFF.toInt())
        val item = PlaybackItem.Web("https://example.com/board", viewport = viewport, framing = framing)
            .also { it.assetId = "board" }
        machine.prepare(item)
        assertEquals(WebLayout(viewport, framing), machine.renderState.layoutB)
        assertEquals(WebLayout(), machine.renderState.layoutA)

        machine.clear()
        assertEquals(WebLayout(), machine.renderState.layoutB)
    }

    @Test
    fun `without document start scripts, the viewport is set when the page paints, in any origin`() {
        val viewport = ContentSize(1920f, 1080f)
        val script = "document.body.style.background = 'red'"
        val item = PlaybackItem.Web(
            "https://example.com/board",
            javaScript = script,
            javaScriptOrigins = setOf("https://example.com"),
            viewport = viewport,
        ).also { it.assetId = "board" }
        machine.prepare(item)
        val view = machine.renderState.webViewB!!

        painted(view, "https://login.other.com/")
        assertEquals(viewportScript(viewport), shadowOf(view).lastEvaluatedJavascript)

        painted(view, item.url)
        assertEquals("the page's own script runs after it", script, shadowOf(view).lastEvaluatedJavascript)
    }

    @Test
    fun `the viewport script sets the page's width in CSS pixels`() {
        val script = viewportScript(ContentSize(1920f, 1080f))
        assertTrue(script.contains("'width=1920'"))
        assertTrue("it leaves frames alone", script.contains("window !== window.top"))
    }

    @Test
    fun `a page's request headers go with its own request`() {
        val headers = mapOf("Authorization" to "Bearer abc")
        val item = PlaybackItem.Web("https://example.com/board", requestHeaders = headers).also { it.assetId = "board" }
        machine.prepare(item)
        val view = machine.renderState.webViewB!!
        // The load is posted to the view, which runs it only once the view is attached.
        Robolectric.buildActivity(Activity::class.java).setup().get().setContentView(view)
        idleMain()
        assertEquals(item.url, shadowOf(view).lastLoadedUrl)
        assertEquals(headers, shadowOf(view).lastAdditionalHttpHeaders)
    }

    // The app's own WebView settings

    private fun machineWith(configure: (WebView) -> Unit) = WebStateMachine(
        context = ApplicationProvider.getApplicationContext(),
        scope = scope,
        config = GaplessWebConfig(configureWebView = configure),
        respondsToScript = { rendererResponds },
    ).also { m -> m.onError = { errors += it } }

    @Test
    fun `the app's function sets up each WebView, and its own chrome client stays`() {
        val configured = mutableListOf<WebView>()
        val chrome = WebChromeClient()
        val m = machineWith { view ->
            configured += view
            view.settings.allowFileAccess = true
            view.webChromeClient = chrome
        }
        m.prepare(page("https://example.com/a"))
        val views = listOf(m.renderState.webViewA!!, m.renderState.webViewB!!)

        assertEquals(views, configured)
        for (view in views) {
            assertTrue(view.settings.allowFileAccess)
            assertSame(chrome, view.webChromeClient)
        }
        m.release()
    }

    @Test
    fun `what the library depends on is put back if the app's function takes it away`() {
        var original: WebViewClient? = null
        val m = machineWith { view ->
            original = view.webViewClient
            view.webViewClient = WebViewClient()
            view.settings.javaScriptEnabled = false
            view.removeJavascriptInterface("gapless")
        }
        m.prepare(page("https://example.com/a"))
        val view = m.renderState.webViewB!!

        assertSame(original, view.webViewClient)
        assertTrue(view.settings.javaScriptEnabled)
        assertNotNull(shadowOf(view).getJavascriptInterface("gapless"))
        m.release()
    }

    @Test
    fun `the app's own client gets the callbacks the library passes on`() {
        val served = WebResourceResponse("text/html", "utf-8", "<p>offline</p>".byteInputStream())
        val calls = mutableListOf<String>()
        val appClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) = served
            override fun onPageCommitVisible(view: WebView, url: String) { calls += "commit $url" }
            override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String?, realm: String?) {
                calls += "auth $host"
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                calls += "gone"
                return false
            }
        }
        val m = machineWith { it.webViewClient = appClient }
        val item = page("https://example.com/a")
        m.prepare(item)
        val view = m.renderState.webViewB!!
        val client = shadowOf(view).webViewClient
        val request = object : WebResourceRequest {
            override fun getUrl() = android.net.Uri.parse(item.url)
            override fun isForMainFrame() = true
            override fun isRedirect() = false
            override fun hasGesture() = false
            override fun getMethod() = "GET"
            override fun getRequestHeaders() = emptyMap<String, String>()
        }

        assertNotSame("the library keeps its own client in place", appClient, client)
        assertSame(served, client.shouldInterceptRequest(view, request))
        client.onReceivedHttpAuthRequest(view, HttpAuthHandler::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance(), "example.com", "dash")
        painted(view, item.url)
        assertTrue("the library still sees the paint", m.pageCommitted)
        client.onRenderProcessGone(view, object : RenderProcessGoneDetail() {
            override fun didCrash() = true
            override fun rendererPriorityAtExit() = 0
        })
        idleMain()

        assertEquals(listOf("auth example.com", "commit ${item.url}"), calls)
        m.release()
    }

    @Test
    fun `a function that throws doesn't stop the page from playing`() {
        val m = machineWith { throw IllegalStateException("app bug") }
        m.prepare(page("https://example.com/a"))

        assertNotNull(m.renderState.webViewA)
        assertNotNull(m.renderState.webViewB)
        assertTrue(errors.isEmpty())
        m.release()
    }

    @Test
    fun `a WebView that replaces a hung one is set up by the app's function too`() {
        val configured = mutableListOf<WebView>()
        val m = machineWith { configured += it }
        rendererResponds = false
        val item = page("https://example.com/hung")
        m.prepare(item)
        m.play(item)
        advancePastPaintTimeout()

        assertEquals(4, configured.size)
        assertTrue(m.renderState.webViewA in configured && m.renderState.webViewB in configured)
        m.release()
    }

    @Test
    fun `a page without a script gets none`() {
        val item = page("https://example.com/plain")
        machine.prepare(item)
        val view = machine.renderState.webViewB!!

        painted(view, item.url)

        assertNull(shadowOf(view).lastEvaluatedJavascript)
    }

    @Test
    fun `a slow page on a responsive renderer is swapped in after the paint timeout`() {
        val item = page("https://example.com/slow")
        machine.prepare(item)
        val before = machine.renderState

        machine.play(item)
        advancePastPaintTimeout()

        assertEquals(listOf<Any>(before.webViewB!!), probed)
        assertEquals(1, machine.renderState.activeSlot)
        assertEquals(listOf(item.playbackId), shown)
        assertTrue(errors.isEmpty())
        assertSame(before.webViewA, machine.renderState.webViewA)
        assertSame(before.webViewB, machine.renderState.webViewB)
    }

    @Test
    fun `a hung renderer is replaced and the page is reported instead of shown`() {
        rendererResponds = false
        val item = page("https://example.com/hung")
        machine.prepare(item)
        val before = machine.renderState

        machine.play(item)
        advancePastPaintTimeout()

        assertEquals(listOf(WebStateMachine.RENDERER_HUNG), errors)
        assertTrue(shown.isEmpty())
        assertEquals(0, machine.renderState.activeSlot)
        // Both slots shared the hung renderer, so both are new.
        assertNotNull(machine.renderState.webViewA)
        assertNotNull(machine.renderState.webViewB)
        assertNotSame(before.webViewA, machine.renderState.webViewA)
        assertNotSame(before.webViewB, machine.renderState.webViewB)
    }

    @Test
    fun `the next page plays on the replacement WebViews`() {
        rendererResponds = false
        val hung = page("https://example.com/hung")
        machine.prepare(hung)
        machine.play(hung)
        advancePastPaintTimeout()

        rendererResponds = true
        val next = page("https://example.com/next")
        machine.prepare(next)
        val replacements = machine.renderState
        machine.play(next)
        advancePastPaintTimeout()

        assertEquals(listOf(next.playbackId), shown)
        assertEquals(1, machine.renderState.activeSlot)
        assertSame(replacements.webViewB, machine.renderState.webViewB)
    }
}
