package io.github._514sid.gapless.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import io.github._514sid.gapless.GaplessAsset

@Composable
internal fun MediaSlot(
    asset: GaplessAsset?,
    generation: Long,
    isActive: Boolean,
    onError: (GaplessAsset, String) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = if (isActive) 1f else 0f
            }
            .zIndex(if (isActive) 1f else 0f),
        contentAlignment = Alignment.Center,
    ) {
        asset?.let {
            when {
                it.isVideo -> VideoSlot(it, generation, isActive, onError)
                it.isWeb -> WebSlot(it, isActive, onError)
                it.isImage -> ImageSlot(it, onError)
            }
        }
    }
}