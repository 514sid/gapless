package io.github._514sid.gapless.internal.web

import androidx.test.core.app.ApplicationProvider
import io.github._514sid.gapless.GaplessFit
import io.github._514sid.gapless.internal.ContentSize
import io.github._514sid.gapless.internal.Framing
import io.github._514sid.gapless.internal.PlaybackItem
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.After
import android.os.Looper
import android.webkit.WebView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
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
