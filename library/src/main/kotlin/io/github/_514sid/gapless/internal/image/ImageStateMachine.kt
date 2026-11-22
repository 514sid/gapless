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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import io.github._514sid.gapless.internal.PlaybackItem
import kotlinx.coroutines.launch

@MainThread
internal class ImageStateMachine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val loadImage: suspend (model: Any, width: Int, height: Int) -> Unit = { model, w, h ->
        val result = context.imageLoader.execute(
            ImageRequest.Builder(context)
                .data(model)
                .size(w, h)
                .allowHardware(true)
                .build()
        )
        if (result is ErrorResult) throw result.throwable
    }
) {
    private companion object {
        const val UHD_LONG_SIDE = 3840
        const val UHD_SHORT_SIDE = 2160

        /** One decode at a time; a new prepare cancels the previous load anyway. */
        val loadDispatcher = Dispatchers.IO.limitedParallelism(1)
    }

    var renderState by mutableStateOf(ImagePlayerState())
        private set

    var onError: ((assetId: String, message: String) -> Unit)? = null

    var containerWidth: Int = 0
    var containerHeight: Int = 0

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
            value = ImageSlotState(item.model)
        )

        val isPortrait = containerHeight > containerWidth
        val maxW = if (isPortrait) UHD_SHORT_SIDE else UHD_LONG_SIDE
        val maxH = if (isPortrait) UHD_LONG_SIDE else UHD_SHORT_SIDE

        val targetW = if (containerWidth > 0) containerWidth.coerceAtMost(maxW) else maxW
        val targetH = if (containerHeight > 0) containerHeight.coerceAtMost(maxH) else maxH

        prepareJob = scope.launch(loadDispatcher) {
            try {
                loadImage(item.model, targetW, targetH)
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

    private fun fail(item: PlaybackItem.Image, error: Throwable) {
        prepareFailed = true
        onError?.invoke(item.assetId, error.message ?: "Image load failed")
    }

    fun play(item: PlaybackItem.Image) {
        prepareJob?.cancel()
        prepareJob = null
        prepareFailed = false

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

    fun clear() {
        prepareJob?.cancel()
        prepareJob = null
        prepareFailed = false
        pendingItem = null
        renderState = ImagePlayerState()
    }

    private fun ImagePlayerState.setSlot(slot: Int, value: ImageSlotState?) =
        if (slot == 0) copy(slotA = value) else copy(slotB = value)
}