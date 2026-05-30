package io.github._514sid.gapless.internal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage

internal data class ImageSlotState(
    val model: Any?,
    val aspectRatio: Float = 1.77f
)

internal data class ImagePlayerState(
    val slotA: ImageSlotState? = null,
    val slotB: ImageSlotState? = null,
    val activeSlot: Int = 0
)

/**
 * Renders images using two A/B slots for seamless transitions.
 *
 * Only the active slot is visible. The inactive slot stays in the composition
 * so Coil can preload the next image while the current one is still showing.
 */
@Composable
internal fun ImagePlayer(
    state: ImagePlayerState,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        listOf(state.slotA, state.slotB).forEachIndexed { index, slot ->
            slot ?: return@forEachIndexed
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (index == state.activeSlot) {
                    AsyncImage(
                        model = slot.model,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.aspectRatio(slot.aspectRatio)
                    )
                }
            }
        }
    }
}
