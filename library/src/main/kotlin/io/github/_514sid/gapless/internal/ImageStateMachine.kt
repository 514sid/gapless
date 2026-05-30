package io.github._514sid.gapless.internal

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class ImageStateMachine(
    private val context: Context,
    private val scope: CoroutineScope
) {

    var renderState by mutableStateOf(ImagePlayerState())
        private set

    private var prepareJob: Job? = null
    private var pendingItem: PlaybackItem.Image? = null

    /**
     * Preload [item] into the inactive slot via Coil.
     * Cancels any previous in-flight preload.
     */
    fun prepare(item: PlaybackItem.Image) {
        prepareJob?.cancel()
        pendingItem = item

        val targetSlot = nextSlot()

        // Pre-populate the slot so the composable reflects the pending image immediately
        // even if Coil has not finished downloading yet.
        renderState = renderState.setSlot(
            slot = targetSlot,
            value = ImageSlotState(item.model, item.targetAspectRatio)
        )

        prepareJob = scope.launch(Dispatchers.IO.limitedParallelism(1)) {
            context.imageLoader.enqueue(
                ImageRequest.Builder(context)
                    .data(item.model)
                    .allowHardware(false)
                    .build()
            )
        }
    }

    /**
     * Make [item] visible.
     *
     * If [item] was the pending prepare, flips to the already-loaded slot.
     * Otherwise, cancels the pending prepare, stages [item] inline, and shows it.
     */
    fun play(item: PlaybackItem.Image) {
        prepareJob?.cancel()
        prepareJob = null

        val targetSlot = if (pendingItem?.playbackId == item.playbackId) {
            nextSlot()
        } else {
            val slot = nextSlot()
            renderState = renderState.setSlot(
                slot = slot,
                value = ImageSlotState(item.model, item.targetAspectRatio)
            )
            slot
        }

        renderState = renderState.copy(activeSlot = targetSlot)
        pendingItem = null
    }

    /** Cancel the in-flight preload and remove the staged slot without affecting the active slot. */
    fun cancelPrepare() {
        prepareJob?.cancel()
        prepareJob = null
        val stagedSlot = nextSlot()
        renderState = if (stagedSlot == 0) renderState.copy(slotA = null)
                      else renderState.copy(slotB = null)
        pendingItem = null
    }

    /** Clear both slots and cancel any in-flight preload. */
    fun clear() {
        prepareJob?.cancel()
        prepareJob = null
        pendingItem = null
        renderState = ImagePlayerState()
    }

    private fun nextSlot() = if (renderState.activeSlot == 0) 1 else 0

    private fun ImagePlayerState.setSlot(slot: Int, value: ImageSlotState) =
        if (slot == 0) copy(slotA = value) else copy(slotB = value)
}
