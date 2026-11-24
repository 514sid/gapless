package io.github._514sid.gapless.internal.image

import android.content.Context
import androidx.annotation.MainThread
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import coil3.imageLoader
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.size.Precision
import coil3.size.Scale
import coil3.size.Size
import io.github._514sid.gapless.GaplessFit
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import io.github._514sid.gapless.internal.PlaybackItem
import kotlinx.coroutines.launch
import java.util.UUID


@MainThread
internal class ImageStateMachine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val loadImage: suspend (model: Any, width: Int, height: Int, fit: GaplessFit) -> Unit = { model, w, h, fit ->
        val result = context.imageLoader.execute(
            ImageRequest.Builder(context)
                .data(model)
                // Same as AsyncImage with the slot's ContentScale, so its request is a cache hit:
                // None decodes the whole image, Crop and FillBounds to fill the size, Fit to fit inside it.
                .apply { if (fit == GaplessFit.Original) size(Size.ORIGINAL) else size(w, h) }
                .scale(if (fit == GaplessFit.Contain) Scale.FIT else Scale.FILL)
                .precision(Precision.INEXACT)
                .allowHardware(true)
                .build()
        )
        if (result is ErrorResult) throw result.throwable
    }
) {
    private companion object {
        const val UHD_LONG_SIDE = 3840
        const val UHD_SHORT_SIDE = 2160

        /** How long a preload waits for the first layout before decoding at the 4K cap. */
        const val MEASURE_WAIT_MS = 1_000L

        /** One decode at a time; a new prepare cancels the previous load anyway. */
        val loadDispatcher = Dispatchers.IO.limitedParallelism(1)
    }

    var renderState by mutableStateOf(ImagePlayerState())
        private set

    /** A staged image failed to load. */
    var onError: ((assetId: String, message: String) -> Unit)? = null

    /** The image on screen failed to display. */
    var onPlaybackError: ((message: String) -> Unit)? = null

    /** The size images are shown at: the player's size after rotation. */
    private val containerSize = MutableStateFlow(0 to 0)

    var containerWidth: Int
        get() = containerSize.value.first
        set(value) { containerSize.value = value to containerSize.value.second }

    var containerHeight: Int
        get() = containerSize.value.second
        set(value) { containerSize.value = containerSize.value.first to value }

    fun updateContainerSize(width: Int, height: Int) {
        containerSize.value = width to height
    }

    private var prepareJob: Job? = null
    private var pendingItem: PlaybackItem.Image? = null
    @Volatile private var prepareFailed = false // set from the IO load job

    /** True once the staged image has decoded. A failed load never becomes ready. */
    val isPrepareComplete: Boolean
        get() = prepareJob?.isActive != true && !prepareFailed

    private val inactiveSlot: Int
        get() = if (renderState.activeSlot == 0) 1 else 0

    fun prepare(item: PlaybackItem.Image) {
        prepareJob?.cancel()
        pendingItem = item
        prepareFailed = false

        renderState = renderState.setSlot(
            slot = inactiveSlot,
            value = ImageSlotState(item.model, item.playbackId, loaded = false, framing = item.framing)
        )

        val owner = scope.coroutineContext[ContinuationInterceptor] ?: Dispatchers.Main
        prepareJob = scope.launch(loadDispatcher) {
            try {
                // Decoding before the first layout would use the 4K cap, then decode again at the
                // real size once shown. Give layout a moment first.
                val (width, height) = withTimeoutOrNull(MEASURE_WAIT_MS) {
                    containerSize.first { (w, h) -> w > 0 && h > 0 }
                } ?: containerSize.value
                val (targetW, targetH) = targetSize(width, height)
                loadImage(item.model, targetW, targetH, item.framing.fit)
                // Decoded and cached: now let the slot show it.
                withContext(owner) { markLoaded(item) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(item, e)
            } catch (e: OutOfMemoryError) {
                // An oversized image must not take the app down with it.
                fail(item, e)
            }
        }
    }

    private fun markLoaded(item: PlaybackItem.Image) {
        val slot = inactiveSlot
        val state = if (slot == 0) renderState.slotA else renderState.slotB
        if (state?.playbackId == item.playbackId && !state.loaded) {
            renderState = renderState.setSlot(slot, state.copy(loaded = true))
        }
    }

    private fun targetSize(width: Int, height: Int): Pair<Int, Int> {
        val isPortrait = height > width
        val maxW = if (isPortrait) UHD_SHORT_SIDE else UHD_LONG_SIDE
        val maxH = if (isPortrait) UHD_LONG_SIDE else UHD_SHORT_SIDE
        val targetW = if (width > 0) width.coerceAtMost(maxW) else maxW
        val targetH = if (height > 0) height.coerceAtMost(maxH) else maxH
        return targetW to targetH
    }

    /**
     * A slot's own decode failed (out of memory, say) after its preload succeeded. Without this the
     * slot just shows black and nobody hears about it.
     */
    /** The image passed to [play] is decoded and on screen. */
    var onShown: ((playbackId: UUID) -> Unit)? = null

    /** The image passed to [play] that hasn't been reported shown yet. */
    private var awaitingShown: UUID? = null

    /** The item each slot last finished decoding, so a cut to a decoded slot reports at once. */
    private val decoded = arrayOfNulls<UUID>(2)

    /** A slot finished decoding its image. Hidden slots decode too, while staged. */
    fun onDisplayed(slot: ImageSlotState) {
        val playbackId = slot.playbackId ?: return
        val index = when (playbackId) {
            renderState.slotA?.playbackId -> 0
            renderState.slotB?.playbackId -> 1
            else -> return
        }
        decoded[index] = playbackId
        if (index == renderState.activeSlot) reportShown(playbackId)
    }

    private fun reportShown(playbackId: UUID) {
        if (playbackId != awaitingShown) return
        awaitingShown = null
        onShown?.invoke(playbackId)
    }

    fun onDisplayError(slot: ImageSlotState, message: String) {
        // A slot still waiting for its preload has no image yet, and Coil reports that empty
        // request as an error. Nothing was decoded, so nothing failed.
        if (!slot.loaded) return
        val playbackId = slot.playbackId ?: return
        val active = if (renderState.activeSlot == 0) renderState.slotA else renderState.slotB
        val pending = pendingItem
        when {
            active?.playbackId == playbackId -> onPlaybackError?.invoke("Image failed to display: $message")
            pending?.playbackId == playbackId && !prepareFailed -> {
                prepareJob?.cancel()
                fail(pending, IllegalStateException(message))
            }
        }
    }

    private fun fail(item: PlaybackItem.Image, error: Throwable) {
        prepareFailed = true
        onError?.invoke(item.assetId, error.message ?: "Image load failed")
    }

    fun play(item: PlaybackItem.Image) {
        prepareJob?.cancel()
        prepareJob = null
        prepareFailed = false

        val target = inactiveSlot

        val staged = if (target == 0) renderState.slotA else renderState.slotB
        if (staged?.playbackId != item.playbackId) {
            renderState = renderState.setSlot(
                slot = target,
                value = ImageSlotState(item.model, item.playbackId, framing = item.framing)
            )
        } else if (!staged.loaded) {
            // Cut before the preload finished: the slot loads it itself now.
            renderState = renderState.setSlot(target, staged.copy(loaded = true))
        }

        renderState = renderState.copy(activeSlot = target)
        pendingItem = null
        awaitingShown = item.playbackId
        if (decoded[target] == item.playbackId) reportShown(item.playbackId)
    }

    fun clear() {
        awaitingShown = null
        decoded.fill(null)
        prepareJob?.cancel()
        prepareJob = null
        prepareFailed = false
        pendingItem = null
        renderState = ImagePlayerState()
    }

    private fun ImagePlayerState.setSlot(slot: Int, value: ImageSlotState?) =
        if (slot == 0) copy(slotA = value) else copy(slotB = value)
}