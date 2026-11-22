package io.github._514sid.gapless.internal.video

import android.net.Uri
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import io.github._514sid.gapless.GaplessVideoConfig
import io.github._514sid.gapless.internal.PlaybackItem
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Drives [DualVideoStateMachine] with scripted players: each test fires the player callbacks
 * (errors, first frames) itself and moves the main looper's clock to run retries and the watchdog.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DualVideoStateMachineTest {

    private val fakes = mutableListOf<FakePlayer>()
    private val errors = mutableListOf<String>()
    private val preloadErrors = mutableListOf<String>()
    private lateinit var engine: DualVideoStateMachine

    private fun start(config: GaplessVideoConfig = GaplessVideoConfig()) {
        engine = DualVideoStateMachine(ApplicationProvider.getApplicationContext(), config) { _, _ ->
            FakePlayer().also { fakes += it }.mock
        }
        engine.onErrorCallback = { errors += it }
        engine.onPreloadErrorCallback = { assetId, _ -> preloadErrors += assetId }
    }

    @After
    fun tearDown() {
        if (::engine.isInitialized) engine.release()
    }

    // --- Retries and classification ---

    @Test
    fun `transient errors are retried, rebuilt on the last attempt, then reported`() {
        start()
        val a = video("a")
        playFirst(a)
        val pa = playerOf(a)
        val prepares = pa.prepares

        pa.error(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        idle(1_010)
        pa.error(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        idle(2_010)
        assertEquals("first two retries reuse the player", prepares + 2, pa.prepares)

        pa.error(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        idle(3_010)
        assertTrue("the last retry releases the old player", pa.released)
        val rebuilt = fakes.last()
        assertEquals(a.uri, rebuilt.uri)
        assertEquals(1, rebuilt.prepares)
        assertTrue(errors.isEmpty())

        rebuilt.error(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        assertEquals(1, errors.size)
    }

    @Test
    fun `a 503 on a staged clip is retried and a 404 is reported at once`() {
        start()
        playFirst(video("a"))
        val b = video("b")
        engine.prepare(b)
        val pb = playerOf(b)
        val prepares = pb.prepares

        pb.fire(httpError(503))
        idle(1_010)
        assertEquals(prepares + 1, pb.prepares)
        assertTrue(preloadErrors.isEmpty())

        pb.fire(httpError(404))
        assertEquals(listOf("b"), preloadErrors)
    }

    @Test
    fun `damaged media gets one retry on a new player`() {
        start()
        playFirst(video("a"))
        val b = video("b")
        engine.prepare(b)
        playerOf(b).error(PlaybackException.ERROR_CODE_DECODING_FAILED)
        idle(1_010)

        val rebuilt = fakes.last()
        assertEquals(3, fakes.size)
        assertEquals(b.uri, rebuilt.uri)
        assertTrue(preloadErrors.isEmpty())

        rebuilt.error(PlaybackException.ERROR_CODE_DECODING_FAILED)
        assertEquals(listOf("b"), preloadErrors)
    }

    // --- Hard cuts ---

    @Test
    fun `a staged decoder failure parks the clip and cuts hard to it`() {
        start()
        val a = video("a")
        playFirst(a)
        val pa = playerOf(a)
        val b = video("b")
        engine.prepare(b)
        val pb = playerOf(b)

        pb.error(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)
        assertTrue("the parked clip reports ready so the cut isn't held up", engine.isNextReady)
        assertTrue(errors.isEmpty() && preloadErrors.isEmpty())

        val preparesB = pb.prepares
        val shown = engine.renderState.activeSlot
        engine.play(b)
        assertNull("the outgoing clip gives up its decoder", pa.uri)
        assertEquals("the incoming clip opens its decoder at the cut", preparesB + 1, pb.prepares)
        assertEquals("the old frame stays up until the new clip draws", shown, engine.renderState.activeSlot)

        pb.firstFrame()
        assertEquals(1 - shown, engine.renderState.activeSlot)
    }

    @Test
    fun `a clip whose decoder fails with the other slot free is reported and seamless cuts resume`() {
        start()
        playFirst(video("a"))
        val b = video("b")
        engine.prepare(b)
        val pb = playerOf(b)
        pb.error(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)
        engine.play(b)
        val preparesAtCut = pb.prepares

        pb.error(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)
        idle(160)
        assertEquals("one quick retry while the old codec is freed", preparesAtCut + 1, pb.prepares)
        assertTrue(errors.isEmpty())

        pb.error(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)
        assertEquals(1, errors.size)

        // Back to seamless cuts: the next clip opens its own decoder while staged.
        val c = video("c")
        engine.prepare(c)
        assertEquals(1, playerOf(c).preparesSinceMedia)
        assertFalse(engine.isNextReady)
    }

    @Test
    fun `a parked clip that draws at its cut keeps hard cuts on`() {
        start()
        playFirst(video("a"))
        val b = video("b")
        engine.prepare(b)
        playerOf(b).error(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)
        engine.play(b)
        playerOf(b).firstFrame()

        val c = video("c")
        engine.prepare(c)
        assertEquals("the next clip is parked too", 0, playerOf(c).preparesSinceMedia)
        assertTrue(engine.isNextReady)
    }

    @Test
    fun `losing the playing clip's decoder hard cuts once without starting a trial`() {
        start()
        val a = video("a")
        playFirst(a)
        val pa = playerOf(a)
        val b = video("b")
        engine.prepare(b)
        val pb = playerOf(b)
        pb.firstFrame()
        val stopsB = pb.stops

        pa.error(PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED)
        assertEquals("the staged clip is parked", stopsB + 1, pb.stops)
        val preparesA = pa.prepares
        idle(1_010)
        assertEquals("the playing clip gets its decoder back", preparesA + 1, pa.prepares)

        engine.play(b)
        pb.firstFrame()

        val c = video("c")
        engine.prepare(c)
        assertEquals("a healthy clip drawing proves nothing; seamless cuts stay on", 1, playerOf(c).preparesSinceMedia)
        assertFalse(engine.isNextReady)
    }

    @Test
    fun `the playing clip losing its decoder on two cuts in a row switches to hard cuts`() {
        start()
        val a = video("a")
        playFirst(a)
        val b = video("b")
        cutAfterReclaim(playing = a, next = b)
        val c = video("c")
        cutAfterReclaim(playing = b, next = c)

        val d = video("d")
        engine.prepare(d)
        assertEquals("staging no longer opens a second decoder", 0, playerOf(d).preparesSinceMedia)
        assertTrue(engine.isNextReady)
    }

    @Test
    fun `a clean cut between two reclaims keeps seamless cuts`() {
        start()
        val a = video("a")
        playFirst(a)
        val b = video("b")
        cutAfterReclaim(playing = a, next = b)
        val c = video("c")
        engine.prepare(c)
        playerOf(c).firstFrame()
        engine.play(c) // no reclaim on this cut
        val d = video("d")
        cutAfterReclaim(playing = c, next = d)

        val e = video("e")
        engine.prepare(e)
        assertEquals(1, playerOf(e).preparesSinceMedia)
        assertFalse(engine.isNextReady)
    }

    @Test
    fun `a queued retry for a clip that gets parked is dropped`() {
        start()
        val a = video("a")
        playFirst(a)
        val b = video("b")
        engine.prepare(b)
        val pb = playerOf(b)

        pb.error(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED) // retry queued for 1s
        playerOf(a).error(PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED) // parks b
        val prepares = pb.prepares
        idle(1_010)
        assertEquals("the parked clip must not reopen a decoder", prepares, pb.prepares)
    }

    @Test
    fun `after a failed hard cut a clip staged on screen moves to the hidden slot`() {
        start()
        enterConfirmedHardCuts()
        val c = video("c")
        engine.prepare(c)
        engine.play(c) // hard cut, pending
        val shown = engine.renderState.activeSlot
        val d = video("d")
        engine.prepare(d) // parked in the slot still on screen
        val onScreen = playerOf(d)
        val pc = playerOf(c)

        pc.error(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        assertEquals(1, errors.size)
        assertEquals("the frozen frame stays up", shown, engine.renderState.activeSlot)
        assertNull("the on-screen copy is dropped", onScreen.uri)
        assertEquals("d is staged on the hidden slot", d.uri, pc.uri)

        pc.firstFrame()
        assertTrue(engine.isNextReady)
        engine.play(d)
        assertEquals(1 - shown, engine.renderState.activeSlot)
    }

    @Test
    fun `a newer clip reusing the slot on screen during a hard cut gets its own aspect ratio`() {
        start()
        enterConfirmedHardCuts()
        val c = video("c")
        engine.prepare(c)
        engine.play(c) // hard cut to the hidden slot, pending
        val d = video("d")
        engine.prepare(d) // parked in the slot on screen
        engine.play(d) // hard cut back onto the slot on screen
        val slot = engine.renderState.activeSlot

        playerOf(d).listener.onVideoSizeChanged(VideoSize(1000, 500))
        val aspect = if (slot == 0) engine.renderState.aspectRatioA else engine.renderState.aspectRatioB
        assertEquals(2f, aspect)
    }

    // --- Watchdog ---

    @Test
    fun `a clip that stops advancing is restarted`() {
        start(GaplessVideoConfig(stallTimeoutMs = 3_000))
        val a = video("a")
        playFirst(a)
        val pa = playerOf(a)
        val prepares = pa.prepares

        idle(4_500)
        assertEquals(1, pa.stops)
        idle(1_000)
        assertEquals(prepares + 1, pa.prepares)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `the retry budget refills after steady playback even with the stall check off`() {
        start(GaplessVideoConfig(stallTimeoutMs = 0))
        val a = video("a")
        playFirst(a)
        val pa = playerOf(a)

        repeat(MAX_RETRIES + 2) {
            pa.error(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
            idle(1_010)
            pa.playbackState = Player.STATE_READY
            repeat(32) {
                pa.position += 1_000
                idle(1_000)
            }
        }
        assertTrue(errors.isEmpty())
        assertSame("never got far enough to rebuild", pa, playerOf(a))
    }

    @Test
    fun `a stall failure followed by play leaves one watchdog running`() {
        start(GaplessVideoConfig(stallTimeoutMs = 2_000))
        val a = video("a")
        playFirst(a)
        val d = video("d")
        // The worst case: the host plays the next clip from inside the error callback, while the
        // watchdog tick that failed the clip is still on the stack.
        engine.onErrorCallback = {
            errors += it
            engine.play(d)
        }

        var waited = 0
        while (errors.isEmpty() && waited < 60) {
            idle(1_000)
            waited++
        }
        assertEquals(1, errors.size)

        val pd = playerOf(d)
        pd.playbackState = Player.STATE_READY
        fakes.forEach { it.positionReads = 0 }
        repeat(10) {
            pd.position += 500
            idle(1_000)
        }
        val ticks = fakes.sumOf { it.positionReads }
        assertTrue("expected one tick a second, got $ticks in 10s", ticks in 9..11)
    }

    // --- Helpers ---

    /** Puts the engine into confirmed hard-cut mode with "b" playing in the slot on screen. */
    private fun enterConfirmedHardCuts() {
        playFirst(video("a"))
        val b = video("b")
        engine.prepare(b)
        playerOf(b).error(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)
        engine.play(b)
        playerOf(b).apply {
            firstFrame()
            playbackState = Player.STATE_READY
        }
    }

    /** Stages [next], has [playing] lose its decoder, then cuts to [next] and lets it draw. */
    private fun cutAfterReclaim(playing: PlaybackItem.Video, next: PlaybackItem.Video) {
        engine.prepare(next)
        playerOf(next).firstFrame()
        playerOf(playing).error(PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED)
        assertTrue("the staged clip is parked", engine.isNextReady)
        engine.play(next)
        playerOf(next).apply {
            firstFrame()
            playbackState = Player.STATE_READY
        }
    }

    private fun playFirst(item: PlaybackItem.Video) {
        engine.prepare(item)
        playerOf(item).firstFrame()
        engine.play(item)
        playerOf(item).playbackState = Player.STATE_READY
    }

    private fun playerOf(item: PlaybackItem.Video): FakePlayer = fakes.last { it.uri == item.uri }

    private fun video(id: String) =
        PlaybackItem.Video(uri = "https://example.com/$id.mp4", mimeType = "video/mp4").apply { assetId = id }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun httpError(status: Int) = PlaybackException(
        "HTTP $status",
        HttpDataSource.InvalidResponseCodeException(
            status, null, null, emptyMap(), DataSpec(Uri.parse("https://example.com")), ByteArray(0),
        ),
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
    )

    /** A stand-in ExoPlayer that records what the engine does to it. */
    private class FakePlayer {
        val mock: ExoPlayer = mockk(relaxed = true)
        lateinit var listener: Player.Listener
        var prepares = 0
        var preparesSinceMedia = 0
        var stops = 0
        var released = false
        var playbackState = Player.STATE_IDLE
        var position = 0L
        var positionReads = 0
        var mediaItem: MediaItem? = null
        private var playWhenReady = false
        private var volume = 1f
        private var params: TrackSelectionParameters = TrackSelectionParameters.DEFAULT

        val uri: String? get() = mediaItem?.localConfiguration?.uri?.toString()

        init {
            every { mock.addListener(any()) } answers { listener = firstArg() }
            every { mock.prepare() } answers {
                prepares++
                preparesSinceMedia++
                playbackState = Player.STATE_BUFFERING
            }
            every { mock.stop() } answers {
                stops++
                playbackState = Player.STATE_IDLE
            }
            every { mock.release() } answers { released = true }
            every { mock.playbackState } answers { playbackState }
            every { mock.currentPosition } answers {
                positionReads++
                position
            }
            every { mock.playWhenReady } answers { playWhenReady }
            every { mock.playWhenReady = any() } answers { playWhenReady = firstArg() }
            every { mock.volume } answers { volume }
            every { mock.volume = any() } answers { volume = firstArg() }
            every { mock.trackSelectionParameters } answers { params }
            every { mock.trackSelectionParameters = any() } answers { params = firstArg() }
            every { mock.setMediaItem(any<MediaItem>()) } answers {
                mediaItem = firstArg()
                preparesSinceMedia = 0
            }
            every { mock.setMediaItem(any<MediaItem>(), any<Long>()) } answers {
                mediaItem = firstArg()
                position = secondArg()
                preparesSinceMedia = 0
            }
            every { mock.clearMediaItems() } answers { mediaItem = null }
        }

        fun firstFrame() = listener.onRenderedFirstFrame()

        fun error(code: Int) = fire(PlaybackException("error $code", null, code))

        fun fire(error: PlaybackException) {
            playbackState = Player.STATE_IDLE
            listener.onPlayerError(error)
        }
    }
}
