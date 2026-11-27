package io.github._514sid.gapless

import io.github._514sid.gapless.internal.ContentSize
import io.github._514sid.gapless.internal.Framing
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
    private inner class Harness(
        scope: TestScope,
        val autoShow: Boolean = true,
        clock: GaplessClock = GaplessClock.Wall,
    ) {
        val controller = GaplessController(scope.backgroundScope, preloadMs, readyTimeoutMs, clock)
        val commands = mutableListOf<PlayerCommand>()
        val events = mutableListOf<GaplessEvent>()
        val ready = mutableSetOf<UUID>()

        init {
            controller.isReadyProvider = { it in ready }
            scope.backgroundScope.launch {
                controller.commands.collect {
                    commands += it
                    // Like a player whose content shows the moment it is played.
                    if (autoShow && it is PlayerCommand.Play) controller.onShown(it.item.playbackId, null)
                }
            }
            scope.backgroundScope.launch { controller.events.collect { events += it } }
        }

        val current: GaplessAsset? get() = controller.currentState.value?.asset
        val plays: List<PlaybackItem> get() = commands.filterIsInstance<PlayerCommand.Play>().map { it.item }
        val prepares: List<PlaybackItem> get() = commands.filterIsInstance<PlayerCommand.Prepare>().map { it.item }
        fun lastPrepared(): PlaybackItem = prepares.last()
        inline fun <reified T : GaplessEvent> eventsOf(): List<T> = events.filterIsInstance<T>()
    }

    private fun video(id: String, width: Int? = null, height: Int? = null) =
        GaplessAsset.Video(id = id, uri = "file:///$id.mp4", mimeType = "video/mp4", width = width, height = height)

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

    // Started waits for the player to show the asset

    /** Plays [first], reported shown, then plays [second] without reporting it. */
    private fun TestScope.cutToUnshown(h: Harness, first: GaplessAsset, second: GaplessAsset) {
        runCurrent()
        h.controller.start(first)
        advanceTimeBy(preloadMs + 1)
        runCurrent()
        h.controller.onShown(h.plays.last().playbackId, null)
        runCurrent()
        h.controller.prepareNext(second)
        runCurrent()
        h.controller.play(second)
        runCurrent()
    }

    @Test
    fun `Started and Ended wait until the player shows the new asset`() = runTest {
        val h = Harness(this, autoShow = false)
        val a = video("a")
        val b = video("b")
        cutToUnshown(h, a, b)

        assertEquals(listOf("a"), h.eventsOf<GaplessEvent.Started>().map { it.asset.id })
        assertTrue(h.eventsOf<GaplessEvent.Ended>().isEmpty())
        assertEquals(a, h.current)

        h.controller.onShown(h.plays.last().playbackId, 1_234L)
        runCurrent()
        assertEquals(listOf("a", "b"), h.eventsOf<GaplessEvent.Started>().map { it.asset.id })
        assertEquals(listOf("a"), h.eventsOf<GaplessEvent.Ended>().map { it.asset.id })
        assertEquals(1_234L, h.eventsOf<GaplessEvent.Started>().last().timestamp)
        assertEquals(1_234L, h.controller.currentState.value?.startedAt)
        assertEquals(b, h.current)
    }

    @Test
    fun `Started is emitted anyway when the player never reports the asset shown`() = runTest {
        val h = Harness(this, autoShow = false)
        cutToUnshown(h, video("a"), video("b"))

        advanceTimeBy(readyTimeoutMs - 1)
        runCurrent()
        assertEquals(1, h.eventsOf<GaplessEvent.Started>().size)

        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("a", "b"), h.eventsOf<GaplessEvent.Started>().map { it.asset.id })
    }

    @Test
    fun `an asset cut away from before it showed gets neither Started nor Ended`() = runTest {
        val h = Harness(this, autoShow = false)
        cutToUnshown(h, video("a"), video("b"))
        val skipped = h.plays.last().playbackId

        h.controller.prepareNext(video("c"))
        runCurrent()
        h.controller.play(video("c"))
        runCurrent()
        h.controller.onShown(skipped, null)
        runCurrent()
        assertEquals(listOf("a"), h.eventsOf<GaplessEvent.Started>().map { it.asset.id })

        h.controller.onShown(h.plays.last().playbackId, null)
        runCurrent()
        assertEquals(listOf("a", "c"), h.eventsOf<GaplessEvent.Started>().map { it.asset.id })
        assertEquals(listOf("a"), h.eventsOf<GaplessEvent.Ended>().map { it.asset.id })
    }

    @Test
    fun `a failure before the new asset shows ends the old one and reports the new one`() = runTest {
        val h = Harness(this, autoShow = false)
        cutToUnshown(h, video("a"), video("b"))

        h.controller.onPlaybackError("decoder failed")
        runCurrent()
        assertEquals(listOf("a"), h.eventsOf<GaplessEvent.Ended>().map { it.asset.id })
        assertEquals(listOf("b"), h.eventsOf<GaplessEvent.PlaybackError>().map { it.asset.id })
        assertEquals(1, h.eventsOf<GaplessEvent.Started>().size)

        // The timeout for the failed asset no longer fires.
        settle()
        assertEquals(1, h.eventsOf<GaplessEvent.Started>().size)
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

    // Completed

    @Test
    fun `the end of the current video is reported as Completed`() = runTest {
        val h = started(video("a"))
        val a = h.plays.single()

        h.controller.onCompleted(a.playbackId)
        runCurrent()

        val completed = h.eventsOf<GaplessEvent.Completed>().single()
        assertEquals("a", completed.asset.id)
        assertEquals(a.playbackId, completed.playbackId)
        assertEquals("a", h.current?.id) // still on screen until the next play()
    }

    @Test
    fun `the end of a video already replaced or stopped is not reported`() = runTest {
        val h = started(video("a"))
        val a = h.plays.single()
        h.controller.prepareNext(video("b"))
        runCurrent()
        h.controller.play(video("b"))
        runCurrent()

        h.controller.onCompleted(a.playbackId)
        runCurrent()
        assertTrue(h.eventsOf<GaplessEvent.Completed>().isEmpty())

        val b = h.plays.last()
        h.controller.stop()
        h.controller.onCompleted(b.playbackId)
        runCurrent()
        assertTrue(h.eventsOf<GaplessEvent.Completed>().isEmpty())
    }

    // Start times

    @Test
    fun `a staged video played with a start time gets the controller's clock and that time`() = runTest {
        val clock = GaplessClock { 42L }
        val h = Harness(this, clock = clock)
        runCurrent()
        h.controller.start(video("a"))
        settle()
        h.controller.prepareNext(video("b"))
        runCurrent()

        h.controller.play(video("b"), startAtMs = 5_000L)
        runCurrent()

        val timeline = (h.plays.last() as PlaybackItem.Video).timeline!!
        assertSame(clock, timeline.clock)
        assertEquals(5_000L, timeline.startAtMs)
        assertEquals(42L - 5_000L, timeline.expectedPositionMs())
    }

    @Test
    fun `an unstaged video played with a start time keeps it through the wait for readiness`() = runTest {
        val h = started(video("a"))

        h.controller.play(video("b"), startAtMs = 7_000L)
        settle()

        assertEquals(7_000L, (h.plays.last() as PlaybackItem.Video).timeline?.startAtMs)
    }

    @Test
    fun `a video played without a start time has no timeline`() = runTest {
        val h = started(video("a"))
        h.controller.prepareNext(video("b"))
        runCurrent()
        h.controller.play(video("b"))
        runCurrent()

        assertNull((h.plays.last() as PlaybackItem.Video).timeline)
        assertNull((h.plays.first() as PlaybackItem.Video).timeline)
    }

    // Asset mapping

    @Test
    fun `web assets keep their refresh interval`() = runTest {
        val h = Harness(this)
        runCurrent()
        h.controller.start(GaplessAsset.Web("w", "https://example.com", refreshIntervalMs = 30_000))
        runCurrent()

        val item = h.lastPrepared() as PlaybackItem.Web
        assertEquals(30_000L, item.refreshIntervalMs)
    }

    @Test
    fun `video size hint comes from declared size and is absent without one`() = runTest {
        val h = Harness(this)
        runCurrent()

        h.controller.prepareNext(video("portrait", width = 1080, height = 1920))
        h.controller.prepareNext(video("unknown"))
        runCurrent()

        val (portrait, unknown) = h.prepares.map { it as PlaybackItem.Video }
        assertEquals(ContentSize(1080f, 1920f), portrait.sizeHint)
        assertEquals("video/mp4", portrait.mimeType)
        assertNull(unknown.sizeHint)
    }

    @Test
    fun `each asset's fit and background reach what it plays as`() = runTest {
        val h = Harness(this)
        runCurrent()
        val white = 0xFFFFFFFF.toInt()

        h.controller.prepareNext(video("v").copy(fit = GaplessFit.Cover, backgroundColor = white))
        h.controller.prepareNext(GaplessAsset.Image("i", "photo.jpg", fit = GaplessFit.Original))
        h.controller.prepareNext(video("plain"))
        runCurrent()

        val (v, i, plain) = h.prepares
        assertEquals(Framing(GaplessFit.Cover, white), (v as PlaybackItem.Video).framing)
        assertEquals(Framing(GaplessFit.Original), (i as PlaybackItem.Image).framing)
        assertEquals(Framing(), (plain as PlaybackItem.Video).framing)
    }

    @Test
    fun `a web page's size becomes its viewport, with its framing`() = runTest {
        val h = Harness(this)
        runCurrent()

        h.controller.prepareNext(
            GaplessAsset.Web("board", "https://example.com", viewportWidth = 1920, viewportHeight = 1080, fit = GaplessFit.Cover),
        )
        h.controller.prepareNext(GaplessAsset.Web("site", "https://example.org"))
        runCurrent()

        val (board, site) = h.prepares.map { it as PlaybackItem.Web }
        assertEquals(ContentSize(1920f, 1080f), board.viewport)
        assertEquals(Framing(GaplessFit.Cover), board.framing)
        assertNull(site.viewport)
    }

    @Test
    fun `each asset's request headers reach what it plays as`() = runTest {
        val h = Harness(this)
        runCurrent()
        val headers = mapOf("Authorization" to "Bearer abc")

        h.controller.prepareNext(video("v").copy(requestHeaders = headers))
        h.controller.prepareNext(GaplessAsset.Image("i", "https://example.com/a.jpg", requestHeaders = headers))
        h.controller.prepareNext(GaplessAsset.Web("w", "https://example.com", requestHeaders = headers))
        runCurrent()

        val (v, i, w) = h.prepares
        assertEquals(headers, (v as PlaybackItem.Video).requestHeaders)
        assertEquals(headers, (i as PlaybackItem.Image).requestHeaders)
        assertEquals(headers, (w as PlaybackItem.Web).requestHeaders)
    }

    @Test
    fun `an animated image's loop count reaches what it plays as`() = runTest {
        val h = Harness(this)
        runCurrent()

        h.controller.prepareNext(GaplessAsset.Image("g", "a.gif", animationLoops = 3))
        h.controller.prepareNext(GaplessAsset.Image("s", "b.svg"))
        runCurrent()

        val (gif, svg) = h.prepares.map { it as PlaybackItem.Image }
        assertEquals(3, gif.animationLoops)
        assertNull(svg.animationLoops)
    }

    // Validation

    @Test
    fun `invalid assets are rejected`() = runTest {
        val controller = Harness(this).controller

        assertThrows<IllegalArgumentException> { controller.play(GaplessAsset.Video(" ", "file:///a.mp4")) }
        assertThrows<IllegalArgumentException> { controller.play(GaplessAsset.Image("a", "")) }
        assertThrows<IllegalArgumentException> { controller.play(GaplessAsset.Video("a", "file:///a.mp3", mimeType = "audio/mpeg")) }
        assertThrows<IllegalArgumentException> { controller.play(video("a").copy(volume = 1.5f)) }
        assertThrows<IllegalArgumentException> { controller.play(video("a").copy(durationMs = 0)) }
        assertThrows<IllegalArgumentException> {
            controller.play(GaplessAsset.Web("w", "https://example.com", refreshIntervalMs = -1))
        }
        assertThrows<IllegalArgumentException> {
            controller.play(GaplessAsset.Image("g", "a.gif", animationLoops = 0))
        }
        assertThrows<IllegalArgumentException> {
            controller.play(video("a").copy(requestHeaders = mapOf("Bad Name" to "x")))
        }
        assertThrows<IllegalArgumentException> {
            controller.play(video("a").copy(requestHeaders = mapOf("X-Token" to "a\r\nX-Evil: 1")))
        }
        assertThrows<IllegalArgumentException> {
            controller.play(GaplessAsset.Web("w", "https://example.com", javaScriptOrigins = emptySet()))
        }
    }

    @Test
    fun `a video without a mime type plays with none, for ExoPlayer to work out`() = runTest {
        val h = Harness(this)
        runCurrent()

        h.controller.prepareNext(GaplessAsset.Video("v", "https://example.com/live.m3u8"))
        runCurrent()

        assertNull((h.lastPrepared() as PlaybackItem.Video).mimeType)
    }
}
