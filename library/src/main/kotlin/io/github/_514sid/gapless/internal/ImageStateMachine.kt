package io.github._514sid.gapless.internal

import android.content.Context
import androidx.annotation.MainThread
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

@MainThread
internal class ImageStateMachine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val loadImage: suspend (model: Any, width: Int, height: Int) -> Unit = { model, w, h ->
        context.imageLoader.execute(
            ImageRequest.Builder(context)
                .data(model)
                .size(w, h)
                .allowHardware(true)
                .build()
        )
    }
) {
    companion object {
        private const val UHD_LONG_SIDE = 3840
        private const val UHD_SHORT_SIDE = 2160
    }

    var renderState by mutableStateOf(ImagePlayerState())
        private set

    var containerWidth: Int = 0
    var containerHeight: Int = 0

    private var prepareJob: Job? = null
    private var pendingItem: PlaybackItem.Image? = null

    val isPrepareComplete: Boolean
        get() = prepareJob?.isActive != true

    private val inactiveSlot: Int
        get() = if (renderState.activeSlot == 0) 1 else 0

    fun prepare(item: PlaybackItem.Image) {
        prepareJob?.cancel()
        pendingItem = item

        renderState = renderState.setSlot(
            slot = inactiveSlot,
            value = ImageSlotState(item.model)
        )

        val isPortrait = containerHeight > containerWidth
        val maxW = if (isPortrait) UHD_SHORT_SIDE else UHD_LONG_SIDE
        val maxH = if (isPortrait) UHD_LONG_SIDE else UHD_SHORT_SIDE

        val targetW = if (containerWidth > 0) containerWidth.coerceAtMost(maxW) else maxW
        val targetH = if (containerHeight > 0) containerHeight.coerceAtMost(maxH) else maxH

        prepareJob = scope.launch(Dispatchers.IO.limitedParallelism(1)) {
            loadImage(item.model, targetW, targetH)
        }
    }

    fun play(item: PlaybackItem.Image) {
        prepareJob?.cancel()
        prepareJob = null

        val target = inactiveSlot

        if (pendingItem?.playbackId != item.playbackId) {
            renderState = renderState.setSlot(
                slot = target,
                value = ImageSlotState(item.model)
            )
        }

        renderState = renderState.copy(activeSlot = target)
        pendingItem = null
    }

    fun cancelPrepare() {
        prepareJob?.cancel()
        prepareJob = null
        renderState = renderState.setSlot(slot = inactiveSlot, value = null)
        pendingItem = null
    }

    fun clear() {
        prepareJob?.cancel()
        prepareJob = null
        pendingItem = null
        renderState = ImagePlayerState()
    }

    private fun ImagePlayerState.setSlot(slot: Int, value: ImageSlotState?) =
        if (slot == 0) copy(slotA = value) else copy(slotB = value)
}