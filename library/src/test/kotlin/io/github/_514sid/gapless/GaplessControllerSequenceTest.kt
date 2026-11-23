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
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.random.Random

/**
 * Calls to the controller in the orders a host gets them in when its schedule changes under it:
 * a playlist swapped mid-play, stop and start, a staged asset replaced before its turn, and a
 * long random stream of calls and player reports checked against the rules events must follow.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GaplessControllerSequenceTest {

    @BeforeEach
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private val preloadMs = 2_000L
    private val readyTimeoutMs = 10_000L

    /** A controller and a fake player that records commands and events. */
    private inner class Harness(scope: TestScope, var autoShow: Boolean = true) {
        val controller = GaplessController(scope.backgroundScope, preloadMs, readyTimeoutMs)
        val commands = mutableListOf<PlayerCommand>()
        val events = mutableListOf<GaplessEvent>()
        val ready = mutableSetOf<UUID>()

        init {
            controller.isReadyProvider = { it in ready }
            scope.backgroundScope.launch {
                controller.commands.collect {
                    commands += it
                    if (autoShow && it is PlayerCommand.Play) controller.onShown(it.item.playbackId, null)
                }
            }
            scope.backgroundScope.launch { controller.events.collect { events += it } }
        }

        val currentId: String? get() = controller.currentState.value?.asset?.id
        val plays: List<PlaybackItem> get() = commands.filterIsInstance<PlayerCommand.Play>().map { it.item }
        val prepares: List<PlaybackItem> get() = commands.filterIsInstance<PlayerCommand.Prepare>().map { it.item }
        fun started(): List<String> = events.filterIsInstance<GaplessEvent.Started>().map { it.asset.id }
        fun ended(): List<String> = events.filterIsInstance<GaplessEvent.Ended>().map { it.asset.id }
    }

    private fun video(id: String) = GaplessAsset(id = id, uri = "file:///$id.mp4", mimeType = "video/mp4")

    private fun TestScope.settle() {
        advanceTimeBy(preloadMs + readyTimeoutMs + 1)
        runCurrent()
    }

    private fun TestScope.startedWith(asset: GaplessAsset): Harness =
        Harness(this).also {
            runCurrent()
            it.controller.start(asset)
            settle()
        }

    /** Stages [next] and cuts to it, as a schedule does at the end of each slot. */
    private fun TestScope.advanceTo(h: Harness, next: GaplessAsset) {
        h.controller.prepareNext(next)
        runCurrent()
        h.controller.play(next)
        runCurrent()
    }

    // Swapping the playlist mid-play

    @Test
    fun `a new playlist replaces the staged asset and plays its own first asset at once`() = runTest {
        val h = startedWith(video("a1"))
        h.controller.prepareNext(video("a2"))
        runCurrent()

        // The playlist changes: the host stages the new list's first asset in place of a2.
        advanceTo(h, video("b1"))

        assertEquals("b1", h.currentId)
        assertEquals(listOf("a1", "b1"), h.started())
        assertEquals(listOf("a1"), h.ended())
        assertTrue(h.plays.none { it.assetId == "a2" })
    }

    @Test
    fun `a new playlist cut to without staging waits for its asset, and the old staged asset never plays`() = runTest {
        val h = startedWith(video("a1"))
        h.controller.prepareNext(video("a2"))
        runCurrent()

        h.controller.play(video("b1"))
        runCurrent()
        assertEquals("a1", h.currentId)
        h.ready += h.prepares.last().playbackId
        advanceTimeBy(100)
        runCurrent()

        assertEquals("b1", h.currentId)
        assertTrue(h.plays.none { it.assetId == "a2" })
    }

    @Test
    fun `an asset from the old playlist is loaded again if it comes back`() = runTest {
        val h = startedWith(video("a1"))
        h.controller.prepareNext(video("a2"))
        runCurrent()
        val stagedA2 = h.prepares.last()
        advanceTo(h, video("b1"))

        advanceTo(h, video("a2"))

        assertEquals("a2", h.currentId)
        assertNotEquals(stagedA2.playbackId, h.plays.last().playbackId)
    }

    @Test
    fun `swapping playlists every slot keeps one asset on screen and ends each one once`() = runTest {
        val h = startedWith(video("p0"))
        repeat(30) { i ->
            // Each slot comes from a different playlist, staged and cut to straight away.
            advanceTo(h, video("p${i + 1}"))
        }

        assertEquals((0..30).map { "p$it" }, h.started())
        assertEquals((0..29).map { "p$it" }, h.ended())
        assertEquals("p30", h.currentId)
    }

    // stop() then start()

    @Test
    fun `stop then start plays the new asset after its preload window`() = runTest {
        val h = startedWith(video("a"))

        h.controller.stop()
        runCurrent()
        assertNull(h.controller.currentState.value)

        h.controller.start(video("b"))
        settle()

        assertEquals("b", h.currentId)
        assertEquals(listOf("a", "b"), h.started())
    }

    @Test
    fun `stop forgets the staged asset, so playing it after start loads it again`() = runTest {
        val h = startedWith(video("a"))
        h.controller.prepareNext(video("b"))
        runCurrent()
        val staged = h.prepares.last()

        h.controller.stop()
        h.controller.start(video("c"))
        settle()
        h.controller.play(video("b"))
        runCurrent()

        assertEquals("c", h.currentId)
        val reloaded = h.prepares.last()
        assertEquals("b", reloaded.assetId)
        assertNotEquals(staged.playbackId, reloaded.playbackId)
    }

    @Test
    fun `stop during a play waiting for readiness cancels the cut`() = runTest {
        val h = startedWith(video("a"))
        h.controller.play(video("b"))
        runCurrent()

        h.controller.stop()
        h.ready += h.prepares.last().playbackId
        settle()

        assertNull(h.controller.currentState.value)
        assertTrue(h.plays.none { it.assetId == "b" })
    }

    @Test
    fun `reports about the stopped asset that arrive after stop are ignored`() = runTest {
        val h = Harness(this, autoShow = false)
        runCurrent()
        h.controller.start(video("a"))
        advanceTimeBy(preloadMs + 1)
        runCurrent()
        val a = h.plays.single()

        h.controller.stop()
        runCurrent()
        h.controller.onShown(a.playbackId, null)
        h.controller.onPreloadError("a", "late")
        h.controller.onPlaybackError("late")
        settle()

        assertTrue(h.started().isEmpty())
        assertTrue(h.events.none { it is GaplessEvent.PreloadError || it is GaplessEvent.PlaybackError })
        assertNull(h.controller.currentState.value)
    }

    @Test
    fun `stop and start back to back many times end with the last start on screen`() = runTest {
        val h = Harness(this)
        runCurrent()
        repeat(20) { i ->
            h.controller.start(video("s$i"))
            if (i % 3 == 0) advanceTimeBy(preloadMs / 2)
            h.controller.stop()
        }
        h.controller.start(video("last"))
        settle()

        assertEquals("last", h.currentId)
        assertEquals("last", h.started().last())
        assertEquals(1, h.plays.count { it.assetId == "last" })
    }

    // A staged asset replaced before play()

    @Test
    fun `play of the replaced asset loads it again instead of cutting to the newer one`() = runTest {
        val h = startedWith(video("a"))
        h.controller.prepareNext(video("b"))
        h.controller.prepareNext(video("c"))
        runCurrent()
        val stagedB = h.prepares.first { it.assetId == "b" }

        h.controller.play(video("b"))
        runCurrent()

        val reloaded = h.prepares.last()
        assertEquals("b", reloaded.assetId)
        assertNotEquals(stagedB.playbackId, reloaded.playbackId)
        h.ready += reloaded.playbackId
        advanceTimeBy(100)
        runCurrent()
        assertEquals("b", h.currentId)
        assertSame(reloaded, h.plays.last())
    }

    @Test
    fun `play of the replacement cuts at once`() = runTest {
        val h = startedWith(video("a"))
        h.controller.prepareNext(video("b"))
        h.controller.prepareNext(video("c"))
        runCurrent()
        val stagedC = h.prepares.last()

        h.controller.play(video("c"))
        runCurrent()

        assertEquals("c", h.currentId)
        assertSame(stagedC, h.plays.last())
    }

    @Test
    fun `staging the same asset twice cuts to the latest copy`() = runTest {
        val h = startedWith(video("a"))
        h.controller.prepareNext(video("b"))
        h.controller.prepareNext(video("b"))
        runCurrent()
        val latest = h.prepares.last()

        h.controller.play(video("b"))
        runCurrent()

        assertSame(latest, h.plays.last())
    }

    @Test
    fun `a preload error for the replaced asset does not disturb its replacement`() = runTest {
        val h = startedWith(video("a"))
        h.controller.prepareNext(video("b"))
        h.controller.prepareNext(video("c"))
        runCurrent()
        val stagedC = h.prepares.last()

        h.controller.onPreloadError("b", "late failure of the replaced asset")
        runCurrent()
        h.controller.play(video("c"))
        runCurrent()

        assertEquals("c", h.currentId)
        assertSame(stagedC, h.plays.last())
    }

    // A random stream of calls and player reports

    /**
     * Checks events as they arrive against the rules a host relies on: one asset on screen, Ended
     * only for that asset and before the next Started, each playback started once, and
     * currentState matching what is on screen.
     */
    private class EventRules(private val h: Harness) {
        private var checked = 0
        private var onScreen: UUID? = null
        private val startedIds = mutableSetOf<UUID>()

        fun check(context: () -> String, stateToo: Boolean = true) {
            val playIds = h.plays.map { it.playbackId }
            assertEquals(playIds.size, playIds.toSet().size) { "an item was played twice; ${context()}" }
            val preparedIds = h.prepares.map { it.playbackId }.toSet()
            assertTrue(playIds.all { it in preparedIds }) { "an item was played without a prepare; ${context()}" }

            while (checked < h.events.size) {
                when (val e = h.events[checked++]) {
                    is GaplessEvent.Started -> {
                        assertNull(onScreen) { "Started ${e.asset.id} while another was on screen; ${context()}" }
                        assertTrue(startedIds.add(e.playbackId)) { "Started ${e.asset.id} twice; ${context()}" }
                        assertTrue(e.playbackId in playIds) { "Started ${e.asset.id} that was never played; ${context()}" }
                        onScreen = e.playbackId
                    }
                    is GaplessEvent.Ended -> {
                        assertEquals(onScreen, e.playbackId) { "Ended ${e.asset.id}, which wasn't on screen; ${context()}" }
                        onScreen = null
                    }
                    is GaplessEvent.PlaybackError -> onScreen = null
                    else -> Unit
                }
            }
            if (stateToo) assertEquals(onScreen, h.controller.currentState.value?.playbackId) {
                "currentState doesn't match what is on screen; ${context()}"
            }
        }

        /** stop() takes the asset off screen without an Ended. Call after stop() was processed. */
        fun stopped(context: () -> String) {
            check(context, stateToo = false)
            onScreen = null
            check(context)
        }
    }

    @Test
    fun `a random stream of calls and player reports keeps the event rules`() {
        repeat(25) { seed -> randomRun(seed) }
    }

    private fun randomRun(seed: Int) = runTest {
        val rnd = Random(seed)
        val h = Harness(this, autoShow = false)
        runCurrent()
        val rules = EventRules(h)
        val ids = (1..6).map { "v$it" }
        val log = ArrayDeque<String>()
        fun context() = "seed $seed, last steps: ${log.joinToString(" | ")}"
        fun note(step: String) {
            log.addLast(step)
            if (log.size > 12) log.removeFirst()
        }

        repeat(600) {
            when (rnd.nextInt(100)) {
                in 0..4 -> rnd.nextOf(ids).let { note("start($it)"); h.controller.start(video(it)) }
                in 5..24 -> rnd.nextOf(ids).let { note("prepareNext($it)"); h.controller.prepareNext(video(it)) }
                in 25..44 -> rnd.nextOf(ids).let { note("play($it)"); h.controller.play(video(it)) }
                in 45..48 -> {
                    note("stop()")
                    h.controller.stop()
                    runCurrent()
                    rules.stopped(::context)
                }
                in 49..62 -> h.plays.lastOrNull()?.let {
                    // The player shows the latest item, sometimes with its real start time.
                    note("shown(${it.assetId})")
                    h.controller.onShown(it.playbackId, if (rnd.nextBoolean()) null else 1_000L)
                }
                in 63..66 -> h.plays.randomOrNullWith(rnd)?.let {
                    note("late shown(${it.assetId})")
                    h.controller.onShown(it.playbackId, null)
                }
                in 67..74 -> h.prepares.randomOrNullWith(rnd)?.let {
                    note("ready(${it.assetId})")
                    h.ready += it.playbackId
                }
                in 75..79 -> rnd.nextOf(ids).let { note("preloadError($it)"); h.controller.onPreloadError(it, "random") }
                in 80..82 -> { note("playbackError"); h.controller.onPlaybackError("random") }
                in 83..85 -> h.prepares.lastOrNull()?.let {
                    note("preloadMissed(${it.assetId})")
                    h.controller.onPreloadMissed(it.assetId, 5_000L)
                }
                else -> rnd.nextLong(1, readyTimeoutMs + 500).let { note("wait ${it}ms"); advanceTimeBy(it) }
            }
            runCurrent()
            rules.check(::context)
        }

        // Whatever state the stream left behind, the controller still plays normally.
        h.autoShow = true
        h.controller.stop()
        runCurrent()
        rules.stopped(::context)
        h.controller.start(video("after"))
        settle()
        rules.check(::context)
        assertEquals("after", h.currentId) { "start after the stream didn't play; ${context()}" }
        advanceTo(h, video("next"))
        rules.check(::context)
        assertEquals("next", h.currentId) { "play after the stream didn't cut; ${context()}" }
    }

    private fun <T> Random.nextOf(list: List<T>): T = list[nextInt(list.size)]

    private fun <T> List<T>.randomOrNullWith(rnd: Random): T? = if (isEmpty()) null else this[rnd.nextInt(size)]
}
