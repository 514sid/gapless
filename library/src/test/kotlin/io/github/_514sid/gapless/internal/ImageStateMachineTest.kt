package io.github._514sid.gapless.internal

import android.content.Context
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import io.github._514sid.gapless.internal.image.ImagePlayerState
import io.github._514sid.gapless.internal.image.ImageStateMachine
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ImageStateMachineTest {
    private lateinit var machine: ImageStateMachine

    private fun imageItem(model: Any = "image.jpg", width: Int = 1920, height: Int = 1080) =
        PlaybackItem.Image(model, width, height)

    @BeforeEach
    fun setup() {
        machine = ImageStateMachine(
            context = mockk<Context>(relaxed = true),
            scope = TestScope(),
            loadImage = { _, _, _ -> }
        )
    }

    @Test
    fun `initial state has both slots null and activeSlot 0`() {
        val state = machine.renderState
        assertNull(state.slotA)
        assertNull(state.slotB)
        assertEquals(0, state.activeSlot)
    }

    // prepare()

    @Test
    fun `prepare stages item in slot B when activeSlot is 0`() {
        val item = imageItem("photo.jpg", 1920, 1080)
        machine.prepare(item)

        val state = machine.renderState
        assertNull(state.slotA)
        assertNotNull(state.slotB)
        assertEquals("photo.jpg", state.slotB?.model)
        assertEquals(0, state.activeSlot)
    }

    @Test
    fun `prepare stages item in slot A when activeSlot is 1`() {
        machine.play(imageItem("first.jpg"))  // moves activeSlot to 1
        val item = imageItem("second.jpg", 1280, 720)
        machine.prepare(item)

        val state = machine.renderState
        assertNotNull(state.slotA)
        assertEquals("second.jpg", state.slotA?.model)
        assertEquals(1, state.activeSlot)
    }

    @Test
    fun `second prepare replaces the first in the inactive slot`() {
        machine.prepare(imageItem("first.jpg"))
        machine.prepare(imageItem("second.jpg"))

        assertEquals("second.jpg", machine.renderState.slotB!!.model)
    }

    // play() — matching pendingItem

    @Test
    fun `play with matching pending item flips activeSlot to the prepared slot`() {
        val item = imageItem("photo.jpg")
        machine.prepare(item)
        machine.play(item)

        val state = machine.renderState
        assertEquals(1, state.activeSlot)
        assertEquals("photo.jpg", state.slotB!!.model)
    }

    @Test
    fun `play with matching pending item preserves the prepared slot content`() {
        val item = imageItem("photo.jpg", 1920, 1080)
        machine.prepare(item)
        machine.play(item)

        val slot = machine.renderState.slotB!!
        assertEquals("photo.jpg", slot.model)
    }

    // play() — no pending / different item

    @Test
    fun `play without prepare stages item and activates it`() {
        val item = imageItem("direct.jpg", 1920, 1080)
        machine.play(item)

        val state = machine.renderState
        assertEquals(1, state.activeSlot)
        assertEquals("direct.jpg", state.slotB!!.model)
    }

    @Test
    fun `play with different item than pending stages the new item`() {
        machine.prepare(imageItem("pending.jpg"))
        val played = imageItem("played.jpg")
        machine.play(played)

        val state = machine.renderState
        assertEquals(1, state.activeSlot)
        assertEquals("played.jpg", state.slotB!!.model)
    }

    @Test
    fun `playing same item twice re-stages it in the opposite slot each time`() {
        val item = imageItem("photo.jpg")
        machine.play(item)
        assertEquals(1, machine.renderState.activeSlot)
        assertEquals("photo.jpg", machine.renderState.slotB!!.model)

        machine.play(item)
        assertEquals(0, machine.renderState.activeSlot)
        assertEquals("photo.jpg", machine.renderState.slotA!!.model)
    }

    @Test
    fun `sequential plays alternate between slots`() {
        machine.play(imageItem("first.jpg"))
        assertEquals(1, machine.renderState.activeSlot)
        assertEquals("first.jpg", machine.renderState.slotB!!.model)

        machine.play(imageItem("second.jpg"))
        assertEquals(0, machine.renderState.activeSlot)
        assertEquals("second.jpg", machine.renderState.slotA!!.model)

        machine.play(imageItem("third.jpg"))
        assertEquals(1, machine.renderState.activeSlot)
        assertEquals("third.jpg", machine.renderState.slotB!!.model)
    }

    // clear()

    @Test
    fun `clear resets render state to default`() {
        machine.prepare(imageItem("photo.jpg"))
        machine.play(imageItem("played.jpg"))
        machine.clear()

        val state = machine.renderState
        assertNull(state.slotA)
        assertNull(state.slotB)
        assertEquals(0, state.activeSlot)
    }

    @Test
    fun `clear after only prepare resets state`() {
        machine.prepare(imageItem("photo.jpg"))
        machine.clear()

        assertEquals(ImagePlayerState(), machine.renderState)
    }

    @Test
    fun `prepare uses raw container size if under UHD limits`() = runTest {
        val widthDeferred = CompletableDeferred<Int>()
        val heightDeferred = CompletableDeferred<Int>()

        machine = ImageStateMachine(
            context = mockk(relaxed = true),
            scope = this,
            loadImage = { _, w, h ->
                widthDeferred.complete(w)
                heightDeferred.complete(h)
            }
        )

        machine.containerWidth = 1920
        machine.containerHeight = 1080
        machine.prepare(imageItem("photo.jpg"))

        assertEquals(1920, widthDeferred.await())
        assertEquals(1080, heightDeferred.await())
    }

    @Test
    fun `prepare caps landscape container at landscape UHD limits`() = runTest {
        val widthDeferred = CompletableDeferred<Int>()
        val heightDeferred = CompletableDeferred<Int>()

        machine = ImageStateMachine(
            context = mockk(relaxed = true),
            scope = this,
            loadImage = { _, w, h ->
                widthDeferred.complete(w)
                heightDeferred.complete(h)
            }
        )

        machine.containerWidth = 8000
        machine.containerHeight = 4000
        machine.prepare(imageItem("photo.jpg"))

        assertEquals(3840, widthDeferred.await())
        assertEquals(2160, heightDeferred.await())
    }

    @Test
    fun `prepare caps portrait container at portrait UHD limits`() = runTest {
        val widthDeferred = CompletableDeferred<Int>()
        val heightDeferred = CompletableDeferred<Int>()

        machine = ImageStateMachine(
            context = mockk(relaxed = true),
            scope = this,
            loadImage = { _, w, h ->
                widthDeferred.complete(w)
                heightDeferred.complete(h)
            }
        )

        machine.containerWidth = 4000
        machine.containerHeight = 8000
        machine.prepare(imageItem("photo.jpg"))

        assertEquals(2160, widthDeferred.await())
        assertEquals(3840, heightDeferred.await())
    }

    @Test
    fun `prepare uses max UHD limits when container size is 0 (unmeasured)`() = runTest {
        val widthDeferred = CompletableDeferred<Int>()
        val heightDeferred = CompletableDeferred<Int>()

        machine = ImageStateMachine(
            context = mockk(relaxed = true),
            scope = this,
            loadImage = { _, w, h ->
                widthDeferred.complete(w)
                heightDeferred.complete(h)
            }
        )

        machine.containerWidth = 0
        machine.containerHeight = 0
        machine.prepare(imageItem("photo.jpg"))

        assertEquals(3840, widthDeferred.await())
        assertEquals(2160, heightDeferred.await())
    }

    @Test
    fun `clear aborts the active Coil load request`() = runTest {
        val cancellationDeferred = CompletableDeferred<Boolean>()
        val startedDeferred = CompletableDeferred<Unit>()

        machine = ImageStateMachine(
            context = mockk(relaxed = true),
            scope = this,
            loadImage = { _, _, _ ->
                try {
                    startedDeferred.complete(Unit)
                    awaitCancellation()
                } catch (e: CancellationException) {
                    cancellationDeferred.complete(true)
                    throw e
                }
            }
        )

        machine.prepare(imageItem("slow_photo.jpg"))
        startedDeferred.await()

        machine.clear()

        assertTrue(cancellationDeferred.await())
    }

    @Test
    fun `play aborts the previous preload request`() = runTest {
        val cancellationDeferred = CompletableDeferred<Boolean>()
        val startedDeferred = CompletableDeferred<Unit>()

        machine = ImageStateMachine(
            context = mockk(relaxed = true),
            scope = this,
            loadImage = { _, _, _ ->
                try {
                    startedDeferred.complete(Unit)
                    awaitCancellation()
                } catch (e: CancellationException) {
                    cancellationDeferred.complete(true)
                    throw e
                }
            }
        )

        machine.prepare(imageItem("slow_photo.jpg"))
        startedDeferred.await()

        machine.play(imageItem("slow_photo.jpg"))

        assertTrue(cancellationDeferred.await())
    }

    // Holding a staged image back until its preload has decoded it

    @Test
    fun `a staged image is held back until its preload finishes`() = runTest {
        val release = CompletableDeferred<Unit>()
        machine = ImageStateMachine(
            context = mockk(relaxed = true),
            scope = this,
            loadImage = { _, _, _ -> release.await() },
        )
        machine.updateContainerSize(1920, 1080)
        machine.prepare(imageItem("photo.jpg"))
        assertFalse(machine.renderState.slotB!!.loaded)

        release.complete(Unit)
        // The load runs on a real IO thread and hands back to the test dispatcher to mark the slot.
        val deadline = System.currentTimeMillis() + 5_000
        while (!machine.renderState.slotB!!.loaded && System.currentTimeMillis() < deadline) {
            testScheduler.advanceUntilIdle()
            Thread.sleep(5)
        }
        assertTrue(machine.renderState.slotB!!.loaded)
        assertTrue(machine.isPrepareComplete)
    }

    @Test
    fun `a cut before the preload finishes lets the slot load the image itself`() {
        val item = imageItem("photo.jpg")
        machine.prepare(item)
        assertFalse(machine.renderState.slotB!!.loaded)

        machine.play(item)
        assertTrue(machine.renderState.slotB!!.loaded)
    }

    @Test
    fun `a preload waits for the first layout and decodes at that size`() = runTest {
        val size = CompletableDeferred<Pair<Int, Int>>()
        machine = ImageStateMachine(
            context = mockk(relaxed = true),
            scope = this,
            loadImage = { _, w, h -> size.complete(w to h) },
        )
        machine.prepare(imageItem("photo.jpg"))
        machine.updateContainerSize(1080, 1920)

        assertEquals(1080 to 1920, size.await())
    }

    // Reporting the image shown

    @Test
    fun `a cut to a decoded image reports it shown at once`() {
        val shown = mutableListOf<java.util.UUID>()
        machine.onShown = { shown += it }
        val item = imageItem("photo.jpg")
        machine.prepare(item)
        machine.onDisplayed(machine.renderState.slotB!!) // decoded while still hidden
        assertTrue(shown.isEmpty())

        machine.play(item)
        assertEquals(listOf(item.playbackId), shown)
    }

    @Test
    fun `a cut to an image still decoding reports it once it decodes`() {
        val shown = mutableListOf<java.util.UUID>()
        machine.onShown = { shown += it }
        val item = imageItem("photo.jpg")
        machine.play(item)
        assertTrue(shown.isEmpty())

        machine.onDisplayed(machine.renderState.slotB!!)
        machine.onDisplayed(machine.renderState.slotB!!)
        assertEquals(listOf(item.playbackId), shown)
    }

    // Failures of the slot's own decode

    @Test
    fun `a decode failure on the image on screen is a playback error`() {
        val messages = mutableListOf<String>()
        machine.onPlaybackError = { messages += it }
        val item = imageItem("photo.jpg")
        machine.play(item)

        machine.onDisplayError(machine.renderState.slotB!!, "out of memory")
        assertEquals(1, messages.size)
    }

    @Test
    fun `a decode failure on a staged image fails its preload once`() {
        val failed = mutableListOf<String>()
        machine.onError = { assetId, _ -> failed += assetId }
        val item = imageItem("photo.jpg").apply { assetId = "poster" }
        machine.prepare(item)
        // As handed to the slot once its preload finished.
        val slot = machine.renderState.slotB!!.copy(loaded = true)

        machine.onDisplayError(slot, "out of memory")
        machine.onDisplayError(slot, "out of memory")
        assertEquals(listOf("poster"), failed)
        assertFalse(machine.isPrepareComplete)
    }

    @Test
    fun `an error from a staged slot still waiting for its preload is ignored`() {
        val failed = mutableListOf<String>()
        machine.onError = { assetId, _ -> failed += assetId }
        val item = imageItem("photo.jpg").apply { assetId = "poster" }
        machine.prepare(item)
        val slot = machine.renderState.slotB!!
        assertFalse(slot.loaded)

        // Coil reports a slot with no image to load as an error.
        machine.onDisplayError(slot, "The request's data is null.")
        assertTrue(failed.isEmpty())

        machine.play(item)
        assertTrue(failed.isEmpty())
    }

    @Test
    fun `a decode failure on a slot no longer in use is ignored`() {
        val reported = mutableListOf<String>()
        machine.onError = { _, message -> reported += message }
        machine.onPlaybackError = { reported += it }
        machine.play(imageItem("first.jpg"))
        val old = machine.renderState.slotB!!
        machine.play(imageItem("second.jpg"))

        machine.onDisplayError(old, "late failure")
        assertTrue(reported.isEmpty())
    }
}
