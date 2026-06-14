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

    // cancelPrepare()

    @Test
    fun `cancelPrepare clears the inactive slot and leaves active slot unchanged`() {
        machine.prepare(imageItem("photo.jpg"))
        machine.cancelPrepare()

        val state = machine.renderState
        assertNull(state.slotB)
        assertEquals(0, state.activeSlot)
    }

    @Test
    fun `cancelPrepare after play clears the correct inactive slot`() {
        machine.play(imageItem("first.jpg"))   // activeSlot = 1 (slotB)
        machine.prepare(imageItem("second.jpg")) // staged in slotA (nextSlot = 0)
        machine.cancelPrepare()

        val state = machine.renderState
        assertNull(state.slotA)
        assertNotNull(state.slotB)
        assertEquals(1, state.activeSlot)
    }

    @Test
    fun `cancelPrepare when nothing is prepared is a no-op`() {
        val before = machine.renderState
        machine.cancelPrepare()
        assertEquals(before, machine.renderState)
    }

    @Test
    fun `prepare after cancelPrepare stages in the correct slot`() {
        machine.prepare(imageItem("first.jpg"))
        machine.cancelPrepare()
        machine.prepare(imageItem("second.jpg"))

        assertEquals("second.jpg", machine.renderState.slotB!!.model)
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
    fun `cancelPrepare aborts the active Coil load request`() = runTest {
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
        machine.cancelPrepare()

        assertTrue(cancellationDeferred.await())
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
}