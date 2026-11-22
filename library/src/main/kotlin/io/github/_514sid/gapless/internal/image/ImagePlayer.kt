package io.github._514sid.gapless.internal.image

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import java.util.UUID

/**
 * @param loaded Whether the slot may load [model]. A staged image is held back until its preload has
 * decoded it, so the slot's own request is a cache hit instead of a second decode running alongside.
 */
internal data class ImageSlotState(
    val model: Any?,
    val playbackId: UUID? = null,
    val loaded: Boolean = true,
)

internal data class ImagePlayerState(
    val slotA: ImageSlotState? = null,
    val slotB: ImageSlotState? = null,
    val activeSlot: Int = 0
)

/**
 * Renders images using two A/B slots for seamless transitions.
 *
 * Slots are permanently kept in the composition tree to avoid heavy layout passes
 * when swapping from video to image playback.
 */
@Composable
internal fun ImagePlayer(
    state: ImagePlayerState,
    modifier: Modifier = Modifier,
    onDisplayError: (ImageSlotState, String) -> Unit = { _, _ -> },
    onDisplayed: (ImageSlotState) -> Unit = {},
) {
    Box(modifier = modifier) {
        ImageSlot(
            slot = state.slotA,
            isActive = state.activeSlot == 0 && state.slotA != null,
            onDisplayError = onDisplayError,
            onDisplayed = onDisplayed,
        )

        ImageSlot(
            slot = state.slotB,
            isActive = state.activeSlot == 1 && state.slotB != null,
            onDisplayError = onDisplayError,
            onDisplayed = onDisplayed,
        )
    }
}

@Composable
private fun ImageSlot(
    slot: ImageSlotState?,
    isActive: Boolean,
    onDisplayError: (ImageSlotState, String) -> Unit,
    onDisplayed: (ImageSlotState) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .graphicsLayer {
                alpha = if (isActive) 1f else 0f
            },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = slot?.takeIf { it.loaded }?.model,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
            onSuccess = { if (slot != null) onDisplayed(slot) },
            onError = { error ->
                if (slot != null) {
                    val throwable = error.result.throwable
                    onDisplayError(slot, throwable.message ?: throwable.javaClass.simpleName)
                }
            },
        )
    }
}