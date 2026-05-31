package io.github._514sid.gapless.internal

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

internal data class ImageSlotState(
    val model: Any?
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
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        ImageSlot(
            slot = state.slotA,
            isActive = state.activeSlot == 0 && state.slotA != null
        )
        
        ImageSlot(
            slot = state.slotB,
            isActive = state.activeSlot == 1 && state.slotB != null
        )
    }
}

@Composable
private fun ImageSlot(
    slot: ImageSlotState?,
    isActive: Boolean
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
            model = slot?.model,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )
    }
}