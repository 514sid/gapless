package io.github._514sid.gapless.internal.web

import androidx.test.core.app.ApplicationProvider
import io.github._514sid.gapless.internal.PlaybackItem
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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

    private fun advancePastPaintTimeout() {
        scope.testScheduler.advanceTimeBy(5_001)
        scope.testScheduler.runCurrent()
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
