package io.github._514sid.gapless.internal.video

import android.view.TextureView
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.viewinterop.AndroidView

internal data class DualVideoPlayerState(
    val textureViewA: TextureView,
    val textureViewB: TextureView,
    val activeSlot: Int = 0,
    val aspectRatioA: Float? = null,
    val aspectRatioB: Float? = null,
)

/** Renders two A/B [TextureView] slots; only alpha differs so the hidden player keeps a live surface. */
@Composable
internal fun DualVideoPlayer(
    state: DualVideoPlayerState,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        VideoSlot(
            view = state.textureViewA,
            aspectRatio = state.aspectRatioA,
            isActive = state.activeSlot == 0,
        )
        VideoSlot(
            view = state.textureViewB,
            aspectRatio = state.aspectRatioB,
            isActive = state.activeSlot == 1,
        )
    }
}

@Composable
private fun VideoSlot(
    view: TextureView,
    aspectRatio: Float?,
    isActive: Boolean,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = if (isActive) 1f else 0f },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .then(
                    if (aspectRatio != null)
                        Modifier.aspectRatio(aspectRatio)
                    else
                        Modifier.fillMaxSize()
                )
                .clipToBounds()
        ) {
            AndroidView(
                factory = {
                    view.apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
