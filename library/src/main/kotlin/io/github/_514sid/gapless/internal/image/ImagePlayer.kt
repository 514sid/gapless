package io.github._514sid.gapless.internal.image

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import android.graphics.drawable.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import coil3.asDrawable
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.gif.MovieDrawable
import coil3.gif.onAnimationEnd
import coil3.gif.repeatCount
import coil3.request.ImageRequest
import io.github._514sid.gapless.internal.Framing
import java.util.UUID

/**
 * @param loaded Whether the slot may load [model]. A staged image is held back until its preload has
 * decoded it, so the slot's own request is a cache hit instead of a second decode running alongside.
 */
internal data class ImageSlotState(
    val model: Any?,
    val playbackId: UUID? = null,
    val loaded: Boolean = true,
    val framing: Framing = Framing(),
    val animationLoops: Int? = null,
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
 *
 * @param visible Whether images are what the player shows; animations only run while they are.
 */
@Composable
internal fun ImagePlayer(
    state: ImagePlayerState,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    onDisplayError: (ImageSlotState, String) -> Unit = { _, _ -> },
    onDisplayed: (ImageSlotState) -> Unit = {},
    onAnimationEnd: (ImageSlotState) -> Unit = {},
) {
    Box(modifier = modifier) {
        ImageSlot(
            slot = state.slotA,
            isActive = visible && state.activeSlot == 0 && state.slotA != null,
            onDisplayError = onDisplayError,
            onDisplayed = onDisplayed,
            onAnimationEnd = onAnimationEnd,
        )

        ImageSlot(
            slot = state.slotB,
            isActive = visible && state.activeSlot == 1 && state.slotB != null,
            onDisplayError = onDisplayError,
            onDisplayed = onDisplayed,
            onAnimationEnd = onAnimationEnd,
        )
    }
}

/** The animated drawable a slot shows, once decoded; null for a still image. */
private class SlotAnimation {
    var drawable: Animatable? by mutableStateOf(null)
}

@Composable
private fun ImageSlot(
    slot: ImageSlotState?,
    isActive: Boolean,
    onDisplayError: (ImageSlotState, String) -> Unit,
    onDisplayed: (ImageSlotState) -> Unit,
    onAnimationEnd: (ImageSlotState) -> Unit,
) {
    val context = LocalPlatformContext.current
    val active by rememberUpdatedState(isActive)
    val animationEnded by rememberUpdatedState(onAnimationEnd)
    val loadedSlot = slot?.takeIf { it.loaded }
    val animation = remember(loadedSlot) { SlotAnimation() }
    val request = remember(loadedSlot, context) {
        loadedSlot?.let { s ->
            ImageRequest.Builder(context)
                .data(s.model)
                .repeatCount(s.animationLoops?.let { it - 1 } ?: MovieDrawable.REPEAT_INFINITE)
                .onAnimationEnd {
                    // Stopping an animation reports an end too. Only one that ran out on screen counts.
                    if (active && animation.drawable?.isRunning == false) animationEnded(s)
                }
                .build()
        }
    }

    // A staged animation would start as soon as it decodes, hidden, and be part way through (or,
    // with a loop count, done) by its cut. Hold it until it is on screen, then start it over.
    LaunchedEffect(animation.drawable, isActive) {
        val drawable = animation.drawable ?: return@LaunchedEffect
        drawable.stop()
        if (isActive) drawable.start()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = if (isActive) 1f else 0f
            }
            .background(Color(slot?.framing?.backgroundColor ?: Framing.BLACK)),
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = slot?.framing?.contentScale ?: ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
            onSuccess = { success ->
                animation.drawable = success.result.image.asDrawable(context.resources) as? Animatable
                if (slot != null) onDisplayed(slot)
            },
            onError = { error ->
                if (slot != null) {
                    val throwable = error.result.throwable
                    onDisplayError(slot, throwable.message ?: throwable.javaClass.simpleName)
                }
            },
        )
    }
}
