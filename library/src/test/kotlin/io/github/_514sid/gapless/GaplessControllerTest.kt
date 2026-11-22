package io.github._514sid.gapless

import io.github._514sid.gapless.internal.PlaybackItem
import io.github._514sid.gapless.internal.PlayerCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class GaplessControllerTest {

    // Readiness checks hop to Dispatchers.Main; point it at the test scheduler.
    @BeforeEach
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private val preloadMs = 2_000L
    private val readyTimeoutMs = 10_000L

    /** A controller wired to a fake player that records commands and events. */
    private inner class Harness(scope: TestScope) {
        val controller = GaplessController(scope.backgroundScope, preloadMs, readyTimeoutMs)
        val commands = mutableListOf<PlayerCommand>()
        val events = mutableListOf<GaplessEvent>()
        val ready = mutableSetOf<UUID>()

        init {
            controller.isReadyProvider = { it in ready }
            scope.backgroundScope.launch { controller.commands.collect { commands += it } }
            scope.backgroundScope.launch { controller.events.collect { events += it } }
        }

        val current: GaplessAsset? get() = controller.currentState.value?.asset
        val plays: List<PlaybackItem> get() = commands.filterIsInstance<PlayerCommand.Play>().map { it.item }
        val prepares: List<PlaybackItem> get() = commands.filterIsInstance<PlayerCommand.Prepare>().map { it.item }
        fun lastPrepared(): PlaybackItem = prepares.last()
        inline fun <reified T : GaplessEvent> eventsOf(): List<T> = events.filterIsInstance<T>()
    }

    private fun video(id: String, width: Int? = null, height: Int? = null) =
        GaplessAsset(id = id, uri = "file:///$id.mp4", mimeType = "video/mp4", width = width, height = height)

    /**
     * Runs past every timer the controller can set. The controller lives in backgroundScope,
     * which advanceUntilIdle() treats as idle, so time has to be advanced explicitly.
     */
    private fun TestScope.settle() {
        advanceTimeBy(preloadMs + readyTimeoutMs + 1)
        runCurrent()
    }

    /** Starts [asset] and lets the preload window run out so it is on screen. */
    private fun TestScope.started(asset: GaplessAsset): Harness =
        Harness(this).also {
            runCurrent()
            it.controller.start(asset)
            settle()
        }

    // start()

    @Test
    fun `start prepares the asset and shows it after preloadMs`() = runTest {
        val h = Harness(this)
        runCurrent()
        val a = video("a")

        h.controller.start(a)
        runCurrent()
        assertEquals(1, h.prepares.size)
        assertEquals("a", h.lastPrepared().assetId)
        assertNull(h.current)

        advanceTimeBy(preloadMs - 1)
        runCurrent()
        assertNull(h.current)

        advanceTimeBy(2)
        runCurrent()
        assertEquals(a, h.current)
        assertSame(h.prepares.single(), h.plays.single())
        assertEquals(listOf("a"), h.eventsOf<GaplessEvent.Started>().map { it.asset.id })
        assertTrue(h.eventsOf<GaplessEvent.Ended>().isEmpty())
    }

    @Test
    fun `stop before the preload window ends shows nothing`() = runTest {
        val h = Harness(this)
        runCurrent()
        h.controller.start(video("a"))
        runCurrent()

        h.controller.stop()
        settle()

        assertNull(h.current)
        assertTrue(h.plays.isEmpty())
    }

    @Test
    fun `a failed start leaves nothing on screen`() = runTest {
        val h = Harness(this)
        runCurrent()
        h.controller.start(video("a"))
        runCurrent()

        h.controller.onPreloadError("a", "boom")
        settle()

        assertNull(h.current)
        assertTrue(h.plays.isEmpty())
        assertEquals("a", h.eventsOf<GaplessEvent.PreloadError>().single().asset.id)
    }

    // prepareNext() + play()

    @Test
    fun `play of a staged asset cuts immediately and ends the previous one`() = runTest {
        val a = video("a")
        val b = video("b")
        val h = started(a)

        h.controller.prepareNext(b)
        runCurrent()
        val staged = h.lastPrepared()

        h.controller.play(b)
        runCurrent()

        assertEquals(b, h.current)
        assertSame(staged, h.plays.last())
        val ended = h.eventsOf<GaplessEvent.Ended>().single()
        assertEquals("a", ended.asset.id)
        val endedAt = h.events.indexOf(ended)
        val startedB = h.events.indexOfFirst { it is GaplessEvent.Started && it.asset.id == "b" }
        assertTrue(endedAt < startedB, "Ended(a) should come before Started(b)")
    }

    @Test
    fun `play of an unstaged asset waits until the player reports it ready`() = runTest {
        val h = started(video("a"))
        val b = video("b")

        h.controller.play(b)
        runCurrent()
        val item = h.lastPrepared()
        assertEquals("b", item.assetId)

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("a", h.current?.id)

        h.ready += item.playbackId
        advanceTimeBy(100)
        runCurrent()
        assertEquals(b, h.current)
        assertSame(item, h.plays.last())
    }

    @Test
    fun `readiness of a different item does not release the wait`() = runTest {
        val h = started(video("a"))
        h.ready += h.plays.single().playbackId // the item on screen is ready, the new one is not

        h.controller.play(video("b"))
        advanceTimeBy(readyTimeoutMs / 2)
        runCurrent()

        assertEquals("a", h.current?.id)
    }

    @Test
    fun `play of an unstaged asset that never becomes ready cuts after readyTimeoutMs`() = runTest {
        val h = started(video("a"))

        h.controller.play(video("b"))
        advanceTimeBy(readyTimeoutMs - 100)
        runCurrent()
        assertEquals("a", h.current?.id)

        advanceTimeBy(200)
        runCurrent()
        assertEquals("b", h.current?.id)
    }

    @Test
    fun `a preload error cancels the transition waiting on that asset`() = runTest {
        val h = started(video("a"))

        h.controller.play(video("b"))
        runCurrent()
        h.controller.onPreloadError("b", "404")
        settle()

        assertEquals("a", h.current?.id)
        assertEquals(1, h.plays.size)
        assertEquals("b", h.eventsOf<GaplessEvent.PreloadError>().single().asset.id)
    }

    @Test
    fun `a staged asset that failed is prepared again on play instead of cut to`() = runTest {
        val h = started(video("a"))
        val b = video("b")

        h.controller.prepareNext(b)
        runCurrent()
        val failed = h.lastPrepared()
        h.controller.onPreloadError("b", "decoder error")
        runCurrent()

        h.controller.play(b)
        runCurrent()

        val retry = h.lastPrepared()
        assertNotEquals(failed.playbackId, retry.playbackId)
        assertEquals("a", h.current?.id)
    }

    @Test
    fun `a newer play supersedes one still waiting`() = runTest {
        val h = started(video("a"))

        h.controller.play(video("b"))
        runCurrent()
        val b = h.lastPrepared()
        h.controller.play(video("c"))
        runCurrent()
        val c = h.lastPrepared()

        h.ready += b.playbackId
        h.ready += c.playbackId
        settle()

        assertEquals("c", h.current?.id)
        assertFalse(h.eventsOf<GaplessEvent.Started>().any { it.asset.id == "b" })
    }

    // Errors and shutdown

    @Test
    fun `a playback error clears the current state and reports the asset`() = runTest {
        val h = started(video("a"))

        h.controller.onPlaybackError("decoder died")
        runCurrent()

        assertNull(h.current)
        val error = h.eventsOf<GaplessEvent.PlaybackError>().single()
        assertEquals("a", error.asset.id)
        assertEquals("decoder died", error.message)
    }

    @Test
    fun `close during a transition does not throw and ignores later calls`() = runTest {
        val h = Harness(this)
        runCurrent()
        h.controller.start(video("a"))
        runCurrent()

        h.controller.close()
        h.controller.start(video("b"))
        settle()

        assertNull(h.current)
        assertTrue(h.plays.isEmpty())
    }

    // Asset mapping

    @Test
    fun `web assets keep their refresh interval`() = runTest {
        val h = Harness(this)
        runCurrent()
        h.controller.start(GaplessAsset("w", "https://example.com", "text/html", refreshIntervalMs = 30_000))
        runCurrent()

        val item = h.lastPrepared() as PlaybackItem.Web
        assertEquals(30_000L, item.refreshIntervalMs)
    }

    @Test
    fun `video aspect hint comes from declared size and is absent without one`() = runTest {
        val h = Harness(this)
        runCurrent()

        h.controller.prepareNext(video("portrait", width = 1080, height = 1920))
        h.controller.prepareNext(video("unknown"))
        runCurrent()

        val (portrait, unknown) = h.prepares.map { it as PlaybackItem.Video }
        assertEquals(1080f / 1920f, portrait.aspectRatioHint)
        assertEquals("video/mp4", portrait.mimeType)
        assertNull(unknown.aspectRatioHint)
    }

    // Validation

    @Test
    fun `invalid assets are rejected`() = runTest {
        val controller = Harness(this).controller

        assertThrows<IllegalArgumentException> { controller.play(GaplessAsset(" ", "file:///a.mp4", "video/mp4")) }
        assertThrows<IllegalArgumentException> { controller.play(GaplessAsset("a", "", "video/mp4")) }
        assertThrows<IllegalArgumentException> { controller.play(GaplessAsset("a", "file:///a.mp3", "audio/mpeg")) }
        assertThrows<IllegalArgumentException> { controller.play(video("a").copy(volume = 1.5f)) }
        assertThrows<IllegalArgumentException> { controller.play(video("a").copy(durationMs = 0)) }
        assertThrows<IllegalArgumentException> {
            controller.play(GaplessAsset("w", "https://example.com", "text/html", refreshIntervalMs = -1))
        }
    }
}
